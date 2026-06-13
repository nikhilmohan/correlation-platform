"""Internal value objects: the trail policy, the topology graph slice, and a trail.

These are the domain types the closure algorithm and repository operate on. They
are distinct from the FastAPI request/response models (``api_models``) and from
the frozen ``acp_event_model`` payloads.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from acp_event_model import ManagedObjectId


@dataclass(frozen=True)
class Boundary:
    """Trail-policy closure boundary (e.g. IGP area)."""

    type: str  # "igp-area" | "none"
    attribute_key: str | None = None


@dataclass(frozen=True)
class SrlgRule:
    """Trail-policy SRLG-union rule."""

    mode: str  # "union-members" | "none"
    srlg_edge_type: str | None = None


@dataclass(frozen=True)
class TrailPolicy:
    """Domain-scoped trail policy fetched from the Knowledge Service.

    Nothing here is hard-coded in the service; every field is read from the
    Knowledge ``trailPolicy`` record for the build's domain.
    """

    closure_edge_types: tuple[str, ...]
    boundary: Boundary
    srlg_rule: SrlgRule


@dataclass(frozen=True)
class GraphNode:
    """A topology node in the per-snapshot graph slice."""

    managed_object_id: str
    object_type: str
    attributes: dict[str, object] = field(default_factory=dict)

    def igp_area(self, key: str | None) -> str | None:
        """Return this node's IGP-area attribute under ``key`` (or ``None``)."""
        if not key:
            return None
        value = self.attributes.get(key)
        return None if value is None else str(value)


@dataclass(frozen=True)
class GraphEdge:
    """A directed topology edge in the per-snapshot graph slice."""

    src: str
    dst: str
    relation: str


@dataclass
class GraphSlice:
    """The domain-/snapshot-scoped graph slice assembled from Topology responses."""

    domain: str
    snapshot_id: str
    nodes: dict[str, GraphNode] = field(default_factory=dict)
    edges: list[GraphEdge] = field(default_factory=list)

    def add_node(self, node: GraphNode) -> None:
        self.nodes.setdefault(node.managed_object_id, node)

    def add_edge(self, edge: GraphEdge) -> None:
        self.edges.append(edge)


@dataclass(frozen=True)
class Trail:
    """A computed trail: its member set plus seed/bounds context."""

    trail_id: str
    domain: str
    snapshot_id: str
    seed_managed_object_id: str
    members: tuple[str, ...]
    igp_area: str | None = None
    srlg_group: str | None = None
    # When members are not loaded (listTrails summary), the persisted count is
    # carried here so callers do not need the full member list.
    persisted_member_count: int | None = None

    @property
    def member_count(self) -> int:
        if self.members:
            return len(self.members)
        return self.persisted_member_count or 0


def object_type_of(managed_object_id: str) -> str:
    """Parse the ``<objectType>`` prefix of a typed ``managedObjectId``."""
    return ManagedObjectId.parse(managed_object_id).object_type
