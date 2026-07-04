"""P3 aligned-fraction controller (spec Task 16, AC 38).

Given ``P3_TOTAL_ALARMS`` (T) and ``P3_ALIGNED_FRACTION`` (f), decide how many aligned cascades to
instantiate (round-robin over the available approved patterns, seeded order) so the aligned alarm
count lands within a +/- tolerance band of ``A = round(f * T)``, and how many non-aligned alarms
fill the remainder. Aligned cascades are whole (a cascade emits its retained sequence length), so
the controller accumulates whole cascades and stops at the count closest to A.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import timedelta

from simulator.engine.domain_pack import DomainPack
from simulator.synth.aligned_synth import AlignedCascade, build_cascade
from simulator.synth.models import PatternView, TrailDetail

# +/- tolerance band as a fraction of T (AC 38: 5 percentage points).
TOLERANCE_PCT = 0.05

# Fallback session window (ms) used only when a pattern carries no sessionWindow — so repeated
# same-(trail,pattern) cascades are still temporally separated (M1). Real patterns supply one.
_DEFAULT_WINDOW_MS = 6000.0


def _next_start_offset_ms(
    key: tuple[str, str],
    window_ms: float,
    cursors: dict[tuple[str, str], float],
    rng: random.Random,
    *,
    stagger_margin: float,
    stagger_jitter_ms: float,
) -> float:
    """Return the start offset (ms) for the next cascade on ``key``, then advance the cursor.

    Successive cascades on the SAME (trailId, patternId) are separated by STRICTLY MORE than that
    pattern's ``window_ms`` — so each forms its own Correlation-Engine ``(trailId, patternId)``
    session and therefore its own incident (M1: defeat session collapse -> preserve the auto-
    correlation KPI). The spacing is ``window_ms * stagger_margin`` (margin > 1) plus a
    seeded-RNG jitter drawn in ``[0, stagger_jitter_ms)`` so a fixed ``P3_RNG_SEED`` reproduces
    identical offsets (AC 41).
    """
    cursor = cursors.get(key, 0.0)
    offset = cursor
    jitter = rng.uniform(0.0, stagger_jitter_ms) if stagger_jitter_ms > 0 else 0.0
    cursors[key] = offset + window_ms * stagger_margin + jitter
    return offset


@dataclass
class SynthPlan:
    """The realized plan: aligned cascades + the non-aligned alarm budget."""

    cascades: list[AlignedCascade] = field(default_factory=list)
    aligned_alarm_count: int = 0
    non_aligned_count: int = 0
    target_aligned: int = 0
    total_alarms: int = 0


def plan(  # noqa: C901 - cohesive controller
    pack: DomainPack,
    patterns: list[PatternView],
    trails: dict[str, TrailDetail],
    total_alarms: int,
    aligned_fraction: float,
    rng: random.Random,
    base_time,
    *,
    optional_include_prob: float = 1.0,
    stagger_margin: float = 1.5,
    stagger_jitter_ms: float = 2000.0,
    in_window_margin: float = 0.9,
) -> SynthPlan:
    """Build aligned cascades up to the target aligned fraction; return the plan.

    Each cascade is anchored at ``base_time + <seeded stagger offset>`` so that repeated cascades on
    the SAME ``(trailId, patternId)`` are separated by strictly more than that pattern's
    ``sessionWindow.windowMs`` — keeping every aligned cascade in its own Correlation-Engine
    session (its own incident) instead of collapsing into one (M1). Offsets are drawn from the
    SEEDED ``rng`` so a fixed ``P3_RNG_SEED`` yields identical ``raisedAt``/ordering (AC 41).
    """
    target_aligned = round(aligned_fraction * total_alarms)
    result = SynthPlan(
        target_aligned=target_aligned,
        total_alarms=total_alarms,
    )
    if target_aligned <= 0 or not patterns:
        result.non_aligned_count = total_alarms
        return result

    # Round-robin over patterns in the seeded order.
    order = list(patterns)
    rng.shuffle(order)
    tol = max(1, round(TOLERANCE_PCT * total_alarms))

    # Per-(trailId, patternId) start cursor (ms offset from base_time) so repeats are staggered.
    start_cursors: dict[tuple[str, str], float] = {}

    accumulated = 0
    idx = 0
    guard = 0
    max_iters = 100_000
    while guard < max_iters:
        guard += 1
        pattern = order[idx % len(order)]
        idx += 1
        trail = trails[pattern.trail_id]
        key = (pattern.trail_id, pattern.pattern_id)
        window_ms = (
            float(pattern.session_window.window_ms)
            if pattern.session_window and pattern.session_window.window_ms
            else _DEFAULT_WINDOW_MS
        )
        offset_ms = _next_start_offset_ms(
            key,
            window_ms,
            start_cursors,
            rng,
            stagger_margin=stagger_margin,
            stagger_jitter_ms=stagger_jitter_ms,
        )
        cascade_base = base_time + timedelta(milliseconds=offset_ms)
        cascade = build_cascade(
            pack,
            pattern,
            trail,
            rng,
            cascade_base,
            optional_include_prob=optional_include_prob,
            in_window_margin=in_window_margin,
        )
        cascade_len = len(cascade.alarms)
        if cascade_len == 0:
            continue
        prospective = accumulated + cascade_len
        # Stop if adding this cascade would overshoot beyond the tolerance band AND we already
        # have at least the lower tolerance bound (avoid an empty aligned set).
        if prospective > target_aligned + tol and accumulated >= max(0, target_aligned - tol):
            break
        result.cascades.append(cascade)
        accumulated = prospective
        # Stop once we are within tolerance at/above the target.
        if accumulated >= target_aligned:
            break

    result.aligned_alarm_count = accumulated
    result.non_aligned_count = max(0, total_alarms - accumulated)
    return result
