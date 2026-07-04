"""P3 fetch + config-snapshot tests (AC 32, 33, 34, 42).

Drives ``p3_fetch.fetch_config`` with injected mock clients and the versioned config-snapshot
persist/load, asserting: fail-fast on empty patterns (AC 32), trailId dedup + 404-skip (AC 33),
persistence + zero-refetch standalone load (AC 34, 42).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from simulator.config.settings import load_settings
from simulator.integrations.pattern_manager_client import MockPatternManagerClient
from simulator.integrations.topology_snapshot_client import MockTopologySnapshotClient
from simulator.integrations.trail_builder_client import MockTrailBuilderClient
from simulator.synth import p3_config_snapshot, p3_fetch
from simulator.synth.p3_config_snapshot import P3ConfigSnapshotError
from tests import p3_fixtures as fx


def _settings(tmp_path: Path, **extra: str):
    env = {
        "SIM_MODE": "synth",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "SIM_OUTPUT_DIR": str(tmp_path),
        "phase": "p3",
    }
    env.update(extra)
    return load_settings(env)


def _clients(patterns, trails):
    return (
        MockPatternManagerClient(patterns),
        MockTrailBuilderClient(trails),
        MockTopologySnapshotClient([fx.snapshot_summary()]),
    )


# --- AC 32: read approved patterns; fail fast if none --------------------------------------
def test_ac32_reads_patterns_and_proceeds(tmp_path: Path) -> None:
    s = _settings(tmp_path)
    pm, tb, ts = _clients(
        [fx.pattern_view("pat-01", "trail-A", [("IPLinkDown", False)], "IPLinkDown")],
        {"trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS)},
    )
    config = p3_fetch.fetch_config(s, pattern_client=pm, trail_client=tb, snapshot_client=ts)
    assert [p.pattern_id for p in config.patterns] == ["pat-01"]
    assert pm.calls == 1


def test_ac32_empty_patterns_fail_fast(tmp_path: Path) -> None:
    s = _settings(tmp_path)
    pm, tb, ts = _clients([], {})
    with pytest.raises(p3_fetch.P3FetchError, match="no approved patterns"):
        p3_fetch.fetch_config(s, pattern_client=pm, trail_client=tb, snapshot_client=ts)


# --- AC 33: trail dedup + 404 skip ---------------------------------------------------------
def test_ac33_dedups_trail_fetches(tmp_path: Path) -> None:
    """3 patterns referencing 2 distinct trailIds -> exactly 2 GET /trails calls."""
    s = _settings(tmp_path)
    patterns = [
        fx.pattern_view("pat-01", "trail-A", [("IPLinkDown", False)], "IPLinkDown"),
        fx.pattern_view("pat-02", "trail-A", [("LSPDown", False)], "LSPDown"),
        fx.pattern_view("pat-03", "trail-B", [("FiberFault", False)], "FiberFault"),
    ]
    trails = {
        "trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS),
        "trail-B": fx.trail_detail("trail-B", fx.TRAIL_B_MEMBERS),
    }
    pm, tb, ts = _clients(patterns, trails)
    config = p3_fetch.fetch_config(s, pattern_client=pm, trail_client=tb, snapshot_client=ts)
    assert tb.calls == 2
    assert tb.requested == ["trail-A", "trail-B"]
    assert set(config.trails) == {"trail-A", "trail-B"}


def test_ac33_trail_404_drops_pattern_but_continues(tmp_path: Path) -> None:
    s = _settings(tmp_path)
    patterns = [
        fx.pattern_view("pat-01", "trail-A", [("IPLinkDown", False)], "IPLinkDown"),
        fx.pattern_view("pat-02", "trail-GONE", [("LSPDown", False)], "LSPDown"),
    ]
    trails = {"trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS)}  # trail-GONE -> 404
    pm, tb, ts = _clients(patterns, trails)
    config = p3_fetch.fetch_config(s, pattern_client=pm, trail_client=tb, snapshot_client=ts)
    assert [p.pattern_id for p in config.patterns] == ["pat-01"]
    assert "trail-GONE" not in config.trails


def test_ac33_all_trails_404_fail_fast(tmp_path: Path) -> None:
    s = _settings(tmp_path)
    patterns = [fx.pattern_view("pat-01", "trail-GONE", [("IPLinkDown", False)], "IPLinkDown")]
    pm, tb, ts = _clients(patterns, {})
    with pytest.raises(p3_fetch.P3FetchError, match="resolvable trail"):
        p3_fetch.fetch_config(s, pattern_client=pm, trail_client=tb, snapshot_client=ts)


# --- AC 34 / 42: persist + standalone zero-refetch load ------------------------------------
def test_ac34_persist_then_load_without_refetch(tmp_path: Path) -> None:
    path = tmp_path / "p3-config-snapshot.json"
    s = _settings(tmp_path)
    patterns = [fx.pattern_view("pat-01", "trail-A", [("IPLinkDown", False)], "IPLinkDown")]
    trails = {"trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS)}
    pm, tb, ts = _clients(patterns, trails)
    config = p3_fetch.fetch_config(s, pattern_client=pm, trail_client=tb, snapshot_client=ts)
    p3_config_snapshot.save(config, path)
    assert path.exists()

    reloaded = p3_config_snapshot.load(path)
    assert [p.pattern_id for p in reloaded.patterns] == ["pat-01"]
    assert reloaded.trails["trail-A"].members[0].managed_object_id == "IPLink:ip-7"
    assert reloaded.moid_universe() == {"IPLink:ip-7", "IGPAdjacency:adj-3", "LSP:lsp-9"}


def test_ac34_load_fails_fast_on_unknown_schema_version(tmp_path: Path) -> None:
    path = tmp_path / "bad.json"
    path.write_text('{"schemaVersion": 99, "domain": "core-ip", "trails": {}, "patterns": []}')
    with pytest.raises(P3ConfigSnapshotError, match="schemaVersion"):
        p3_config_snapshot.load(path)


def test_config_snapshot_load_rejects_unresolved_trailref(tmp_path: Path) -> None:
    path = tmp_path / "inconsistent.json"
    path.write_text(
        '{"schemaVersion": 1, "domain": "core-ip", "trails": {}, '
        '"patterns": [{"patternId": "p", "trailId": "missing", "sequence": [], '
        '"rootCauseAlarmType": "IPLinkDown", "timing": {}, "sessionWindow": null}]}'
    )
    with pytest.raises(P3ConfigSnapshotError, match="unresolved trailId"):
        p3_config_snapshot.load(path)
