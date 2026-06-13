"""Orchestration tests for ``BuildService`` (the shared build pipeline).

``BuildService`` is exercised end-to-end with the REAL closure + repository (the
in-memory SQLite ``engine`` fixture) and stubbed Topology/Knowledge clients —
either the respx-backed real clients (mock mode) or lightweight fakes for
fine-grained control of the dependency-failure path. Maps to:

- AC-6  a build persists trails and returns a result whose trailCount == len(trailIds),
        snapshotId/domain carried through; emit produces trails.built.
- AC-9  the policy is fetched per domain (two domains -> two policy fetches).
- AC-11 a non-Core-IP domain builds with its own policy and carries the right domain.
- AC-15 a rebuild for a new snapshotId leaves prior-snapshot records intact.
- AC-20 the emitted domain is the supplied domain (no Topology lookup for domain).
- Dependency failure (IntegrationError) is re-raised so the caller holds the event.
"""

from __future__ import annotations

import httpx
import pytest

from fixtures import (
    DEFAULT_POLICY,
    FakeGraph,
    install_knowledge_stub,
    install_topology_stub,
)
from trailbuilder.build_service import BuildResult, BuildService
from trailbuilder.clients.errors import IntegrationError
from trailbuilder.clients.policy_client import KnowledgePolicyClient
from trailbuilder.clients.topology_client import TopologyClient
from trailbuilder.closure import TrailClosure
from trailbuilder.event_publisher import TrailsBuiltPublisher
from trailbuilder.models import Boundary, GraphSlice, SrlgRule, TrailPolicy
from trailbuilder.repository import TrailRepository

TOPO_BASE = "http://topology.test"
KNOW_BASE = "http://knowledge.test"

METRO_POLICY = {
    "closureEdgeTypes": ["HOSTS", "TERMINATES"],
    "boundary": {"type": "none", "attributeKey": None},
    "srlgRule": {"mode": "none", "srlgEdgeType": None},
}


def _core_ip_graph(domain: str = "core-ip") -> FakeGraph:
    """A small Port -HOSTS- Interface -TERMINATES- IPLink chain in one area."""
    g = FakeGraph(domain=domain)
    g.add_node("Port:P1", "Port", igp_area="area-0")
    g.add_node("Interface:P1.100", "Interface", igp_area="area-0")
    g.add_node("IPLink:L1", "IPLink")
    g.add_edge("Port:P1", "Interface:P1.100", "HOSTS")
    g.add_edge("Interface:P1.100", "IPLink:L1", "TERMINATES")
    return g


def _service(
    settings,
    engine,
    producer,
    topology_client: TopologyClient,
    policy_client: KnowledgePolicyClient,
) -> BuildService:
    repo = TrailRepository(engine, settings.trail_retention_snapshots)
    publisher = TrailsBuiltPublisher(producer, settings.trails_built_topic, settings.service_name)
    return BuildService(settings, topology_client, policy_client, repo, TrailClosure(), publisher)


def test_build_persists_trails_and_returns_result(settings, engine, producer) -> None:
    """AC-6: a build computes + persists trails and returns a populated BuildResult."""
    graph = _core_ip_graph()
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
        policy = KnowledgePolicyClient(
            settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0)
        )
        result = _service(settings, engine, producer, topo, policy).build(
            "snap-1", "core-ip", "trace-1"
        )
    assert isinstance(result, BuildResult)
    assert result.snapshot_id == "snap-1"
    assert result.domain == "core-ip"
    assert result.trail_count == len(result.trail_ids)
    assert result.trail_ids, "expected at least one trail from the chain"
    # Persisted: the trail is readable back from the repository.
    repo = TrailRepository(engine, settings.trail_retention_snapshots)
    assert repo.list_trails("snap-1", "core-ip")


def test_build_emits_trails_built_with_event_domain(settings, engine, producer) -> None:
    """AC-6 + AC-20: emit produces trails.built carrying the supplied domain verbatim."""
    graph = _core_ip_graph()
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
        policy = KnowledgePolicyClient(
            settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0)
        )
        _service(settings, engine, producer, topo, policy).build("snap-1", "core-ip", "trace-1")
    from acp_event_model import deserialize

    msgs = producer.for_topic("trails.built")
    assert len(msgs) == 1
    payload = deserialize(msgs[0]).payload
    assert payload.domain == "core-ip"
    assert payload.snapshotId == "snap-1"
    assert payload.trailCount == len(payload.trailIds)


def test_build_with_emit_false_does_not_publish(settings, engine, producer) -> None:
    """emit=False (used internally) persists but produces no trails.built event."""
    graph = _core_ip_graph()
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
        policy = KnowledgePolicyClient(
            settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0)
        )
        _service(settings, engine, producer, topo, policy).build(
            "snap-1", "core-ip", "trace-1", emit=False
        )
    assert producer.for_topic("trails.built") == []


def test_policy_fetched_per_domain(settings, engine, producer) -> None:
    """AC-9 + AC-11: two domains in sequence -> two parameterized policy fetches."""
    core_graph = _core_ip_graph("core-ip")
    metro_graph = _core_ip_graph("metro")
    policy_calls: list[str] = []
    with (
        install_topology_stub(TOPO_BASE, core_graph),
        install_knowledge_stub(
            KNOW_BASE, {"core-ip": DEFAULT_POLICY, "metro": METRO_POLICY}, call_log=policy_calls
        ),
    ):
        topo_core = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
        policy = KnowledgePolicyClient(
            settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0)
        )
        svc = _service(settings, engine, producer, topo_core, policy)
        svc.build("snap-1", "core-ip", "trace-1")
        # Re-point the topology stub to the metro graph for the second build.
        with install_topology_stub(TOPO_BASE, metro_graph):
            topo_metro = TopologyClient(
                settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0)
            )
            svc_metro = _service(settings, engine, producer, topo_metro, policy)
            result_metro = svc_metro.build("snap-1", "metro", "trace-2")
    assert sorted(policy_calls) == ["core-ip", "metro"]
    # AC-11: the metro build carries the metro domain on its trails.
    assert result_metro.domain == "metro"


def test_non_core_ip_domain_carries_correct_domain(settings, engine, producer) -> None:
    """AC-11: a non-Core-IP domain builds with its own policy and carries its domain."""
    graph = _core_ip_graph("metro")
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"metro": METRO_POLICY}),
    ):
        topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
        policy = KnowledgePolicyClient(
            settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0)
        )
        result = _service(settings, engine, producer, topo, policy).build(
            "snap-1", "metro", "trace-1"
        )
    repo = TrailRepository(engine, settings.trail_retention_snapshots)
    for trail_id in result.trail_ids:
        detail = repo.get_trail(trail_id)
        assert detail is not None
        assert detail.domain == "metro"


def test_new_snapshot_leaves_prior_records_intact(settings, engine, producer) -> None:
    """AC-15: rebuilding for a new snapshotId keeps prior snapshot trail records."""
    graph = _core_ip_graph()
    with (
        install_topology_stub(TOPO_BASE, graph),
        install_knowledge_stub(KNOW_BASE, {"core-ip": DEFAULT_POLICY}),
    ):
        topo = TopologyClient(settings, client=httpx.Client(base_url=TOPO_BASE, timeout=5.0))
        policy = KnowledgePolicyClient(
            settings, client=httpx.Client(base_url=KNOW_BASE, timeout=5.0)
        )
        svc = _service(settings, engine, producer, topo, policy)
        svc.build("snap-1", "core-ip", "trace-1")
        svc.build("snap-2", "core-ip", "trace-2")
    repo = TrailRepository(engine, settings.trail_retention_snapshots)
    assert repo.list_trails("snap-1", "core-ip"), "prior snapshot trails must survive"
    assert repo.list_trails("snap-2", "core-ip")


def test_integration_error_is_reraised_to_hold_build(settings, engine, producer) -> None:
    """A Topology/Knowledge IntegrationError propagates so the caller holds the event."""

    class _FailingPolicy:
        def get_policy(self, domain: str) -> TrailPolicy:
            raise IntegrationError("knowledge", "down")

    class _UnusedTopology:
        def fetch_slice(self, **_: object) -> GraphSlice:  # pragma: no cover - never reached
            raise AssertionError("topology should not be called when policy fails")

    repo = TrailRepository(engine, settings.trail_retention_snapshots)
    publisher = TrailsBuiltPublisher(producer, settings.trails_built_topic, settings.service_name)
    svc = BuildService(
        settings,
        _UnusedTopology(),  # type: ignore[arg-type]
        _FailingPolicy(),  # type: ignore[arg-type]
        repo,
        TrailClosure(),
        publisher,
    )
    with pytest.raises(IntegrationError) as ei:
        svc.build("snap-1", "core-ip", "trace-1")
    assert ei.value.reason == "knowledge"
    # Nothing emitted on the held path.
    assert producer.for_topic("trails.built") == []


def test_topology_failure_holds_and_does_not_emit(settings, engine, producer) -> None:
    """A Topology fetch failure also holds (re-raises) and emits nothing."""

    class _OkPolicy:
        def get_policy(self, domain: str) -> TrailPolicy:
            return TrailPolicy(
                closure_edge_types=("HOSTS",),
                boundary=Boundary(type="none"),
                srlg_rule=SrlgRule(mode="none"),
            )

    class _FailingTopology:
        def fetch_slice(self, **_: object) -> GraphSlice:
            raise IntegrationError("topology", "unreachable")

    repo = TrailRepository(engine, settings.trail_retention_snapshots)
    publisher = TrailsBuiltPublisher(producer, settings.trails_built_topic, settings.service_name)
    svc = BuildService(
        settings,
        _FailingTopology(),  # type: ignore[arg-type]
        _OkPolicy(),  # type: ignore[arg-type]
        repo,
        TrailClosure(),
        publisher,
    )
    with pytest.raises(IntegrationError):
        svc.build("snap-1", "core-ip", "trace-1")
    assert producer.for_topic("trails.built") == []


def test_build_result_trail_count_property() -> None:
    """BuildResult.trail_count mirrors len(trail_ids)."""
    r = BuildResult(snapshot_id="s", domain="d", trail_ids=["a", "b"])
    assert r.trail_count == 2
