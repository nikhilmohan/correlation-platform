"""P3 compatible-trail discovery (spec Task 21, AC 47-50, 58; design §A).

For each approved pattern, enumerate the deployed trails via Trail Builder ``GET /trails`` (paged,
the existing published list endpoint) and apply the **hostability rule**: a trail is compatible
with pattern P iff it hosts >=1 member of every ``objectType`` P requires (root included). Required
object types are derived the SAME way the Correlation Engine derives them — the distinct set of
``PatternView.sampleAlarms[].managedObjectId`` prefixes (the real objects P manifested on) — so the
simulator and CE agree on "required" (see ``_required_types``); the pack affinity table is only a
fallback for patterns with no sampleAlarms. Candidate trail members are fetched via ``get_trail``
**memoized** in a
per-run fetch cache so each trailId is fetched at most once across all patterns (AC 48). Results are
cached in the P3 config snapshot by the caller (``p3_fetch``); a second run loading that snapshot
performs zero list/detail fetches (AC 48). When network-wide is off this module is never called
(AC 58).
"""

from __future__ import annotations

import logging

from simulator.engine.domain_pack import DomainPack
from simulator.integrations import trail_builder_client
from simulator.obs import metrics
from simulator.obs.logging import get_logger, log_event
from simulator.synth.models import CompatibleTrail, CompatibleTrailSet, PatternView, TrailDetail

_log = get_logger("simulator.synth.trail_discovery")

_PAGE_LIMIT = 200


def _enumerate_trails(
    trail_client, snapshot_id: str, domain: str, *, page_limit: int = _PAGE_LIMIT
) -> list:
    """Page ``list_trails`` until a short page returns; return the flat TrailSummary list."""
    summaries: list = []
    offset = 0
    while True:
        page = trail_client.list_trails(snapshot_id, domain, limit=page_limit, offset=offset)
        summaries.extend(page)
        if len(page) < page_limit:
            break
        offset += page_limit
    return summaries


def _memoized_get_trail(
    trail_client, trail_id: str, cache: dict[str, TrailDetail | None]
) -> TrailDetail | None:
    """Fetch a trail's members via ``get_trail``, memoized so each id is fetched at most once.

    A 404 is cached as ``None`` (not compatible, logged once). Non-404 fetch errors propagate to the
    caller's bounded-retry error handling.
    """
    if trail_id in cache:
        return cache[trail_id]
    result = trail_client.get_trail(trail_id)
    if isinstance(result, trail_builder_client.TrailNotFound):
        log_event(
            _log,
            logging.INFO,
            "p3.trail_not_found",
            f"trail {trail_id} not found (404) during discovery; treated as not compatible",
            trailId=trail_id,
        )
        cache[trail_id] = None
        return None
    cache[trail_id] = result
    return result


def _hosts_all(trail: TrailDetail, required: set[str]) -> bool:
    """True iff ``trail`` hosts >=1 member of every required objectType (hostability rule)."""
    present = {m.object_type for m in trail.members}
    return required.issubset(present)


def discover_compatible_trails(
    pack: DomainPack,
    patterns: list[PatternView],
    trail_client,
    snapshot_id: str,
    domain: str,
    discovery_areas: dict[str, str | None],
    *,
    fetch_cache: dict[str, TrailDetail | None] | None = None,
) -> dict[str, CompatibleTrailSet]:
    """Discover each pattern's compatible-trail set (grouped by area, non-discovery areas first).

    ``discovery_areas`` maps ``patternId -> igpArea`` of that pattern's own discovery trail (so the
    plan can prefer *different* areas). The returned map is keyed by ``patternId``. Trails are
    ordered grouped-by-area (non-discovery areas first), and within an area by ``trailId`` — a
    deterministic, cacheable order; the seeded shuffle is a plan-time concern (AC 57), not here.
    """
    affinity = pack.placement_affinity()
    cache: dict[str, TrailDetail | None] = fetch_cache if fetch_cache is not None else {}
    summaries = _enumerate_trails(trail_client, snapshot_id, domain)
    # Include each pattern's own discovery trail as a candidate even if the list omitted it.
    summary_ids = {s.trail_id for s in summaries}
    area_by_id: dict[str, str | None] = {s.trail_id: s.igp_area for s in summaries}

    result: dict[str, CompatibleTrailSet] = {}
    for pattern in patterns:
        required = _required_types(pack, pattern, affinity)
        candidate_ids = list(dict.fromkeys([*summary_ids, pattern.trail_id]))
        compatible: list[CompatibleTrail] = []
        for trail_id in candidate_ids:
            detail = _memoized_get_trail(trail_client, trail_id, cache)
            if detail is None:
                continue
            if _hosts_all(detail, required):
                area = detail.igp_area if detail.igp_area is not None else area_by_id.get(trail_id)
                compatible.append(CompatibleTrail(trail_id=trail_id, igp_area=area))

        discovery_area = discovery_areas.get(pattern.pattern_id)
        ordered = _order_by_area(compatible, discovery_area)
        _log_spread(pattern.pattern_id, ordered)
        metrics.P3_COMPATIBLE_TRAILS.labels(patternId=pattern.pattern_id).set(len(ordered))
        result[pattern.pattern_id] = CompatibleTrailSet(
            pattern_id=pattern.pattern_id,
            discovery_area=discovery_area,
            trails=tuple(ordered),
        )
    return result


def _required_types(pack: DomainPack, pattern: PatternView, affinity) -> set[str]:
    """The objectTypes a trail must host to be compatible with ``pattern`` (hostability rule input).

    Derived the SAME way the Correlation Engine derives a pattern's required objectTypes: the
    distinct set of ``managedObjectId`` prefixes (``<objectType>:<id>``) across the pattern's
    ``sampleAlarms`` — the real objects the pattern actually manifested on. This is authoritative
    and replaces the previous theoretical alarmType->objectType affinity derivation, which
    disagreed with where the pattern's alarms really land (e.g. QueueDrop's affinity wrongly
    required ``VPNService`` though its real sampleAlarms sit on ``{FiberSpan, Interface, Port}``,
    yielding 0 compatible trails).

    Fallback (only when a pattern carries NO sampleAlarms, e.g. older mined patterns): mirror CE's
    fallback and derive from the pack affinity table over the sequence + root alarmTypes.
    """
    prefixes = {s.object_type for s in pattern.sample_alarms if s.object_type}
    if prefixes:
        return prefixes

    from simulator.domains.coreip import p3_placement

    return p3_placement.required_object_types(
        (e.alarm_type for e in pattern.sequence),
        pattern.root_cause_alarm_type,
        affinity,
    )


def _order_by_area(
    compatible: list[CompatibleTrail], discovery_area: str | None
) -> list[CompatibleTrail]:
    """Group by igp-area (non-discovery areas first), each group ordered by trailId (AC 49)."""
    by_area: dict[str | None, list[CompatibleTrail]] = {}
    for ct in compatible:
        by_area.setdefault(ct.igp_area, []).append(ct)
    # Areas other than the discovery area first; a stable, cacheable order within/between areas.
    non_discovery = sorted(
        (a for a in by_area if a != discovery_area), key=lambda a: (a is None, a or "")
    )
    ordered_areas: list[str | None] = list(non_discovery)
    if discovery_area in by_area:
        ordered_areas.append(discovery_area)
    out: list[CompatibleTrail] = []
    for area in ordered_areas:
        out.extend(sorted(by_area[area], key=lambda ct: ct.trail_id))
    return out


def _log_spread(pattern_id: str, ordered: list[CompatibleTrail]) -> None:
    """Log same-area / single-trail fallbacks (design §A step 6; OQ-NW-2). Never aborts."""
    if not ordered:
        log_event(
            _log,
            logging.WARNING,
            "p3.no_compatible_trails",
            f"pattern {pattern_id} has zero compatible trails; it contributes no aligned cascade",
            patternId=pattern_id,
        )
        return
    areas = {ct.igp_area for ct in ordered}
    if len(ordered) == 1:
        log_event(
            _log,
            logging.INFO,
            "p3.single_compatible_trail",
            f"pattern {pattern_id} has a single compatible trail; multi-trail spread unavailable",
            patternId=pattern_id,
            trailId=ordered[0].trail_id,
        )
    elif len(areas) == 1:
        log_event(
            _log,
            logging.INFO,
            "p3.area_spread_unavailable",
            f"pattern {pattern_id} compatible trails share one igp-area; area spread unavailable",
            patternId=pattern_id,
            igpArea=next(iter(areas)),
            trails=len(ordered),
        )
