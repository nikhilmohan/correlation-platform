"""Wire-AlarmEvent + moid-sharing pure-logic tests (spec AC 2, 3, 7).

Builds wire ``AlarmEvent`` payloads from synthesized alarms via ``replay.synth_to_event`` and
asserts they satisfy the frozen ``acp_event_model`` binding (required fields incl. canonical
``alarmType``), that the alarmType is distinct from eventType/probableCause, and that every
emitted moid is shared with the topology snapshot.
"""

from __future__ import annotations

import itertools
import random
from datetime import UTC, datetime

import networkx as nx
import pytest
from acp_event_model import AlarmEvent
from acp_event_model import validate as validate_moid
from pydantic import ValidationError

from simulator.domains.coreip import alarm_shapes
from simulator.domains.coreip.scenario_library import SCENARIO_LIBRARY
from simulator.engine import cascade, replay, snapshot_writer, topology_builder
from simulator.engine.domain_pack import DomainPack, TopologyParams

_START = datetime(2026, 1, 1, tzinfo=UTC)


def _alarm_ids():
    return (f"ALM-{i:07d}" for i in itertools.count())


def _candidates(graph: nx.DiGraph, object_type: str) -> list[str]:
    return [m for m, d in graph.nodes(data=True) if d.get("objectType") == object_type]


@pytest.fixture
def big_graph(pack: DomainPack) -> nx.DiGraph:
    params = TopologyParams(node_count=50, site_count=10, interfaces_per_port=2, igp_area_count=3)
    return topology_builder.build_topology(pack, params, random.Random(99)).graph


def _all_scenario_alarms(pack: DomainPack, graph: nx.DiGraph):
    seq = _alarm_ids()
    alarms = []
    for scenario in SCENARIO_LIBRARY:
        cands = _candidates(graph, scenario.fault_origin_type)
        if not cands:
            continue
        a, _ = cascade.propagate(
            pack,
            graph,
            scenario,
            cands[0],
            scenario_id=f"sc-{scenario.scenario_type}-000",
            start_at=_START,
            base_interval_ms=400.0,
            jitter_stddev_ms=0.0,
            rng=random.Random(0),
            alarm_id_seq=seq,
        )
        alarms.extend(a)
    return alarms


# --- AC 7: emitted AlarmEvents validate against the frozen schema --------------------------


def test_ac7_synth_to_event_validates_against_frozen_binding(
    pack: DomainPack, big_graph: nx.DiGraph
) -> None:
    """AC7: every synthesized alarm becomes an AlarmEvent the frozen binding accepts."""
    alarms = _all_scenario_alarms(pack, big_graph)
    assert alarms
    vocab = set(pack.alarm_type_vocabulary())
    for synth in alarms:
        event = replay.synth_to_event(synth)
        # round-trips through the frozen binding without raising
        revalidated = AlarmEvent.model_validate(event.model_dump(by_alias=True))
        assert revalidated.alarmType == synth.alarm_type
        # required fields present + non-empty
        assert event.alarmId
        assert event.managedObjectId
        assert event.eventType
        assert event.probableCause
        assert event.alarmType in vocab
        assert event.perceivedSeverity
        assert event.raisedAt is not None
        # state is the frozen State enum; its value is one of raised/cleared
        assert getattr(event.state, "value", event.state) in ("raised", "cleared")
        assert event.trailIds == []


def test_ac7_alarm_type_distinct_from_event_type_and_probable_cause(pack: DomainPack) -> None:
    """AC7: alarmType is a canonical join token, distinct from X.733 eventType/probableCause."""
    for token in pack.alarm_type_vocabulary():
        shape = pack.alarm_shape(token)
        assert shape.alarm_type == token
        # the canonical token is its own space — not equal to the X.733 category/cause
        assert shape.alarm_type != shape.event_type
        assert shape.alarm_type != shape.probable_cause


def test_ac7_missing_alarm_type_fails_validation() -> None:
    """AC7: an AlarmEvent payload missing alarmType MUST fail the frozen binding (required[])."""
    payload = {
        "alarmId": "ALM-0000001",
        "managedObjectId": "Node:N0",
        "eventType": "communicationsAlarm",
        "probableCause": "lossOfSignal",
        # alarmType intentionally omitted
        "perceivedSeverity": "critical",
        "raisedAt": _START.isoformat(),
        "state": "raised",
        "trailIds": [],
    }
    with pytest.raises(ValidationError):
        AlarmEvent.model_validate(payload)


def test_ac7_vocabulary_is_29_tokens(pack: DomainPack) -> None:
    """AC7/AC25: the canonical Core-IP alarmType vocabulary is the 29-token set."""
    vocab = pack.alarm_type_vocabulary()
    assert len(vocab) == 29
    assert len(set(vocab)) == 29
    assert vocab == alarm_shapes.ALARM_TYPE_VOCABULARY


# --- AC 2 + AC 3: moids shared between snapshot and alarms; conform to scheme ---------------


def test_ac2_emitted_moids_are_subset_of_snapshot(pack: DomainPack, big_graph: nx.DiGraph) -> None:
    """AC2: every emitted alarm moid is present in the topology snapshot from the same run."""
    snapshot = snapshot_writer.graph_to_snapshot(big_graph, pack.domain_id())
    snapshot_moids = {n["managedObjectId"] for n in snapshot["nodes"]}
    alarms = _all_scenario_alarms(pack, big_graph)
    emitted = {a.managed_object_id for a in alarms}
    assert emitted, "expected emitted alarms"
    assert emitted.issubset(snapshot_moids), emitted - snapshot_moids


def test_ac3_emitted_moids_pass_frozen_validator(pack: DomainPack, big_graph: nx.DiGraph) -> None:
    """AC3: every emitted moid validates against the frozen event-model moid validator."""
    for synth in _all_scenario_alarms(pack, big_graph):
        assert validate_moid(synth.managed_object_id) == synth.managed_object_id
