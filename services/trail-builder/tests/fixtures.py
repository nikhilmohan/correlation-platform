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


def realistic_coreip_slice(
    node_count: int = 9,
    area_count: int = 3,
    domain: str = "core-ip",
    snapshot_id: str = "snap-real",
) -> GraphSlice:
    """Build a ``GraphSlice`` mirroring the Simulator's area-less-mesh topology.

    Areas are bridged ONLY through area-less IPLink/SRLG/FiberSpan/LSP/IGPAdjacency
    connectors — never through a direct area-bearing edge. Returns the slice the
    closure runs on directly (no Topology client involved).
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
        area = f"area-{i % area_count}"
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

    return s
