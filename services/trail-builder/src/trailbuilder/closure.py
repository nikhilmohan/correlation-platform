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
            # An area-less seed that genuinely rides NO area — e.g. a pure
            # area-less HUB such as ``VPNService:CUST-*`` (reachable only across a
            # SERVES/LSP fan-out, never directly terminating an area-bearing
            # endpoint) — yields NO standalone trail. Returning an unbounded
            # whole-component set here was issue #240's NULL-area whole-network
            # trail: with a boundary active, the hub seeded a 170-member trail
            # spanning the entire graph. The hub still appears as a MEMBER of every
            # area trail whose IPLink/LSP genuinely reaches it (admitted by the
            # per-area ``_reachable`` walk); it just never SEEDS an unbounded set.
            # The unbounded fallback survives ONLY when there is no IGP-area
            # boundary at all (``igp_key is None``, handled above) — never under an
            # active boundary, so no NULL-area whole-network trail can form (#240).
            return []
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

        Issue #234 + the #240 residual. A connector "rides within" area ``X`` only
        when it genuinely terminates / anchors to ``X``'s area-bearing **endpoints**
        — NOT merely when ``X`` is mesh-reachable from it across the network-wide
        area-less connector fabric (FiberSpan/LSP/IGPAdjacency rings, SRLG groups,
        and shared hubs such as ``VPNService:CUST-*``).

        We classify each area-less object by whether it is **anchored** — directly
        adjacent to at least one area-bearing object (``direct[n]`` non-empty). An
        IPLink terminated by two area-``X`` interfaces is anchored to ``{X}``; an
        IPLink terminated by an area-0 and an area-2 interface is a genuine
        cross-area span anchored to ``{0, 2}``.

        Area membership then propagates **only through anchored connectors** — a
        chain ``anchored -> anchored -> …`` carries its area set, but propagation
        *stops at* (never relays through) an **unanchored** connector. So:

          * A genuine cross-area IPLink rides exactly its two terminating areas;
            the FiberSpan/LSP/IGPAdjacency that ride only that one link inherit
            exactly that link's area set — and nothing more.
          * A pure area-less **hub** with no direct area-bearing neighbour — e.g.
            ``VPNService:CUST-*`` (reached only across ``SERVES``) or a single
            shared transport FiberSpan riding every IPLink — is *unanchored*. It
            may *receive* the areas of the anchored connectors adjacent to it (so
            it correctly rides every area it legitimately conducts), but because it
            is never relayed *through*, it can NEVER bridge one area's connectors
            into another area. This is what eliminates both the #234 wholesale
            replication and the #240 hub-bridged NULL-area whole-network trail:
            without a relaying hub, a single-area connector's area set stays
            single-area and the hub itself targets no standalone area (it seeds no
            trail — see ``_bounded_closures``).

        With no IGP-area boundary this is empty (every connector is unbounded).
        """
        if igp_key is None:
            return {}

        area_less = [n for n in graph.nodes if graph.nodes[n].get("igp_area") is None]

        # `direct[n]` — areas of the area-bearing objects DIRECTLY adjacent to the
        # area-less object `n`. `n` is **anchored** iff `direct[n]` is non-empty;
        # only anchored connectors genuinely terminate an area's endpoints.
        direct: dict[str, set[str]] = {}
        for n in area_less:
            direct[n] = {
                graph.nodes[nb].get("igp_area")  # type: ignore[misc]
                for nb in self._undirected_neighbors(graph, n)
                if graph.nodes[nb].get("igp_area") is not None
            }

        # Propagate area membership ALONG anchored connectors to a fixpoint: a
        # connector adopts the areas its anchored neighbour is itself DIRECTLY
        # anchored to (``direct[nb]``) — NOT that neighbour's full accumulated set
        # (``connector_areas[nb]``).
        #
        # The #240 RESIDUAL was relaying ``connector_areas[nb]`` here: an anchored
        # connector that *also* rides a genuine cross-area link accumulated that
        # link's foreign area, then relayed the whole accumulated set onward — so a
        # genuinely SINGLE-area link adjacent to it inherited the foreign area and
        # leaked into a foreign area's trail. Concretely, an IGPAdjacency anchored
        # to an area-0 interface that rides both a single-area (area-0) IPLink and a
        # cross-area (area-0/area-1) IPLink accumulated {0,1}, then back-propagated
        # area-1 into the single-area link. Relaying only ``direct[nb]`` (the areas
        # ``nb`` genuinely terminates) carries area membership exactly ONE anchored
        # hop — a connector rides an area only when it directly anchors that area or
        # directly rides a connector that terminates it — so a genuine cross-area
        # span still rides both its termination areas (``direct`` of its endpoints),
        # while a single-area connector can never acquire a foreign area conducted
        # *through* a shared neighbour. Crucially the relayed-from neighbour must
        # itself be anchored (``direct[nb]`` non-empty), so an UNANCHORED hub
        # (``VPNService:CUST-*``, a shared transport mesh) is a propagation
        # dead-end — it absorbs its anchored neighbours' direct areas but is never a
        # conduit. Converges in O(area_less * areas) iterations.
        connector_areas: dict[str, set[str]] = {n: set(direct[n]) for n in area_less}
        changed = True
        while changed:
            changed = False
            for n in area_less:
                for nb in self._undirected_neighbors(graph, n):
                    # Relay ONLY through an anchored neighbour (a genuine
                    # conductor) and ONLY that neighbour's DIRECT anchor areas —
                    # never its transitively-accumulated set, never an unanchored hub.
                    nb_direct = direct.get(nb)
                    if nb_direct and not nb_direct <= connector_areas[n]:
                        connector_areas[n] |= nb_direct
                        changed = True
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
        links receives **all** of that group's co-member links plus the group node.
        Because the SRLG co-members are themselves **area-less** link objects,
        adding them never drags a *different* area's area-bearing object
        (Node/Interface) into the trail — so each trail's area-BEARING members stay
        single-area (AC-2 holds) while all co-member links of an SRLG co-appear in
        any trail that touches the group (AC-3). SRLG fate-sharing is a GENUINE
        cross-area relationship (the shared group is the cross-area conductor): a
        group bundling two links in different areas legitimately co-appears in both
        areas' trails as overlap rather than fusing the two areas' area-bearing
        nodes into one whole-network trail (the #225 re-fuse the naive "merge the
        whole sets" union caused). ``areas`` is index-parallel to ``member_sets``
        (kept for context; the link-level union is area-safe by construction). With
        no boundary this is equivalent to the original co-member union.
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
            # All are area-less, so adding them never drags a *different* area's
            # area-bearing Node/Interface into the trail — the trail's area-BEARING
            # members stay single-area (AC-2). SRLG fate-sharing is a GENUINE
            # cross-area relationship: a group bundling two links in different areas
            # legitimately co-appears in both areas' trails as overlap (the shared
            # group is the cross-area conductor), which is why an SRLG-co-member
            # link riding two areas is genuinely — not spuriously — inter-area.
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
