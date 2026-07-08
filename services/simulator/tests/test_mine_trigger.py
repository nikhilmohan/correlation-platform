"""HTTP mine-trigger tests — POST /mine/run + GET /mine/status (spec AC 78-89).

The P2 mine-corpus trigger mirrors the P3 synth trigger. Each test maps 1:1 to an acceptance
criterion. The actual P2 corpus-generate pipeline is replaced by an injected stub ``run_corpus``
(fast, deterministic, no Kafka/Topology/Trail-Builder) so the run-manager state machine, the
409/422/202 handlers, the status shape, and the shared run-guard are exercised in isolation. A
``threading.Event`` gate lets a test hold a run "in progress" to assert the running status, the
409 concurrency guard, and the cross-trigger (mine<->synth) mutual exclusion, then release it to
assert the terminal (completed/failed) status.
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
from simulator.synth.mine_run_manager import (
    MineRunManager,
    MineRunOverrides,
    derive_mine_settings,
)
from simulator.synth.run_guard import RunConflict, RunGuard
from simulator.synth.run_manager import RunManager

# --------------------------------------------------------------------------- test doubles


@dataclass
class _StubCorpusOutcome:
    emitted: int


class _StubProducer:
    def produce(self, topic: str, envelope: Any) -> None:  # pragma: no cover - not called
        ...

    def flush(self) -> None:  # pragma: no cover - not called
        ...


def _settings() -> Settings:
    return load_settings({"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092"})


def _mine_manager_with(run_corpus, guard: RunGuard | None = None) -> MineRunManager:
    return MineRunManager(
        _settings,
        lambda s: _StubProducer(),
        run_corpus=run_corpus,
        guard=guard,
    )


def _instant_corpus(emitted: int = 1500):
    def run_corpus(settings, producer, *, run_id, progress=None):
        if progress is not None:
            progress.set_total(emitted)
            for i in range(emitted):
                progress.inc_emitted(aligned=i % 3 == 0)
        return _StubCorpusOutcome(emitted=emitted)

    return run_corpus


def _gated_corpus(gate: threading.Event, started: threading.Event, total: int = 1500):
    """A run that signals it has started, then blocks on ``gate`` until the test releases it."""

    def run_corpus(settings, producer, *, run_id, progress=None):
        if progress is not None:
            progress.set_total(total)
            progress.inc_emitted(aligned=True)
            progress.inc_emitted(aligned=False)
        started.set()
        gate.wait(timeout=5)
        return _StubCorpusOutcome(emitted=total)

    return run_corpus


def _failing_corpus(message: str = "no topology snapshot"):
    def run_corpus(settings, producer, *, run_id, progress=None):
        raise RuntimeError(message)

    return run_corpus


def _synth_run(emitted: int = 42):
    def run_synth(settings, producer, *, run_id, progress=None):
        if progress is not None:
            progress.set_total(settings.p3_total_alarms)
            for i in range(emitted):
                progress.inc_emitted(aligned=i % 2 == 0)

        @dataclass
        class _S:
            aligned_fraction: float = 0.6
            enrichment_safe_count: int = 0
            shortfall_cascades: int = 0
            enrichment_conflict_patterns: list[str] | None = None

        @dataclass
        class _O:
            emitted: int
            summary: _S
            labels: object = None

        return _O(emitted=emitted, summary=_S())

    return run_synth


def _client(mine_manager: MineRunManager, run_manager: RunManager | None = None) -> TestClient:
    return TestClient(
        create_app(RunState(started=True), run_manager=run_manager, mine_manager=mine_manager),
        raise_server_exceptions=False,
    )


def _wait_until_idle(client: TestClient, path: str = "/mine/status", timeout: float = 5.0) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        body = client.get(path).json()
        if body["status"] == "idle" and body["summary"] is not None:
            return body
        time.sleep(0.02)
    raise AssertionError("run did not reach a terminal idle+summary state in time")


# --------------------------------------------------------------------------- AC 78: 202 + runId


def test_ac78_post_mine_run_returns_202_with_uuid_and_running() -> None:
    client = _client(_mine_manager_with(_instant_corpus()))
    resp = client.post("/mine/run", json={})
    assert resp.status_code == 202
    body = resp.json()
    assert body["status"] == "running"
    assert isinstance(body["runId"], str) and body["runId"]
    import uuid

    uuid.UUID(body["runId"])


def test_ac78_post_mine_run_no_body_returns_202() -> None:
    client = _client(_mine_manager_with(_instant_corpus()))
    resp = client.post("/mine/run")
    assert resp.status_code == 202
    assert resp.json()["status"] == "running"


# --------------------------------------------------------------------------- AC 79: 422 invalid


@pytest.mark.parametrize(
    "body",
    [
        {"scenarioInstances": 0},
        {"scenarioInstances": -1},
        {"seed": -5},
        {"unknownKnob": 3},
        {"scenarioInstances": "lots"},
    ],
)
def test_ac79_invalid_param_returns_422(body: dict[str, Any]) -> None:
    started_calls = {"n": 0}

    def counting_corpus(settings, producer, *, run_id, progress=None):
        started_calls["n"] += 1
        return _StubCorpusOutcome(emitted=1)

    client = _client(_mine_manager_with(counting_corpus))
    resp = client.post("/mine/run", json=body)
    assert resp.status_code == 422
    assert started_calls["n"] == 0


# --------------------------------------------------------------------------- AC 80: 409 mine active


def test_ac80_second_mine_post_while_running_returns_409() -> None:
    gate = threading.Event()
    started = threading.Event()
    client = _client(_mine_manager_with(_gated_corpus(gate, started)))
    try:
        first = client.post("/mine/run", json={})
        assert first.status_code == 202
        active_run_id = first.json()["runId"]
        assert started.wait(timeout=5)

        second = client.post("/mine/run", json={})
        assert second.status_code == 409
        assert second.json()["runId"] == active_run_id
    finally:
        gate.set()
    _wait_until_idle(client)


# --------------------------------------------------------------------------- AC 81: progress


def test_ac81_status_running_reports_progress_counters() -> None:
    gate = threading.Event()
    started = threading.Event()
    client = _client(_mine_manager_with(_gated_corpus(gate, started, total=1500)))
    try:
        run_id = client.post("/mine/run", json={}).json()["runId"]
        assert started.wait(timeout=5)
        body = client.get("/mine/status").json()
        assert body["status"] == "running"
        assert body["runId"] == run_id
        prog = body["progress"]
        assert prog["alarmsTotal"] == 1500
        assert 0 <= prog["alarmsEmitted"] <= prog["alarmsTotal"]
        assert body["summary"] is None
    finally:
        gate.set()
    _wait_until_idle(client)


# --------------------------------------------------------------------------- AC 82: idle+completed


def test_ac82_status_idle_with_completed_summary_after_success() -> None:
    client = _client(_mine_manager_with(_instant_corpus(emitted=1500)))
    run_id = client.post("/mine/run", json={}).json()["runId"]
    body = _wait_until_idle(client)
    assert body["status"] == "idle"
    assert body["runId"] == run_id
    summary = body["summary"]
    assert summary["status"] == "completed"
    assert summary["runId"] == run_id
    assert summary["alarmsEmitted"] == 1500
    assert summary["startedAt"] and summary["completedAt"]
    assert summary["completedAt"] >= summary["startedAt"]
    assert summary["failureReason"] is None


# --------------------------------------------------------------------------- AC 83: idle-never


def test_ac83_status_idle_no_run_ever() -> None:
    client = _client(_mine_manager_with(_instant_corpus()))
    body = client.get("/mine/status").json()
    assert body["status"] == "idle"
    assert body["runId"] is None
    assert body["summary"] is None
    assert body["progress"] == {
        "alarmsEmitted": 0,
        "alarmsTotal": 0,
        "alignedEmitted": 0,
        "nonAlignedEmitted": 0,
    }


# --------------------------------------------------------------------------- AC 84: failure


def test_ac84_failed_run_surfaces_failure_reason_and_releases_guard() -> None:
    client = _client(_mine_manager_with(_failing_corpus("no topology snapshot")))
    first = client.post("/mine/run", json={})
    assert first.status_code == 202
    body = _wait_until_idle(client)
    assert body["status"] == "idle"
    assert body["summary"]["status"] == "failed"
    assert "no topology snapshot" in body["summary"]["failureReason"]
    # guard released -> a new run is accepted
    second = client.post("/mine/run", json={})
    assert second.status_code == 202


# --------------------------------------------------------------------------- AC 85: body overrides


def test_ac85_body_overrides_and_env_defaults() -> None:
    captured: list[Settings] = []

    def capturing_corpus(settings, producer, *, run_id, progress=None):
        captured.append(settings)
        if progress is not None:
            progress.set_total(settings.scenario_instances)
        return _StubCorpusOutcome(emitted=settings.scenario_instances)

    def provider() -> Settings:
        return load_settings(
            {"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092", "SCENARIO_INSTANCES": "8"}
        )

    mm = MineRunManager(provider, lambda s: _StubProducer(), run_corpus=capturing_corpus)
    client = _client(mm)

    client.post("/mine/run", json={"scenarioInstances": 20})
    _wait_until_idle(client)
    assert captured[-1].scenario_instances == 20

    client.post("/mine/run", json={})
    _wait_until_idle(client)
    assert captured[-1].scenario_instances == 8


# --------------------------------------------------------------------------- AC 86: seed override


def test_ac86_seed_override_reproducible() -> None:
    seen_seeds: list[int | None] = []
    run_ids: list[str] = []

    def capturing_corpus(settings, producer, *, run_id, progress=None):
        seen_seeds.append(settings.sim_seed)
        run_ids.append(run_id)
        return _StubCorpusOutcome(emitted=10)

    mm = MineRunManager(_settings, lambda s: _StubProducer(), run_corpus=capturing_corpus)
    client = _client(mm)
    client.post("/mine/run", json={"seed": 42})
    _wait_until_idle(client)
    client.post("/mine/run", json={"seed": 42})
    _wait_until_idle(client)
    assert seen_seeds == [42, 42]
    assert run_ids[0] != run_ids[1]


# --------------------------------------------------------------------------- AC 87: shared guard


def test_ac87_mine_blocks_concurrent_synth_via_shared_guard() -> None:
    guard = RunGuard()
    gate = threading.Event()
    started = threading.Event()
    mine_mgr = _mine_manager_with(_gated_corpus(gate, started), guard=guard)
    synth_mgr = RunManager(
        _settings, lambda s: _StubProducer(), run_synth=_synth_run(), guard=guard
    )
    client = _client(mine_mgr, run_manager=synth_mgr)
    try:
        mine_id = client.post("/mine/run", json={}).json()["runId"]
        assert started.wait(timeout=5)
        # a synth run must be rejected while the mine run holds the shared guard
        resp = client.post("/synth/run", json={})
        assert resp.status_code == 409
        assert resp.json()["runId"] == mine_id
    finally:
        gate.set()
    _wait_until_idle(client)


def test_ac87_synth_blocks_concurrent_mine_via_shared_guard() -> None:
    guard = RunGuard()
    gate = threading.Event()
    started = threading.Event()
    synth_mgr = RunManager(
        _settings,
        lambda s: _StubProducer(),
        run_synth=_gated_corpus(gate, started),  # signature-compatible stub
        guard=guard,
    )
    mine_mgr = _mine_manager_with(_instant_corpus(), guard=guard)
    client = _client(mine_mgr, run_manager=synth_mgr)
    try:
        synth_id = client.post("/synth/run", json={}).json()["runId"]
        assert started.wait(timeout=5)
        resp = client.post("/mine/run", json={})
        assert resp.status_code == 409
        assert resp.json()["runId"] == synth_id
    finally:
        gate.set()
    _wait_until_idle(client, path="/synth/status")


# --------------------------------------------------------------------------- AC 88: read routes


def test_ac88_health_and_metrics_responsive_during_mine_run() -> None:
    gate = threading.Event()
    started = threading.Event()
    client = _client(_mine_manager_with(_gated_corpus(gate, started)))
    try:
        client.post("/mine/run", json={})
        assert started.wait(timeout=5)
        t0 = time.monotonic()
        assert client.get("/health").status_code == 200
        assert client.get("/metrics").status_code == 200
        assert time.monotonic() - t0 < 2.0
    finally:
        gate.set()
    _wait_until_idle(client)


# --------------------------------------------------------------------------- AC 89: no-manager


def test_ac89_app_without_mine_manager_has_no_mine_routes() -> None:
    client = TestClient(create_app(RunState(started=True)), raise_server_exceptions=False)
    assert client.get("/mine/status").status_code == 404
    assert client.get("/health").status_code == 200


# --------------------------------------------------------------------------- MineRunManager unit


def test_mine_manager_single_run_concurrency_one_409() -> None:
    gate = threading.Event()
    started = threading.Event()
    mm = _mine_manager_with(_gated_corpus(gate, started))
    first = mm.start(MineRunOverrides())
    assert started.wait(timeout=5)
    with pytest.raises(RunConflict) as excinfo:
        mm.start(MineRunOverrides())
    assert excinfo.value.active_run_id == first
    gate.set()
    deadline = time.monotonic() + 5
    while mm.status().status == "running" and time.monotonic() < deadline:
        time.sleep(0.02)
    assert mm.status().status == "idle"


def test_derive_mine_settings_applies_only_present_overrides() -> None:
    base = load_settings(
        {
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "SCENARIO_INSTANCES": "8",
            "SIM_SEED": "7",
        }
    )
    derived = derive_mine_settings(base, MineRunOverrides(scenario_instances=20))
    assert derived.scenario_instances == 20
    assert derived.sim_seed == 7  # untouched
    assert base.scenario_instances == 8  # base not mutated


def test_derive_mine_settings_pins_p2_generate() -> None:
    base = load_settings(
        {"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092", "PHASE": "p3", "SIM_MODE": "synth"}
    )
    derived = derive_mine_settings(base, MineRunOverrides())
    # a mine run always drives the P2 generate corpus path regardless of ambient env
    assert derived.phase == "p2"
    assert derived.sim_mode == "generate"


def test_derive_mine_settings_no_overrides_pins_phase() -> None:
    base = load_settings({"KAFKA_BOOTSTRAP_SERVERS": "localhost:9092"})
    derived = derive_mine_settings(base, MineRunOverrides())
    assert derived.phase == "p2"
    assert derived.sim_mode == "generate"


def test_run_guard_release_reflects_last_run() -> None:
    guard = RunGuard()
    rid = guard.acquire("mine")
    assert guard.snapshot().active is True
    assert guard.snapshot().kind == "mine"
    guard.release()
    snap = guard.snapshot()
    assert snap.active is False
    assert snap.run_id == rid  # last run id retained when idle
