"""Real Kafka producer (confluent-kafka / librdkafka) — integration boundary.

Configured idempotent (``enable.idempotence=true``, ``acks=all``) so at-least-once redelivery
is dedupable downstream on ``eventId``/``alarmId``. A delivery callback logs + counts produce
errors and marks ``/health`` unhealthy. This module is excluded from unit coverage (it touches a
real broker); unit tests inject the in-memory double from ``tests``. ``confluent-kafka`` is an
optional dependency (``pip install .[kafka]``) so the unit suite installs without native libs.
"""

from __future__ import annotations

import logging
from typing import Any

from acp_event_model import TypedEnvelope, serialize

from simulator.obs import metrics
from simulator.obs.logging import log_event

_log = logging.getLogger("simulator.kafka")


class KafkaProducer:
    """Idempotent confluent-kafka producer for the alarm topics."""

    def __init__(self, bootstrap_servers: str) -> None:
        from confluent_kafka import Producer  # imported lazily (optional dep)

        self._healthy = True
        self._producer = Producer(
            {
                "bootstrap.servers": bootstrap_servers,
                "enable.idempotence": True,
                "acks": "all",
            }
        )

    @property
    def healthy(self) -> bool:
        return self._healthy

    def _on_delivery(self, err: Any, msg: Any) -> None:
        if err is not None:
            self._healthy = False
            metrics.PRODUCE_ERRORS.inc()
            log_event(
                _log,
                logging.ERROR,
                "kafka.delivery_error",
                f"delivery failed: {err}",
                topic=getattr(msg, "topic", lambda: None)(),
            )

    def produce(self, topic: str, envelope: TypedEnvelope[Any]) -> None:
        self._producer.produce(
            topic, value=serialize(envelope).encode("utf-8"), on_delivery=self._on_delivery
        )
        self._producer.poll(0)

    def flush(self) -> None:
        self._producer.flush()
