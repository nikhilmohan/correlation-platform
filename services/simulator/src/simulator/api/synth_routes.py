"""HTTP synth trigger router — ``POST /synth/run`` + ``GET /synth/status`` (spec Task 26/27, AC 66-77).

``POST /synth/run`` validates the optional body (pydantic -> 422 on a bad param), then asks the
shared :class:`RunManager` to start a background P3 synth run (409 if one is already active), and
returns 202 ``{runId,status:"running"}`` before emission begins. ``GET /synth/status`` renders the
frozen status shape (idle/running, runId, progress, last-run summary). Both endpoints are on the
Simulator's own OpenAPI surface — no cross-service contract change, no new Kafka topic/payload.
"""

from __future__ import annotations

from fastapi import APIRouter, Response
from pydantic import BaseModel, ConfigDict, Field

from simulator.synth.run_manager import RunConflict, RunManager, RunOverrides


class SynthRunRequest(BaseModel):
    """Optional P3 knob overrides for a triggered run; absent fields use env/config defaults."""

    model_config = ConfigDict(extra="forbid")

    target: float | None = Field(default=None, ge=0.0, le=1.0)
    totalAlarms: int | None = Field(default=None, ge=1)
    seed: int | None = Field(default=None, ge=0)

    def to_overrides(self) -> RunOverrides:
        return RunOverrides(target=self.target, total_alarms=self.totalAlarms, seed=self.seed)


class SynthRunResponse(BaseModel):
    """202 body: the accepted run's id + running status."""

    runId: str
    status: str = "running"


class SynthConflictResponse(BaseModel):
    """409 body: the already-active run's id."""

    detail: str = "a synth run is already in progress"
    runId: str | None = None


class ProgressModel(BaseModel):
    alarmsEmitted: int = 0
    alarmsTotal: int = 0
    alignedEmitted: int = 0
    nonAlignedEmitted: int = 0


class SynthSummaryModel(BaseModel):
    runId: str
    status: str
    alarmsEmitted: int
    alignedFraction: float
    enrichmentSafeCount: int
    shortfallCascades: int
    enrichmentConflictPatterns: list[str] = []
    failureReason: str | None = None
    startedAt: str
    completedAt: str


class SynthStatusResponse(BaseModel):
    """Frozen ``GET /synth/status`` shape (top-level status is only idle/running)."""

    status: str
    runId: str | None = None
    progress: ProgressModel
    summary: SynthSummaryModel | None = None


def build_synth_router(run_manager: RunManager) -> APIRouter:
    """Build the synth router bound to the shared :class:`RunManager`."""
    router = APIRouter()

    @router.post(
        "/synth/run",
        status_code=202,
        response_model=SynthRunResponse,
        responses={
            202: {"model": SynthRunResponse},
            409: {"model": SynthConflictResponse},
            422: {"description": "Invalid override parameter"},
        },
    )
    def synth_run(request: SynthRunRequest | None = None) -> Response:
        req = request or SynthRunRequest()
        try:
            run_id = run_manager.start(req.to_overrides())
        except RunConflict as conflict:
            body = SynthConflictResponse(runId=conflict.active_run_id)
            return Response(
                content=body.model_dump_json(),
                media_type="application/json",
                status_code=409,
            )
        body = SynthRunResponse(runId=run_id, status="running")
        return Response(
            content=body.model_dump_json(),
            media_type="application/json",
            status_code=202,
        )

    @router.get("/synth/status", response_model=SynthStatusResponse)
    def synth_status() -> dict[str, object]:
        return run_manager.status().to_json()

    return router
