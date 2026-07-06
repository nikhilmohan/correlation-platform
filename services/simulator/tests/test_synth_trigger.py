"""HTTP synth-trigger tests — POST /synth/run + GET /synth/status (spec AC 66-77).

Each test maps 1:1 to an acceptance criterion. The actual P3 synth pipeline is replaced by an
injected stub ``run_synth`` (fast, deterministic, no Kafka/PM/TB/Topology) so the RunManager state
machine, the 409/422/202 handlers, and the status shape are exercised in isolation. A
``threading.Event`` gate lets a test hold a run "in progress" to assert the running status + the
409 concurrency guard, then release it to assert the terminal (completed/failed) status.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Any

import pytest
from fastapi.testclient import TestClient

from simulator.api.app import RunState, create_app
from simulator.config.settings import Settings, load_settings
from simulator.synth.progress import ProgressSink
from simulator.synth.run_manager import (
    RunConflict,
    RunManager,
    RunOverrides,
    derive_settings,
)

# --------------------------------------------------------------------------- test doubles


@dataclass
class _StubSummary:
    aligned_fraction: float = 0.62
    enrichment_safe_count: int = 90
    shortfall_cascades: int = 0
    enrichment_conflict_patterns: list[str] | None = None


@dataclass
class _StubOutcome:
    emitted: int
    summary: _StubSummary
    labels: object = None


class _StubProducer:
    def produce(self, topic: str, envelope: Any) -> None:  # pragma: no cover - not called
        ...

    def flush(self) -> None:  # pragma: no cover - not called
        ...


def _settings() -> Settings:
    return load_settings({"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092"})


def _manager_with(run_synth) -> RunManager:
    return RunManager(
        _settings,
        lambda s: _StubProducer(),
        run_synth=run_synth,
    )


def _instant_run(emitted: int = 42):
    def run_synth(settings, producer, *, run_id, progress=None):
        if progress is not None:
            progress.set_total(settings.p3_total_alarms)
            for i in range(emitted):
                progress.inc_emitted(aligned=i % 2 == 0)
        return _StubOutcome(emitted=emitted, summary=_StubSummary(), labels="labels-sentinel")

    return run_synth


def _gated_run(gate: threading.Event, started: threading.Event, total: int = 200):
    """A run that signals it has started, then blocks on ``gate`` until the test releases it."""

    def run_synth(settings, producer, *, run_id, progress=None):
        if progress is not None:
            progress.set_total(settings.p3_total_alarms)
            progress.inc_emitted(aligned=True)
            progress.inc_emitted(aligned=False)
        started.set()
        gate.wait(timeout=5)
        return _StubOutcome(emitted=total, summary=_StubSummary(), labels=None)

    return run_synth


def _failing_run(message: str = "no approved patterns"):
    def run_synth(settings, producer, *, run_id, progress=None):
        raise RuntimeError(message)

    return run_synth


def _client(run_manager: RunManager) -> TestClient:
    return TestClient(
        create_app(RunState(started=True), run_manager=run_manager),
        raise_server_exceptions=False,
    )


def _wait_until_idle(client: TestClient, timeout: float = 5.0) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        body = client.get("/synth/status").json()
        if body["status"] == "idle" and body["summary"] is not None:
            return body
        time.sleep(0.02)
    raise AssertionError("run did not reach a terminal idle+summary state in time")


# --------------------------------------------------------------------------- AC 66


def test_ac66_post_run_returns_202_with_uuid_and_running() -> None:
    client = _client(_manager_with(_instant_run()))
    resp = client.post("/synth/run", json={})
    assert resp.status_code == 202
    body = resp.json()
    assert body["status"] == "running"
    assert isinstance(body["runId"], str) and body["runId"]
    # UUID-shaped
    import uuid

    uuid.UUID(body["runId"])


def test_ac66_post_run_no_body_returns_202() -> None:
    client = _client(_manager_with(_instant_run()))
    resp = client.post("/synth/run")
    assert resp.status_code == 202
    assert resp.json()["status"] == "running"


# --------------------------------------------------------------------------- AC 67


@pytest.mark.parametrize(
    "body",
    [
        {"target": 1.5},
        {"target": -0.1},
        {"totalAlarms": 0},
        {"totalAlarms": -1},
        {"seed": -5},
        {"unknownKnob": 3},
    ],
)
def test_ac67_invalid_param_returns_422(body: dict[str, Any]) -> None:
    started_calls = {"n": 0}

    def counting_run(settings, producer, *, run_id, progress=None):
        started_calls["n"] += 1
        return _StubOutcome(emitted=1, summary=_StubSummary())

    client = _client(_manager_with(counting_run))
    resp = client.post("/synth/run", json=body)
    assert resp.status_code == 422
    # No background run started on a validation failure.
    assert started_calls["n"] == 0


# --------------------------------------------------------------------------- AC 68


def test_ac68_second_post_while_running_returns_409() -> None:
    gate = threading.Event()
    started = threading.Event()
    client = _client(_manager_with(_gated_run(gate, started)))
    try:
        first = client.post("/synth/run", json={})
        assert first.status_code == 202
        active_run_id = first.json()["runId"]
        assert started.wait(timeout=5)

        second = client.post("/synth/run", json={})
        assert second.status_code == 409
        assert second.json()["runId"] == active_run_id
    finally:
        gate.set()
    _wait_until_idle(client)


# --------------------------------------------------------------------------- AC 69


def test_ac69_status_running_reports_progress_counters() -> None:
    gate = threading.Event()
    started = threading.Event()
    client = _client(_manager_with(_gated_run(gate, started, total=200)))
    try:
        run_id = client.post("/synth/run", json={"totalAlarms": 200}).json()["runId"]
        assert started.wait(timeout=5)
        body = client.get("/synth/status").json()
        assert body["status"] == "running"
        assert body["runId"] == run_id
        prog = body["progress"]
        assert prog["alarmsTotal"] == 200
        assert 0 <= prog["alarmsEmitted"] <= prog["alarmsTotal"]
        assert prog["alignedEmitted"] >= 0
        assert prog["nonAlignedEmitted"] >= 0
        assert body["summary"] is None
    finally:
        gate.set()
    _wait_until_idle(client)


# --------------------------------------------------------------------------- AC 70


def test_ac70_status_idle_with_completed_summary_after_success() -> None:
    client = _client(_manager_with(_instant_run(emitted=50)))
    run_id = client.post("/synth/run", json={}).json()["runId"]
    body = _wait_until_idle(client)
    assert body["status"] == "idle"
    assert body["runId"] == run_id
    summary = body["summary"]
    assert summary["status"] == "completed"
    assert summary["runId"] == run_id
    assert summary["alarmsEmitted"] == 50
    assert 0.0 <= summary["alignedFraction"] <= 1.0
    assert summary["startedAt"] and summary["completedAt"]
    assert summary["completedAt"] >= summary["startedAt"]
    assert summary["failureReason"] is None


# --------------------------------------------------------------------------- AC 71


def test_ac71_status_idle_no_run_ever() -> None:
    client = _client(_manager_with(_instant_run()))
    body = client.get("/synth/status").json()
    assert body["status"] == "idle"
    assert body["runId"] is None
    assert body["summary"] is None
    # progress always present, zero-filled when idle-never
    assert body["progress"] == {
        "alarmsEmitted": 0,
        "alarmsTotal": 0,
        "alignedEmitted": 0,
        "nonAlignedEmitted": 0,
    }


# --------------------------------------------------------------------------- AC 72


def test_ac72_failed_run_surfaces_failure_reason_and_releases_guard() -> None:
    client = _client(_manager_with(_failing_run("no approved patterns")))
    first = client.post("/synth/run", json={})
    assert first.status_code == 202
    body = _wait_until_idle(client)
    assert body["status"] == "idle"
    assert body["summary"]["status"] == "failed"
    assert "no approved patterns" in body["summary"]["failureReason"]
    # guard released -> a new run is accepted
    second = client.post("/synth/run", json={})
    assert second.status_code == 202


# --------------------------------------------------------------------------- AC 73


def test_ac73_body_overrides_and_env_defaults() -> None:
    captured: list[Settings] = []

    def capturing_run(settings, producer, *, run_id, progress=None):
        captured.append(settings)
        if progress is not None:
            progress.set_total(settings.p3_total_alarms)
        return _StubOutcome(emitted=settings.p3_total_alarms, summary=_StubSummary())

    def provider() -> Settings:
        return load_settings(
            {
                "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
                "P3_AUTO_CORRELATION_TARGET": "0.6",
                "P3_TOTAL_ALARMS": "500",
            }
        )

    rm = RunManager(provider, lambda s: _StubProducer(), run_synth=capturing_run)
    client = _client(rm)

    client.post("/synth/run", json={"target": 0.75, "totalAlarms": 200})
    _wait_until_idle(client)
    assert captured[-1].p3_total_alarms == 200
    assert captured[-1].p3_auto_correlation_target == 0.75

    client.post("/synth/run", json={})
    _wait_until_idle(client)
    assert captured[-1].p3_total_alarms == 500
    assert captured[-1].p3_auto_correlation_target == 0.6


# --------------------------------------------------------------------------- AC 74


def test_ac74_seed_override_is_reproducible() -> None:
    seen_seeds: list[int | None] = []
    run_ids: list[str] = []

    def capturing_run(settings, producer, *, run_id, progress=None):
        seen_seeds.append(settings.p3_rng_seed)
        run_ids.append(run_id)
        return _StubOutcome(emitted=10, summary=_StubSummary())

    rm = RunManager(_settings, lambda s: _StubProducer(), run_synth=capturing_run)
    client = _client(rm)
    client.post("/synth/run", json={"seed": 42})
    _wait_until_idle(client)
    client.post("/synth/run", json={"seed": 42})
    _wait_until_idle(client)
    # Same seed threaded to both runs (reproducible relative sequence), distinct runIds.
    assert seen_seeds == [42, 42]
    assert run_ids[0] != run_ids[1]


# --------------------------------------------------------------------------- AC 76


def test_ac76_health_and_metrics_responsive_during_run() -> None:
    gate = threading.Event()
    started = threading.Event()
    client = _client(_manager_with(_gated_run(gate, started)))
    try:
        client.post("/synth/run", json={})
        assert started.wait(timeout=5)
        t0 = time.monotonic()
        assert client.get("/health").status_code == 200
        assert client.get("/metrics").status_code == 200
        assert time.monotonic() - t0 < 2.0
    finally:
        gate.set()
    _wait_until_idle(client)


# --------------------------------------------------------------------------- AC 77


def test_ac77_existing_read_endpoints_unaffected() -> None:
    client = _client(_manager_with(_instant_run()))
    for path in ("/labels", "/scenarios", "/health", "/metrics"):
        assert client.get(path).status_code == 200


def test_ac77_app_without_run_manager_has_no_synth_routes() -> None:
    client = TestClient(create_app(RunState(started=True)), raise_server_exceptions=False)
    assert client.get("/synth/status").status_code == 404
    assert client.get("/health").status_code == 200


# --------------------------------------------------------------------------- RunManager unit


def test_run_manager_single_run_concurrency_one_409() -> None:
    """Two near-simultaneous starts -> exactly one succeeds, the other raises RunConflict."""
    gate = threading.Event()
    started = threading.Event()
    rm = _manager_with(_gated_run(gate, started))
    first = rm.start(RunOverrides())
    assert started.wait(timeout=5)
    with pytest.raises(RunConflict) as excinfo:
        rm.start(RunOverrides())
    assert excinfo.value.active_run_id == first
    gate.set()
    # drain
    deadline = time.monotonic() + 5
    while rm.status().status == "running" and time.monotonic() < deadline:
        time.sleep(0.02)
    assert rm.status().status == "idle"


def test_derive_settings_applies_only_present_overrides() -> None:
    base = load_settings(
        {
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "P3_TOTAL_ALARMS": "500",
            "P3_RNG_SEED": "7",
        }
    )
    derived = derive_settings(base, RunOverrides(total_alarms=123))
    assert derived.p3_total_alarms == 123
    assert derived.p3_rng_seed == 7  # untouched
    # base is not mutated
    assert base.p3_total_alarms == 500


def test_derive_settings_target_enables_network_wide() -> None:
    base = load_settings({"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092"})
    derived = derive_settings(base, RunOverrides(target=0.7))
    assert derived.p3_auto_correlation_target == 0.7
    assert derived.p3_network_wide is True


def test_derive_settings_no_overrides_returns_base() -> None:
    base = load_settings({"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092"})
    assert derive_settings(base, RunOverrides()) is base


def test_progress_sink_counts_and_snapshots() -> None:
    sink = ProgressSink()
    sink.set_total(10)
    sink.inc_emitted(aligned=True)
    sink.inc_emitted(aligned=False)
    sink.inc_emitted(aligned=True)
    snap = sink.snapshot()
    assert snap.alarmsTotal == 10
    assert snap.alarmsEmitted == 3
    assert snap.alignedEmitted == 2
    assert snap.nonAlignedEmitted == 1
