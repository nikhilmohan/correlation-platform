"""Emit: one PatternMinedEvent per sequence produced to patterns.mined (serialized wire JSON)."""

from __future__ import annotations

import json

from acp_event_model import deserialize

from pattern_miner.assemble import group_transactions
from pattern_miner.emit import PatternEmitter
from pattern_miner.metrics import Metrics

from .helpers import build_assembler, default_params, make_alarm, make_transaction


class _FakeProducer:
    def __init__(self):
        self.published = []

    def publish(self, topic, envelope):
        self.published.append((topic, envelope))


def _envelopes():
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=i)
        for i, t in enumerate(["FiberFault", "LinkDown", "AdjDown"])
    ]
    txns = [make_transaction(trail_id=f"t{i}", alarms=alarms) for i in range(3)]
    assembler = build_assembler(windowing=default_params().windowing)
    out = []
    for batch in group_transactions([(t, "tr") for t in txns]):
        out.extend(assembler.mine_batch(batch, default_params(min_support=0.5)))
    return out


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
