"""Integration-tagged proof that the REAL process entrypoint serves HTTP + persists to Postgres.

This is the catch-net for B1 (HTTP server never started) and B2 (asyncpg Pg repos never wired):
it starts ``noise_filter.app.serve`` — the ACTUAL assembled entrypoint — against a REAL
Testcontainers Postgres and a REAL HTTP bind, then asserts over real sockets:

  (a) GET /health and GET /metrics return over HTTP (B1),
  (b) a window processed through the entrypoint's pipeline writes a row to the REAL nf_run_stats
      table via the Pg repository — then reads back over the live HTTP read API (B2),
  (c) observed-chatter persists to Postgres and reads back via the live read API.

Kafka is the only collaborator stubbed (no broker in the unit/integration container): the consume
loop's transport is replaced with a fake consumer + a one-shot windower that injects ONE finalized
window, so the rest of the entrypoint — pool, migrations, Pg repos, uvicorn, the async pipeline —
is the genuine deployed code path. Knowledge is served by respx (the startup gate).

Marked ``integration`` (deselected by the unit gate; the integration stage runs it).
"""

from __future__ import annotations

import asyncio
from urllib.parse import quote

import httpx
import pytest

pytestmark = pytest.mark.integration

asyncpg = pytest.importorskip("asyncpg")
testcontainers_postgres = pytest.importorskip("testcontainers.postgres")
respx = pytest.importorskip("respx")

from testcontainers.postgres import PostgresContainer  # noqa: E402

from noise_filter import app as app_mod  # noqa: E402
from noise_filter.clients import (  # noqa: E402
    MODEL_PARAMS_RECORD_ID,
    MODEL_PARAMS_RECORD_TYPE,
)
from noise_filter.config import Settings  # noqa: E402
from noise_filter.metrics import Metrics  # noqa: E402

from .fixtures import make_alarm, storm  # noqa: E402
from .helpers import make_window  # noqa: E402

KNOWLEDGE_URL = "http://knowledge.test"
HTTP_PORT = 8137


def _envelope(record_id: str, payload: dict) -> dict:
    return {
        "recordId": record_id,
        "recordType": "modelParams",
        "version": "v1",
        "domain": "core-ip",
        "payload": payload,
    }


class _FakeConsumer:
    def poll(self, timeout):
        return None

    def commit(self, msg):  # pragma: no cover
        pass

    def close(self):
        pass


class _NoopProducer:
    def __init__(self, *a, **k):
        self.raw = object()

    def publish(self, *a, **k):  # pragma: no cover - no producer path here
        pass


@pytest.mark.asyncio
async def test_real_entrypoint_serves_http_and_persists_to_postgres(monkeypatch):
    with PostgresContainer("postgres:16-alpine") as pg:
        db_url = pg.get_connection_url().replace("postgresql+psycopg2://", "postgresql://")

        # Knowledge + Trail Builder served by respx; our own HTTP server (127.0.0.1) is an explicit
        # pass-through to the real transport, so /health, /metrics and the read API are hit over a
        # REAL socket (not intercepted).
        router = respx.mock(assert_all_called=False, assert_all_mocked=False)
        router.route(host="127.0.0.1").pass_through()
        router.route(host="localhost").pass_through()
        mp_path = (
            f"/domains/core-ip/{MODEL_PARAMS_RECORD_TYPE}/{quote(MODEL_PARAMS_RECORD_ID, safe='')}"
        )
        router.get(f"{KNOWLEDGE_URL}{mp_path}").mock(
            return_value=httpx.Response(
                200,
                json=_envelope(
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

        # Pre-seed ONE finalized window: a dense storm (one cluster) PLUS a temporally-distant
        # chatty outlier so DBSCAN labels it noise -> an observed-chatter signature is upserted to
        # the real nf_observed_chatter table (proves (c) over real Postgres).
        cascade = storm(6, trail_id="t1", start=0.0, spread=3.0)
        chatter = make_alarm(
            alarm_id="chatter-int-1",
            managed_object_id="Port:noisy",
            alarm_type="QualityOfServiceAlarm",
            event_type="qualityOfServiceAlarm",
            perceived_severity="warning",
            raised_offset_seconds=300.0,  # far outside the storm's tight density
        )
        window_alarms = cascade + [chatter]
        finalized = [make_window(window_alarms)]

        class _OneShotWindower:
            def __init__(self, *a, **k):
                self._done = False

            def pop_finalized(self):
                if self._done:
                    return []
                self._done = True
                return finalized

            def add(self, alarm):  # pragma: no cover
                pass

        monkeypatch.setattr(app_mod, "make_consumer", lambda *a, **k: _FakeConsumer())
        monkeypatch.setattr(app_mod, "TransactionProducer", _NoopProducer)
        monkeypatch.setattr(app_mod, "TrailWindower", _OneShotWindower)

        # The real trail context is resolved over HTTP in the entrypoint; stub it via respx so
        # snapshotId resolves and a run-stats row (+ chatter) is produced.
        router.get(url__regex=r"http://trail.test/trails/.*").mock(
            return_value=httpx.Response(
                200,
                json={
                    "snapshotId": "snap-int-1",
                    "domain": "core-ip",
                    "members": [{"managedObjectId": a.managedObjectId} for a in window_alarms],
                    "edges": [],
                },
            )
        )

        settings = Settings(
            KNOWLEDGE_SERVICE_URL=KNOWLEDGE_URL,
            TRAIL_BUILDER_URL="http://trail.test",
            TOPOLOGY_SERVICE_URL="http://topology.test",
            KAFKA_BOOTSTRAP_SERVERS="localhost:9092",
            HTTP_PORT=HTTP_PORT,
            NOISE_FILTER_DB_URL=db_url,
            WINDOW_BACKSTOP_SECONDS=0,
        )
        metrics = Metrics()

        with router:
            serve_task = asyncio.create_task(app_mod.serve(settings, metrics))
            try:
                base = f"http://127.0.0.1:{HTTP_PORT}"
                async with httpx.AsyncClient(timeout=5.0) as client:
                    # (a) /health + /metrics reachable over real HTTP.
                    await _await_http_ready(client, base)
                    health = await client.get(f"{base}/health")
                    assert health.status_code == 200, health.text
                    assert health.json()["store"] == "ok", health.text
                    metrics_resp = await client.get(f"{base}/metrics")
                    assert metrics_resp.status_code == 200
                    assert "nf_windows_finalized_total" in metrics_resp.text

                    # (b) the window was persisted to REAL nf_run_stats; read it back over HTTP.
                    rows = await _await_rows(client, base)
                    assert rows["total"] >= 1
                    assert rows["items"][0]["trailId"] == "t1"
                    assert rows["items"][0]["snapshotId"] == "snap-int-1"

                    # Prove it is the REAL Pg table, not in-memory: query Postgres directly.
                    pool = await asyncpg.create_pool(app_mod._asyncpg_url(db_url))
                    try:
                        db_count = await pool.fetchval(
                            "SELECT count(*) FROM noise_filter.nf_run_stats WHERE trail_id = $1",
                            "t1",
                        )
                        chatter_db_count = await pool.fetchval(
                            "SELECT count(*) FROM noise_filter.nf_observed_chatter"
                        )
                    finally:
                        await pool.close()
                    assert db_count >= 1
                    assert chatter_db_count >= 1  # the noise outlier persisted as a chatter sig

                    # (c) observed-chatter persisted + readable back over the live HTTP read API.
                    chatter_resp = await _await_chatter(client, base)
                    assert chatter_resp["total"] >= 1
                    types = {it["alarmType"] for it in chatter_resp["items"]}
                    assert "QualityOfServiceAlarm" in types
            finally:
                serve_task.cancel()
                with pytest.raises((asyncio.CancelledError, Exception)):
                    await serve_task


async def _await_http_ready(client: httpx.AsyncClient, base: str) -> None:
    for _ in range(100):
        try:
            r = await client.get(f"{base}/health")
            if r.status_code in (200, 503):
                return
        except httpx.HTTPError:
            pass
        await asyncio.sleep(0.1)
    raise AssertionError("HTTP server did not become reachable")


async def _await_rows(client: httpx.AsyncClient, base: str) -> dict:
    for _ in range(100):
        r = await client.get(f"{base}/api/v1/run-stats")
        if r.status_code == 200 and r.json()["total"] >= 1:
            return r.json()
        await asyncio.sleep(0.1)
    raise AssertionError("no run-stats row was persisted via the entrypoint")


async def _await_chatter(client: httpx.AsyncClient, base: str) -> dict:
    for _ in range(100):
        r = await client.get(f"{base}/api/v1/observed-chatter")
        if r.status_code == 200 and r.json()["total"] >= 1:
            return r.json()
        await asyncio.sleep(0.1)
    raise AssertionError("no observed-chatter signature was persisted via the entrypoint")
