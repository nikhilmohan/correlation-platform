"""Generic forward-propagation cascade (criteria 4, 5, 29; design Algorithm logical flow).

Given a root-cause node and the pack's per-edge-relation propagation templates, walk the graph
closure (BFS over the template-relevant edge relations) producing the ordered child-alarm set.
The traversal is domain-agnostic: all template data, the canonical ``alarmType`` tokens, and
the X.733 shapes come from the pack. ``co-failure-group`` templates (SRLG) fate-share the root
to all co-members before propagating onward.

Each emitted alarm carries its canonical ``alarmType`` (the join key) set from the pack's
``alarm_shape``; ``raisedAt = parent.raisedAt + base_delay + jitter`` (jitter clamped ≥ 0 so a
child never precedes its parent — causal ordering preserved).
"""

from __future__ import annotations

import random
from collections import deque
from collections.abc import Iterator
from datetime import datetime, timedelta

import networkx as nx

from simulator.engine.domain_pack import DomainPack, PropagationTemplate, ScenarioDef
from simulator.engine.models import GroundTruthLabel, SynthAlarm


def _templates_by_relation(
    templates: tuple[PropagationTemplate, ...],
) -> dict[str, list[PropagationTemplate]]:
    out: dict[str, list[PropagationTemplate]] = {}
    for t in templates:
        out.setdefault(t.edge_relation, []).append(t)
    return out


def _delay(base_ms: float, jitter_ms: float, rng: random.Random) -> timedelta:
    ms = base_ms + (rng.gauss(0.0, jitter_ms) if jitter_ms > 0 else 0.0)
    return timedelta(milliseconds=max(0.0, ms))


def propagate(  # noqa: C901 - the cascade BFS is intentionally one cohesive routine
    pack: DomainPack,
    graph: nx.DiGraph,
    scenario: ScenarioDef,
    root_node: str,
    scenario_id: str,
    start_at: datetime,
    base_interval_ms: float,
    jitter_stddev_ms: float,
    rng: random.Random,
    alarm_id_seq: Iterator[str],
) -> tuple[list[SynthAlarm], GroundTruthLabel]:
    """Produce the ordered alarm list + ground-truth label for one scenario instance."""
    by_rel = _templates_by_relation(pack.propagation_templates())
    allowed = set(scenario.relations) if scenario.relations is not None else set(by_rel)

    def make_alarm(moid: str, alarm_type: str, at: datetime, is_root: bool) -> SynthAlarm:
        shape = pack.alarm_shape(alarm_type)
        return SynthAlarm(
            alarm_id=next(alarm_id_seq),
            managed_object_id=moid,
            alarm_type=alarm_type,
            event_type=shape.event_type,
            probable_cause=shape.probable_cause,
            perceived_severity=shape.perceived_severity,
            raised_at=at,
            trace_id=scenario_id,
            scenario_id=scenario_id,
            is_root=is_root,
        )

    # 1. root-cause alarm
    root_alarm = make_alarm(root_node, scenario.root_alarm_type, start_at, is_root=True)
    alarms: list[SynthAlarm] = [root_alarm]
    children: list[str] = []
    last_at = start_at
    emitted_on: set[str] = {root_node}

    # 2. SRLG fate-sharing: expand the root to all co-members before BFS.
    frontier: deque[str] = deque([root_node])
    if scenario.co_failure_relation:
        for _, member, data in graph.out_edges(root_node, data=True):
            if data.get("relation") == scenario.co_failure_relation:
                frontier.append(member)

    # 3. BFS over template-relevant edges; apply ALL templates per relation.
    while frontier:
        node = frontier.popleft()
        for _, target, data in graph.out_edges(node, data=True):
            relation = data.get("relation")
            if relation not in allowed or relation not in by_rel:
                continue
            for template in by_rel[relation]:
                last_at = last_at + _delay(base_interval_ms, jitter_stddev_ms, rng)
                child = make_alarm(target, template.effect_alarm_type, last_at, is_root=False)
                alarms.append(child)
                children.append(child.alarm_id)
            if target not in emitted_on:
                emitted_on.add(target)
                frontier.append(target)

    label = GroundTruthLabel(
        scenario_id=scenario_id,
        scenario_type=scenario.scenario_type,
        root_cause=root_alarm.alarm_id,
        root_cause_managed_object_id=root_node,
        root_cause_alarm_type=root_alarm.alarm_type,
        children=children,
    )
    return alarms, label
