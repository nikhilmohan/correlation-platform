"""Thin confluent-kafka wrappers (transport only).

Not exercised by the unit gate (no live broker); the decode/route/mine/emit logic they drive is
unit-tested in :mod:`pattern_miner.ingest`, :mod:`pattern_miner.assemble`, and
:mod:`pattern_miner.emit`. Kept minimal and side-effect-free at import time.
"""

from __future__ import annotations

from typing import Any

from acp_event_model import TypedEnvelope, serialize


class PatternProducer:
    """Idempotent producer for ``patterns.mined`` (and the DLQ via :class:`DlqPublisher`)."""

    def __init__(self, bootstrap_servers: str) -> None:
        from confluent_kafka import Producer

        self._producer = Producer(
            {
                "bootstrap.servers": bootstrap_servers,
                "enable.idempotence": True,
                "acks": "all",
            }
        )

    @property
    def raw(self) -> Any:
        return self._producer

    def publish(self, topic: str, envelope: TypedEnvelope) -> None:
        self._producer.produce(topic, value=serialize(envelope).encode())
        self._producer.poll(0)

    def produce(self, topic: str, *, value: bytes, headers: Any = None) -> None:
        self._producer.produce(topic, value=value, headers=headers)
        self._producer.poll(0)

    def flush(self, timeout: float = 10.0) -> None:
        self._producer.flush(timeout)


def make_consumer(bootstrap_servers: str, group_id: str, topics: list[str]) -> Any:
    """Create a confluent-kafka consumer with manual offset commit (at-least-once)."""
    from confluent_kafka import Consumer

    consumer = Consumer(
        {
            "bootstrap.servers": bootstrap_servers,
            "group.id": group_id,
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
        }
    )
    consumer.subscribe(topics)
    return consumer
