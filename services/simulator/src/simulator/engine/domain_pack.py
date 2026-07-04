"""The :class:`DomainPack` Protocol — the engine↔domain boundary (spec Task 8 / criterion 19).

The engine is *domain-agnostic*: it never names a Core-IP object type, relation, alarm token,
template, or scenario. It resolves all such domain values through this Protocol. The only
concrete implementation for the MVP is :mod:`simulator.domains.coreip`. Adding a new domain is
adding a new ``DomainPack`` implementation — no engine edit.

The dataclasses below (`AlarmShape`, `PropagationTemplate`, `ScenarioDef`, `NoiseClass`,
`GeoSite`) are *generic carriers* of domain data — they hold no Core-IP literal, only the
shape the engine consumes. The Core-IP values that fill them live in the pack.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

import networkx as nx


@dataclass(frozen=True)
class AlarmShape:
    """The full shape for one canonical ``alarmType`` token.

    ``alarm_type`` is the canonical join key (set on ``AlarmEvent.alarmType``); the X.733
    ``event_type``/``probable_cause``/``perceived_severity`` are distinct token spaces.
    """

    alarm_type: str
    event_type: str
    probable_cause: str
    perceived_severity: str


@dataclass(frozen=True)
class PropagationTemplate:
    """One §5 propagation record: hopping an ``edge_relation`` emits ``effect_alarm_type``.

    Multiple records may share one ``edge_relation`` and emit different effect tokens, so a
    single hop contributes several distinct effect types (what lets one cascade span 10-20
    types). ``fanout`` ``each-target`` applies to every reachable target on the relation;
    ``co-failure-group`` expands SRLG fate-sharing.
    """

    edge_relation: str
    effect_alarm_type: str
    fanout: str = "each-target"  # "each-target" | "co-failure-group"


@dataclass(frozen=True)
class ScenarioDef:
    """A grounded fault scenario: a fault-origin object type + the relations its cascade walks."""

    scenario_type: str
    fault_origin_type: str
    root_alarm_type: str
    # Edge relations (in walk priority) the cascade follows for this scenario. When None the
    # cascade uses every relation that has a propagation template.
    relations: tuple[str, ...] | None = None
    # Co-failure relation that fate-shares the root (SRLG): expand before propagating onward.
    co_failure_relation: str | None = None


@dataclass(frozen=True)
class NoiseClass:
    """A background-noise behaviour class (≥3 per pack)."""

    name: str
    # Candidate alarm-type tokens this class emits (a subset of the vocabulary).
    alarm_types: tuple[str, ...]
    # If True a single occurrence emits a raised+cleared pair (flapping/transient).
    self_clearing: bool = False


@dataclass(frozen=True)
class GeoSite:
    """A grounded telco-PoP geo site (distinct coordinates)."""

    site_id: str
    name: str
    latitude: float
    longitude: float
    region: str


@dataclass
class BuildResult:
    """What a pack returns from :meth:`DomainPack.build_topology`.

    ``graph`` is a ``networkx.DiGraph`` whose nodes carry ``objectType``/``attributes`` and
    whose edges carry ``relation``/``attributes``. ``sites`` records the placed geo sites and
    ``igp_areas`` the distinct area tokens, for metrics/assertions.
    """

    graph: nx.DiGraph
    sites: list[GeoSite] = field(default_factory=list)
    igp_areas: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class TopologyParams:
    """Topology-shaping knobs the engine passes to the pack (all from config)."""

    node_count: int
    site_count: int
    interfaces_per_port: int
    igp_area_count: int
    devices_per_site: int | None = None


@runtime_checkable
class DomainPack(Protocol):
    """The swappable domain interface the engine depends on (and nothing else)."""

    def domain_id(self) -> str:
        """Domain identifier stamped on the snapshot (e.g. ``core-ip``)."""
        ...

    def object_types(self) -> tuple[str, ...]:
        """The domain's object-type vocabulary (incl. ``Site``/``Interface``)."""
        ...

    def edge_relations(self) -> tuple[str, ...]:
        """The domain's edge-relation vocabulary (incl. ``LOCATED_AT``/``HOSTS``/``TERMINATES``)."""
        ...

    def attribute_keys(self) -> dict[str, tuple[str, ...]]:
        """Well-known ``attributes`` keys per category (``device``/``connection``/``site``)."""
        ...

    def alarm_type_vocabulary(self) -> tuple[str, ...]:
        """The canonical ``alarmType`` token set the pack emits (⊆ Knowledge vocabulary)."""
        ...

    def alarm_shape(self, alarm_type: str) -> AlarmShape:
        """Return the full shape (join token + X.733 fields) for one ``alarmType``."""
        ...

    def propagation_templates(self) -> tuple[PropagationTemplate, ...]:
        """The §5 propagation records (per edge relation → effect ``alarmType``)."""
        ...

    def scenario_library(self) -> tuple[ScenarioDef, ...]:
        """The grounded fault scenarios."""
        ...

    def noise_classes(self) -> tuple[NoiseClass, ...]:
        """The noise behaviour classes (≥3)."""
        ...

    def geo_sites(self) -> tuple[GeoSite, ...]:
        """The grounded geo-site catalogue (≥10 distinct entries)."""
        ...

    def placement_affinity(self) -> dict[str, str]:
        """P3 synthesis: map each canonical ``alarmType`` to its affine ``objectType``.

        Pack-authored (OQ-P3-1); the engine/``synth`` stay domain-generic and read the mapping
        only through this Protocol method. Used to place a pattern sequence element onto a real
        trail member of the affine object type (with a fallback to any trail member).
        """
        ...

    def build_topology(self, params: TopologyParams, rng: random.Random) -> BuildResult:
        """Populate a typed multi-layer ``networkx`` graph for the domain."""
        ...
