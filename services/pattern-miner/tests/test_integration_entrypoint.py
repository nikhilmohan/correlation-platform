"""Integration-tagged proof the REAL entrypoint serves HTTP + consumes->mines->produces.

This is the catch-net for the two structural bugs unit tests can hide:
  (a) the HTTP server never actually starts (nothing serves /health, /metrics), and
  (b) the consume->mine->produce loop is never wired to the real ``serve()`` (mines nothing /
      produces nothing when actually run).

It starts ``pattern_miner.app.serve`` — the ACTUAL assembled entrypoint — with:
  * Kafka transport faked in-process (no broker in the CI container): a fake consumer feeds ONE
    real ``transactions.clean`` message (a fiber-cut storm) and a fake producer captures what is
    produced to ``patterns.mined``;
  * Knowledge served by respx (the real ``GET /domains/{domain}/model-params/{recordId}`` route +
    enveloped ``payload.params[]`` shape) — the startup gate + per-run params;
  * the pure-Python PrefixSpan engine (Spark is exercised by the ``spark``-marked test).

Then it asserts over a REAL HTTP socket that /health + /metrics serve, and that the real loop
mined the fiber-cut sequence and produced a ``PatternMinedEvent`` on ``patterns.mined``.

Marked ``integration`` (deselected by the unit gate; the integration stage runs it).
"""

from __future__ import annotations

import asyncio
import json
from urllib.parse import quote

import httpx
import pytest

pytestmark = pytest.mark.integration

respx = pytest.importorskip("respx")

from acp_event_model import deserialize  # noqa: E402

from pattern_miner import app as app_mod  # noqa: E402
from pattern_miner.config import Settings  # noqa: E402
from pattern_miner.knowledge import MODEL_PARAMS_RECORD_TYPE  # noqa: E402
from pattern_miner.metrics import Metrics  # noqa: E402

from .helpers import make_alarm, make_transaction, wrap  # noqa: E402

KNOWLEDGE_URL = "http://knowledge.test"
DOMAIN = "core-ip"
RECORD_ID = "core-ip/modelParams/pattern-miner"
HTTP_PORT = 8189
FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]


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


def _fiber_cut_message() -> bytes:
    """A transactions.clean message: ONE trail, several fiber-cut sessions (support high)."""
    alarms = []
    for s in range(4):
        base = s * 300.0
        for i, t in enumerate(FIBER_CUT):
            alarms.append(make_alarm(alarm_type=t, raised_offset_seconds=base + i))
    txn = make_transaction(trail_id="trail-int", alarms=alarms)
    return json.dumps(wrap(txn, trace_id="trace-int")).encode()


class _FakeMessage:
    def __init__(self, value: bytes) -> None:
        self._value = value

    def value(self):
        return self._value

    def error(self):
        return None

    def topic(self):
        return "transactions.clean"


class _FakeConsumer:
    """Delivers the one fiber-cut message once, then returns None (idle)."""

    def __init__(self, value: bytes) -> None:
        self._queue = [_FakeMessage(value)]

    def poll(self, timeout):
        return self._queue.pop(0) if self._queue else None

    def commit(self, msg=None):
        pass

    def close(self):
        pass


class _CapturingProducer:
    """In-process producer capturing what the real loop publishes to patterns.mined / DLQ."""

    def __init__(self, *a, **k) -> None:
        self.published: list = []  # list[(topic, envelope)]
        self.raw = self

    def publish(self, topic, envelope):
        self.published.append((topic, envelope))

    def produce(self, topic, *, value, headers=None):
        self.published.append((topic, value))

    def poll(self, _):
        pass

    def flush(self, timeout=10.0):
        pass


@pytest.mark.asyncio
async def test_real_entrypoint_serves_http_and_mines_and_produces(monkeypatch):
    captured = _CapturingProducer()

    monkeypatch.setattr(
        app_mod, "make_consumer", lambda *a, **k: _FakeConsumer(_fiber_cut_message())
    )
    monkeypatch.setattr(app_mod, "PatternProducer", lambda *a, **k: captured)

    router = respx.mock(assert_all_called=False, assert_all_mocked=False)
    router.route(host="127.0.0.1").pass_through()
    router.route(host="localhost").pass_through()
    mp_path = f"/domains/{DOMAIN}/{MODEL_PARAMS_RECORD_TYPE}/{quote(RECORD_ID, safe='')}"
    router.get(f"{KNOWLEDGE_URL}{mp_path}").mock(
        return_value=httpx.Response(200, json=_record_envelope())
    )

    settings = Settings(
        KNOWLEDGE_BASE_URL=KNOWLEDGE_URL,
        KNOWLEDGE_DOMAIN=DOMAIN,
        KNOWLEDGE_MODEL_PARAMS_RECORD_ID=RECORD_ID,
        KAFKA_BOOTSTRAP_SERVERS="localhost:9092",
        HTTP_PORT=HTTP_PORT,
        MINING_ENGINE="local",
        BATCH_FLUSH_SECONDS=0.5,
    )
    metrics = Metrics()

    with router:
        serve_task = asyncio.create_task(app_mod.serve(settings, metrics))
        try:
            base = f"http://127.0.0.1:{HTTP_PORT}"
            async with httpx.AsyncClient(timeout=5.0) as client:
                await _await_http_ready(client, base)
                # (a) /health + /metrics serve over a real socket.
                health = await client.get(f"{base}/health")
                assert health.status_code == 200, health.text
                assert health.json()["knowledge"] == "up"
                metrics_resp = await client.get(f"{base}/metrics")
                assert metrics_resp.status_code == 200
                assert "pm_patterns_emitted_total" in metrics_resp.text

                # (b) the real loop mined the fiber-cut sequence and produced it to patterns.mined.
                produced = await _await_produced(captured)
            assert produced, "no PatternMinedEvent was produced by the real entrypoint"
            sequences = []
            for topic, envelope in produced:
                assert topic == "patterns.mined"
                # envelope is a TypedEnvelope; round-trip through the codec proves valid wire shape.
                typed = deserialize(envelope.to_json())
                assert typed.type == "PatternMinedEvent"
                sequences.append(typed.payload.sequence)
            assert FIBER_CUT in sequences
        finally:
            serve_task.cancel()
            with pytest.raises((asyncio.CancelledError, Exception)):
                await serve_task


async def _await_http_ready(client: httpx.AsyncClient, base: str) -> None:
    for _ in range(100):
        try:
            r = await client.get(f"{base}/health")
            if r.status_code == 200:
                return
        except httpx.HTTPError:
            pass
        await asyncio.sleep(0.1)
    raise AssertionError("HTTP server did not become reachable")


async def _await_produced(producer: _CapturingProducer) -> list:
    for _ in range(100):
        pm = [(t, e) for t, e in producer.published if t == "patterns.mined"]
        if pm:
            return pm
        await asyncio.sleep(0.1)
    return []
