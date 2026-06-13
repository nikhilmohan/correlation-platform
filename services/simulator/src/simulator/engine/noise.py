"""Background-noise generation (criteria 6, 13, 23).

Generates noise alarms from the pack's noise classes (≥3), at a configurable rate/mix. A
``HARD_NOISE_FRACTION`` of noise is placed *near* a cascade in time and/or on a
topology-adjacent object (DBSCAN stress); the remainder is clearly separated easy noise. Every
noise alarm carries a valid canonical ``alarmType`` but appears in **no** label's children — it
is identifiable as noise solely by that absence (criterion 6).
"""

from __future__ import annotations

import random
from collections.abc import Iterator
from datetime import datetime, timedelta

import networkx as nx

from simulator.engine.domain_pack import DomainPack
from simulator.engine.models import SynthAlarm


def _weighted_choice(weights: dict[str, float], rng: random.Random) -> str:
    names = list(weights.keys())
    w = [max(0.0, weights[n]) for n in names]
    total = sum(w)
    if total <= 0:
        return rng.choice(names)
    return rng.choices(names, weights=w, k=1)[0]


def generate_noise(  # noqa: C901 - cohesive noise synthesis
    pack: DomainPack,
    graph: nx.DiGraph,
    count: int,
    noise_mix: dict[str, float],
    hard_noise_fraction: float,
    window_start: datetime,
    window_end: datetime,
    cascade_times: list[datetime],
    rng: random.Random,
    alarm_id_seq: Iterator[str],
) -> list[SynthAlarm]:
    """Produce ``count`` noise alarms (mix of hard/easy) over the window."""
    if count <= 0:
        return []
    classes = {nc.name: nc for nc in pack.noise_classes()}
    # restrict the mix to known classes
    mix = {name: w for name, w in noise_mix.items() if name in classes} or {
        nc.name: 1.0 for nc in pack.noise_classes()
    }
    candidate_objects = list(graph.nodes())
    span = max(timedelta(seconds=1), window_end - window_start)
    out: list[SynthAlarm] = []
    for i in range(count):
        cls_name = _weighted_choice(mix, rng)
        cls = classes[cls_name]
        moid = rng.choice(candidate_objects)
        alarm_type = rng.choice(cls.alarm_types)
        shape = pack.alarm_shape(alarm_type)
        is_hard = (i / count) < hard_noise_fraction and bool(cascade_times)
        if is_hard:
            near = rng.choice(cascade_times)
            at = near + timedelta(milliseconds=rng.uniform(-1500, 1500))
        else:
            at = window_start + timedelta(seconds=rng.uniform(0, span.total_seconds()))
        out.append(
            SynthAlarm(
                alarm_id=next(alarm_id_seq),
                managed_object_id=moid,
                alarm_type=alarm_type,
                event_type=shape.event_type,
                probable_cause=shape.probable_cause,
                perceived_severity=shape.perceived_severity,
                raised_at=at,
                trace_id=f"noise-{cls_name}-{i}",
                is_noise=True,
                noise_class=cls_name,
                is_hard_noise=is_hard,
            )
        )
    return out


def generate_background(
    pack: DomainPack,
    graph: nx.DiGraph,
    count: int,
    window_start: datetime,
    window_end: datetime,
    rng: random.Random,
    alarm_id_seq: Iterator[str],
) -> list[SynthAlarm]:
    """Produce ``count`` background (non-pattern) alarms on real objects, in no label."""
    if count <= 0:
        return []
    vocab = pack.alarm_type_vocabulary()
    candidate_objects = list(graph.nodes())
    span = max(timedelta(seconds=1), window_end - window_start)
    out: list[SynthAlarm] = []
    for i in range(count):
        moid = rng.choice(candidate_objects)
        alarm_type = rng.choice(vocab)
        shape = pack.alarm_shape(alarm_type)
        at = window_start + timedelta(seconds=rng.uniform(0, span.total_seconds()))
        out.append(
            SynthAlarm(
                alarm_id=next(alarm_id_seq),
                managed_object_id=moid,
                alarm_type=alarm_type,
                event_type=shape.event_type,
                probable_cause=shape.probable_cause,
                perceived_severity=shape.perceived_severity,
                raised_at=at,
                trace_id=f"bg-{i}",
                is_background=True,
            )
        )
    return out
