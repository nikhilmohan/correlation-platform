"""P3 closed-loop target controller (spec Task 22, AC 51/52/55/56; design §B, §C, §D).

Given the CE-measured post-enrichment target ``P3_AUTO_CORRELATION_TARGET`` and ``P3_TOTAL_ALARMS``,
compute how many complete enrichment-safe aligned cascades to emit so the expected CE rate
``enrichmentSafeCount / T`` lands within ``P3_TARGET_TOLERANCE`` of the target (§B), then distribute
those cascades across each pattern's compatible trails (distinct igp-areas first, fill to the
per-trail cap, then staggered trail-repeats — §C). Patterns whose sessionWindow conflicts with the
enrichment dedup window are excluded (§D, AC 62). Shortfall (when caps make the target
arithmetically unreachable) is recorded, never silent (AC 55). The plan is a flat, seeded list of
``PlanEntry`` (AC 57). This module is pure planning — no I/O, no emission.
"""

from __future__ import annotations

import logging
import random
from dataclasses import dataclass, field

from simulator.config.settings import Settings
from simulator.obs.logging import get_logger, log_event
from simulator.synth import enrichment_safe
from simulator.synth.enrichment_safe import SpacingBounds, SpacingConflict
from simulator.synth.models import CompatibleTrailSet, PatternView

_log = get_logger("simulator.synth.target_controller")


@dataclass(frozen=True)
class PlanEntry:
    """One planned (pattern, trail, instance) cascade to synthesize + emit."""

    pattern_id: str
    trail_id: str
    igp_area: str | None
    instance_index: int


@dataclass
class NetworkWidePlan:
    """The realized network-wide plan: ordered cascade entries + target/shortfall/conflict data."""

    entries: list[PlanEntry] = field(default_factory=list)
    enrichment_conflict_patterns: list[str] = field(default_factory=list)
    shortfall_cascades: int = 0
    target_emitted_fraction: float = 0.0
    target_aligned_alarms: int = 0
    # Per-pattern spacing bounds for eligible patterns (reused by the emit step).
    spacing: dict[str, SpacingBounds] = field(default_factory=dict)
    # Per-pattern emitted cascade length (L_P) used by the count math.
    cascade_lengths: dict[str, int] = field(default_factory=dict)


def _emitted_cascade_length(pattern: PatternView) -> int:
    """L_P — the emitted cascade length (mandatory elements + guaranteed root; §B).

    Under the default include-prob of 1.0 the emitted cascade equals the full sequence length. The
    root always contributes once even if it is not present in ``sequence`` (aligned_synth inserts
    it). Mirrors what ``aligned_synth.build_cascade`` actually emits so the count math is exact.
    """
    types = [e.alarm_type for e in pattern.sequence]
    if pattern.root_cause_alarm_type not in types:
        return len(types) + 1
    return max(1, len(types))


def _session_window_ms(pattern: PatternView, fallback_ms: float) -> float:
    if pattern.session_window and pattern.session_window.window_ms:
        return float(pattern.session_window.window_ms)
    return fallback_ms


def _eligible_patterns(
    settings: Settings,
    patterns: list[PatternView],
    plan: NetworkWidePlan,
) -> list[PatternView]:
    """Filter out enrichment-conflicting patterns; record + log them (AC 62). Never aborts here."""
    dedup_ms = float(settings.p3_enrichment_dedup_window_ms)
    eligible: list[PatternView] = []
    for pattern in patterns:
        length = _emitted_cascade_length(pattern)
        window_ms = _session_window_ms(pattern, dedup_ms * 4)
        reconciled = enrichment_safe.reconcile_spacing(
            dedup_ms,
            window_ms,
            length,
            spacing_margin=settings.p3_dedup_spacing_margin,
            in_window_margin=settings.p3_in_window_margin,
        )
        if isinstance(reconciled, SpacingConflict):
            plan.enrichment_conflict_patterns.append(pattern.pattern_id)
            log_event(
                _log,
                logging.WARNING,
                "p3.enrichment_window_conflict",
                f"pattern {pattern.pattern_id} excluded: {reconciled.reason}",
                patternId=pattern.pattern_id,
                dedupWindowMs=reconciled.dedup_window_ms,
                sessionWindowMs=reconciled.session_window_ms,
                cascadeLength=reconciled.cascade_length,
            )
            continue
        plan.spacing[pattern.pattern_id] = reconciled
        plan.cascade_lengths[pattern.pattern_id] = length
        eligible.append(pattern)
    return eligible


def _target_counts(
    settings: Settings, eligible: list[PatternView], plan: NetworkWidePlan
) -> dict[str, int]:
    """Greedy cascade-count math (§B): per-pattern cascade counts closest to A within tolerance."""
    target = settings.p3_auto_correlation_target or 0.0
    margin = settings.p3_enrichment_over_provision_margin
    total = settings.p3_total_alarms
    emitted_fraction = target / (1.0 - margin) if margin < 1.0 else target
    plan.target_emitted_fraction = emitted_fraction
    a = round(emitted_fraction * total)
    plan.target_aligned_alarms = a
    tol_alarms = round(settings.p3_target_tolerance * total)

    counts: dict[str, int] = {p.pattern_id: 0 for p in eligible}
    if a <= 0 or not eligible:
        return counts

    # Greedy round-robin over eligible patterns, each cascade contributing its own L_P, stopping at
    # the whole-cascade count whose accumulated aligned-alarm total lands closest to A within TOL.
    accumulated = 0
    idx = 0
    guard = 0
    max_iters = 1_000_000
    while guard < max_iters:
        guard += 1
        pattern = eligible[idx % len(eligible)]
        idx += 1
        length = plan.cascade_lengths[pattern.pattern_id]
        prospective = accumulated + length
        if prospective > a + tol_alarms and accumulated >= max(0, a - tol_alarms):
            break
        counts[pattern.pattern_id] += 1
        accumulated = prospective
        if accumulated >= a:
            break
    return counts


def _distribute(
    settings: Settings,
    pattern: PatternView,
    n_p: int,
    compatible: CompatibleTrailSet,
    rng: random.Random,
) -> tuple[list[PlanEntry], int]:
    """Distribute ``n_p`` cascades across ``pattern``'s compatible trails (§C).

    distinct-areas-first -> fill used trails round-robin to the per-trail cap -> staggered repeats.
    Returns (entries, shortfall). Shortfall > 0 only when caps make ``n_p`` unreachable.
    """
    cap = settings.p3_max_cascades_per_trail
    trails = list(compatible.trails)
    if not trails or n_p <= 0:
        return [], max(0, n_p)

    # Seeded shuffle *within* each area group preserving the non-discovery-areas-first ordering
    # (AC 57): stable area order from discovery, trails shuffled inside each area.
    trails = _seeded_area_shuffle(trails, rng)

    entries: list[PlanEntry] = []
    per_trail: dict[str, int] = {t.trail_id: 0 for t in trails}
    used_areas: set[str | None] = set()

    remaining = n_p
    # 1. one cascade per distinct area (maximize distinctAreasUsed).
    for ct in trails:
        if remaining <= 0:
            break
        if ct.igp_area in used_areas:
            continue
        used_areas.add(ct.igp_area)
        per_trail[ct.trail_id] += 1
        entries.append(
            PlanEntry(pattern.pattern_id, ct.trail_id, ct.igp_area, per_trail[ct.trail_id])
        )
        remaining -= 1

    # 2. fill used/available trails round-robin up to the per-trail cap.
    progress = True
    while remaining > 0 and progress:
        progress = False
        for ct in trails:
            if remaining <= 0:
                break
            if per_trail[ct.trail_id] >= cap:
                continue
            per_trail[ct.trail_id] += 1
            entries.append(
                PlanEntry(pattern.pattern_id, ct.trail_id, ct.igp_area, per_trail[ct.trail_id])
            )
            remaining -= 1
            progress = True

    # 3. caps exhausted with cascades remaining -> genuine shortfall (AC 55). instanceIndex already
    #    carries repeats (>=2) via the round-robin fill above (§C step 3/AC 56).
    return entries, max(0, remaining)


def _seeded_area_shuffle(trails: list, rng: random.Random) -> list:
    """Shuffle trails within each igp-area group while keeping the area group order (AC 57)."""
    order: list[str | None] = []
    by_area: dict[str | None, list] = {}
    for ct in trails:
        if ct.igp_area not in by_area:
            by_area[ct.igp_area] = []
            order.append(ct.igp_area)
        by_area[ct.igp_area].append(ct)
    out: list = []
    for area in order:
        group = list(by_area[area])
        rng.shuffle(group)
        out.extend(group)
    return out


def plan_network_wide(
    settings: Settings,
    patterns: list[PatternView],
    compatible: dict[str, CompatibleTrailSet],
    rng: random.Random,
) -> NetworkWidePlan:
    """Build the network-wide distribution plan (§B + §C + §D). Seeded, reproducible (AC 57)."""
    plan = NetworkWidePlan()
    eligible = _eligible_patterns(settings, patterns, plan)
    if not eligible:
        return plan

    counts = _target_counts(settings, eligible, plan)

    total_shortfall = 0
    for pattern in eligible:
        n_p = counts.get(pattern.pattern_id, 0)
        comp = compatible.get(pattern.pattern_id)
        if comp is None or not comp.trails:
            # No compatible trails: this pattern contributes nothing; its whole count is shortfall.
            total_shortfall += n_p
            continue
        entries, shortfall = _distribute(settings, pattern, n_p, comp, rng)
        plan.entries.extend(entries)
        total_shortfall += shortfall

    plan.shortfall_cascades = total_shortfall
    if total_shortfall > 0:
        log_event(
            _log,
            logging.WARNING,
            "p3.target_shortfall",
            "auto-correlation target unreachable under per-trail caps; emitting max achievable",
            shortfallCascades=total_shortfall,
            targetAlignedAlarms=plan.target_aligned_alarms,
            entries=len(plan.entries),
        )
    return plan
