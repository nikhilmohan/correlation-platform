"""``codebook.generated`` producer tests (frozen event-model binding + DLQ fallback).

Verifies :class:`codebook_generator.producer.CodebookEventProducer` emits a frozen
``CodebookGeneratedEvent`` (deserializable by the ``acp_event_model`` binding, AC-9) keyed by
``codebookId``, and falls back to ``codebook.generated.dlq`` on a publish failure without
losing the message.
"""

from __future__ import annotations

from acp_event_model import CodebookGeneratedEvent, deserialize

from codebook_generator.producer import CodebookEventProducer, build_envelope

from .conftest import FakeProducer


def _producer(fake: FakeProducer) -> CodebookEventProducer:
    return CodebookEventProducer(
        fake, topic="codebook.generated", dlq_topic="codebook.generated.dlq"
    )


def test_build_envelope_round_trips_through_frozen_binding() -> None:
    """The built envelope deserializes back to a CodebookGeneratedEvent (frozen binding)."""
    env = build_envelope(
        snapshot_id="snap-X",
        scenario_count=3,
        codebook_id="cb-1",
        domain="core-ip",
        trace_id="trace-1",
    )
    assert env.type == "CodebookGeneratedEvent"
    assert isinstance(env.payload, CodebookGeneratedEvent)
    assert env.payload.domain == "core-ip"
    assert env.payload.scenarioCount == 3


def test_emit_publishes_to_topic_keyed_by_codebook_id() -> None:
    """emit publishes one message to codebook.generated keyed by codebookId."""
    fake = FakeProducer()
    _producer(fake).emit(
        snapshot_id="snap-X",
        scenario_count=2,
        codebook_id="cb-42",
        domain="core-ip",
        trace_id="trace-1",
    )
    msgs = [m for m in fake.messages if m[0] == "codebook.generated"]
    assert len(msgs) == 1
    topic, value, key = msgs[0]
    assert key == b"cb-42"
    payload = deserialize(value).payload
    assert payload.codebookId == "cb-42"
    assert payload.domain == "core-ip"
    assert payload.snapshotId == "snap-X"
    assert payload.scenarioCount == 2


def test_emit_falls_back_to_dlq_on_publish_failure() -> None:
    """A publish failure on the primary topic routes the message to the DLQ, not lost."""
    fake = FakeProducer()
    fake.fail_topics.add("codebook.generated")
    _producer(fake).emit(
        snapshot_id="snap-X",
        scenario_count=1,
        codebook_id="cb-dlq",
        domain="core-ip",
        trace_id="trace-1",
    )
    # Nothing on the primary topic (it failed); the message landed on the DLQ.
    assert fake.topic_messages("codebook.generated") == []
    dlq = fake.topic_messages("codebook.generated.dlq")
    assert len(dlq) == 1
    assert deserialize(dlq[0]).payload.codebookId == "cb-dlq"
