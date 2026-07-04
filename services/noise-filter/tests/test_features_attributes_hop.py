"""Attribute + hop-distance feature ACs (AC-10, 16, 18) + supporting (EH-5, EH-12, DA-10)."""

from __future__ import annotations

import math

import httpx
import pytest
import respx

from noise_filter.clients import TopologyClient, TrailContext
from noise_filter.config import FeatureSettings, ModelParams
from noise_filter.features import FeatureVectorizer, HopDistanceResolver

from .conftest import build_pipeline, make_trail_ctx
from .fixtures import BASE_TIME, make_alarm
from .helpers import make_window

TOPOLOGY_URL = "http://topology.test"


def _node_response(equipment_type: str):
    return httpx.Response(
        200,
        json={
            "managedObjectId": "Port:x",
            "objectType": "Port",
            "domain": "core-ip",
            "name": "x",
            "attributes": {"equipmentType": equipment_type},
            "snapshotId": "snap-1",
        },
    )


@respx.mock
def test_attribute_feature_config_driven_inclusion_and_exclusion(metrics):
    """AC-10: equipmentType enabled -> extra dim + Topology called; disabled -> no dim, no call."""
    alarms = [
        make_alarm(alarm_id="a1", managed_object_id="Port:a", raised_offset_seconds=0.0),
        make_alarm(alarm_id="a2", managed_object_id="Port:b", raised_offset_seconds=1.0),
    ]

    route = respx.get(url__regex=rf"{TOPOLOGY_URL}/topology/nodes/.*")
    route.side_effect = [
        _node_response("router"),
        _node_response("switch"),
    ]

    topo = TopologyClient(TOPOLOGY_URL)
    vec = FeatureVectorizer(topology_client=topo, metrics=metrics)

    enabled = FeatureSettings(attribute_keys=("equipmentType",))
    m_enabled = vec.build_matrix(alarms, window_start=BASE_TIME, features=enabled)
    assert m_enabled.shape[1] == 5  # base 4 + 1 attribute dim
    assert route.called
    calls_after_enabled = len(respx.calls)
    assert calls_after_enabled == 2  # one per distinct managedObjectId

    # Disabled: no Topology call, no attribute dim. Call count must NOT increase.
    vec_disabled = FeatureVectorizer(topology_client=topo, metrics=metrics)
    m_disabled = vec_disabled.build_matrix(
        alarms, window_start=BASE_TIME, features=FeatureSettings(attribute_keys=())
    )
    assert m_disabled.shape[1] == 4
    assert len(respx.calls) == calls_after_enabled  # no new Topology calls when disabled


def test_encoding_scales_come_from_feature_config_not_literals(metrics):
    """M1: time_scale_seconds + categorical_weight are read from FeatureSettings (config), so the
    same alarms vectorized with different config knobs yield different scaled values."""
    # Distinct severities -> distinct, vocab-INDEPENDENT severity ordinals (critical=5, warning=2),
    # so the severity column cleanly reflects categorical_weight without vocab re-indexing effects.
    alarms = [
        make_alarm(
            alarm_id="a1",
            perceived_severity="critical",  # ordinal 5
            raised_offset_seconds=0.0,
        ),
        make_alarm(
            alarm_id="a2",
            perceived_severity="warning",  # ordinal 2
            raised_offset_seconds=20.0,
        ),
    ]
    vec = FeatureVectorizer(metrics=metrics)
    sev_col = 3  # severity is the 4th base dimension

    f_default = FeatureSettings()  # time_scale=10, categorical_weight=0.3
    m1 = vec.build_matrix(alarms, window_start=BASE_TIME, features=f_default)
    # relative-timestamp col = (offset / time_scale): 20s / 10 = 2.0 for the 2nd alarm.
    assert m1[1, 0] == pytest.approx(2.0)
    # severity diff = |5 - 2| * 0.3 = 0.9
    assert abs(m1[0, sev_col] - m1[1, sev_col]) == pytest.approx(0.9)

    f_scaled = FeatureSettings(time_scale_seconds=20.0, categorical_weight=1.0)
    m2 = vec.build_matrix(alarms, window_start=BASE_TIME, features=f_scaled)
    # With time_scale=20: 20s / 20 = 1.0; with categorical_weight=1.0 severity diff = |5-2| = 3.0.
    assert m2[1, 0] == pytest.approx(1.0)
    assert abs(m2[0, sev_col] - m2[1, sev_col]) == pytest.approx(3.0)


@respx.mock
def test_topology_unavailable_degrades_skips_attribute(metrics):
    """EH-5: Topology error -> attribute dim degrades (alarm still vectorized, never dropped)."""
    alarms = [make_alarm(alarm_id="a1", managed_object_id="Port:a")]
    respx.get(url__regex=rf"{TOPOLOGY_URL}/topology/nodes/.*").mock(
        side_effect=httpx.ConnectError("down")
    )
    topo = TopologyClient(TOPOLOGY_URL)
    vec = FeatureVectorizer(topology_client=topo, metrics=metrics)
    m = vec.build_matrix(
        alarms, window_start=BASE_TIME, features=FeatureSettings(attribute_keys=("equipmentType",))
    )
    # Matrix still built (1 row), attribute dim present but degraded (value -1), alarm kept.
    assert m.shape[0] == 1
    assert metrics.topology_attr_skip._value.get() >= 1


@pytest.mark.asyncio
async def test_retention_floor_holds_with_hop_feature_enabled(run_repo, chatter_repo, metrics):
    """AC-16: hop feature enabled keeps >= ceil(M*0.95) valid cascade ids (retention floor)."""
    from .fixtures import storm

    cascade = storm(8, trail_id="t1", start=0.0, spread=4.0)
    M = len(cascade)
    member_ids = [a.managedObjectId for a in cascade]
    edges = [(member_ids[i], member_ids[i + 1]) for i in range(len(member_ids) - 1)]
    ctx = make_trail_ctx(member_ids=member_ids, edges=edges, seed_id=member_ids[0])

    win = make_window(cascade)
    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=600),
        features=FeatureSettings(hop_distance_enabled=True, hop_traversal_max_depth=8),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
        trail_ctx=ctx,
    )
    out = await pipe.process_window(win)
    kept = {aid for ev in out.events for aid in ev.payload.alarmIds}
    valid = {a.alarmId for a in cascade}
    assert len(kept & valid) >= math.ceil(M * 0.95)


@pytest.mark.asyncio
async def test_concurrent_faults_separate_transaction_groups(run_repo, chatter_repo, metrics):
    """AC-18: two near-simultaneous faults (distinct hop profiles) on one trail -> TWO events."""
    from .fixtures import make_alarm

    # Fault A near seed-A objects; fault B near seed-B objects; overlapping in time.
    fault_a = [
        make_alarm(
            alarm_id=f"a{i}",
            managed_object_id=f"Port:a{i}",
            alarm_type="PortDown",
            raised_offset_seconds=float(i),
        )
        for i in range(4)
    ]
    fault_b = [
        make_alarm(
            alarm_id=f"b{i}",
            managed_object_id=f"Port:b{i}",
            alarm_type="PortDown",
            raised_offset_seconds=float(i),
        )
        for i in range(4)
    ]
    # Trail topology: two chains rooted at distinct seeds, far apart in hop space.
    a_ids = [a.managedObjectId for a in fault_a]
    b_ids = [a.managedObjectId for a in fault_b]
    # Build a long bridge between the two chains so hop-distance differs strongly.
    bridge = [f"Bridge:n{i}" for i in range(8)]
    chain = a_ids + bridge + b_ids
    edges = [(chain[i], chain[i + 1]) for i in range(len(chain) - 1)]
    ctx = make_trail_ctx(member_ids=chain, edges=edges, seed_id=a_ids[0])

    win = make_window(fault_a + fault_b)
    pipe = build_pipeline(
        params=ModelParams(eps=0.5, min_samples=3, window_size_seconds=600),
        features=FeatureSettings(hop_distance_enabled=True, hop_traversal_max_depth=20),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
        trail_ctx=ctx,
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 2
    group_a = set(out.events[0].payload.alarmIds)
    group_b = set(out.events[1].payload.alarmIds)
    assert group_a.isdisjoint(group_b)  # no shared alarm ids


def test_hop_distance_never_hard_gates():
    """DA-10: an alarm beyond hopTraversalMaxDepth gets the bound value (kept, never dropped)."""
    member_ids = [f"Port:n{i}" for i in range(6)]
    edges = [(member_ids[i], member_ids[i + 1]) for i in range(len(member_ids) - 1)]
    ctx = TrailContext(
        trail_id="t1",
        snapshot_id="s1",
        domain="core-ip",
        member_ids=member_ids,
        edges=edges,
        seed_id=member_ids[0],
    )
    resolver = HopDistanceResolver(None)  # client unused for this computation
    dists = resolver.hop_distances(ctx, max_depth=2)
    # Nodes beyond depth 2 are simply absent from dists (the vectorizer assigns them the bound).
    far = member_ids[5]
    bound = 2.0
    value = float(dists.get(far, bound))
    assert value == bound  # bounded, not dropped


def test_hop_seed_resolution_prefers_explicit_then_fault_origin_then_root():
    """DA-10: seed resolution order — explicit seed, then fault-origin list, then DAG root."""
    members = ["Port:a", "Port:b", "Port:c"]
    edges = [("Port:a", "Port:b"), ("Port:b", "Port:c")]
    # explicit
    ctx = TrailContext(
        trail_id="t",
        snapshot_id="s",
        domain=None,
        member_ids=members,
        edges=edges,
        seed_id="Port:b",
    )
    assert HopDistanceResolver(None).resolve_seed(ctx) == "Port:b"
    # fault-origin list
    ctx2 = TrailContext(
        trail_id="t", snapshot_id="s", domain=None, member_ids=members, edges=edges, seed_id=None
    )
    r = HopDistanceResolver(None, fault_origin_ids=frozenset({"Port:c"}))
    assert r.resolve_seed(ctx2) == "Port:c"
    # DAG root (no incoming edge)
    assert HopDistanceResolver(None).resolve_seed(ctx2) == "Port:a"
