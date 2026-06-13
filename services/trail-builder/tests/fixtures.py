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

    router.get(url__regex=r".*/topology/nodes$").mock(side_effect=_nodes)
    router.get(url__regex=r".*/topology/nodes/(?P<managedObjectId>[^/]+)/neighbors$").mock(
        side_effect=_neighbors
    )
    router.get(url__regex=r".*/topology/traversal$").mock(side_effect=_traverse)
    router.get(url__regex=r".*/topology/snapshots/current$").mock(
        return_value=httpx.Response(200, json={"snapshotId": "current"})
    )
    return router


def install_knowledge_stub(
    base_url: str, policies: dict[str, dict], call_log: list[str] | None = None
) -> respx.MockRouter:
    """Install respx routes for the Knowledge trailPolicy read API.

    ``policies`` maps domain -> trailPolicy payload dict.
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
                "recordId": "default",
                "version": "1",
                "isCurrent": True,
                "payload": payload,
            },
        )

    router.get(
        url__regex=r".*/domains/(?P<domain>[^/]+)/trailPolicy/default$"
    ).mock(side_effect=_policy)
    return router
