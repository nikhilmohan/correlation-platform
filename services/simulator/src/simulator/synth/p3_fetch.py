"""P3 fetch + validate deployed state (spec Task 13, AC 32, 33, 44).

Orchestrates the three config-switchable read clients to assemble a :class:`P3ConfigSnapshot`:
  1. Pattern Manager ``GET /patterns?lifecycle=approved`` -> ``PatternView[]``. Empty -> fail fast.
  2. Trail Builder ``GET /trails/{trailId}`` per *distinct* trailId (dedup). A 404 warns + drops
     the patterns on that trail; continue if >=1 pattern still has a trail, else fail fast.
  3. Topology ``GET /topology/snapshots`` -> ``SnapshotSummary[]`` (provenance).
Each client is mock/real switchable; no hard-coded URL. Fail-fast happens *before* any emission.
"""

from __future__ import annotations

import logging

from simulator.config.settings import Settings
from simulator.domains.coreip.pack import CoreIPPack
from simulator.engine.domain_pack import DomainPack
from simulator.integrations import (
    pattern_manager_client,
    topology_snapshot_client,
    trail_builder_client,
)
from simulator.obs.logging import get_logger, log_event
from simulator.synth import trail_discovery
from simulator.synth.models import PatternView
from simulator.synth.p3_config_snapshot import P3ConfigSnapshot

_log = get_logger("simulator.synth.p3_fetch")


class P3FetchError(RuntimeError):
    """Raised when the deployed state is empty or inconsistent (fail-fast, exit before emission)."""


def fetch_config(
    settings: Settings,
    *,
    pattern_client=None,
    trail_client=None,
    snapshot_client=None,
    pack: DomainPack | None = None,
) -> P3ConfigSnapshot:
    """Fetch approved patterns + their trail members + snapshot list into a P3 config snapshot."""
    pattern_client = pattern_client or pattern_manager_client.make_client(
        settings.pattern_manager_api_mode, settings.pattern_manager_api_base_url
    )
    trail_client = trail_client or trail_builder_client.make_client(
        settings.trail_builder_api_mode, settings.trail_builder_api_base_url
    )
    snapshot_client = snapshot_client or topology_snapshot_client.make_client(
        settings.topology_api_mode, settings.topology_api_base_url
    )

    patterns = pattern_client.list_approved()
    if not patterns:
        log_event(
            _log,
            logging.ERROR,
            "p3.no_approved_patterns",
            "no approved patterns returned by Pattern Manager; nothing to synthesize",
        )
        raise P3FetchError("no approved patterns available")

    # Dedup trailIds across patterns -> exactly one GET /trails/{trailId} per distinct id (AC 33).
    distinct_trail_ids = _distinct_preserving_order(p.trail_id for p in patterns)
    trails = {}
    dropped_trail_ids: set[str] = set()
    for trail_id in distinct_trail_ids:
        result = trail_client.get_trail(trail_id)
        if isinstance(result, trail_builder_client.TrailNotFound):
            log_event(
                _log,
                logging.WARNING,
                "p3.trail_not_found",
                f"trail {trail_id} not found (404); dropping its patterns from synthesis",
                trailId=trail_id,
            )
            dropped_trail_ids.add(trail_id)
            continue
        trails[trail_id] = result

    usable: list[PatternView] = [p for p in patterns if p.trail_id in trails]
    if not usable:
        log_event(
            _log,
            logging.ERROR,
            "p3.no_approved_patterns",
            "every approved pattern's trail was missing (404); nothing to synthesize",
        )
        raise P3FetchError("no approved pattern has a resolvable trail")

    snapshots = snapshot_client.list_snapshots(settings.synth_domain)

    config = P3ConfigSnapshot(
        domain=settings.synth_domain,
        patterns=usable,
        trails=trails,
        source_snapshots=snapshots,
    )

    # Network-wide (Task 21, AC 47-49): discover + cache compatible trails per pattern. Off ->
    # this block is skipped entirely, so NO GET /trails?... list call is made (AC 58).
    if settings.p3_network_wide_active:
        _attach_compatible_trails(
            settings, config, usable, trails, snapshots, trail_client, pack or CoreIPPack()
        )

    return config


def _resolve_snapshot_id(config_snapshots, trails) -> str:
    """The snapshotId to enumerate trails against (a trail's snapshotId, else a listed snapshot)."""
    for trail in trails.values():
        if trail.snapshot_id:
            return trail.snapshot_id
    if config_snapshots:
        return config_snapshots[0].snapshot_id
    return ""


def _attach_compatible_trails(
    settings: Settings,
    config: P3ConfigSnapshot,
    patterns: list[PatternView],
    trails,
    snapshots,
    trail_client,
    pack: DomainPack,
) -> None:
    """Run compatible-trail discovery and cache the result on the config snapshot (AC 48)."""
    snapshot_id = _resolve_snapshot_id(snapshots, trails)
    discovery_areas = {
        p.pattern_id: (trails[p.trail_id].igp_area if p.trail_id in trails else None)
        for p in patterns
    }
    # Seed the discovery fetch-cache with the already-fetched discovery trails so their members are
    # NOT re-fetched (memoized, AC 48).
    fetch_cache: dict = {trail_id: detail for trail_id, detail in trails.items()}
    config.compatible_trails = trail_discovery.discover_compatible_trails(
        pack,
        patterns,
        trail_client,
        snapshot_id,
        settings.synth_domain,
        discovery_areas,
        fetch_cache=fetch_cache,
    )
    # Make every discovered compatible trail's members available at emit time (each element is
    # placed on a member of its assigned trail, AC 50). The fetch-cache already holds them (each
    # fetched at most once, AC 48); merge the successful fetches into the config's trail map.
    for trail_id, detail in fetch_cache.items():
        if detail is not None:
            config.trails.setdefault(trail_id, detail)


def _distinct_preserving_order(values) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for v in values:
        if v not in seen:
            seen.add(v)
            out.append(v)
    return out
