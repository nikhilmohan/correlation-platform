"""RunManager — thread-safe single-run state machine for the HTTP synth trigger (spec Task 26-29).

The RunManager owns the idle<->running state, the 409 concurrency guard, background submission of
the EXISTING P3 synth pipeline (``p3_run.run_synth``) onto a worker thread, and the progress +
summary bookkeeping read by ``GET /synth/status``. It reuses the P3 pipeline unchanged (emits the
frozen ``AlarmEvent`` on ``alarms.live``) — it introduces no new synthesis logic and no contract
change. All P3 knobs default to env/config; the POST body may override ``target``/``totalAlarms``/
``seed`` (mapped onto a derived ``Settings``); every other knob is env-only.
"""

from __future__ import annotations

import logging
import threading
import uuid
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import UTC, datetime

from simulator.config.settings import Settings
from simulator.integrations.producer import AlarmProducer
from simulator.obs.logging import get_logger, log_event
from simulator.synth.progress import ProgressSink, ProgressSnapshot

_log = get_logger("simulator.synth.run_manager")

# Callables the RunManager is constructed with (dependency seams for fast, broker-free unit tests).
SettingsProvider = Callable[[], Settings]
ProducerFactory = Callable[[Settings], AlarmProducer]
# Runs the synth pipeline; injected so tests can stub the run without Kafka/PM/TB/Topology.
RunSynth = Callable[..., "object"]


@dataclass(frozen=True)
class RunOverrides:
    """The validated, optional POST-body overrides for a triggered run (Task 29)."""

    target: float | None = None
    total_alarms: int | None = None
    seed: int | None = None


@dataclass
class SynthStatusSummary:
    """The last completed/failed run's summary (the ``summary`` object of the status shape)."""

    run_id: str
    status: str  # "completed" | "failed"
    alarms_emitted: int
    aligned_fraction: float
    enrichment_safe_count: int
    shortfall_cascades: int
    enrichment_conflict_patterns: list[str]
    failure_reason: str | None
    started_at: str
    completed_at: str

    def to_json(self) -> dict[str, object]:
        return {
            "runId": self.run_id,
            "status": self.status,
            "alarmsEmitted": self.alarms_emitted,
            "alignedFraction": self.aligned_fraction,
            "enrichmentSafeCount": self.enrichment_safe_count,
            "shortfallCascades": self.shortfall_cascades,
            "enrichmentConflictPatterns": list(self.enrichment_conflict_patterns),
            "failureReason": self.failure_reason,
            "startedAt": self.started_at,
            "completedAt": self.completed_at,
        }


@dataclass(frozen=True)
class SynthStatus:
    """A consistent snapshot of the RunManager state (rendered by the GET handler)."""

    status: str  # "idle" | "running"
    run_id: str | None
    progress: ProgressSnapshot
    summary: SynthStatusSummary | None

    def to_json(self) -> dict[str, object]:
        return {
            "status": self.status,
            "runId": self.run_id,
            "progress": self.progress.to_json(),
            "summary": self.summary.to_json() if self.summary is not None else None,
        }


class RunConflict(RuntimeError):
    """Raised by :meth:`RunManager.start` when a run is already active (handler -> 409)."""

    def __init__(self, active_run_id: str | None) -> None:
        super().__init__("a synth run is already in progress")
        self.active_run_id = active_run_id


def derive_settings(base: Settings, overrides: RunOverrides) -> Settings:
    """Return a Settings copy with the present overrides applied; absent fields keep env defaults.

    ``target`` -> ``p3_auto_correlation_target`` (network-wide auto-correlation target),
    ``total_alarms`` -> ``p3_total_alarms``, ``seed`` -> ``p3_rng_seed``. All other P3 knobs are
    env-only (OQ-TRIGGER-5).
    """
    changes: dict[str, object] = {}
    if overrides.target is not None:
        changes["p3_auto_correlation_target"] = overrides.target
        # A target override implies the network-wide closed-loop path (that is what target drives).
        changes["p3_network_wide"] = True
    if overrides.total_alarms is not None:
        changes["p3_total_alarms"] = overrides.total_alarms
    if overrides.seed is not None:
        changes["p3_rng_seed"] = overrides.seed
    if not changes:
        return base
    return base.model_copy(update=changes)


class RunManager:
    """Thread-safe single-run manager: 409 guard, background worker, progress + summary."""

    def __init__(
        self,
        settings_provider: SettingsProvider,
        producer_factory: ProducerFactory,
        *,
        run_synth: RunSynth,
        on_labels: Callable[[object], None] | None = None,
    ) -> None:
        self._settings_provider = settings_provider
        self._producer_factory = producer_factory
        self._run_synth = run_synth
        self._on_labels = on_labels
        self._lock = threading.Lock()
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="synth-run")
        self._active = False
        self._run_id: str | None = None
        self._progress = ProgressSink()
        self._summary: SynthStatusSummary | None = None
        self._started_at: str | None = None

    def start(self, overrides: RunOverrides) -> str:
        """Accept a run (returns the new runId) or raise :class:`RunConflict` (handler -> 409)."""
        with self._lock:
            if self._active:
                raise RunConflict(active_run_id=self._run_id)
            run_id = str(uuid.uuid4())
            self._active = True
            self._run_id = run_id
            self._progress = ProgressSink()
            self._started_at = datetime.now(tz=UTC).isoformat()
        self._executor.submit(self._run_wrapper, run_id, overrides, self._progress)
        log_event(_log, logging.INFO, "synth.run_accepted", "synth run accepted", runId=run_id)
        return run_id

    def status(self) -> SynthStatus:
        """Return the frozen status shape (idle/running, runId, progress, summary)."""
        with self._lock:
            active = self._active
            run_id = self._run_id
            summary = self._summary
            progress = self._progress
        return SynthStatus(
            status="running" if active else "idle",
            run_id=run_id,
            progress=progress.snapshot(),
            summary=summary,
        )

    def _run_wrapper(
        self, run_id: str, overrides: RunOverrides, progress: ProgressSink
    ) -> None:
        started_at = self._started_at or datetime.now(tz=UTC).isoformat()
        summary: SynthStatusSummary
        try:
            settings = derive_settings(self._settings_provider(), overrides)
            producer = self._producer_factory(settings)
            outcome = self._run_synth(
                settings, producer, run_id=run_id, progress=progress
            )
            summary = self._summary_from_outcome(run_id, outcome, started_at)
            if self._on_labels is not None:
                self._on_labels(getattr(outcome, "labels", None))
            log_event(
                _log,
                logging.INFO,
                "synth.run_complete",
                "synth run complete",
                runId=run_id,
                alarmsEmitted=summary.alarms_emitted,
            )
        except Exception as exc:  # runtime failure surfaces via status (AC 72)
            snap = progress.snapshot()
            summary = SynthStatusSummary(
                run_id=run_id,
                status="failed",
                alarms_emitted=snap.alarmsEmitted,
                aligned_fraction=0.0,
                enrichment_safe_count=0,
                shortfall_cascades=0,
                enrichment_conflict_patterns=[],
                failure_reason=str(exc) or exc.__class__.__name__,
                started_at=started_at,
                completed_at=datetime.now(tz=UTC).isoformat(),
            )
            log_event(
                _log,
                logging.ERROR,
                "synth.run_failed",
                "synth run failed",
                runId=run_id,
                error=str(exc),
            )
        finally:
            with self._lock:
                self._active = False
                self._run_id = run_id
                self._summary = summary

    @staticmethod
    def _summary_from_outcome(
        run_id: str, outcome: object, started_at: str
    ) -> SynthStatusSummary:
        run_summary = getattr(outcome, "summary", None)
        emitted = int(getattr(outcome, "emitted", 0))
        return SynthStatusSummary(
            run_id=run_id,
            status="completed",
            alarms_emitted=emitted,
            aligned_fraction=float(getattr(run_summary, "aligned_fraction", 0.0)),
            enrichment_safe_count=int(getattr(run_summary, "enrichment_safe_count", 0)),
            shortfall_cascades=int(getattr(run_summary, "shortfall_cascades", 0)),
            enrichment_conflict_patterns=list(
                getattr(run_summary, "enrichment_conflict_patterns", []) or []
            ),
            failure_reason=None,
            started_at=started_at,
            completed_at=datetime.now(tz=UTC).isoformat(),
        )
