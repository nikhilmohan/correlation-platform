"""``TrailClosure`` — the overlapping, IGP-area-bounded trail algorithm.

Computes overlapping, policy-bounded trails as the transitive closure over the
policy's dependency-edge set from each seed, bounded by IGP area, then unions
SRLG-co-member links into shared trails. All bounds come from the domain's
Knowledge trail policy — nothing is hard-coded.

Overlap is real: because the IGP-area bound prunes per-seed at the area
boundary, a single object that sits on multiple area-bounded reachable sets
(e.g. two LSP paths) plus an SRLG group lands in multiple distinct trails.
"""

from __future__ import annotations

import hashlib

import networkx as nx

from .models import GraphSlice, TrailPolicy, Trail


class TrailClosure:
    """Pure, in-memory trail computation over a fetched graph slice."""

    def compute(self, slice_: GraphSlice, policy: TrailPolicy) -> list[Trail]:
        """Return the deduplicated set of overlapping, bounded trails."""
        graph = self._build_graph(slice_, policy)
        igp_key = policy.boundary.attribute_key if policy.boundary.type == "igp-area" else None

        # 1. Per-seed, area-bounded transitive closure over the dependency edges.
        member_sets: list[frozenset[str]] = []
        seed_for: dict[frozenset[str], str] = {}
        for seed in sorted(graph.nodes):
            members = self._bounded_closure(graph, slice_, seed, igp_key)
            fs = frozenset(members)
            member_sets.append(fs)
            seed_for.setdefault(fs, seed)

        # 2. SRLG union: merge member sets that contain co-member links of one SRLG.
        member_sets = self._srlg_union(member_sets, slice_, policy)

        # 3. Deduplicate identical member sets; assign deterministic ids + context.
        return self._materialize(member_sets, slice_, igp_key, seed_for)

    # --- step 1: graph + bounded closure ---

    def _build_graph(self, slice_: GraphSlice, policy: TrailPolicy) -> nx.MultiDiGraph:
        graph = nx.MultiDiGraph()
        for node in slice_.nodes.values():
            graph.add_node(
                node.managed_object_id,
                object_type=node.object_type,
                igp_area=node.igp_area(
                    policy.boundary.attribute_key if policy.boundary.type == "igp-area" else None
                ),
            )
        closure_relations = set(policy.closure_edge_types)
        for edge in slice_.edges:
            if edge.relation in closure_relations and edge.src in graph and edge.dst in graph:
                graph.add_edge(edge.src, edge.dst, relation=edge.relation)
        return graph

    def _bounded_closure(
        self,
        graph: nx.MultiDiGraph,
        slice_: GraphSlice,
        seed: str,
        igp_key: str | None,
    ) -> set[str]:
        """Undirected reachable set from ``seed``, pruning cross-area members.

        The edge view is undirected (a Port and its IPLink correlate regardless
        of edge direction). When the policy has an IGP-area boundary, traversal
        does not cross into a node of a different ``igpArea`` than the seed's, so
        no trail spans two areas (AC-2) — there is no whole-network trail.
        """
        seed_area = graph.nodes[seed].get("igp_area") if igp_key else None
        reachable: set[str] = {seed}
        frontier = [seed]
        while frontier:
            current = frontier.pop()
            for neighbor in self._undirected_neighbors(graph, current):
                if neighbor in reachable:
                    continue
                if igp_key is not None and seed_area is not None:
                    n_area = graph.nodes[neighbor].get("igp_area")
                    # Prune cross-area members so no trail spans two IGP areas
                    # (AC-2). Area-less objects (e.g. LSP / IPLink / SRLG group,
                    # which carry no igpArea) are NOT pruned: they are layer
                    # objects that legitimately ride within one area, and a
                    # shared one may appear in several area-bounded trails —
                    # that is the source of real trail overlap (AC-1).
                    if n_area is not None and n_area != seed_area:
                        continue
                reachable.add(neighbor)
                frontier.append(neighbor)
        return reachable

    @staticmethod
    def _undirected_neighbors(graph: nx.MultiDiGraph, node: str) -> set[str]:
        return set(graph.successors(node)) | set(graph.predecessors(node))

    # --- step 2: SRLG union ---

    def _srlg_union(
        self,
        member_sets: list[frozenset[str]],
        slice_: GraphSlice,
        policy: TrailPolicy,
    ) -> list[frozenset[str]]:
        """Merge trails whose links share an SRLG group (policy ``srlgRule``)."""
        if policy.srlg_rule.mode != "union-members" or not policy.srlg_rule.srlg_edge_type:
            return member_sets
        srlg_edge = policy.srlg_rule.srlg_edge_type

        # Group link members by their SRLG group node (MEMBER_OF edges).
        # An SRLG group node is the target of MEMBER_OF edges from its co-members.
        groups: dict[str, set[str]] = {}
        for edge in slice_.edges:
            if edge.relation == srlg_edge:
                groups.setdefault(edge.dst, set()).add(edge.src)
                groups.setdefault(edge.src, set())  # ensure group node tracked

        merged = list(member_sets)
        for co_members in groups.values():
            if len(co_members) < 2:
                continue
            # Find every trail that contains any co-member link; union them.
            touched_idx = [
                i for i, ms in enumerate(merged) if ms & co_members
            ]
            if len(touched_idx) < 1:
                continue
            union: set[str] = set()
            for i in touched_idx:
                union |= merged[i]
            union |= co_members
            new_fs = frozenset(union)
            for i in touched_idx:
                merged[i] = new_fs
        return merged

    # --- step 3: dedup + materialize ---

    def _materialize(
        self,
        member_sets: list[frozenset[str]],
        slice_: GraphSlice,
        igp_key: str | None,
        seed_for: dict[frozenset[str], str],
    ) -> list[Trail]:
        seen: dict[frozenset[str], Trail] = {}
        for fs in member_sets:
            if not fs or fs in seen:
                continue
            members = tuple(sorted(fs))
            trail_id = _deterministic_id(slice_.domain, slice_.snapshot_id, members)
            seed = seed_for.get(fs) or members[0]
            igp_area = None
            if igp_key and seed in slice_.nodes:
                igp_area = slice_.nodes[seed].igp_area(igp_key)
            srlg_group = self._srlg_context(fs, slice_)
            seen[fs] = Trail(
                trail_id=trail_id,
                domain=slice_.domain,
                snapshot_id=slice_.snapshot_id,
                seed_managed_object_id=seed,
                members=members,
                igp_area=igp_area,
                srlg_group=srlg_group,
            )
        return list(seen.values())

    @staticmethod
    def _srlg_context(members: frozenset[str], slice_: GraphSlice) -> str | None:
        """Best-effort SRLG group id for listTrails context (a member of type SRLG)."""
        for m in sorted(members):
            if m.startswith("SRLG:"):
                return m
        return None


def _deterministic_id(domain: str, snapshot_id: str, members: tuple[str, ...]) -> str:
    """Content-derived, reproducible trail id (domain + snapshot + sorted members)."""
    digest = hashlib.sha256(
        "|".join([domain, snapshot_id, *members]).encode("utf-8")
    ).hexdigest()
    return f"trail-{digest[:24]}"
