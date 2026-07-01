"""The storm-reduction pipeline core.

Given a finalized trail-window (the full enriched ``AlarmEvent``s), the pipeline:
vectorize -> DBSCAN -> emit dense clusters (drop noise) -> record run-stats + observed-chatter.
This is the unit-testable heart of the service, independent of Kafka. Snapshot/domain are
resolved from the trail context (Trail Builder ``getTrail``, DA-7); if unresolvable the window is
NOT emitted with a fabricated id (EH-8).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from acp_event_model import TypedEnvelope

from .clients import TrailBuilderClient, TrailContext
from .cluster import Clusterer
from .config import FeatureConfig, ParamStore
from .emit import TransactionEmitter
from .features import FeatureVectorizer
from .logging_setup import get_logger
from .stats import (
    ObservedChatterRecorder,
    RunStatsRecorder,
    build_run_stats_row,
)
from .windowing import TrailWindow

log = get_logger(__name__)


@dataclass
class WindowOutcome:
    """What a window produced: the emitted envelopes + the recorded run-stats row id."""

    events: list[TypedEnvelope]
    run_stats_written: bool
    chatter_signatures: int


class Pipeline:
    """Orchestrates the per-window storm-reduction flow."""

    def __init__(
        self,
        *,
        param_store: ParamStore,
        feature_config: FeatureConfig,
        vectorizer: FeatureVectorizer,
        clusterer: Clusterer,
        emitter: TransactionEmitter,
        run_stats_recorder: RunStatsRecorder,
        chatter_recorder: ObservedChatterRecorder,
        trail_builder_client: TrailBuilderClient | None = None,
        producer=None,
        metrics=None,
        oracle_valid_ids: dict[str, set[str]] | None = None,
    ) -> None:
        self._params = param_store
        self._features = feature_config
        self._vectorizer = vectorizer
        self._clusterer = clusterer
        self._emitter = emitter
        self._run_stats = run_stats_recorder
        self._chatter = chatter_recorder
        self._trail_builder = trail_builder_client
        self._producer = producer
        self._metrics = metrics
        # Optional Simulator-oracle valid alarmIds per trail (for retention_vs_oracle stat/tests).
        self._oracle_valid_ids = oracle_valid_ids or {}
        self._trail_ctx_cache: dict[str, TrailContext] = {}

    def _resolve_trail_ctx(self, trail_id: str) -> TrailContext | None:
        if trail_id in self._trail_ctx_cache:
            return self._trail_ctx_cache[trail_id]
        if self._trail_builder is None:
            return None
        try:
            ctx = self._trail_builder.get_trail(trail_id)
        except Exception as exc:  # noqa: BLE001 — handled per EH-8/EH-12 by callers
            log.warning("get_trail_failed", trail_id=trail_id, error=str(exc))
            return None
        self._trail_ctx_cache[trail_id] = ctx
        return ctx

    async def process_window(self, window: TrailWindow) -> WindowOutcome:
        """Run the full per-window flow and return its outcome."""
        params = self._params.get()
        features = self._features.get()
        # Buffer-and-sort (DA-3c): the real alarms.enriched topic is keyed by managedObjectId, so a
        # trail's alarms arrive out of event-time order across partitions. Order by raisedAt here so
        # the feature matrix + emitted alarms[]/alarmIds[] are deterministically event-time ordered.
        alarms = window.sorted_alarms()
        if self._metrics is not None:
            self._metrics.windows_finalized.inc()
            self._metrics.window_size_alarms.observe(len(alarms))

        trail_ctx = self._resolve_trail_ctx(window.trail_id)

        # snapshotId provenance (EH-8): never fabricate.
        snapshot_id = trail_ctx.snapshot_id if trail_ctx else None
        domain = trail_ctx.domain if trail_ctx else None
        if snapshot_id is None:
            if self._metrics is not None:
                self._metrics.snapshot_unresolved.inc()
            log.warning("snapshot_unresolved", trail_id=window.trail_id)
            return WindowOutcome(events=[], run_stats_written=False, chatter_signatures=0)

        matrix = self._vectorizer.build_matrix(
            alarms,
            window_start=window.window_start,
            features=features,
            trail_ctx=trail_ctx,
        )

        labels = self._clusterer.label(matrix, params)

        result = self._emitter.build_events(
            alarms,
            labels,
            trail_id=window.trail_id,
            snapshot_id=snapshot_id,
            window_start=window.window_start,
            window_end=window.window_end,
            domain=domain,
        )

        # Publish (the critical path) BEFORE any best-effort DB write.
        await self._publish(result.events)

        if not result.events and self._metrics is not None:
            self._metrics.windows_no_cluster.inc()
        if self._metrics is not None and result.clusters_formed > 0:
            self._metrics.storm_reduction_ratio.observe(result.alarms_in / result.clusters_formed)

        retention = self._retention_vs_oracle(window.trail_id, result)
        row = build_run_stats_row(
            trail_id=window.trail_id,
            snapshot_id=snapshot_id,
            domain=domain,
            window_start=window.window_start,
            window_end=window.window_end,
            params=params,
            features=features,
            alarms_in=result.alarms_in,
            clusters_formed=result.clusters_formed,
            alarms_kept=result.alarms_kept,
            max_cluster_size=result.max_cluster_size,
            retention_vs_oracle=retention,
        )
        # Best-effort run-stats write (EH-11) — off the emit critical path.
        written = await self._run_stats.record(row)
        # Best-effort observed-chatter upsert (EH-15) — off the emit critical path.
        sig_count = await self._chatter.record(result.noise_alarms, trail_id=window.trail_id)

        return WindowOutcome(
            events=result.events,
            run_stats_written=written,
            chatter_signatures=sig_count,
        )

    def _retention_vs_oracle(self, trail_id: str, result) -> float | None:
        valid = self._oracle_valid_ids.get(trail_id)
        if not valid:
            return None
        kept_ids = {aid for ev in result.events for aid in ev.payload.alarmIds}
        kept_valid = len(kept_ids & valid)
        return kept_valid / len(valid) if valid else None

    async def _publish(self, events: list[TypedEnvelope]) -> None:
        if self._producer is None:
            return
        for ev in events:
            try:
                self._producer.publish("transactions.clean", ev)
            except Exception as exc:  # noqa: BLE001
                if self._metrics is not None:
                    self._metrics.produce_failures.inc()
                log.error("produce_failed", error=str(exc))
                raise


def ceil_floor_helpers() -> tuple:
    """Expose math.ceil/floor for tests asserting AC-9/AC-16 thresholds."""
    return (math.ceil, math.floor)
