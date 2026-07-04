"""Ingest / dedupe / DLQ acceptance criteria (AC-5, AC-6, AC-7)."""

from __future__ import annotations

import json

from noise_filter.config import ModelParams
from noise_filter.ingest import DlqPublisher, MessageRouter, RouteDecision
from noise_filter.windowing import DedupeCache

from .conftest import build_pipeline
from .fixtures import envelope_for, make_alarm
from .helpers import make_window


class FakeProducer:
    """Captures produce() calls for DLQ assertions."""

    def __init__(self) -> None:
        self.messages: list[dict] = []

    def produce(self, topic, *, value, headers=None):
        self.messages.append({"topic": topic, "value": value, "headers": dict(headers or [])})


async def test_duplicate_event_id_processed_once(run_repo, chatter_repo, metrics):
    """AC-5: same AlarmEvent (identical eventId) twice -> processed once; metric increments."""
    import uuid

    alarm = make_alarm(alarm_id="dup-1", trail_ids=["t1"])
    env = envelope_for(alarm, event_id=str(uuid.uuid4()))
    raw = env.to_json()

    dedupe = DedupeCache(ttl_seconds=900)
    router = MessageRouter(dedupe, metrics=metrics)

    first = router.route(raw)
    second = router.route(raw)

    assert first.decision == RouteDecision.ACCEPT
    assert second.decision == RouteDecision.DROP_DUPLICATE

    # The accepted alarm appears exactly once in a window/output.
    win = make_window([alarm])
    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=1, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
    )
    out = await pipe.process_window(win)
    ids = [aid for ev in out.events for aid in ev.payload.alarmIds]
    assert ids.count("dup-1") == 1
    assert metrics.duplicates_dropped._value.get() == 1


def test_poison_message_routed_to_dlq(metrics):
    """AC-6: malformed JSON -> DLQ reason=deserialize_error; router does not crash."""
    dedupe = DedupeCache()
    router = MessageRouter(dedupe, metrics=metrics)
    producer = FakeProducer()
    dlq = DlqPublisher(producer)

    bad = b"{ this is not valid json "
    result = router.route(bad)
    assert result.decision == RouteDecision.DLQ
    assert result.dlq_reason == "deserialize_error"
    dlq.publish(bad, reason=result.dlq_reason, error=result.error or "")

    assert producer.messages[0]["topic"] == "alarms.enriched.dlq"
    assert producer.messages[0]["headers"]["reason"] == b"deserialize_error"

    # The router keeps working on a subsequent valid message.
    good = envelope_for(make_alarm(alarm_id="ok-1")).to_json()
    assert router.route(good).decision == RouteDecision.ACCEPT


def test_unknown_schema_version_routed_to_dlq(metrics):
    """AC-7: unsupported major schemaVersion -> DLQ reason=unsupported_schema_version."""
    dedupe = DedupeCache()
    router = MessageRouter(dedupe, metrics=metrics)
    producer = FakeProducer()
    dlq = DlqPublisher(producer)

    env = json.loads(envelope_for(make_alarm()).to_json())
    env["schemaVersion"] = 2  # unsupported major
    result = router.route(json.dumps(env))

    assert result.decision == RouteDecision.DLQ
    assert result.dlq_reason == "unsupported_schema_version"
    dlq.publish(json.dumps(env), reason=result.dlq_reason, error=result.error or "")
    assert producer.messages[0]["headers"]["reason"] == b"unsupported_schema_version"

    # Continues processing subsequent valid messages.
    good = envelope_for(make_alarm(alarm_id="ok-2")).to_json()
    assert router.route(good).decision == RouteDecision.ACCEPT


def test_wrong_payload_type_routed_to_dlq(metrics):
    """A valid envelope carrying a non-AlarmEvent type is DLQ'd (defensive — consumer expects
    only AlarmEvent on alarms.enriched)."""
    dedupe = DedupeCache()
    router = MessageRouter(dedupe, metrics=metrics)
    env = json.loads(envelope_for(make_alarm()).to_json())
    env["type"] = "TransactionEvent"  # wrong type for this topic
    result = router.route(json.dumps(env))
    assert result.decision == RouteDecision.DLQ
