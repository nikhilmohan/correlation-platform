"""IO-layer unit tests for ``TopologyClient`` against the frozen Topology query API.

The transport is mocked with ``respx`` (the unit-test backing for the
``TOPOLOGY_SERVICE_MODE=mock`` integration point) via the shared ``install_topology_stub``
fixture, which serves the frozen ``NodeListDto`` / ``NeighborsDto`` / ``TraversalDto``
shapes from an in-memory typed graph. Maps to:

- AC-12 config-switchable integration point exercised in mock mode end-to-end.
- AC-13 the base-URL comes from settings (no hard-coded URL).
- Closure assembly + domain/snapshot scoping of every call; IntegrationError on failures.
"""

from __future__ import annotations

import httpx
import pytest

from fixtures import FakeGraph, install_topology_stub
from trailbuilder.clients.errors import IntegrationError
from trailbuilder.clients.topology_client import TopologyClient

BASE = "http://topology.test"


def _client(settings) -> TopologyClient:
    # Bind an httpx client to the configured base URL so respx (mounted on that
    # base) intercepts every call — the same code path as real mode (AC-12).
    return TopologyClient(settings, client=httpx.Client(base_url=BASE, timeout=5.0))


def test_fetch_slice_assembles_nodes_and_edges(settings) -> None:
    """AC-12: in mock mode the client assembles the full graph slice via the frozen API."""
    graph = FakeGraph(domain="core-ip")
    graph.add_node("Node:A", "Node", igp_area="area-0")
    graph.add_node("Interface:A.1", "Interface", igp_area="area-0")
    graph.add_node("IPLink:L1", "IPLink")
    graph.add_edge("Node:A", "Interface:A.1", "HOSTS")
    graph.add_edge("Interface:A.1", "IPLink:L1", "TERMINATES")

    with install_topology_stub(BASE, graph):
        slice_ = _client(settings).fetch_slice(
            domain="core-ip",
            snapshot_scope="current",
            seed_object_types=["Node"],
            closure_relations=["HOSTS", "TERMINATES"],
            srlg_edge_type="MEMBER_OF",
        )

    assert slice_.domain == "core-ip"
    assert slice_.snapshot_id == "current"
    # The closure followed HOSTS then TERMINATES through the Interface to the IPLink.
    assert {"Node:A", "Interface:A.1", "IPLink:L1"} <= set(slice_.nodes)
    edge_keys = {(e.src, e.dst, e.relation) for e in slice_.edges}
    assert ("Node:A", "Interface:A.1", "HOSTS") in edge_keys
    assert ("Interface:A.1", "IPLink:L1", "TERMINATES") in edge_keys


def test_every_call_is_domain_and_snapshot_scoped(settings) -> None:
    """AC-13/scoping: every Topology call carries domain + the in-scope snapshotId."""
    graph = FakeGraph(domain="core-ip")
    graph.add_node("Node:A", "Node", igp_area="area-0")
    call_log: list[dict] = []

    with install_topology_stub(BASE, graph, call_log=call_log):
        _client(settings).fetch_slice(
            domain="core-ip",
            snapshot_scope="current",
            seed_object_types=["Node"],
            closure_relations=["HOSTS"],
            srlg_edge_type=None,
        )

    assert call_log, "expected at least one Topology call"
    for entry in call_log:
        params = entry["params"]
        assert params.get("domain") == "core-ip"
        assert params.get("snapshotId") == "current"


def test_traversal_maxdepth_flows_from_settings(settings) -> None:
    """The traversal `maxDepth` param is the configured `traversal_max_depth` (#214).

    No literal in the client — the value flows from config so the env-tunable
    default (12) is what hits Topology's `GET /topology/traversal`.
    """
    graph = FakeGraph(domain="core-ip")
    graph.add_node("Node:A", "Node", igp_area="area-0")
    tuned = settings.model_copy(update={"traversal_max_depth": 7})
    call_log: list[dict] = []

    with install_topology_stub(BASE, graph, call_log=call_log):
        TopologyClient(tuned, client=httpx.Client(base_url=BASE, timeout=5.0)).fetch_slice(
            domain="core-ip",
            snapshot_scope="current",
            seed_object_types=["Node"],
            closure_relations=["HOSTS"],
            srlg_edge_type=None,
        )

    traversals = [e for e in call_log if e["path"] == "/topology/traversal"]
    assert traversals, "expected at least one traversal call"
    for entry in traversals:
        assert entry["params"].get("maxDepth") == "7"


def test_base_url_comes_from_settings(settings) -> None:
    """AC-13: changing the base URL routes calls to the new address (no hard-coding)."""
    client = TopologyClient(settings)
    assert client._base == settings.topology_service_base_url.rstrip("/")  # noqa: SLF001


def test_get_raises_integration_error_on_http_error(settings) -> None:
    """A persistent transport error surfaces as a labelled IntegrationError (not a crash)."""
    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get(url__regex=r".*/topology/nodes(\?.*)?$").mock(
        return_value=httpx.Response(503, json={"error": "down"})
    )
    with router:
        with pytest.raises(IntegrationError) as ei:
            _client(settings).fetch_slice(
                domain="core-ip",
                snapshot_scope="current",
                seed_object_types=["Node"],
                closure_relations=["HOSTS"],
                srlg_edge_type=None,
            )
    assert ei.value.reason == "topology"


def test_get_raises_integration_error_on_non_object_body(settings) -> None:
    """A frozen-contract violation (non-object body) is rejected as an IntegrationError."""
    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get(url__regex=r".*/topology/nodes(\?.*)?$").mock(
        return_value=httpx.Response(200, json=[1, 2, 3])
    )
    with router:
        with pytest.raises(IntegrationError):
            _client(settings).fetch_slice(
                domain="core-ip",
                snapshot_scope="current",
                seed_object_types=["Node"],
                closure_relations=["HOSTS"],
                srlg_edge_type=None,
            )


def test_node_dto_missing_field_is_integration_error(settings) -> None:
    """A NodeDto missing a required field raises an IntegrationError (no silent drop)."""
    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get(url__regex=r".*/topology/nodes(\?.*)?$").mock(
        return_value=httpx.Response(
            200, json={"nodes": [{"objectType": "Node"}]}  # missing managedObjectId
        )
    )
    with router:
        with pytest.raises(IntegrationError):
            _client(settings).fetch_slice(
                domain="core-ip",
                snapshot_scope="current",
                seed_object_types=["Node"],
                closure_relations=["HOSTS"],
                srlg_edge_type=None,
            )


def test_ping_true_when_reachable(settings) -> None:
    """ping() reports reachability for /health (mocked transport)."""
    graph = FakeGraph(domain="core-ip")
    with install_topology_stub(BASE, graph):
        assert _client(settings).ping() is True


def test_ping_false_when_unreachable(settings) -> None:
    """ping() is best-effort: a transport error returns False, not an exception."""
    router = __import__("respx").mock(base_url=BASE, assert_all_called=False)
    router.get(url__regex=r".*/topology/snapshots/current(\?.*)?$").mock(
        side_effect=httpx.ConnectError("refused")
    )
    with router:
        assert _client(settings).ping() is False
