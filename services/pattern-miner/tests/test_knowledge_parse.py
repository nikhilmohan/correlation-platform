"""Knowledge param-parsing edge cases: profile shapes, optional keys, derived ceiling."""

from __future__ import annotations

import pytest

from pattern_miner.knowledge import (
    KnowledgeError,
    _parse_anchoring_params,
    _parse_mining_params,
    _parse_profiles,
)


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
        {"key": "anchoring.matchConfidenceThreshold", "value": 0.5},
        {"key": "anchoring.weights.order", "value": 0.7},
        {"key": "anchoring.weights.jaccard", "value": 0.3},
        {"key": "codebookVersion", "value": "current"},
        {"key": "sample.maxAlarms", "value": 10},
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


def test_sample_max_alarms_parsed_from_knowledge():
    """[SAMPLE] AC-26: the sample cap K is mapped from the ``sample.maxAlarms`` dotted key."""
    params = _parse_mining_params(_payload(_base_params()))
    assert params.sample_max_alarms == 10


def test_missing_sample_max_alarms_raises():
    """[SAMPLE] AC-26: ``sample.maxAlarms`` is required — no code default, fail fast."""
    params = [p for p in _base_params() if p["key"] != "sample.maxAlarms"]
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


# --------------------------------------------------------------------- anchoring parse


def _anchor_map(params: list[dict]) -> dict:
    return {p["key"]: p["value"] for p in params}


def test_anchoring_params_parsed_from_knowledge():
    """Threshold + weights come from Knowledge; scorer/tiebreak/grouping have template defaults."""
    m = _anchor_map(_base_params())
    a = _parse_anchoring_params(m)
    assert a.match_confidence_threshold == 0.5
    assert a.w_order == 0.7
    assert a.w_jaccard == 0.3
    assert a.scoring_method == "ordered_subsequence_jaccard"  # structural default
    assert a.tie_break == "chain_length_then_scenario_id"  # structural default
    assert a.grouping_keys == ("scenarioId",)  # structural default


def test_anchoring_missing_threshold_raises():
    """The match-confidence threshold is required — no code default (fail fast)."""
    m = _anchor_map([p for p in _base_params() if p["key"] != "anchoring.matchConfidenceThreshold"])
    with pytest.raises(KnowledgeError):
        _parse_anchoring_params(m)


def test_anchoring_missing_weight_raises():
    """Scorer weights are required — no code default."""
    m = _anchor_map([p for p in _base_params() if p["key"] != "anchoring.weights.order"])
    with pytest.raises(KnowledgeError):
        _parse_anchoring_params(m)


def test_anchoring_overrides_from_knowledge():
    """Knowledge-authored scorer / tie-break / grouping keys override the template defaults."""
    extra = [
        {"key": "anchoring.scoringMethod", "value": "custom_scorer"},
        {"key": "anchoring.tieBreak", "value": "scenario_id_only"},
        {"key": "anchoring.groupingKeys", "value": ["faultOriginType", "scenarioId"]},
    ]
    a = _parse_anchoring_params(_anchor_map(_base_params(extra)))
    assert a.scoring_method == "custom_scorer"
    assert a.tie_break == "scenario_id_only"
    assert a.grouping_keys == ("faultOriginType", "scenarioId")


def test_anchoring_grouping_keys_comma_string():
    """Grouping keys may be authored as a comma-separated string."""
    extra = [{"key": "anchoring.groupingKeys", "value": "faultOriginType, scenarioId"}]
    a = _parse_anchoring_params(_anchor_map(_base_params(extra)))
    assert a.grouping_keys == ("faultOriginType", "scenarioId")
