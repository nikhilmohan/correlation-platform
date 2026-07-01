"""Knowledge param-parsing edge cases: profile shapes, optional keys, derived ceiling."""

from __future__ import annotations

import pytest

from pattern_miner.knowledge import KnowledgeError, _parse_mining_params, _parse_profiles


def _payload(params: list[dict]) -> dict:
    return {"paramSet": "pattern-miner", "params": params}


def _base_params(extra: list[dict] | None = None) -> list[dict]:
    params = [
        {"key": "prefixspan.minSupport", "value": 0.3},
        {"key": "prefixspan.maxPatternLength", "value": 10},
        {"key": "prefixspan.maxSequenceCount", "value": 1000},
        {"key": "window.adaptive.baseGapSeconds", "value": 5.0},
        {"key": "window.adaptive.gapMultiplier", "value": 3.0},
        {"key": "window.adaptive.tempoPercentile", "value": 95.0},
        {"key": "codebookVersion", "value": "current"},
    ]
    if extra:
        params.extend(extra)
    return params


def test_profiles_floor_only_map():
    profiles = _parse_profiles({"fast": 0.5, "slow": 30.0})
    assert profiles["fast"].floor_seconds == 0.5
    assert profiles["slow"].ceiling_seconds is None


def test_profiles_floor_and_ceiling_dict():
    profiles = _parse_profiles({"fast": {"floor": 0.5, "ceiling": 2.0}})
    assert profiles["fast"].floor_seconds == 0.5
    assert profiles["fast"].ceiling_seconds == 2.0


def test_profiles_non_dict_returns_empty():
    assert _parse_profiles("nope") == {}


def test_missing_required_key_raises():
    params = [p for p in _base_params() if p["key"] != "codebookVersion"]
    with pytest.raises(KnowledgeError):
        _parse_mining_params(_payload(params))


def test_max_closing_gap_derived_when_absent():
    """maxClosingGapSeconds absent -> a Knowledge-sourced ceiling is derived (not a literal)."""
    profiles = [{"key": "window.adaptive.profiles", "value": {"fast": 0.5, "slow": 30.0}}]
    params = _parse_mining_params(_payload(_base_params(profiles)))
    # derived ceiling >= the slowest profile floor (30.0)
    assert params.windowing.max_closing_gap_seconds >= 30.0


def test_explicit_max_closing_gap_and_min_burst_samples_honored():
    extra = [
        {"key": "window.adaptive.maxClosingGapSeconds", "value": 240.0},
        {"key": "window.adaptive.minBurstSamples", "value": 3},
        {"key": "window.adaptive.classThresholds", "value": {"fast": 1.0, "slow": 600.0}},
    ]
    params = _parse_mining_params(_payload(_base_params(extra)))
    assert params.windowing.max_closing_gap_seconds == 240.0
    assert params.windowing.min_burst_samples == 3
    assert params.windowing.class_thresholds == {"fast": 1.0, "slow": 600.0}
