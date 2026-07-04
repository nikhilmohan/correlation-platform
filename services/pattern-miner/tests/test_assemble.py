"""3-stage pipeline assembly edge cases: empty run, provenance, trace propagation, metrics."""

from __future__ import annotations

from pattern_miner.assemble import group_transactions

from .helpers import (
    default_params,
    make_alarm,
    make_scenario,
    make_transaction,
    run_pipeline,
)

FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]


def _fiber_scenario():
    return make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER_CUT)


def _fiber_txns(n: int, snapshot_id: str = "snap-9"):
    return [
        make_transaction(
            trail_id=f"t{i}",
            snapshot_id=snapshot_id,
            alarms=[
                make_alarm(alarm_type=t, raised_offset_seconds=j) for j, t in enumerate(FIBER_CUT)
            ],
        )
        for i in range(n)
    ]


def test_empty_batch_yields_no_events():
    assert group_transactions([]) == []


def test_no_frequent_sequence_emits_nothing():
    """A run whose only cascade cannot reach minSupport emits no pattern (valid empty outcome)."""
    txn = make_transaction(
        trail_id="t", alarms=[make_alarm(alarm_type="A", raised_offset_seconds=0)]
    )
    params = default_params(min_support=1.1)  # impossible support -> nothing frequent
    envelopes = run_pipeline([txn], [_fiber_scenario()], params)
    assert envelopes == []


def test_source_window_id_stable_and_composite():
    """sourceWindowId is the composite session reference, deterministic per input+boundary."""
    params = default_params(min_support=0.5)
    txns = _fiber_txns(3)
    e1 = run_pipeline(txns, [_fiber_scenario()], params)
    e2 = run_pipeline(txns, [_fiber_scenario()], params)
    assert e1 and e2
    ids1 = sorted(env.payload.provenance.sourceWindowId for env in e1)
    ids2 = sorted(env.payload.provenance.sourceWindowId for env in e2)
    assert ids1 == ids2  # deterministic
    for env in e1:
        assert env.payload.provenance.sourceWindowId.startswith("sw:")
        assert env.payload.provenance.snapshotId == "snap-9"


def test_patterns_emitted_metric_increments():
    from pattern_miner.metrics import Metrics

    metrics = Metrics()
    envelopes = run_pipeline(
        _fiber_txns(3), [_fiber_scenario()], default_params(min_support=0.5), metrics=metrics
    )
    assert metrics.patterns_emitted._value.get() == len(envelopes)


def test_trace_id_propagated_to_envelope():
    envelopes = run_pipeline(_fiber_txns(3), [_fiber_scenario()], default_params(min_support=0.5))
    assert envelopes
    assert all(env.traceId == "trace-1" for env in envelopes)
