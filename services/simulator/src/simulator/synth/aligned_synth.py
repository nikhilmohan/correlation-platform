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
) -> AlignedCascade:
    """Build one pattern-aligned cascade for ``pattern`` on its ``trail``."""
    affinity = pack.placement_affinity()
    retained = _retain_elements(pattern, rng, optional_include_prob)

    inter_arrivals = _inter_arrival_gaps(pattern, len(retained), rng)
    alarms: list[SynthAlarm] = []
    child_ids: list[str] = []
    root_id = ""
    root_type = pattern.root_cause_alarm_type
    at = base_time
    for idx, element in enumerate(retained):
        if idx > 0:
            at = at + timedelta(milliseconds=inter_arrivals[idx - 1])
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
        trail_id=pattern.trail_id,
        root_cause_alarm_id=root_id,
        root_cause_alarm_type=root_type,
        child_alarm_ids=child_ids,
        scenario_type="pattern-aligned",
    )
    return AlignedCascade(alarms=alarms, label=label)


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


def _inter_arrival_gaps(pattern: PatternView, n: int, rng: random.Random) -> list[float]:
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
        # leave a small margin so the last alarm lands strictly inside the window
        budget = window_ms * 0.9
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
