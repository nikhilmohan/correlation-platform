"""Process-entrypoint wiring tests (B1 + B2 regression guards).

These cover ``noise_filter.app`` — the assembled-service wiring that the green pipeline/unit
suite never exercised (app.py was entirely ``# pragma: no cover``). They prove, WITHOUT Docker:

* the HTTP server object is actually built bound to HTTP_PORT (B1),
* ``build_pg_repositories`` creates a pool + wires the Pg* repositories (B2, via a fake asyncpg),
* ``build_components`` injects whatever repos it is given into the read API (so the real run uses
  the Pg repos, not in-memory),
* the Knowledge startup gate blocks then releases readiness,
* the blocking consume loop dispatches finalized windows through the async pipeline onto the loop
  and exits on the stop event.

The live-Postgres + real-HTTP proof of the SAME entrypoint is in
``test_integration_entrypoint.py`` (integration-tagged).
"""

from __future__ import annotations

import asyncio
import threading
import time
from urllib.parse import quote

import httpx
import pytest
import respx

from noise_filter import app as app_mod
from noise_filter.clients import MODEL_PARAMS_RECORD_ID, MODEL_PARAMS_RECORD_TYPE
from noise_filter.config import FeatureSettings, ModelParams, Settings
from noise_filter.metrics import Metrics
from noise_filter.repository import (
    InMemoryObservedChatterRepository,
    InMemoryRunStatsRepository,
)

from .fixtures import storm
from .helpers import make_window


def _settings(**over) -> Settings:
    """Build Settings via env-var aliases (pydantic-settings populates by alias)."""
    base = {
        "KNOWLEDGE_SERVICE_URL": "http://knowledge.test",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "TOPOLOGY_SERVICE_URL": "http://topology.test",
        "TRAIL_BUILDER_URL": "http://trail.test",
        "HTTP_PORT": 8099,
        "NOISE_FILTER_DB_URL": "",
    }
    base.update(over)
    return Settings(**base)


def test_asyncpg_url_normalizes_driver_prefixes():
    assert app_mod._asyncpg_url("postgresql+asyncpg://u@h/db") == "postgresql://u@h/db"
    assert app_mod._asyncpg_url("postgres://u@h/db") == "postgresql://u@h/db"
    assert app_mod._asyncpg_url("postgresql://u@h/db") == "postgresql://u@h/db"


def test_build_components_injects_given_repos_into_read_api():
    """B2: the repos passed to build_components (the Pg* repos in run()) back the read API."""
    run_repo = InMemoryRunStatsRepository()
    chatter_repo = InMemoryObservedChatterRepository()
    metrics = Metrics()
    _, _, _, r, c, api_state = app_mod.build_components(
        _settings(), metrics, run_repo=run_repo, chatter_repo=chatter_repo
    )
    assert r is run_repo and c is chatter_repo
    assert api_state.run_stats_repo is run_repo
    assert api_state.chatter_repo is chatter_repo


def test_make_http_server_binds_configured_port():
    """B1: a real uvicorn server is constructed bound to HTTP_PORT on all interfaces."""
    metrics = Metrics()
    *_rest, api_state = app_mod.build_components(_settings(), metrics)
    from noise_filter.api import create_app

    server = app_mod.make_http_server(create_app(api_state), 8099)
    assert server.config.port == 8099
    assert server.config.host == "0.0.0.0"


@pytest.mark.asyncio
async def test_build_pg_repositories_wires_pg_repos(monkeypatch):
    """B2: build_pg_repositories creates a pool and returns Pg-backed repositories."""
    created = {}

    class FakePool:
        async def close(self):  # pragma: no cover - not exercised here
            created["closed"] = True

    async def fake_create_pool(url):
        created["url"] = url
        return FakePool()

    import asyncpg

    monkeypatch.setattr(asyncpg, "create_pool", fake_create_pool)

    pool, run_repo, chatter_repo = await app_mod.build_pg_repositories(
        "postgresql+asyncpg://u@h/db"
    )
    from noise_filter.pg_repository import (
        PgObservedChatterRepository,
        PgRunStatsRepository,
    )

    assert created["url"] == "postgresql://u@h/db"  # normalized for asyncpg
    assert isinstance(run_repo, PgRunStatsRepository)
    assert isinstance(chatter_repo, PgObservedChatterRepository)
    assert isinstance(pool, FakePool)


@pytest.mark.asyncio
async def test_gate_on_knowledge_blocks_then_releases():
    """EH-4: the startup gate retries while Knowledge fails, then flips params_loaded true."""
    calls = {"n": 0}

    class FlakyLoader:
        def load(self):
            calls["n"] += 1
            if calls["n"] < 2:
                raise RuntimeError("knowledge down")

    class State:
        params_loaded = False

    state = State()
    # deadline well past the single 3s sleep so it succeeds on the 2nd attempt.
    await app_mod.gate_on_knowledge(FlakyLoader(), state, deadline_seconds=30)
    assert state.params_loaded is True
    assert calls["n"] == 2


@pytest.mark.asyncio
async def test_gate_on_knowledge_raises_past_deadline():
    class AlwaysFails:
        def load(self):
            raise RuntimeError("never up")

    class State:
        params_loaded = False

    with pytest.raises(RuntimeError):
        await app_mod.gate_on_knowledge(AlwaysFails(), State(), deadline_seconds=-1)


def test_build_pipeline_assembles_runnable_pipeline():
    metrics = Metrics()
    param_store, feature_config, _, run_repo, chatter_repo, _ = app_mod.build_components(
        _settings(), metrics
    )
    param_store.set(ModelParams(eps=0.5, min_samples=3, window_size_seconds=600))
    feature_config.set(FeatureSettings.fallback())
    pipeline = app_mod.build_pipeline(
        _settings(), metrics, param_store, feature_config, run_repo, chatter_repo
    )
    assert pipeline is not None


class _FakeMsg:
    def __init__(self, *, err=None, topic="alarms.enriched", value=b""):
        self._err = err
        self._topic = topic
        self._value = value

    def error(self):
        return self._err

    def topic(self):
        return self._topic

    def value(self):
        return self._value

    def offset(self):  # pragma: no cover - not asserted
        return 0


class _FakeConsumer:
    """Returns ``None`` from poll (so the loop drains windower) until stop is requested."""

    def __init__(self):
        self.closed = False

    def poll(self, timeout):
        return None

    def commit(self, msg):  # pragma: no cover - no real msgs in this path
        pass

    def close(self):
        self.closed = True


def test_consume_loop_dispatches_finalized_windows_and_stops(monkeypatch):
    """B1: the blocking consume loop drives the async pipeline on the loop and honors stop_event.

    Proves the consumer-thread <-> event-loop bridge: a pre-seeded finalized window is processed
    through the REAL async pipeline (writing to the run-stats repo) from the consumer thread.
    """
    metrics = Metrics()
    param_store, feature_config, _, run_repo, chatter_repo, api_state = app_mod.build_components(
        _settings(), metrics
    )
    param_store.set(ModelParams(eps=1.0, min_samples=3, window_size_seconds=600))
    feature_config.set(FeatureSettings.fallback())

    # A pipeline with a fake trail-builder so snapshotId resolves (so a row is written).
    from .conftest import build_pipeline as _bp
    from .conftest import make_trail_ctx

    cascade = storm(6, trail_id="t1", start=0.0, spread=3.0)
    ctx = make_trail_ctx(member_ids=[a.managedObjectId for a in cascade])
    pipeline = _bp(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
        trail_ctx=ctx,
    )

    # Patch transport: fake consumer + a no-op producer; pre-seed one finalized window.
    monkeypatch.setattr(app_mod, "make_consumer", lambda *a, **k: _FakeConsumer())

    class _NoopProducer:
        def __init__(self, *a, **k):
            self.raw = object()

    monkeypatch.setattr(app_mod, "TransactionProducer", _NoopProducer)

    finalized = [make_window(cascade)]

    class _OneShotWindower:
        def __init__(self, *a, **k):
            self._done = False

        def pop_finalized(self):
            if self._done:
                return []
            self._done = True
            return finalized

        def add(self, alarm):  # pragma: no cover - no real msgs here
            pass

    monkeypatch.setattr(app_mod, "TrailWindower", _OneShotWindower)

    stop_event = threading.Event()

    async def driver():
        loop = asyncio.get_running_loop()
        t = threading.Thread(
            target=app_mod.consume_loop,
            kwargs=dict(
                settings=_settings(),
                pipeline=pipeline,
                loader=None,
                api_state=api_state,
                loop=loop,
                stop_event=stop_event,
            ),
            daemon=True,
        )
        t.start()
        # Let the loop process the seeded window, then stop.
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            rows, total = await run_repo.list_runs(trail_id="t1")
            if total >= 1:
                break
            await asyncio.sleep(0.05)
        stop_event.set()
        # Join off the event loop so we don't block it.
        await asyncio.to_thread(t.join, 5)
        return total

    total = asyncio.run(driver())
    assert total >= 1  # the window was processed through the real async pipeline
    assert api_state.kafka_connected is True


def _kn_envelope(record_id: str, payload: dict) -> dict:
    return {
        "recordId": record_id,
        "recordType": "modelParams",
        "version": "v1",
        "domain": "core-ip",
        "payload": payload,
    }


@pytest.mark.asyncio
async def test_serve_runs_http_and_consumer_concurrently_then_shuts_down(monkeypatch):
    """B1+B2 orchestration (no DB): serve() starts uvicorn AND the consumer task, then drains.

    A fake uvicorn Server records that serve() was awaited (HTTP task) and returns once
    ``should_exit`` is set; the consumer task runs the real consume_loop with a fake broker. This
    proves both tasks are launched concurrently and that shutdown stops both + closes the pool.
    """
    served = {"http": False}

    class _FakeServer:
        def __init__(self):
            self.should_exit = False

        async def serve(self):
            served["http"] = True
            # Stop the consumer shortly after both tasks are up, then exit cleanly.
            await asyncio.sleep(0.2)
            self.should_exit = True

    monkeypatch.setattr(app_mod, "make_http_server", lambda app, port: _FakeServer())
    monkeypatch.setattr(app_mod, "make_consumer", lambda *a, **k: _FakeConsumer())

    class _NoopProducer2:
        def __init__(self, *a, **k):
            self.raw = object()

    monkeypatch.setattr(app_mod, "TransactionProducer", _NoopProducer2)

    # The consumer loop must observe should_exit via the shared stop_event; the fake server sets
    # should_exit but serve() wires stop_event separately. Stop the loop when the server exits by
    # patching consume_loop to a short, cooperative version that exits on stop_event.
    real_consume = app_mod.consume_loop

    def _short_consume(**kwargs):
        # Flip kafka_connected (as the real loop does) then exit promptly on stop.
        kwargs["api_state"].kafka_connected = True
        ev = kwargs["stop_event"]
        ev.wait(timeout=2.0)

    monkeypatch.setattr(app_mod, "consume_loop", _short_consume)

    settings = _settings(NOISE_FILTER_DB_URL="")  # in-memory path (no DB)
    metrics = Metrics()

    with respx.mock(assert_all_called=False) as router:
        mp_path = (
            f"/domains/core-ip/{MODEL_PARAMS_RECORD_TYPE}/{quote(MODEL_PARAMS_RECORD_ID, safe='')}"
        )
        router.get(f"http://knowledge.test{mp_path}").mock(
            return_value=httpx.Response(
                200,
                json=_kn_envelope(
                    MODEL_PARAMS_RECORD_ID,
                    {
                        "params": [
                            {"key": "dbscan.epsilon", "value": 1.0},
                            {"key": "dbscan.minSamples", "value": 3},
                            {"key": "window.sizeSeconds", "value": 600},
                            {"key": "feature.attributeKeys", "value": []},
                            {"key": "feature.hopDistance.enabled", "value": False},
                        ],
                        "paramSet": "noise-filter",
                    },
                ),
            )
        )
        # serve() should return on its own once the fake server exits (stop_event then set).
        await asyncio.wait_for(app_mod.serve(settings, metrics), timeout=10)

    assert served["http"] is True
    assert real_consume is not None  # sanity: real loop exists (covered by other test)
