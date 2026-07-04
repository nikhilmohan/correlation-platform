"""Run-stats + observed-chatter recording (best-effort, off the emit critical path).

Both recorders run AFTER the ``TransactionEvent``s have been emitted; a DB failure is caught,
logged, metric-counted, and the pipeline continues (EH-11, EH-15) — emission never depends on
the DB. The data model is aggregate-only: ``nf_run_stats`` holds one row per finalized window
(counts + params), ``nf_observed_chatter`` holds one row per distinct chatter signature
(occurrence count + first/last seen). NO alarm payloads are stored.
"""

from __future__ import annotations

import math
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime

from acp_event_model import AlarmEvent

from .config import FeatureSettings, ModelParams
from .logging_setup import get_logger
from .repository import ObservedChatterRepository, RunStatsRepository

log = get_logger(__name__)


@dataclass
class RunStatsRow:
    """One aggregate run-stats record (maps 1:1 to a ``nf_run_stats`` row)."""

    run_id: str
    run_timestamp: datetime
    trail_id: str
    snapshot_id: str
    domain: str | None
    window_start: datetime
    window_end: datetime
    eps: float
    min_samples: int
    window_size_seconds: int
    algorithm: str
    alarms_in: int
    clusters_formed: int
    alarms_kept: int
    alarms_dropped: int
    noise_ratio: float
    storm_max_cluster_size: int | None
    storm_reduction_ratio: float | None
    retention_vs_oracle: float | None
    hop_feature_enabled: bool


@dataclass
class ChatterSignature:
    """One observed-noise/chatter signature (maps to a ``nf_observed_chatter`` row)."""

    managed_object_id: str | None
    alarm_type: str
    event_type: str
    trail_id: str | None
    occurrence_count: int = 1
    first_seen: datetime = field(default_factory=lambda: datetime.now(UTC))
    last_seen: datetime = field(default_factory=lambda: datetime.now(UTC))


def build_run_stats_row(
    *,
    trail_id: str,
    snapshot_id: str,
    domain: str | None,
    window_start: datetime,
    window_end: datetime,
    params: ModelParams,
    features: FeatureSettings,
    alarms_in: int,
    clusters_formed: int,
    alarms_kept: int,
    max_cluster_size: int | None,
    retention_vs_oracle: float | None = None,
) -> RunStatsRow:
    """Assemble a :class:`RunStatsRow` from the per-window aggregate counts."""
    alarms_dropped = alarms_in - alarms_kept
    noise_ratio = (alarms_dropped / alarms_in) if alarms_in > 0 else 0.0
    storm_reduction = (alarms_in / clusters_formed) if clusters_formed > 0 else None
    return RunStatsRow(
        run_id=str(uuid.uuid4()),
        run_timestamp=datetime.now(UTC),
        trail_id=trail_id,
        snapshot_id=snapshot_id,
        domain=domain,
        window_start=window_start,
        window_end=window_end,
        eps=params.eps,
        min_samples=params.min_samples,
        window_size_seconds=params.window_size_seconds,
        algorithm=params.algorithm,
        alarms_in=alarms_in,
        clusters_formed=clusters_formed,
        alarms_kept=alarms_kept,
        alarms_dropped=alarms_dropped,
        noise_ratio=noise_ratio,
        storm_max_cluster_size=max_cluster_size,
        storm_reduction_ratio=storm_reduction,
        retention_vs_oracle=retention_vs_oracle,
        hop_feature_enabled=features.hop_distance_enabled,
    )


class RunStatsRecorder:
    """Writes ONE run-stats row per window, best-effort (EH-11)."""

    def __init__(self, repository: RunStatsRepository, metrics=None) -> None:
        self._repo = repository
        self._metrics = metrics

    async def record(self, row: RunStatsRow) -> bool:
        """Persist ``row``; return ``True`` on success, ``False`` on a caught failure.

        Never raises — a DB failure is logged + counted and the pipeline continues.
        """
        try:
            await self._repo.insert_run(row)
            return True
        except Exception as exc:  # noqa: BLE001 — best-effort, never block emission
            if self._metrics is not None:
                self._metrics.stats_write_failures.inc()
            log.error("stats_write_failed", run_id=row.run_id, error=str(exc))
            return False


class ObservedChatterRecorder:
    """Upserts ONE signature per distinct ``(managedObjectId, alarmType, eventType, trailId)`` for
    every noise-labeled alarm in a window — counted ONCE per window (algorithm step F2). Best-effort
    (EH-15)."""

    def __init__(self, repository: ObservedChatterRepository, metrics=None) -> None:
        self._repo = repository
        self._metrics = metrics

    async def record(self, noise_alarms: list[AlarmEvent], *, trail_id: str | None) -> int:
        """Upsert distinct chatter signatures for the noise-labeled alarms.

        Returns the number of distinct signatures upserted. Never raises (EH-15).
        """
        distinct: dict[tuple[str | None, str, str, str | None], ChatterSignature] = {}
        for a in noise_alarms:
            key = (a.managedObjectId, a.alarmType, a.eventType, trail_id)
            if key not in distinct:
                distinct[key] = ChatterSignature(
                    managed_object_id=a.managedObjectId,
                    alarm_type=a.alarmType,  # pass-through mirror — never derived
                    event_type=a.eventType,
                    trail_id=trail_id,
                )
        if not distinct:
            return 0
        try:
            for sig in distinct.values():
                await self._repo.upsert_signature(sig)
            if self._metrics is not None:
                self._metrics.chatter_signatures_recorded.inc(len(distinct))
            return len(distinct)
        except Exception as exc:  # noqa: BLE001 — best-effort, never block emission
            if self._metrics is not None:
                self._metrics.chatter_write_failures.inc()
            log.error("chatter_write_failed", trail_id=trail_id, error=str(exc))
            return 0


def isclose(a: float, b: float, *, rel_tol: float = 1e-9, abs_tol: float = 1e-9) -> bool:
    """Floating-point tolerance helper (used by AC-11 assertions)."""
    return math.isclose(a, b, rel_tol=rel_tol, abs_tol=abs_tol)
