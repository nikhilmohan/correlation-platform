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


def test_consume_loop_dispatches_to_correct_handler(monkeypatch, settings) -> None:
    """The consume loop routes topology vs knowledge messages to the right handler
    and uses the <service>-<topic> group-id convention."""
    topo_calls: list[bytes] = []
    know_calls: list[bytes] = []

    class _Handler:
        def __init__(self, sink: list[bytes]) -> None:
            self._sink = sink

        def handle(self, raw: bytes) -> None:
            self._sink.append(raw)

    container = types.SimpleNamespace(
        topology_changed_handler=_Handler(topo_calls),
        knowledge_updated_handler=_Handler(know_calls),
    )

    topo_topic = settings.topology_changed_topic
    know_topic = settings.knowledge_updated_topic

    class _Msg:
        def __init__(self, topic: str, value: bytes) -> None:
            self._topic = topic
            self._value = value

        def topic(self) -> str:
            return self._topic

        def value(self) -> bytes:
            return self._value

        def error(self):  # type: ignore[no-untyped-def]
            return None

    msgs = [
        _Msg(topo_topic, b"topo-1"),
        _Msg(know_topic, b"know-1"),
        None,  # idle poll -> skipped
    ]
    captured_conf: dict[str, dict] = {}

    class _FakeConsumer:
        def __init__(self, conf: dict) -> None:
            captured_conf["conf"] = conf
            self._queue = list(msgs)

        def subscribe(self, topics):  # type: ignore[no-untyped-def]
            captured_conf["topics"] = topics

        def poll(self, timeout):  # type: ignore[no-untyped-def]
            if not self._queue:
                raise KeyboardInterrupt  # break out of the infinite loop
            return self._queue.pop(0)

        def close(self) -> None:
            captured_conf["closed"] = True

    fake_module = types.ModuleType("confluent_kafka")
    fake_module.Consumer = _FakeConsumer  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "confluent_kafka", fake_module)

    with pytest.raises(KeyboardInterrupt):
        runtime._consume_loop(settings, container)  # type: ignore[arg-type]

    assert topo_calls == [b"topo-1"]
    assert know_calls == [b"know-1"]
    assert captured_conf["conf"]["group.id"] == f"{settings.kafka_consumer_group}-{topo_topic}"
    assert set(captured_conf["topics"]) == {topo_topic, know_topic}
    assert captured_conf["closed"] is True
