"""Closure-algorithm acceptance tests (AC-1, AC-2, AC-3, AC-19).

These exercise ``TrailClosure`` directly on small fixture graph slices that
**inject** ``igpArea`` on their nodes (per the design: the unit fixtures inject
the attribute; the area-bound on *real* data is the role of the integration
assertion INT-IGPAREA, not these unit tests).
"""

from __future__ import annotations

import pytest

from fixtures import realistic_coreip_slice
from trailbuilder.closure import TrailClosure
from trailbuilder.models import Boundary, GraphEdge, GraphNode, GraphSlice, SrlgRule, TrailPolicy

POLICY = TrailPolicy(
    closure_edge_types=("HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER", "TRAVERSES"),
    boundary=Boundary(type="igp-area", attribute_key="igpArea"),
    srlg_rule=SrlgRule(mode="union-members", srlg_edge_type="MEMBER_OF"),
)

# Policy matching the realistic Simulator-shaped fixture (adds CONTAINS for the
# Node -> LineCard edge; everything else is the same dependency-edge set).
REALISTIC_POLICY = TrailPolicy(
    closure_edge_types=(
        "CONTAINS",
        "HOSTS",
        "TERMINATES",
        "RIDES_ON",
        "ADJACENCY_OVER",
        "TRAVERSES",
    ),
    boundary=Boundary(type="igp-area", attribute_key="igpArea"),
    srlg_rule=SrlgRule(mode="union-members", srlg_edge_type="MEMBER_OF"),
)


def _connected_component_size(slice_: GraphSlice, policy: TrailPolicy) -> int:
    """Size of the largest connected component over the closure edge view.

    This is the whole-network membership the buggy closure collapses to; a correct
    area-bounded build must produce every trail strictly smaller than this.
    """
    import networkx as nx

    g = nx.Graph()
    g.add_nodes_from(slice_.nodes)
    closure = set(policy.closure_edge_types)
    for e in slice_.edges:
        if e.relation in closure and e.src in slice_.nodes and e.dst in slice_.nodes:
            g.add_edge(e.src, e.dst)
    return max((len(c) for c in nx.connected_components(g)), default=0)


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


def test_area_less_mesh_does_not_fuse_areas() -> None:
    """AC-2 (#225 reproduction): a realistic full topology whose areas are joined

    ONLY through the area-less IPLink/SRLG/FiberSpan/LSP connector mesh must yield
    multiple area-bounded trails and NO whole-network trail.

    This is the unit-level reproduction of the round-8 gate failure. It FAILS on
    the old single-``seed_area`` closure (which fuses the whole graph into one
    181-member trail) and PASSES only after the area-component fix.
    """
    s = realistic_coreip_slice(node_count=9, area_count=3)
    component_size = _connected_component_size(s, REALISTIC_POLICY)

    trails = TrailClosure().compute(s, REALISTIC_POLICY)
    assert trails, "expected at least one trail"

    # (a) No trail spans two IGP areas.
    for t in trails:
        areas = {s.nodes[m].igp_area("igpArea") for m in t.members if m in s.nodes}
        areas.discard(None)
        assert len(areas) <= 1, f"trail {t.trail_id} spans areas {areas}"

    # (b) No whole-network trail: the largest trail is strictly smaller than the
    #     whole connected dependency component (the exact round-8 catch).
    largest = max(len(t.members) for t in trails)
    assert largest < component_size, (
        f"whole-network trail detected: largest trail {largest} "
        f">= connected component {component_size}"
    )

    # (c) The bound actually produced per-area trails (more than one area present).
    distinct_areas = {t.igp_area for t in trails if t.igp_area is not None}
    assert len(distinct_areas) >= 2, f"expected multiple area-bounded trails, got {distinct_areas}"


@pytest.mark.parametrize("seed_type", ["FiberSpan", "IPLink"])
def test_area_less_seed_yields_per_area_trails_not_whole_network(seed_type: str) -> None:
    """An area-less seed (FiberSpan / IPLink) yields one bounded set per area it

    touches — never one whole-network set. Directly covers root-cause defect
    (c)(1): the old closure skipped pruning entirely for an area-less seed, so a
    FiberSpan/IPLink seed walked the whole connected component (the 181-member fuse).

    Asserted on the closure produced *from that specific area-less seed* (the
    deterministic ``trail_id`` is content-derived, so after dedup an identical
    member set may be attributed to a different representative seed — what matters
    is that each area-less seed's own bounded set is single-area and sub-component).
    """
    s = realistic_coreip_slice(node_count=9, area_count=3)
    component_size = _connected_component_size(s, REALISTIC_POLICY)
    closure = TrailClosure()
    graph = closure._build_graph(s, REALISTIC_POLICY)

    seeds = [n for n in graph.nodes if n.startswith(f"{seed_type}:")]
    assert seeds, f"expected at least one {seed_type} seed object in the fixture"

    connector_areas = closure._connector_areas(graph, "igpArea")
    for seed in seeds:
        bounded = closure._bounded_closures(graph, seed, "igpArea", connector_areas)
        # Each area-less seed produces >= 1 bounded set, every one single-area and
        # strictly smaller than the whole connected component (never whole-network).
        assert bounded, f"{seed} produced no bounded set"
        for members, area in bounded:
            assert area is not None, f"{seed} bounded set has no resolved area"
            member_areas = {s.nodes[m].igp_area("igpArea") for m in members if m in s.nodes}
            member_areas.discard(None)
            assert member_areas <= {area}, f"{seed} set spans areas {member_areas}, tagged {area}"
            assert len(members) < component_size, (
                f"{seed_type} seed {seed} produced a whole-network set: "
                f"{len(members)} >= {component_size}"
            )

    # And end-to-end: the built trail set is multiple, area-bounded, none whole-network.
    trails = closure.compute(s, REALISTIC_POLICY)
    assert len(trails) >= 2
    assert all(len(t.members) < component_size for t in trails)


def test_single_area_connector_not_replicated_into_foreign_area_trails() -> None:
    """#234 regression: a single-area area-less connector must NOT be replicated

    into other areas' trails across the network-wide area-less transport mesh.

    The fixture uses the ``block`` layout (contiguous per-area node blocks, so
    consecutive nodes share an area and the connector between them is single-area)
    PLUS ``shared_transport`` — one area-less ``FiberSpan:F-MESH`` riding EVERY
    IPLink. That mesh is a genuine area-less-to-area-less chain in the closure-edge
    view, so on the PR-merged #225 closure ANY area's seed walks the entire
    connector inventory network-wide: a single-area connector such as
    ``FiberSpan:F-N0_N1`` / ``IPLink:N0_N1`` (both endpoints in area-0) leaks
    WHOLESALE into the area-1 and area-2 trails (the round-10 120-130-member
    bloat). After the connector-mesh-area-scope fix a single-area connector rides
    only its own area, so it appears only in that area's trails.

    Pre-fix this assertion FAILS (the area-0-only span shows up as a member of
    area-1 / area-2 trails); post-fix it PASSES.
    """
    s = realistic_coreip_slice(
        node_count=9, area_count=3, area_layout="block", shared_transport=True
    )
    component_size = _connected_component_size(s, REALISTIC_POLICY)
    trails = TrailClosure().compute(s, REALISTIC_POLICY)
    assert trails, "expected at least one trail"

    # area-0-only transport connectors: FiberSpans whose ONLY route to an
    # area-bearing object is through area-0 nodes (every node in the N0/N1/N2 block
    # is area-0, so F-N0_N1 / F-N1_N2 ride purely area-0). FiberSpans are NOT SRLG
    # co-members (only IPLinks are MEMBER_OF an SRLG), so the only way they could
    # reach an area-1 / area-2 trail is the network-wide area-less transport mesh —
    # i.e. exactly the #234 wholesale-replication leak, with no legitimate
    # fate-sharing route to confound the assertion.
    area0_only_connectors = {"FiberSpan:F-N0_N1", "FiberSpan:F-N1_N2"}
    for mo in area0_only_connectors:
        assert mo in s.nodes, f"fixture must contain {mo}"

    # Pre-fix (PR-merged #225) these area-0-only FiberSpans appear in the area-1 AND
    # area-2 trails (reached across the F-MESH transport conduit); post-fix they ride
    # only their own area-0.
    foreign_leaks = [
        (t.trail_id, t.igp_area, mo)
        for t in trails
        if t.igp_area not in (None, "area-0")
        for mo in area0_only_connectors
        if mo in t.members
    ]
    assert not foreign_leaks, (
        "single-area (area-0) connector replicated into a foreign-area trail "
        f"(the #234 mesh leak): {foreign_leaks}"
    )

    # Sanity: those area-0-only connectors DO still ride their own area-0 trails
    # (the fix scopes them, it does not drop them).
    area0_members = {m for t in trails if t.igp_area == "area-0" for m in t.members}
    assert area0_only_connectors <= area0_members, (
        "area-0-only connectors must still appear in area-0 trails; missing "
        f"{sorted(area0_only_connectors - area0_members)}"
    )

    # And the bulk effect: with the network-wide mesh present, the pre-fix closure
    # replicated the WHOLE area-less connector inventory into every area trail, so
    # the largest trail approached the whole connected component. After per-area
    # scoping no trail carries the foreign-area connector mesh, so the largest trail
    # is materially smaller than the connected component.
    largest = max(len(t.members) for t in trails)
    assert largest < component_size * 0.65, (
        f"connector-mesh bloat: largest trail {largest} is not materially smaller "
        f"than the connected component {component_size} (#234 wholesale replication)"
    )


def test_no_degenerate_srlg_only_single_member_trail() -> None:
    """M1: a pure risk-group (SRLG) node is never a standalone 1-member trail.

    An ``SRLG`` is an area-less fate-sharing GROUP node with no closure-edge
    neighbours (its only edges are ``MEMBER_OF``, not in the dependency-edge set).
    Seeding the closure from it yields a useless ``('SRLG:*',)`` 1-member trail
    that survives dedup — the 7-9 degenerate ``SRLG:*``-only trails the round-8
    gate produced. This asserts there are NONE, while SRLG groups still co-appear
    as MEMBERS of real multi-member area-bounded trails via the SRLG union.

    FAILS on the PR-#230 closure (which seeds every node, incl. SRLG); PASSES once
    risk-group object types are excluded from the seed loop.
    """
    s = realistic_coreip_slice(node_count=9, area_count=3)
    trails = TrailClosure().compute(s, REALISTIC_POLICY)

    # (a) No standalone 1-member SRLG-only trail.
    degenerate = [
        t for t in trails if t.member_count == 1 and all(m.startswith("SRLG:") for m in t.members)
    ]
    assert (
        not degenerate
    ), f"degenerate SRLG-only 1-member trails: {[t.members for t in degenerate]}"

    # (b) SRLG groups still co-trail: each SRLG group node appears as a member of
    #     at least one real (multi-member, area-bounded) trail via the union.
    srlg_nodes = {mo for mo in s.nodes if mo.startswith("SRLG:")}
    assert srlg_nodes, "fixture must contain SRLG group nodes"
    srlg_members = {m for t in trails if t.member_count > 1 for m in t.members if m in srlg_nodes}
    assert srlg_members == srlg_nodes, (
        f"every SRLG group must co-appear inside a multi-member trail; "
        f"missing {sorted(srlg_nodes - srlg_members)}"
    )


def test_srlg_node_never_seeds_a_trivial_trail_focused() -> None:
    """M1 (focused): the SRLG group node co-trails its links but never seeds alone.

    A minimal two-link/one-group slice: both links are reachable from their own
    devices, and only the SRLG union co-trails them. The SRLG group node must end
    up as a MEMBER of the co-trailed trail(s) and must NOT appear as its own
    1-member trail.
    """
    s = _slice()
    _add(s, "IPLink:L1", "IPLink")
    _add(s, "IPLink:L2", "IPLink")
    _add(s, "SRLG:G1", "SRLG")
    _add(s, "Node:N1", "Node", igp_area="area-0")
    _add(s, "Node:N2", "Node", igp_area="area-0")
    s.add_edge(GraphEdge("Node:N1", "IPLink:L1", "TRAVERSES"))
    s.add_edge(GraphEdge("Node:N2", "IPLink:L2", "TRAVERSES"))
    s.add_edge(GraphEdge("IPLink:L1", "SRLG:G1", "MEMBER_OF"))
    s.add_edge(GraphEdge("IPLink:L2", "SRLG:G1", "MEMBER_OF"))

    trails = TrailClosure().compute(s, POLICY)
    assert not [
        t for t in trails if t.members == ("SRLG:G1",)
    ], "SRLG must not seed a 1-member trail"
    assert any(
        "SRLG:G1" in t.members and t.member_count > 1 for t in trails
    ), "SRLG group must still co-appear as a member of a real trail"


def test_unrecognized_boundary_type_falls_back_unbounded() -> None:
    """m3: an UNRECOGNIZED ``policy.boundary.type`` falls back to a safe unbounded

    whole-component closure — no crash. This is the Phase-B extension-point seam:
    only ``"igp-area"`` is implemented; any other boundary type (e.g. ``"srlg"`` or
    ``"service"``) must not raise and must close the whole connected component
    rather than dropping the build.
    """
    for boundary_type in ("srlg", "service", "unknown-future-strategy"):
        policy = TrailPolicy(
            closure_edge_types=("ADJACENCY_OVER",),
            boundary=Boundary(type=boundary_type, attribute_key="igpArea"),
            srlg_rule=SrlgRule(mode="none"),
        )
        s = _slice()
        _add(s, "Node:A", "Node", igp_area="area-0")
        _add(s, "Node:B", "Node", igp_area="area-1")
        s.add_edge(GraphEdge("Node:A", "Node:B", "ADJACENCY_OVER"))

        trails = TrailClosure().compute(s, policy)  # must not raise

        # Unbounded fallback: the whole connected component is one trail (areas
        # are NOT pruned because the boundary strategy is unrecognized), and the
        # trail carries no igpArea (the area bound did not fire).
        assert any(
            {"Node:A", "Node:B"} <= set(t.members) for t in trails
        ), f"boundary.type={boundary_type!r} must fall back to an unbounded whole-component trail"
        assert all(
            t.igp_area is None for t in trails
        ), f"boundary.type={boundary_type!r} is unrecognized; no area should be tagged"


def test_srlg_union_does_not_fuse_two_areas() -> None:
    """m1: an SRLG union across the area-less mesh does NOT fuse two areas'

    area-bearing nodes into one trail. Two devices in DIFFERENT IGP areas each
    TRAVERSE one of two links that share an SRLG group. The union must co-trail
    the (area-less) co-member links + the group node, but must NEVER drag a
    different area's area-bearing Node into the trail. The result is TWO
    area-bounded trails (one per area), each carrying both links + the group, and
    NO single trail containing both area-bearing devices.
    """
    s = _slice()
    _add(s, "IPLink:L1", "IPLink")  # area-less
    _add(s, "IPLink:L2", "IPLink")  # area-less
    _add(s, "SRLG:G1", "SRLG")  # area-less group
    _add(s, "Node:N1", "Node", igp_area="area-0")  # area-bearing
    _add(s, "Node:N2", "Node", igp_area="area-1")  # area-bearing, DIFFERENT area
    s.add_edge(GraphEdge("Node:N1", "IPLink:L1", "TRAVERSES"))
    s.add_edge(GraphEdge("Node:N2", "IPLink:L2", "TRAVERSES"))
    s.add_edge(GraphEdge("IPLink:L1", "SRLG:G1", "MEMBER_OF"))
    s.add_edge(GraphEdge("IPLink:L2", "SRLG:G1", "MEMBER_OF"))

    trails = TrailClosure().compute(s, POLICY)

    # No trail fuses the two areas' area-bearing nodes.
    assert not [
        t for t in trails if "Node:N1" in t.members and "Node:N2" in t.members
    ], "SRLG union must not fuse two areas' area-bearing nodes into one trail"

    # No trail spans two IGP areas (the global AC-2 invariant holds through the union).
    for t in trails:
        areas = {s.nodes[m].igp_area("igpArea") for m in t.members if m in s.nodes}
        areas.discard(None)
        assert len(areas) <= 1, f"trail {t.trail_id} spans areas {areas}"

    # The fate-shared links still co-trail (AC-3) — both links land together in
    # each area-bounded trail, alongside the group node.
    co_trailed = [t for t in trails if {"IPLink:L1", "IPLink:L2", "SRLG:G1"} <= set(t.members)]
    assert (
        len(co_trailed) >= 2
    ), "both SRLG-co-member links must co-trail in each area-bounded trail"
    assert {t.igp_area for t in co_trailed} == {
        "area-0",
        "area-1",
    }, "the shared SRLG links must appear in BOTH areas' trails (overlap), not one fused trail"


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
