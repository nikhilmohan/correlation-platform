"""P3 non-aligned remainder synthesis (spec Task 16, OQ-P3-3, AC 39).

Produces the ``1 - P3_ALIGNED_FRACTION`` remainder as a configurable MIX of three sub-classes, all
on **real** trail-member ``managedObjectId``s with canonical ``alarmType`` tokens, each labeled
with its ``scenarioType`` so it is excluded from the aligned-fraction count (AC 43):
  * ``partial-cascade`` — start a real pattern's cascade but stop before the decisive condition
    (CE opens then expires -> reverted-open, no incident).
  * ``non-aligned`` — random single alarms on real objects that form no approved sequence.
  * ``noise`` — the pack's noise classes placed on real moids.
The three fractions are validated to sum to ~1.0 by settings; here they only weight the mix.
"""

from __future__ import annotations

import random
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta

from simulator.engine.domain_pack import DomainPack
from simulator.engine.models import SynthAlarm
from simulator.obs import metrics
from simulator.synth.models import P3CascadeLabel, PatternView, TrailDetail


@dataclass
class NonAlignedResult:
    """The non-aligned alarms + their per-alarm ground-truth labels."""

    alarms: list[SynthAlarm]
    labels: list[P3CascadeLabel]


def build_nonaligned(  # noqa: C901 - cohesive three-way mix
    pack: DomainPack,
    patterns: list[PatternView],
    trails: dict[str, TrailDetail],
    count: int,
    rng: random.Random,
    base_time: datetime,
    *,
    partial_fraction: float = 0.4,
    random_fraction: float = 0.4,
    noise_fraction: float = 0.2,
    background_interval_ms: float = 2000.0,
) -> NonAlignedResult:
    """Synthesize ``count`` non-aligned alarms as a partial/random/noise mix."""
    if count <= 0:
        return NonAlignedResult(alarms=[], labels=[])

    n_partial = round(count * partial_fraction)
    n_random = round(count * random_fraction)
    n_noise = count - n_partial - n_random
    if n_noise < 0:
        n_noise = 0

    moid_pool = _moid_pool(trails)
    vocab = pack.alarm_type_vocabulary()
    noise_types = _noise_types(pack)

    alarms: list[SynthAlarm] = []
    labels: list[P3CascadeLabel] = []
    at = base_time

    def advance() -> datetime:
        nonlocal at
        at = at + timedelta(milliseconds=rng.uniform(0.0, background_interval_ms * 2))
        return at

    # partial cascades: emit a few (not decisive) elements of a real pattern on its real trail.
    emitted_partial = 0
    if patterns:
        while emitted_partial < n_partial:
            pattern = rng.choice(patterns)
            trail = trails[pattern.trail_id]
            take = _partial_take(pattern, rng)
            child_ids: list[str] = []
            for element in take:
                if emitted_partial >= n_partial:
                    break
                shape = pack.alarm_shape(element.alarm_type)
                member = rng.choice(trail.members)
                alarm_id = _alarm_id(rng)
                alarms.append(
                    _mk(
                        alarm_id,
                        member.managed_object_id,
                        element.alarm_type,
                        shape,
                        advance(),
                        f"partial-{pattern.pattern_id}",
                    )
                )
                child_ids.append(alarm_id)
                metrics.P3_NONALIGNED_ALARMS.labels(scenarioType="partial-cascade").inc()
                emitted_partial += 1
            if child_ids:
                labels.append(
                    P3CascadeLabel(
                        pattern_id=pattern.pattern_id,
                        trail_id=pattern.trail_id,
                        root_cause_alarm_id="",
                        root_cause_alarm_type=pattern.root_cause_alarm_type,
                        child_alarm_ids=child_ids,
                        scenario_type="partial-cascade",
                    )
                )
    else:  # no patterns to truncate -> treat the partial budget as random singles
        n_random += n_partial

    # random single alarms on real objects (no approved sequence).
    for _ in range(n_random):
        moid = rng.choice(moid_pool)
        alarm_type = rng.choice(vocab)
        shape = pack.alarm_shape(alarm_type)
        alarm_id = _alarm_id(rng)
        alarms.append(_mk(alarm_id, moid, alarm_type, shape, advance(), "non-aligned"))
        labels.append(P3CascadeLabel("", "", alarm_id, alarm_type, [], scenario_type="non-aligned"))
        metrics.P3_NONALIGNED_ALARMS.labels(scenarioType="non-aligned").inc()

    # noise alarms on real objects.
    for _ in range(n_noise):
        moid = rng.choice(moid_pool)
        alarm_type = rng.choice(noise_types)
        shape = pack.alarm_shape(alarm_type)
        alarm_id = _alarm_id(rng)
        alarms.append(_mk(alarm_id, moid, alarm_type, shape, advance(), "noise", is_noise=True))
        labels.append(P3CascadeLabel("", "", alarm_id, alarm_type, [], scenario_type="noise"))
        metrics.P3_NONALIGNED_ALARMS.labels(scenarioType="noise").inc()

    return NonAlignedResult(alarms=alarms, labels=labels)


def _mk(
    alarm_id: str,
    moid: str,
    alarm_type: str,
    shape,
    at: datetime,
    trace_prefix: str,
    *,
    is_noise: bool = False,
) -> SynthAlarm:
    return SynthAlarm(
        alarm_id=alarm_id,
        managed_object_id=moid,
        alarm_type=alarm_type,
        event_type=shape.event_type,
        probable_cause=shape.probable_cause,
        perceived_severity=shape.perceived_severity,
        raised_at=at,
        trace_id=f"{trace_prefix}-{alarm_id}",
        is_noise=is_noise,
    )


def _alarm_id(rng: random.Random) -> str:
    return f"alm-{uuid.UUID(int=rng.getrandbits(128)).hex[:16]}"


def _moid_pool(trails: dict[str, TrailDetail]) -> list[str]:
    pool = sorted({m.managed_object_id for t in trails.values() for m in t.members})
    return pool


def _noise_types(pack: DomainPack) -> tuple[str, ...]:
    out: list[str] = []
    for nc in pack.noise_classes():
        out.extend(nc.alarm_types)
    return tuple(out) or pack.alarm_type_vocabulary()


def _partial_take(pattern: PatternView, rng: random.Random) -> list:
    """Take a strict-prefix subset of the sequence that will NOT satisfy the decisive condition.

    Emit at most floor((len-1)/2)+ but never include the root and never the full sequence, so the
    CE opens then expires (reverted-open) instead of matching.
    """
    seq = [e for e in pattern.sequence if e.alarm_type != pattern.root_cause_alarm_type]
    if not seq:
        return []
    take_n = max(1, min(len(seq) - 1, rng.randint(1, max(1, len(seq) // 2))))
    return seq[:take_n]
