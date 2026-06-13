"""Runtime wiring: real confluent-kafka producer/consumers + Alembic upgrade.

Kept thin and import-light so unit tests never need a live broker. The
``confluent_kafka`` import is deferred into the functions that need it.
"""

from __future__ import annotations

import threading

from .config import Settings, get_settings
from .container import Container, build_container
from .db.engine import make_engine
from .observability import configure_logging, get_logger

_log = get_logger("trailbuilder.runtime")


def run_migrations(settings: Settings) -> None:
    """Run ``alembic upgrade head`` before serving (creates the schema + tables)."""
    import pathlib

    from alembic import command
    from alembic.config import Config

    root = pathlib.Path(__file__).resolve().parent.parent.parent
    cfg = Config(str(root / "alembic.ini"))
    cfg.set_main_option("script_location", str(root / "migrations"))
    cfg.set_main_option("sqlalchemy.url", settings.database_url)
    command.upgrade(cfg, "head")


def make_runtime_container(settings: Settings) -> Container:
    """Build a container backed by a real Kafka producer + DB engine."""
    from confluent_kafka import Producer  # deferred import

    producer = Producer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "enable.idempotence": True,
        }
    )
    engine = make_engine(settings.database_url)
    return build_container(settings, engine, _ConfluentProducerAdapter(producer))


class _ConfluentProducerAdapter:
    """Adapts confluent_kafka.Producer to the minimal Producer protocol."""

    def __init__(self, producer) -> None:  # type: ignore[no-untyped-def]
        self._producer = producer

    def produce(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        self._producer.produce(topic, value=value, key=key)

    def flush(self, timeout: float = 5.0) -> int:
        return self._producer.flush(timeout)


def _consume_topic(settings: Settings, topic: str, handle) -> None:  # type: ignore[no-untyped-def]
    """Poll one topic under its OWN consumer + ``<service>-<topic>`` group id.

    Each consumed topic gets its own group — so ``topology.changed`` is consumed
    under ``trail-builder-topology.changed`` and ``knowledge.updated`` under
    ``trail-builder-knowledge.updated`` (not one shared group). The two topics
    serve unrelated purposes (build trigger vs. policy-refresh), so independent
    groups keep their offset commits and rebalances decoupled — the convention
    the reviewer flagged.
    """
    from confluent_kafka import Consumer

    group_id = f"{settings.kafka_consumer_group}-{topic}"
    consumer = Consumer(
        {
            "bootstrap.servers": settings.kafka_bootstrap_servers,
            "group.id": group_id,
            "enable.auto.commit": True,
            "auto.offset.reset": "earliest",
        }
    )
    consumer.subscribe([topic])
    _log.info("kafka consumer started", extra={"topic": topic, "group": group_id})
    try:
        while True:
            msg = consumer.poll(1.0)
            if msg is None or msg.error():
                continue
            handle(msg.value())
    finally:
        consumer.close()


def start_consumers(settings: Settings, container: Container) -> list[threading.Thread]:
    """Start one daemon consumer thread per consumed topic (own group each)."""
    plan = [
        (settings.topology_changed_topic, container.topology_changed_handler.handle),
        (settings.knowledge_updated_topic, container.knowledge_updated_handler.handle),
    ]
    threads: list[threading.Thread] = []
    for topic, handle in plan:
        thread = threading.Thread(
            target=_consume_topic, args=(settings, topic, handle), daemon=True
        )
        thread.start()
        threads.append(thread)
    return threads


def main() -> None:
    """Entrypoint: migrate, start the per-topic consumer threads, serve the API."""
    import uvicorn

    from .api import create_app

    settings = get_settings()
    configure_logging(settings.log_level)
    run_migrations(settings)
    container = make_runtime_container(settings)

    start_consumers(settings, container)

    app = create_app(container)
    uvicorn.run(app, host="0.0.0.0", port=8000)  # noqa: S104
