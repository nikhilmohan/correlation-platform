"""Scenario-cascade + ground-truth pure-logic tests (spec AC 4, 5, 21, 10, 10a, 25, 2).

Exercises the domain-agnostic cascade over the Core-IP pack: the fiber-cut cascade content,
scenario distinguishability, the 9-scenario library with its 10-24 distinct-alarmType span, the
ground-truth label shape (rootCause / rootCauseManagedObjectId / rootCauseAlarmType / children),
and that emitted moids are a subset of the topology snapshot.
"""

from __future__ import annotations

import itertools
import random
from datetime import UTC, datetime

import networkx as nx
import pytest

from simulator.domains.coreip.scenario_library import SCENARIO_LIBRARY
from simulator.engine import cascade, topology_builder
from simulator.engine.domain_pack import DomainPack, ScenarioDef, TopologyParams

_START = datetime(2026, 1, 1, tzinfo=UTC)


def _alarm_ids():
    return (f"ALM-{i:07d}" for i in itertools.count())


def _candidates(graph: nx.DiGraph, object_type: str) -> list[str]:
    return [m for m, d in graph.nodes(data=True) if d.get("objectType") == object_type]


def _run_scenario(pack: DomainPack, graph: nx.DiGraph, scenario: ScenarioDef):
    origin = _candidates(graph, scenario.fault_origin_type)[0]
    return cascade.propagate(
        pack,
        graph,
        scenario,
        origin,
        scenario_id=f"sc-{scenario.scenario_type}-000",
        start_at=_START,
        base_interval_ms=400.0,
        jitter_stddev_ms=0.0,
        rng=random.Random(0),
        alarm_id_seq=_alarm_ids(),
    )


@pytest.fixture
def big_graph(pack: DomainPack) -> nx.DiGraph:
    """A larger topology so every scenario's cascade reaches the full layered tail."""
    params = TopologyParams(node_count=50, site_count=10, interfaces_per_port=2, igp_area_count=3)
    return topology_builder.build_topology(pack, params, random.Random(99)).graph


def _by_type(name: str) -> ScenarioDef:
    return {s.scenario_type: s for s in SCENARIO_LIBRARY}[name]


# --- AC 4: fiber-cut cascade is correct ----------------------------------------------------


def test_ac4_fiber_cut_root_is_fiberspan_fiberfault(
    pack: DomainPack, big_graph: nx.DiGraph
) -> None:
    """AC4: fiber-cut root alarm is on a FiberSpan with canonical alarmType FiberFault."""
    scenario = _by_type("fiber-cut")
    alarms, label = _run_scenario(pack, big_graph, scenario)
    root = alarms[0]
    assert root.is_root
    assert root.managed_object_id.startswith("FiberSpan:")
    assert root.alarm_type == "FiberFault"
    assert label.root_cause == root.alarm_id
    assert label.root_cause_alarm_type == "FiberFault"


def test_ac4_fiber_cut_cascade_contains_required_effect_types(
    pack: DomainPack, big_graph: nx.DiGraph
) -> None:
    """AC4: cascade has LinkDown (IPLink), AdjDown (IGPAdjacency), LSPDown, ReachabilityLoss."""
    scenario = _by_type("fiber-cut")
    alarms, _ = _run_scenario(pack, big_graph, scenario)
    types_by_object_prefix: dict[str, set[str]] = {}
    for a in alarms:
        prefix = a.managed_object_id.split(":", 1)[0]
        types_by_object_prefix.setdefault(prefix, set()).add(a.alarm_type)
    all_types = {a.alarm_type for a in alarms}
    # the required §5 effect tokens are present somewhere in the cascade
    assert "LinkDown" in all_types
    assert "AdjDown" in types_by_object_prefix.get("IGPAdjacency", set())
    assert "LSPDown" in types_by_object_prefix.get("LSP", set())
    assert "ReachabilityLoss" in types_by_object_prefix.get("VPNService", set())
    # LinkDown lands on the IPLink object
    assert "LinkDown" in types_by_object_prefix.get("IPLink", set())


def test_ac4_fiber_cut_children_are_downstream_alarms(
    pack: DomainPack, big_graph: nx.DiGraph
) -> None:
    """AC4: label children == every downstream alarmId (all non-root emitted alarms)."""
    scenario = _by_type("fiber-cut")
    alarms, label = _run_scenario(pack, big_graph, scenario)
    downstream = [a.alarm_id for a in alarms if not a.is_root]
    assert label.children == downstream
    assert label.root_cause not in label.children


def test_ac4_children_never_precede_root_causally(pack: DomainPack, big_graph: nx.DiGraph) -> None:
    """AC4 support: causal ordering — no child raisedAt precedes the root."""
    scenario = _by_type("fiber-cut")
    alarms, _ = _run_scenario(pack, big_graph, scenario)
    root_at = alarms[0].raised_at
    assert all(a.raised_at >= root_at for a in alarms)


# --- AC 5: line-card-fault and port-fault are producible and distinguishable ----------------


def test_ac5_linecard_and_port_faults_are_distinct(pack: DomainPack, big_graph: nx.DiGraph) -> None:
    """AC5: line-card-fault and port-fault differ in root object type and rootCauseAlarmType."""
    _, lc_label = _run_scenario(pack, big_graph, _by_type("line-card-fault"))
    _, port_label = _run_scenario(pack, big_graph, _by_type("port-fault"))

    assert lc_label.root_cause_managed_object_id.startswith("LineCard:")
    assert port_label.root_cause_managed_object_id.startswith("Port:")
    assert lc_label.root_cause_alarm_type == "LineCardFault"
    assert port_label.root_cause_alarm_type == "PortDown"
    assert lc_label.root_cause_alarm_type != port_label.root_cause_alarm_type
    assert lc_label.scenario_type != port_label.scenario_type


# --- AC 21: 9 grounded scenarios, each spanning 10-24 distinct alarmTypes, distinct roots ---


def test_ac21_library_has_nine_grounded_scenarios() -> None:
    """AC21: the pack ships exactly 9 distinct grounded scenarios with the expected types."""
    expected = {
        "fiber-cut",
        "line-card-fault",
        "port-fault",
        "interface-fault",
        "node-failure",
        "ip-link-failure",
        "lsp-te-failure",
        "routing-adjacency-failure",
        "srlg-shared-risk-failure",
    }
    got = {s.scenario_type for s in SCENARIO_LIBRARY}
    assert got == expected
    assert len(SCENARIO_LIBRARY) == 9


def test_ac21_scenario_types_are_distinct() -> None:
    """AC21: the 9 scenarios are distinct (distinct scenarioType identities).

    NOTE: spec AC21 also says "the 9 root-cause alarmTypes are distinct per scenario", but the
    approved design's scenario table (design.md #234) deliberately models scenario 9
    (srlg-shared-risk-failure) as a FiberSpan-origin co-failure VARIANT (same root token as
    fiber-cut) and scenario 8 (routing-adjacency-failure) as an Interface-origin routing-emphasis
    VARIANT (same root token as interface-fault). So production yields 7 distinct root tokens,
    not 9. This is a spec<->design contradiction flagged to the human; this test asserts the
    invariant production+design actually guarantee (distinct scenarioType per scenario).
    """
    types = [s.scenario_type for s in SCENARIO_LIBRARY]
    assert len(set(types)) == len(types) == 9


def test_ac21_root_alarm_types_match_design_grouping() -> None:
    """AC21/design: root tokens distinct per faultOriginType group; SRLG/routing are variants."""
    roots = {s.scenario_type: s.root_alarm_type for s in SCENARIO_LIBRARY}
    # SRLG co-failure is a FiberSpan-origin variant -> mirrors fiber-cut's root token
    assert roots["srlg-shared-risk-failure"] == roots["fiber-cut"]
    # routing-adjacency is an Interface-origin variant -> mirrors interface-fault's root token
    assert roots["routing-adjacency-failure"] == roots["interface-fault"]
    # the seven base fault-origin scenarios carry distinct root tokens
    base = [
        "fiber-cut",
        "line-card-fault",
        "port-fault",
        "interface-fault",
        "node-failure",
        "ip-link-failure",
        "lsp-te-failure",
    ]
    base_roots = [roots[b] for b in base]
    assert len(set(base_roots)) == len(base_roots), base_roots


@pytest.mark.parametrize("scenario_type", [s.scenario_type for s in SCENARIO_LIBRARY])
def test_ac21_each_scenario_spans_10_to_24_distinct_alarm_types(
    pack: DomainPack, big_graph: nx.DiGraph, scenario_type: str
) -> None:
    """AC21: a single instance's symptom set spans 10-24 distinct canonical alarmType tokens."""
    scenario = _by_type(scenario_type)
    candidates = _candidates(big_graph, scenario.fault_origin_type)
    assert candidates, f"no fault-origin object for {scenario_type}"
    alarms, _ = _run_scenario(pack, big_graph, scenario)
    distinct_types = {a.alarm_type for a in alarms}
    assert (
        10 <= len(distinct_types) <= 24
    ), f"{scenario_type} span={len(distinct_types)} types={sorted(distinct_types)}"


@pytest.mark.parametrize("scenario_type", [s.scenario_type for s in SCENARIO_LIBRARY])
def test_ac21_root_alarm_type_in_vocabulary(pack: DomainPack, scenario_type: str) -> None:
    """AC21/AC7a: each scenario's root alarmType is a member of the pack vocabulary."""
    scenario = _by_type(scenario_type)
    assert scenario.root_alarm_type in pack.alarm_type_vocabulary()


# --- AC 10 / 10a: ground-truth label shape (the eval oracle) -------------------------------


def test_ac10_label_root_cause_matches_root_alarm(pack: DomainPack, big_graph: nx.DiGraph) -> None:
    """AC10: rootCause == the injected root alarm's alarmId; children == downstream alarmIds."""
    for scenario in SCENARIO_LIBRARY:
        if not _candidates(big_graph, scenario.fault_origin_type):
            continue
        alarms, label = _run_scenario(pack, big_graph, scenario)
        root = next(a for a in alarms if a.is_root)
        children_ids = [a.alarm_id for a in alarms if not a.is_root]
        assert label.root_cause == root.alarm_id
        assert label.children == children_ids
        assert label.root_cause_managed_object_id == root.managed_object_id


def test_ac10a_label_carries_root_cause_alarm_type(pack: DomainPack, big_graph: nx.DiGraph) -> None:
    """AC10a: rootCauseAlarmType equals the root alarm's canonical alarmType (vocab token)."""
    vocab = set(pack.alarm_type_vocabulary())
    for scenario in SCENARIO_LIBRARY:
        if not _candidates(big_graph, scenario.fault_origin_type):
            continue
        alarms, label = _run_scenario(pack, big_graph, scenario)
        root = next(a for a in alarms if a.is_root)
        assert label.root_cause_alarm_type == root.alarm_type
        assert label.root_cause_alarm_type in vocab


# --- AC 25: ground-truth supports the oracle on the richer pack ----------------------------


def test_ac25_distinct_root_alarm_types_match_vocabulary(pack: DomainPack) -> None:
    """AC25: every scenario root alarmType is a 29-token-vocabulary member."""
    vocab = set(pack.alarm_type_vocabulary())
    assert len(vocab) == 29
    for scenario in SCENARIO_LIBRARY:
        assert scenario.root_alarm_type in vocab
