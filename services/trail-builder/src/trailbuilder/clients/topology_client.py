"""``TopologyClient`` — graph closure against the FROZEN Topology query API.

Pinned to Topology's frozen paths / params / DTOs (Q3):
  - list-by-type  ``GET /topology/nodes?objectType=&domain=&snapshotId=``  -> NodeListDto
  - neighbors     ``GET /topology/nodes/{moId}/neighbors?relation=&domain=&snapshotId=`` -> NeighborsDto
  - bounded trav. ``GET /topology/traversal?start=&relation=&maxDepth=&crossDomain=false`` -> TraversalDto

Every read is domain- AND snapshot-scoped: the ``snapshotId`` token is always the
build's in-scope snapshot scope (``current``), so the graph slice matches the
snapshot the trails are persisted under — Topology is never asked to re-resolve a
snapshot. The graph store is never touched directly (Topology is the sole owner).
"""

from __future__ import annotations

import httpx

from ..config import Settings
from ..models import GraphEdge, GraphNode, GraphSlice
from .errors import IntegrationError


class TopologyClient:
    """Assembles a per-snapshot graph slice from the frozen Topology query API."""

    def __init__(self, settings: Settings, client: httpx.Client | None = None) -> None:
        self._settings = settings
        self._base = settings.topology_service_base_url.rstrip("/")
        self._client = client or httpx.Client(
            base_url=self._base, timeout=settings.http_timeout_seconds
        )

    # --- public API ---

    def fetch_slice(
        self,
        domain: str,
        snapshot_scope: str,
        seed_object_types: list[str],
        closure_relations: list[str],
        srlg_edge_type: str | None,
    ) -> GraphSlice:
        """Fetch the domain-/snapshot-scoped graph slice for trail computation.

        ``snapshot_scope`` is the in-scope snapshot token (``current``). Seeds are
        listed per ``seed_object_types``; edges are discovered via the neighbors
        endpoint over the closure relations (+ the SRLG edge type); a bounded
        traversal call per seed pins the read to the frozen traversal path and the
        in-scope snapshot scope (Q3 / AC-26).
        """
        relations = list(dict.fromkeys([*closure_relations, *([srlg_edge_type] if srlg_edge_type else [])]))
        slice_ = GraphSlice(domain=domain, snapshot_id=snapshot_scope)

        # 1. Enumerate seed nodes per fault-capable type (also seeds the node set).
        seeds: list[str] = []
        for object_type in seed_object_types:
            node_list = self._list_nodes(object_type, domain, snapshot_scope)
            for node in node_list:
                slice_.add_node(node)
                seeds.append(node.managed_object_id)

        # 2. Discover edges (and any non-seed neighbour nodes) via the neighbors API,
        #    and pin the read to the frozen traversal path + in-scope snapshot scope.
        visited: set[str] = set()
        frontier = list(seeds)
        seen_edges: set[tuple[str, str, str]] = set()
        while frontier:
            mo_id = frontier.pop()
            if mo_id in visited:
                continue
            visited.add(mo_id)
            # Snapshot-scoped bounded traversal (pins frozen path + snapshotId=current).
            self._traverse(mo_id, relations, domain, snapshot_scope)
            for neighbor_node, edge in self._neighbors(mo_id, relations, domain, snapshot_scope):
                slice_.add_node(neighbor_node)
                key = (edge.src, edge.dst, edge.relation)
                if key not in seen_edges:
                    seen_edges.add(key)
                    slice_.add_edge(edge)
                if neighbor_node.managed_object_id not in visited:
                    frontier.append(neighbor_node.managed_object_id)
        return slice_

    def ping(self) -> bool:
        """Best-effort reachability probe for /health."""
        try:
            self._client.get("/topology/snapshots/current")
            return True
        except httpx.HTTPError:
            return False

    # --- frozen-path calls ---

    def _list_nodes(self, object_type: str, domain: str, snapshot_scope: str) -> list[GraphNode]:
        data = self._get(
            "/topology/nodes",
            params={"objectType": object_type, "domain": domain, "snapshotId": snapshot_scope},
        )
        return [_node_from_dto(n) for n in data.get("nodes", [])]

    def _neighbors(
        self, mo_id: str, relations: list[str], domain: str, snapshot_scope: str
    ) -> list[tuple[GraphNode, GraphEdge]]:
        data = self._get(
            f"/topology/nodes/{mo_id}/neighbors",
            params={
                "relation": relations,
                "domain": domain,
                "snapshotId": snapshot_scope,
                "crossDomain": "false",
            },
        )
        out: list[tuple[GraphNode, GraphEdge]] = []
        for entry in data.get("neighbors", []):
            node = _node_from_dto(entry["node"])
            via = entry["via"]
            out.append(
                (
                    node,
                    GraphEdge(src=via["from"], dst=via["to"], relation=via["relation"]),
                )
            )
        return out

    def _traverse(
        self, start: str, relations: list[str], domain: str, snapshot_scope: str
    ) -> list[GraphNode]:
        data = self._get(
            "/topology/traversal",
            params={
                "start": start,
                "relation": relations,
                "maxDepth": self._settings.traversal_max_depth,
                "domain": domain,
                "snapshotId": snapshot_scope,
                "crossDomain": "false",
            },
        )
        return [_node_from_dto(n) for n in data.get("reached", [])]

    # --- transport with bounded retry ---

    def _get(self, path: str, params: dict) -> dict:
        attempts = max(1, self._settings.http_retry_max)
        last: Exception | None = None
        for _ in range(attempts):
            try:
                resp = self._client.get(path, params=params)
                resp.raise_for_status()
                body = resp.json()
                if not isinstance(body, dict):
                    raise IntegrationError("topology", f"unexpected non-object body for {path}")
                return body
            except (httpx.HTTPError, ValueError) as exc:
                last = exc
        raise IntegrationError("topology", f"topology call {path} failed: {last}")


def _node_from_dto(dto: dict) -> GraphNode:
    """Decode a frozen ``NodeDto`` into a :class:`GraphNode`."""
    try:
        return GraphNode(
            managed_object_id=dto["managedObjectId"],
            object_type=dto["objectType"],
            attributes=dict(dto.get("attributes") or {}),
        )
    except KeyError as exc:
        raise IntegrationError("topology", f"NodeDto missing field {exc}") from exc
