"""Feature vectorization for DBSCAN.

Base feature row per alarm (design "Feature vector per alarm"):
  1. Relative timestamp (``raisedAt - windowStart`` seconds) — the PRIMARY storm-density signal.
  2. Object-type layer — ordinal of the ``objectType`` prefix parsed from ``managedObjectId``.
  3. Alarm type — encoding of ``eventType``.
  4. Severity — ordinal of ``perceivedSeverity`` (X.733 ordering).
  5. Optional attribute dims — one per enabled attribute key (Topology ``attributes`` map).
  6. Optional ONE soft hop-distance dim — when the hop-distance feature is enabled.

Features share a single fixed-scale encoding so ``eps`` is meaningful: the relative timestamp is
divided by the Knowledge-sourced ``timeScaleSeconds`` and the categorical/ordinal dims are scaled
by the Knowledge-sourced ``categoricalWeight`` (both co-tuned WITH ``eps`` in the feature-config
record — see :class:`FeatureSettings`). The relative timestamp dominates so temporally tight
storms collapse to one dense cluster, while the categorical dims only nudge density. Encoding is
deterministic for a fixed input + fixed config (reproducibility requirement). NOTE: this is a
fixed-scale (not per-window z-score) encoding; the scale lives in config, not in code literals.
"""

from __future__ import annotations

import numpy as np
from acp_event_model import AlarmEvent

from .clients import TopologyClient, TrailBuilderClient, TrailContext
from .config import FeatureSettings
from .logging_setup import get_logger

log = get_logger(__name__)

# X.733 perceived-severity ordering (ordinal). Unknown -> 0.
_SEVERITY_ORDER = {
    "cleared": 0,
    "indeterminate": 1,
    "warning": 2,
    "minor": 3,
    "major": 4,
    "critical": 5,
}


def object_type_of(managed_object_id: str) -> str:
    """Parse the ``objectType`` prefix from a ``<objectType>:<id>`` managedObjectId."""
    return managed_object_id.split(":", 1)[0]


def _stable_ordinal(value: str, vocabulary: list[str]) -> int:
    """Deterministic ordinal of ``value`` within the (sorted) observed vocabulary."""
    if value not in vocabulary:
        vocabulary.append(value)
        vocabulary.sort()
    return vocabulary.index(value)


def _severity_ordinal(severity: str) -> int:
    return _SEVERITY_ORDER.get(severity.lower(), 1)


class HopDistanceResolver:
    """Resolves the soft hop-distance dimension from the trail seed (DA-10, OQ #7).

    Strictly SOFT: it nudges cluster density only and NEVER drops/gates an alarm. Nodes beyond
    ``hopTraversalMaxDepth`` or unreachable from the seed get the bound value (kept, not dropped).
    """

    def __init__(
        self,
        trail_builder_client: TrailBuilderClient,
        *,
        fault_origin_ids: frozenset[str] = frozenset(),
    ) -> None:
        self._client = trail_builder_client
        self._fault_origin_ids = fault_origin_ids

    def resolve_seed(self, ctx: TrailContext) -> str | None:
        """Seed preference: (a) explicit seed/root field; (b) Knowledge fault-origin member;
        (c) topological DAG root (a member with no incoming dependency edge)."""
        if ctx.seed_id:
            return ctx.seed_id
        for mid in ctx.member_ids:
            if mid in self._fault_origin_ids:
                return mid
        with_incoming = {dst for _src, dst in ctx.edges}
        roots = [m for m in ctx.member_ids if m not in with_incoming]
        return roots[0] if roots else (ctx.member_ids[0] if ctx.member_ids else None)

    def hop_distances(self, ctx: TrailContext, *, max_depth: int) -> dict[str, int]:
        """Bounded BFS hop-distance from the seed along dependency edges, per managedObjectId."""
        seed = self.resolve_seed(ctx)
        if seed is None:
            return {}
        adjacency: dict[str, list[str]] = {}
        for src, dst in ctx.edges:
            adjacency.setdefault(src, []).append(dst)
            # Treat dependency edges as traversable both ways for hop-distance proximity.
            adjacency.setdefault(dst, []).append(src)
        dist: dict[str, int] = {seed: 0}
        frontier = [seed]
        depth = 0
        while frontier and depth < max_depth:
            depth += 1
            nxt: list[str] = []
            for node in frontier:
                for neigh in adjacency.get(node, []):
                    if neigh not in dist:
                        dist[neigh] = depth
                        nxt.append(neigh)
            frontier = nxt
        return dist


class FeatureVectorizer:
    """Builds the fixed-scale feature matrix for a finalized window (scales from feature config)."""

    def __init__(
        self,
        *,
        topology_client: TopologyClient | None = None,
        hop_resolver: HopDistanceResolver | None = None,
        metrics=None,
    ) -> None:
        self._topology = topology_client
        self._hop_resolver = hop_resolver
        self._metrics = metrics

    def build_matrix(
        self,
        alarms: list[AlarmEvent],
        *,
        window_start,
        features: FeatureSettings,
        trail_ctx: TrailContext | None = None,
    ) -> np.ndarray:
        """Return an (n_alarms, n_dims) fixed-scale feature matrix.

        Scales (``time_scale_seconds`` / ``categorical_weight``) come from the Knowledge-sourced
        ``features`` (co-tuned with ``eps``). Topology is called only when
        ``features.attribute_keys`` is non-empty; the hop dimension is added only when
        ``features.hop_distance_enabled``. Degradations (Topology / Trail Builder unavailable) skip
        the affected dimension but NEVER drop an alarm (EH-5, EH-12).
        """
        n = len(alarms)
        ws_epoch = window_start.timestamp()
        time_scale = features.time_scale_seconds
        cat_weight = features.categorical_weight

        # 1. Relative timestamp (PRIMARY) — continuous, divided by the configured time scale.
        rel_ts = np.array(
            [(a.raisedAt.timestamp() - ws_epoch) / time_scale for a in alarms],
            dtype=float,
        )

        # 2-4. Categorical/ordinal dims — configured weight so they nudge, not dominate.
        obj_vocab: list[str] = []
        obj_layer = (
            np.array(
                [_stable_ordinal(object_type_of(a.managedObjectId), obj_vocab) for a in alarms],
                dtype=float,
            )
            * cat_weight
        )

        et_vocab: list[str] = []
        event_type = (
            np.array([_stable_ordinal(a.eventType, et_vocab) for a in alarms], dtype=float)
            * cat_weight
        )

        severity = (
            np.array([_severity_ordinal(a.perceivedSeverity) for a in alarms], dtype=float)
            * cat_weight
        )

        columns: list[np.ndarray] = [rel_ts, obj_layer, event_type, severity]

        # 5. Optional attribute dimensions (one per enabled key) — categorical-weighted.
        if features.attribute_keys and self._topology is not None:
            attr_cache: dict[str, dict] = {}
            for key in features.attribute_keys:
                key_vocab: list[str] = []
                col = np.zeros(n, dtype=float)
                for i, a in enumerate(alarms):
                    attrs = self._fetch_attributes(a.managedObjectId, attr_cache)
                    val = attrs.get(key)
                    col[i] = _stable_ordinal(str(val), key_vocab) if val is not None else -1.0
                columns.append(col * cat_weight)

        # 6. Optional ONE soft hop-distance dimension — categorical-weighted (never a hard gate).
        if (
            features.hop_distance_enabled
            and self._hop_resolver is not None
            and trail_ctx is not None
        ):
            try:
                dists = self._hop_resolver.hop_distances(
                    trail_ctx, max_depth=features.hop_traversal_max_depth
                )
                bound = float(features.hop_traversal_max_depth)
                hop_col = np.array(
                    [float(dists.get(a.managedObjectId, bound)) for a in alarms], dtype=float
                )
                columns.append(hop_col * cat_weight)
            except Exception as exc:  # noqa: BLE001 — degrade, never drop (EH-12)
                if self._metrics is not None:
                    self._metrics.hop_feature_skip.inc()
                log.warning("hop_feature_skip", error=str(exc))

        return np.column_stack(columns)

    def _fetch_attributes(self, managed_object_id: str, cache: dict[str, dict]) -> dict:
        if managed_object_id in cache:
            return cache[managed_object_id]
        try:
            attrs = self._topology.fetch_attributes(managed_object_id)  # type: ignore[union-attr]
        except Exception as exc:  # noqa: BLE001 — degrade, never drop (EH-5)
            if self._metrics is not None:
                self._metrics.topology_attr_skip.inc()
            log.warning("topology_attr_skip", managedObjectId=managed_object_id, error=str(exc))
            attrs = {}
        cache[managed_object_id] = attrs
        return attrs
