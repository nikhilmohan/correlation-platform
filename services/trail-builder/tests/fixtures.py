"""Test fixtures: graph-slice builders + respx stubs for the frozen producer APIs.

The Topology stub serves the frozen ``NodeListDto`` / ``NeighborsDto`` /
``TraversalDto`` shapes from an in-memory typed graph; the Knowledge stub serves
a per-domain ``trailPolicy`` ``RecordResponse``. Both are generated to match the
producers' published OpenAPI shapes (the unit-test mock backing).
"""

from __future__ import annotations

from dataclasses import dataclass, field

import httpx
import respx

from trailbuilder.models import GraphEdge, GraphNode, GraphSlice

DEFAULT_POLICY = {
    "closureEdgeTypes": [
        "HOSTED_ON",
        "HOSTS",
        "TERMINATES",
        "RIDES_ON",
        "ADJACENCY_OVER",
        "TRAVERSES",
        "SERVES",
    ],
    "boundary": {"type": "igp-area", "attributeKey": "igpArea"},
    "srlgRule": {"mode": "union-members", "srlgEdgeType": "MEMBER_OF"},
}


@dataclass
class FakeGraph:
    """An in-memory typed topology used to serve the frozen Topology DTOs."""

    domain: str = "core-ip"
    nodes: dict[str, dict] = field(default_factory=dict)
    edges: list[tuple[str, str, str]] = field(default_factory=list)

    def add_node(self, mo_id: str, object_type: str, igp_area: str | None = None, **attrs) -> None:
        attributes = dict(attrs)
        if igp_area is not None:
            attributes["igpArea"] = igp_area
        self.nodes[mo_id] = {
            "managedObjectId": mo_id,
            "objectType": object_type,
            "domain": self.domain,
            "snapshotId": "current",
            "attributes": attributes,
        }

    def add_edge(self, src: str, dst: str, relation: str) -> None:
        self.edges.append((src, dst, relation))

    # --- DTO assembly ---

    def node_list_dto(self, object_type: str) -> dict:
        nodes = [n for n in self.nodes.values() if n["objectType"] == object_type]
        return {
            "domain": self.domain,
            "objectType": object_type,
            "snapshotId": "current",
            "count": len(nodes),
            "nodes": nodes,
        }

    def neighbors_dto(self, mo_id: str, relations: list[str] | None) -> dict:
        rels = set(relations) if relations else None
        out = []
        for i, (src, dst, rel) in enumerate(self.edges):
            if rels is not None and rel not in rels:
                continue
            other = None
            if src == mo_id:
                other = dst
            elif dst == mo_id:
                other = src
            if other is None or other not in self.nodes:
                continue
            out.append(
                {
                    "node": self.nodes[other],
                    "via": {
                        "edgeId": f"e{i}",
                        "from": src,
                        "to": dst,
                        "relation": rel,
                        "domain": self.domain,
                        "attributes": {},
                        "snapshotId": "current",
                    },
                }
            )
        return {"managedObjectId": mo_id, "domain": self.domain, "neighbors": out}

    def traversal_dto(self, start: str, relations: list[str]) -> dict:
        return {
            "start": start,
            "domain": self.domain,
            "relations": relations,
            "maxDepth": 32,
            "crossDomain": False,
            "reached": list(self.nodes.values()),
        }


def install_topology_stub(
    base_url: str, graph: FakeGraph, call_log: list[dict] | None = None
) -> respx.MockRouter:
    """Install respx routes for the frozen Topology query API against ``graph``."""
    router = respx.mock(base_url=base_url, assert_all_called=False)

    def _nodes(request: httpx.Request) -> httpx.Response:
        if call_log is not None:
            call_log.append({"path": "/topology/nodes", "params": dict(request.url.params)})
        object_type = request.url.params.get("objectType", "")
        return httpx.Response(200, json=graph.node_list_dto(object_type))

    def _neighbors(request: httpx.Request, managedObjectId: str) -> httpx.Response:
        if call_log is not None:
            call_log.append(
                {"path": "/topology/nodes/{id}/neighbors", "params": dict(request.url.params)}
            )
        relations = request.url.params.get_list("relation") or None
        return httpx.Response(200, json=graph.neighbors_dto(managedObjectId, relations))

    def _traverse(request: httpx.Request) -> httpx.Response:
        if call_log is not None:
            call_log.append({"path": "/topology/traversal", "params": dict(request.url.params)})
        start = request.url.params.get("start", "")
        relations = request.url.params.get_list("relation")
        return httpx.Response(200, json=graph.traversal_dto(start, relations))

    router.get(url__regex=r".*/topology/nodes(\?.*)?$").mock(side_effect=_nodes)
    router.get(url__regex=r".*/topology/nodes/(?P<managedObjectId>[^/?]+)/neighbors(\?.*)?$").mock(
        side_effect=_neighbors
    )
    router.get(url__regex=r".*/topology/traversal(\?.*)?$").mock(side_effect=_traverse)
    router.get(url__regex=r".*/topology/snapshots/current(\?.*)?$").mock(
        return_value=httpx.Response(200, json={"snapshotId": "current"})
    )
    return router


def install_knowledge_stub(
    base_url: str, policies: dict[str, dict], call_log: list[str] | None = None
) -> respx.MockRouter:
    """Install respx routes for the Knowledge trailPolicy read API.

    ``policies`` maps domain -> trailPolicy payload dict. The route mirrors Knowledge's
    published RecordController contract: ``GET /domains/{domain}/trail-policies/{recordId}``
    where ``recordId`` is the seeded slash-bearing id ``core-ip/trailPolicy/default``,
    URL-encoded so the slashes survive as one path segment (the controller decodes once).
    """
    router = respx.mock(base_url=base_url, assert_all_called=False)

    def _policy(request: httpx.Request, domain: str) -> httpx.Response:
        if call_log is not None:
            call_log.append(domain)
        payload = policies.get(domain)
        if payload is None:
            return httpx.Response(404, json={"error": "not found"})
        return httpx.Response(
            200,
            json={
                "domain": domain,
                "recordType": "trailPolicy",
                "recordId": "core-ip/trailPolicy/default",
                "version": "1",
                "isCurrent": True,
                "payload": payload,
            },
        )

    # The recordId path segment is URL-encoded (slashes -> %2F), so it is a single,
    # slashless path segment captured here as ``[^/?]+``.
    router.get(url__regex=r".*/domains/(?P<domain>[^/?]+)/trail-policies/[^/?]+(\?.*)?$").mock(
        side_effect=_policy
    )
    return router


# ---------------------------------------------------------------------------
# Realistic full-topology graph-slice fixture (the #225 reproduction).
#
# Mirrors the Simulator's Core-IP shape: N Nodes spread across `area_count` IGP
# areas, each Node carrying a LineCard -> Port -> Interface stack; consecutive
# Nodes joined by an IPLink; and — crucially — areas are joined ONLY through an
# *area-less connector mesh* (FiberSpan + IGPAdjacency + LSP riding each link, and
# SRLG groups bundling adjacent IPLinks). NONE of those connectors carries
# `igpArea`. There is NO direct area-bearing-to-area-bearing cross-area edge:
# the only thing bridging two areas is the area-less mesh — exactly the real-data
# shape the old tiny fixtures never reproduced.
#
# On the old closure this fuses into ONE whole-network trail (an area-less seed
# gets no prune at all, and an area-bearing seed leaks across the area-less mesh).
# After the area-component fix it yields multiple area-bounded trails and no
# whole-network trail.
# ---------------------------------------------------------------------------


def _area_of(index: int, area_count: int, node_count: int, area_layout: str) -> str:
    """Map a node index to its IGP area under the chosen layout.

    * ``"round-robin"`` (default) — ``index % area_count``: consecutive nodes are
      always in *different* areas, so every inter-node connector is genuinely
      cross-area (the original #225 fixture shape).
    * ``"block"`` — contiguous blocks of nodes per area (the Simulator's real
      shape): consecutive nodes within a block share an area, so a connector
      between them is **single-area**, while the connector that straddles a block
      boundary is genuinely **cross-area**. This is the shape that exposes the
      connector-mesh-area-scope leak (#234): a single-area connector must NOT be
      replicated into the other areas' trails.
    """
    if area_layout == "block":
        block = max(1, -(-node_count // area_count))  # ceil division
        return f"area-{min(index // block, area_count - 1)}"
    return f"area-{index % area_count}"


def realistic_coreip_slice(
    node_count: int = 9,
    area_count: int = 3,
    domain: str = "core-ip",
    snapshot_id: str = "snap-real",
    area_layout: str = "round-robin",
    shared_transport: bool = False,
) -> GraphSlice:
    """Build a ``GraphSlice`` mirroring the Simulator's area-less-mesh topology.

    Areas are bridged ONLY through area-less IPLink/SRLG/FiberSpan/LSP/IGPAdjacency
    connectors — never through a direct area-bearing edge. Returns the slice the
    closure runs on directly (no Topology client involved).

    ``area_layout`` selects how node indices map to IGP areas — ``"round-robin"``
    (the original #225 shape, every inter-node connector cross-area) or ``"block"``
    (contiguous per-area blocks, so some connectors are single-area and some are
    cross-area — the shape that exercises the connector-mesh-area-scope fix #234).

    ``shared_transport`` adds the gate's **network-wide area-less mesh**: a single
    area-less transport object that rides EVERY IPLink (a shared
    conduit/transport-mesh), creating an area-less-to-area-less chain that makes
    every connector reachable from every area's closure. This reproduces the #234
    leak — on the PR-merged #225 closure it replicates single-area connectors
    (e.g. ``FiberSpan:F-N0_N1`` between two area-0 Nodes) WHOLESALE into every
    area trail; after the connector-mesh-area-scope fix a single-area connector
    rides only its own area.
    """
    s = GraphSlice(domain=domain, snapshot_id=snapshot_id)

    def _add(mo_id: str, object_type: str, igp_area: str | None = None) -> None:
        attrs: dict[str, object] = {}
        if igp_area is not None:
            attrs["igpArea"] = igp_area
        s.add_node(GraphNode(managed_object_id=mo_id, object_type=object_type, attributes=attrs))

    # Per-node area-bearing stack: Node -> LineCard -> Port -> Interface, all in
    # the node's area (the Simulator stamps igpArea on Node + Interface; LineCard
    # and Port are area-less hardware, but they sit *inside* one node's reach).
    for i in range(node_count):
        area = _area_of(i, area_count, node_count, area_layout)
        node = f"Node:N{i}"
        lc = f"LineCard:N{i}-LC0"
        port = f"Port:N{i}-LC0-P0"
        iface = f"Interface:N{i}-LC0-P0.0"
        _add(node, "Node", igp_area=area)
        _add(lc, "LineCard")  # area-less hardware
        _add(port, "Port")  # area-less hardware
        _add(iface, "Interface", igp_area=area)
        s.add_edge(GraphEdge(node, lc, "CONTAINS"))
        s.add_edge(GraphEdge(lc, port, "HOSTS"))
        s.add_edge(GraphEdge(port, iface, "HOSTS"))

    # The area-less connector mesh between consecutive nodes. Each adjacent pair
    # (N_i, N_{i+1}) is joined by an IPLink (area-less) terminated by both ends'
    # interfaces, a FiberSpan + IGPAdjacency + LSP riding the same link, and an
    # SRLG group bundling adjacent IPLinks into a fate-shared chain. Consecutive
    # nodes may be in DIFFERENT areas (i and i+1 differ mod area_count), so this
    # mesh is the *only* thing that could bridge areas.
    for i in range(node_count - 1):
        a, b = f"N{i}", f"N{i + 1}"
        iplink = f"IPLink:{a}_{b}"
        fiber = f"FiberSpan:F-{a}_{b}"
        adj = f"IGPAdjacency:ADJ-{a}_{b}"
        lsp = f"LSP:LSP-{a}_{b}"
        iface_a = f"Interface:{a}-LC0-P0.0"
        iface_b = f"Interface:{b}-LC0-P0.0"
        _add(iplink, "IPLink")  # area-less
        _add(fiber, "FiberSpan")  # area-less
        _add(adj, "IGPAdjacency")  # area-less
        _add(lsp, "LSP")  # area-less
        # Interfaces TERMINATE the IPLink (the area-bearing endpoints of the link).
        s.add_edge(GraphEdge(iface_a, iplink, "TERMINATES"))
        s.add_edge(GraphEdge(iface_b, iplink, "TERMINATES"))
        # The area-less layer objects ride the link.
        s.add_edge(GraphEdge(fiber, iplink, "RIDES_ON"))
        s.add_edge(GraphEdge(adj, iplink, "ADJACENCY_OVER"))
        s.add_edge(GraphEdge(lsp, iplink, "TRAVERSES"))

    # SRLG groups bundling adjacent IPLinks into a fate-shared chain — the
    # network-wide area-less bridge that fused everything in the gate (#225).
    for i in range(node_count - 2):
        srlg = f"SRLG:SRLG-{i}"
        _add(srlg, "SRLG")  # area-less
        link0 = f"IPLink:N{i}_N{i + 1}"
        link1 = f"IPLink:N{i + 1}_N{i + 2}"
        s.add_edge(GraphEdge(link0, srlg, "MEMBER_OF"))
        s.add_edge(GraphEdge(link1, srlg, "MEMBER_OF"))

    # Optional network-wide area-less transport mesh (the #234 gate shape): one
    # area-less transport object that RIDES_ON every IPLink. This is a genuine
    # area-less-to-area-less chain in the closure-edge view — IPLink_X <-RIDES_ON-
    # TransportMesh -RIDES_ON-> IPLink_Y — so from any area's seed the closure can
    # walk the ENTIRE connector mesh network-wide. On the PR-merged #225 closure
    # this admits every area-less connector into every area's trail (the bloat);
    # the connector-mesh-area-scope fix restricts admission to connectors that
    # genuinely ride within the area.
    if shared_transport:
        mesh = "FiberSpan:F-MESH"  # one shared area-less transport conduit
        _add(mesh, "FiberSpan")  # area-less, no igpArea
        for i in range(node_count - 1):
            s.add_edge(GraphEdge(mesh, f"IPLink:N{i}_N{i + 1}", "RIDES_ON"))

    return s


# ---------------------------------------------------------------------------
# FAITHFUL Simulator-topology fixture (the #240 reproduction).
#
# The earlier ``realistic_coreip_slice`` under-modelled the real Simulator in the
# two ways that let the #240 residual ship green: it used round-robin / block area
# assignment instead of the Simulator's ROLE-BASED scheme (scattered area-0
# backbone P/RR nodes, so many IPLinks GENUINELY terminate one area-0 + one
# edge-area endpoint), and it had no shared area-less ``VPNService`` hub reachable
# over ``SERVES`` (the bridge that produced the NULL-area whole-network trail).
#
# ``simulator_coreip_slice`` is a 1:1 structural replica of
# ``services/simulator/src/simulator/domains/coreip/topology_model.py`` — same
# role-based area assignment (``_area_for_role``), same HOSTED_ON/HOSTS/TERMINATES/
# RIDES_ON/ADJACENCY_OVER/TRAVERSES/SERVES/MEMBER_OF edge vocabulary, same shared
# ``VPNService:CUST-{i%5}`` hub, same SRLG bundling of adjacent IPLink pairs. It is
# NOT injected with a convenient area layout; the areas fall out of the roles
# exactly as on real Simulator-generated topology. This is the fixture the #240
# tests run on, so they fail pre-fix exactly as the live P1 gate did and pass after.
# ---------------------------------------------------------------------------

# The Simulator's domain trail policy (the Knowledge-authored default for Core IP):
# the full dependency-edge vocabulary the closure traverses, the igp-area boundary,
# and the SRLG union rule. Mirrors ``fixtures.DEFAULT_POLICY`` / the live default.
SIMULATOR_CLOSURE_EDGE_TYPES: tuple[str, ...] = (
    "HOSTED_ON",
    "HOSTS",
    "TERMINATES",
    "RIDES_ON",
    "ADJACENCY_OVER",
    "TRAVERSES",
    "SERVES",
)


def _area_for_role(role: str, area_count: int, edge_area_index: int) -> str:
    """Replica of the Simulator's role->area map (topology_model._area_for_role).

    Backbone roles (``P``, ``RR``) -> ``area-0``; edge roles -> a numbered edge area
    round-robined by an edge-node counter. Because P/RR nodes are SCATTERED through
    the node index (every 3rd is P, every 7th is RR), area-0 backbone nodes are
    interleaved with edge-area nodes — so consecutive nodes very frequently differ
    in area and the IPLink between them is a GENUINE inter-area backbone link.
    """
    if role in ("P", "RR"):
        return "area-0"
    if area_count <= 1:
        return "area-0"
    return f"area-{1 + (edge_area_index % (area_count - 1))}"


def _role_for_index(i: int) -> str:
    """Replica of the Simulator's role assignment by node index."""
    if i % 7 == 6:
        return "RR"
    if i % 3 == 0:
        return "P"
    if i % 5 == 4:
        return "peering"
    return "PE"


def simulator_coreip_slice(
    node_count: int = 20,
    area_count: int = 3,
    domain: str = "core-ip",
    snapshot_id: str = "SNAP-sim",
    interfaces_per_port: int = 1,
) -> GraphSlice:
    """Build a ``GraphSlice`` that is a 1:1 structural replica of the Simulator's
    Core-IP topology (``topology_model.build_topology``).

    Defaults (20 nodes / 3 areas) match the P1 gate run (SNAP-bf5a10aa) that proved
    the #240 residual. Role-based areas, a shared ``VPNService`` hub over ``SERVES``,
    and SRLG-bundled adjacent IPLinks are all present, so the closure is exercised
    on the same shape that fails live — not a convenient injected layout.
    """
    s = GraphSlice(domain=domain, snapshot_id=snapshot_id)

    def _add(mo_id: str, object_type: str, igp_area: str | None = None) -> None:
        attrs: dict[str, object] = {}
        if igp_area is not None:
            attrs["igpArea"] = igp_area
        s.add_node(GraphNode(managed_object_id=mo_id, object_type=object_type, attributes=attrs))

    # Nodes + their role-based areas (scattered area-0 backbone).
    nodes: list[tuple[str, str]] = []  # (managedObjectId, igpArea)
    edge_area_index = 0
    for i in range(node_count):
        role = _role_for_index(i)
        area = _area_for_role(role, area_count, edge_area_index)
        if role not in ("P", "RR"):
            edge_area_index += 1
        moid = f"Node:N{i}"
        _add(moid, "Node", area)
        nodes.append((moid, area))

    # Per-node LineCard -> Port -> Interface stack (HOSTED_ON node->lc->port,
    # HOSTS port->interface). LineCard/Port are area-less hardware; the Interface
    # carries the node's area (the Simulator stamps igpArea on Node + Interface).
    for moid, area in nodes:
        nid = moid.split(":", 1)[1]
        lc = f"LineCard:{nid}-LC1"
        port = f"Port:{nid}-LC1-P1"
        _add(lc, "LineCard")
        _add(port, "Port")
        s.add_edge(GraphEdge(moid, lc, "HOSTED_ON"))
        s.add_edge(GraphEdge(lc, port, "HOSTED_ON"))
        for k in range(interfaces_per_port):
            iface = f"Interface:{nid}-LC1-P1-if{k}"
            _add(iface, "Interface", area)
            s.add_edge(GraphEdge(port, iface, "HOSTS"))

    def _first_iface(node_moid: str) -> str:
        nid = node_moid.split(":", 1)[1]
        return f"Interface:{nid}-LC1-P1-if0"

    # IPLinks between consecutive nodes, terminated by both ends' first interface;
    # a FiberSpan rides each link; an IGPAdjacency is reachable from BOTH the
    # interface and the link; an LSP traverses each link and SERVES a shared
    # area-less VPNService hub (the cross-area bridge the NULL-area trail rode).
    iplinks: list[str] = []
    for i in range(node_count - 1):
        a = nodes[i][0].split(":", 1)[1]
        b = nodes[i + 1][0].split(":", 1)[1]
        if_a, if_b = _first_iface(nodes[i][0]), _first_iface(nodes[i + 1][0])
        link = f"IPLink:{a}_{b}"
        _add(link, "IPLink")
        iplinks.append(link)
        s.add_edge(GraphEdge(if_a, link, "TERMINATES"))
        s.add_edge(GraphEdge(if_b, link, "TERMINATES"))
        fiber = f"FiberSpan:F-{a}_{b}"
        _add(fiber, "FiberSpan")
        s.add_edge(GraphEdge(fiber, link, "RIDES_ON"))
        adj = f"IGPAdjacency:{a}_{b}"
        _add(adj, "IGPAdjacency")
        s.add_edge(GraphEdge(if_a, adj, "ADJACENCY_OVER"))
        s.add_edge(GraphEdge(link, adj, "ADJACENCY_OVER"))
        lsp = f"LSP:{a}-{b}-1"
        _add(lsp, "LSP")
        s.add_edge(GraphEdge(link, lsp, "TRAVERSES"))
        vpn = f"VPNService:CUST-{i % 5}"
        if vpn not in s.nodes:
            _add(vpn, "VPNService")
        s.add_edge(GraphEdge(lsp, vpn, "SERVES"))

    # SRLG groups bundle each adjacent pair of IPLinks (bidirectional MEMBER_OF).
    for j in range(0, len(iplinks) - 1, 2):
        srlg = f"SRLG:SRLG-{j // 2}"
        _add(srlg, "SRLG")
        s.add_edge(GraphEdge(srlg, iplinks[j], "MEMBER_OF"))
        s.add_edge(GraphEdge(srlg, iplinks[j + 1], "MEMBER_OF"))
        s.add_edge(GraphEdge(iplinks[j], srlg, "MEMBER_OF"))
        s.add_edge(GraphEdge(iplinks[j + 1], srlg, "MEMBER_OF"))

    return s


# --- analytic helpers computed FROM the fixture (never hard-coded) ----------


def genuine_inter_area_iplinks(slice_: GraphSlice, igp_key: str = "igpArea") -> set[str]:
    """The IPLinks that GENUINELY span >1 IGP area — computed from the fixture.

    An IPLink genuinely spans areas iff its two terminating Interfaces carry
    different ``igpArea`` values. This is the ground truth the #240 tests assert
    against (the cross-area connector count must equal this, NOT be hard-coded).
    """
    term_areas: dict[str, set[str]] = {}
    for edge in slice_.edges:
        if edge.relation == "TERMINATES" and edge.dst.startswith("IPLink:"):
            area = slice_.nodes[edge.src].igp_area(igp_key) if edge.src in slice_.nodes else None
            if area is not None:
                term_areas.setdefault(edge.dst, set()).add(area)
    return {link for link, areas in term_areas.items() if len(areas) > 1}


def genuine_connector_areas(slice_: GraphSlice, igp_key: str = "igpArea") -> dict[str, set[str]]:
    """The ground-truth area set each area-less connector GENUINELY belongs to.

    Computed structurally from the fixture (not from the closure under test):

      * an **IPLink** rides the areas of its terminating Interfaces, plus — through
        SRLG fate-sharing — the areas of every co-member link in its SRLG group(s);
      * a **FiberSpan / IGPAdjacency / LSP** rides exactly the areas of the IPLink(s)
        it is directly attached to (it carries one link's areas, nothing more);
      * an **SRLG** group rides the union of its co-member links' termination areas.

    A connector legitimately appears in >1 area's trails iff its genuine area set
    has >1 area. Any trail-area appearance OUTSIDE this set is a spurious leak.
    """
    node_area = {mo: n.igp_area(igp_key) for mo, n in slice_.nodes.items()}

    def link_term_areas(link: str) -> set[str]:
        return {
            node_area[e.src]
            for e in slice_.edges
            if e.relation == "TERMINATES" and e.dst == link and node_area.get(e.src) is not None
        }

    # SRLG group -> co-member IPLinks.
    srlg_links: dict[str, set[str]] = {}
    for e in slice_.edges:
        if e.relation == "MEMBER_OF":
            if e.src.startswith("SRLG:") and e.dst.startswith("IPLink:"):
                srlg_links.setdefault(e.src, set()).add(e.dst)
            if e.dst.startswith("SRLG:") and e.src.startswith("IPLink:"):
                srlg_links.setdefault(e.dst, set()).add(e.src)

    out: dict[str, set[str]] = {}
    for mo, area in node_area.items():
        if area is not None:
            continue  # area-bearing object, not a connector
        otype = mo.split(":", 1)[0]
        if mo.startswith("IPLink:"):
            areas = set(link_term_areas(mo))
            for links in srlg_links.values():
                if mo in links:
                    for link in links:
                        areas |= link_term_areas(link)
            out[mo] = areas
        elif otype in ("FiberSpan", "IGPAdjacency", "LSP"):
            links = {e.dst for e in slice_.edges if e.src == mo and e.dst.startswith("IPLink:")} | {
                e.src for e in slice_.edges if e.dst == mo and e.src.startswith("IPLink:")
            }
            areas = set()
            for link in links:
                areas |= link_term_areas(link)
            out[mo] = areas
        elif mo.startswith("SRLG:"):
            areas = set()
            for link in srlg_links.get(mo, set()):
                areas |= link_term_areas(link)
            out[mo] = areas
    return out
