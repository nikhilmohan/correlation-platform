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
    ):
        assert key in fields
