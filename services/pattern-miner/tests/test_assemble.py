"""Assembler edge cases: empty result, multi-session sourceWindowId, no-emit paths."""

from __future__ import annotations

from pattern_miner.assemble import PatternAssembler, group_transactions
from pattern_miner.metrics import Metrics
from pattern_miner.mining import PrefixSpanMiner
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine
from pattern_miner.timing import TimingComputer
from pattern_miner.windowing import SessionWindower

from .helpers import default_params, default_windowing, make_alarm, make_transaction


def _assembler(metrics=None):
    m = metrics or Metrics()
    return PatternAssembler(
        SessionWindower(default_windowing(), metrics=m),
        PrefixSpanMiner(LocalPrefixSpanEngine(), metrics=m),
        TimingComputer(),
        metrics=m,
    )


def test_empty_batch_yields_no_events():
    batch = group_transactions([])
    assert batch == []


def test_no_frequent_sequence_emits_nothing():
    """A single trail with one alarm at very high minSupport yields no frequent sequence."""
    alarms = [make_alarm(alarm_type="A", raised_offset_seconds=0)]
    txn = make_transaction(trail_id="t", alarms=alarms)
    batch = group_transactions([(txn, "tr")])[0]
    # minSupport 1.0 with a single session still returns support-1 A; use a rarer construction.
    params = default_params(min_support=1.1)  # impossible support -> nothing frequent
    envelopes = _assembler().mine_batch(batch, params)
    assert envelopes == []


def test_source_window_id_stable_and_composite():
    """sourceWindowId is the composite session reference and is deterministic per input+boundary."""
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=i)
        for i, t in enumerate(["FiberFault", "LinkDown", "AdjDown"])
    ]
    txn = make_transaction(trail_id="t-src", alarms=alarms, snapshot_id="snap-9")
    batch = group_transactions([(txn, "tr")])[0]
    params = default_params(min_support=0.5)
    e1 = _assembler().mine_batch(batch, params)
    e2 = _assembler().mine_batch(batch, params)
    assert e1 and e2
    ids1 = sorted(env.payload.provenance.sourceWindowId for env in e1)
    ids2 = sorted(env.payload.provenance.sourceWindowId for env in e2)
    assert ids1 == ids2  # deterministic
    for env in e1:
        assert env.payload.provenance.sourceWindowId.startswith("sw:t-src:")
        assert env.payload.provenance.snapshotId == "snap-9"


def test_patterns_emitted_metric_increments():
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=i)
        for i, t in enumerate(["FiberFault", "LinkDown", "AdjDown"])
    ]
    txn = make_transaction(trail_id="t", alarms=alarms)
    batch = group_transactions([(txn, "tr")])[0]
    metrics = Metrics()
    envelopes = _assembler(metrics).mine_batch(batch, default_params(min_support=0.5))
    assert metrics.patterns_emitted._value.get() == len(envelopes)


def test_trace_id_propagated_to_envelope():
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=i)
        for i, t in enumerate(["FiberFault", "LinkDown", "AdjDown"])
    ]
    txn = make_transaction(trail_id="t", alarms=alarms)
    batch = group_transactions([(txn, "trace-propagate")])[0]
    envelopes = _assembler().mine_batch(batch, default_params(min_support=0.5))
    assert envelopes
    assert all(env.traceId == "trace-propagate" for env in envelopes)
