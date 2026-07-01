"""Shared test builders: typed alarms, TransactionEvents, and the mining pipeline under test.

Test inputs are ``TransactionEvent``s with typed ``alarms[]`` populated inline (each of the six
required fields) — no resolver fake (alarm detail is in-band). The mining pipeline is assembled
against the pure-Python ``LocalPrefixSpanEngine`` (Spark is container-only); a ``spark``-marked
test exercises the real Spark engine in-container.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from acp_event_model import Alarm, TransactionEvent, TypedEnvelope

from pattern_miner.assemble import PatternAssembler, group_transactions
from pattern_miner.config import MiningParams, TempoProfile, WindowingParams
from pattern_miner.metrics import Metrics
from pattern_miner.mining import PrefixSpanMiner
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


def default_params(
    *,
    min_support: float = 0.3,
    max_pattern_length: int = 10,
    max_sequence_count: int = 1000,
    codebook_version: str = "current",
    windowing: WindowingParams | None = None,
) -> MiningParams:
    """A MiningParams mirroring the live Knowledge record."""
    return MiningParams(
        min_support=min_support,
        max_pattern_length=max_pattern_length,
        max_sequence_count=max_sequence_count,
        windowing=windowing or default_windowing(),
        codebook_version=codebook_version,
    )


def build_assembler(*, windowing: WindowingParams | None = None, metrics: Metrics | None = None):
    """Assemble the pipeline (windower + local PrefixSpan miner + timing) for tests."""
    m = metrics or Metrics()
    windower = SessionWindower(windowing or default_windowing(), metrics=m)
    miner = PrefixSpanMiner(LocalPrefixSpanEngine(), metrics=m)
    return PatternAssembler(windower, miner, TimingComputer(), metrics=m)


def mine_transactions(transactions: list[TransactionEvent], params: MiningParams, *, metrics=None):
    """Run the full window->mine->assemble pipeline over transactions; return envelopes."""
    assembler = build_assembler(windowing=params.windowing, metrics=metrics)
    envelopes = []
    for batch in group_transactions([(t, "trace-1") for t in transactions]):
        envelopes.extend(assembler.mine_batch(batch, params))
    return envelopes
