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

from simulator.engine.domain_pack import DomainPack
from simulator.synth.aligned_synth import AlignedCascade, build_cascade
from simulator.synth.models import PatternView, TrailDetail

# +/- tolerance band as a fraction of T (AC 38: 5 percentage points).
TOLERANCE_PCT = 0.05


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
) -> SynthPlan:
    """Build aligned cascades up to the target aligned fraction; return the plan."""
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

    accumulated = 0
    idx = 0
    guard = 0
    max_iters = 100_000
    while guard < max_iters:
        guard += 1
        pattern = order[idx % len(order)]
        idx += 1
        trail = trails[pattern.trail_id]
        cascade = build_cascade(
            pack,
            pattern,
            trail,
            rng,
            base_time,
            optional_include_prob=optional_include_prob,
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
