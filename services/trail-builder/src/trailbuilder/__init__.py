"""Trail Builder Service — policy-bounded correlation trails.

Builds overlapping, IGP-area-bounded correlation trails from the Topology graph
(read via the Topology Service query API) using the domain trail policy authored
in the Knowledge Service; persists them in PostgreSQL (schema ``trailbuilder``);
serves the trail-query API; and emits ``trails.built``.
"""

__version__ = "0.1.0"
