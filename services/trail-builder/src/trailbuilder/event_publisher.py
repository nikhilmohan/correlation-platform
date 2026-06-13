"""``TrailsBuiltPublisher`` — emits ``trails.built`` (frozen ``TrailsBuiltEvent``).

Builds a ``TrailsBuiltEvent`` (``snapshotId``, ``trailIds``, ``trailCount ==
len(trailIds)``, ``domain`` taken from the triggering event — no Topology
lookup), wraps it in an ``Envelope`` and produces it via ``acp_event_model``.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import Protocol

from acp_event_model import TrailsBuiltEvent, TypedEnvelope, serialize


class Producer(Protocol):
    """Minimal Kafka-producer surface (so tests can inject a fake)."""

    def produce(self, topic: str, value: bytes, key: bytes | None = ...) -> None: ...

    def flush(self, timeout: float = ...) -> int: ...


class TrailsBuiltPublisher:
    """Serializes + produces ``trails.built`` events."""

    def __init__(self, producer: Producer, topic: str, source: str = "trail-builder") -> None:
        self._producer = producer
        self._topic = topic
        self._source = source

    def build_event(
        self, snapshot_id: str, domain: str, trail_ids: list[str], trace_id: str
    ) -> TypedEnvelope:
        """Construct the typed envelope (trailCount == len(trailIds))."""
        payload = TrailsBuiltEvent(
            snapshotId=snapshot_id,
            domain=domain,
            trailIds=trail_ids,
            trailCount=len(trail_ids),
        )
        return TypedEnvelope(
            eventId=str(uuid.uuid4()),
            type="TrailsBuiltEvent",
            schemaVersion=1,
            occurredAt=datetime.now(UTC),
            source=self._source,
            traceId=trace_id,
            payload=payload,
        )

    def emit(
        self, snapshot_id: str, domain: str, trail_ids: list[str], trace_id: str
    ) -> TypedEnvelope:
        """Build, serialize, and produce the ``trails.built`` event."""
        envelope = self.build_event(snapshot_id, domain, trail_ids, trace_id)
        self._producer.produce(self._topic, serialize(envelope).encode("utf-8"))
        self._producer.flush(5.0)
        return envelope
