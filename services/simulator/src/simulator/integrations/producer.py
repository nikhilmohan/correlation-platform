"""Producer abstraction — the seam between replay and the real Kafka client.

``AlarmProducer`` is the minimal Protocol ``replay`` depends on: ``produce(topic, envelope)``
plus ``flush()``. The real ``confluent-kafka`` implementation lives in ``kafka_producer.py``
(network boundary, integration-only); unit tests inject an in-memory double, and the export
corpus writer taps the same ``produce`` call so the corpus is exactly the wire stream.
"""

from __future__ import annotations

from typing import Any, Protocol

from acp_event_model import TypedEnvelope


class AlarmProducer(Protocol):
    """Minimal producer seam: emit a typed envelope to a topic, then flush."""

    def produce(self, topic: str, envelope: TypedEnvelope[Any]) -> None:
        """Serialize + send one envelope to ``topic``."""
        ...

    def flush(self) -> None:
        """Block until all buffered messages are delivered (or error)."""
        ...
