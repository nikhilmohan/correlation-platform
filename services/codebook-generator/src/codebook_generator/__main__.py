"""Service entrypoint.

Startup order (spec criteria 27-29): apply migrations FIRST, then start the consumer.
The FastAPI app is served separately (``uvicorn codebook_generator.api:app``) in Compose;
this module runs the Kafka consumer loop. Both share :mod:`config`.

Usage:
    python -m codebook_generator            # migrate + run consumer loop
    python -m codebook_generator migrate     # migrate only (entrypoint pre-step)
"""

from __future__ import annotations

import sys

from .config import load_settings
from .logging_config import configure_logging, get_logger
from .migrate import apply_migrations

logger = get_logger(__name__)


def _run_consumer_loop() -> None:  # pragma: no cover - requires a live Kafka broker
    from confluent_kafka import Consumer, Producer

    from .bootstrap import build_components

    settings = load_settings()
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers})
    components = build_components(settings, message_producer=_ConfluentProducerAdapter(producer))

    consumer = Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": settings.kafka_consumer_group,
            "enable.auto.commit": False,
            "auto.offset.reset": "earliest",
        }
    )
    consumer.subscribe([settings.trails_built_topic])
    logger.info("consumer subscribed", extra={"eventId": settings.trails_built_topic})
    try:
        while True:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                logger.error("consumer error: %s", msg.error())
                continue
            components.handler.handle(msg.value())
            consumer.commit(msg)
    finally:
        consumer.close()


class _ConfluentProducerAdapter:  # pragma: no cover - thin adapter over confluent-kafka
    """Adapts a confluent-kafka Producer to the :class:`MessageProducer` protocol."""

    def __init__(self, producer: object) -> None:
        self._producer = producer

    def produce(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        self._producer.produce(topic, value=value, key=key)

    def flush(self, timeout: float | None = None) -> int:
        return self._producer.flush(timeout if timeout is not None else 10.0)


def main(argv: list[str] | None = None) -> int:
    """Apply migrations, then optionally run the consumer loop."""
    args = argv if argv is not None else sys.argv[1:]
    settings = load_settings()
    configure_logging(settings.log_level)

    settings.require_database_url()
    try:
        apply_migrations(settings.database_url)
    except Exception as exc:  # noqa: BLE001 — abort startup on migration failure
        logger.error("migration failed; aborting startup: %s", exc)
        return 1

    if args and args[0] == "migrate":
        return 0

    _run_consumer_loop()  # pragma: no cover
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
