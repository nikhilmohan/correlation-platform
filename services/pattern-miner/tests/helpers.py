"""Shared test builders: typed alarms, TransactionEvents, scenarios, and the 3-stage pipeline.

Test inputs are ``TransactionEvent``s with typed ``alarms[]`` populated inline (each of the six
required fields) — no resolver fake (alarm detail is in-band). Codebook fault-origin scenarios are
built inline as :class:`~pattern_miner.codebook.Scenario` fixtures (domain-agnostic — the alarm
tokens and scenario ids in fixtures are illustrative, never literals in source/config). The mining
pipeline is assembled against the pure-Python ``LocalPrefixSpanEngine`` (Spark is container-only); a
``spark``-marked test exercises the real Spark engine in-container.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from acp_event_model import Alarm, TransactionEvent, TypedEnvelope

from pattern_miner.anchoring import AnchorGrouper
from pattern_miner.assemble import ThreeStagePipeline, group_transactions
from pattern_miner.codebook import Scenario
from pattern_miner.config import (
    AnchoringParams,
    MiningParams,
    TempoProfile,
    WindowingParams,
)
from pattern_miner.metrics import Metrics
from pattern_miner.mining import GroupedMiner, PrefixSpanMiner
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine
from pattern_miner.timing import TimingComputer
from pattern_miner.windowing import SessionWindower

BASE = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)


def make_alarm(
    *,
    alarm_type: str,
    raised_offset_seconds: float,
    alarm_id: str | None = None,
    event_type: str = "communicationsAlarm",
    managed_object_id: str = "Port:1",
    perceived_severity: str = "critical",
) -> Alarm:
    """Build one typed ``Alarm`` (six required fields) at ``BASE + offset``."""
    return Alarm(
        alarmId=alarm_id or f"alarm-{uuid.uuid4().hex[:8]}",
        alarmType=alarm_type,
        eventType=event_type,
        raisedAt=BASE + timedelta(seconds=raised_offset_seconds),
        managedObjectId=managed_object_id,
        perceivedSeverity=perceived_severity,
    )


def make_transaction(
    *,
    trail_id: str,
    alarms: list[Alarm],
    snapshot_id: str = "snap-1",
    domain: str | None = "core-ip",
    transaction_id: str | None = None,
) -> TransactionEvent:
    """Build a ``TransactionEvent`` with the typed ``alarms[]`` (and mirrored ``alarmIds[]``)."""
    ordered = sorted(alarms, key=lambda a: (a.raisedAt, a.alarmId))
    return TransactionEvent(
        transactionId=transaction_id or f"txn-{uuid.uuid4().hex[:8]}",
        trailId=trail_id,
        snapshotId=snapshot_id,
        domain=domain,
        alarmIds=[a.alarmId for a in ordered],
        alarms=ordered,
        windowStart=ordered[0].raisedAt,
        windowEnd=ordered[-1].raisedAt,
    )


def wrap(txn: TransactionEvent, *, trace_id: str = "trace-1", event_id: str | None = None) -> dict:
    """Envelope a TransactionEvent as canonical wire dict (for the ingest router)."""
    envelope = TypedEnvelope(
        eventId=event_id or str(uuid.uuid4()),
        type="TransactionEvent",
        schemaVersion=1,
        occurredAt=BASE,
        source="noise-filter",
        traceId=trace_id,
        payload=txn,
    )
    return envelope.to_dict()


def make_scenario(
    *,
    scenario_id: str,
    symptom_chain: list[str],
    fault_origin_type: str = "FaultOrigin",
    fault_origin_object_id: str = "obj-1",
    trail_ids: list[str] | None = None,
) -> Scenario:
    """Build one Codebook fault-origin scenario fixture (values illustrative, never in source)."""
    return Scenario(
        scenario_id=scenario_id,
        fault_origin_object_id=fault_origin_object_id,
        fault_origin_type=fault_origin_type,
        symptom_chain=tuple(symptom_chain),
        trail_ids=tuple(trail_ids or []),
    )


def default_windowing(
    *,
    base_gap_seconds: float = 5.0,
    gap_multiplier: float = 3.0,
    tempo_percentile: float = 95.0,
    max_closing_gap_seconds: float = 120.0,
    min_burst_samples: int = 2,
    profiles: dict[str, float] | None = None,
    class_thresholds: dict[str, float] | None = None,
) -> WindowingParams:
    """WindowingParams mirroring the live Knowledge record (values injected, none hard-coded)."""
    prof = profiles if profiles is not None else {"fast": 0.5, "slow": 30.0, "default": 5.0}
    return WindowingParams(
        base_gap_seconds=base_gap_seconds,
        gap_multiplier=gap_multiplier,
        tempo_percentile=tempo_percentile,
        max_closing_gap_seconds=max_closing_gap_seconds,
        min_burst_samples=min_burst_samples,
        profiles={n: TempoProfile(name=n, floor_seconds=f) for n, f in prof.items()},
        class_thresholds=class_thresholds or {},
    )


def default_anchoring(
    *,
    match_confidence_threshold: float = 0.5,
    w_order: float = 0.7,
    w_jaccard: float = 0.3,
    scoring_method: str = "ordered_subsequence_jaccard",
    tie_break: str = "chain_length_then_scenario_id",
    grouping_keys: tuple[str, ...] = ("scenarioId",),
) -> AnchoringParams:
    """AnchoringParams mirroring the live Knowledge record (values injected, none hard-coded)."""
    return AnchoringParams(
        match_confidence_threshold=match_confidence_threshold,
        w_order=w_order,
        w_jaccard=w_jaccard,
        scoring_method=scoring_method,
        tie_break=tie_break,
        grouping_keys=grouping_keys,
    )


def default_params(
    *,
    min_support: float = 0.3,
    max_pattern_length: int = 10,
    max_sequence_count: int = 1000,
    codebook_version: str = "current",
    sample_max_alarms: int = 10,
    windowing: WindowingParams | None = None,
    anchoring: AnchoringParams | None = None,
) -> MiningParams:
    """A MiningParams mirroring the live Knowledge record.

    ``sample_max_alarms`` mirrors the Knowledge ``sample.maxAlarms`` cap (injected here, never a
    source/default-config literal); tests that assert the K-cap pass an explicit value.
    """
    return MiningParams(
        min_support=min_support,
        max_pattern_length=max_pattern_length,
        max_sequence_count=max_sequence_count,
        windowing=windowing or default_windowing(),
        anchoring=anchoring or default_anchoring(),
        codebook_version=codebook_version,
        sample_max_alarms=sample_max_alarms,
    )


def build_pipeline(*, windowing: WindowingParams | None = None, metrics: Metrics | None = None):
    """Assemble the 3-stage pipeline (windower + grouped PrefixSpan miner + timing) for tests."""
    m = metrics or Metrics()
    windower = SessionWindower(windowing or default_windowing(), metrics=m)
    grouped_miner = GroupedMiner(PrefixSpanMiner(LocalPrefixSpanEngine(), metrics=m), metrics=m)
    return ThreeStagePipeline(windower, grouped_miner, TimingComputer(), metrics=m)


def run_pipeline(
    transactions: list[TransactionEvent],
    scenarios: list[Scenario],
    params: MiningParams,
    *,
    metrics: Metrics | None = None,
):
    """Run the full Stage1->Stage2->Stage3 pipeline over transactions; return the envelopes."""
    pipeline = build_pipeline(windowing=params.windowing, metrics=metrics)
    batches = group_transactions([(t, "trace-1") for t in transactions])
    return pipeline.run(batches, scenarios, params)


def window_sessions(
    transactions: list[TransactionEvent], params: MiningParams, *, metrics: Metrics | None = None
):
    """Stage-1 only: window transactions into candidate cascade sessions (for anchoring tests)."""
    windower = SessionWindower(params.windowing, metrics=metrics)
    sessions = []
    for batch in group_transactions([(t, "trace-1") for t in transactions]):
        sessions.extend(
            windower.sessions_for_trail(
                batch.trail_id, batch.alarms, snapshot_id=batch.snapshot_id, domain=batch.domain
            )
        )
    return sessions


def group_sessions(sessions, scenarios: list[Scenario], params: MiningParams):
    """Stage-2 only: anchor + group candidate cascades (for anchoring assertions)."""
    return AnchorGrouper(scenarios, params.anchoring).group(sessions)
