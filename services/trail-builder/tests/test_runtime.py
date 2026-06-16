"""Runtime-wiring tests: the confluent producer adapter, container assembly,
the Alembic upgrade invocation, and the consume-loop dispatch.

No live broker / DB is touched: ``confluent_kafka`` and the Alembic ``command``
are stubbed, so these tests exercise the wiring (group-id convention, topic
subscription, message dispatch to the right handler) deterministically.
"""

from __future__ import annotations

import sys
import types

import pytest

from trailbuilder import runtime
from trailbuilder.container import Container


class _FakeConfluentProducer:
    def __init__(self, conf: dict) -> None:
        self.conf = conf
        self.produced: list[tuple[str, bytes, bytes | None]] = []
        self.flushed = False

    def produce(self, topic, value=None, key=None):  # type: ignore[no-untyped-def]
        self.produced.append((topic, value, key))

    def flush(self, timeout=5.0):  # type: ignore[no-untyped-def]
        self.flushed = True
        return 0


def test_producer_adapter_delegates_to_confluent() -> None:
    fake = _FakeConfluentProducer({})
    adapter = runtime._ConfluentProducerAdapter(fake)
    adapter.produce("trails.built", b"payload", key=b"k")
    assert fake.produced == [("trails.built", b"payload", b"k")]
    assert adapter.flush(1.0) == 0
    assert fake.flushed is True


def test_make_runtime_container_builds_with_idempotent_producer(monkeypatch, settings) -> None:
    """make_runtime_container wires a real engine + an idempotence-enabled producer."""
    captured: dict[str, dict] = {}

    fake_module = types.ModuleType("confluent_kafka")

    def _producer(conf):  # type: ignore[no-untyped-def]
        captured["conf"] = conf
        return _FakeConfluentProducer(conf)

    fake_module.Producer = _producer  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "confluent_kafka", fake_module)

    container = runtime.make_runtime_container(settings)
    assert isinstance(container, Container)
    # enable.idempotence is set on the producer (at-least-once safety).
    assert captured["conf"]["enable.idempotence"] is True
    assert captured["conf"]["bootstrap.servers"] == settings.kafka_bootstrap_servers


def test_run_migrations_invokes_alembic_upgrade_head(monkeypatch, settings) -> None:
    """run_migrations points Alembic at the service's migrations and upgrades to head."""
    calls: dict[str, object] = {}

    import alembic.command as alembic_command

    def _upgrade(cfg, rev):  # type: ignore[no-untyped-def]
        calls["rev"] = rev
        calls["script_location"] = cfg.get_main_option("script_location")
        calls["url"] = cfg.get_main_option("sqlalchemy.url")

    # Patch only ``upgrade`` on the real module so ``alembic.config`` stays intact.
    monkeypatch.setattr(alembic_command, "upgrade", _upgrade)

    runtime.run_migrations(settings)
    assert calls["rev"] == "head"
    assert str(calls["script_location"]).endswith("migrations")
    assert calls["url"] == settings.database_url


class _Msg:
    """Minimal confluent-kafka message stand-in."""

    def __init__(self, value: bytes) -> None:
        self._value = value

    def value(self) -> bytes:
        return self._value

    def error(self):  # type: ignore[no-untyped-def]
        return None


def _install_fake_consumer(monkeypatch, queue, captured) -> None:  # type: ignore[no-untyped-def]
    class _FakeConsumer:
        def __init__(self, conf: dict) -> None:
            captured["conf"] = conf
            self._queue = list(queue)

        def subscribe(self, topics):  # type: ignore[no-untyped-def]
            captured["topics"] = topics

        def poll(self, timeout):  # type: ignore[no-untyped-def]
            if not self._queue:
                raise KeyboardInterrupt  # break out of the infinite loop
            return self._queue.pop(0)

        def close(self) -> None:
            captured["closed"] = True

    fake_module = types.ModuleType("confluent_kafka")
    fake_module.Consumer = _FakeConsumer  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "confluent_kafka", fake_module)


def test_consume_topic_dispatches_under_conventioned_group(monkeypatch, settings) -> None:
    """Each topic is consumed under its OWN ``<service>-<topic>`` group id and
    its messages are routed to the supplied handler."""
    topo_topic = settings.topology_changed_topic
    seen: list[bytes] = []
    captured: dict[str, object] = {}
    _install_fake_consumer(monkeypatch, [_Msg(b"topo-1"), None, _Msg(b"topo-2")], captured)

    with pytest.raises(KeyboardInterrupt):
        runtime._consume_topic(settings, topo_topic, seen.append)

    # The idle (None) poll is skipped, not a terminator: both real messages dispatch.
    assert seen == [b"topo-1", b"topo-2"]
    assert captured["conf"]["group.id"] == f"{settings.kafka_consumer_group}-{topo_topic}"
    assert captured["topics"] == [topo_topic]
    assert captured["closed"] is True


def test_knowledge_topic_uses_its_own_group(monkeypatch, settings) -> None:
    """``knowledge.updated`` is consumed under ``trail-builder-knowledge.updated``,
    NOT the topology group (per-topic group-id convention)."""
    know_topic = settings.knowledge_updated_topic
    seen: list[bytes] = []
    captured: dict[str, object] = {}
    _install_fake_consumer(monkeypatch, [_Msg(b"know-1"), None], captured)

    with pytest.raises(KeyboardInterrupt):
        runtime._consume_topic(settings, know_topic, seen.append)

    assert seen == [b"know-1"]
    assert captured["conf"]["group.id"] == f"{settings.kafka_consumer_group}-{know_topic}"
    assert captured["conf"]["group.id"] != f"{settings.kafka_consumer_group}-topology.changed"
    assert captured["topics"] == [know_topic]


def test_start_consumers_spawns_one_thread_per_topic(monkeypatch, settings) -> None:
    """start_consumers launches one daemon consumer thread per consumed topic,
    each bound to its own topic + handler."""
    started: list[tuple[str, object]] = []

    def _fake_consume(s, topic, handle):  # type: ignore[no-untyped-def]
        started.append((topic, handle))

    monkeypatch.setattr(runtime, "_consume_topic", _fake_consume)

    container = types.SimpleNamespace(
        topology_changed_handler=types.SimpleNamespace(handle=lambda raw: None),
        knowledge_updated_handler=types.SimpleNamespace(handle=lambda raw: None),
    )
    threads = runtime.start_consumers(settings, container)  # type: ignore[arg-type]
    for t in threads:
        t.join(timeout=2.0)

    topics = {topic for topic, _ in started}
    assert topics == {settings.topology_changed_topic, settings.knowledge_updated_topic}
    assert len(threads) == 2
    assert all(t.daemon for t in threads)
