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

        # Per-connector area anchoring (the #234 fix). An area-less connector
        # "rides within" an area only when it genuinely connects that area's
        # area-bearing objects — NOT merely when it is reachable from that area
        # across a network-wide area-less mesh. ``connector_areas[c]`` is the set
        # of areas a given area-less connector legitimately rides within; an
        # area-less neighbour is admitted to area ``A``'s closure only when
        # ``A in connector_areas[neighbour]``.
        connector_areas = self._connector_areas(graph, igp_key)

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
            for members, area in self._bounded_closures(graph, seed, igp_key, connector_areas):
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
        connector_areas: dict[str, set[str]],
    ) -> list[tuple[set[str], str | None]]:
        """Return the seed's area-bounded reachable set(s) — the #225 / #234 fix.

        The edge view is undirected (a Port and its IPLink correlate regardless of
        edge direction). With NO IGP-area boundary (``igp_key`` is ``None``) this
        is a single unbounded whole-component closure (the Phase-B fallback).

        With an IGP-area boundary it bounds by **area component**:

          * Determine the seed's **target area(s)**: its own ``igpArea`` if it is
            area-bearing; otherwise the areas the seed *genuinely rides within*
            (``connector_areas[seed]``) — so an area-less FiberSpan/IPLink/LSP seed
            that rides a single area produces only that area's set, never one set
            per area it is merely mesh-reachable from.
          * Run a closure **per target area `A`**: from the seed, admit a
            neighbour iff it is an area-``A`` area-bearing object, or an area-less
            connector that genuinely rides within area ``A``
            (``A in connector_areas[neighbour]``). An area-less connector that
            rides only *another* area is pruned — so the network-wide area-less
            mesh can neither bridge areas (AC-2) nor replicate single-area
            connectors into foreign area trails (#234).

        A connector that genuinely rides two areas (a cross-area span) lands in
        BOTH areas' sets — legitimate overlap (AC-1), kept distinct by dedup.
        """
        if igp_key is None:
            return [(self._reachable(graph, seed, None, connector_areas), None)]

        target_areas = self._target_areas(graph, seed, connector_areas)
        if not target_areas:
            # No area-bearing object anywhere in the seed's reach (a fully
            # area-less island): fall back to a single unbounded set so the seed
            # still yields its (area-less) trail rather than vanishing.
            return [(self._reachable(graph, seed, None, connector_areas), None)]
        return [(self._reachable(graph, seed, a, connector_areas), a) for a in sorted(target_areas)]

    def _target_areas(
        self,
        graph: nx.MultiDiGraph,
        seed: str,
        connector_areas: dict[str, set[str]],
    ) -> set[str]:
        """The IGP area(s) a seed is bounded to.

        Its own area if area-bearing; else the areas the area-less seed genuinely
        rides within (``connector_areas``) — NOT every area it is mesh-reachable
        from. An area-less seed that rides one area therefore targets only that
        area; a genuine cross-area span targets both.
        """
        seed_area = graph.nodes[seed].get("igp_area")
        if seed_area is not None:
            return {seed_area}
        return set(connector_areas.get(seed, set()))

    def _connector_areas(
        self,
        graph: nx.MultiDiGraph,
        igp_key: str | None,
    ) -> dict[str, set[str]]:
        """Map each area-less connector to the set of areas it genuinely rides within.

        The #234 fix. A connector "rides within" area ``X`` only when it actually
        connects ``X``'s area-bearing objects — not merely when ``X`` is reachable
        from it across a network-wide area-less mesh. Concretely a connector is
        anchored to ``X`` iff it is, or is directly adjacent to, a **genuine
        ``X``-conductor** — an area-less object that is *directly* adjacent to an
        area-``X`` area-bearing object (e.g. an IPLink terminated by an area-``X``
        Interface; a FiberSpan riding such an IPLink).

        Crucially a pure *transport* object (e.g. one FiberSpan riding every
        IPLink network-wide) is **not** a conductor of any area — it is directly
        adjacent to no area-bearing object — so it never propagates one area's
        connectors into another area. It legitimately rides every area it directly
        conducts, but it cannot make a single-area connector appear cross-area.

        With no IGP-area boundary this is empty (every connector is unbounded).
        """
        if igp_key is None:
            return {}

        area_less = [n for n in graph.nodes if graph.nodes[n].get("igp_area") is None]

        # `direct[n]` — areas of the area-bearing objects DIRECTLY adjacent to the
        # area-less object `n`. An `n` with a non-empty `direct[n]` is a genuine
        # conductor of each of those areas.
        direct: dict[str, set[str]] = {}
        for n in area_less:
            areas: set[str] = set()
            for nb in self._undirected_neighbors(graph, n):
                nb_area = graph.nodes[nb].get("igp_area")
                if nb_area is not None:
                    areas.add(nb_area)
            direct[n] = areas

        # A connector rides within area X iff it conducts X directly, or it is
        # directly adjacent to a genuine X-conductor (e.g. a FiberSpan riding an
        # IPLink that terminates on an area-X Interface). Traversal continues only
        # *through* genuine X-conductors, so a network-wide transport object (no
        # direct area-bearing neighbour, conductor of nothing) never bridges one
        # area's connectors into another.
        connector_areas: dict[str, set[str]] = {n: set(direct[n]) for n in area_less}
        for n in area_less:
            for nb in self._undirected_neighbors(graph, n):
                if nb in direct:  # area-less neighbour
                    connector_areas[n] |= direct[nb]
        return connector_areas

    def _reachable(
        self,
        graph: nx.MultiDiGraph,
        seed: str,
        target_area: str | None,
        connector_areas: dict[str, set[str]],
    ) -> set[str]:
        """Undirected reachable set, optionally bounded to ``target_area``.

        When ``target_area`` is ``None`` this is the whole connected component
        (unbounded fallback). Otherwise:

          * an **area-bearing** neighbour is admitted only when its area equals
            ``target_area``;
          * an **area-less connector** neighbour is admitted only when it
            *genuinely rides within* ``target_area`` (#234) —
            ``target_area in connector_areas[neighbour]``. A connector that rides
            only another area is pruned, so neither the area-less mesh as a whole
            (AC-2) nor an individual single-area connector (#234) can leak into a
            foreign area's trail.
        """
        reachable: set[str] = {seed}
        frontier = [seed]
        while frontier:
            current = frontier.pop()
            for neighbor in self._undirected_neighbors(graph, current):
                if neighbor in reachable:
                    continue
                if target_area is not None and not self._admits(
                    graph, neighbor, target_area, connector_areas
                ):
                    continue
                reachable.add(neighbor)
                frontier.append(neighbor)
        return reachable

    @staticmethod
    def _admits(
        graph: nx.MultiDiGraph,
        node: str,
        target_area: str,
        connector_areas: dict[str, set[str]],
    ) -> bool:
        """Whether ``node`` may be admitted to ``target_area``'s reachable set.

        An area-bearing node is admitted only within its own area; an area-less
        connector only when it genuinely rides within ``target_area``.
        """
        node_area = graph.nodes[node].get("igp_area")
        if node_area is not None:
            return node_area == target_area
        return target_area in connector_areas.get(node, set())

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
