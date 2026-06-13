"""FastAPI read API (spec task 9 / criteria 8, 13, 16, 18-24).

Read-only — this service is the sole writer (via the Kafka pipeline). Publishes OpenAPI 3.1
at ``/openapi.json`` (Swagger UI at ``/docs``); both the native ``/scenarios`` endpoint and
the CE ``/trail-signatures`` projection are in it. ``/codebooks`` dispatches on the query
param (``snapshotId`` xor ``domain``). Every codebook response carries ``domain``.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import Depends, FastAPI, HTTPException, Query, Request, Response
from pydantic import BaseModel, Field

from .logging_config import SERVICE_NAME
from .metrics import render_latest
from .models import PredictedSymptom, Scenario
from .projection import TrailScenarioSignature, project_codebook
from .store import CodebookStore


# --- Response models (drive the published OpenAPI) ---
class CodebookMeta(BaseModel):
    """Codebook metadata response."""

    codebookId: str
    snapshotId: str
    domain: str
    scenarioCount: int
    knowledgeVersion: str | None = None
    compiledAt: datetime | None = None
    active: bool | None = None


class ScenarioOut(BaseModel):
    """A scenario in the native scenarios view."""

    scenarioId: str
    faultOriginObjectId: str
    faultOriginType: str
    predictedSymptoms: list[PredictedSymptom] = Field(default_factory=list)
    trailIds: list[str] = Field(default_factory=list)


class ScenarioListResponse(BaseModel):
    """Native scenarios listing for a codebook."""

    codebookId: str
    domain: str
    scenarios: list[ScenarioOut] = Field(default_factory=list)


class TrailSignaturesResponse(BaseModel):
    """CE-oriented per-trail signatures projection for a codebook."""

    codebookId: str
    domain: str
    trailSignatures: list[TrailScenarioSignature] = Field(default_factory=list)


class CodebookListResponse(BaseModel):
    """Codebook listing keyed by the filter param used."""

    domain: str | None = None
    snapshotId: str | None = None
    codebooks: list[CodebookMeta] = Field(default_factory=list)


class ErrorResponse(BaseModel):
    """Structured error body."""

    error: str
    detail: str


def _meta_to_model(meta: dict[str, Any]) -> CodebookMeta:
    return CodebookMeta(
        codebookId=meta["codebook_id"],
        snapshotId=meta["snapshot_id"],
        domain=meta["domain"],
        scenarioCount=meta["scenario_count"],
        knowledgeVersion=meta.get("knowledge_version"),
        compiledAt=meta.get("compiled_at"),
        active=meta.get("active"),
    )


def _scenario_to_out(scenario: Scenario) -> ScenarioOut:
    return ScenarioOut(
        scenarioId=scenario.scenarioId,
        faultOriginObjectId=scenario.faultOriginObjectId,
        faultOriginType=scenario.faultOriginType,
        predictedSymptoms=scenario.predictedSymptoms,
        trailIds=scenario.trailIds,
    )


def get_store(request: Request) -> CodebookStore:
    """Dependency: the store bound to app state (overridable in tests)."""
    store = getattr(request.app.state, "store", None)
    if store is None:  # pragma: no cover - guarded by startup
        raise HTTPException(status_code=503, detail="store not initialized")
    return store


def create_app(store: CodebookStore | None = None) -> FastAPI:
    """Build the FastAPI app, optionally with a pre-bound store (tests)."""
    app = FastAPI(
        title="Codebook Generator API",
        version="0.1.0",
        description="Read API for compiled forward-propagation codebooks (domain-scoped).",
        openapi_version="3.1.0",
    )
    if store is not None:
        app.state.store = store

    @app.get("/health")
    def health(store: CodebookStore = Depends(get_store)) -> dict[str, str]:
        if not store.ping():
            raise HTTPException(status_code=503, detail="database not ready")
        return {"status": "ok", "service": SERVICE_NAME}

    @app.get("/metrics")
    def metrics() -> Response:
        return Response(content=render_latest(), media_type="text/plain; version=0.0.4")

    @app.get(
        "/codebooks",
        response_model=CodebookListResponse,
        responses={400: {"model": ErrorResponse}},
    )
    def list_codebooks(
        snapshotId: str | None = Query(default=None),
        domain: str | None = Query(default=None),
        store: CodebookStore = Depends(get_store),
    ) -> CodebookListResponse:
        if domain:
            metas = store.list_by_domain(domain)
            return CodebookListResponse(domain=domain, codebooks=[_meta_to_model(m) for m in metas])
        if snapshotId:
            metas = store.list_by_snapshot(snapshotId)
            return CodebookListResponse(
                snapshotId=snapshotId, codebooks=[_meta_to_model(m) for m in metas]
            )
        raise HTTPException(
            status_code=400,
            detail="exactly one of 'domain' or 'snapshotId' query parameters is required",
        )

    @app.get(
        "/codebooks/active",
        response_model=CodebookMeta,
        responses={404: {"model": ErrorResponse}},
    )
    def get_active(
        domain: str = Query(),
        snapshotId: str = Query(),
        store: CodebookStore = Depends(get_store),
    ) -> CodebookMeta:
        meta = store.get_active(domain, snapshotId)
        if meta is None:
            raise HTTPException(
                status_code=404,
                detail=f"no active codebook for domain={domain} snapshotId={snapshotId}",
            )
        return _meta_to_model(meta)

    @app.get(
        "/codebooks/{codebookId}",
        response_model=CodebookMeta,
        responses={404: {"model": ErrorResponse}},
    )
    def get_codebook(codebookId: str, store: CodebookStore = Depends(get_store)) -> CodebookMeta:
        meta = store.get_codebook_meta(codebookId)
        if meta is None:
            raise HTTPException(status_code=404, detail=f"unknown codebookId {codebookId}")
        return _meta_to_model(meta)

    @app.get(
        "/codebooks/{codebookId}/scenarios",
        response_model=ScenarioListResponse,
        responses={404: {"model": ErrorResponse}},
    )
    def get_scenarios(
        codebookId: str,
        faultOriginType: str | None = Query(default=None),
        store: CodebookStore = Depends(get_store),
    ) -> ScenarioListResponse:
        meta = store.get_codebook_meta(codebookId)
        if meta is None:
            raise HTTPException(status_code=404, detail=f"unknown codebookId {codebookId}")
        scenarios = store.get_scenarios(codebookId, faultOriginType)
        return ScenarioListResponse(
            codebookId=codebookId,
            domain=meta["domain"],
            scenarios=[_scenario_to_out(s) for s in scenarios],
        )

    @app.get(
        "/codebooks/{codebookId}/trail-signatures",
        response_model=TrailSignaturesResponse,
        responses={404: {"model": ErrorResponse}},
    )
    def get_trail_signatures(
        codebookId: str,
        trailId: str | None = Query(default=None),
        store: CodebookStore = Depends(get_store),
    ) -> TrailSignaturesResponse:
        meta = store.get_codebook_meta(codebookId)
        if meta is None:
            raise HTTPException(status_code=404, detail=f"unknown codebookId {codebookId}")
        scenarios = store.get_scenarios(codebookId)
        signatures = project_codebook(scenarios, trailId)
        return TrailSignaturesResponse(
            codebookId=codebookId,
            domain=meta["domain"],
            trailSignatures=signatures,
        )

    @app.get(
        "/codebooks/{codebookId}/scenarios/{scenarioId}",
        response_model=ScenarioOut,
        responses={404: {"model": ErrorResponse}},
    )
    def get_scenario(
        codebookId: str, scenarioId: str, store: CodebookStore = Depends(get_store)
    ) -> ScenarioOut:
        scenario = store.get_scenario(codebookId, scenarioId)
        if scenario is None:
            raise HTTPException(
                status_code=404,
                detail=f"unknown scenarioId {scenarioId} in codebook {codebookId}",
            )
        return _scenario_to_out(scenario)

    return app


def _build_runtime_app() -> FastAPI:
    """Build the app for ``uvicorn codebook_generator.api:app`` (binds a store from env)."""
    from .config import load_settings
    from .logging_config import configure_logging

    settings = load_settings()
    configure_logging(settings.log_level)
    settings.require_database_url()
    return create_app(CodebookStore.from_url(settings.database_url))


def __getattr__(name: str) -> Any:  # pragma: no cover - lazy uvicorn entrypoint
    """Lazily build the module-level ``app`` only when imported by an ASGI server."""
    if name == "app":
        return _build_runtime_app()
    raise AttributeError(name)
