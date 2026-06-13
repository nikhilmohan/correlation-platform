"""Consumer-handler tests: ``TopologyChangedHandler`` + ``KnowledgeUpdatedHandler``.

The handlers are decoupled from confluent-kafka so they run with a fake build
service, the real ``IdempotencyStore`` (in-memory SQLite ``engine`` fixture), and
the ``FakeProducer`` as the DLQ sink. Envelopes are built with ``acp_event_model``
+ ``serialize`` so the deserialize path is exercised against the frozen contract.
Maps to:

- AC-6  a valid topology.changed triggers a domain-scoped build (domain read from event).
- AC-7  idempotency — a redelivered eventId builds exactly once.
- AC-8  knowledge.updated trailPolicy invalidates the policy cache; no build, no emit.
- AC-14 poison topology.changed (bad JSON / unknown schemaVersion) -> DLQ, no crash.
- AC-20 the build domain equals the topology.changed domain (no Topology lookup).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime

import pytest
from acp_event_model import (
    KnowledgeUpdatedEvent,
    TopologyChangedEvent,
    TypedEnvelope,
    serialize,
)

from trailbuilder.build_service import BuildResult
from trailbuilder.clients.errors import IntegrationError
from trailbuilder.idempotency import IdempotencyStore
from trailbuilder.kafka_consumer import KnowledgeUpdatedHandler, TopologyChangedHandler


@dataclass
class FakeBuildService:
    """Records each build call; optionally raises IntegrationError to simulate a hold."""

    calls: list[tuple[str, str, str, bool]] = field(default_factory=list)
    raise_integration: bool = False

    def build(self, snapshot_id: str, domain: str, trace_id: str, emit: bool = True) -> BuildResult:
        self.calls.append((snapshot_id, domain, trace_id, emit))
        if self.raise_integration:
            raise IntegrationError("topology", "unreachable")
        return BuildResult(snapshot_id=snapshot_id, domain=domain, trail_ids=["t-1"])


@dataclass
class FakePolicyClient:
    """Records invalidation calls for the knowledge.updated path."""

    invalidated: list[str] = field(default_factory=list)

    def invalidate(self, domain: str) -> None:
        self.invalidated.append(domain)


def _topology_changed_raw(
    snapshot_id: str = "snap-1",
    domain: str | None = "core-ip",
    event_id: str | None = None,
    trace_id: str = "trace-1",
) -> str:
    payload = TopologyChangedEvent(
        snapshotId=snapshot_id, domain=domain, changeType="created", nodes=[], edges=[]
    )
    env = TypedEnvelope(
        eventId=event_id or str(uuid.uuid4()),
        type="TopologyChangedEvent",
        schemaVersion=1,
        occurredAt=datetime.now(UTC),
        source="simulator",
        traceId=trace_id,
        payload=payload,
    )
    return serialize(env)


def _knowledge_updated_raw(record_type: str = "trailPolicy", domain: str = "core-ip") -> str:
    payload = KnowledgeUpdatedEvent(
        recordType=record_type, recordId="default", version="2", domain=domain
    )
    env = TypedEnvelope(
        eventId=str(uuid.uuid4()),
        type="KnowledgeUpdatedEvent",
        schemaVersion=1,
        occurredAt=datetime.now(UTC),
        source="knowledge",
        traceId="trace-k",
        payload=payload,
    )
    return serialize(env)


def _handler(settings, engine, build_service, dlq_producer) -> TopologyChangedHandler:
    return TopologyChangedHandler(settings, IdempotencyStore(engine), build_service, dlq_producer)


# --- TopologyChangedHandler ---


def test_valid_topology_changed_triggers_build(settings, engine, producer) -> None:
    """AC-6 + AC-20: a valid event builds for the event's domain (read from the event)."""
    build = FakeBuildService()
    status = _handler(settings, engine, build, producer).handle(
        _topology_changed_raw(snapshot_id="snap-1", domain="core-ip")
    )
    assert status == "built"
    assert build.calls == [("snap-1", "core-ip", "trace-1", True)]


def test_duplicate_event_builds_once(settings, engine, producer) -> None:
    """AC-7: the same eventId delivered twice builds exactly once."""
    build = FakeBuildService()
    handler = _handler(settings, engine, build, producer)
    event_id = str(uuid.uuid4())
    raw = _topology_changed_raw(event_id=event_id)
    first = handler.handle(raw)
    second = handler.handle(raw)
    assert first == "built"
    assert second == "duplicate"
    assert len(build.calls) == 1


def test_held_event_is_not_marked_processed(settings, engine, producer) -> None:
    """AC-7 (hold semantics): an IntegrationError holds — a redelivery retries the build."""
    build = FakeBuildService(raise_integration=True)
    handler = _handler(settings, engine, build, producer)
    event_id = str(uuid.uuid4())
    raw = _topology_changed_raw(event_id=event_id)
    assert handler.handle(raw) == "held"
    # Not marked processed: a redelivery is allowed to retry (not deduped away).
    build.raise_integration = False
    assert handler.handle(raw) == "built"
    assert len(build.calls) == 2


def test_missing_domain_falls_back_on_event_path(settings, engine, producer) -> None:
    """A legacy topology.changed without domain uses the configured fallback (event path only)."""
    build = FakeBuildService()
    status = _handler(settings, engine, build, producer).handle(_topology_changed_raw(domain=None))
    assert status == "built"
    # Fallback domain is the configured DEFAULT_DOMAIN.
    assert build.calls[0][1] == settings.default_domain


def test_poison_bad_json_routed_to_dlq(settings, engine, producer) -> None:
    """AC-14: malformed JSON is routed to the topology.changed.dlq, no crash."""
    build = FakeBuildService()
    status = _handler(settings, engine, build, producer).handle("{not json")
    assert status == "dlq"
    assert producer.for_topic(settings.topology_changed_dlq_topic)
    assert build.calls == []


def test_unexpected_event_type_routed_to_dlq(settings, engine, producer) -> None:
    """AC-14: a well-formed but wrong-type event is routed to the DLQ."""
    build = FakeBuildService()
    status = _handler(settings, engine, build, producer).handle(_knowledge_updated_raw())
    assert status == "dlq"
    assert producer.for_topic(settings.topology_changed_dlq_topic)


def test_poison_does_not_block_subsequent_message(settings, engine, producer) -> None:
    """AC-14: a poison message does not prevent processing the next valid message."""
    build = FakeBuildService()
    handler = _handler(settings, engine, build, producer)
    assert handler.handle(b"\xff\xfe not utf8 or json") == "dlq"
    assert handler.handle(_topology_changed_raw(snapshot_id="snap-2")) == "built"
    assert build.calls == [("snap-2", "core-ip", "trace-1", True)]


def test_dlq_accepts_bytes_input(settings, engine, producer) -> None:
    """The DLQ path handles raw bytes (the native confluent-kafka value type)."""
    build = FakeBuildService()
    status = _handler(settings, engine, build, producer).handle(b"{bad")
    assert status == "dlq"
    assert producer.for_topic(settings.topology_changed_dlq_topic)


# --- KnowledgeUpdatedHandler ---


def test_knowledge_trailpolicy_invalidates_cache(settings) -> None:
    """AC-8: a trailPolicy knowledge.updated invalidates the domain policy cache."""
    policy = FakePolicyClient()
    status = KnowledgeUpdatedHandler(policy).handle(_knowledge_updated_raw(domain="core-ip"))
    assert status == "refreshed"
    assert policy.invalidated == ["core-ip"]


def test_knowledge_non_trailpolicy_is_ignored(settings) -> None:
    """AC-8: a non-trailPolicy record type is ignored (no cache invalidation)."""
    policy = FakePolicyClient()
    status = KnowledgeUpdatedHandler(policy).handle(
        _knowledge_updated_raw(record_type="alarmTypeVocabulary")
    )
    assert status == "ignored"
    assert policy.invalidated == []


def test_knowledge_wrong_event_type_is_ignored(settings) -> None:
    """A non-KnowledgeUpdatedEvent on the topic is ignored (refresh-only path)."""
    policy = FakePolicyClient()
    status = KnowledgeUpdatedHandler(policy).handle(_topology_changed_raw())
    assert status == "ignored"
    assert policy.invalidated == []


def test_knowledge_poison_is_skipped_not_dlq(settings) -> None:
    """AC-8: poison knowledge.updated is skipped (refresh-only self-heals), not DLQ'd."""
    policy = FakePolicyClient()
    status = KnowledgeUpdatedHandler(policy).handle("{garbage")
    assert status == "skipped"
    assert policy.invalidated == []


@pytest.mark.parametrize("domain", ["core-ip", "metro"])
def test_knowledge_refresh_is_domain_scoped(settings, domain: str) -> None:
    """AC-8: the invalidation targets the event's domain (domain-scoped refresh)."""
    policy = FakePolicyClient()
    KnowledgeUpdatedHandler(policy).handle(_knowledge_updated_raw(domain=domain))
    assert policy.invalidated == [domain]
