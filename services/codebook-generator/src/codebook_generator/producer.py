"""``codebook.generated`` producer (frozen event-model binding) + DLQ fallback.

Builds a :class:`acp_event_model.CodebookGeneratedEvent` (``snapshotId``, ``scenarioCount``,
``codebookId``, ``domain``), wraps it in the canonical envelope, and publishes to
``codebook.generated`` keyed by ``codebookId``. On publish failure the payload is routed to
``codebook.generated.dlq`` with error metadata; the codebook is already persisted so it
stays queryable.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Protocol

from acp_event_model import CodebookGeneratedEvent, TypedEnvelope, serialize

from .logging_config import get_logger
from .metrics import dlq_routed_total

logger = get_logger(__name__)


class MessageProducer(Protocol):
    """Minimal Kafka-producer surface (confluent-kafka compatible)."""

    def produce(self, topic: str, value: bytes, key: bytes | None = None) -> None: ...

    def flush(self, timeout: float | None = None) -> int: ...


def build_envelope(
    *,
    snapshot_id: str,
    scenario_count: int,
    codebook_id: str,
    domain: str,
    trace_id: str,
    source: str = "codebook-generator",
) -> TypedEnvelope[CodebookGeneratedEvent]:
    """Construct the typed ``codebook.generated`` envelope (frozen binding)."""
    payload = CodebookGeneratedEvent(
        snapshotId=snapshot_id,
        scenarioCount=scenario_count,
        codebookId=codebook_id,
        domain=domain,
    )
    return TypedEnvelope[CodebookGeneratedEvent](
        eventId=str(uuid.uuid4()),
        type="CodebookGeneratedEvent",
        schemaVersion=1,
        occurredAt=datetime.now(UTC),
        source=source,
        traceId=trace_id,
        payload=payload,
    )


class CodebookEventProducer:
    """Publishes ``codebook.generated`` events with DLQ fallback."""

    def __init__(
        self,
        producer: MessageProducer,
        *,
        topic: str,
        dlq_topic: str,
    ) -> None:
        self._producer = producer
        self._topic = topic
        self._dlq_topic = dlq_topic

    def emit(
        self,
        *,
        snapshot_id: str,
        scenario_count: int,
        codebook_id: str,
        domain: str,
        trace_id: str,
    ) -> None:
        """Publish a ``codebook.generated`` event; route to DLQ on failure."""
        envelope = build_envelope(
            snapshot_id=snapshot_id,
            scenario_count=scenario_count,
            codebook_id=codebook_id,
            domain=domain,
            trace_id=trace_id,
        )
        value = serialize(envelope).encode("utf-8")
        try:
            self._producer.produce(self._topic, value=value, key=codebook_id.encode("utf-8"))
            self._producer.flush()
        except Exception as exc:  # noqa: BLE001 — fall back to DLQ, never lose the message
            logger.error(
                "codebook.generated publish failed; routing to DLQ",
                extra={"codebookId": codebook_id, "domain": domain, "snapshotId": snapshot_id},
                exc_info=exc,
            )
            dlq_routed_total.labels(topic=self._dlq_topic).inc()
            self._producer.produce(self._dlq_topic, value=value, key=codebook_id.encode("utf-8"))
            self._producer.flush()
