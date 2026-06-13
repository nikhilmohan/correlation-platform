"""``trails.built`` consumer (spec task 1 / criteria 6, 11, 12).

Deserializes via the frozen ``acp_event_model`` codec (which rejects unknown major
``schemaVersion`` and unknown ``type``), dedups on the envelope ``eventId``, dispatches to
the :class:`CompilationPipeline`, and commits the offset only after a successful compile
(at-least-once; dedup makes redelivery safe). Unprocessable messages route to
``trails.built.dlq``; the consumer continues with subsequent messages.
"""

from __future__ import annotations

from typing import Protocol

from acp_event_model import CodecError, SchemaVersionError, UnknownEventTypeError, deserialize
from pydantic import BaseModel, ValidationError

from .clients.base import IntegrationError
from .logging_config import get_logger
from .metrics import dlq_routed_total, errors_total
from .pipeline import CompilationPipeline, CompileResult
from .producer import MessageProducer
from .vocabulary import VocabularyError

logger = get_logger(__name__)


class DlqRouter:
    """Routes unprocessable raw messages to a DLQ topic with error metadata."""

    def __init__(self, producer: MessageProducer, dlq_topic: str) -> None:
        self._producer = producer
        self._dlq_topic = dlq_topic

    def route(self, raw: bytes, reason: str) -> None:
        """Publish ``raw`` to the DLQ; never raises into the consumer loop."""
        logger.warning("routing message to DLQ: %s", reason)
        dlq_routed_total.labels(topic=self._dlq_topic).inc()
        try:
            self._producer.produce(self._dlq_topic, value=raw)
            self._producer.flush()
        except Exception as exc:  # noqa: BLE001 — DLQ best-effort; do not crash the loop
            logger.error("failed to route to DLQ: %s", exc)


class TrailsBuiltHandler:
    """Pure message handler: deserialize -> dedup -> compile, with DLQ routing.

    Returns the :class:`CompileResult` on success, or ``None`` when the message was routed
    to the DLQ (so the caller can still commit the offset and move on).
    """

    def __init__(
        self,
        *,
        pipeline: CompilationPipeline,
        dlq: DlqRouter,
        default_domain: str,
    ) -> None:
        self._pipeline = pipeline
        self._dlq = dlq
        self._default_domain = default_domain

    def handle(self, raw: bytes) -> CompileResult | None:
        """Process one raw ``trails.built`` message."""
        try:
            envelope = deserialize(raw)
        except SchemaVersionError as exc:
            self._dlq.route(raw, f"unsupported schemaVersion: {exc}")
            return None
        except (CodecError, UnknownEventTypeError, ValidationError) as exc:
            self._dlq.route(raw, f"undeserializable trails.built: {exc}")
            return None

        if envelope.type != "TrailsBuiltEvent":
            self._dlq.route(raw, f"unexpected type {envelope.type!r} on trails.built")
            return None

        payload = envelope.payload
        snapshot_id = getattr(payload, "snapshotId", None)
        if not snapshot_id:
            self._dlq.route(raw, "missing snapshotId on TrailsBuiltEvent")
            return None

        domain = _resolve_domain(payload, self._default_domain)
        try:
            return self._pipeline.compile(
                event_id=envelope.eventId,
                snapshot_id=snapshot_id,
                domain=domain,
                trace_id=envelope.traceId,
            )
        except (IntegrationError, VocabularyError) as exc:
            errors_total.labels(domain=domain).inc()
            logger.error(
                "compile failed; routing trigger to DLQ",
                extra={"eventId": envelope.eventId, "domain": domain, "snapshotId": snapshot_id},
                exc_info=exc,
            )
            self._dlq.route(raw, f"compile failed: {exc}")
            return None


def _resolve_domain(payload: BaseModel, default_domain: str) -> str:
    from .domain import resolve_domain

    return resolve_domain(payload, default_domain)
