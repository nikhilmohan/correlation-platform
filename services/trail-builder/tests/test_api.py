"""HTTP API tests via FastAPI ``TestClient`` against a Container built with
STUBBED Topology/Knowledge clients + a seeded in-memory SQLite repository.

Maps the API-facing acceptance criteria:

- AC-1  multi-trail overlap   -> getTrailsForObject returns >= 3 distinct ids.
- AC-4  getTrailsForObject completeness (exact set per object).
- AC-5  getTrail correctness: members, snapshotId, domain; typed <objectType>:<id>.
- AC-10 trails carry domain; listTrails is domain-scoped (no cross-domain leakage).
- AC-16 OpenAPI contract: required-param 422 paths + response-body shapes.
- AC-17 listTrails enumerates all trails for snapshot+domain.
- AC-18 getTrail members are typed managedObjectIds (visualization-ready).
- POST /trails/rebuild happy-path + 502 dependency-unavailable.
- 404 on unknown trailId.
- pagination via limit/offset.
"""

from __future__ import annotations

import httpx
import pytest
from fastapi.testclient import TestClient

from fixtures import DEFAULT_POLICY, FakeGraph, install_knowledge_stub, install_topology_stub
from trailbuilder.api import create_app
from trailbuilder.clients.errors import IntegrationError
from trailbuilder.clients.policy_client import KnowledgePolicyClient
from trailbuilder.clients.topology_client import TopologyClient
from trailbuilder.container import build_container
from trailbuilder.models import Trail

TOPO_BASE = "http://topology.test"
KNOW_BASE = "http://knowledge.test"


def _trail(
    trail_id: str,
    domain: str,
    snapshot_id: str,
    members: tuple[str, ...],
    *,
    seed: str | None = None,
    igp_area: str | None = None,
    srlg_group: str | None = None,
) -> Trail:
    return Trail(
        trail_id=trail_id,
        domain=domain,
        snapshot_id=snapshot_id,
        seed_managed_object_id=seed or members[0],
        members=members,
        igp_area=igp_area,
        srlg_group=srlg_group,
    )


@pytest.fixture
def container(settings, engine, producer):
    """A Container wired with respx-stubbed Topology/Knowledge clients + SQLite."""
    topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
    policy = KnowledgePolicyClient(settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0))
    return build_container(settings, engine, producer, topology_client=topo, policy_client=policy)


@pytest.fixture
def seeded_container(container):
    """Container whose repository is pre-loaded with overlapping, multi-domain trails.

    Object ``Port:X`` deliberately participates in three trails (two LSP paths +
    one SRLG group) so AC-1 (overlap) is real and not degenerate.
    """
    repo = container.repository
    # core-ip / snap-1: three trails that overlap on Port:X (AC-1).
    repo.persist_build(
        "core-ip",
        "snap-1",
        [
            _trail(
                "t-lsp-a",
                "core-ip",
                "snap-1",
                ("Port:X", "Interface:X.1", "IPLink:A"),
                igp_area="area-0",
            ),
            _trail(
                "t-lsp-b",
                "core-ip",
                "snap-1",
                ("Port:X", "Interface:X.2", "IPLink:B"),
                igp_area="area-0",
            ),
            _trail(
                "t-srlg",
                "core-ip",
                "snap-1",
                ("Port:X", "IPLink:A", "IPLink:B"),
                srlg_group="srlg-7",
            ),
        ],
    )
    # metro / snap-1: a separate-domain trail on the SAME snapshotId (AC-10).
    repo.persist_build(
        "metro",
        "snap-1",
        [_trail("t-metro", "metro", "snap-1", ("Node:M1", "Node:M2"))],
    )
    return container


@pytest.fixture
def client(seeded_container):
    return TestClient(create_app(seeded_container))


# --- AC-1 / AC-4: getTrailsForObject ---------------------------------------


def test_get_trails_for_object_overlap(client) -> None:
    """AC-1: Port:X belongs to >= 3 distinct trails (two LSPs + one SRLG)."""
    resp = client.get(
        "/trails/by-object", params={"managedObjectId": "Port:X", "domain": "core-ip"}
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["managedObjectId"] == "Port:X"
    assert body["domain"] == "core-ip"
    assert len(set(body["trailIds"])) >= 3
    assert set(body["trailIds"]) == {"t-lsp-a", "t-lsp-b", "t-srlg"}


def test_get_trails_for_object_exact_set(client) -> None:
    """AC-4: completeness — each object returns exactly its trail-id set."""
    resp = client.get(
        "/trails/by-object", params={"managedObjectId": "Interface:X.1", "domain": "core-ip"}
    )
    assert resp.status_code == 200
    assert resp.json()["trailIds"] == ["t-lsp-a"]


def test_get_trails_for_object_empty_when_absent(client) -> None:
    """An object with no trails returns an empty (not error) list."""
    resp = client.get(
        "/trails/by-object", params={"managedObjectId": "Port:UNKNOWN", "domain": "core-ip"}
    )
    assert resp.status_code == 200
    assert resp.json()["trailIds"] == []


def test_get_trails_for_object_domain_scoped(client) -> None:
    """AC-10: a metro object is not visible under the core-ip domain (no leakage)."""
    resp = client.get(
        "/trails/by-object", params={"managedObjectId": "Node:M1", "domain": "core-ip"}
    )
    assert resp.status_code == 200
    assert resp.json()["trailIds"] == []
    resp_metro = client.get(
        "/trails/by-object", params={"managedObjectId": "Node:M1", "domain": "metro"}
    )
    assert resp_metro.json()["trailIds"] == ["t-metro"]


def test_get_trails_for_object_requires_domain(client) -> None:
    """AC-16: domain is REQUIRED — 422 when omitted."""
    resp = client.get("/trails/by-object", params={"managedObjectId": "Port:X"})
    assert resp.status_code == 422


def test_get_trails_for_object_malformed_id_422(client) -> None:
    """A managedObjectId not matching <objectType>:<id> is rejected with 422."""
    resp = client.get(
        "/trails/by-object", params={"managedObjectId": "not-typed", "domain": "core-ip"}
    )
    assert resp.status_code == 422


# --- AC-5 / AC-18: getTrail -------------------------------------------------


def test_get_trail_correctness_and_typed_members(client) -> None:
    """AC-5 + AC-18: full member list, snapshotId, domain; every member is typed."""
    resp = client.get("/trails/t-lsp-a")
    assert resp.status_code == 200
    body = resp.json()
    assert body["trailId"] == "t-lsp-a"
    assert body["domain"] == "core-ip"
    assert body["snapshotId"] == "snap-1"
    assert body["memberCount"] == len(body["members"]) == 3
    member_ids = {m["managedObjectId"] for m in body["members"]}
    assert member_ids == {"Port:X", "Interface:X.1", "IPLink:A"}
    # AC-18: every member matches <objectType>:<id> and carries the parsed type.
    for m in body["members"]:
        assert ":" in m["managedObjectId"]
        assert m["objectType"] == m["managedObjectId"].split(":", 1)[0]
    # AC-19 readiness: an Interface:* layer member is present + typed.
    assert any(m["objectType"] == "Interface" for m in body["members"])


def test_get_trail_404_on_unknown(client) -> None:
    """getTrail 404s on an unknown trailId."""
    resp = client.get("/trails/does-not-exist")
    assert resp.status_code == 404


# --- AC-10 / AC-17: listTrails ----------------------------------------------


def test_list_trails_enumerates_all_for_snapshot_domain(client) -> None:
    """AC-17: listTrails returns exactly the N trails for snapshot+domain."""
    resp = client.get("/trails", params={"snapshotId": "snap-1", "domain": "core-ip"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["snapshotId"] == "snap-1"
    assert body["domain"] == "core-ip"
    assert body["count"] == 3
    ids = {t["trailId"] for t in body["trails"]}
    assert ids == {"t-lsp-a", "t-lsp-b", "t-srlg"}
    # AC-17: each summary carries a member count > 0 and the domain.
    for t in body["trails"]:
        assert t["domain"] == "core-ip"
        assert t["memberCount"] > 0


def test_list_trails_domain_scoped_no_leakage(client) -> None:
    """AC-10: same snapshotId, different domain -> only that domain's trails."""
    resp = client.get("/trails", params={"snapshotId": "snap-1", "domain": "metro"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["count"] == 1
    assert body["trails"][0]["trailId"] == "t-metro"
    assert body["trails"][0]["domain"] == "metro"


def test_list_trails_pagination(client) -> None:
    """Pagination via limit/offset slices the ordered result set deterministically."""
    page1 = client.get(
        "/trails", params={"snapshotId": "snap-1", "domain": "core-ip", "limit": 2, "offset": 0}
    ).json()
    page2 = client.get(
        "/trails", params={"snapshotId": "snap-1", "domain": "core-ip", "limit": 2, "offset": 2}
    ).json()
    assert [t["trailId"] for t in page1["trails"]] == ["t-lsp-a", "t-lsp-b"]
    assert [t["trailId"] for t in page2["trails"]] == ["t-srlg"]


def test_list_trails_requires_domain(client) -> None:
    """AC-16: domain is REQUIRED on listTrails -> 422 when omitted."""
    resp = client.get("/trails", params={"snapshotId": "snap-1"})
    assert resp.status_code == 422


def test_list_trails_invalid_limit_422(client) -> None:
    """AC-16: limit out of bounds is rejected (request-schema validation)."""
    resp = client.get("/trails", params={"snapshotId": "snap-1", "domain": "core-ip", "limit": 0})
    assert resp.status_code == 422


# --- POST /trails/rebuild ---------------------------------------------------


def _rebuild_client(settings, engine, producer, graph: FakeGraph) -> TestClient:
    topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
    policy = KnowledgePolicyClient(settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0))
    container = build_container(
        settings, engine, producer, topology_client=topo, policy_client=policy
    )
    return TestClient(create_app(container))


def test_rebuild_happy_path(settings, engine, producer) -> None:
    """POST /trails/rebuild builds + returns a TrailsBuiltSummary (trailCount==len)."""
    graph = FakeGraph(domain="core-ip")
    graph.add_node("Port:P1", "Port", igp_area="area-0")
    graph.add_node("Interface:P1.100", "Interface", igp_area="area-0")
    graph.add_node("IPLink:L1", "IPLink")
    graph.add_edge("Port:P1", "Interface:P1.100", "HOSTS")
    graph.add_edge("Interface:P1.100", "IPLink:L1", "TERMINATES")
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        client = _rebuild_client(settings, engine, producer, graph)
        resp = client.post("/trails/rebuild", json={"snapshotId": "snap-9", "domain": "core-ip"})
    assert resp.status_code == 200
    body = resp.json()
    assert body["snapshotId"] == "snap-9"
    assert body["domain"] == "core-ip"
    assert body["trailCount"] == len(body["trailIds"])
    assert body["trailIds"]


def test_rebuild_missing_domain_422(settings, engine, producer) -> None:
    """AC-16: rebuild requires both snapshotId and domain -> 422 when domain missing."""
    graph = FakeGraph(domain="core-ip")
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        client = _rebuild_client(settings, engine, producer, graph)
        resp = client.post("/trails/rebuild", json={"snapshotId": "snap-9"})
    assert resp.status_code == 422


def test_rebuild_502_on_dependency_unavailable(settings, engine, producer) -> None:
    """A Topology/Knowledge IntegrationError surfaces as HTTP 502 (not a 500)."""

    class _FailingBuild:
        def build(self, *a: object, **k: object) -> object:
            raise IntegrationError("knowledge", "unreachable")

    topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
    policy = KnowledgePolicyClient(settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0))
    container = build_container(
        settings, engine, producer, topology_client=topo, policy_client=policy
    )
    container.build_service = _FailingBuild()  # type: ignore[assignment]
    client = TestClient(create_app(container))
    resp = client.post("/trails/rebuild", json={"snapshotId": "snap-9", "domain": "core-ip"})
    assert resp.status_code == 502


# --- AC-16: OpenAPI contract surface ----------------------------------------


def test_openapi_published_with_frozen_paths(client) -> None:
    """AC-16: /openapi.json is 3.1 and carries the frozen operation paths."""
    spec = client.get("/openapi.json").json()
    assert spec["openapi"].startswith("3.1")
    paths = spec["paths"]
    assert "/trails/by-object" in paths
    assert "/trails/{trailId}" in paths
    assert "/trails" in paths
    assert "/trails/rebuild" in paths
    # Frozen operation ids the consumers generate clients against.
    op_ids = {
        op["operationId"]
        for path in paths.values()
        for op in path.values()
        if isinstance(op, dict) and "operationId" in op
    }
    assert {"getTrailsForObject", "getTrail", "listTrails", "rebuildTrails"} <= op_ids


# --- /health + /metrics (observability surface) -----------------------------


def test_health_ok_when_all_dependencies_reachable(settings, engine, producer) -> None:
    """/health is 200 + status ok when topology, knowledge and DB all respond."""
    graph = FakeGraph(domain="core-ip")
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        client = _rebuild_client(settings, engine, producer, graph)
        resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["dependencies"]["topology"] == "ok"
    assert body["dependencies"]["knowledge"] == "ok"
    assert body["dependencies"]["db"] == "ok"


def test_health_degraded_returns_503(settings, engine, producer) -> None:
    """When an integration point is unreachable, /health degrades to 503."""

    class _DownTopology:
        def ping(self) -> bool:
            return False

    topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
    policy = KnowledgePolicyClient(settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0))
    container = build_container(
        settings, engine, producer, topology_client=topo, policy_client=policy
    )
    container.topology = _DownTopology()  # type: ignore[assignment]
    client = TestClient(create_app(container))
    resp = client.get("/health")
    assert resp.status_code == 503
    assert resp.json()["status"] == "degraded"
    assert resp.json()["dependencies"]["topology"] == "degraded"


def test_metrics_endpoint_serves_prometheus(client) -> None:
    """/metrics serves the Prometheus exposition with our query counter present."""
    # Drive at least one query so the counter is registered/non-empty.
    client.get("/trails/by-object", params={"managedObjectId": "Port:X", "domain": "core-ip"})
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert "query_requests_total" in resp.text
