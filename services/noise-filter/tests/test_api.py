"""Read-API acceptance criteria (AC-12, 14, 21, 22) + supporting (time-range, 503 filters)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from noise_filter.api import ApiState, create_app
from noise_filter.metrics import Metrics
from noise_filter.repository import (
    InMemoryObservedChatterRepository,
    InMemoryRunStatsRepository,
)
from noise_filter.stats import ChatterSignature, RunStatsRow


def _validate_against_component(instance, openapi_spec, component_name) -> None:
    """Validate ``instance`` against ``components.schemas[component_name]`` of an OpenAPI 3.1 doc,
    resolving ``$ref``s via a modern jsonschema ``referencing`` registry.
    """
    import jsonschema
    from referencing import Registry, Resource
    from referencing.jsonschema import DRAFT202012

    # Register the whole OpenAPI doc under a base URI so '#/components/...' $refs resolve.
    # OpenAPI 3.1's schema dialect is JSON Schema 2020-12 (the doc carries no '$schema' key,
    # so the specification is supplied explicitly).
    resource = Resource(contents=openapi_spec, specification=DRAFT202012)
    registry = Registry().with_resource(uri="urn:openapi", resource=resource)
    schema = {"$ref": "urn:openapi#/components/schemas/" + component_name}
    jsonschema.Draft202012Validator(schema, registry=registry).validate(instance)


def _row(*, run_id: str, trail_id: str, ts: datetime) -> RunStatsRow:
    return RunStatsRow(
        run_id=run_id,
        run_timestamp=ts,
        trail_id=trail_id,
        snapshot_id="snap-1",
        domain="core-ip",
        window_start=ts,
        window_end=ts + timedelta(minutes=10),
        eps=1.0,
        min_samples=3,
        window_size_seconds=600,
        algorithm="dbscan",
        alarms_in=10,
        clusters_formed=1,
        alarms_kept=8,
        alarms_dropped=2,
        noise_ratio=0.2,
        storm_max_cluster_size=8,
        storm_reduction_ratio=10.0,
        retention_vs_oracle=1.0,
        hop_feature_enabled=False,
    )


def _make_client(*, run_repo=None, chatter_repo=None):
    run_repo = run_repo or InMemoryRunStatsRepository()
    chatter_repo = chatter_repo or InMemoryObservedChatterRepository()
    metrics = Metrics()
    state = ApiState(
        run_stats_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics_registry=metrics.registry,
    )
    state.params_loaded = True
    state.kafka_connected = True
    state.metrics = metrics
    return TestClient(create_app(state)), run_repo, chatter_repo, metrics


@pytest.mark.asyncio
async def test_run_stats_read_api_returns_rows_and_validates_openapi():
    """AC-12: GET /api/v1/run-stats returns recorded rows; response validates against OpenAPI."""
    run_repo = InMemoryRunStatsRepository()
    await run_repo.insert_run(_row(run_id="r1", trail_id="t1", ts=datetime.now(UTC)))
    client, *_ = _make_client(run_repo=run_repo)

    resp = client.get("/api/v1/run-stats")
    assert resp.status_code == 200
    body = resp.json()
    assert body["total"] == 1
    item = body["items"][0]
    assert item["runId"] == "r1"
    assert item["alarmsIn"] == 10 and item["noiseRatio"] == 0.2

    # Validate the response against the service's published OpenAPI schema for RunStatsPage.
    spec = client.get("/openapi.json").json()
    _validate_against_component(body, spec, "RunStatsPage")


@pytest.mark.asyncio
async def test_run_stats_query_by_trail_id_returns_subset():
    """AC-14: filter by trailId returns only that trail's rows."""
    run_repo = InMemoryRunStatsRepository()
    now = datetime.now(UTC)
    await run_repo.insert_run(_row(run_id="r1", trail_id="t1", ts=now))
    await run_repo.insert_run(_row(run_id="r2", trail_id="t2", ts=now))
    client, *_ = _make_client(run_repo=run_repo)

    resp = client.get("/api/v1/run-stats", params={"trailId": "t1"})
    body = resp.json()
    assert {i["runId"] for i in body["items"]} == {"r1"}
    assert body["total"] == 1


@pytest.mark.asyncio
async def test_run_stats_query_by_time_range():
    """Supporting: from/to filter over runTimestamp."""
    run_repo = InMemoryRunStatsRepository()
    old = datetime(2026, 1, 1, tzinfo=UTC)
    new = datetime(2026, 6, 1, tzinfo=UTC)
    await run_repo.insert_run(_row(run_id="old", trail_id="t1", ts=old))
    await run_repo.insert_run(_row(run_id="new", trail_id="t1", ts=new))
    client, *_ = _make_client(run_repo=run_repo)

    resp = client.get("/api/v1/run-stats", params={"from": "2026-03-01T00:00:00Z"})
    ids = {i["runId"] for i in resp.json()["items"]}
    assert ids == {"new"}


@pytest.mark.asyncio
async def test_observed_chatter_endpoint_returns_ranked_and_validates_openapi():
    """AC-21: signatures ranked by occurrenceCount desc; response validates against OpenAPI."""
    chatter_repo = InMemoryObservedChatterRepository()
    # Insert two signatures; bump one to a higher count.
    low = ChatterSignature(
        managed_object_id="Port:a",
        alarm_type="QoS",
        event_type="qualityOfServiceAlarm",
        trail_id="t1",
    )
    high = ChatterSignature(
        managed_object_id="Port:b",
        alarm_type="LinkFlap",
        event_type="communicationsAlarm",
        trail_id="t1",
    )
    await chatter_repo.upsert_signature(low)
    for _ in range(3):
        await chatter_repo.upsert_signature(high)
    client, *_ = _make_client(chatter_repo=chatter_repo)

    resp = client.get("/api/v1/observed-chatter")
    assert resp.status_code == 200
    body = resp.json()
    counts = [i["occurrenceCount"] for i in body["items"]]
    assert counts == sorted(counts, reverse=True)  # ranked most-frequent first
    assert body["items"][0]["alarmType"] == "LinkFlap"
    for item in body["items"]:
        for f in ("alarmType", "eventType", "occurrenceCount", "firstSeen", "lastSeen"):
            assert f in item

    spec = client.get("/openapi.json").json()
    _validate_against_component(body, spec, "ObservedChatterPage")


@pytest.mark.parametrize("method", ["post", "put", "patch", "delete"])
def test_observed_chatter_endpoint_read_only(method):
    """AC-22: non-GET to /api/v1/observed-chatter returns 405 (no mutation/promotion API)."""
    client, *_ = _make_client()
    resp = getattr(client, method)("/api/v1/observed-chatter")
    assert resp.status_code == 405


@pytest.mark.asyncio
async def test_observed_chatter_query_filters_alarm_type_and_min_occurrence():
    """Supporting: alarmType + minOccurrence filters."""
    chatter_repo = InMemoryObservedChatterRepository()
    await chatter_repo.upsert_signature(
        ChatterSignature(
            managed_object_id="Port:a", alarm_type="QoS", event_type="x", trail_id="t1"
        )
    )
    for _ in range(3):
        await chatter_repo.upsert_signature(
            ChatterSignature(
                managed_object_id="Port:b", alarm_type="Flap", event_type="y", trail_id="t1"
            )
        )
    client, *_ = _make_client(chatter_repo=chatter_repo)

    by_type = client.get("/api/v1/observed-chatter", params={"alarmType": "Flap"}).json()
    assert {i["alarmType"] for i in by_type["items"]} == {"Flap"}

    by_min = client.get("/api/v1/observed-chatter", params={"minOccurrence": 2}).json()
    assert all(i["occurrenceCount"] >= 2 for i in by_min["items"])


def test_run_stats_read_api_db_unreachable_returns_503():
    """EH-13: read while store unreachable returns 503; read-error metric increments."""
    run_repo = InMemoryRunStatsRepository(read_fail=True)
    client, _, _, metrics = _make_client(run_repo=run_repo)
    resp = client.get("/api/v1/run-stats")
    assert resp.status_code == 503
    assert metrics.runstats_read_errors._value.get() == 1


def test_observed_chatter_read_db_unreachable_returns_503():
    """EH-17: observed-chatter read while store unreachable returns 503."""
    chatter_repo = InMemoryObservedChatterRepository(read_fail=True)
    client, *_ = _make_client(chatter_repo=chatter_repo)
    resp = client.get("/api/v1/observed-chatter")
    assert resp.status_code == 503


def test_invalid_query_param_returns_422():
    """EH-14: invalid limit returns 422 (FastAPI validation), no DB call."""
    client, *_ = _make_client()
    resp = client.get("/api/v1/run-stats", params={"limit": 9999})
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_get_run_stats_by_id_and_404():
    """GET /api/v1/run-stats/{runId} returns the row; 404 when absent."""
    run_repo = InMemoryRunStatsRepository()
    await run_repo.insert_run(_row(run_id="r1", trail_id="t1", ts=datetime.now(UTC)))
    client, *_ = _make_client(run_repo=run_repo)
    assert client.get("/api/v1/run-stats/r1").status_code == 200
    assert client.get("/api/v1/run-stats/missing").status_code == 404


def test_checked_in_openapi_matches_generated_spec():
    """The checked-in openapi.json (the published contract) matches the live FastAPI spec, so the
    single-source-of-truth artifact never drifts from the implementation."""
    import json
    from pathlib import Path

    checked_in = Path(__file__).resolve().parents[1] / "openapi.json"
    assert checked_in.exists(), "services/noise-filter/openapi.json must be checked in"
    client, *_ = _make_client()
    live = client.get("/openapi.json").json()
    on_disk = json.loads(checked_in.read_text())
    assert on_disk == live, (
        "openapi.json is stale — regenerate it from the FastAPI app "
        "(python -c 'from noise_filter.api import ...; app.openapi()')."
    )
