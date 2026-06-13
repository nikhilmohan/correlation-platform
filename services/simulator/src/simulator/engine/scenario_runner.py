"""Synthesizer orchestration — labeled scenarios + noise + background (criteria 4-6, 21-23, 32).

Runs each selected scenario ``SCENARIO_INSTANCES`` times over the topology closure (with fan-out
and SRLG fate-sharing), records a ground-truth label per instance, then interleaves background
(non-pattern) and noise alarms at the configured fractions. When ``TOTAL_ALARMS`` is set the
runner solves ``SCENARIO_INSTANCES`` / background / noise counts to approximately hit the target.

The output is a single time-ordered ``SynthAlarm`` stream plus a populated ``LabelStore``. Every
emitted alarm carries its canonical ``alarmType`` (the join key); noise/background alarms appear
in no label's children.
"""

from __future__ import annotations

import itertools
import random
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import datetime

import networkx as nx

from simulator.config.settings import Settings
from simulator.engine import cascade, noise
from simulator.engine.domain_pack import DomainPack, ScenarioDef
from simulator.engine.labels import LabelStore
from simulator.engine.models import SynthAlarm


@dataclass
class SynthesisResult:
    alarms: list[SynthAlarm]
    labels: LabelStore
    resolved_instances: int
    background_count: int
    noise_count: int


def _alarm_id_seq() -> Iterator[str]:
    for i in itertools.count():
        yield f"ALM-{i:07d}"


def _fault_origin_candidates(graph: nx.DiGraph, object_type: str) -> list[str]:
    return [
        moid for moid, data in graph.nodes(data=True) if data.get("objectType") == object_type
    ]


def _expected_cascade_size(
    pack: DomainPack, graph: nx.DiGraph, scenario: ScenarioDef, rng: random.Random
) -> int:
    """A deterministic estimate of a scenario's cascade size for the TOTAL_ALARMS solve."""
    candidates = _fault_origin_candidates(graph, scenario.fault_origin_type)
    if not candidates:
        return 1
    alarms, _ = cascade.propagate(
        pack,
        graph,
        scenario,
        candidates[0],
        scenario_id="estimate",
        start_at=datetime.now().astimezone(),
        base_interval_ms=1.0,
        jitter_stddev_ms=0.0,
        rng=random.Random(0),
        alarm_id_seq=_alarm_id_seq(),
    )
    return len(alarms)


def _solve_instances(
    pack: DomainPack,
    graph: nx.DiGraph,
    scenarios: list[ScenarioDef],
    settings: Settings,
    rng: random.Random,
) -> int:
    """Choose SCENARIO_INSTANCES so the total ≈ TOTAL_ALARMS (clamped ≥ 5 for minability)."""
    if settings.total_alarms is None:
        return settings.scenario_instances
    signal = max(1e-6, 1.0 - settings.background_fraction - settings.noise_rate)
    target_signal = settings.total_alarms * signal
    avg_cascade = sum(_expected_cascade_size(pack, graph, s, rng) for s in scenarios) / max(
        1, len(scenarios)
    )
    per_scenario = target_signal / max(1, len(scenarios))
    instances = round(per_scenario / max(1.0, avg_cascade))
    return max(5, instances)


def synthesize(
    pack: DomainPack,
    graph: nx.DiGraph,
    settings: Settings,
    rng: random.Random,
    window_start: datetime,
    window_end: datetime,
) -> SynthesisResult:
    """Build the full labeled + noise + background alarm stream for a run."""
    by_type = {s.scenario_type: s for s in pack.scenario_library()}
    scenarios = [by_type[name] for name in settings.selected_scenarios if name in by_type]
    labels = LabelStore()
    alarm_ids = _alarm_id_seq()
    instances = _solve_instances(pack, graph, scenarios, settings, rng)

    signal_alarms: list[SynthAlarm] = []
    cascade_times: list[datetime] = []
    span_s = max(1.0, (window_end - window_start).total_seconds())

    for scenario in scenarios:
        candidates = _fault_origin_candidates(graph, scenario.fault_origin_type)
        if not candidates:
            continue
        for inst in range(instances):
            origin = rng.choice(candidates)
            start = window_start.fromtimestamp(
                window_start.timestamp() + rng.uniform(0, span_s),
                tz=window_start.tzinfo,
            )
            cascade_times.append(start)
            scenario_id = f"sc-{scenario.scenario_type}-{inst:03d}"
            alarms, label = cascade.propagate(
                pack,
                graph,
                scenario,
                origin,
                scenario_id=scenario_id,
                start_at=start,
                base_interval_ms=settings.base_interval_ms,
                jitter_stddev_ms=settings.jitter_stddev_ms,
                rng=rng,
                alarm_id_seq=alarm_ids,
            )
            signal_alarms.extend(alarms)
            labels.record(label)

    signal_n = len(signal_alarms)
    # Solve background + noise counts from fractions relative to the *total*.
    bg_frac = settings.background_fraction
    noise_frac = settings.noise_rate
    signal_frac = max(1e-6, 1.0 - bg_frac - noise_frac)
    total = signal_n / signal_frac if signal_n else 0.0
    background_count = int(round(total * bg_frac))
    noise_count = int(round(total * noise_frac))

    background = noise.generate_background(
        pack, graph, background_count, window_start, window_end, rng, alarm_ids
    )
    noise_alarms = noise.generate_noise(
        pack,
        graph,
        noise_count,
        settings.noise_mix_weights,
        settings.hard_noise_fraction,
        window_start,
        window_end,
        cascade_times,
        rng,
        alarm_ids,
    )

    all_alarms = signal_alarms + background + noise_alarms
    all_alarms.sort(key=lambda a: a.raised_at)
    return SynthesisResult(
        alarms=all_alarms,
        labels=labels,
        resolved_instances=instances,
        background_count=len(background),
        noise_count=len(noise_alarms),
    )
