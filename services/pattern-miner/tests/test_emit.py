"""Emit: one PatternMinedEvent per sequence produced to patterns.mined (serialized wire JSON)."""

from __future__ import annotations

import json

import pytest
from acp_event_model import deserialize

from pattern_miner.emit import PatternEmitter
from pattern_miner.metrics import Metrics

from .helpers import default_params, make_alarm, make_scenario, make_transaction, run_pipeline


class _FakeProducer:
    def __init__(self):
        self.published = []

    def publish(self, topic, envelope):
        self.published.append((topic, envelope))


class _FailingProducer:
    """Publishes ``fail_after`` envelopes, then raises on the next produce."""

    def __init__(self, fail_after=0):
        self.published = []
        self._fail_after = fail_after

    def publish(self, topic, envelope):
        if len(self.published) >= self._fail_after:
            raise RuntimeError("broker unavailable")
        self.published.append((topic, envelope))


FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]


def _envelopes():
    txns = [
        make_transaction(
            trail_id=f"t{i}",
            alarms=[
                make_alarm(alarm_type=t, raised_offset_seconds=j) for j, t in enumerate(FIBER_CUT)
            ],
        )
        for i in range(3)
    ]
    scenarios = [make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER_CUT)]
    return run_pipeline(txns, scenarios, default_params(min_support=0.5))


def test_emit_produces_one_message_per_sequence():
    producer = _FakeProducer()
    metrics = Metrics()
    emitter = PatternEmitter(producer, "patterns.mined", metrics=metrics)
    envelopes = _envelopes()
    assert envelopes
    count = emitter.emit(envelopes)
    assert count == len(envelopes)
    assert all(topic == "patterns.mined" for topic, _ in producer.published)


def test_emitted_envelope_serializes_to_canonical_wire_and_round_trips():
    """Each emitted envelope serializes to canonical wire JSON that deserializes back cleanly."""
    from acp_event_model import serialize

    envelopes = _envelopes()
    for env in envelopes:
        wire = serialize(env)
        parsed = json.loads(wire)
        assert parsed["type"] == "PatternMinedEvent"
        assert parsed["source"] == "pattern-miner"
        assert parsed["schemaVersion"] == 1
        # deserialize enforces the frozen schema (extra="forbid") end to end.
        typed = deserialize(wire)
        assert typed.type == "PatternMinedEvent"
        assert typed.payload.trailId.startswith("t")


def test_emit_fails_fast_and_counts_produce_failure():
    """A produce failure re-raises (fail-fast) and increments the produce-failure counter.

    Per design.md failure handling: a produce failure means the run is not committed and the
    job exits non-zero for replay-safe re-consume; it must NOT be silently swallowed.
    """
    envelopes = _envelopes()
    assert envelopes
    producer = _FailingProducer(fail_after=1)
    metrics = Metrics()
    emitter = PatternEmitter(producer, "patterns.mined", metrics=metrics)

    with pytest.raises(RuntimeError, match="broker unavailable"):
        emitter.emit(envelopes + envelopes)  # >= 2 envelopes so a mid-stream produce can fail

    # First envelope produced before the failure; failure counted, not swallowed.
    assert len(producer.published) == 1
    assert metrics.produce_failures._value.get() == 1.0
