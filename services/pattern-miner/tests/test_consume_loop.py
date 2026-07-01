"""Unit coverage of the consume->mine->produce loop internals (no broker, no Spark).

Drives ``app_mod.consume_loop`` directly with fake Kafka transport + respx-mocked Knowledge + the
pure-Python engine, asserting the loop: fetches params, windows+mines a fiber-cut, produces a
PatternMinedEvent, DLQ-routes poison, dedupes a duplicate, and fails a batch without producing when
Knowledge is unavailable (no stale/default mining).
"""

from __future__ import annotations

import json
import threading
from urllib.parse import quote

import httpx
import respx

from pattern_miner import app as app_mod
from pattern_miner.config import Settings
from pattern_miner.knowledge import MODEL_PARAMS_RECORD_TYPE
from pattern_miner.metrics import Metrics
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine

from .helpers import make_alarm, make_transaction, wrap

KNOWLEDGE_URL = "http://knowledge.test"
DOMAIN = "core-ip"
RECORD_ID = "core-ip/modelParams/pattern-miner"
FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]
MP_PATH = f"/domains/{DOMAIN}/{MODEL_PARAMS_RECORD_TYPE}/{quote(RECORD_ID, safe='')}"


def _record_envelope() -> dict:
    return {
        "domain": DOMAIN,
        "recordType": "modelParams",
        "recordId": RECORD_ID,
        "version": "v1",
        "isCurrent": True,
        "payload": {
            "paramSet": "pattern-miner",
            "params": [
                {"key": "prefixspan.minSupport", "value": 0.3},
                {"key": "prefixspan.maxPatternLength", "value": 10},
                {"key": "prefixspan.maxSequenceCount", "value": 1000},
                {"key": "window.adaptive.baseGapSeconds", "value": 5.0},
                {"key": "window.adaptive.gapMultiplier", "value": 3.0},
                {"key": "window.adaptive.tempoPercentile", "value": 95.0},
                {"key": "window.adaptive.profiles", "value": {"fast": 0.5, "slow": 30.0}},
                {"key": "codebookVersion", "value": "current"},
            ],
        },
    }


def _fiber_cut_bytes(event_id: str | None = None) -> bytes:
    alarms = []
    for s in range(4):
        base = s * 300.0
        for i, t in enumerate(FIBER_CUT):
            alarms.append(make_alarm(alarm_type=t, raised_offset_seconds=base + i))
    txn = make_transaction(trail_id="trail-loop", alarms=alarms)
    return json.dumps(wrap(txn, event_id=event_id)).encode()


class _Msg:
    def __init__(self, value):
        self._v = value

    def value(self):
        return self._v

    def error(self):
        return None

    def topic(self):
        return "transactions.clean"


class _FakeConsumer:
    def __init__(self, messages):
        self._msgs = list(messages)
        self.committed = 0

    def poll(self, timeout):
        return self._msgs.pop(0) if self._msgs else None

    def commit(self, msg=None):
        self.committed += 1

    def close(self):
        pass


class _CapturingProducer:
    def __init__(self, *a, **k):
        self.published = []
        self.raw = self

    def publish(self, topic, envelope):
        self.published.append((topic, envelope))

    def produce(self, topic, *, value, headers=None):
        self.published.append((topic, value))

    def poll(self, _):
        pass

    def flush(self, timeout=10.0):
        pass


def _run_loop(messages, monkeypatch, *, knowledge_up=True):
    captured = _CapturingProducer()
    consumer = _FakeConsumer(messages)
    monkeypatch.setattr(app_mod, "make_consumer", lambda *a, **k: consumer)
    monkeypatch.setattr(app_mod, "PatternProducer", lambda *a, **k: captured)

    settings = Settings(
        KNOWLEDGE_BASE_URL=KNOWLEDGE_URL,
        KNOWLEDGE_DOMAIN=DOMAIN,
        KNOWLEDGE_MODEL_PARAMS_RECORD_ID=RECORD_ID,
        MINING_ENGINE="local",
        BATCH_FLUSH_SECONDS=0.0,  # flush eagerly
        KNOWLEDGE_RETRY_MAX=0,
        KNOWLEDGE_RETRY_BACKOFF_MS=0,
    )
    metrics = Metrics()
    api_state = app_mod.ApiState(metrics_registry=metrics.registry)
    stop = threading.Event()

    router = respx.mock(assert_all_called=False, assert_all_mocked=False)
    if knowledge_up:
        router.get(f"{KNOWLEDGE_URL}{MP_PATH}").mock(
            return_value=httpx.Response(200, json=_record_envelope())
        )
    else:
        router.get(f"{KNOWLEDGE_URL}{MP_PATH}").mock(return_value=httpx.Response(503))

    # Stop the loop right after it drains the fake consumer (poll returns None -> flush -> exit).
    original_poll = consumer.poll

    def poll_then_stop(timeout):
        msg = original_poll(timeout)
        if msg is None:
            stop.set()
        return msg

    consumer.poll = poll_then_stop  # type: ignore[method-assign]

    with router:
        app_mod.consume_loop(
            settings=settings,
            engine=LocalPrefixSpanEngine(),
            knowledge=app_mod.build_knowledge_client(settings),
            metrics=metrics,
            api_state=api_state,
            stop_event=stop,
        )
    return captured, metrics, api_state


def test_loop_mines_and_produces_fiber_cut(monkeypatch):
    captured, metrics, api_state = _run_loop([_Msg(_fiber_cut_bytes())], monkeypatch)
    assert api_state.kafka_connected is True
    mined = [e for t, e in captured.published if t == "patterns.mined"]
    assert mined
    assert any(e.payload.sequence == FIBER_CUT for e in mined)
    assert metrics.mining_runs._value.get() >= 1


def test_loop_routes_poison_to_dlq(monkeypatch):
    captured, metrics, _ = _run_loop([_Msg(b"{bad json")], monkeypatch)
    dlq = [(t, v) for t, v in captured.published if t == "transactions.clean.dlq"]
    assert dlq, "poison message not routed to DLQ"
    assert not [t for t, _ in captured.published if t == "patterns.mined"]


def test_loop_dedupes_duplicate_event_id(monkeypatch):
    eid = "22222222-2222-2222-2222-222222222222"
    msgs = [_Msg(_fiber_cut_bytes(event_id=eid)), _Msg(_fiber_cut_bytes(event_id=eid))]
    captured, metrics, _ = _run_loop(msgs, monkeypatch)
    assert metrics.duplicates_dropped._value.get() >= 1


def test_loop_knowledge_down_fails_batch_no_emit(monkeypatch):
    captured, metrics, _ = _run_loop([_Msg(_fiber_cut_bytes())], monkeypatch, knowledge_up=False)
    assert not [t for t, _ in captured.published if t == "patterns.mined"]
    assert metrics.mining_failures._value.get() >= 1
