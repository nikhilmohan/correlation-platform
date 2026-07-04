"""P3 enrichment-safe cascade tests (AC 59-65).

Each test maps 1:1 to a spec acceptance criterion. Trail Builder / Pattern Manager / Topology are
mocked from their published OpenAPI shapes; no live services.
"""

from __future__ import annotations

import logging
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


# --- AC 60: no transient/self-clearing cascade members ----------------------------------------
def test_cascade_excludes_transient_types(tmp_path: Path) -> None:
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [_pattern()])
    transients = enrichment_safe.transient_types(CoreIPPack(), set())
    assert transients  # pack-derived, non-empty
    cascades = _aligned_cascades(outcome, producer)
    for cascade in cascades:
        for a in cascade:
            assert a.alarmType not in transients


def test_transient_types_config_override_wins() -> None:
    override = enrichment_safe.transient_types(CoreIPPack(), {"CustomTransient"})
    assert override == frozenset({"CustomTransient"})  # config override, not pack-derived


# --- AC 61: inter-arrival above dedup, within session window ----------------------------------
def test_cascade_spacing_bounds(tmp_path: Path) -> None:
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
        for prev, nxt in zip(ordered, ordered[1:], strict=False):
            gap = (nxt.raisedAt - prev.raisedAt).total_seconds() * 1000.0
            assert gap >= 2000  # above dedup window


# --- AC 62: conflicting pattern excluded + logged ---------------------------------------------
def test_conflict_pattern_excluded(tmp_path: Path, caplog) -> None:
    # window <= dedup -> impossible to be both above dedup and within window.
    conflicting = _pattern("pat-conflict", "trail-A", window_ms=1500)
    ok = _pattern("pat-ok", "trail-B", window_ms=30000)
    producer = FakeProducer()
    with caplog.at_level(logging.WARNING):
        outcome = _run(_settings(tmp_path), producer, [conflicting, ok])
    assert "pat-conflict" in outcome.summary.enrichment_conflict_patterns
    assert any(getattr(r, "event", "") == "p3.enrichment_window_conflict" for r in caplog.records)
    # run did NOT abort: the conflict-free pattern still emitted aligned cascades.
    aligned = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert aligned
    assert all(x.pattern_id != "pat-conflict" for x in aligned)


def test_reconcile_spacing_returns_conflict() -> None:
    result = enrichment_safe.reconcile_spacing(
        2000, 1500, 3, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingConflict)


def test_reconcile_spacing_returns_bounds() -> None:
    result = enrichment_safe.reconcile_spacing(
        2000, 30000, 3, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingBounds)
    assert result.lo_ms >= 2000  # strictly above dedup window


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
    # non-aligned/noise labels exist and were NOT filtered through the enrichment-safe transient
    # exclusion — they may legitimately carry transient types (the synthesizer never asserts them).
    assert non_aligned_labels
    scenario_types = {x.scenario_type for x in non_aligned_labels}
    assert scenario_types & {"non-aligned", "partial-cascade", "noise"}
    # assert_cascade_safe raises if a transient is present -> confirm it would flag non-aligned,
    # i.e. the guard is aligned-only by design (we never call it on non-aligned).
    from simulator.engine.models import SynthAlarm

    bad = [
        SynthAlarm(
            alarm_id="x",
            managed_object_id="m",
            alarm_type=next(iter(transients)),
            event_type="e",
            probable_cause="p",
            perceived_severity="warning",
            raised_at=datetime.now(tz=UTC),
        )
    ]
    try:
        enrichment_safe.assert_cascade_safe(bad, dedup_window_ms=2000, transients=transients)
        raised = False
    except enrichment_safe.EnrichmentSafetyError:
        raised = True
    assert raised  # the guard WOULD reject a transient -> proving it is applied only to aligned


# --- AC 65: summary records enrichmentSafeCount + enrichmentConflictPatterns -------------------
def test_summary_enrichment_fields(tmp_path: Path) -> None:
    conflicting = _pattern("pat-conflict", "trail-A", window_ms=1500)
    ok = _pattern("pat-ok", "trail-B", window_ms=30000)
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer, [conflicting, ok])
    d = outcome.summary.to_json()
    assert d["enrichmentSafeCount"] == outcome.summary.aligned_alarms > 0
    assert d["enrichmentConflictPatterns"] == ["pat-conflict"]


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
    picks = [
        aligned_synth._place_distinct("IPLinkDown", trail, pack.placement_affinity(), rng, used)
        for _ in range(3)
    ]
    assert len({m.managed_object_id for m in picks}) == 3  # all distinct


def test_view_roundtrip() -> None:
    v = PatternView.from_api(_pattern())
    assert v.pattern_id == "pat-01"
