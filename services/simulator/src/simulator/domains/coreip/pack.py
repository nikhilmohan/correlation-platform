"""The Core-IP :class:`DomainPack` implementation (the only pack for the MVP).

Assembles the geo catalogue, alarm shapes, propagation templates, scenario library and noise
classes into a single ``DomainPack``. Declares the domain vocabulary (object types incl.
``Interface``/``Site``; relations incl. ``HOSTS``/``TERMINATES``/``LOCATED_AT``; attribute keys
incl. ``igpArea``) the snapshot is stamped/validated with. The engine depends only on the
``DomainPack`` Protocol — none of this is referenced by name in ``engine/``.
"""

from __future__ import annotations

import random

from simulator.domains.coreip import alarm_shapes, propagation, scenario_library, topology_model
from simulator.domains.coreip.geo_catalogue import GEO_CATALOGUE
from simulator.engine.domain_pack import (
    AlarmShape,
    BuildResult,
    GeoSite,
    NoiseClass,
    PropagationTemplate,
    ScenarioDef,
    TopologyParams,
)

_OBJECT_TYPES = (
    "Node",
    "LineCard",
    "Port",
    "Interface",
    "IPLink",
    "IGPAdjacency",
    "LSP",
    "VPNService",
    "FiberSpan",
    "SRLG",
    "Site",
)

_EDGE_RELATIONS = (
    "LOCATED_AT",
    "HOSTED_ON",
    "HOSTS",
    "TERMINATES",
    "RIDES_ON",
    "ADJACENCY_OVER",
    "TRAVERSES",
    "SERVES",
    "MEMBER_OF",
)

_ATTRIBUTE_KEYS = {
    "device": ("vendor", "model", "equipmentType", "role", "capacity", "igpArea"),
    "connection": ("linkType", "capacity", "protectionRole"),
    "site": ("name", "latitude", "longitude", "region"),
    "interface": ("name", "addressFamily", "role", "igpArea"),
}


class CoreIPPack:
    """Concrete Core-IP domain pack satisfying :class:`DomainPack`."""

    def domain_id(self) -> str:
        return "core-ip"

    def object_types(self) -> tuple[str, ...]:
        return _OBJECT_TYPES

    def edge_relations(self) -> tuple[str, ...]:
        return _EDGE_RELATIONS

    def attribute_keys(self) -> dict[str, tuple[str, ...]]:
        return _ATTRIBUTE_KEYS

    def alarm_type_vocabulary(self) -> tuple[str, ...]:
        return alarm_shapes.ALARM_TYPE_VOCABULARY

    def alarm_shape(self, alarm_type: str) -> AlarmShape:
        return alarm_shapes.alarm_shape(alarm_type)

    def propagation_templates(self) -> tuple[PropagationTemplate, ...]:
        return propagation.PROPAGATION_TEMPLATES

    def scenario_library(self) -> tuple[ScenarioDef, ...]:
        return scenario_library.SCENARIO_LIBRARY

    def noise_classes(self) -> tuple[NoiseClass, ...]:
        return scenario_library.NOISE_CLASSES

    def geo_sites(self) -> tuple[GeoSite, ...]:
        return GEO_CATALOGUE

    def build_topology(self, params: TopologyParams, rng: random.Random) -> BuildResult:
        return topology_model.build_topology(params, rng, GEO_CATALOGUE)
