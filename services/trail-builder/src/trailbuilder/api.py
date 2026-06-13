"""FastAPI app: the trail-query + rebuild API, /health, /metrics, /openapi.json.

The three trail-query operations have FROZEN paths + response schemas (P1-G4,
P1-G10, P2-GAP-09). ``domain`` is strictly REQUIRED on ``getTrailsForObject`` and
``listTrails`` (clear **400** when missing/blank, no default — Q1 + Q7); a
``RequestValidationError`` handler maps a missing/blank required input to 400
(see ``_validation_handler``), leaving present-but-malformed inputs at 422.
"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from .api_models import (
    DependencyHealth,
    HealthResponse,
    ListTrailsResponse,
    RebuildRequest,
    TrailDetail,
    TrailMember,
    TrailsBuiltSummary,
    TrailsForObjectResponse,
    TrailSummary,
)
from .clients.errors import IntegrationError
from .container import Container
from .models import Trail, object_type_of
from .observability import QUERY_REQUESTS_TOTAL


def create_app(container: Container) -> FastAPI:
    """Build the FastAPI application bound to ``container``."""
    app = FastAPI(
        title="Trail Builder Service",
        version="0.1.0",
        description=(
            "Policy-bounded correlation trails. The trail-query operations "
            "(getTrailsForObject, getTrail, listTrails) are the frozen contract "
            "consumers build against."
        ),
        openapi_version="3.1.0",
    )
    app.state.container = container

    # Inputs whose absence/blankness is a contract-mandated 400 (vs. a generic
    # 422). ``missing`` => the required input was not supplied at all;
    # ``string_too_short`` => a required string was supplied blank (the required
    # string params carry ``min_length=1``).
    _MISSING_REQUIRED_KINDS = frozenset({"missing", "string_too_short"})

    @app.exception_handler(RequestValidationError)
    async def _validation_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        """Map a *missing/blank required input* to HTTP 400; everything else 422.

        The frozen spec (AC-16) and design (Q1 + Q7, API table) require a **400**
        when a strictly-required input is omitted or blank — ``domain`` on
        ``getTrailsForObject``/``listTrails``, ``snapshotId`` on ``listTrails``,
        and the ``snapshotId``/``domain`` body fields on ``rebuild``. A clear 400
        surfaces the bug at the caller; it is never silently defaulted. A
        *present-but-malformed* input (e.g. a ``limit`` out of bounds, or a
        non-typed ``managedObjectId``) is a different error class and keeps
        FastAPI's default 422. The two are told apart by the per-error ``type``
        Pydantic assigns.
        """
        errors = exc.errors()
        missing_required = bool(errors) and all(
            e.get("type") in _MISSING_REQUIRED_KINDS for e in errors
        )
        status = 400 if missing_required else 422
        return JSONResponse(status_code=status, content={"detail": jsonable_encoder(errors)})

    def get_container() -> Container:
        return app.state.container

    # NB: ``Depends(get_container)`` is supplied as a parameter *default* rather
    # than via a closure-local ``Annotated`` alias. Under
    # ``from __future__ import annotations`` FastAPI resolves a route's string
    # annotation against the module globals, where a closure-local alias is
    # invisible — so an aliased ``Container`` would be mis-read as a query param.
    # A default value is a runtime object and is unaffected by postponed
    # annotations, so the dependency is recognised reliably.

    @app.get(
        "/trails/by-object",
        response_model=TrailsForObjectResponse,
        operation_id="getTrailsForObject",
        tags=["trails"],
        responses={400: {"description": "Missing/blank required query parameter"}},
    )
    def get_trails_for_object(
        managedObjectId: Annotated[
            str, Query(min_length=1, description="Typed <objectType>:<id>.")
        ],
        domain: Annotated[
            str,
            Query(min_length=1, description="REQUIRED — the domain that scopes the trails."),
        ],
        c: Container = Depends(get_container),
    ) -> TrailsForObjectResponse:
        QUERY_REQUESTS_TOTAL.labels(op="getTrailsForObject").inc()
        # Validate the managedObjectId shape (422 on malformed).
        try:
            object_type_of(managedObjectId)
        except ValueError as exc:
            raise HTTPException(
                status_code=422, detail=f"malformed managedObjectId: {exc}"
            ) from exc
        trail_ids = c.repository.trail_ids_for_object(managedObjectId, domain)
        return TrailsForObjectResponse(
            managedObjectId=managedObjectId, domain=domain, trailIds=trail_ids
        )

    @app.get(
        "/trails",
        response_model=ListTrailsResponse,
        operation_id="listTrails",
        tags=["trails"],
        responses={400: {"description": "Missing/blank required query parameter"}},
    )
    def list_trails(
        snapshotId: Annotated[
            str, Query(min_length=1, description="The snapshot the trails were built from.")
        ],
        domain: Annotated[
            str,
            Query(min_length=1, description="REQUIRED — the domain that scopes the trails."),
        ],
        limit: Annotated[int | None, Query(ge=1, le=1000)] = None,
        offset: Annotated[int, Query(ge=0)] = 0,
        c: Container = Depends(get_container),
    ) -> ListTrailsResponse:
        QUERY_REQUESTS_TOTAL.labels(op="listTrails").inc()
        trails = c.repository.list_trails(snapshotId, domain, limit=limit, offset=offset)
        summaries = [
            TrailSummary(
                trailId=t.trail_id,
                domain=t.domain,
                memberCount=t.member_count,
                igpArea=t.igp_area,
                srlgGroup=t.srlg_group,
            )
            for t in trails
        ]
        return ListTrailsResponse(
            snapshotId=snapshotId, domain=domain, count=len(summaries), trails=summaries
        )

    @app.get(
        "/trails/{trailId}",
        response_model=TrailDetail,
        operation_id="getTrail",
        tags=["trails"],
        responses={404: {"description": "Unknown trailId"}},
    )
    def get_trail(trailId: str, c: Container = Depends(get_container)) -> TrailDetail:
        QUERY_REQUESTS_TOTAL.labels(op="getTrail").inc()
        trail = c.repository.get_trail(trailId)
        if trail is None:
            raise HTTPException(status_code=404, detail=f"unknown trailId {trailId!r}")
        return _to_detail(trail)

    @app.post(
        "/trails/rebuild",
        response_model=TrailsBuiltSummary,
        operation_id="rebuildTrails",
        tags=["trails"],
        responses={
            400: {"description": "Missing/blank required body field"},
            502: {"description": "Topology/Knowledge unavailable"},
        },
    )
    def rebuild(
        request: RebuildRequest,
        authorization: Annotated[str | None, Header()] = None,
        c: Container = Depends(get_container),
    ) -> TrailsBuiltSummary:
        _check_rebuild_auth(c, authorization)
        try:
            result = c.build_service.build(
                request.snapshotId, request.domain, trace_id="rebuild-api", emit=True
            )
        except IntegrationError as exc:
            raise HTTPException(
                status_code=502, detail=f"dependency unavailable: {exc.reason}"
            ) from exc
        return TrailsBuiltSummary(
            snapshotId=result.snapshot_id,
            domain=result.domain,
            trailIds=result.trail_ids,
            trailCount=result.trail_count,
        )

    @app.get("/health", response_model=HealthResponse, tags=["ops"])
    def health(response: Response, c: Container = Depends(get_container)) -> HealthResponse:
        deps = DependencyHealth(
            topology="ok" if c.topology.ping() else "degraded",
            knowledge="ok" if c.policy.ping() else "degraded",
            db=_db_health(c),
            kafka="ok",
        )
        ready = all(v == "ok" for v in deps.model_dump().values())
        if not ready:
            response.status_code = 503
        return HealthResponse(status="ok" if ready else "degraded", dependencies=deps)

    @app.get("/metrics", tags=["ops"])
    def metrics() -> Response:
        return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app


def _to_detail(trail: Trail) -> TrailDetail:
    members = [TrailMember(managedObjectId=m, objectType=object_type_of(m)) for m in trail.members]
    return TrailDetail(
        trailId=trail.trail_id,
        domain=trail.domain,
        snapshotId=trail.snapshot_id,
        members=members,
        memberCount=len(members),
        igpArea=trail.igp_area,
        srlgGroup=trail.srlg_group,
    )


def _check_rebuild_auth(container: Container, authorization: str | None) -> None:
    token = container.settings.rebuild_api_token
    if not token:
        return  # guard disabled (local/CI)
    expected = f"Bearer {token}"
    if authorization != expected:
        raise HTTPException(status_code=401, detail="invalid or missing bearer token")


def _db_health(container: Container) -> str:
    try:
        with container.engine.connect() as conn:
            conn.exec_driver_sql("SELECT 1")
        return "ok"
    except Exception:
        return "degraded"
