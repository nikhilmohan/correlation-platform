"""Ingest: dedupe (AC7) + DLQ routing (AC8) — no live broker needed."""

from __future__ import annotations

from pattern_miner.ingest import Dedup, DlqPublisher, MessageRouter, RouteDecision
from pattern_miner.metrics import Metrics

from .helpers import make_alarm, make_transaction, wrap


class _FakeProducer:
    def __init__(self):
        self.produced = []

    def produce(self, topic, *, value, headers=None):
        self.produced.append((topic, value, headers))


def _fiber_txn():
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=i)
        for i, t in enumerate(["FiberFault", "LinkDown", "AdjDown"])
    ]
    return make_transaction(trail_id="t1", alarms=alarms)


# --------------------------------------------------------------------------- AC 7
def test_duplicate_event_id_dropped():
    """AC7: the same envelope eventId processed twice -> accepted once, then dropped silently."""
    metrics = Metrics()
    router = MessageRouter(Dedup(), metrics=metrics)
    msg = wrap(_fiber_txn(), event_id="11111111-1111-1111-1111-111111111111")

    first = router.route(msg)
    assert first.decision == RouteDecision.ACCEPT
    assert first.transaction is not None

    second = router.route(msg)
    assert second.decision == RouteDecision.DROP_DUPLICATE
    assert second.transaction is None
    assert metrics.duplicates_dropped._value.get() == 1
    assert metrics.transactions_consumed._value.get() == 1


# --------------------------------------------------------------------------- AC 8
def test_poison_malformed_json_routed_to_dlq():
    """AC8: undeserializable bytes route to the DLQ; no acceptance."""
    metrics = Metrics()
    router = MessageRouter(Dedup(), metrics=metrics)
    result = router.route(b"{not valid json")
    assert result.decision == RouteDecision.DLQ
    assert result.dlq_reason == "deserialize_error"
    assert metrics.dlq.labels(reason="deserialize_error")._value.get() == 1


def test_poison_missing_alarms_field_routed_to_dlq():
    """AC8: a TransactionEvent missing the required alarms[] fails schema validation -> DLQ."""
    metrics = Metrics()
    router = MessageRouter(Dedup(), metrics=metrics)
    msg = wrap(_fiber_txn())
    del msg["payload"]["alarms"]  # required field
    result = router.route(msg)
    assert result.decision == RouteDecision.DLQ
    assert result.dlq_reason == "validation_error"
    assert result.transaction is None


def test_wrong_type_routed_to_dlq():
    """AC8 variant: a valid envelope with the wrong payload type routes to DLQ (wrong_type)."""
    metrics = Metrics()
    router = MessageRouter(Dedup(), metrics=metrics)
    msg = wrap(_fiber_txn())
    msg["type"] = "AlarmEvent"  # not TransactionEvent
    result = router.route(msg)
    assert result.decision == RouteDecision.DLQ
    assert result.dlq_reason in {"wrong_type", "validation_error"}


def test_unsupported_schema_version_routed_to_dlq():
    """AC8 companion: an unsupported major schemaVersion routes to DLQ."""
    metrics = Metrics()
    router = MessageRouter(Dedup(), metrics=metrics)
    msg = wrap(_fiber_txn())
    msg["schemaVersion"] = 2
    result = router.route(msg)
    assert result.decision == RouteDecision.DLQ
    assert result.dlq_reason == "unsupported_schema_version"


def test_dlq_publisher_writes_headers_and_bytes():
    """DlqPublisher writes the original bytes + structured reason/error headers to the DLQ topic."""
    producer = _FakeProducer()
    dlq = DlqPublisher(producer, "transactions.clean.dlq")
    dlq.publish(b"poison", reason="deserialize_error", error="boom")
    assert len(producer.produced) == 1
    topic, value, headers = producer.produced[0]
    assert topic == "transactions.clean.dlq"
    assert value == b"poison"
    header_map = dict(headers)
    assert header_map["reason"] == b"deserialize_error"
    assert header_map["error"] == b"boom"


def test_valid_transaction_accepted_carries_trace_id():
    """A fresh valid TransactionEvent is accepted and carries its envelope traceId through."""
    router = MessageRouter(Dedup())
    msg = wrap(_fiber_txn(), trace_id="trace-xyz")
    result = router.route(msg)
    assert result.decision == RouteDecision.ACCEPT
    assert result.trace_id == "trace-xyz"
    assert result.transaction.trailId == "t1"
