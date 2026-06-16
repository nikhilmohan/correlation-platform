"""``BuildService`` — orchestrates a domain-scoped trail build.

Shared by the Kafka ``topology.changed`` path and the ``POST /trails/rebuild``
path: fetch the domain trail policy, fetch the domain-/snapshot-scoped graph
slice, compute the closure, persist (supersede + prune), and emit
``trails.built``. The ``snapshotId`` is always the in-scope snapshot carried by
the trigger — never re-resolved from Topology.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

from .clients.errors import IntegrationError
from .clients.policy_client import KnowledgePolicyClient
from .clients.topology_client import TopologyClient
from .closure import TrailClosure
from .config import Settings
from .event_publisher import TrailsBuiltPublisher
from .models import TrailPolicy
from .observability import (
    BUILD_DURATION,
    BUILD_FAILURES_TOTAL,
    BUILDS_TOTAL,
    TRAIL_DISTINCT_IGP_AREAS,
    TRAILS_BUILT,
    get_logger,
)
from .repository import TrailRepository

_log = get_logger("trailbuilder.build")

# The fault-capable seed object types per the Core IP §5 model. These name the
# object types whose nodes seed the closure; they are not policy thresholds.
# This is a correctness constraint, NOT merely an optimisation hint: seeding from
# every node type is NOT a harmless superset. A pure risk-GROUP node (SRLG) has no
# closure-edge neighbours, so seeding from it yields a degenerate 1-member
# ``SRLG:*``-only trail that survives dedup (no real trail equals it). The closure
# therefore excludes risk-group object types (``closure.RISK_GROUP_OBJECT_TYPES``)
# from the seed loop; an SRLG still co-trails as a MEMBER via the SRLG union.
SEED_OBJECT_TYPES: tuple[str, ...] = (
    "Node",
    "LineCard",
    "Port",
    "Interface",
    "IPLink",
    "FiberSpan",
)


@dataclass
class BuildResult:
    """The outcome of a successful build."""

    snapshot_id: str
    domain: str
    trail_ids: list[str]

    @property
    def trail_count(self) -> int:
        return len(self.trail_ids)


class BuildService:
    """Domain-scoped trail build pipeline."""

    def __init__(
        self,
        settings: Settings,
        topology: TopologyClient,
        policy: KnowledgePolicyClient,
        repository: TrailRepository,
        closure: TrailClosure,
        publisher: TrailsBuiltPublisher,
    ) -> None:
        self._settings = settings
        self._topology = topology
        self._policy = policy
        self._repo = repository
        self._closure = closure
        self._publisher = publisher

    def build(self, snapshot_id: str, domain: str, trace_id: str, emit: bool = True) -> BuildResult:
        """Run a full build for ``(snapshot_id, domain)``.

        Raises:
            IntegrationError: a Topology/Knowledge dependency failed; the build
                is held (the caller must NOT mark the event processed).
        """
        BUILDS_TOTAL.labels(domain=domain).inc()
        start = time.perf_counter()
        try:
            trail_policy = self._policy.get_policy(domain)
            slice_ = self._topology.fetch_slice(
                domain=domain,
                snapshot_scope=self._settings.topology_snapshot_scope,
                seed_object_types=list(SEED_OBJECT_TYPES),
                closure_relations=list(trail_policy.closure_edge_types),
                srlg_edge_type=trail_policy.srlg_rule.srlg_edge_type,
            )
            # The persisted/emitted snapshotId is the in-scope event snapshot.
            slice_.snapshot_id = snapshot_id
            trails = self._closure.compute(slice_, trail_policy)
            self._repo.persist_build(domain, snapshot_id, trails)
        except IntegrationError as exc:
            BUILD_FAILURES_TOTAL.labels(reason=exc.reason).inc()
            _log.error(
                "build held: dependency error",
                extra={
                    "traceId": trace_id,
                    "snapshotId": snapshot_id,
                    "domain": domain,
                    "reason": exc.reason,
                },
            )
            raise
        finally:
            BUILD_DURATION.observe(time.perf_counter() - start)

        TRAILS_BUILT.labels(domain=domain).inc(len(trails))
        self._record_igp_metric(domain, trails)
        trail_ids = [t.trail_id for t in trails]

        if emit:
            self._publisher.emit(snapshot_id, domain, trail_ids, trace_id)

        _log.info(
            "build complete",
            extra={
                "traceId": trace_id,
                "snapshotId": snapshot_id,
                "domain": domain,
                "trailCount": len(trail_ids),
            },
        )
        return BuildResult(snapshot_id=snapshot_id, domain=domain, trail_ids=trail_ids)

    @staticmethod
    def _record_igp_metric(domain: str, trails: list) -> None:  # type: ignore[type-arg]
        areas = {t.igp_area for t in trails if t.igp_area is not None}
        TRAIL_DISTINCT_IGP_AREAS.labels(domain=domain).set(len(areas))

    @staticmethod
    def _resolve_policy(policy_client: KnowledgePolicyClient, domain: str) -> TrailPolicy:
        return policy_client.get_policy(domain)
