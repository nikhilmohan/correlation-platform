"""Domain-agnostic topology builder (criterion 19).

Delegates the actual layered construction to the active :class:`DomainPack`; this module
never names a domain object type, relation, or attribute key — it only threads config + the
seeded RNG into ``pack.build_topology`` and returns the typed graph. The "no Core-IP literals
in engine" criterion is enforced by a static scan of this package.
"""

from __future__ import annotations

import random

from simulator.engine.domain_pack import BuildResult, DomainPack, TopologyParams


def build_topology(pack: DomainPack, params: TopologyParams, rng: random.Random) -> BuildResult:
    """Ask the active domain pack to build its typed multi-layer topology graph."""
    result = pack.build_topology(params, rng)
    # Engine-level invariant (domain-agnostic): every node carries an objectType + attributes.
    for _, data in result.graph.nodes(data=True):
        if "objectType" not in data:
            raise ValueError("domain pack produced a node without an objectType")
        data.setdefault("attributes", {})
    for _, _, data in result.graph.edges(data=True):
        if "relation" not in data:
            raise ValueError("domain pack produced an edge without a relation")
    return result
