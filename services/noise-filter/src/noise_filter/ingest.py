"""Kafka ingest: message decoding, dedupe, DLQ routing, and a thin producer wrapper.

The decode/route logic (``MessageRouter``) is separated from the confluent-kafka transport so
it is unit-testable without a live broker. Routing rules (Flow 4 / EH-1, EH-2, EH-3):

* undeserializable bytes               -> DLQ ``reason=deserialize_error``
* unknown major ``schemaVersion``      -> DLQ ``reason=unsupported_schema_version``
* valid envelope, wrong ``type``       -> DLQ ``reason=wrong_type`` (not an AlarmEvent)
* valid AlarmEvent, duplicate eventId  -> dropped (dedupe), not DLQ
* valid AlarmEvent, fresh              -> accepted into windowing
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Any

from acp_event_model import (
    AlarmEvent,
    CodecError,
    SchemaVersionError,
    UnknownEventTypeError,
    deserialize,
)

from .logging_setup import get_logger
from .windowing import DedupeCache

log = get_logger(__name__)


class RouteDecision(StrEnum):
    ACCEPT = "accept"
    DROP_DUPLICATE = "drop_duplicate"
    DLQ = "dlq"


@dataclass
class RouteResult:
    decision: RouteDecision
    alarm: AlarmEvent | None = None
    event_id: str | None = None
    dlq_reason: str | None = None
    error: str | None = None


class MessageRouter:
    """Decodes raw bytes into an :class:`AlarmEvent` and decides its fate.

    Knowledge-envelope note: this consumer reads ``AlarmEvent`` from Kafka (the ``payload`` of
    the event envelope, unwrapped by ``acp_event_model.deserialize``). The Knowledge RecordResponse
    ``.payload`` unwrap (the recurring-bug guard) lives in ``clients.KnowledgeClient``.
    """

    def __init__(self, dedupe: DedupeCache, metrics=None) -> None:
        self._dedupe = dedupe
        self._metrics = metrics

    def route(self, raw: bytes | str | dict[str, Any]) -> RouteResult:
        try:
            envelope = deserialize(raw)
        except SchemaVersionError as exc:
            return self._dlq("unsupported_schema_version", str(exc))
        except (CodecError, UnknownEventTypeError) as exc:
            reason = "wrong_type" if isinstance(exc, UnknownEventTypeError) else "deserialize_error"
            return self._dlq(reason, str(exc))
        except Exception as exc:  # noqa: BLE001 — pydantic ValidationError etc.
            return self._dlq("validation_error", str(exc))

        if envelope.type != "AlarmEvent":
            return self._dlq("wrong_type", f"expected AlarmEvent, got {envelope.type}")

        alarm = envelope.payload
        if not isinstance(alarm, AlarmEvent):
            return self._dlq("validation_error", "payload is not an AlarmEvent")

        event_id = envelope.eventId
        if self._dedupe.seen_before(event_id):
            if self._metrics is not None:
                self._metrics.duplicates_dropped.inc()
            return RouteResult(decision=RouteDecision.DROP_DUPLICATE, event_id=event_id)

        if self._metrics is not None:
            self._metrics.alarms_consumed.inc()
        return RouteResult(decision=RouteDecision.ACCEPT, alarm=alarm, event_id=event_id)

    def _dlq(self, reason: str, error: str) -> RouteResult:
        if self._metrics is not None:
            self._metrics.dlq.labels(reason=reason).inc()
        log.warning("dlq_route", reason=reason, error=error)
        return RouteResult(decision=RouteDecision.DLQ, dlq_reason=reason, error=error)


class DlqPublisher:
    """Publishes the original bytes + structured error headers to ``alarms.enriched.dlq``."""

    DLQ_TOPIC = "alarms.enriched.dlq"

    def __init__(self, producer: Any) -> None:
        self._producer = producer

    def publish(self, raw: bytes | str, *, reason: str, error: str) -> None:
        headers = [("reason", reason.encode()), ("error", error.encode()[:1024])]
        payload = raw.encode() if isinstance(raw, str) else raw
        self._producer.produce(self.DLQ_TOPIC, value=payload, headers=headers)
