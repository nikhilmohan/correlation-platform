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

from .models import GraphSlice, Trail, TrailPolicy

# Object types that are pure risk GROUPS, not fault-capable seed objects. An
# ``SRLG`` is an area-less fate-sharing group node: it carries no ``igpArea`` and
# has no closure-edge neighbours (its only edges are the ``MEMBER_OF`` links that
# are NOT in the dependency-edge set), so seeding from it yields a useless
# 1-member ``SRLG:*``-only trail. It must still appear as a MEMBER of real trails
# via the SRLG co-trailing union — it is just never a standalone SEED.
RISK_GROUP_OBJECT_TYPES: frozenset[str] = frozenset({"SRLG"})


class TrailClosure:
    """Pure, in-memory trail computation over a fetched graph slice."""

    def compute(self, slice_: GraphSlice, policy: TrailPolicy) -> list[Trail]:
        """Return the deduplicated set of overlapping, bounded trails."""
        graph = self._build_graph(slice_, policy)
        # Dispatch on the Knowledge-authored boundary policy. Area-component
        # bounding applies only when ``boundary.type == "igp-area"`` (the only
        # strategy implemented). Any other / absent boundary type falls back to
        # the unbounded whole-component closure — never crashes, a clean Phase-B
        # extension point (other boundary strategies plug in here).
        igp_key = policy.boundary.attribute_key if policy.boundary.type == "igp-area" else None

        # 1. Per-seed, area-bounded transitive closure over the dependency edges.
        #    An area-less seed yields ONE member set per area it touches; each set
        #    carries the area it is bounded to (None when unbounded). A member set
        #    may legitimately appear under two areas (shared area-less connector) —
        #    those stay distinct trails (overlap), never fused. The three lists are
        #    index-parallel: member_sets[i] is bounded to areas[i], seeded by seeds[i].
        member_sets: list[frozenset[str]] = []
        areas: list[str | None] = []
        seeds: list[str] = []
        for seed in sorted(graph.nodes):
            # Seed only from fault-capable objects. Pure risk-GROUP nodes (SRLG)
            # are never standalone seeds — seeding from them yields a degenerate
            # 1-member ``SRLG:*``-only trail (they have no closure-edge
            # neighbours). They still become MEMBERS of real trails via the SRLG
            # co-trailing union in step 2.
            if graph.nodes[seed].get("object_type") in RISK_GROUP_OBJECT_TYPES:
                continue
            for members, area in self._bounded_closures(graph, seed, igp_key):
                member_sets.append(frozenset(members))
                areas.append(area)
                seeds.append(seed)

        # 2. SRLG co-trailing: add an SRLG group's co-member (area-less) links to
        #    every trail that already touches the group. Adding only the area-less
        #    link objects (never another area's area-bearing nodes) keeps each trail
        #    single-area, so the SRLG mesh can never re-fuse two areas into a
        #    whole-network trail while fate-shared links still co-appear
        #    (AC-2 + AC-3 together).
        member_sets = self._srlg_union(member_sets, areas, slice_, policy)

        # 3. Deduplicate identical member sets; assign deterministic ids + context.
        seed_for: dict[frozenset[str], str] = {}
        area_for: dict[frozenset[str], str | None] = {}
        for fs, area, seed in zip(member_sets, areas, seeds, strict=True):
            seed_for.setdefault(fs, seed)
            if fs not in area_for or (area is not None and area_for.get(fs) is None):
                area_for[fs] = area
        return self._materialize(member_sets, slice_, igp_key, seed_for, area_for)

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

    def _bounded_closures(
        self,
        graph: nx.MultiDiGraph,
        seed: str,
        igp_key: str | None,
    ) -> list[tuple[set[str], str | None]]:
        """Return the seed's area-bounded reachable set(s) — the #225 fix.

        The edge view is undirected (a Port and its IPLink correlate regardless of
        edge direction). With NO IGP-area boundary (``igp_key`` is ``None``) this
        is a single unbounded whole-component closure (the Phase-B fallback).

        With an IGP-area boundary it bounds by **area component**, not by a
        "differs-from-seed" prune:

          * Determine the seed's **target area(s)**: its own ``igpArea`` if it is
            area-bearing; otherwise the set of areas of the area-bearing objects
            directly reachable from it over the dependency-edge view (so an
            area-less FiberSpan/IPLink/LSP seed produces one set per area it
            touches — never a whole-network set).
          * Run a closure **per target area `A`**: from the seed, admit a
            neighbour iff it is area-less OR its area equals ``A``. Crucially an
            **area-less object never extends the frontier into a *different*
            area's area-bearing object** — when expanding, a cross-area
            area-bearing neighbour is pruned. So the area-less connector mesh can
            no longer bridge areas, and there is no whole-network trail (AC-2).

        A shared area-less object reachable within two areas lands in BOTH areas'
        sets — legitimate overlap (AC-1), kept distinct by dedup since the member
        sets differ.
        """
        if igp_key is None:
            return [(self._reachable(graph, seed, target_area=None), None)]

        target_areas = self._target_areas(graph, seed)
        if not target_areas:
            # No area-bearing object anywhere in the seed's reach (a fully
            # area-less island): fall back to a single unbounded set so the seed
            # still yields its (area-less) trail rather than vanishing.
            return [(self._reachable(graph, seed, target_area=None), None)]
        return [(self._reachable(graph, seed, target_area=a), a) for a in sorted(target_areas)]

    def _target_areas(self, graph: nx.MultiDiGraph, seed: str) -> set[str]:
        """The IGP area(s) a seed is bounded to.

        Its own area if area-bearing; else the areas of the area-bearing objects
        directly reachable from it over the dependency-edge view.
        """
        seed_area = graph.nodes[seed].get("igp_area")
        if seed_area is not None:
            return {seed_area}
        areas: set[str] = set()
        seen: set[str] = {seed}
        frontier = [seed]
        # Walk only through area-less objects to discover the area-bearing
        # objects this area-less seed touches (each defines a target area).
        while frontier:
            current = frontier.pop()
            for neighbor in self._undirected_neighbors(graph, current):
                if neighbor in seen:
                    continue
                seen.add(neighbor)
                n_area = graph.nodes[neighbor].get("igp_area")
                if n_area is not None:
                    areas.add(n_area)
                else:
                    frontier.append(neighbor)
        return areas

    def _reachable(
        self,
        graph: nx.MultiDiGraph,
        seed: str,
        target_area: str | None,
    ) -> set[str]:
        """Undirected reachable set, optionally bounded to ``target_area``.

        When ``target_area`` is ``None`` this is the whole connected component
        (unbounded fallback). Otherwise an area-bearing neighbour is admitted only
        if its area equals ``target_area``; an area-less neighbour is admitted but
        never bridges into a *different* area's area-bearing object (that is what
        the per-neighbour area check enforces on every expansion step).
        """
        reachable: set[str] = {seed}
        frontier = [seed]
        while frontier:
            current = frontier.pop()
            for neighbor in self._undirected_neighbors(graph, current):
                if neighbor in reachable:
                    continue
                if target_area is not None:
                    n_area = graph.nodes[neighbor].get("igp_area")
                    # Area-bearing neighbour: admit only within the target area.
                    # Area-less neighbour (n_area is None): admit as a connector
                    # within this area's reach — but because every area-bearing
                    # neighbour reached *through* it is itself area-checked here,
                    # it can never bridge into another area's objects.
                    if n_area is not None and n_area != target_area:
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
        areas: list[str | None],
        slice_: GraphSlice,
        policy: TrailPolicy,
    ) -> list[frozenset[str]]:
        """Co-trail SRLG-fate-shared links (policy ``srlgRule``) — area-safe (AC-3).

        For each SRLG group, every trail that already contains one of its co-member
        links receives **all** of that group's co-member links. Because the SRLG
        co-members are themselves **area-less** link objects, adding them never
        drags a *different* area's area-bearing object (Node/Interface) into the
        trail — so each trail stays single-area (AC-2 holds) while all co-member
        links of an SRLG co-appear in any trail that touches the group (AC-3). A
        shared SRLG group/link thus appears in **both** areas' trails as legitimate
        overlap rather than fusing them into one whole-network trail (the #225
        re-fuse the naive "merge the whole sets" union caused). ``areas`` is
        index-parallel to ``member_sets`` (kept for the area context; the
        link-level union is area-safe by construction). With no boundary this is
        equivalent to the original co-member union.
        """
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
        for group_node, co_members in groups.items():
            if len(co_members) < 2:
                continue
            # The area-less objects to co-trail: the co-member links + the SRLG
            # group node itself (so listTrails SRLG context is present on the trail).
            shared = set(co_members) | {group_node}
            for i, ms in enumerate(merged):
                if ms & co_members:
                    merged[i] = frozenset(ms | shared)
        return merged

    # --- step 3: dedup + materialize ---

    def _materialize(
        self,
        member_sets: list[frozenset[str]],
        slice_: GraphSlice,
        igp_key: str | None,
        seed_for: dict[frozenset[str], str],
        area_for: dict[frozenset[str], str | None],
    ) -> list[Trail]:
        seen: dict[frozenset[str], Trail] = {}
        for fs in member_sets:
            if not fs or fs in seen:
                continue
            members = tuple(sorted(fs))
            trail_id = _deterministic_id(slice_.domain, slice_.snapshot_id, members)
            seed = seed_for.get(fs) or members[0]
            # Tag the trail with the area it was BOUNDED to (derived from the
            # area-bearing objects it reaches — the #225 fix), not the seed's own
            # area (which is None for an area-less seed). Fall back to deriving it
            # from the members if a set surfaced only via SRLG-union.
            igp_area: str | None = None
            if igp_key:
                igp_area = area_for.get(fs)
                if igp_area is None:
                    igp_area = self._area_of_members(fs, slice_, igp_key)
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
    def _area_of_members(members: frozenset[str], slice_: GraphSlice, igp_key: str) -> str | None:
        """The single IGP area shared by a member set's area-bearing objects.

        After area-component bounding every trail's area-bearing members share one
        area, so this is well-defined; if a post-union set ended up mixing areas
        (it should not), return ``None`` rather than guess.
        """
        areas = {
            slice_.nodes[m].igp_area(igp_key)
            for m in members
            if m in slice_.nodes and slice_.nodes[m].igp_area(igp_key) is not None
        }
        return next(iter(areas)) if len(areas) == 1 else None

    @staticmethod
    def _srlg_context(members: frozenset[str], slice_: GraphSlice) -> str | None:
        """Best-effort SRLG group id for listTrails context (a member of type SRLG)."""
        for m in sorted(members):
            if m.startswith("SRLG:"):
                return m
        return None


def _deterministic_id(domain: str, snapshot_id: str, members: tuple[str, ...]) -> str:
    """Content-derived, reproducible trail id (domain + snapshot + sorted members)."""
    digest = hashlib.sha256("|".join([domain, snapshot_id, *members]).encode("utf-8")).hexdigest()
    return f"trail-{digest[:24]}"
