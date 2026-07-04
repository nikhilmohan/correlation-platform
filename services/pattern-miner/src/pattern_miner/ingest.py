"""Kafka ingest: message decoding, dedupe, and DLQ routing (spec criteria 7, 8).

The decode/route logic (``MessageRouter``) is separated from the confluent-kafka transport so it
is unit-testable without a live broker. Routing rules (Flow: DLQ / criteria 7, 8):

* undeserializable bytes / invalid envelope   -> DLQ ``reason=deserialize_error``
* unknown major ``schemaVersion``             -> DLQ ``reason=unsupported_schema_version``
* valid envelope, wrong ``type``              -> DLQ ``reason=wrong_type`` (not a TransactionEvent)
* payload fails ``TransactionEvent`` schema   -> DLQ ``reason=validation_error``
* valid TransactionEvent, duplicate eventId   -> dropped (dedupe), not DLQ
* valid TransactionEvent, fresh               -> accepted into the mining batch

The consumer reads ``TransactionEvent`` from Kafka (the envelope ``payload``, unwrapped by
``acp_event_model.deserialize``). Dedupe is by the envelope **``eventId``** (idempotency key).
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from acp_event_model import (
    CodecError,
    SchemaVersionError,
    TransactionEvent,
    UnknownEventTypeError,
    deserialize,
)

from .logging_setup import get_logger

log = get_logger(__name__)


class RouteDecision(StrEnum):
    ACCEPT = "accept"
    DROP_DUPLICATE = "drop_duplicate"
    DLQ = "dlq"


@dataclass
class RouteResult:
    decision: RouteDecision
    transaction: TransactionEvent | None = None
    event_id: str | None = None
    trace_id: str | None = None
    dlq_reason: str | None = None
    error: str | None = None


class Dedup:
    """In-memory set of processed envelope ``eventId``s for the current run (spec criterion 7)."""

    def __init__(self) -> None:
        self._seen: set[str] = set()

    def seen_before(self, event_id: str) -> bool:
        """True if this ``eventId`` was already processed; else record it and return False."""
        if event_id in self._seen:
            return True
        self._seen.add(event_id)
        return False

    def __len__(self) -> int:
        return len(self._seen)


class MessageRouter:
    """Decodes raw bytes into a :class:`TransactionEvent` and decides its fate."""

    def __init__(self, dedup: Dedup, *, metrics=None) -> None:
        self._dedup = dedup
        self._metrics = metrics

    def route(self, raw: bytes | str | dict[str, Any]) -> RouteResult:
        try:
            envelope = deserialize(raw)
        except SchemaVersionError as exc:
            return self._dlq("unsupported_schema_version", str(exc))
        except UnknownEventTypeError as exc:
            return self._dlq("wrong_type", str(exc))
        except CodecError as exc:
            return self._dlq("deserialize_error", str(exc))
        except Exception as exc:  # noqa: BLE001 — pydantic ValidationError etc.
            return self._dlq("validation_error", str(exc))

        if envelope.type != "TransactionEvent":
            return self._dlq("wrong_type", f"expected TransactionEvent, got {envelope.type}")

        txn = envelope.payload
        if not isinstance(txn, TransactionEvent):
            return self._dlq("validation_error", "payload is not a TransactionEvent")

        event_id = envelope.eventId
        if self._dedup.seen_before(event_id):
            if self._metrics is not None:
                self._metrics.duplicates_dropped.inc()
            log.debug("duplicate_dropped", event_id=event_id)
            return RouteResult(decision=RouteDecision.DROP_DUPLICATE, event_id=event_id)

        if self._metrics is not None:
            self._metrics.transactions_consumed.inc()
        return RouteResult(
            decision=RouteDecision.ACCEPT,
            transaction=txn,
            event_id=event_id,
            trace_id=envelope.traceId,
        )

    def _dlq(self, reason: str, error: str) -> RouteResult:
        if self._metrics is not None:
            self._metrics.dlq.labels(reason=reason).inc()
        log.warning("dlq_route", reason=reason, error=error)
        return RouteResult(decision=RouteDecision.DLQ, dlq_reason=reason, error=error)


class DlqPublisher:
    """Publishes original bytes + structured error headers to the DLQ topic."""

    def __init__(self, producer: Any, dlq_topic: str) -> None:
        self._producer = producer
        self._dlq_topic = dlq_topic

    def publish(self, raw: bytes | str, *, reason: str, error: str) -> None:
        headers = [("reason", reason.encode()), ("error", error.encode()[:1024])]
        payload = raw.encode() if isinstance(raw, str) else raw
        self._producer.produce(self._dlq_topic, value=payload, headers=headers)
