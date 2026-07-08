"""HTTP mine-corpus trigger router — ``POST /mine/run`` + ``GET /mine/status``.

``POST /mine/run`` validates the optional body (pydantic → 422 on a bad param), then asks the
shared :class:`MineRunManager` to start a background P2 corpus-generate run (409 if a mine OR synth
run is already active), and returns 202 ``{runId,status:"running"}`` before emission begins.
``GET /mine/status`` renders the frozen status shape (idle/running, runId, progress, last-run
summary). Both endpoints are on the Simulator's own OpenAPI surface — no cross-service contract
change, no new Kafka topic/payload (the P2 corpus reuses the existing ``alarms.history`` emit).
"""

from __future__ import annotations

from fastapi import APIRouter, Response
from pydantic import BaseModel, ConfigDict, Field

from simulator.synth.mine_run_manager import MineRunManager, MineRunOverrides
from simulator.synth.run_guard import RunConflict


class MineRunRequest(BaseModel):
    """Optional P2 corpus knob overrides for a triggered run; absent fields use env/config."""

    model_config = ConfigDict(extra="forbid")

    scenarioInstances: int | None = Field(default=None, ge=1)
    seed: int | None = Field(default=None, ge=0)

    def to_overrides(self) -> MineRunOverrides:
        return MineRunOverrides(scenario_instances=self.scenarioInstances, seed=self.seed)


class MineRunResponse(BaseModel):
    """202 body: the accepted run's id + running status."""

    runId: str
    status: str = "running"


class MineConflictResponse(BaseModel):
    """409 body: the already-active run's id (a mine or a synth run)."""

    detail: str = "a run is already in progress"
    runId: str | None = None


class MineProgressModel(BaseModel):
    alarmsEmitted: int = 0
    alarmsTotal: int = 0
    alignedEmitted: int = 0
    nonAlignedEmitted: int = 0


class MineSummaryModel(BaseModel):
    runId: str
    status: str
    alarmsEmitted: int
    failureReason: str | None = None
    startedAt: str
    completedAt: str


class MineStatusResponse(BaseModel):
    """Frozen ``GET /mine/status`` shape (top-level status is only idle/running)."""

    status: str
    runId: str | None = None
    progress: MineProgressModel
    summary: MineSummaryModel | None = None


def build_mine_router(mine_manager: MineRunManager) -> APIRouter:
    """Build the mine router bound to the shared :class:`MineRunManager`."""
    router = APIRouter()

    @router.post(
        "/mine/run",
        status_code=202,
        response_model=MineRunResponse,
        responses={
            202: {"model": MineRunResponse},
            409: {"model": MineConflictResponse},
            422: {"description": "Invalid override parameter"},
        },
    )
    def mine_run(request: MineRunRequest | None = None) -> Response:
        req = request or MineRunRequest()
        try:
            run_id = mine_manager.start(req.to_overrides())
        except RunConflict as conflict:
            body = MineConflictResponse(runId=conflict.active_run_id)
            return Response(
                content=body.model_dump_json(),
                media_type="application/json",
                status_code=409,
            )
        body = MineRunResponse(runId=run_id, status="running")
        return Response(
            content=body.model_dump_json(),
            media_type="application/json",
            status_code=202,
        )

    @router.get("/mine/status", response_model=MineStatusResponse)
    def mine_status() -> dict[str, object]:
        return mine_manager.status().to_json()

    return router
