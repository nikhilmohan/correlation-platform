"""HTTP surface tests (criteria 10, 10a, 16, 17).

The read-only FastAPI surface exposes /health (liveness), /metrics (Prometheus), /labels and
/scenarios (ground-truth retrieval). Exercised via fastapi.testclient against ``create_app`` —
the uvicorn process boundary (server.py) is integration-only. OpenAPI 3.1 is auto-generated at
/openapi.json and the checked-in services/simulator/openapi.json is the authoritative surface.
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from simulator.api.app import RunState, create_app
from simulator.engine.labels import LabelStore
from simulator.engine.models import GroundTruthLabel
from simulator.synth.run_manager import RunManager


def _full_app():
    """The full HTTP surface (read routes + synth trigger) — the authoritative openapi surface."""

    def _noop_run(settings, producer, *, run_id, progress=None):  # pragma: no cover
        raise RuntimeError("unused")

    rm = RunManager(
        lambda: None,  # type: ignore[arg-type,return-value]
        lambda s: None,  # type: ignore[arg-type,return-value]
        run_synth=_noop_run,
    )
    return create_app(RunState(started=True), run_manager=rm)


def _label(scenario_id: str = "s1") -> GroundTruthLabel:
    return GroundTruthLabel(
        scenario_id=scenario_id,
        scenario_type="fiber-cut",
        root_cause="alarm-root",
        root_cause_managed_object_id="FiberSpan:f1",
        root_cause_alarm_type="FiberFault",
        children=["alarm-c1", "alarm-c2"],
    )


def _client(state: RunState) -> TestClient:
    return TestClient(create_app(state), raise_server_exceptions=False)


def test_ac16_health_returns_200_when_running() -> None:
    resp = _client(RunState(started=True, kafka_connected=True)).get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_ac16_health_non_200_before_startup() -> None:
    resp = _client(RunState(started=False)).get("/health")
    assert resp.status_code == 503


def test_ac16_health_non_200_on_kafka_loss() -> None:
    resp = _client(RunState(started=True, kafka_connected=False)).get("/health")
    assert resp.status_code == 503
    assert resp.json()["kafka"] == "disconnected"


def test_ac17_metrics_returns_prometheus_text() -> None:
    resp = _client(RunState(started=True)).get("/metrics")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("text/plain")
    assert "simulator_alarms_emitted_total" in resp.text


def test_ac10_labels_endpoint_returns_frozen_shape() -> None:
    store = LabelStore()
    store.record(_label("s1"))
    resp = _client(RunState(started=True, labels=store)).get("/labels")
    assert resp.status_code == 200
    body = resp.json()
    assert body[0]["scenarioId"] == "s1"
    assert body[0]["rootCause"] == "alarm-root"
    assert body[0]["rootCauseAlarmType"] == "FiberFault"
    assert body[0]["children"] == ["alarm-c1", "alarm-c2"]


def test_ac10_labels_filtered_by_scenario_id() -> None:
    store = LabelStore()
    store.record(_label("s1"))
    store.record(_label("s2"))
    resp = _client(RunState(started=True, labels=store)).get("/labels", params={"scenarioId": "s2"})
    assert resp.status_code == 200
    assert [r["scenarioId"] for r in resp.json()] == ["s2"]


def test_ac10_labels_unknown_scenario_id_is_404() -> None:
    resp = _client(RunState(started=True, labels=LabelStore())).get(
        "/labels", params={"scenarioId": "nope"}
    )
    assert resp.status_code == 404


def test_ac10_label_by_id_path() -> None:
    store = LabelStore()
    store.record(_label("s1"))
    client = _client(RunState(started=True, labels=store))
    assert client.get("/labels/s1").json()["scenarioId"] == "s1"
    assert client.get("/labels/missing").status_code == 404


def test_scenarios_endpoint_returns_state_scenarios() -> None:
    scenarios = [{"scenarioType": "fiber-cut", "rootCauseObjectType": "FiberSpan"}]
    resp = _client(RunState(started=True, scenarios=scenarios)).get("/scenarios")
    assert resp.status_code == 200
    assert resp.json() == scenarios


def test_openapi_is_3_1() -> None:
    resp = _client(RunState(started=True)).get("/openapi.json")
    assert resp.status_code == 200
    assert resp.json()["openapi"].startswith("3.1")


def test_checked_in_openapi_matches_live_surface() -> None:
    """The checked-in services/simulator/openapi.json is the authoritative surface (no drift)."""
    import json
    from pathlib import Path

    spec_path = Path(__file__).resolve().parents[1] / "openapi.json"
    assert spec_path.exists(), "services/simulator/openapi.json must be checked in"
    checked_in = json.loads(spec_path.read_text())
    # The authoritative surface is the FULL app (read routes + the synth trigger, AC 75).
    live = _full_app().openapi()
    assert checked_in["openapi"] == live["openapi"]
    assert set(checked_in["paths"]) == set(live["paths"])


def test_ac75_openapi_declares_synth_endpoints() -> None:
    """POST /synth/run (202/409/422) and GET /synth/status (200) are in the checked-in spec."""
    import json
    from pathlib import Path

    spec_path = Path(__file__).resolve().parents[1] / "openapi.json"
    checked_in = json.loads(spec_path.read_text())
    paths = checked_in["paths"]
    assert "/synth/run" in paths, "checked-in openapi.json missing /synth/run"
    assert "/synth/status" in paths, "checked-in openapi.json missing /synth/status"
    run_responses = paths["/synth/run"]["post"]["responses"]
    assert {"202", "409", "422"}.issubset(run_responses)
    assert "200" in paths["/synth/status"]["get"]["responses"]


def test_ac75_drift_guard_catches_missing_synth_status() -> None:
    """Deleting /synth/status from the checked-in doc makes the drift comparison fail (non-zero)."""
    import copy
    import json
    from pathlib import Path

    spec_path = Path(__file__).resolve().parents[1] / "openapi.json"
    checked_in = json.loads(spec_path.read_text())
    tampered = copy.deepcopy(checked_in)
    tampered["paths"].pop("/synth/status", None)
    live = _full_app().openapi()
    # The real drift assertion is set-equality on paths; the tampered doc must fail it.
    assert set(tampered["paths"]) != set(live["paths"])
