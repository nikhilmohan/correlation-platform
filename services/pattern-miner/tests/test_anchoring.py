"""Stage-2 anchoring unit tests: the LCS+Jaccard scorer, argmax assignment, grouping.

These exercise ``CascadeMatcher`` / ``AnchorGrouper`` / ``score_cascade`` directly (the accuracy
crux), proving: the weighted LCS-ratio + Jaccard confidence; over-split guard (variants of one
fault-origin -> one anchor); over-merge guard (extra unrelated tokens penalised, single-anchor
argmax); unexplained below threshold; and that the threshold/weights come from Knowledge params.
"""

from __future__ import annotations

import math

from pattern_miner.anchoring import (
    AnchorGrouper,
    CascadeMatcher,
    score_cascade,
)

from .helpers import (
    default_anchoring,
    default_params,
    make_alarm,
    make_scenario,
    make_transaction,
    window_sessions,
)

FIBER = ["FiberFault", "LinkDown", "AdjDown"]
CARD = ["PortDown", "InterfaceDown"]


def _session(tokens, trail="t"):
    txn = make_transaction(
        trail_id=trail,
        alarms=[make_alarm(alarm_type=t, raised_offset_seconds=i) for i, t in enumerate(tokens)],
    )
    return window_sessions([txn], default_params(min_support=0.1))[0]


def test_score_exact_match_is_one():
    a = default_anchoring(w_order=0.7, w_jaccard=0.3)
    assert math.isclose(score_cascade(FIBER, tuple(FIBER), a), 1.0, rel_tol=1e-9)


def test_score_partial_chain_between_zero_and_one():
    a = default_anchoring(w_order=0.7, w_jaccard=0.3)
    # C = [FiberFault, AdjDown] vs S = FIBER: lcs 2/3, jaccard 2/3 -> ~0.667.
    conf = score_cascade(["FiberFault", "AdjDown"], tuple(FIBER), a)
    assert 0.6 < conf < 0.7


def test_score_weights_are_knowledge_sourced():
    """Changing the Knowledge scorer weights changes the confidence (no code default)."""
    tokens = ["FiberFault", "AdjDown"]
    order_heavy = score_cascade(tokens, tuple(FIBER), default_anchoring(w_order=1.0, w_jaccard=0.0))
    jaccard_heavy = score_cascade(
        tokens, tuple(FIBER), default_anchoring(w_order=0.0, w_jaccard=1.0)
    )
    # both are 2/3 here, so use an asymmetric case: extra unrelated token lowers jaccard only.
    noisy = ["FiberFault", "LinkDown", "AdjDown", "Noise"]
    o = score_cascade(noisy, tuple(FIBER), default_anchoring(w_order=1.0, w_jaccard=0.0))
    j = score_cascade(noisy, tuple(FIBER), default_anchoring(w_order=0.0, w_jaccard=1.0))
    assert math.isclose(o, 1.0)  # full chain in order
    assert j < 1.0  # extra token penalised by the union denominator
    assert order_heavy == jaccard_heavy  # sanity: symmetric case equal


def test_extra_unrelated_tokens_penalized_no_over_merge():
    """A cascade with the chain PLUS many unrelated tokens is penalised (over-merge guard)."""
    a = default_anchoring(w_order=0.7, w_jaccard=0.3)
    clean = score_cascade(FIBER, tuple(FIBER), a)
    noisy = score_cascade([*FIBER, "N1", "N2", "N3"], tuple(FIBER), a)
    assert noisy < clean


def test_matcher_argmax_picks_best_scenario():
    scenarios = [
        make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER),
        make_scenario(scenario_id="SC-CARD", symptom_chain=CARD),
    ]
    matcher = CascadeMatcher(scenarios, default_anchoring(match_confidence_threshold=0.5))
    result = matcher.match(_session(FIBER))
    assert result.scenario_id == "SC-FIBER"
    assert result.confidence > 0.5


def test_matcher_unexplained_below_threshold():
    scenarios = [make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER)]
    matcher = CascadeMatcher(scenarios, default_anchoring(match_confidence_threshold=0.9))
    result = matcher.match(_session(["FiberFault", "AdjDown"]))  # ~0.667 < 0.9
    assert result.scenario_id is None


def test_grouper_no_over_split_variants_same_anchor():
    """Two variant cascades of one fault-origin land in the SAME group (zero over-split)."""
    scenarios = [make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER)]
    sessions = [_session(FIBER, "a"), _session(["FiberFault", "AdjDown"], "b")]
    groups = AnchorGrouper(scenarios, default_anchoring(match_confidence_threshold=0.5)).group(
        sessions
    )
    anchored = [g for g in groups if not g.is_unexplained]
    assert len(anchored) == 1
    assert anchored[0].scenario_id == "SC-FIBER"
    assert len(anchored[0].sessions) == 2


def test_grouper_distinct_scenarios_distinct_groups_no_over_merge():
    scenarios = [
        make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER),
        make_scenario(scenario_id="SC-CARD", symptom_chain=CARD),
    ]
    sessions = [_session(FIBER, "a"), _session(CARD, "c")]
    groups = AnchorGrouper(scenarios, default_anchoring(match_confidence_threshold=0.5)).group(
        sessions
    )
    ids = {g.scenario_id for g in groups if not g.is_unexplained}
    assert ids == {"SC-FIBER", "SC-CARD"}
    # each cascade in exactly one group.
    all_windows = [s.source_window_id for g in groups for s in g.sessions]
    assert len(all_windows) == len(set(all_windows)) == 2


def test_grouper_unexplained_group_present_and_last():
    scenarios = [make_scenario(scenario_id="SC-FIBER", symptom_chain=FIBER)]
    sessions = [_session(FIBER, "a"), _session(["Zzz", "Yyy"], "x")]
    groups = AnchorGrouper(scenarios, default_anchoring(match_confidence_threshold=0.5)).group(
        sessions
    )
    assert groups[-1].is_unexplained
    assert len(groups[-1].sessions) == 1


def test_empty_symptom_chain_scores_zero():
    assert score_cascade(FIBER, (), default_anchoring()) == 0.0


def test_consecutive_repeat_tokens_deduped():
    """Storm-repeated tokens do not distort the ordered-chain coverage."""
    a = default_anchoring(w_order=1.0, w_jaccard=0.0)
    stormy = ["FiberFault", "FiberFault", "LinkDown", "LinkDown", "AdjDown"]
    assert math.isclose(score_cascade(stormy, tuple(FIBER), a), 1.0, rel_tol=1e-9)
