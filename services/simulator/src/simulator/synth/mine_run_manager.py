"""MineRunManager — thread-safe single-run state machine for the P2 mine-corpus trigger.

Mirrors :class:`simulator.synth.run_manager.RunManager` (the P3 synth trigger): it owns the
idle<->running state for ``POST /mine/run`` / ``GET /mine/status``, the background submission of the
EXISTING P2 corpus-generate pipeline (``p2_run.run_corpus``) onto a worker thread, and the
progress + summary bookkeeping read by ``GET /mine/status``. It shares the SAME :class:`RunGuard`
as the synth manager so a mine run and a synth run can never run concurrently (both drive the
simulator's single producer). It reuses the P2 generate path unchanged (emits the frozen
``AlarmEvent`` on ``alarms.history``) — no new synthesis logic and no contract change. The corpus
knobs default to env/config; the POST body may override ``scenarioInstances`` (corpus size) and
``seed`` (rng seed); every other knob is env-only.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import UTC, datetime

from simulator.config.settings import Settings
from simulator.integrations.producer import AlarmProducer
from simulator.obs.logging import get_logger, log_event
from simulator.synth.progress import ProgressSink, ProgressSnapshot
from simulator.synth.run_guard import RunConflict, RunGuard

__all__ = [
    "MineRunManager",
    "MineRunOverrides",
    "MineStatus",
    "MineStatusSummary",
    "RunConflict",
    "derive_mine_settings",
]

_log = get_logger("simulator.synth.mine_run_manager")

SettingsProvider = Callable[[], Settings]
ProducerFactory = Callable[[Settings], AlarmProducer]
# Runs the P2 corpus-generate pipeline; injected so tests can stub it without Kafka/Topology/TB.
RunCorpus = Callable[..., "object"]


@dataclass(frozen=True)
class MineRunOverrides:
    """The validated, optional POST-body overrides for a triggered mine run."""

    scenario_instances: int | None = None
    seed: int | None = None


@dataclass
class MineStatusSummary:
    """The last completed/failed mine run's summary (the ``summary`` object of the status shape)."""

    run_id: str
    status: str  # "completed" | "failed"
    alarms_emitted: int
    failure_reason: str | None
    started_at: str
    completed_at: str

    def to_json(self) -> dict[str, object]:
        return {
            "runId": self.run_id,
            "status": self.status,
            "alarmsEmitted": self.alarms_emitted,
            "failureReason": self.failure_reason,
            "startedAt": self.started_at,
            "completedAt": self.completed_at,
        }


@dataclass(frozen=True)
class MineStatus:
    """A consistent snapshot of the MineRunManager state (rendered by the GET handler)."""

    status: str  # "idle" | "running"
    run_id: str | None
    progress: ProgressSnapshot
    summary: MineStatusSummary | None

    def to_json(self) -> dict[str, object]:
        return {
            "status": self.status,
            "runId": self.run_id,
            "progress": self.progress.to_json(),
            "summary": self.summary.to_json() if self.summary is not None else None,
        }


def derive_mine_settings(base: Settings, overrides: MineRunOverrides) -> Settings:
    """Return a Settings copy pinned to the P2 generate path with the present overrides applied.

    A mine run ALWAYS drives the P2 corpus-generate pipeline (``phase='p2'``,
    ``sim_mode='generate'``) regardless of ambient env, then applies the optional body overrides:
    ``scenario_instances`` → ``SCENARIO_INSTANCES`` (corpus size), ``seed`` → ``SIM_SEED``. Every
    other corpus knob is env-only. ``base`` is never mutated.
    """
    changes: dict[str, object] = {"phase": "p2", "sim_mode": "generate"}
    if overrides.scenario_instances is not None:
        changes["scenario_instances"] = overrides.scenario_instances
    if overrides.seed is not None:
        changes["sim_seed"] = overrides.seed
    return base.model_copy(update=changes)


class MineRunManager:
    """Thread-safe single-run manager for the P2 mine-corpus trigger (shared-guard 409)."""

    def __init__(
        self,
        settings_provider: SettingsProvider,
        producer_factory: ProducerFactory,
        *,
        run_corpus: RunCorpus,
        guard: RunGuard | None = None,
    ) -> None:
        self._settings_provider = settings_provider
        self._producer_factory = producer_factory
        self._run_corpus = run_corpus
        # A shared RunGuard makes the mine run mutually exclusive with the P3 synth run.
        self._guard = guard or RunGuard()
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="mine-run")
        self._run_id: str | None = None
        self._progress = ProgressSink()
        self._summary: MineStatusSummary | None = None
        self._started_at: str | None = None

    def start(self, overrides: MineRunOverrides) -> str:
        """Accept a mine run (returns the new runId) or raise :class:`RunConflict` (handler → 409).

        The shared guard is acquired first: if a mine OR synth run is already active it raises
        :class:`RunConflict` with the active runId.
        """
        run_id = self._guard.acquire("mine")
        with self._lock:
            self._run_id = run_id
            self._progress = ProgressSink()
            self._started_at = datetime.now(tz=UTC).isoformat()
        self._executor.submit(self._run_wrapper, run_id, overrides, self._progress)
        log_event(_log, logging.INFO, "mine.run_accepted", "mine run accepted", runId=run_id)
        return run_id

    def status(self) -> MineStatus:
        """Return the frozen status shape (idle/running, runId, progress, summary).

        Only a mine run is reported as ``running`` here — an active SYNTH run (holding the shared
        guard) leaves ``/mine/status`` ``idle`` (its progress lives on ``/synth/status``).
        """
        guard_snap = self._guard.snapshot()
        with self._lock:
            run_id = self._run_id
            summary = self._summary
            progress = self._progress
        active = guard_snap.active and guard_snap.kind == "mine"
        return MineStatus(
            status="running" if active else "idle",
            run_id=run_id,
            progress=progress.snapshot(),
            summary=summary,
        )

    def _run_wrapper(
        self, run_id: str, overrides: MineRunOverrides, progress: ProgressSink
    ) -> None:
        started_at = self._started_at or datetime.now(tz=UTC).isoformat()
        summary: MineStatusSummary
        try:
            settings = derive_mine_settings(self._settings_provider(), overrides)
            producer = self._producer_factory(settings)
            outcome = self._run_corpus(settings, producer, run_id=run_id, progress=progress)
            emitted = int(getattr(outcome, "emitted", 0))
            summary = MineStatusSummary(
                run_id=run_id,
                status="completed",
                alarms_emitted=emitted,
                failure_reason=None,
                started_at=started_at,
                completed_at=datetime.now(tz=UTC).isoformat(),
            )
            log_event(
                _log,
                logging.INFO,
                "mine.run_complete",
                "mine corpus run complete",
                runId=run_id,
                alarmsEmitted=emitted,
            )
        except Exception as exc:  # runtime failure surfaces via status
            snap = progress.snapshot()
            summary = MineStatusSummary(
                run_id=run_id,
                status="failed",
                alarms_emitted=snap.alarmsEmitted,
                failure_reason=str(exc) or exc.__class__.__name__,
                started_at=started_at,
                completed_at=datetime.now(tz=UTC).isoformat(),
            )
            log_event(
                _log,
                logging.ERROR,
                "mine.run_failed",
                "mine corpus run failed",
                runId=run_id,
                error=str(exc),
            )
        finally:
            with self._lock:
                self._run_id = run_id
                self._summary = summary
            self._guard.release()
