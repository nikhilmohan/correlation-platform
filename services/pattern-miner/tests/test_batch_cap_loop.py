"""[BATCH-CAP] flush()-level batch-cap + SparkContext resilience (BC-5, BC-6, BC-7).

Drives ``app_mod.consume_loop`` with fake Kafka transport + respx-mocked Knowledge/Codebook, the
pure-Python engine, and a tiny ``MAX_TRAILS_PER_BATCH`` so a small corpus forces multiple sub-runs.
Proves: offsets commit exactly once per flush after all sub-runs (BC-5); a gateway-death mid-sub-run
resets the engine, recreates, and the sub-run then succeeds (BC-6); recreate exhaustion fails the
run clean (no commit) and dips /health Spark-not-ready, then self-heals (BC-7).

Spark itself is container-only, so the death is injected via a fake engine that raises the
gateway-death error class — exactly what :func:`pattern_miner.app.is_gateway_death` classifies.
"""

from __future__ import annotations

import json
import threading
from urllib.parse import quote

import httpx
import respx

from pattern_miner import app as app_mod
from pattern_miner.app import is_gateway_death
from pattern_miner.config import Settings
from pattern_miner.knowledge import MODEL_PARAMS_RECORD_TYPE
from pattern_miner.metrics import Metrics
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine

from .helpers import make_alarm, make_transaction, wrap

KNOWLEDGE_URL = "http://knowledge.test"
CODEBOOK_URL = "http://codebook.test"
DOMAIN = "core-ip"
RECORD_ID = "core-ip/modelParams/pattern-miner"
CHAIN = ["FaultA", "FaultB", "FaultC"]
MP_PATH = f"/domains/{DOMAIN}/{MODEL_PARAMS_RECORD_TYPE}/{quote(RECORD_ID, safe='')}"
CODEBOOK_ID = "cb-cap-1"


class _Py4JNetworkError(RuntimeError):
    """Stand-in for py4j's Py4JNetworkError (classified by name, so no py4j import needed)."""


def _scenarios_body() -> dict:
    return {
        "codebookId": CODEBOOK_ID,
        "domain": DOMAIN,
        "scenarios": [
            {
                "scenarioId": "SC-A",
                "faultOriginObjectId": "obj-a",
                "faultOriginType": "OriginA",
                "predictedSymptoms": [
                    {"alarmType": t, "managedObjectId": f"obj-{t}"} for t in CHAIN
                ],
                "trailIds": [],
            }
        ],
    }


def _record_envelope(cap: int | None = None) -> dict:
    params = [
        {"key": "prefixspan.minSupport", "value": 0.5},
        {"key": "prefixspan.maxPatternLength", "value": 10},
        {"key": "prefixspan.maxSequenceCount", "value": 1000},
        {"key": "window.adaptive.baseGapSeconds", "value": 5.0},
        {"key": "window.adaptive.gapMultiplier", "value": 3.0},
        {"key": "window.adaptive.tempoPercentile", "value": 95.0},
        {"key": "window.adaptive.profiles", "value": {"fast": 0.5, "slow": 30.0}},
        {"key": "anchoring.matchConfidenceThreshold", "value": 0.5},
        {"key": "anchoring.weights.order", "value": 0.7},
        {"key": "anchoring.weights.jaccard", "value": 0.3},
        {"key": "codebookVersion", "value": "current"},
        {"key": "sample.maxAlarms", "value": 10},
    ]
    if cap is not None:
        params.append({"key": "batching.maxTrailsPerBatch", "value": cap})
    return {
        "domain": DOMAIN,
        "recordType": "modelParams",
        "recordId": RECORD_ID,
        "version": "v1",
        "isCurrent": True,
        "payload": {"paramSet": "pattern-miner", "params": params},
    }


def _cascade_bytes(trail_id: str, event_id: str | None = None) -> bytes:
    alarms = [make_alarm(alarm_type=t, raised_offset_seconds=i * 0.5) for i, t in enumerate(CHAIN)]
    txn = make_transaction(trail_id=trail_id, alarms=alarms)
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
        self.commit_calls: list = []

    def poll(self, timeout):
        return self._msgs.pop(0) if self._msgs else None

    def commit(self, msg=None):
        self.commit_calls.append(msg)

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


class _DeathThenHealEngine:
    """Engine that raises a gateway-death N times on run(), healing after reset() past budget."""

    def __init__(self, *, deaths: int, always_dead: bool = False):
        self._deaths_remaining = deaths
        self._always_dead = always_dead
        self._delegate = LocalPrefixSpanEngine()
        self.reset_calls = 0
        self._healthy = True

    def run(self, sequences, *, min_support, max_pattern_length):
        if self._always_dead or self._deaths_remaining > 0:
            if not self._always_dead:
                self._deaths_remaining -= 1
            self._healthy = False
            raise _Py4JNetworkError("Answer from Java side is empty")
        return self._delegate.run(
            sequences, min_support=min_support, max_pattern_length=max_pattern_length
        )

    def reset(self):
        self.reset_calls += 1
        self._healthy = True  # a fresh session would rebuild on next run

    def is_healthy(self):
        return self._healthy


def _make_settings(*, cap: int, **overrides) -> Settings:
    base = dict(
        KNOWLEDGE_BASE_URL=KNOWLEDGE_URL,
        KNOWLEDGE_DOMAIN=DOMAIN,
        KNOWLEDGE_MODEL_PARAMS_RECORD_ID=RECORD_ID,
        CODEBOOK_BASE_URL=CODEBOOK_URL,
        MINING_ENGINE="local",
        # High flush interval so ALL fake messages pool into ONE flush; the drain (poll -> None ->
        # stop) triggers the single finally-flush. This lets one flush hold many trails -> sub-runs.
        BATCH_FLUSH_SECONDS=3600.0,
        KNOWLEDGE_RETRY_MAX=0,
        KNOWLEDGE_RETRY_BACKOFF_MS=0,
        CODEBOOK_RETRY_MAX=0,
        CODEBOOK_RETRY_BACKOFF_MS=0,
        MAX_TRAILS_PER_BATCH=str(cap),
        SPARK_RECREATE_BACKOFF_MS=0,
    )
    base.update(overrides)
    return Settings(**base)


def _run_loop(messages, monkeypatch, *, settings, engine, knowledge_cap=None):
    captured = _CapturingProducer()
    consumer = _FakeConsumer(messages)
    monkeypatch.setattr(app_mod, "make_consumer", lambda *a, **k: consumer)
    monkeypatch.setattr(app_mod, "PatternProducer", lambda *a, **k: captured)

    metrics = Metrics()
    api_state = app_mod.ApiState(metrics_registry=metrics.registry)
    stop = threading.Event()

    router = respx.mock(assert_all_called=False, assert_all_mocked=False)
    router.get(f"{KNOWLEDGE_URL}{MP_PATH}").mock(
        return_value=httpx.Response(200, json=_record_envelope(cap=knowledge_cap))
    )
    router.get(f"{CODEBOOK_URL}/codebooks/active").mock(
        return_value=httpx.Response(200, json={"codebookId": CODEBOOK_ID, "domain": DOMAIN})
    )
    router.get(f"{CODEBOOK_URL}/codebooks/{CODEBOOK_ID}/scenarios").mock(
        return_value=httpx.Response(200, json=_scenarios_body())
    )

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
            engine=engine,
            knowledge=app_mod.build_knowledge_client(settings),
            codebook=app_mod.build_codebook_client(settings),
            metrics=metrics,
            api_state=api_state,
            stop_event=stop,
        )
    return captured, metrics, api_state, consumer


# ------------------------------------------------------------------ BC-1 (loop) + BC-5


def test_flush_chunks_into_subruns_and_commits_once(monkeypatch):
    """BC-5: 5 trails with cap=2 -> 3 sub-runs; each emits; commit() called EXACTLY once."""
    msgs = [_Msg(_cascade_bytes(f"trail-{k}")) for k in range(5)]
    settings = _make_settings(cap=2)
    captured, metrics, _, consumer = _run_loop(
        msgs, monkeypatch, settings=settings, engine=LocalPrefixSpanEngine()
    )
    mined = [e for t, e in captured.published if t == "patterns.mined"]
    assert mined, "capped flush emitted nothing"
    # 3 sub-runs (ceil(5/2)); the mining-sub-runs counter reflects that.
    assert metrics.mining_sub_runs._value.get() == 3
    assert metrics.last_flush_sub_run_count._value.get() == 3
    # offsets committed ONCE for the whole flush (not per sub-run).
    assert len(consumer.commit_calls) == 1


def test_flush_failure_leaves_offsets_uncommitted(monkeypatch):
    """BC-5: a mid-flush Spark failure (recreate-exhausted) leaves commit() uncalled -> replay."""
    msgs = [_Msg(_cascade_bytes(f"trail-{k}")) for k in range(4)]
    settings = _make_settings(cap=2, SPARK_RECREATE_MAX_ATTEMPTS=1)
    engine = _DeathThenHealEngine(deaths=0, always_dead=True)
    captured, metrics, api_state, consumer = _run_loop(
        msgs, monkeypatch, settings=settings, engine=engine
    )
    assert not [t for t, _ in captured.published if t == "patterns.mined"]
    assert consumer.commit_calls == []  # whole flush replays
    assert metrics.mining_failures._value.get() >= 1


# ------------------------------------------------------------------ BC-6


def test_spark_gateway_death_triggers_reset_and_recreate(monkeypatch):
    """BC-6: one gateway death -> engine.reset() called, recreate, the sub-run then succeeds."""
    msgs = [_Msg(_cascade_bytes("trail-a"))]
    settings = _make_settings(cap=8, SPARK_RECREATE_MAX_ATTEMPTS=3)
    engine = _DeathThenHealEngine(deaths=1)  # dies once, heals on retry
    captured, metrics, api_state, consumer = _run_loop(
        msgs, monkeypatch, settings=settings, engine=engine
    )
    mined = [e for t, e in captured.published if t == "patterns.mined"]
    assert mined, "sub-run did not recover after reset+recreate"
    assert engine.reset_calls == 1
    assert metrics.spark_recreate_attempts._value.get() == 1
    assert metrics.spark_recreate_failures._value.get() == 0
    assert api_state.spark_ready is True  # self-healed to ready
    assert len(consumer.commit_calls) == 1  # committed after recovery


def test_engine_reset_nulls_cached_session():
    """BC-6 companion: SparkPrefixSpanEngine.reset() drops the cached session; is_healthy flips."""
    from pattern_miner.mining.spark_engine import SparkPrefixSpanEngine

    class _FakeSession:
        def __init__(self):
            self.stopped = False

        def stop(self):
            self.stopped = True

    engine = SparkPrefixSpanEngine()
    engine._spark = _FakeSession()  # simulate a built (then dead) session
    engine._recreatable = True
    assert engine.is_healthy() is True
    engine.reset()
    assert engine._spark is None
    assert engine.is_healthy() is False  # next run() will rebuild


# ------------------------------------------------------------------ BC-7


def test_spark_recreate_exhaustion_fails_clean_and_health_not_ready(monkeypatch):
    """BC-7: always-dead engine -> no commit, spark-recreate-failures++, /health Spark not-ready."""
    msgs = [_Msg(_cascade_bytes("trail-a"))]
    settings = _make_settings(cap=8, SPARK_RECREATE_MAX_ATTEMPTS=2)
    engine = _DeathThenHealEngine(deaths=0, always_dead=True)
    captured, metrics, api_state, consumer = _run_loop(
        msgs, monkeypatch, settings=settings, engine=engine
    )
    assert not [t for t, _ in captured.published if t == "patterns.mined"]
    assert consumer.commit_calls == []  # replayable
    assert metrics.spark_recreate_failures._value.get() == 1
    assert metrics.spark_recreate_attempts._value.get() == 2  # bounded attempts before failing
    assert api_state.spark_ready is False  # /health would report Spark not-ready


def test_spark_readiness_self_heals_after_recovery(monkeypatch):
    """BC-7: readiness never latches DOWN — a dip to not-ready flips back to ready on success.

    A single engine dies twice then heals; across the retries spark_ready dips to False but the
    successful run at the end restores it to True (self-heal, no container restart).
    """
    msgs = [_Msg(_cascade_bytes("trail-a"))]
    settings = _make_settings(cap=8, SPARK_RECREATE_MAX_ATTEMPTS=3)
    engine = _DeathThenHealEngine(deaths=2)
    _, metrics, api_state, consumer = _run_loop(msgs, monkeypatch, settings=settings, engine=engine)
    assert engine.reset_calls == 2
    assert api_state.spark_ready is True  # latched back UP after recovery
    assert len(consumer.commit_calls) == 1


# ------------------------------------------------------------------ gateway-death classifier


def test_is_gateway_death_classifies_death_classes():
    """The classifier matches Py4J deaths / empty-answer / connection-refused; not other errors."""
    assert is_gateway_death(_Py4JNetworkError("Answer from Java side is empty"))
    assert is_gateway_death(ConnectionRefusedError("connection refused"))
    assert is_gateway_death(ConnectionError("boom"))
    assert is_gateway_death(OSError("Connection refused"))
    assert is_gateway_death(RuntimeError("answer from java side is empty"))
    # a plain domain/logic error is NOT a gateway death (must not trigger the recreate loop).
    assert not is_gateway_death(ValueError("min_support out of range"))
    assert not is_gateway_death(KeyError("missing"))
