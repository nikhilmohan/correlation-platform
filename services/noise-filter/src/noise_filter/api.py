"""FastAPI read-only HTTP surface: run-stats + observed-chatter read API, plus /health, /metrics.

The published OpenAPI 3.1 doc at ``/openapi.json`` is the single source of truth for this surface
(checked into ``services/noise-filter/openapi.json``). All endpoints are GET-only — run-stats rows
and chatter signatures are written exclusively by the pipeline (AC-22, EH-16). Read errors when the
store is unreachable return 503 (EH-13, EH-17); invalid query params return 422 (EH-14).
"""

from __future__ import annotations

from datetime import datetime

from fastapi import FastAPI, HTTPException, Query, Response
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, generate_latest
from pydantic import BaseModel, Field

from .repository import (
    ObservedChatterRepository,
    RepositoryUnavailable,
    RunStatsRepository,
)
from .stats import ChatterSignature, RunStatsRow


# --------------------------------------------------------------------------- #
# Response models (drive the published OpenAPI 3.1)                            #
# --------------------------------------------------------------------------- #
class RunStatsRowModel(BaseModel):
    runId: str
    runTimestamp: datetime
    trailId: str
    snapshotId: str
    domain: str | None = None
    windowStart: datetime
    windowEnd: datetime
    eps: float
    minSamples: int
    windowSize: int
    algorithm: str
    alarmsIn: int
    clustersFormed: int
    alarmsKept: int
    alarmsDropped: int
    noiseRatio: float
    stormMaxClusterSize: int | None = None
    stormReductionRatio: float | None = None
    retentionVsOracle: float | None = None
    hopFeatureEnabled: bool

    @classmethod
    def from_row(cls, r: RunStatsRow) -> RunStatsRowModel:
        return cls(
            runId=r.run_id,
            runTimestamp=r.run_timestamp,
            trailId=r.trail_id,
            snapshotId=r.snapshot_id,
            domain=r.domain,
            windowStart=r.window_start,
            windowEnd=r.window_end,
            eps=r.eps,
            minSamples=r.min_samples,
            windowSize=r.window_size_seconds,
            algorithm=r.algorithm,
            alarmsIn=r.alarms_in,
            clustersFormed=r.clusters_formed,
            alarmsKept=r.alarms_kept,
            alarmsDropped=r.alarms_dropped,
            noiseRatio=r.noise_ratio,
            stormMaxClusterSize=r.storm_max_cluster_size,
            stormReductionRatio=r.storm_reduction_ratio,
            retentionVsOracle=r.retention_vs_oracle,
            hopFeatureEnabled=r.hop_feature_enabled,
        )


class RunStatsPage(BaseModel):
    items: list[RunStatsRowModel]
    total: int
    limit: int
    offset: int


class ObservedChatterSignatureModel(BaseModel):
    managedObjectId: str | None = None
    alarmType: str
    eventType: str
    trailId: str | None = None
    occurrenceCount: int
    firstSeen: datetime
    lastSeen: datetime

    @classmethod
    def from_sig(cls, s: ChatterSignature) -> ObservedChatterSignatureModel:
        return cls(
            managedObjectId=s.managed_object_id,
            alarmType=s.alarm_type,
            eventType=s.event_type,
            trailId=s.trail_id,
            occurrenceCount=s.occurrence_count,
            firstSeen=s.first_seen,
            lastSeen=s.last_seen,
        )


class ObservedChatterPage(BaseModel):
    items: list[ObservedChatterSignatureModel]
    total: int
    limit: int
    offset: int


class ErrorModel(BaseModel):
    code: str
    message: str


class HealthModel(BaseModel):
    status: str
    store: str = Field(description="run-stats store: ok | degraded")


# --------------------------------------------------------------------------- #
# App state container                                                          #
# --------------------------------------------------------------------------- #
class ApiState:
    """Holds the repositories + readiness flag the API depends on."""

    def __init__(
        self,
        *,
        run_stats_repo: RunStatsRepository,
        chatter_repo: ObservedChatterRepository,
        metrics_registry: CollectorRegistry,
        max_limit: int = 500,
    ) -> None:
        self.run_stats_repo = run_stats_repo
        self.chatter_repo = chatter_repo
        self.metrics_registry = metrics_registry
        self.max_limit = max_limit
        self.params_loaded = False
        self.kafka_connected = False
        self.metrics = None  # optional Metrics instance for read-error counting


def create_app(state: ApiState) -> FastAPI:
    """Build the FastAPI app bound to ``state`` (repositories + readiness)."""
    app = FastAPI(
        title="Noise Filter — run-stats read API",
        version="1.0.0",
        description="Read-only run-stats + observed-chatter signature API (Phase-2 telemetry).",
    )
    app.state.api = state

    def _clamp_limit(limit: int) -> int:
        return min(limit, state.max_limit)

    def _count_read_error() -> None:
        if state.metrics is not None:
            state.metrics.runstats_read_errors.inc()

    @app.get("/health", response_model=HealthModel)
    async def health() -> Response:
        ready = state.params_loaded and state.kafka_connected
        store_status = "ok"
        try:
            await state.run_stats_repo.list_runs(limit=1)
        except RepositoryUnavailable:
            store_status = "degraded"
        body = HealthModel(status="ok" if ready else "not-ready", store=store_status)
        code = 200 if ready else 503
        return Response(
            content=body.model_dump_json(), media_type="application/json", status_code=code
        )

    @app.get("/metrics")
    async def metrics() -> Response:
        data = generate_latest(state.metrics_registry)
        return Response(content=data, media_type=CONTENT_TYPE_LATEST)

    @app.get("/api/v1/run-stats", response_model=RunStatsPage)
    async def list_run_stats(
        trailId: str | None = Query(default=None),
        from_: datetime | None = Query(default=None, alias="from"),
        to: datetime | None = Query(default=None),
        limit: int = Query(default=50, ge=1, le=500),
        offset: int = Query(default=0, ge=0),
    ) -> RunStatsPage:
        try:
            rows, total = await state.run_stats_repo.list_runs(
                trail_id=trailId,
                from_ts=from_,
                to_ts=to,
                limit=_clamp_limit(limit),
                offset=offset,
            )
        except RepositoryUnavailable as exc:
            _count_read_error()
            raise HTTPException(status_code=503, detail="run-stats store unreachable") from exc
        return RunStatsPage(
            items=[RunStatsRowModel.from_row(r) for r in rows],
            total=total,
            limit=_clamp_limit(limit),
            offset=offset,
        )

    @app.get("/api/v1/run-stats/{run_id}", response_model=RunStatsRowModel)
    async def get_run_stats(run_id: str) -> RunStatsRowModel:
        try:
            row = await state.run_stats_repo.get_run(run_id)
        except RepositoryUnavailable as exc:
            _count_read_error()
            raise HTTPException(status_code=503, detail="run-stats store unreachable") from exc
        if row is None:
            raise HTTPException(status_code=404, detail="run-stats row not found")
        return RunStatsRowModel.from_row(row)

    @app.get("/api/v1/observed-chatter", response_model=ObservedChatterPage)
    async def list_observed_chatter(
        alarmType: str | None = Query(default=None),
        trailId: str | None = Query(default=None),
        minOccurrence: int = Query(default=1, ge=1),
        limit: int = Query(default=50, ge=1, le=500),
        offset: int = Query(default=0, ge=0),
    ) -> ObservedChatterPage:
        try:
            sigs, total = await state.chatter_repo.list_signatures(
                alarm_type=alarmType,
                trail_id=trailId,
                min_occurrence=minOccurrence,
                limit=_clamp_limit(limit),
                offset=offset,
            )
        except RepositoryUnavailable as exc:
            _count_read_error()
            raise HTTPException(
                status_code=503, detail="observed-chatter store unreachable"
            ) from exc
        return ObservedChatterPage(
            items=[ObservedChatterSignatureModel.from_sig(s) for s in sigs],
            total=total,
            limit=_clamp_limit(limit),
            offset=offset,
        )

    return app
