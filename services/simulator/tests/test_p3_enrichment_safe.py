"""P3 enrichment-safe cascade tests (AC 59-65).

Each test maps 1:1 to a spec acceptance criterion. Trail Builder / Pattern Manager / Topology are
mocked from their published OpenAPI shapes; no live services.
"""

from __future__ import annotations

import random
from datetime import UTC, datetime
from pathlib import Path

from simulator.config.settings import load_settings
from simulator.domains.coreip.pack import CoreIPPack
from simulator.integrations.pattern_manager_client import MockPatternManagerClient
from simulator.integrations.topology_snapshot_client import MockTopologySnapshotClient
from simulator.integrations.trail_builder_client import MockTrailBuilderClient
from simulator.synth import aligned_synth, enrichment_safe, p3_run
from simulator.synth.enrichment_safe import SpacingBounds, SpacingConflict
from simulator.synth.models import PatternView
from tests import p3_fixtures as fx


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list = []

    def produce(self, topic, envelope) -> None:
        self.sent.append((topic, envelope))

    def flush(self) -> None:
        pass


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


def _pattern(pattern_id="pat-01", trail_id="trail-A", window_ms=30000) -> dict:
    return fx.pattern_view(
        pattern_id,
        trail_id,
        [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", False)],
        "IPLinkDown",
        window_ms=window_ms,
    )


def _trail_bodies(count: int = 40):
    """A fleet of ``count`` compatible trails across 3 igp-areas (each hosts IPLink/IGP/LSP)."""
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
    # Named trails the conflict test references as discovery trails.
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


def _run(settings, producer, patterns):
    pm = MockPatternManagerClient(patterns)
    tb = MockTrailBuilderClient(_trail_bodies())
    ts = MockTopologySnapshotClient([fx.snapshot_summary()])
    return p3_run.run_synth(
        settings,
        producer,
        run_id="es-test",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
    )


def _aligned_cascades(outcome, producer):
    """Group emitted aligned alarms into per-cascade lists via label child/root ids."""
    by_id = {e.payload.alarmId: e.payload for _, e in producer.sent}
    cascades = []
    for label in outcome.labels.all():
        if label.scenario_type != "pattern-aligned":
            continue
        ids = [label.root_cause_alarm_id, *label.child_alarm_ids]
        cascades.append([by_id[i] for i in ids if i in by_id])
    return cascades


# --- AC 59: distinct object/type per element within dedup window ------------------------------
def test_cascade_elements_distinct_objects(tmp_path: Path) -> None:
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [_pattern()])
    cascades = _aligned_cascades(outcome, producer)
    assert len(cascades) >= 10
    for cascade in cascades:
        pairs = [(a.managedObjectId, a.alarmType) for a in cascade]
        assert len(pairs) == len(set(pairs))  # no duplicate (moid, type) in a cascade
        moids = [a.managedObjectId for a in cascade]
        assert len(moids) == len(set(moids))  # each element on a distinct managed object


# --- AC 60 (corrected): transient ALARMTYPE is NOT excluded from aligned cascades --------------
# Premise correction: transience is a per-instance RAISE+CLEAR property (SelfClearStep releases a
# raise with no matching clear; FlapDampStep collapses raise/clear flapping), NOT an alarmType
# identity. Aligned cascade elements are sustained single raises (no clear emitted) so they are
# self-clear-safe and flap-safe regardless of alarmType — even when the same alarmType is used as
# self-clearing NOISE elsewhere. assert_cascade_safe therefore applies NO alarmType blocklist.
def test_cascade_with_transient_alarmtypes_is_safe() -> None:
    """A sustained-raise aligned cascade whose members ARE pack self-clearing types is safe."""
    transients = enrichment_safe.transient_types(CoreIPPack(), set())
    assert transients  # pack-derived, non-empty (PortDown/CRCErrors/InterfaceErrors etc.)
    from simulator.engine.models import SynthAlarm

    base = datetime.now(tz=UTC)
    # Each element: a sustained raise on a DISTINCT managedObjectId; alarmType IS a transient type.
    cascade = [
        SynthAlarm(
            alarm_id=f"alm-{i}",
            managed_object_id=f"Port:p{i}",  # distinct object per element -> distinct dedup key
            alarm_type=t,
            event_type="e",
            probable_cause="p",
            perceived_severity="warning",
            raised_at=base,
        )
        for i, t in enumerate(sorted(transients))
    ]
    # No EnrichmentSafetyError: transient alarmType on a sustained raise is enrichment-safe.
    enrichment_safe.assert_cascade_safe(cascade, dedup_window_ms=2000)


def test_queuedrop_cascade_with_transient_members_not_rejected(tmp_path: Path) -> None:
    """The real QueueDrop pattern (QueueDrop,PortDown,InterfaceDown,CRCErrors,InterfaceErrors) and a
    CRCErrors-rooted pattern synthesize aligned cascades and are NOT excluded (AC 60 corrected).

    PortDown/CRCErrors/InterfaceErrors are pack self-clearing NOISE types; as SUSTAINED cascade
    raises they must pass. Drives the full synth and asserts >0 aligned cascades emitted, none of
    the patterns is dropped, and every emitted cascade member is a `raised` event (no `cleared`).
    """
    queuedrop = fx.pattern_view(
        "pat-queuedrop",
        "trail-A",
        [
            ("QueueDrop", False),
            ("PortDown", False),
            ("InterfaceDown", False),
            ("CRCErrors", False),
            ("InterfaceErrors", False),
        ],
        "QueueDrop",
        window_ms=30000,
    )
    crc = fx.pattern_view(
        "pat-crc",
        "trail-B",
        [("CRCErrors", False), ("InterfaceErrors", False)],
        "CRCErrors",  # root cause IS a self-clearing type -> must not be excluded
        window_ms=30000,
    )
    # Trails must host the required objectTypes these patterns manifest on (Interface, Port) per the
    # default sampleAlarm prefixes; build a fleet that hosts them across 3 igp-areas.
    areas = ["area-0", "area-1", "area-2"]
    bodies: dict = {}
    for i in range(40):
        tid = f"trail-{i:03d}"
        bodies[tid] = fx.trail_detail(
            tid,
            [
                (f"Interface:{i}", "Interface"),
                (f"Port:{i}", "Port"),
                (f"IPLink:{i}", "IPLink"),
            ],
            igp_area=areas[i % 3],
        )
    for tid, area in (("trail-A", "area-0"), ("trail-B", "area-1")):
        bodies[tid] = fx.trail_detail(
            tid,
            [
                (f"Interface:{tid}", "Interface"),
                (f"Port:{tid}", "Port"),
                (f"IPLink:{tid}", "IPLink"),
            ],
            igp_area=area,
        )
    producer = FakeProducer()
    pm = MockPatternManagerClient([queuedrop, crc])
    tb = MockTrailBuilderClient(bodies)
    ts = MockTopologySnapshotClient([fx.snapshot_summary()])
    outcome = p3_run.run_synth(
        _settings(tmp_path),
        producer,
        run_id="es-crc-test",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
    )

    # Nothing excluded for a transient alarmType; both patterns produce aligned cascades.
    assert outcome.summary.enrichment_conflict_patterns == []
    aligned = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert aligned
    assert {x.pattern_id for x in aligned} == {"pat-queuedrop", "pat-crc"}
    cascades = _aligned_cascades(outcome, producer)
    assert len(cascades) > 0
    # Every emitted cascade member is a `raised` event; NO `cleared` is emitted for cascade members.
    for cascade in cascades:
        for a in cascade:
            assert str(getattr(a.state, "value", a.state)) == "raised"


def test_transient_types_config_override_wins() -> None:
    override = enrichment_safe.transient_types(CoreIPPack(), {"CustomTransient"})
    assert override == frozenset({"CustomTransient"})  # config override, not pack-derived (noise)


# --- AC 61 (corrected): natural timing within the session window ------------------------------
def test_cascade_spacing_within_window(tmp_path: Path) -> None:
    """Cascades fit inside the session window using natural timing.

    Corrected model: cascade elements have DISTINCT dedup keys (distinct managedObjectId + distinct
    alarmType per position), so enrichment never dedups them and there is NO dedup-window floor.
    The only remaining spacing invariant is that the whole cascade stays within the session window.
    """
    producer = FakeProducer()
    outcome = _run(
        _settings(tmp_path, P3_ENRICHMENT_DEDUP_WINDOW_MS="2000"), producer, [_pattern()]
    )
    cascades = _aligned_cascades(outcome, producer)
    assert len(cascades) >= 10
    for cascade in cascades:
        ordered = sorted(cascade, key=lambda a: a.raisedAt)
        span_ms = (ordered[-1].raisedAt - ordered[0].raisedAt).total_seconds() * 1000.0
        assert span_ms <= 30000  # within session window
        # elements have distinct dedup keys -> enrichment-safe by construction (no floor needed)
        keys = [(a.managedObjectId, a.alarmType) for a in cascade]
        assert len(keys) == len(set(keys))


# --- AC 62 (corrected): distinct-key cascade NOT excluded; only degenerate window conflicts ----
def test_small_window_pattern_not_excluded(tmp_path: Path) -> None:
    """A pattern whose windowMs is below the dedup window is NO LONGER excluded.

    Corrected model: cascade elements have distinct dedup keys, so enrichment never dedups them;
    windowMs < dedup window is not a conflict. The pattern must still emit aligned cascades.
    """
    small = _pattern("pat-small", "trail-A", window_ms=1500)
    ok = _pattern("pat-ok", "trail-B", window_ms=30000)
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [small, ok])
    assert outcome.summary.enrichment_conflict_patterns == []  # nothing excluded
    aligned = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert aligned
    # BOTH patterns are eligible and contribute aligned cascades.
    assert {x.pattern_id for x in aligned} == {"pat-small", "pat-ok"}


def test_reconcile_spacing_degenerate_window_conflicts() -> None:
    # Only a degenerate windowMs <= 0 conflicts now; a distinct-key cascade never does otherwise.
    result = enrichment_safe.reconcile_spacing(2000, 0, 3, spacing_margin=0.1, in_window_margin=0.9)
    assert isinstance(result, SpacingConflict)


def test_reconcile_spacing_small_window_returns_bounds() -> None:
    # windowMs (1500) BELOW the dedup window (2000) no longer conflicts: distinct-key cascade.
    result = enrichment_safe.reconcile_spacing(
        2000, 1500, 3, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingBounds)
    assert result.lo_ms == 0.0  # no dedup-window floor
    assert result.hi_ms > 0.0


def test_reconcile_spacing_returns_bounds() -> None:
    result = enrichment_safe.reconcile_spacing(
        2000, 30000, 3, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingBounds)
    assert result.lo_ms == 0.0  # natural floor, NOT the dedup window
    # per-gap upper budget fits the whole cascade in the window: 30000*0.9 / (3-1) = 13500
    assert result.hi_ms == 30000 * 0.9 / 2


# --- AC 63: no flap-damping trigger -----------------------------------------------------------
def test_cascade_no_flap(tmp_path: Path) -> None:
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [_pattern()])
    cascades = _aligned_cascades(outcome, producer)
    for cascade in cascades:
        # every element is a raise; no (moid, type) appears twice.
        for a in cascade:
            assert str(getattr(a.state, "value", a.state)) == "raised"
        pairs = [(a.managedObjectId, a.alarmType) for a in cascade]
        assert len(pairs) == len(set(pairs))


# --- AC 64: non-aligned/noise not constrained -------------------------------------------------
def test_nonaligned_not_enrichment_constrained(tmp_path: Path) -> None:
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [_pattern()])
    transients = enrichment_safe.transient_types(CoreIPPack(), set())
    non_aligned_labels = [x for x in outcome.labels.all() if x.scenario_type != "pattern-aligned"]
    # non-aligned/noise labels exist and are NOT constrained by the aligned enrichment-safe guard —
    # noise IS allowed to be transient (raise+clear self-clearing pairs). The synthesizer never
    # calls assert_cascade_safe on non-aligned output.
    assert non_aligned_labels
    scenario_types = {x.scenario_type for x in non_aligned_labels}
    assert scenario_types & {"non-aligned", "partial-cascade", "noise"}
    # The aligned guard applies NO alarmType blocklist: a sustained raise carrying a transient
    # alarmType (distinct object) is enrichment-safe and must NOT be rejected (AC 60 corrected).
    from simulator.engine.models import SynthAlarm

    sustained_transient = [
        SynthAlarm(
            alarm_id="x",
            managed_object_id="Port:m1",
            alarm_type=next(iter(transients)),
            event_type="e",
            probable_cause="p",
            perceived_severity="warning",
            raised_at=datetime.now(tz=UTC),
        )
    ]
    # No EnrichmentSafetyError: transient alarmType on a sustained raise is safe.
    enrichment_safe.assert_cascade_safe(sustained_transient, dedup_window_ms=2000)


# --- AC 65: summary records enrichmentSafeCount + enrichmentConflictPatterns -------------------
def test_summary_enrichment_fields(tmp_path: Path) -> None:
    # Corrected model: distinct-key patterns are never excluded -> conflict list is empty and
    # every emitted aligned alarm is enrichment-safe by construction.
    small = _pattern("pat-small", "trail-A", window_ms=1500)
    ok = _pattern("pat-ok", "trail-B", window_ms=30000)
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [small, ok])
    d = outcome.summary.to_json()
    # enrichmentSafeCount is the EXPECTED CORRELATED count (closed-loop yield basis), not the
    # emitted aligned length; it is <= aligned_alarms (yield < 1) and > 0.
    assert d["enrichmentSafeCount"] == outcome.summary.expected_correlated_alarms > 0
    assert d["enrichmentSafeCount"] <= outcome.summary.aligned_alarms
    assert d["expectedCorrelatedAlarms"] == d["enrichmentSafeCount"]
    assert d["enrichmentConflictPatterns"] == []


def test_place_distinct_draws_without_replacement() -> None:
    pack = CoreIPPack()
    from simulator.synth.models import TrailDetail, TrailMember

    trail = TrailDetail(
        "trail-A",
        (
            TrailMember("IPLink:1", "IPLink"),
            TrailMember("IPLink:2", "IPLink"),
            TrailMember("IPLink:3", "IPLink"),
        ),
    )
    rng = random.Random(3)
    used: set[str] = set()
    target_type = pack.placement_affinity().get("IPLinkDown")
    picks = [
        aligned_synth._place_distinct("IPLinkDown", target_type, trail, rng, used) for _ in range(3)
    ]
    assert len({m.managed_object_id for m in picks}) == 3  # all distinct


def test_view_roundtrip() -> None:
    v = PatternView.from_api(_pattern())
    assert v.pattern_id == "pat-01"


def test_view_parses_sample_alarms() -> None:
    """PatternView parses the published sampleAlarms[] (managedObjectId + alarmType)."""
    obj = _pattern()
    obj["sampleAlarms"] = [
        {"managedObjectId": "FiberSpan:f1", "alarmType": "QueueDrop"},
        {"managedObjectId": "Interface:i1", "alarmType": "QueueDrop"},
    ]
    v = PatternView.from_api(obj)
    assert [s.managed_object_id for s in v.sample_alarms] == ["FiberSpan:f1", "Interface:i1"]
    assert {s.object_type for s in v.sample_alarms} == {"FiberSpan", "Interface"}
    # Absent sampleAlarms -> empty tuple (backward-compat, triggers the fallback path).
    obj.pop("sampleAlarms")
    assert PatternView.from_api(obj).sample_alarms == ()
