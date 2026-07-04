"""P3 pattern-aligned cascade synthesis (spec Tasks 15-17, OQ-P3-1, OQ-P3-4, AC 35-37).

For each approved pattern, build a cascade that maps each ``sequence[i].alarmType`` onto a real
trail member (pack affinity table + fallback to any trail member), paces it inside the pattern's
session window so the Correlation Engine matches, marks the ``rootCauseAlarmType`` element as root
cause, and records a :class:`P3CascadeLabel`. Optional elements are default-included under a seeded
RNG (``P3_OPTIONAL_INCLUDE_PROB``); the root element is never dropped.
"""

from __future__ import annotations

import random
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from simulator.engine.domain_pack import DomainPack
from simulator.engine.models import SynthAlarm
from simulator.obs import metrics
from simulator.synth.models import P3CascadeLabel, PatternView, TrailDetail, TrailMember

# Fallback pacing when the pattern carries no timing (spec: BASE_INTERVAL_MS / JITTER_STDDEV_MS).
_DEFAULT_INTER_ARRIVAL_MS = 400.0
_DEFAULT_STDDEV_MS = 300.0
_DEFAULT_MAX_INTER_ARRIVAL_MS = 1500.0


@dataclass
class AlignedCascade:
    """One synthesized pattern-aligned cascade: its alarms + ground-truth label."""

    alarms: list[SynthAlarm]
    label: P3CascadeLabel


def build_cascade(
    pack: DomainPack,
    pattern: PatternView,
    trail: TrailDetail,
    rng: random.Random,
    base_time: datetime,
    *,
    optional_include_prob: float = 1.0,
    in_window_margin: float = 0.9,
    spacing_lo_ms: float | None = None,
    spacing_hi_ms: float | None = None,
    instance_index: int = 1,
    igp_area: str | None = None,
) -> AlignedCascade:
    """Build one pattern-aligned cascade for ``pattern`` on ``trail``.

    Single-trail path (``spacing_*`` unsupplied): behaviour is unchanged from the pre-network-wide
    build — affine placement with fallback, timing-derived gaps compressed to fit the window.

    Network-wide path (``spacing_lo_ms``/``spacing_hi_ms`` supplied): every element is placed on a
    **distinct** trail member (draw without replacement, AC 59/63), inter-arrival gaps are drawn in
    ``[spacing_lo_ms, spacing_hi_ms]`` so each gap is above the enrichment dedup window yet the
    whole cascade stays within the session window (AC 61); the label records ``instance_index`` +
    ``igp_area`` (AC 54, 56). The trail argument is the assigned trail so every moid belongs to
    that trail (AC 50).
    """
    affinity = pack.placement_affinity()
    retained = _retain_elements(pattern, rng, optional_include_prob)

    network_wide = spacing_lo_ms is not None and spacing_hi_ms is not None
    if network_wide:
        inter_arrivals = _reconciled_gaps(len(retained), spacing_lo_ms, spacing_hi_ms, rng)
    else:
        inter_arrivals = _inter_arrival_gaps(pattern, len(retained), rng, in_window_margin)

    alarms: list[SynthAlarm] = []
    child_ids: list[str] = []
    root_id = ""
    root_type = pattern.root_cause_alarm_type
    used_moids: set[str] = set()
    at = base_time
    for idx, element in enumerate(retained):
        if idx > 0:
            at = at + timedelta(milliseconds=inter_arrivals[idx - 1])
        if network_wide:
            member = _place_distinct(element.alarm_type, trail, affinity, rng, used_moids)
        else:
            member = _place(element.alarm_type, trail, affinity, rng)
        shape = pack.alarm_shape(element.alarm_type)
        alarm_id = f"alm-{uuid.UUID(int=rng.getrandbits(128)).hex[:16]}"
        is_root = element.alarm_type == root_type and not root_id
        alarms.append(
            SynthAlarm(
                alarm_id=alarm_id,
                managed_object_id=member.managed_object_id,
                alarm_type=element.alarm_type,
                event_type=shape.event_type,
                probable_cause=shape.probable_cause,
                perceived_severity=shape.perceived_severity,
                raised_at=at,
                trace_id=pattern.pattern_id,
                scenario_id=pattern.pattern_id,
                is_root=is_root,
            )
        )
        if is_root:
            root_id = alarm_id
        else:
            child_ids.append(alarm_id)

    label = P3CascadeLabel(
        pattern_id=pattern.pattern_id,
        trail_id=trail.trail_id if network_wide else pattern.trail_id,
        root_cause_alarm_id=root_id,
        root_cause_alarm_type=root_type,
        child_alarm_ids=child_ids,
        scenario_type="pattern-aligned",
        instance_index=instance_index,
        igp_area=igp_area if network_wide else None,
    )
    return AlignedCascade(alarms=alarms, label=label)


def _reconciled_gaps(n: int, lo_ms: float, hi_ms: float, rng: random.Random) -> list[float]:
    """Draw ``n-1`` inter-arrival gaps in ``[lo_ms, hi_ms]`` (enrichment-safe spacing, AC 61)."""
    if n <= 1:
        return []
    hi = max(lo_ms, hi_ms)
    return [rng.uniform(lo_ms, hi) for _ in range(n - 1)]


def _retain_elements(pattern: PatternView, rng: random.Random, include_prob: float) -> list:
    """Decide which sequence elements to include (optional under seeded RNG; root forced-in)."""
    retained = []
    root_type = pattern.root_cause_alarm_type
    root_present = False
    for element in pattern.sequence:
        keep = True
        if element.optional and include_prob < 1.0:
            keep = rng.random() < include_prob
        if element.alarm_type == root_type:
            keep = True  # root is never dropped
            root_present = True
        if keep:
            retained.append(element)
    # If the root type never appears in the sequence (defensive), synthesize it first so the
    # cascade always has a root-cause alarm carrying rootCauseAlarmType.
    if not root_present:
        from simulator.synth.models import SequenceElement

        retained.insert(0, SequenceElement(alarm_type=root_type, optional=False))
    return retained


def _inter_arrival_gaps(
    pattern: PatternView, n: int, rng: random.Random, in_window_margin: float = 0.9
) -> list[float]:
    """Compute the (n-1) inter-arrival gaps (ms), clamped to fit the session window."""
    if n <= 1:
        return []
    timing = pattern.timing
    median = timing.median_inter_arrival_ms or _DEFAULT_INTER_ARRIVAL_MS
    stddev = (
        timing.stddev_inter_arrival_ms
        if timing.stddev_inter_arrival_ms is not None
        else _DEFAULT_STDDEV_MS
    )
    max_gap = timing.max_inter_arrival_ms or _DEFAULT_MAX_INTER_ARRIVAL_MS
    gaps = []
    for _ in range(n - 1):
        gap = rng.gauss(median, stddev)
        gap = max(0.0, min(gap, max_gap))
        gaps.append(gap)

    # Clamp the whole cascade span to fit inside the session window (proportional compression).
    window_ms = pattern.session_window.window_ms if pattern.session_window else None
    if window_ms is not None and window_ms > 0:
        total = sum(gaps)
        # leave a small margin (P3_IN_WINDOW_MARGIN) so the last alarm lands strictly inside the
        # window — no hard-coded threshold (CLAUDE.md).
        budget = window_ms * in_window_margin
        if total > budget and total > 0:
            scale = budget / total
            gaps = [g * scale for g in gaps]
    return gaps


def _place(
    alarm_type: str,
    trail: TrailDetail,
    affinity: dict[str, str],
    rng: random.Random,
) -> TrailMember:
    """Pick a real trail member for ``alarm_type`` (affine objectType, else any member — logged)."""
    affine = affinity.get(alarm_type)
    candidates = [m for m in trail.members if m.object_type == affine] if affine else []
    if candidates:
        return rng.choice(candidates)
    # Fallback: any member of the SAME trail (real object, correct trail -> CE still matches).
    metrics.P3_PLACEMENT_FALLBACK.labels(alarmType=alarm_type).inc()
    return rng.choice(list(trail.members))


def _place_distinct(
    alarm_type: str,
    trail: TrailDetail,
    affinity: dict[str, str],
    rng: random.Random,
    used_moids: set[str],
) -> TrailMember:
    """Place ``alarm_type`` on a DISTINCT (unused) trail member (draw without replacement, AC 59).

    Prefer an unused member of the affine ``objectType``; else any unused member; only if every
    member is already used (cascade longer than the trail) reuse a member and log
    ``p3.member_reuse`` — rare, since compatible trails host the required types. Chosen moid marked.
    """
    affine = affinity.get(alarm_type)
    affine_unused = [
        m
        for m in trail.members
        if m.object_type == affine and m.managed_object_id not in used_moids
    ]
    if affine and affine_unused:
        member = rng.choice(affine_unused)
        used_moids.add(member.managed_object_id)
        return member
    any_unused = [m for m in trail.members if m.managed_object_id not in used_moids]
    if any_unused:
        if affine:
            metrics.P3_PLACEMENT_FALLBACK.labels(alarmType=alarm_type).inc()
        member = rng.choice(any_unused)
        used_moids.add(member.managed_object_id)
        return member
    # Every member used: cascade longer than the trail. Reuse (logged) — no crash.
    metrics.P3_MEMBER_REUSE.inc()
    return rng.choice(list(trail.members))
