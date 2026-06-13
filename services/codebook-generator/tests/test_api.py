"""FastAPI read-API tests (spec criteria 8, 13, 16, 18-24).

Drives :func:`codebook_generator.api.create_app` through Starlette's ``TestClient`` against a
store populated by a real compile cycle. Confirms every route returns ``200`` (guarding the
FastAPI ``Depends`` DI-under-``from __future__ import annotations`` gotcha — routes must not
422 on a missing dependency), responses carry ``domain``, and the responses validate against
the published ``openapi.json`` schemas (active-codebook determinism, supersede, the CE
``trail-signatures`` projection shape/root-cause/alias/fan-out).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import jsonschema
import pytest
from fastapi.testclient import TestClient

from codebook_generator.api import create_app
from codebook_generator.bootstrap import Components, build_components
from codebook_generator.store import CodebookStore, scenario_id

from .conftest import FakeProducer, MockCollaborators, make_settings, trails_built_bytes

_OPENAPI = json.loads((Path(__file__).resolve().parents[1] / "openapi.json").read_text())


def _response_schema(path: str, status: str = "200") -> dict[str, Any]:
    """The response body JSON schema for a path/status from the checked-in spec."""
    content = _OPENAPI["paths"][path]["get"]["responses"][status]["content"]
    return content["application/json"]["schema"]


def _validate(instance: Any, schema: dict[str, Any]) -> None:
    """Validate ``instance`` against ``schema`` with the openapi doc as the ref store."""
    resolver = jsonschema.RefResolver.from_schema(_OPENAPI)
    jsonschema.validate(instance=instance, schema=schema, resolver=resolver)


@pytest.fixture
def client(components: Components) -> TestClient:
    """A TestClient bound to the same store the pipeline writes to."""
    return TestClient(create_app(components.store))


@pytest.fixture
def compiled(components: Components) -> str:
    """Compile a core-ip codebook for snap-X and return its codebookId."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-X", domain="core-ip"))
    return result.codebook_id


# --------------------------------------------------------------------------- #
# Health / metrics                                                            #
# --------------------------------------------------------------------------- #
def test_health_returns_200(client: TestClient) -> None:
    """/health returns 200 with the service name (DI dependency resolves)."""
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["service"] == "codebook-generator"


def test_metrics_returns_prometheus_text(client: TestClient) -> None:
    """/metrics returns Prometheus exposition text."""
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert "codebook_compiled_total" in resp.text


# --------------------------------------------------------------------------- #
# AC-13 — metadata carries domain                                            #
# --------------------------------------------------------------------------- #
def test_get_codebook_meta_carries_domain(client: TestClient, compiled: str) -> None:
    """AC-13: GET /codebooks/{id} returns domain and validates against openapi.json."""
    resp = client.get(f"/codebooks/{compiled}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["domain"] == "core-ip"
    _validate(body, _response_schema("/codebooks/{codebookId}"))


def test_get_unknown_codebook_404(client: TestClient) -> None:
    """An unknown codebookId returns 404 (not 422 — DI resolves)."""
    assert client.get("/codebooks/nope").status_code == 404


# --------------------------------------------------------------------------- #
# AC-8 — scenario signature + trail tags by codebookId                       #
# --------------------------------------------------------------------------- #
def test_get_scenario_returns_signature_and_trails(client: TestClient, compiled: str) -> None:
    """AC-8: GET /codebooks/{id}/scenarios/{sid} returns symptoms + trailIds, validates."""
    sid = scenario_id(compiled, "FiberSpan:f1")
    resp = client.get(f"/codebooks/{compiled}/scenarios/{sid}")
    assert resp.status_code == 200
    body = resp.json()
    assert body["faultOriginType"] == "FiberSpan"
    assert body["predictedSymptoms"][0]["alarmType"] == "FiberFault"
    assert body["trailIds"] == ["TRAIL-1"]
    _validate(body, _response_schema("/codebooks/{codebookId}/scenarios/{scenarioId}"))


def test_get_scenarios_list(client: TestClient, compiled: str) -> None:
    """GET /codebooks/{id}/scenarios returns the full scenario list with domain."""
    resp = client.get(f"/codebooks/{compiled}/scenarios")
    assert resp.status_code == 200
    body = resp.json()
    assert body["domain"] == "core-ip"
    assert len(body["scenarios"]) == 4
    _validate(body, _response_schema("/codebooks/{codebookId}/scenarios"))


def test_get_unknown_scenario_404(client: TestClient, compiled: str) -> None:
    """An unknown scenarioId returns 404."""
    assert client.get(f"/codebooks/{compiled}/scenarios/missing").status_code == 404


# --------------------------------------------------------------------------- #
# AC-16 — domain-scoped listing                                              #
# --------------------------------------------------------------------------- #
def test_list_codebooks_filters_by_domain(
    settings, store: CodebookStore, fake_producer: FakeProducer, mocks: MockCollaborators
) -> None:  # noqa: ANN001
    """AC-16: GET /codebooks?domain=core-ip returns only the core-ip codebook."""
    components = build_components(
        settings, message_producer=fake_producer, store=store, http_client=mocks.httpx_client()
    )
    cb_core = components.handler.handle(
        trails_built_bytes(snapshot_id="snap-X", domain="core-ip")
    ).codebook_id
    cb_tr = components.handler.handle(
        trails_built_bytes(snapshot_id="snap-T", domain="transport")
    ).codebook_id

    client = TestClient(create_app(store))
    resp = client.get("/codebooks?domain=core-ip")
    assert resp.status_code == 200
    body = resp.json()
    ids = {c["codebookId"] for c in body["codebooks"]}
    assert cb_core in ids
    assert cb_tr not in ids
    _validate(body, _response_schema("/codebooks"))


def test_list_codebooks_by_snapshot(client: TestClient, compiled: str) -> None:
    """GET /codebooks?snapshotId=snap-X returns the snapshot's codebook."""
    resp = client.get("/codebooks?snapshotId=snap-X")
    assert resp.status_code == 200
    assert any(c["codebookId"] == compiled for c in resp.json()["codebooks"])


def test_list_codebooks_requires_a_filter(client: TestClient) -> None:
    """GET /codebooks with neither filter returns 400."""
    assert client.get("/codebooks").status_code == 400


# --------------------------------------------------------------------------- #
# AC-18/19/20 — active-codebook retrieval, supersede, determinism            #
# --------------------------------------------------------------------------- #
def test_active_endpoint_returns_single_codebook(client: TestClient, compiled: str) -> None:
    """AC-18: GET /codebooks/active returns the active codebook and validates."""
    resp = client.get("/codebooks/active?domain=core-ip&snapshotId=snap-X")
    assert resp.status_code == 200
    body = resp.json()
    assert body["codebookId"] == compiled
    _validate(body, _response_schema("/codebooks/active"))


def test_active_endpoint_404_when_absent(client: TestClient) -> None:
    """AC-18: no compiled codebook for the key -> 404."""
    assert client.get("/codebooks/active?domain=core-ip&snapshotId=never").status_code == 404


def test_supersede_makes_new_codebook_active(components: Components) -> None:
    """AC-19: recompiling the key makes the new codebook active; old stays retrievable."""
    old = components.handler.handle(
        trails_built_bytes(snapshot_id="snap-X", domain="core-ip")
    ).codebook_id
    new = components.handler.handle(
        trails_built_bytes(snapshot_id="snap-X", domain="core-ip")
    ).codebook_id
    assert old != new

    client = TestClient(create_app(components.store))
    active = client.get("/codebooks/active?domain=core-ip&snapshotId=snap-X").json()
    assert active["codebookId"] == new
    # Both remain retrievable by their individual ids (prior content not destroyed).
    assert client.get(f"/codebooks/{old}").status_code == 200
    assert client.get(f"/codebooks/{new}").status_code == 200


def test_active_retrieval_is_deterministic(client: TestClient, compiled: str) -> None:
    """AC-20: two sequential active retrievals return the same codebookId."""
    a = client.get("/codebooks/active?domain=core-ip&snapshotId=snap-X").json()
    b = client.get("/codebooks/active?domain=core-ip&snapshotId=snap-X").json()
    assert a["codebookId"] == b["codebookId"] == compiled


# --------------------------------------------------------------------------- #
# AC-21/22/23/24 — CE trail-signatures projection                            #
# --------------------------------------------------------------------------- #
def test_trail_signatures_shape(client: TestClient, compiled: str) -> None:
    """AC-21: the projection returns the frozen TrailScenarioSignature shape, validated."""
    resp = client.get(f"/codebooks/{compiled}/trail-signatures")
    assert resp.status_code == 200
    body = resp.json()
    _validate(body, _response_schema("/codebooks/{codebookId}/trail-signatures"))
    sig = body["trailSignatures"][0]
    assert set(sig) >= {"trailId", "scenarioId", "rootCauseAlarmType", "expectedSymptoms"}
    assert set(sig["expectedSymptoms"][0]) == {"alarmType", "managedObjectId"}
    # The native scenarios endpoint remains available and unchanged.
    assert client.get(f"/codebooks/{compiled}/scenarios").status_code == 200


def test_trail_signatures_root_cause_is_origin_alarm(client: TestClient, compiled: str) -> None:
    """AC-22: rootCauseAlarmType is the FiberSpan origin's own alarm (FiberFault), not type."""
    body = client.get(f"/codebooks/{compiled}/trail-signatures").json()
    fiber = next(
        s
        for s in body["trailSignatures"]
        if s["scenarioId"] == scenario_id(compiled, "FiberSpan:f1")
    )
    assert fiber["rootCauseAlarmType"] == "FiberFault"
    assert fiber["rootCauseAlarmType"] != "FiberSpan"


def test_trail_signatures_expected_symptoms_alias(client: TestClient, compiled: str) -> None:
    """AC-23: expectedSymptoms == the source scenario's predictedSymptoms (item-for-item)."""
    sid = scenario_id(compiled, "FiberSpan:f1")
    scenario = client.get(f"/codebooks/{compiled}/scenarios/{sid}").json()
    body = client.get(f"/codebooks/{compiled}/trail-signatures").json()
    fiber = next(s for s in body["trailSignatures"] if s["scenarioId"] == sid)
    assert fiber["expectedSymptoms"] == scenario["predictedSymptoms"]


def test_trail_signatures_fanout_and_filter(components: Components) -> None:
    """AC-24: signatures fan out per trail; ?trailId filters; an unknown trail -> empty list."""
    # Compile against a store whose trail builder returns two trails per object.
    settings = make_settings()
    import httpx

    def handler(request: httpx.Request) -> httpx.Response:
        # Reuse the default mock router for everything except by-object (two trails).
        from .conftest import MockCollaborators

        if request.url.path == "/trails/by-object":
            return httpx.Response(
                200,
                json={
                    "managedObjectId": request.url.params["managedObjectId"],
                    "domain": request.url.params["domain"],
                    "trailIds": ["T1", "T2"],
                },
            )
        return MockCollaborators().router.handler(request)

    client_http = httpx.Client(transport=httpx.MockTransport(handler))
    comp = build_components(
        settings,
        message_producer=FakeProducer(),
        store=components.store,
        http_client=client_http,
    )
    cb = comp.handler.handle(trails_built_bytes(snapshot_id="snap-F", domain="core-ip")).codebook_id

    client = TestClient(create_app(components.store))
    fiber_sid = scenario_id(cb, "FiberSpan:f1")

    # No filter: each scenario fans out across T1 and T2.
    all_sigs = client.get(f"/codebooks/{cb}/trail-signatures").json()["trailSignatures"]
    fiber_trails = sorted(s["trailId"] for s in all_sigs if s["scenarioId"] == fiber_sid)
    assert fiber_trails == ["T1", "T2"]

    # ?trailId=T1 returns only the T1 signatures.
    t1 = client.get(f"/codebooks/{cb}/trail-signatures?trailId=T1").json()["trailSignatures"]
    assert t1 and all(s["trailId"] == "T1" for s in t1)

    # A trail matching no scenario returns a 200 empty list.
    empty = client.get(f"/codebooks/{cb}/trail-signatures?trailId=NONE")
    assert empty.status_code == 200
    assert empty.json()["trailSignatures"] == []
