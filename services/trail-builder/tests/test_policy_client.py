"""IO-layer unit tests for ``KnowledgePolicyClient`` (domain-scoped trail-policy fetch).

The transport is mocked with ``respx`` (the ``KNOWLEDGE_SERVICE_MODE=mock`` backing) via
the shared ``install_knowledge_stub`` fixture, which serves a per-domain ``trailPolicy``
``RecordResponse``. Maps to:

- AC-8  ``invalidate`` drops the cache so the next fetch re-reads (knowledge.updated refresh).
- AC-9  policy is fetched per domain — two domains -> two parameterized calls.
- AC-11 a non-Core-IP domain's policy is decoded and used without a code change.
- AC-12 config-switchable integration point exercised in mock mode.
- AC-13 the base-URL comes from settings (no hard-coded URL).
"""

from __future__ import annotations

import httpx
import pytest

from fixtures import DEFAULT_POLICY, install_knowledge_stub
from trailbuilder.clients.errors import IntegrationError
from trailbuilder.clients.policy_client import (
    POLICY_PATH_SEGMENT,
    POLICY_RECORD_ID,
    KnowledgePolicyClient,
)

BASE = "http://knowledge.test"

# The exact request the client must issue, verified against Knowledge's published
# RecordController contract (origin/build/knowledge): the route is
# GET /domains/{domain}/{recordTypePathSegment}/{recordId}, where the segment is the
# kebab-case form of the RecordType enum (TRAIL_POLICY -> "trail-policies") and the
# recordId is the seeded slash-bearing id "core-ip/trailPolicy/default", URL-encoded so
# the slashes survive as one path segment (the controller URLDecoder.decodes it once).
EXPECTED_PATH = "/domains/core-ip/trail-policies/core-ip%2FtrailPolicy%2Fdefault"

# A distinct non-Core-IP policy (different boundary + SRLG rule) for AC-11.
METRO_POLICY = {
    "closureEdgeTypes": ["HOSTS", "TERMINATES"],
    "boundary": {"type": "none", "attributeKey": None},
    "srlgRule": {"mode": "none", "srlgEdgeType": None},
}


def _client(settings) -> KnowledgePolicyClient:
    return KnowledgePolicyClient(settings, client=httpx.Client(base_url=BASE, timeout=5.0))


def test_fetch_issues_exact_contract_path(settings) -> None:
    """Contract pin: the client must hit Knowledge's exact RecordController route.

    Regression guard for #206 — the client previously called the off-contract
    ``/domains/{domain}/trailPolicy/default`` (wrong kebab segment + wrong recordId),
    which Knowledge answered with HTTP 400, killing the P1 chain. The single request
    must be ``GET /domains/core-ip/trail-policies/core-ip%2FtrailPolicy%2Fdefault``.
    """
    seen: list[str] = []
    router = __import__("respx").mock(base_url=BASE, assert_all_called=True)

    def _capture(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.raw_path.decode())
        return httpx.Response(
            200,
            json={
                "domain": "core-ip",
                "recordType": "trailPolicy",
                "recordId": POLICY_RECORD_ID,
                "version": "1",
                "isCurrent": True,
                "payload": DEFAULT_POLICY,
            },
        )

    # Match ONLY the exact contract path — any drift (e.g. back to trailPolicy/default)
    # leaves the route unmatched and respx fails the request.
    router.get(EXPECTED_PATH).mock(side_effect=_capture)
    with router:
        _client(settings).get_policy("core-ip")

    assert seen == [EXPECTED_PATH]


def test_ping_uses_exact_contract_path(settings) -> None:
    """``ping`` health-checks the same contract route (not the off-contract one)."""
    seen: list[str] = []
    router = __import__("respx").mock(base_url=BASE, assert_all_called=True)

    def _capture(request: httpx.Request) -> httpx.Response:
        seen.append(request.url.raw_path.decode())
        return httpx.Response(200, json={"payload": DEFAULT_POLICY})

    router.get(EXPECTED_PATH).mock(side_effect=_capture)
    with router:
        assert _client(settings).ping() is True
    assert seen == [EXPECTED_PATH]


def test_path_segment_and_record_id_match_knowledge_seed() -> None:
    """Drift guard vs Knowledge's published recordType path segment + seeded id.

    The kebab path segment is the canonical RecordType.pathSegment() for TRAIL_POLICY,
    and the recordId is the literal stored in core-ip.json. Pinned here so a future
    drift in either constant is caught by a unit test, not at integration time.
    """
    assert POLICY_PATH_SEGMENT == "trail-policies"
    assert POLICY_RECORD_ID == "core-ip/trailPolicy/default"


def test_get_policy_decodes_record_response(settings) -> None:
    """AC-12: in mock mode the client decodes the frozen trailPolicy RecordResponse."""
    with install_knowledge_stub(BASE, {"core-ip": DEFAULT_POLICY}):
        policy = _client(settings).get_policy("core-ip")
    assert policy.closure_edge_types == tuple(DEFAULT_POLICY["closureEdgeTypes"])
    assert policy.boundary.type == "igp-area"
    assert policy.boundary.attribute_key == "igpArea"
    assert policy.srlg_rule.mode == "union-members"
    assert policy.srlg_rule.srlg_edge_type == "MEMBER_OF"


def test_get_policy_is_cached_per_domain(settings) -> None:
    """AC-9: a second get_policy for the same domain hits the cache (one network call)."""
    call_log: list[str] = []
    with install_knowledge_stub(BASE, {"core-ip": DEFAULT_POLICY}, call_log=call_log):
        client = _client(settings)
        client.get_policy("core-ip")
        client.get_policy("core-ip")
    assert call_log == ["core-ip"]


def test_policy_is_fetched_per_domain(settings) -> None:
    """AC-9/AC-11: two domains -> two parameterized fetches, each its own policy."""
    call_log: list[str] = []
    with install_knowledge_stub(
        BASE, {"core-ip": DEFAULT_POLICY, "metro": METRO_POLICY}, call_log=call_log
    ):
        client = _client(settings)
        core = client.get_policy("core-ip")
        metro = client.get_policy("metro")

    assert sorted(call_log) == ["core-ip", "metro"]
    # AC-11: the non-Core-IP domain's distinct policy is used, no code change.
    assert core.boundary.type == "igp-area"
    assert metro.boundary.type == "none"
    assert metro.srlg_rule.mode == "none"


def test_invalidate_forces_refetch(settings) -> None:
    """AC-8: invalidate(domain) drops the cache so the next get_policy re-fetches."""
    call_log: list[str] = []
    with install_knowledge_stub(BASE, {"core-ip": DEFAULT_POLICY}, call_log=call_log):
        client = _client(settings)
        client.get_policy("core-ip")
        client.invalidate("core-ip")
        client.get_policy("core-ip")
    assert call_log == ["core-ip", "core-ip"]


def test_unknown_domain_raises_integration_error(settings) -> None:
    """A 404 for an unknown domain surfaces as a labelled IntegrationError."""
    with install_knowledge_stub(BASE, {"core-ip": DEFAULT_POLICY}):
        with pytest.raises(IntegrationError) as ei:
            _client(settings).get_policy("nope")
    assert ei.value.reason == "knowledge"


def test_empty_closure_edge_types_is_integration_error(settings) -> None:
    """A trailPolicy with no closure edge types is rejected (unusable policy)."""
    bad = {**DEFAULT_POLICY, "closureEdgeTypes": []}
    with install_knowledge_stub(BASE, {"core-ip": bad}):
        with pytest.raises(IntegrationError):
            _client(settings).get_policy("core-ip")


def test_missing_payload_field_is_integration_error(settings) -> None:
    """A trailPolicy payload missing a required field is rejected."""
    bad = {"closureEdgeTypes": ["HOSTS"]}  # no boundary / srlgRule
    with install_knowledge_stub(BASE, {"core-ip": bad}):
        with pytest.raises(IntegrationError):
            _client(settings).get_policy("core-ip")


def test_stale_not_ok_raises_when_uncached(settings) -> None:
    """Without stale-ok and no cache, a failed fetch raises IntegrationError."""
    settings = settings.model_copy(update={"knowledge_stale_ok": False})
    client = _client(settings)
    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get(url__regex=r".*/domains/[^/]+/trail-policies/[^/?]+(\?.*)?$").mock(
        return_value=httpx.Response(503, json={"error": "down"})
    )
    with router:
        with pytest.raises(IntegrationError):
            client.get_policy("core-ip")


def test_base_url_comes_from_settings(settings) -> None:
    """AC-13: the configured base URL is used (no hard-coded URL)."""
    client = KnowledgePolicyClient(settings)
    assert client._base == settings.knowledge_service_base_url.rstrip("/")  # noqa: SLF001
