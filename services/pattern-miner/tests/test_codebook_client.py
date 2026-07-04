"""Codebook client contract tests — respx against the REAL Codebook paths + verified shape.

WHY THIS EXISTS
---------------
respx unit mocks alone cannot catch a "client invented a path the service does not serve" bug: a
mock happily intercepts whatever (wrong) URL the client builds. These tests pin the client's
constructed request paths + query params + response parsing against the paths/shape published in the
live Codebook ``/openapi.json`` (verified live; mirrored in the design). A fabricated path (an
``/api/v1`` prefix, ``snapshotId`` alone on ``/codebooks/active``, or the wrong scenario shape) must
FAIL these tests.

Verified live contract:
* ``GET /codebooks/active?domain={domain}&snapshotId={snapshotId}`` — BOTH params required.
* ``GET /codebooks/{codebookId}/scenarios`` -> ``ScenarioListResponse`` with
  ``scenarios[].{scenarioId, faultOriginObjectId, faultOriginType,
  predictedSymptoms:[{alarmType, managedObjectId}], trailIds:[...]}``.
"""

from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest
import respx

from pattern_miner.codebook import (
    CodebookClient,
    CodebookError,
    NoActiveCodebookError,
)

CODEBOOK_URL = "http://codebook.test"
DOMAIN = "core-ip"
SNAPSHOT = "snap-77"
CODEBOOK_ID = "cb-001"

OPENAPI = json.loads((Path(__file__).parent / "fixtures" / "codebook_openapi.json").read_text())


def _client(**kw) -> CodebookClient:
    return CodebookClient(CODEBOOK_URL, retry_max=kw.get("retry_max", 1), retry_backoff_ms=0)


def _scenarios_body() -> dict:
    return {
        "codebookId": CODEBOOK_ID,
        "domain": DOMAIN,
        "scenarios": [
            {
                "scenarioId": "SC-FIBER",
                "faultOriginObjectId": "obj-fiber-1",
                "faultOriginType": "FiberCut",
                "predictedSymptoms": [
                    {"alarmType": "FiberFault", "managedObjectId": "obj-fiber-1"},
                    {"alarmType": "LinkDown", "managedObjectId": "obj-link-1"},
                    {"alarmType": "AdjDown", "managedObjectId": "obj-rtr-1"},
                ],
                "trailIds": ["trail-a", "trail-b"],
            }
        ],
    }


@respx.mock
def test_codebook_client_resolves_active_by_domain_snapshot():
    """resolve_codebook_id GETs /codebooks/active?domain=&snapshotId= and returns codebookId."""
    route = respx.get(f"{CODEBOOK_URL}/codebooks/active").mock(
        return_value=httpx.Response(
            200, json={"codebookId": CODEBOOK_ID, "domain": DOMAIN, "snapshotId": SNAPSHOT}
        )
    )
    codebook_id = _client().resolve_codebook_id(DOMAIN, SNAPSHOT)
    assert codebook_id == CODEBOOK_ID
    request = route.calls.last.request
    assert request.url.path == "/codebooks/active"
    assert request.url.params["domain"] == DOMAIN
    assert request.url.params["snapshotId"] == SNAPSHOT
    assert "/api/v1/" not in str(request.url)


@respx.mock
def test_codebook_client_active_404_raises_no_active():
    """A 404 on /codebooks/active -> NoActiveCodebookError (fail fast; no unanchored mining)."""
    respx.get(f"{CODEBOOK_URL}/codebooks/active").mock(return_value=httpx.Response(404))
    with pytest.raises(NoActiveCodebookError):
        _client().resolve_codebook_id(DOMAIN, SNAPSHOT)


@respx.mock
def test_codebook_client_fetches_scenarios():
    """get_scenarios issues GET /codebooks/{id}/scenarios and parses the verified scenario shape."""
    route = respx.get(f"{CODEBOOK_URL}/codebooks/{CODEBOOK_ID}/scenarios").mock(
        return_value=httpx.Response(200, json=_scenarios_body())
    )
    scenarios = _client().get_scenarios(CODEBOOK_ID)
    assert route.calls.last.request.url.path == f"/codebooks/{CODEBOOK_ID}/scenarios"
    assert len(scenarios) == 1
    s = scenarios[0]
    assert s.scenario_id == "SC-FIBER"
    assert s.fault_origin_type == "FiberCut"
    # symptom_chain is exactly the ORDERED predictedSymptoms[].alarmType list.
    assert s.symptom_chain == ("FiberFault", "LinkDown", "AdjDown")
    assert s.trail_ids == ("trail-a", "trail-b")


@respx.mock
def test_codebook_client_scenarios_url_has_no_api_v1_prefix():
    """Guard against an invented /api/v1 path on the scenarios endpoint."""
    client = _client()
    assert "/api/v1/" not in client._scenarios_url(CODEBOOK_ID)
    assert client._scenarios_url(CODEBOOK_ID).endswith(f"/codebooks/{CODEBOOK_ID}/scenarios")


@respx.mock
def test_codebook_client_retries_then_fails_fast():
    """On repeated 5xx the client retries per CODEBOOK_RETRY_* then raises (fail fast)."""
    route = respx.get(f"{CODEBOOK_URL}/codebooks/active").mock(return_value=httpx.Response(503))
    with pytest.raises(CodebookError):
        CodebookClient(CODEBOOK_URL, retry_max=2, retry_backoff_ms=0).resolve_codebook_id(
            DOMAIN, SNAPSHOT
        )
    assert route.call_count == 3  # retry_max=2 -> 3 attempts total


@respx.mock
def test_codebook_client_recovers_after_transient_error():
    respx.get(f"{CODEBOOK_URL}/codebooks/active").mock(
        side_effect=[
            httpx.Response(503),
            httpx.Response(200, json={"codebookId": CODEBOOK_ID, "domain": DOMAIN}),
        ]
    )
    assert (
        CodebookClient(CODEBOOK_URL, retry_max=2, retry_backoff_ms=0).resolve_codebook_id(
            DOMAIN, SNAPSHOT
        )
        == CODEBOOK_ID
    )


def test_codebook_client_mock_matches_published_openapi():
    """The paths/params the client uses conform to the published Codebook openapi.json.

    Asserts against the SPEC (not the client) that: the two endpoints exist, /codebooks/active
    requires BOTH domain and snapshotId, and the ScenarioList schema carries the verified fields.
    This is the anti-fabrication guard: if the client ever builds a path not in this spec, the
    corresponding respx contract test above (which pins the exact path) fails.
    """
    paths = OPENAPI["paths"]
    assert "/codebooks/active" in paths
    assert "/codebooks/{codebookId}/scenarios" in paths

    active_params = {p["name"]: p for p in paths["/codebooks/active"]["get"]["parameters"]}
    assert active_params["domain"]["required"] is True
    assert active_params["snapshotId"]["required"] is True

    # The client builds exactly these paths (no /api/v1).
    client = _client()
    assert client._active_url() == f"{CODEBOOK_URL}/codebooks/active"
    assert client._scenarios_url("X") == f"{CODEBOOK_URL}/codebooks/X/scenarios"

    # ScenarioList response shape carries the verified scenario fields.
    schemas = OPENAPI["components"]["schemas"]
    scenario_schema = schemas["ScenarioOut"]["properties"]
    for field in (
        "scenarioId",
        "faultOriginObjectId",
        "faultOriginType",
        "predictedSymptoms",
        "trailIds",
    ):
        assert field in scenario_schema, field
    symptom_schema = schemas["PredictedSymptom"]["properties"]
    assert "alarmType" in symptom_schema
    assert "managedObjectId" in symptom_schema


@respx.mock
def test_codebook_client_scenarios_for_resolves_then_fetches():
    """scenarios_for = resolve active codebook then fetch its scenarios (once per run)."""
    respx.get(f"{CODEBOOK_URL}/codebooks/active").mock(
        return_value=httpx.Response(200, json={"codebookId": CODEBOOK_ID, "domain": DOMAIN})
    )
    respx.get(f"{CODEBOOK_URL}/codebooks/{CODEBOOK_ID}/scenarios").mock(
        return_value=httpx.Response(200, json=_scenarios_body())
    )
    scenarios = _client().scenarios_for(DOMAIN, SNAPSHOT)
    assert [s.scenario_id for s in scenarios] == ["SC-FIBER"]
