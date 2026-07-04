"""P3 label-surface tests (AC 43) + engine/synth domain-purity (AC 19 invariant).

The P3 cascade labels appear on the existing ``/labels`` surface with additive fields, and the run
summary is served via ``GET /labels/p3-summary`` (the 60-70% KPI is directly computable). The
``synth/`` package and ``engine/`` remain domain-generic — the Core-IP placement affinity is
pack-authored (OQ-P3-1); no Core-IP alarmType/objectType literal leaks into synth/engine.
"""

from __future__ import annotations

from pathlib import Path

from fastapi.testclient import TestClient

from simulator.api.app import RunState, create_app
from simulator.synth.models import P3CascadeLabel, P3RunSummary
from simulator.synth.p3_labels import P3LabelStore


def _state_with_p3() -> RunState:
    store = P3LabelStore()
    store.record(
        P3CascadeLabel(
            pattern_id="pat-01",
            trail_id="trail-A",
            root_cause_alarm_id="alm-root",
            root_cause_alarm_type="IPLinkDown",
            child_alarm_ids=["alm-c1", "alm-c2"],
            scenario_type="pattern-aligned",
        )
    )
    store.set_summary(
        P3RunSummary(
            total_alarms=200, aligned_alarms=132, non_aligned_alarms=68, aligned_fraction=0.66
        )
    )
    return RunState(started=True, p3_labels=store)


def test_ac43_labels_endpoint_includes_p3_cascade_records() -> None:
    client = TestClient(create_app(_state_with_p3()))
    resp = client.get("/labels")
    assert resp.status_code == 200
    body = resp.json()
    p3_records = [
        r for r in body if r.get("scenarioType") == "pattern-aligned" and "patternId" in r
    ]
    assert p3_records
    rec = p3_records[0]
    assert rec["rootCauseAlarmType"] == "IPLinkDown"
    assert rec["childAlarmIds"] == ["alm-c1", "alm-c2"]


def test_ac43_p3_summary_endpoint() -> None:
    client = TestClient(create_app(_state_with_p3()))
    resp = client.get("/labels/p3-summary")
    assert resp.status_code == 200
    body = resp.json()
    assert body["alignedFraction"] == 0.66
    assert body["totalAlarms"] == 200
    assert body["alignedAlarms"] == 132


def test_p3_summary_404_when_no_run() -> None:
    client = TestClient(create_app(RunState(started=True)))
    assert client.get("/labels/p3-summary").status_code == 404


def test_p3_summary_route_precedes_scenario_id_route() -> None:
    """/labels/p3-summary must not be captured by /labels/{scenario_id}."""
    client = TestClient(create_app(_state_with_p3()))
    resp = client.get("/labels/p3-summary")
    assert resp.status_code == 200
    assert "alignedFraction" in resp.json()


# --- AC 19 invariant: no Core-IP literal in engine/ or synth/ ------------------------------
_CORE_IP_TOKENS = ("IPLinkDown", "ISISAdjacencyDown", "FiberFault", "core-ip", "IGPAdjacency")


def test_ac19_synth_package_has_no_core_ip_literals() -> None:
    synth_dir = Path(__file__).resolve().parents[1] / "src" / "simulator" / "synth"
    for py in synth_dir.glob("*.py"):
        text = py.read_text()
        for token in _CORE_IP_TOKENS:
            assert token not in text, f"{py.name} leaks Core-IP literal {token!r}"


def test_ac19_engine_still_domain_generic() -> None:
    engine_dir = Path(__file__).resolve().parents[1] / "src" / "simulator" / "engine"
    for py in engine_dir.glob("*.py"):
        text = py.read_text()
        for token in ("IPLinkDown", "ISISAdjacencyDown", "FiberFault"):
            assert token not in text, f"{py.name} leaks Core-IP literal {token!r}"
