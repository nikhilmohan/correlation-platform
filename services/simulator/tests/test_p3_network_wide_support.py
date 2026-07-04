"""Supporting unit tests for P3 network-wide (not 1:1 with an AC but required by the design).

Covers: config fail-fast for each new knob, v1->v2 config-snapshot load compatibility,
``list_trails`` paging (mock), ``required_object_types`` derivation, ``reconcile_spacing`` boundary
math, and the additive /labels/p3-summary drift.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from simulator.config.settings import ConfigError, load_settings
from simulator.domains.coreip import p3_placement
from simulator.integrations.trail_builder_client import MockTrailBuilderClient
from simulator.synth import enrichment_safe, p3_config_snapshot
from simulator.synth.enrichment_safe import SpacingBounds, SpacingConflict


def _base_env(tmp_path: Path, **extra: str) -> dict[str, str]:
    env = {
        "SIM_MODE": "synth",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "SIM_OUTPUT_DIR": str(tmp_path),
        "P3_TOTAL_ALARMS": "300",
        "phase": "p3",
    }
    env.update(extra)
    return env


# --- config fail-fast for each new knob -------------------------------------------------------
@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("P3_AUTO_CORRELATION_TARGET", "1.5"),
        ("P3_AUTO_CORRELATION_TARGET", "-0.1"),
        ("P3_TARGET_TOLERANCE", "0"),
        ("P3_MAX_CASCADES_PER_TRAIL", "0"),
        ("P3_ENRICHMENT_OVER_PROVISION_MARGIN", "1.0"),
        ("P3_ENRICHMENT_OVER_PROVISION_MARGIN", "-0.1"),
        ("P3_ENRICHMENT_DEDUP_WINDOW_MS", "0"),
        ("P3_DEDUP_SPACING_MARGIN", "-0.1"),
        ("P3_CASCADE_YIELD", "1.5"),
        ("P3_PARTIAL_MATCH_TOLERANCE", "-1"),
        ("P3_EXPECTED_ENRICHMENT_TRIM", "-1"),
    ],
)
def test_invalid_network_wide_config_fails_fast(tmp_path: Path, key: str, value: str) -> None:
    with pytest.raises(ConfigError):
        load_settings(_base_env(tmp_path, **{key: value, "P3_NETWORK_WIDE": "true"}))


def test_valid_network_wide_config_loads(tmp_path: Path) -> None:
    s = load_settings(
        _base_env(
            tmp_path,
            P3_NETWORK_WIDE="true",
            P3_AUTO_CORRELATION_TARGET="0.6",
            P3_TARGET_TOLERANCE="0.03",
            P3_MAX_CASCADES_PER_TRAIL="3",
            P3_ENRICHMENT_OVER_PROVISION_MARGIN="0.1",
            P3_ENRICHMENT_DEDUP_WINDOW_MS="2000",
        )
    )
    assert s.p3_network_wide_active
    assert s.p3_max_cascades_per_trail == 3


def test_target_set_implies_network_wide_active(tmp_path: Path) -> None:
    s = load_settings(_base_env(tmp_path, P3_AUTO_CORRELATION_TARGET="0.6"))
    assert s.p3_network_wide_active  # target set -> active even without the explicit flag


def test_transient_types_parsed_from_config(tmp_path: Path) -> None:
    s = load_settings(
        _base_env(tmp_path, P3_ENRICHMENT_TRANSIENT_TYPES="A, B ,C", P3_NETWORK_WIDE="true")
    )
    assert s.p3_transient_type_set() == {"A", "B", "C"}


# --- Closed-loop CORRELATED-yield count math (the #392 fix) -----------------------------------
import math  # noqa: E402

from simulator.synth import target_controller  # noqa: E402
from simulator.synth.models import CompatibleTrail, CompatibleTrailSet, PatternView  # noqa: E402
from tests import p3_fixtures as fx  # noqa: E402


def _yield_settings(tmp_path: Path, **extra: str):
    return load_settings(
        _base_env(
            tmp_path,
            P3_NETWORK_WIDE="true",
            P3_AUTO_CORRELATION_TARGET="0.6",
            P3_TARGET_TOLERANCE="0.03",
            P3_RNG_SEED="4242",
            **extra,
        )
    )


def test_flat_yield_scales_correlated_per_cascade_with_length(tmp_path: Path) -> None:
    """Default flat yield (0.61): expected correlated per cascade == yield * L (bounded [1, L])."""
    s = _yield_settings(tmp_path)  # P3_CASCADE_YIELD default 0.61
    assert s.p3_cascade_yield == 0.61
    assert target_controller._correlated_per_cascade(s, 5) == 0.61 * 5
    assert target_controller._correlated_per_cascade(s, 2) == 0.61 * 2
    # Never below 1 (a cascade that fires yields at least one correlated member) nor above L.
    assert target_controller._correlated_per_cascade(s, 1) == 1.0
    assert target_controller._correlated_per_cascade(s, 3) <= 3.0


def test_derived_yield_is_length_minus_trim_bounded_by_firing_floor(tmp_path: Path) -> None:
    """P3_CASCADE_YIELD<=0 -> derived L - trim, but 0 when survivors < N - tolerance (no fire)."""
    # trim 1, tolerance 1: survived = L-1, floor = L-1 -> survives exactly at floor -> L-1.
    s = _yield_settings(
        tmp_path,
        P3_CASCADE_YIELD="0",
        P3_EXPECTED_ENRICHMENT_TRIM="1",
        P3_PARTIAL_MATCH_TOLERANCE="1",
    )
    assert target_controller._correlated_per_cascade(s, 5) == 4.0  # (L-1)/L survivors
    # trim 2, tolerance 1: survived = L-2 < firing floor (L-1) -> cannot fire -> 0 correlated.
    s2 = _yield_settings(
        tmp_path / "b",
        P3_CASCADE_YIELD="0",
        P3_EXPECTED_ENRICHMENT_TRIM="2",
        P3_PARTIAL_MATCH_TOLERANCE="1",
    )
    assert target_controller._correlated_per_cascade(s2, 5) == 0.0
    # trim 2, tolerance 2: survived = L-2 == firing floor -> fires -> L-2 survivors.
    s3 = _yield_settings(
        tmp_path / "c",
        P3_CASCADE_YIELD="0",
        P3_EXPECTED_ENRICHMENT_TRIM="2",
        P3_PARTIAL_MATCH_TOLERANCE="2",
    )
    assert target_controller._correlated_per_cascade(s3, 5) == 3.0


def _pat(pattern_id: str, length: int) -> PatternView:
    seq = [(f"T{i}", False) for i in range(length)]
    return PatternView.from_api(fx.pattern_view(pattern_id, "trail-A", seq, "T0", window_ms=30000))


def test_count_math_targets_correlated_yield_not_emitted_length(tmp_path: Path) -> None:
    """The planned aligned-alarm count == needed_correlated / yield (within rounding), and the
    plan's EXPECTED correlated fraction is within tolerance of TARGET — proving the math now
    targets CORRELATED yield, not emitted length."""
    s = _yield_settings(tmp_path)  # TARGET 0.6, T 300, yield 0.61
    total = s.p3_total_alarms  # 300
    target = s.p3_auto_correlation_target  # 0.6
    yield_f = s.p3_cascade_yield  # 0.61
    length = 4
    needed_correlated = round(target * total)  # 180

    pattern = _pat("pat-01", length)
    # A large compatible-trail fleet so per-trail caps never clip the target (isolate the math).
    trails = tuple(CompatibleTrail(f"trail-{i:03d}", f"area-{i % 3}") for i in range(400))
    compatible = {"pat-01": CompatibleTrailSet("pat-01", "area-0", trails)}
    import random

    plan = target_controller.plan_network_wide(s, [pattern], compatible, random.Random(1))

    # 1. sizing target basis is CORRELATED, == round(TARGET * T).
    assert plan.target_correlated_alarms == needed_correlated
    # 2. number of cascades == ceil(needed_correlated / (yield * L)); emitted == cascades * L.
    y_l = yield_f * length
    expected_cascades = math.ceil(needed_correlated / y_l)
    assert len(plan.entries) == expected_cascades
    assert plan.target_aligned_alarms == expected_cascades * length
    # 3. EXPECTED correlated fraction (expected_correlated / T) is within tolerance of TARGET.
    correlated_fraction = plan.expected_correlated_alarms / total
    assert abs(correlated_fraction - target) <= s.p3_target_tolerance + 1e-9
    # 4. and the EMITTED fraction is HIGHER than the target by ~1/yield (the point of the fix).
    emitted_fraction = plan.target_aligned_alarms / total
    assert emitted_fraction > target
    assert abs(emitted_fraction - target / yield_f) <= 0.05


def test_shortfall_caps_do_not_over_report_correlated(tmp_path: Path) -> None:
    """Under per-trail caps that clip the target, expected_correlated_alarms reflects only the
    PLACED cascades (never the unreachable sizing target) so the summary stays honest (AC 55)."""
    s = _yield_settings(tmp_path, P3_MAX_CASCADES_PER_TRAIL="1")
    # 2 trails, cap 1 -> at most 2 cascades placeable; TARGET 0.6 * 300 needs far more.
    trails = (CompatibleTrail("trail-A", "area-0"), CompatibleTrail("trail-B", "area-1"))
    compatible = {"pat-01": CompatibleTrailSet("pat-01", "area-0", trails)}
    import random

    plan = target_controller.plan_network_wide(s, [_pat("pat-01", 4)], compatible, random.Random(1))
    assert len(plan.entries) == 2  # capped
    assert plan.shortfall_cascades > 0
    # expected correlated == placed cascades * yield*L, NOT the unreachable sizing target.
    assert plan.expected_correlated_alarms == round(2 * s.p3_cascade_yield * 4)
    assert plan.expected_correlated_alarms < plan.target_correlated_alarms


# --- v1 -> v2 config-snapshot load compatibility ----------------------------------------------
def test_v1_config_snapshot_loads_without_compatible_trails(tmp_path: Path) -> None:
    path = tmp_path / "v1.json"
    v1 = {
        "schemaVersion": 1,
        "domain": "core-ip",
        "sourceSnapshots": [],
        "trails": {
            "trail-A": {
                "snapshotId": "snap-1",
                "igpArea": "area-0",
                "members": [{"managedObjectId": "IPLink:1", "objectType": "IPLink"}],
            }
        },
        "patterns": [
            {
                "patternId": "pat-01",
                "trailId": "trail-A",
                "sequence": [{"alarmType": "IPLinkDown", "optional": False}],
                "rootCauseAlarmType": "IPLinkDown",
                "timing": {},
                "sessionWindow": {"windowMs": 6000, "type": "gap"},
            }
        ],
    }
    path.write_text(json.dumps(v1))
    loaded = p3_config_snapshot.load(path)
    assert loaded.schema_version == 1
    assert not loaded.has_compatible_trails()  # v1 -> discovery runs on next network-wide run


def test_v2_config_snapshot_roundtrips_compatible_trails(tmp_path: Path) -> None:
    from simulator.synth.models import (
        CompatibleTrail,
        CompatibleTrailSet,
        PatternView,
        TrailDetail,
        TrailMember,
    )

    snap = p3_config_snapshot.P3ConfigSnapshot(
        domain="core-ip",
        patterns=[
            PatternView.from_api(
                {
                    "patternId": "pat-01",
                    "trailId": "trail-A",
                    "sequence": [{"alarmType": "IPLinkDown", "optional": False}],
                    "rootCauseAlarmType": "IPLinkDown",
                    "timing": {},
                    "sessionWindow": {"windowMs": 6000},
                }
            )
        ],
        trails={
            "trail-A": TrailDetail(
                "trail-A", (TrailMember("IPLink:1", "IPLink"),), snapshot_id="s", igp_area="area-0"
            )
        },
        compatible_trails={
            "pat-01": CompatibleTrailSet(
                "pat-01", "area-0", (CompatibleTrail("trail-A", "area-0"),)
            )
        },
    )
    path = tmp_path / "v2.json"
    p3_config_snapshot.save(snap, path)
    assert json.loads(path.read_text())["schemaVersion"] == 2
    loaded = p3_config_snapshot.load(path)
    assert loaded.has_compatible_trails()
    assert loaded.compatible_trails["pat-01"].trails[0].trail_id == "trail-A"


def test_stale_schema_version_fails_fast(tmp_path: Path) -> None:
    path = tmp_path / "bad.json"
    path.write_text(json.dumps({"schemaVersion": 99, "domain": "core-ip"}))
    with pytest.raises(p3_config_snapshot.P3ConfigSnapshotError):
        p3_config_snapshot.load(path)


# --- list_trails paging (mock) ----------------------------------------------------------------
def test_list_trails_paging_mock() -> None:
    bodies = {
        f"trail-{i}": {
            "trailId": f"trail-{i}",
            "domain": "core-ip",
            "igpArea": "area-0",
            "members": [{"managedObjectId": f"IPLink:{i}", "objectType": "IPLink"}],
        }
        for i in range(5)
    }
    tb = MockTrailBuilderClient(bodies)
    page1 = tb.list_trails("snap", "core-ip", limit=2, offset=0)
    page2 = tb.list_trails("snap", "core-ip", limit=2, offset=2)
    page3 = tb.list_trails("snap", "core-ip", limit=2, offset=4)
    assert len(page1) == 2 and len(page2) == 2 and len(page3) == 1
    assert tb.list_calls == 3
    assert {s.trail_id for s in page1 + page2 + page3} == set(bodies)


def test_list_trails_uses_configured_trail_list() -> None:
    tb = MockTrailBuilderClient(
        trail_list=[{"trailId": "t1", "domain": "core-ip", "memberCount": 3, "igpArea": "area-1"}]
    )
    page = tb.list_trails("snap", "core-ip")
    assert page[0].trail_id == "t1" and page[0].igp_area == "area-1"


# --- required_object_types derivation ----------------------------------------------------------
def test_required_object_types_includes_root_and_skips_unmapped() -> None:
    affinity = {"IPLinkDown": "IPLink", "ISISAdjacencyDown": "IGPAdjacency"}
    req = p3_placement.required_object_types(
        ["IPLinkDown", "ISISAdjacencyDown", "UnknownType"], "IPLinkDown", affinity
    )
    assert req == {"IPLink", "IGPAdjacency"}  # unmapped type contributes no requirement


def test_required_object_types_root_added_when_absent() -> None:
    affinity = {"IPLinkDown": "IPLink", "FiberFault": "FiberSpan"}
    req = p3_placement.required_object_types(["ISISAdjacencyDown"], "FiberFault", affinity)
    assert "FiberSpan" in req  # root's affine type always required


# --- reconcile_spacing boundary math ----------------------------------------------------------
def test_reconcile_spacing_single_element_never_conflicts() -> None:
    result = enrichment_safe.reconcile_spacing(
        2000, 100, 1, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingBounds)  # n==1 needs no gap


def test_reconcile_spacing_lo_is_natural_floor_not_dedup() -> None:
    # Corrected model: lo is a natural floor (0), NOT the dedup window; hi = budget / (n-1).
    result = enrichment_safe.reconcile_spacing(
        2000, 60000, 4, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingBounds)
    assert result.lo_ms == 0.0  # no dedup-window floor (distinct-key cascade)
    assert result.hi_ms == pytest.approx(60000 * 0.9 / 3)


def test_reconcile_spacing_window_equal_dedup_not_conflict() -> None:
    # windowMs == dedup window no longer conflicts: distinct-key cascades are dedup-safe by
    # construction, so the pattern stays eligible and gets valid bounds.
    result = enrichment_safe.reconcile_spacing(
        2000, 2000, 3, spacing_margin=0.1, in_window_margin=0.9
    )
    assert isinstance(result, SpacingBounds)


def test_reconcile_spacing_degenerate_window_conflicts() -> None:
    result = enrichment_safe.reconcile_spacing(2000, 0, 3, spacing_margin=0.1, in_window_margin=0.9)
    assert isinstance(result, SpacingConflict)


# --- additive /labels/p3-summary drift --------------------------------------------------------
def test_p3_summary_model_has_additive_network_wide_fields() -> None:
    from simulator.api.app import P3RunSummaryModel

    fields = P3RunSummaryModel.model_fields
    for key in (
        "distinctTrailsUsed",
        "distinctAreasUsed",
        "shortfallCascades",
        "enrichmentSafeCount",
        "enrichmentConflictPatterns",
        "alignedFractionEmitted",
        "expectedCorrelatedAlarms",
    ):
        assert key in fields
