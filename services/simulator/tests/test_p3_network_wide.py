"""P3 network-wide emission + closed-loop auto-correlation target tests (AC 47-58).

Each test maps 1:1 to a spec acceptance criterion. Trail Builder / Pattern Manager / Topology are
mocked from their published OpenAPI shapes (list + detail). No live services.
"""

from __future__ import annotations

import logging
import random
from pathlib import Path

from acp_event_model import AlarmEvent, TypedEnvelope

from simulator.config.settings import load_settings
from simulator.domains.coreip.pack import CoreIPPack
from simulator.integrations.pattern_manager_client import MockPatternManagerClient
from simulator.integrations.topology_snapshot_client import MockTopologySnapshotClient
from simulator.integrations.trail_builder_client import MockTrailBuilderClient
from simulator.synth import p3_fetch, p3_run, target_controller, trail_discovery
from simulator.synth.models import CompatibleTrail, CompatibleTrailSet, PatternView
from tests import p3_fixtures as fx


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, TypedEnvelope]] = []
        self.flushed = 0

    def produce(self, topic: str, envelope: TypedEnvelope) -> None:
        self.sent.append((topic, envelope))

    def flush(self) -> None:
        self.flushed += 1


def _settings(tmp_path: Path, **extra: str):
    env = {
        "SIM_MODE": "synth",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "SIM_OUTPUT_DIR": str(tmp_path),
        "PACING_MULTIPLIER": "0",
        "P3_TOTAL_ALARMS": "300",
        "P3_RNG_SEED": "4242",
        "P3_NETWORK_WIDE": "true",
        "P3_AUTO_CORRELATION_TARGET": "0.6",
        "P3_ENRICHMENT_DEDUP_WINDOW_MS": "2000",
        "phase": "p3",
    }
    env.update(extra)
    return load_settings(env)


# One pattern requiring IPLink (root) + IGPAdjacency; sessionWindow 30s so spacing reconciles.
def _pattern(pattern_id="pat-01", trail_id="trail-A", window_ms=30000) -> dict:
    return fx.pattern_view(
        pattern_id,
        trail_id,
        [("IPLinkDown", False), ("ISISAdjacencyDown", False)],
        "IPLinkDown",
        window_ms=window_ms,
    )


def _view(obj: dict) -> PatternView:
    return PatternView.from_api(obj)


# Three named trails across three igp-areas, each hosting IPLink + IGPAdjacency (compatible), used
# by the small structural tests. The named trails are trail-A/B/C for the AC 50/54 assertions.
def _trail_bodies():
    return {
        "trail-A": fx.trail_detail(
            "trail-A",
            [
                ("IPLink:a1", "IPLink"),
                ("IGPAdjacency:a2", "IGPAdjacency"),
                ("Interface:a3", "Interface"),
            ],
            igp_area="area-0",
        ),
        "trail-B": fx.trail_detail(
            "trail-B",
            [
                ("IPLink:b1", "IPLink"),
                ("IGPAdjacency:b2", "IGPAdjacency"),
                ("Interface:b3", "Interface"),
            ],
            igp_area="area-1",
        ),
        "trail-C": fx.trail_detail(
            "trail-C",
            [
                ("IPLink:c1", "IPLink"),
                ("IGPAdjacency:c2", "IGPAdjacency"),
                ("Interface:c3", "Interface"),
            ],
            igp_area="area-2",
        ),
    }


# A larger fleet: ``count`` compatible trails round-robined across 3 igp-areas, so
# distinct_trails x cap comfortably exceeds the cascade count the target needs (AC 51/52).
def _fleet_bodies(count: int = 40, discovery_trail: str = "trail-A"):
    bodies: dict = {}
    areas = ["area-0", "area-1", "area-2"]
    for i in range(count):
        tid = f"trail-{i:03d}"
        bodies[tid] = fx.trail_detail(
            tid,
            [
                (f"IPLink:{i}", "IPLink"),
                (f"IGPAdjacency:{i}", "IGPAdjacency"),
                (f"LSP:{i}", "LSP"),
                (f"Interface:{i}", "Interface"),
            ],
            igp_area=areas[i % 3],
        )
    # Ensure the named discovery trails resolve (trail-A/trail-B used by the multi-pattern tests).
    for tid, area in (("trail-A", "area-0"), ("trail-B", "area-1")):
        bodies[tid] = fx.trail_detail(
            tid,
            [
                (f"IPLink:{tid}", "IPLink"),
                (f"IGPAdjacency:{tid}", "IGPAdjacency"),
                (f"LSP:{tid}", "LSP"),
                (f"Interface:{tid}", "Interface"),
            ],
            igp_area=area,
        )
    return bodies


def _mock_clients(patterns, trail_bodies=None, trail_list=None):
    return (
        MockPatternManagerClient(patterns),
        MockTrailBuilderClient(trail_bodies or _trail_bodies(), trail_list=trail_list),
        MockTopologySnapshotClient([fx.snapshot_summary()]),
    )


def _run(settings, producer, patterns, trail_bodies=None, **kw):
    pm, tb, ts = _mock_clients(patterns, trail_bodies=trail_bodies)
    return p3_run.run_synth(
        settings,
        producer,
        run_id="nw-test",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
        **kw,
    )


# --- AC 47: hostability rule via the list endpoint --------------------------------------------
def test_discovery_hostability_filters_incompatible_trail() -> None:
    pack = CoreIPPack()
    pattern = _view(_pattern())
    trail_a = fx.trail_detail(
        "trail-A",
        [("IPLink:1", "IPLink"), ("IGPAdjacency:2", "IGPAdjacency"), ("Interface:3", "Interface")],
        igp_area="area-0",
    )
    trail_b = fx.trail_detail(
        "trail-B", [("Interface:4", "Interface"), ("Node:5", "Node")], igp_area="area-1"
    )
    tb = MockTrailBuilderClient({"trail-A": trail_a, "trail-B": trail_b})
    result = trail_discovery.discover_compatible_trails(
        pack, [pattern], tb, "snap-1", "core-ip", {"pat-01": "area-0"}
    )
    trail_ids = {ct.trail_id for ct in result["pat-01"].trails}
    assert trail_ids == {"trail-A"}  # Trail B lacks an IPLink member -> excluded


def test_discovery_includes_passing_discovery_trail() -> None:
    pack = CoreIPPack()
    pattern = _view(_pattern(trail_id="trail-A"))
    tb = MockTrailBuilderClient(_trail_bodies())
    result = trail_discovery.discover_compatible_trails(
        pack, [pattern], tb, "snap-1", "core-ip", {"pat-01": "area-0"}
    )
    assert "trail-A" in {ct.trail_id for ct in result["pat-01"].trails}


# --- Bug fix: required objectTypes come from sampleAlarms prefixes, matching the CE -----------
def test_required_types_from_sample_alarm_prefixes() -> None:
    """_required_types is the distinct set of sampleAlarms managedObjectId prefixes (CE parity)."""
    pattern = _view(
        fx.pattern_view(
            "pat-qd",
            "trail-A",
            [("QueueDrop", False), ("CRCErrors", False)],
            "QueueDrop",
            sample_alarms=[
                ("FiberSpan:f1", "QueueDrop"),
                ("Interface:i1", "QueueDrop"),
                ("Port:p1", "CRCErrors"),
            ],
        )
    )
    required = trail_discovery._required_types(
        CoreIPPack(), pattern, CoreIPPack().placement_affinity()
    )
    assert required == {"FiberSpan", "Interface", "Port"}


def test_queuedrop_style_finds_compatible_trail_not_requiring_vpnservice() -> None:
    """The live bug: QueueDrop's affinity wrongly required VPNService (0 trails); the real
    sampleAlarms land on {FiberSpan, Interface, Port}. A trail hosting those is now compatible,
    and one missing Port is not — with NO VPNService member anywhere."""
    pack = CoreIPPack()
    pattern = _view(
        fx.pattern_view(
            "pat-qd",
            "trail-A",
            [("QueueDrop", False)],
            "QueueDrop",
            sample_alarms=[
                ("FiberSpan:f1", "QueueDrop"),
                ("Interface:i1", "QueueDrop"),
                ("Port:p1", "QueueDrop"),
            ],
        )
    )
    hosting = fx.trail_detail(
        "trail-A",
        [("FiberSpan:f9", "FiberSpan"), ("Interface:i9", "Interface"), ("Port:p9", "Port")],
        igp_area="area-0",
    )
    missing_port = fx.trail_detail(
        "trail-B",
        [("FiberSpan:f8", "FiberSpan"), ("Interface:i8", "Interface")],
        igp_area="area-1",
    )
    tb = MockTrailBuilderClient({"trail-A": hosting, "trail-B": missing_port})
    result = trail_discovery.discover_compatible_trails(
        pack, [pattern], tb, "snap-1", "core-ip", {"pat-qd": "area-0"}
    )
    trail_ids = {ct.trail_id for ct in result["pat-qd"].trails}
    assert trail_ids == {"trail-A"}  # under the old affinity this was {} (required VPNService)


def test_required_types_fallback_to_affinity_when_no_sample_alarms() -> None:
    """A pattern with NO sampleAlarms falls back to the pack affinity-derived required set."""
    pattern = _view(
        fx.pattern_view(
            "pat-legacy",
            "trail-A",
            [("IPLinkDown", False), ("ISISAdjacencyDown", False)],
            "IPLinkDown",
            sample_alarms=[],
        )
    )
    assert pattern.sample_alarms == ()
    required = trail_discovery._required_types(
        CoreIPPack(), pattern, CoreIPPack().placement_affinity()
    )
    assert required == {"IPLink", "IGPAdjacency"}


def test_placement_prefers_sample_alarm_object_type() -> None:
    """Emit-time placement puts an alarmType on the objectType it appeared on in sampleAlarms."""
    import random as _random
    from datetime import UTC, datetime

    from simulator.synth import aligned_synth

    pattern = _view(
        fx.pattern_view(
            "pat-qd",
            "trail-A",
            [("QueueDrop", False)],
            "QueueDrop",
            sample_alarms=[("Interface:i1", "QueueDrop")],
        )
    )
    # Trail hosts both a VPNService (old affinity target) and an Interface (real sample target).
    trail = fx.trail_detail(
        "trail-A",
        [("VPNService:v1", "VPNService"), ("Interface:i9", "Interface")],
        igp_area="area-0",
    )
    from simulator.synth.models import TrailDetail

    detail = TrailDetail.from_api(trail)
    cascade = aligned_synth.build_cascade(
        CoreIPPack(),
        pattern,
        detail,
        _random.Random(1),
        datetime(2026, 1, 1, tzinfo=UTC),
    )
    moids = {a.managed_object_id for a in cascade.alarms}
    assert moids == {"Interface:i9"}  # placed on the sampleAlarm objectType, not VPNService


# --- AC 48: cached compatible trails, zero re-fetch on a second run ----------------------------
def test_discovery_cached_second_run_zero_calls(tmp_path: Path) -> None:
    path = tmp_path / "p3-config-snapshot.json"
    patterns = [_pattern()]
    _run(_settings(tmp_path, P3_CONFIG_SNAPSHOT_PATH=str(path)), FakeProducer(), patterns)
    assert path.exists()
    import json

    snap = json.loads(path.read_text())
    assert snap["schemaVersion"] == 2
    assert snap["compatibleTrails"]["pat-01"]["trails"]  # cached

    pm, tb, ts = _mock_clients(patterns)
    p3_run.run_synth(
        _settings(tmp_path, P3_CONFIG_SNAPSHOT_PATH=str(path)),
        FakeProducer(),
        run_id="nw-2",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
    )
    assert tb.list_calls == 0 and tb.calls == 0  # zero GET /trails and zero GET /trails/{id}
    assert pm.calls == 0 and ts.calls == 0


# --- AC 49: distribute across trails, prefer distinct areas -----------------------------------
def test_distribution_one_per_area_under_cap(tmp_path: Path) -> None:
    settings = _settings(tmp_path, P3_MAX_CASCADES_PER_TRAIL="3")
    pattern = _view(_pattern())
    compatible = {
        "pat-01": CompatibleTrailSet(
            "pat-01",
            "area-0",
            (
                CompatibleTrail("trail-A", "area-0"),
                CompatibleTrail("trail-B", "area-1"),
                CompatibleTrail("trail-C", "area-2"),
            ),
        )
    }
    # Force exactly 3 cascades via a target that rounds to a small A.
    plan = target_controller._distribute(
        settings, pattern, 3, compatible["pat-01"], random.Random(1)
    )
    entries, shortfall = plan
    assert shortfall == 0
    by_trail = {}
    for e in entries:
        by_trail[e.trail_id] = by_trail.get(e.trail_id, 0) + 1
    assert len(by_trail) == 3  # one per distinct area/trail
    assert all(v <= 3 for v in by_trail.values())


# --- AC 50: each cascade uses THAT trail's members --------------------------------------------
def test_cascade_moids_belong_to_assigned_trail(tmp_path: Path) -> None:
    producer = FakeProducer()
    bodies = _fleet_bodies()
    outcome = _run(_settings(tmp_path), producer, [_pattern()], trail_bodies=bodies)
    # Per-trail member sets (from the mock detail bodies).
    trail_members = {
        tid: {m["managedObjectId"] for m in body["members"]} for tid, body in bodies.items()
    }
    by_id = {e.payload.alarmId: e.payload for _, e in producer.sent}
    # For each aligned cascade label, every element's moid must belong to THAT label's trail.
    checked = 0
    for label in outcome.labels.all():
        if label.scenario_type != "pattern-aligned":
            continue
        ids = [label.root_cause_alarm_id, *label.child_alarm_ids]
        for alarm_id in ids:
            if alarm_id in by_id:
                assert by_id[alarm_id].managedObjectId in trail_members[label.trail_id]
                checked += 1
    assert checked > 0


# --- AC 51: closed-loop hits CE post-enrichment target within tolerance ------------------------
def test_target_controller_hits_rate_within_tolerance(tmp_path: Path) -> None:
    producer = FakeProducer()
    patterns = [_pattern()]
    outcome = _run(_settings(tmp_path), producer, patterns, trail_bodies=_fleet_bodies())
    rate = outcome.summary.enrichment_safe_count / 300
    assert 0.57 <= rate <= 0.63
    assert outcome.summary.enrichment_safe_count == outcome.summary.aligned_alarms
    assert (
        abs(outcome.summary.aligned_fraction_emitted - 0.6) < 1e-6
    )  # margin 0 -> emitted == target


def test_target_over_provision_margin_records_emitted_fraction(tmp_path: Path) -> None:
    producer = FakeProducer()
    patterns = [_pattern()]
    outcome = _run(
        _settings(tmp_path, P3_ENRICHMENT_OVER_PROVISION_MARGIN="0.1"),
        producer,
        patterns,
        trail_bodies=_fleet_bodies(),
    )
    # emitted fraction = TARGET / (1 - margin) = 0.6 / 0.9
    assert abs(outcome.summary.aligned_fraction_emitted - (0.6 / 0.9)) < 1e-6


# --- AC 52: recalculates for different targets ------------------------------------------------
def test_target_controller_scales_with_target(tmp_path: Path) -> None:
    patterns = [_pattern()]
    o4 = _run(
        _settings(tmp_path / "a", P3_AUTO_CORRELATION_TARGET="0.4"),
        FakeProducer(),
        patterns,
        trail_bodies=_fleet_bodies(),
    )
    o8 = _run(
        _settings(tmp_path / "b", P3_AUTO_CORRELATION_TARGET="0.8"),
        FakeProducer(),
        patterns,
        trail_bodies=_fleet_bodies(),
    )
    assert abs(o4.summary.aligned_fraction - 0.4) <= 0.03 + 1e-9
    assert abs(o8.summary.aligned_fraction - 0.8) <= 0.03 + 1e-9
    assert o8.summary.aligned_alarms > o4.summary.aligned_alarms


# --- AC 53: multiple distinct trails per pattern (labels) --------------------------------------
def test_labels_multiple_distinct_trails_per_pattern(tmp_path: Path) -> None:
    outcome = _run(_settings(tmp_path), FakeProducer(), [_pattern()], trail_bodies=_fleet_bodies())
    cascade_labels = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    trails = {x.trail_id for x in cascade_labels}
    assert len(trails) >= 2
    assert outcome.summary.distinct_trails_used >= 2


# --- AC 54: labels include igpArea + instanceIndex --------------------------------------------
def test_cascade_labels_have_igparea_and_instanceindex(tmp_path: Path) -> None:
    outcome = _run(_settings(tmp_path), FakeProducer(), [_pattern()])
    cascade_labels = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert cascade_labels
    for label in cascade_labels:
        d = label.to_json()
        for key in (
            "patternId",
            "trailId",
            "instanceIndex",
            "rootCauseAlarmId",
            "rootCauseAlarmType",
            "childAlarmIds",
            "scenarioType",
            "igpArea",
        ):
            assert key in d
        assert label.igp_area is not None
    # two cascades on different trails carry different trailId + igpArea
    a = next(x for x in cascade_labels if x.trail_id == "trail-A")
    b = next(x for x in cascade_labels if x.trail_id == "trail-B")
    assert a.igp_area != b.igp_area


# --- AC 55: shortfall logged, not fatal -------------------------------------------------------
def test_target_shortfall_logged_and_nonfatal(tmp_path: Path, caplog) -> None:
    # 2 trails, cap 1 -> at most 2 cascades per pattern; TARGET 0.9 x 1000 is unreachable.
    two_trails = {
        "trail-A": fx.trail_detail(
            "trail-A",
            [("IPLink:a1", "IPLink"), ("IGPAdjacency:a2", "IGPAdjacency")],
            igp_area="area-0",
        ),
        "trail-B": fx.trail_detail(
            "trail-B",
            [("IPLink:b1", "IPLink"), ("IGPAdjacency:b2", "IGPAdjacency")],
            igp_area="area-1",
        ),
    }
    patterns = [_pattern()]
    settings = _settings(
        tmp_path,
        P3_AUTO_CORRELATION_TARGET="0.9",
        P3_TOTAL_ALARMS="1000",
        P3_MAX_CASCADES_PER_TRAIL="1",
    )
    pm, tb, ts = _mock_clients(patterns, trail_bodies=two_trails)
    producer = FakeProducer()
    with caplog.at_level(logging.WARNING):
        outcome = p3_run.run_synth(
            settings,
            producer,
            run_id="nw-short",
            pattern_client=pm,
            trail_client=tb,
            snapshot_client=ts,
        )
    assert outcome.summary.shortfall_cascades > 0
    assert any(getattr(r, "event", "") == "p3.target_shortfall" for r in caplog.records)
    # non-fatal: run completed and emitted something.
    assert producer.sent


# --- AC 56: staggered trail-repeats on exhaustion ---------------------------------------------
def test_trail_repeat_staggered_instance_index(tmp_path: Path) -> None:
    # 2 trails, cap 3 -> 6 distinct-trail slots; force > 2 cascades so repeats (instanceIndex>=2).
    settings = _settings(tmp_path, P3_MAX_CASCADES_PER_TRAIL="3")
    pattern = _view(_pattern())
    compatible = CompatibleTrailSet(
        "pat-01",
        "area-0",
        (CompatibleTrail("trail-A", "area-0"), CompatibleTrail("trail-B", "area-1")),
    )
    entries, shortfall = target_controller._distribute(
        settings, pattern, 4, compatible, random.Random(7)
    )
    assert shortfall == 0
    indices = [e.instance_index for e in entries]
    assert max(indices) >= 2  # repeats carry instanceIndex >= 2


# --- AC 57: seeded reproducible; different seeds differ ---------------------------------------
def test_network_wide_seed_reproducible_and_varies(tmp_path: Path) -> None:
    patterns = [_pattern("pat-01", "trail-A"), _pattern("pat-02", "trail-B")]

    def triples(seed: str):
        pm, tb, ts = _mock_clients(patterns, trail_bodies=_fleet_bodies())
        out = p3_run.run_synth(
            _settings(tmp_path / seed, P3_RNG_SEED=seed),
            FakeProducer(),
            run_id="r",
            pattern_client=pm,
            trail_client=tb,
            snapshot_client=ts,
        )
        return [
            (x.pattern_id, x.trail_id, x.instance_index)
            for x in out.labels.all()
            if x.scenario_type == "pattern-aligned"
        ]

    a1 = triples("111")
    a2 = triples("111")
    assert a1 == a2  # same seed -> identical triples in the same order
    b = triples("999")
    assert a1[:5] != b[:5]  # different seed -> different assignments (high prob)


# --- AC 58: network-wide off -> single-trail behavior unchanged --------------------------------
def test_network_wide_off_single_trail_behavior(tmp_path: Path) -> None:
    patterns = [_pattern("pat-01", "trail-A")]
    pm, tb, ts = _mock_clients(patterns)
    settings = load_settings(
        {
            "SIM_MODE": "synth",
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "SIM_OUTPUT_DIR": str(tmp_path),
            "PACING_MULTIPLIER": "0",
            "P3_TOTAL_ALARMS": "80",
            "P3_RNG_SEED": "4242",
            "phase": "p3",
        }
    )
    assert not settings.p3_network_wide_active
    outcome = p3_run.run_synth(
        settings,
        FakeProducer(),
        run_id="off",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
    )
    # trail_discovery never runs -> zero GET /trails?... list calls.
    assert tb.list_calls == 0
    # each aligned cascade is on the pattern's discovery trail only.
    cascade_labels = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert cascade_labels
    assert {x.trail_id for x in cascade_labels} == {"trail-A"}


def test_fetch_config_off_makes_no_list_call(tmp_path: Path) -> None:
    patterns = [_pattern("pat-01", "trail-A")]
    pm, tb, ts = _mock_clients(patterns)
    settings = load_settings(
        {
            "SIM_MODE": "synth",
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "SIM_OUTPUT_DIR": str(tmp_path),
            "P3_TOTAL_ALARMS": "80",
            "phase": "p3",
        }
    )
    p3_fetch.fetch_config(settings, pattern_client=pm, trail_client=tb, snapshot_client=ts)
    assert tb.list_calls == 0


def test_emitted_alarms_are_valid_alarmevents(tmp_path: Path) -> None:
    producer = FakeProducer()
    _run(_settings(tmp_path), producer, [_pattern()])
    assert producer.sent
    for _, envelope in producer.sent:
        assert isinstance(envelope.payload, AlarmEvent)
        assert envelope.payload.alarmType


# --- Regression (live bug #392): real-shaped 5 patterns are NOT excluded, aligned > 0 ----------
# Root cause: the enrichment-safe synthesis wrongly required each cascade inter-arrival to exceed
# the enrichment dedup window (2000ms), which conflicted with a ~5000ms session window for a 4-6
# element cascade (~975ms/gap budget) -> reconcile_spacing reported a conflict -> plan_network_wide
# EXCLUDED all 5 patterns -> entries=0, aligned=0. Cascade elements have DISTINCT dedup keys
# (distinct managedObjectId + distinct alarmType per position) so enrichment NEVER dedups them; the
# blanket dedup-window floor was removed. This fixture mirrors the live shapes (windowMs ~5000, 4-6
# distinct-object elements) and asserts NO pattern is excluded and aligned cascades are produced.
def _real_shaped_patterns() -> list[dict]:
    # All member alarmTypes are NON-transient (transients are correctly excluded by AC 60), each
    # sequence position a distinct alarmType on a distinct objectType -> distinct dedup keys.
    specs = [
        (
            "pat-fiber",
            [
                ("LOS", False),
                ("InterfaceDown", False),
                ("ISISAdjacencyDown", False),
                ("LSPDown", False),
                ("VPNReachabilityLoss", False),
            ],
            "LOS",
        ),
        (
            "pat-linecard",
            [
                ("LineCardFault", False),
                ("InterfaceDown", False),
                ("IPLinkDown", False),
                ("ISISAdjacencyDown", False),
            ],
            "LineCardFault",
        ),
        (
            "pat-iplink",
            [
                ("IPLinkDown", False),
                ("ISISAdjacencyDown", False),
                ("LSPDown", False),
                ("VPNReachabilityLoss", False),
                ("ServiceDegraded", False),
            ],
            "IPLinkDown",
        ),
        (
            "pat-fibercut",
            [
                ("FiberCut", False),
                ("OpticalPowerLow", False),
                ("InterfaceDown", False),
                ("IPLinkDown", False),
                ("ISISAdjacencyDown", False),
                ("LSPDown", False),
            ],
            "FiberCut",
        ),
        (
            "pat-bgp",
            [
                ("BGPPeerDown", False),
                ("RouteFlap", False),
                ("VPNReachabilityLoss", False),
                ("ServiceDegraded", False),
            ],
            "BGPPeerDown",
        ),
    ]
    return [
        fx.pattern_view(pid, "trail-A", seq, root, window_ms=5000, median_ms=600.0, max_ms=1200.0)
        for pid, seq, root in specs
    ]


def _all_types_fleet(count: int = 40) -> dict:
    """A fleet of ``count`` compatible trails, each hosting every objectType the 5 patterns need."""
    object_types = [
        "FiberSpan",
        "LineCard",
        "Port",
        "Interface",
        "IPLink",
        "IGPAdjacency",
        "LSP",
        "VPNService",
    ]
    bodies: dict = {}
    areas = ["area-0", "area-1", "area-2"]
    tids = [f"trail-{i:03d}" for i in range(count)] + ["trail-A"]
    for i, tid in enumerate(tids):
        # 3 members per objectType so distinct placement has room even when two sequence positions
        # share an affine objectType (e.g. ISISAdjacencyDown + RouteFlap both -> IGPAdjacency).
        members = [(f"{ot}:{tid}-{k}", ot) for ot in object_types for k in range(3)]
        bodies[tid] = fx.trail_detail(tid, members, igp_area=areas[i % 3])
    return bodies


def test_five_real_shaped_patterns_not_excluded_and_aligned_positive(tmp_path: Path) -> None:
    patterns = _real_shaped_patterns()
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, patterns, trail_bodies=_all_types_fleet())
    # NO pattern excluded by the (removed) dedup-window/session-window conflict.
    assert outcome.summary.enrichment_conflict_patterns == []
    # aligned cascades ARE produced (the bug produced aligned=0).
    aligned = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert aligned
    assert outcome.summary.aligned_alarms > 0
    assert outcome.summary.enrichment_safe_count == outcome.summary.aligned_alarms
    # multiple of the 5 patterns contribute (target distributes across them).
    assert len({x.pattern_id for x in aligned}) >= 2
    # every cascade stays within its 5000ms session window and keeps distinct dedup keys.
    by_id = {e.payload.alarmId: e.payload for _, e in producer.sent}
    for label in aligned:
        ids = [label.root_cause_alarm_id, *label.child_alarm_ids]
        alarms = [by_id[i] for i in ids if i in by_id]
        ordered = sorted(alarms, key=lambda a: a.raisedAt)
        span_ms = (ordered[-1].raisedAt - ordered[0].raisedAt).total_seconds() * 1000.0
        assert span_ms <= 5000
        keys = [(a.managedObjectId, a.alarmType) for a in alarms]
        assert len(keys) == len(set(keys))  # distinct dedup keys -> enrichment-safe
