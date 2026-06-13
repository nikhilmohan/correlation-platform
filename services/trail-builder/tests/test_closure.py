"""Closure-algorithm acceptance tests (AC-1, AC-2, AC-3, AC-19).

These exercise ``TrailClosure`` directly on small fixture graph slices that
**inject** ``igpArea`` on their nodes (per the design: the unit fixtures inject
the attribute; the area-bound on *real* data is the role of the integration
assertion INT-IGPAREA, not these unit tests).
"""

from __future__ import annotations

from trailbuilder.closure import TrailClosure
from trailbuilder.models import Boundary, GraphEdge, GraphNode, GraphSlice, SrlgRule, TrailPolicy

POLICY = TrailPolicy(
    closure_edge_types=("HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER", "TRAVERSES"),
    boundary=Boundary(type="igp-area", attribute_key="igpArea"),
    srlg_rule=SrlgRule(mode="union-members", srlg_edge_type="MEMBER_OF"),
)


def _slice(domain: str = "core-ip", snapshot_id: str = "snap-1") -> GraphSlice:
    return GraphSlice(domain=domain, snapshot_id=snapshot_id)


def _add(slice_: GraphSlice, mo_id: str, object_type: str, igp_area: str | None = None) -> None:
    attrs: dict[str, object] = {}
    if igp_area is not None:
        attrs["igpArea"] = igp_area
    slice_.add_node(GraphNode(managed_object_id=mo_id, object_type=object_type, attributes=attrs))


def test_object_on_two_lsps_one_srlg_yields_three_trails() -> None:
    """AC-1: an object on two LSP paths + one SRLG group is in >= 3 trails.

    The shared object ``X`` participates in three independent structures whose
    area-bounded reachable sets stay distinct (the area prune keeps them from
    collapsing into one connected component): LSP path A in area-0, LSP path B in
    area-1, and an SRLG group bridging two links in area-2. ``X`` is therefore a
    member of three distinct trails — real overlap, not a degenerate single trail.
    """
    s = _slice()
    _add(s, "Node:X", "Node", igp_area=None)  # area-less; rides every layer

    # LSP path A — area-0 (A1 and A2 reachable from X, pruned from B/C by area).
    _add(s, "Node:A1", "Node", igp_area="area-0")
    _add(s, "Node:A2", "Node", igp_area="area-0")
    s.add_edge(GraphEdge("Node:A1", "Node:X", "RIDES_ON"))
    s.add_edge(GraphEdge("Node:X", "Node:A2", "RIDES_ON"))

    # LSP path B — area-1.
    _add(s, "Node:B1", "Node", igp_area="area-1")
    _add(s, "Node:B2", "Node", igp_area="area-1")
    s.add_edge(GraphEdge("Node:B1", "Node:X", "RIDES_ON"))
    s.add_edge(GraphEdge("Node:X", "Node:B2", "RIDES_ON"))

    # SRLG group — two links in area-2 that X traverses, fate-shared.
    _add(s, "Node:C1", "Node", igp_area="area-2")
    _add(s, "IPLink:LC1", "IPLink")
    _add(s, "IPLink:LC2", "IPLink")
    _add(s, "SRLG:G1", "SRLG")
    s.add_edge(GraphEdge("Node:C1", "Node:X", "RIDES_ON"))
    s.add_edge(GraphEdge("Node:C1", "IPLink:LC1", "TRAVERSES"))
    s.add_edge(GraphEdge("Node:C1", "IPLink:LC2", "TRAVERSES"))
    s.add_edge(GraphEdge("IPLink:LC1", "SRLG:G1", "MEMBER_OF"))
    s.add_edge(GraphEdge("IPLink:LC2", "SRLG:G1", "MEMBER_OF"))

    trails = TrailClosure().compute(s, POLICY)
    containing = {t.trail_id for t in trails if "Node:X" in t.members}
    assert len(containing) >= 3, f"expected X in >=3 trails, got {len(containing)}"


def test_no_trail_spans_two_igp_areas() -> None:
    """AC-2: no single trail contains members from two IGP areas (bounded)."""
    s = _slice()
    # area-0 cluster <-> area-1 cluster joined by one edge; bound must split them.
    _add(s, "Node:A", "Node", igp_area="area-0")
    _add(s, "Node:B", "Node", igp_area="area-0")
    _add(s, "Node:C", "Node", igp_area="area-1")
    _add(s, "Node:D", "Node", igp_area="area-1")
    s.add_edge(GraphEdge("Node:A", "Node:B", "ADJACENCY_OVER"))
    s.add_edge(GraphEdge("Node:B", "Node:C", "ADJACENCY_OVER"))  # cross-area edge
    s.add_edge(GraphEdge("Node:C", "Node:D", "ADJACENCY_OVER"))

    trails = TrailClosure().compute(s, POLICY)
    assert trails, "expected at least one trail"
    all_members = {m for t in trails for m in t.members}
    for t in trails:
        areas = {s.nodes[m].igp_area("igpArea") for m in t.members}
        areas.discard(None)
        assert len(areas) <= 1, f"trail {t.trail_id} spans areas {areas}"
    # No whole-network trail: no single trail equals every node.
    assert all(len(t.members) < len(all_members) for t in trails)


def test_two_links_sharing_srlg_in_same_trail() -> None:
    """AC-3: two IP links in one SRLG group end up in the same trail."""
    s = _slice()
    _add(s, "IPLink:L1", "IPLink")
    _add(s, "IPLink:L2", "IPLink")
    _add(s, "SRLG:G1", "SRLG")
    # Each link reachable from its own seed device, distinct areas — only the SRLG
    # union should bring them together.
    _add(s, "Node:N1", "Node", igp_area="area-0")
    _add(s, "Node:N2", "Node", igp_area="area-1")
    s.add_edge(GraphEdge("Node:N1", "IPLink:L1", "TRAVERSES"))
    s.add_edge(GraphEdge("Node:N2", "IPLink:L2", "TRAVERSES"))
    s.add_edge(GraphEdge("IPLink:L1", "SRLG:G1", "MEMBER_OF"))
    s.add_edge(GraphEdge("IPLink:L2", "SRLG:G1", "MEMBER_OF"))

    trails = TrailClosure().compute(s, POLICY)
    co_trailed = [t for t in trails if "IPLink:L1" in t.members and "IPLink:L2" in t.members]
    assert co_trailed, "expected both SRLG-co-member links in one trail"


def test_trail_includes_interface_between_port_and_iplink() -> None:
    """AC-19: closure traverses Port -HOSTS- Interface -TERMINATES- IPLink."""
    s = _slice()
    _add(s, "Port:P1", "Port", igp_area="area-0")
    _add(s, "Interface:P1.100", "Interface", igp_area="area-0")
    _add(s, "IPLink:L1", "IPLink")
    s.add_edge(GraphEdge("Port:P1", "Interface:P1.100", "HOSTS"))
    s.add_edge(GraphEdge("Interface:P1.100", "IPLink:L1", "TERMINATES"))

    trails = TrailClosure().compute(s, POLICY)
    with_iface = [
        t
        for t in trails
        if "Interface:P1.100" in t.members and "Port:P1" in t.members and "IPLink:L1" in t.members
    ]
    assert with_iface, "expected a trail containing Port, Interface and IPLink together"
    assert any("Interface:" in m for t in with_iface for m in t.members)


def test_no_boundary_policy_does_not_prune() -> None:
    """A policy with no IGP-area boundary closes the whole connected component."""
    policy = TrailPolicy(
        closure_edge_types=("ADJACENCY_OVER",),
        boundary=Boundary(type="none"),
        srlg_rule=SrlgRule(mode="none"),
    )
    s = _slice()
    _add(s, "Node:A", "Node", igp_area="area-0")
    _add(s, "Node:B", "Node", igp_area="area-1")
    s.add_edge(GraphEdge("Node:A", "Node:B", "ADJACENCY_OVER"))
    trails = TrailClosure().compute(s, policy)
    assert any({"Node:A", "Node:B"} <= set(t.members) for t in trails)
