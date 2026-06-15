"""#240 — residual area-bounding tests on a FAITHFUL Simulator-topology replica.

These tests run the closure on ``simulator_coreip_slice`` — a 1:1 structural
replica of ``services/simulator/.../coreip/topology_model.py`` (role-based areas
with a scattered area-0 backbone, a shared area-less ``VPNService`` hub reachable
over ``SERVES``, and SRLG-bundled adjacent IPLinks). The earlier unit fixtures
under-modelled this shape, which is why the #234 fix passed unit tests while the
P1 live gate (snapshot SNAP-bf5a10aa) still produced (1) a 190-member NULL-area
whole-network trail and (2) area-less connectors leaking into foreign areas.

Every cross-area expectation is computed FROM the fixture (``genuine_*`` helpers),
never hard-coded — so the assertions track the genuine inter-area structure the
role-based area assignment produces rather than assuming a count.

PROVEN against the pre-fix closure (commit 9b6ea70): the two #240 tests below FAIL
pre-fix (NULL-area 170-member trail; foreign-area connector leaks) and PASS after
the connector-area-anchoring fix.
"""

from __future__ import annotations

from collections import defaultdict

import pytest

from fixtures import (
    SIMULATOR_CLOSURE_EDGE_TYPES,
    genuine_connector_areas,
    genuine_inter_area_iplinks,
    simulator_coreip_slice,
)
from trailbuilder.closure import TrailClosure
from trailbuilder.models import Boundary, GraphSlice, SrlgRule, TrailPolicy

_CONNECTOR_TYPES = ("FiberSpan", "IGPAdjacency", "IPLink", "LSP", "SRLG")

# The Simulator's Knowledge-authored Core-IP trail policy (igp-area boundary, SRLG
# union, full dependency-edge vocabulary). Nothing here is a hard-coded threshold:
# it is exactly the policy the Knowledge Service serves for this domain.
SIM_POLICY = TrailPolicy(
    closure_edge_types=SIMULATOR_CLOSURE_EDGE_TYPES,
    boundary=Boundary(type="igp-area", attribute_key="igpArea"),
    srlg_rule=SrlgRule(mode="union-members", srlg_edge_type="MEMBER_OF"),
)


def _connected_component_size(slice_: GraphSlice, policy: TrailPolicy) -> int:
    import networkx as nx

    g = nx.Graph()
    g.add_nodes_from(slice_.nodes)
    closure = set(policy.closure_edge_types)
    for e in slice_.edges:
        if e.relation in closure and e.src in slice_.nodes and e.dst in slice_.nodes:
            g.add_edge(e.src, e.dst)
    return max((len(c) for c in nx.connected_components(g)), default=0)


def _connector_trail_areas(trails: list, slice_: GraphSlice) -> dict[str, set[str | None]]:
    """For each area-less connector, the set of trail ``igp_area`` values it appears in."""
    appears: dict[str, set[str | None]] = defaultdict(set)
    for t in trails:
        for m in t.members:
            if m.split(":", 1)[0] in _CONNECTOR_TYPES and m in slice_.nodes:
                appears[m].add(t.igp_area)
    return appears


def test_sim_fixture_models_role_based_scattered_backbone() -> None:
    """Guard: the fixture genuinely reproduces the real topology's hard shape.

    If this guard ever weakens (no scattered area-0 backbone, no shared VPNService
    hub, no genuine inter-area links), the #240 tests below would pass vacuously —
    exactly how the under-modelled fixtures let the residual ship. This pins the
    structural properties that make the closure's area bound non-trivial.
    """
    s = simulator_coreip_slice(node_count=20, area_count=3)

    # (a) Multiple IGP areas, with area-0 (backbone) SCATTERED — i.e. area-0 nodes
    #     are not a contiguous block; they interleave with edge-area nodes.
    node_areas = {
        mo: n.igp_area("igpArea")
        for mo, n in s.nodes.items()
        if mo.startswith("Node:") and n.igp_area("igpArea") is not None
    }
    areas_present = set(node_areas.values())
    assert areas_present == {"area-0", "area-1", "area-2"}, areas_present
    area0_indices = sorted(
        int(mo.split(":N", 1)[1]) for mo, a in node_areas.items() if a == "area-0"
    )
    # Scattered: area-0 nodes are not a single contiguous run starting at 0.
    assert area0_indices != list(
        range(len(area0_indices))
    ), f"area-0 backbone is contiguous {area0_indices}; the real topology scatters it"

    # (b) The shared area-less VPNService hub exists and fans in many LSPs over SERVES.
    vpns = [mo for mo in s.nodes if mo.startswith("VPNService:")]
    assert vpns, "fixture must contain the shared VPNService hub"
    serves = [e for e in s.edges if e.relation == "SERVES" and e.dst in vpns]
    assert len(serves) >= len(vpns) + 1, "VPNService must be a shared fan-in hub (many LSPs)"

    # (c) GENUINE inter-area IPLinks exist (the role-based scatter creates them).
    genuine = genuine_inter_area_iplinks(s)
    assert (
        len(genuine) >= 5
    ), f"expected several genuine inter-area backbone links, got {len(genuine)}"


def test_sim_no_null_area_or_whole_network_trail() -> None:
    """#240 defect (1): NO trail has ``igp_area=None`` and none is whole-network.

    The P1 gate produced a 190-member ``igp_area=NULL`` trail containing the ENTIRE
    network, bridged across areas by the shared area-less ``VPNService`` hub over
    ``SERVES`` (an area-less seed with no resolved area fell into the old unbounded
    fallback and walked the whole connected component).

    Pre-fix (9b6ea70) this FAILS: a single NULL-area trail spans every object.
    Post-fix every trail is bounded to a non-null area and is far smaller than the
    whole connected dependency component.
    """
    s = simulator_coreip_slice(node_count=20, area_count=3)
    total = len(s.nodes)
    component = _connected_component_size(s, SIM_POLICY)

    trails = TrailClosure().compute(s, SIM_POLICY)
    assert trails, "expected trails"

    # (a) No NULL-area trail at all (the boundary is active → every trail is bounded).
    null_area = [t for t in trails if t.igp_area is None]
    assert not null_area, (
        "NULL-area trail(s) present (#240 hub-bridged whole-network trail): "
        f"{[(t.igp_area, len(t.members)) for t in null_area]}"
    )

    # (b) Every trail bounded to exactly one area on its area-bearing members.
    for t in trails:
        member_areas = {s.nodes[m].igp_area("igpArea") for m in t.members if m in s.nodes}
        member_areas.discard(None)
        assert len(member_areas) <= 1, f"trail {t.trail_id} spans areas {member_areas}"

    # (c) No whole-network trail: the largest trail is far below the connected
    #     component (it must not approach the whole graph).
    largest = max(len(t.members) for t in trails)
    assert (
        largest < component
    ), f"whole-network trail: largest {largest} >= connected component {component}"
    assert (
        largest < total * 0.5
    ), f"largest trail {largest} is not materially smaller than the {total}-object graph"


def test_sim_connectors_cross_area_only_when_genuinely_inter_area() -> None:
    """#240 defect (2): an area-less connector appears in >1 area's trails ONLY when

    it is GENUINELY inter-area (computed from the fixture), and never in a FOREIGN
    area it does not genuinely ride.

    The P1 gate showed FiberSpan 18/19, IGPAdjacency 18/19, IPLink 19/19, LSP 18/19,
    SRLG 9/9 appearing in >1 area — far more than the genuine inter-area structure,
    because the area-less connector mesh + the VPNService hub replicated single-area
    connectors network-wide. Some cross-area links ARE legitimate (role-based
    backbone links genuinely terminate area-0 + an edge area), so this asserts
    against the GENUINE count derived from the fixture — not against zero.

    Pre-fix (9b6ea70) this FAILS (connectors appear in foreign areas / the cross-area
    count vastly exceeds the genuine count). Post-fix every connector appears only in
    areas it genuinely rides, and the cross-area count equals the genuine count.
    """
    s = simulator_coreip_slice(node_count=20, area_count=3)
    trails = TrailClosure().compute(s, SIM_POLICY)

    genuine = genuine_connector_areas(s)
    appears = _connector_trail_areas(trails, s)

    # (a) NO connector appears in an area it does not genuinely ride. A ``None``
    #     (unbounded) trail-area counts as a FOREIGN appearance too: the boundary is
    #     active, so a correctly-bounded connector appears only in its genuine,
    #     non-null area(s) and NEVER in an unbounded trail. This is what makes the
    #     assertion bite on the pre-fix closure (which collapses every connector into
    #     the single NULL-area whole-network trail) rather than passing vacuously.
    leaks: list[tuple[str, set[str | None], set[str]]] = []
    for mo, seen in appears.items():
        allowed: set[str | None] = set(genuine.get(mo, set()))
        foreign = seen - allowed
        if foreign:
            leaks.append((mo, foreign, genuine.get(mo, set())))
    assert not leaks, (
        "area-less connector(s) appeared in a FOREIGN or unbounded (None) area "
        f"(#240 mesh/hub leak): {sorted((mo, sorted(str(x) for x in f)) for mo, f, _ in leaks)}"
    )

    # (b) The number of connectors appearing in >1 area equals the number that are
    #     GENUINELY inter-area — computed from the fixture, NOT hard-coded to 0.
    cross_area_observed = {
        mo for mo, seen in appears.items() if len({a for a in seen if a is not None}) > 1
    }
    genuine_multi = {mo for mo, areas in genuine.items() if len(areas) > 1 and mo in appears}
    assert cross_area_observed == genuine_multi, (
        "cross-area connector set must equal the genuine inter-area set computed from "
        f"the fixture; observed-only={sorted(cross_area_observed - genuine_multi)}, "
        f"genuine-only={sorted(genuine_multi - cross_area_observed)}"
    )
    # And the genuine inter-area set is non-trivial (role-based backbone creates it).
    assert (
        len(genuine_multi) >= 5
    ), f"expected several genuine inter-area connectors, got {len(genuine_multi)}"


def test_sim_vpnservice_hub_never_seeds_whole_network_trail() -> None:
    """#240 root cause: the shared area-less ``VPNService`` hub must neither seed nor

    bridge a whole-network trail. The hub is reachable only across a
    ``SERVES``/``LSP`` fan-out and terminates no area-bearing endpoint directly, so
    it genuinely rides NO single area on its own. Under an active boundary it must:

      * anchor to NO area (``connector_areas[hub] == set()``) — so it can never be
        used as a propagation conduit that bridges one area's connectors into
        another (the mechanism behind the #240 cross-area replication); and
      * seed NO standalone bounded set — so it can never produce the unbounded
        whole-component walk that became the 190-member NULL-area trail at the gate.

    Because a genuinely-cross-area shared hub belongs to no single area, it is
    correctly pruned from every single-area trail (the same way a genuine cross-area
    area-bearing edge is pruned). What matters for #240 is that it never bridges or
    seeds a whole-network trail — asserted below.
    """
    s = simulator_coreip_slice(node_count=20, area_count=3)
    closure = TrailClosure()
    graph = closure._build_graph(s, SIM_POLICY)
    connector_areas = closure._connector_areas(graph, "igpArea")

    vpns = [mo for mo in graph.nodes if mo.startswith("VPNService:")]
    assert vpns, "fixture must contain the VPNService hub"
    for vpn in vpns:
        # The hub anchors to NO area directly (its only edges are SERVES from LSPs),
        # so it is never relayed through — it cannot bridge areas.
        assert (
            connector_areas.get(vpn, set()) == set()
        ), f"{vpn} should anchor to no area (pure hub), got {connector_areas.get(vpn)}"
        # Seeding from it yields NO bounded set (no standalone trail), never an
        # unbounded whole-component walk.
        bounded = closure._bounded_closures(graph, vpn, "igpArea", connector_areas)
        assert bounded == [], f"{vpn} must seed no standalone trail, got {len(bounded)} set(s)"

    # End-to-end: no whole-network / NULL-area trail forms (the hub did not bridge).
    trails = closure.compute(s, SIM_POLICY)
    assert not [t for t in trails if t.igp_area is None], "hub bridged a NULL-area trail"
    component = sum(1 for _ in graph.nodes)
    assert max(len(t.members) for t in trails) < component, "hub seeded a whole-network trail"


def test_sim_genuine_inter_area_link_rides_both_its_areas() -> None:
    """A GENUINE inter-area backbone link rides BOTH its terminating areas (overlap

    is preserved — the fix is not over-pruning). For each genuinely inter-area
    IPLink the union of trail areas it appears in must equal its two termination
    areas exactly.
    """
    s = simulator_coreip_slice(node_count=20, area_count=3)
    trails = TrailClosure().compute(s, SIM_POLICY)
    appears = _connector_trail_areas(trails, s)
    genuine = genuine_connector_areas(s)

    genuine_links = genuine_inter_area_iplinks(s)
    assert genuine_links, "fixture must contain genuine inter-area links"
    for link in genuine_links:
        seen = {a for a in appears.get(link, set()) if a is not None}
        # The link's own two termination areas must both be represented.
        term_areas = genuine[link]
        assert (
            term_areas <= seen
        ), f"{link} genuinely spans {sorted(term_areas)} but only appears in {sorted(seen)}"


@pytest.mark.parametrize("node_count", [10, 20, 30])
def test_sim_area_bound_holds_across_sizes(node_count: int) -> None:
    """The area bound holds across topology sizes (no NULL-area trail, no whole-network

    trail, no foreign-area connector leak) — guarding against a size-specific fluke.
    """
    s = simulator_coreip_slice(node_count=node_count, area_count=3)
    component = _connected_component_size(s, SIM_POLICY)
    trails = TrailClosure().compute(s, SIM_POLICY)
    assert trails

    assert not [t for t in trails if t.igp_area is None], "NULL-area trail present"
    assert max(len(t.members) for t in trails) < component, "whole-network trail present"

    genuine = genuine_connector_areas(s)
    appears = _connector_trail_areas(trails, s)
    for mo, seen in appears.items():
        foreign = {a for a in seen if a is not None} - genuine.get(mo, set())
        assert not foreign, f"{mo} leaked into foreign area(s) {sorted(foreign)}"
