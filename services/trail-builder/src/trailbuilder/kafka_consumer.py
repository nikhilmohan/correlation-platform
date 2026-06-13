"""Kafka consumers: ``topology.changed`` (build trigger) + ``knowledge.updated``.

The message-handling logic is decoupled from the confluent-kafka client so it is
unit-testable with a fake producer/consumer. ``TopologyChangedHandler`` dedupes
on envelope ``eventId`` (``processed_event``), routes poison messages to
``topology.changed.dlq``, and runs a build. ``KnowledgeUpdatedHandler`` invalidates
the per-domain policy cache when ``recordType == "trailPolicy"`` — no build, no emit.
"""

from __future__ import annotations

from typing import Protocol

from acp_event_model import CodecError, SchemaVersionError, UnknownEventTypeError, deserialize
from pydantic import ValidationError

# Deserialization-stage poison: anything raised while turning raw bytes into a
# typed envelope. ``CodecError``/``SchemaVersionError``/``UnknownEventTypeError``
# are the contract-defined failures; ``ValidationError`` is a payload that fails
# its schema; ``UnicodeDecodeError``/``ValueError`` cover non-UTF8 or non-JSON
# bytes that ``json.loads`` rejects before the codec can wrap them. Catching the
# full set guarantees a poison message is DLQ'd, never crashing the consumer (AC-14).
_POISON: tuple[type[Exception], ...] = (
    CodecError,
    SchemaVersionError,
    UnknownEventTypeError,
    ValidationError,
    UnicodeDecodeError,
    ValueError,
)

from .build_service import BuildService
from .clients.errors import IntegrationError
from .clients.policy_client import KnowledgePolicyClient
from .config import Settings
from .idempotency import IdempotencyStore
from .observability import (
    DLQ_MESSAGES_TOTAL,
    POLICY_REFRESHES_TOTAL,
    get_logger,
)

_log = get_logger("trailbuilder.kafka")


class Producer(Protocol):
    def produce(self, topic: str, value: bytes, key: bytes | None = ...) -> None: ...
    def flush(self, timeout: float = ...) -> int: ...


class TopologyChangedHandler:
    """Handles one ``topology.changed`` message end-to-end."""

    def __init__(
        self,
        settings: Settings,
        idempotency: IdempotencyStore,
        build_service: BuildService,
        dlq_producer: Producer,
    ) -> None:
        self._settings = settings
        self._idempotency = idempotency
        self._build = build_service
        self._dlq = dlq_producer

    def handle(self, raw: bytes | str) -> str:
        """Process a raw message. Returns a status string for observability.

        Statuses: ``"built"``, ``"duplicate"``, ``"dlq"``, ``"held"``.
        Poison messages (bad JSON / unknown major schemaVersion / unknown type)
        go to the DLQ; dependency failures hold the build (eventId not marked).
        """
        try:
            envelope = deserialize(raw)
        except _POISON as exc:
            self._to_dlq(raw, reason=type(exc).__name__)
            return "dlq"

        if envelope.type != "TopologyChangedEvent":
            self._to_dlq(raw, reason=f"unexpected type {envelope.type}")
            return "dlq"

        event_id = envelope.eventId
        payload = envelope.payload
        snapshot_id = payload.snapshotId  # type: ignore[attr-defined]
        # Default-domain fallback applies ONLY on the event path (legacy producer).
        domain = payload.domain or self._settings.default_domain  # type: ignore[attr-defined]
        trace_id = envelope.traceId

        if self._idempotency.seen(event_id):
            _log.info(
                "duplicate topology.changed ignored",
                extra={"traceId": trace_id, "snapshotId": snapshot_id, "domain": domain},
            )
            return "duplicate"

        if payload.domain is None:  # type: ignore[attr-defined]
            _log.warning(
                "topology.changed missing domain; defaulting (event path only)",
                extra={"traceId": trace_id, "snapshotId": snapshot_id, "domain": domain},
            )

        try:
            self._build.build(snapshot_id, domain, trace_id, emit=True)
        except IntegrationError:
            # Held: do NOT mark processed, so a redelivery retries.
            return "held"

        # Mark processed only after a successful build + emit (atomic dedupe).
        self._idempotency.mark_processed(event_id, snapshot_id, domain)
        return "built"

    def _to_dlq(self, raw: bytes | str, reason: str) -> None:
        DLQ_MESSAGES_TOTAL.inc()
        value = raw.encode("utf-8") if isinstance(raw, str) else raw
        _log.error("routing poison topology.changed to DLQ", extra={"dlqReason": reason})
        self._dlq.produce(self._settings.topology_changed_dlq_topic, value)
        self._dlq.flush(5.0)


class KnowledgeUpdatedHandler:
    """Handles one ``knowledge.updated`` message (policy-refresh trigger only)."""

    def __init__(self, policy_client: KnowledgePolicyClient) -> None:
        self._policy = policy_client

    def handle(self, raw: bytes | str) -> str:
        """Invalidate the domain policy cache on a trailPolicy change.

        Poison messages on this topic are logged and skipped (refresh-only path;
        a missed refresh self-heals on the next event) — NOT DLQ'd. Returns one
        of ``"refreshed"``, ``"ignored"``, ``"skipped"``.
        """
        try:
            envelope = deserialize(raw)
        except _POISON:
            _log.warning("skipping poison knowledge.updated (refresh-only)")
            return "skipped"

        if envelope.type != "KnowledgeUpdatedEvent":
            return "ignored"

        payload = envelope.payload
        if payload.recordType != "trailPolicy":  # type: ignore[attr-defined]
            return "ignored"

        domain = payload.domain  # type: ignore[attr-defined]
        self._policy.invalidate(domain)
        POLICY_REFRESHES_TOTAL.labels(domain=domain).inc()
        _log.info("trail policy cache invalidated", extra={"domain": domain})
        return "refreshed"
