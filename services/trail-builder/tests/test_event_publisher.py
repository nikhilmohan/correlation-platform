"""Unit tests for ``TrailsBuiltPublisher`` (emits the frozen ``TrailsBuiltEvent``).

The producer is the in-memory ``FakeProducer`` fixture; the emitted bytes are
deserialized back through ``acp_event_model`` to prove the on-wire contract.
Maps to:

- AC-6  ``trails.built`` payload deserializes against ``TrailsBuiltEvent`` with
        ``trailCount == len(trailIds)``, ``snapshotId`` + ``domain`` carried through.
- AC-20 the emitted ``domain`` equals the supplied (event-carried) domain — no lookup.
"""

from __future__ import annotations

from acp_event_model import TrailsBuiltEvent, deserialize

from trailbuilder.event_publisher import TrailsBuiltPublisher


def _publisher(producer) -> TrailsBuiltPublisher:
    return TrailsBuiltPublisher(producer, topic="trails.built", source="trail-builder")


def test_emit_produces_one_message_on_topic(producer) -> None:
    """AC-6: emit produces exactly one message on the trails.built topic."""
    _publisher(producer).emit("snap-1", "core-ip", ["t-a", "t-b"], "trace-1")
    assert len(producer.for_topic("trails.built")) == 1


def test_emitted_payload_deserializes_as_trails_built_event(producer) -> None:
    """AC-6: the emitted bytes deserialize against the frozen TrailsBuiltEvent model."""
    _publisher(producer).emit("snap-1", "core-ip", ["t-a", "t-b", "t-c"], "trace-1")
    raw = producer.for_topic("trails.built")[0]
    envelope = deserialize(raw)
    assert envelope.type == "TrailsBuiltEvent"
    assert isinstance(envelope.payload, TrailsBuiltEvent)
    assert envelope.payload.snapshotId == "snap-1"
    assert envelope.payload.trailIds == ["t-a", "t-b", "t-c"]


def test_trail_count_equals_len_trail_ids(producer) -> None:
    """AC-6: trailCount is set to len(trailIds) — never independently."""
    env = _publisher(producer).build_event("snap-1", "core-ip", ["a", "b", "c", "d"], "trace-1")
    assert env.payload.trailCount == 4
    assert env.payload.trailCount == len(env.payload.trailIds)


def test_empty_trail_ids_yields_zero_count(producer) -> None:
    """AC-6: a build with no trails emits trailCount == 0 (a valid emission)."""
    env = _publisher(producer).build_event("snap-1", "core-ip", [], "trace-1")
    assert env.payload.trailCount == 0
    assert env.payload.trailIds == []


def test_emitted_domain_matches_supplied_domain(producer) -> None:
    """AC-20: the emitted domain equals the supplied (event-carried) domain verbatim."""
    _publisher(producer).emit("snap-9", "metro", ["t-x"], "trace-2")
    payload = deserialize(producer.for_topic("trails.built")[0]).payload
    assert payload.domain == "metro"


def test_envelope_carries_trace_id_and_source(producer) -> None:
    """The envelope carries the propagated traceId and the service source."""
    env = _publisher(producer).build_event("snap-1", "core-ip", ["a"], "trace-xyz")
    assert env.traceId == "trace-xyz"
    assert env.source == "trail-builder"
    assert env.type == "TrailsBuiltEvent"
