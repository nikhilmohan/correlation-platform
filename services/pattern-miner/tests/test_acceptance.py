"""Acceptance criteria AC-1..AC-18 (unit/contract), mapped 1:1 to the 3-stage spec/design test plan.

All tests are pytest and run against the pure-Python ``LocalPrefixSpanEngine`` (Spark is
container-only; a ``spark``-marked test in ``test_spark_engine.py`` exercises the real MLlib engine
in ``local[*]`` in-container). Inputs are ``TransactionEvent``s with typed ``alarms[]`` and inline
Codebook ``Scenario`` fixtures (domain values illustrative — never literals in source/config).
AC-19..AC-21 (accuracy oracle) live in ``test_accuracy_oracle.py``.
"""

from __future__ import annotations

import math

import pytest
from acp_event_model import PatternMinedEvent, Provenance
from pydantic import ValidationError

from pattern_miner.timing import TimingComputer
from pattern_miner.windowing import SessionWindower

from .helpers import (
    default_anchoring,
    default_params,
    default_windowing,
    group_sessions,
    make_alarm,
    make_scenario,
    make_transaction,
    run_pipeline,
    window_sessions,
)

FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]
CARD_FAIL = ["PortDown", "InterfaceDown"]


def _fiber_scenario():
    return make_scenario(
        scenario_id="SC-FIBER", fault_origin_type="FiberCut", symptom_chain=FIBER_CUT
    )


def _card_scenario():
    return make_scenario(
        scenario_id="SC-CARD", fault_origin_type="CardFail", symptom_chain=CARD_FAIL
    )


def _cascade_txn(trail_id, tokens, *, spacing=1.0, snapshot_id="snap-1"):
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=i * spacing) for i, t in enumerate(tokens)
    ]
    return make_transaction(trail_id=trail_id, alarms=alarms, snapshot_id=snapshot_id)


def _find_by_anchor(envelopes, scenario_id):
    return [e for e in envelopes if e.payload.provenance.anchorScenarioId == scenario_id]


# =========================================================== Stage 1 — time+space (AC-1..AC-3)


def test_stage1_different_tempo_trails_get_appropriate_boundaries():
    """AC-1: a fast burst and a slow burst each stay as ONE session under adaptive windowing."""
    windowing = default_windowing(
        base_gap_seconds=5.0,
        gap_multiplier=3.0,
        tempo_percentile=95.0,
        max_closing_gap_seconds=600.0,
        profiles={"fast": 0.2, "slow": 30.0, "default": 5.0},
        class_thresholds={"fast": 1.0, "slow": 600.0},
    )
    windower = SessionWindower(windowing)
    fast = [make_alarm(alarm_type=f"F{i}", raised_offset_seconds=i * 0.1) for i in range(5)]
    slow = [make_alarm(alarm_type=f"S{i}", raised_offset_seconds=i * 60.0) for i in range(5)]
    assert len(windower.sessions_for_trail("fast", fast, snapshot_id="s", domain="d")) == 1
    assert len(windower.sessions_for_trail("slow", slow, snapshot_id="s", domain="d")) == 1


def test_stage1_idle_period_splits_trail_into_two_sessions():
    """AC-2: two bursts separated by a clear idle period split into exactly two sessions."""
    windowing = default_windowing(
        base_gap_seconds=5.0, gap_multiplier=3.0, tempo_percentile=95.0, profiles={"default": 5.0}
    )
    windower = SessionWindower(windowing)
    burst1 = [make_alarm(alarm_type=f"A{i}", raised_offset_seconds=i * 0.2) for i in range(3)]
    burst2 = [
        make_alarm(alarm_type=f"B{i}", raised_offset_seconds=100.0 + i * 0.2) for i in range(3)
    ]
    sessions = windower.sessions_for_trail("t", burst1 + burst2, snapshot_id="s", domain="d")
    assert len(sessions) == 2
    assert {a.alarmType for a in sessions[0].alarms} == {"A0", "A1", "A2"}
    assert {a.alarmType for a in sessions[1].alarms} == {"B0", "B1", "B2"}


def test_stage1_knowledge_windowing_config_changes_boundaries():
    """AC-3: changing the Knowledge windowing config reshapes boundaries for the SAME input."""
    burst1 = [make_alarm(alarm_type=f"A{i}", raised_offset_seconds=i * 1.0) for i in range(3)]
    burst2 = [
        make_alarm(alarm_type=f"B{i}", raised_offset_seconds=20.0 + i * 1.0) for i in range(3)
    ]
    alarms = burst1 + burst2
    small = default_windowing(
        base_gap_seconds=3.0,
        gap_multiplier=1.5,
        tempo_percentile=50.0,
        max_closing_gap_seconds=5.0,
        profiles={"default": 1.0},
    )
    large = default_windowing(
        base_gap_seconds=60.0,
        gap_multiplier=1.5,
        tempo_percentile=50.0,
        max_closing_gap_seconds=120.0,
        profiles={"default": 60.0},
    )
    s_small = SessionWindower(small).sessions_for_trail("t", alarms, snapshot_id="s", domain="d")
    s_large = SessionWindower(large).sessions_for_trail("t", alarms, snapshot_id="s", domain="d")
    assert len(s_small) == 2
    assert len(s_large) == 1
    again = SessionWindower(small).sessions_for_trail("t", alarms, snapshot_id="s", domain="d")
    assert [x.source_window_id for x in s_small] == [x.source_window_id for x in again]


# =========================================================== Stage 2 — anchoring (AC-4..AC-8)


def test_stage2_distinct_scenarios_anchor_to_distinct_groups():
    """AC-4: cascades matching two scenarios anchor to distinct groups (zero over-merge)."""
    params = default_params(min_support=0.1)
    txns = [_cascade_txn("trail-a", FIBER_CUT), _cascade_txn("trail-c", CARD_FAIL)]
    sessions = window_sessions(txns, params)
    groups = group_sessions(sessions, [_fiber_scenario(), _card_scenario()], params)
    by_id = {g.scenario_id: g for g in groups}
    assert set(by_id) == {"SC-FIBER", "SC-CARD"}
    fiber_windows = {s.source_window_id for s in by_id["SC-FIBER"].sessions}
    card_windows = {s.source_window_id for s in by_id["SC-CARD"].sessions}
    assert fiber_windows.isdisjoint(card_windows)  # no cascade in both groups


def test_stage2_same_scenario_multi_trail_single_group():
    """AC-5: one scenario, multiple cascades -> one group/event, same anchor (zero over-split)."""
    params = default_params(min_support=0.1)
    # C1 = full chain (trail-a); C2 = a variant with one symptom missing (trail-b) -> same anchor.
    txns = [
        _cascade_txn("trail-a", ["FiberFault", "LinkDown", "AdjDown"]),
        _cascade_txn("trail-b", ["FiberFault", "AdjDown"]),
    ]
    envelopes = run_pipeline(txns, [_fiber_scenario()], params)
    fiber = _find_by_anchor(envelopes, "SC-FIBER")
    assert len(fiber) == 1  # not split into two anchored groups
    assert fiber[0].payload.provenance.anchorScenarioId == "SC-FIBER"


def test_stage2_unexplained_cascade_not_forced_to_closest():
    """AC-6: a cascade below threshold lands in the unexplained group (anchorScenarioId null)."""
    params = default_params(
        min_support=0.1, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )
    txns = [_cascade_txn("trail-x", ["Xyz", "Abc"])]  # unrelated to fiber/card chains
    envelopes = run_pipeline(txns, [_fiber_scenario(), _card_scenario()], params)
    assert envelopes
    for e in envelopes:
        assert e.payload.provenance.anchorScenarioId is None


def test_stage2_threshold_sourced_from_knowledge_changes_outcome():
    """AC-7: only the Knowledge threshold changes anchored vs unexplained (no code change)."""
    txns = [_cascade_txn("trail-b", ["FiberFault", "AdjDown"])]  # partial fiber chain, conf ~0.67
    high = default_params(
        min_support=0.1, anchoring=default_anchoring(match_confidence_threshold=0.9)
    )
    low = default_params(
        min_support=0.1, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )
    hi = run_pipeline(txns, [_fiber_scenario()], high)
    lo = run_pipeline(txns, [_fiber_scenario()], low)
    assert all(e.payload.provenance.anchorScenarioId is None for e in hi)
    assert any(e.payload.provenance.anchorScenarioId == "SC-FIBER" for e in lo)


def test_stage2_scenario_set_sourced_from_codebook_changes_anchoring():
    """AC-8: changing the Codebook scenario set changes which fault-origin a cascade anchors to."""
    params = default_params(min_support=0.1)
    txns = [_cascade_txn("trail-a", FIBER_CUT)]
    with_fiber = run_pipeline(txns, [_fiber_scenario()], params)
    assert any(e.payload.provenance.anchorScenarioId == "SC-FIBER" for e in with_fiber)
    # A different scenario set (only card) -> the same cascade no longer anchors to fiber.
    with_card = run_pipeline(txns, [_card_scenario()], params)
    assert all(e.payload.provenance.anchorScenarioId != "SC-FIBER" for e in with_card)


# ================================================== Stage 3 — bounded PrefixSpan (AC-9..AC-11)


def test_stage3_group_sequence_and_support():
    """AC-9: the fiber group's event sequence equals the ordered alarmType chain, support = freq."""
    params = default_params(min_support=0.5)
    txns = [_cascade_txn(f"t{i}", FIBER_CUT) for i in range(4)]
    envelopes = run_pipeline(txns, [_fiber_scenario()], params)
    fiber = _find_by_anchor(envelopes, "SC-FIBER")
    assert len(fiber) == 1
    assert fiber[0].payload.sequence == FIBER_CUT  # from alarmType, not eventType/probableCause
    assert "communicationsAlarm" not in fiber[0].payload.sequence
    assert math.isclose(fiber[0].payload.support, 1.0, rel_tol=1e-9)


def test_stage3_event_count_bounded_by_groups():
    """AC-10: total emitted events <= distinct anchored groups + unexplained (N+1)."""
    params = default_params(min_support=0.1)
    txns = [
        _cascade_txn("a1", FIBER_CUT),
        _cascade_txn("a2", FIBER_CUT),
        _cascade_txn("c1", CARD_FAIL),
        _cascade_txn("x1", ["Zzz", "Yyy"]),  # unexplained
    ]
    envelopes = run_pipeline(txns, [_fiber_scenario(), _card_scenario()], params)
    # N=2 anchored fault-origins + 1 unexplained => at most 3 events.
    assert len(envelopes) <= 3
    anchors = {e.payload.provenance.anchorScenarioId for e in envelopes}
    assert anchors <= {"SC-FIBER", "SC-CARD", None}


def test_stage3_min_support_filters_and_restores_within_group():
    """AC-11: raising minSupport past a group sequence's support removes it; lowering restores."""
    # Fiber group of 4 sessions: 1 carries the full 3-chain (support 0.25), all 4 carry FiberFault.
    txns = [
        _cascade_txn("t0", FIBER_CUT),
        _cascade_txn("t1", ["FiberFault"]),
        _cascade_txn("t2", ["FiberFault"]),
        _cascade_txn("t3", ["FiberFault"]),
    ]
    scen = [_fiber_scenario()]
    high = run_pipeline(
        txns,
        scen,
        default_params(
            min_support=0.5, anchoring=default_anchoring(match_confidence_threshold=0.1)
        ),
    )
    hi_fiber = _find_by_anchor(high, "SC-FIBER")
    assert hi_fiber and hi_fiber[0].payload.sequence != FIBER_CUT  # full chain filtered out
    low = run_pipeline(
        txns,
        scen,
        default_params(
            min_support=0.2, anchoring=default_anchoring(match_confidence_threshold=0.1)
        ),
    )
    lo_fiber = _find_by_anchor(low, "SC-FIBER")
    assert lo_fiber and lo_fiber[0].payload.sequence == FIBER_CUT  # full chain restored


# =========================================================== Contract & correctness (AC-12..AC-18)


def test_output_validates_and_anchor_field_semantics():
    """AC-12: every event validates; anchored -> str anchor; unexplained -> null still valid."""
    params = default_params(min_support=0.1)
    txns = [_cascade_txn("a1", FIBER_CUT), _cascade_txn("x1", ["Zzz", "Yyy"])]
    envelopes = run_pipeline(txns, [_fiber_scenario()], params)
    assert envelopes
    for env in envelopes:
        reparsed = PatternMinedEvent.model_validate(env.payload.model_dump())
        for fld in ("sequence", "support", "confidence", "lift", "trailId", "timing", "provenance"):
            assert getattr(reparsed, fld) is not None
    anchored = _find_by_anchor(envelopes, "SC-FIBER")
    unexplained = _find_by_anchor(envelopes, None)
    assert anchored and isinstance(anchored[0].payload.provenance.anchorScenarioId, str)
    assert unexplained and unexplained[0].payload.provenance.anchorScenarioId is None
    # absence of the optional field still validates (not in provenance.required).
    prov = Provenance(sourceWindowId="w", snapshotId="s", codebookVersion="c")
    assert prov.anchorScenarioId is None


def test_no_rca_or_lifecycle_fields_on_output():
    """AC-13: no event carries rootCauseAlarmType/patternId/lifecycle; extra='forbid' enforces."""
    envelopes = run_pipeline(
        [_cascade_txn("t", FIBER_CUT)], [_fiber_scenario()], default_params(min_support=0.1)
    )
    assert envelopes
    for env in envelopes:
        d = env.payload.model_dump()
        for forbidden in ("rootCauseAlarmType", "patternId", "lifecycle"):
            assert forbidden not in d
    base = envelopes[0].payload.model_dump()
    for forbidden in ("rootCauseAlarmType", "patternId", "lifecycle"):
        with pytest.raises(ValidationError):
            PatternMinedEvent.model_validate({**base, forbidden: "x"})


def test_provenance_fields_and_anchor_matches_scenario():
    """AC-14: provenance sub-fields non-empty; codebookVersion == Knowledge; anchor == scenario."""
    params = default_params(min_support=0.1, codebook_version="current")
    envelopes = run_pipeline([_cascade_txn("t", FIBER_CUT)], [_fiber_scenario()], params)
    assert envelopes
    for env in envelopes:
        prov = env.payload.provenance
        assert prov.sourceWindowId and prov.snapshotId and prov.codebookVersion == "current"
    anchored = _find_by_anchor(envelopes, "SC-FIBER")
    assert anchored and anchored[0].payload.provenance.anchorScenarioId == "SC-FIBER"


def test_provenance_domain_propagated():
    """AC-14 companion: provenance.domain equals the source TransactionEvent.domain."""
    txn = make_transaction(
        trail_id="t",
        domain="ran-domain",
        alarms=[make_alarm(alarm_type=t, raised_offset_seconds=i) for i, t in enumerate(FIBER_CUT)],
    )
    envelopes = run_pipeline(
        [txn],
        [make_scenario(scenario_id="SC-X", symptom_chain=FIBER_CUT)],
        default_params(min_support=0.1),
    )
    assert envelopes
    for env in envelopes:
        assert env.payload.provenance.domain == "ran-domain"


# =========================================================== Timing (AC-18)


def test_timing_emits_ms_keys():
    """AC-18: timing carries exactly the 4 ms keys; the old seconds keys are absent."""
    computer = TimingComputer()
    alarms = (
        make_alarm(alarm_type="FiberFault", raised_offset_seconds=0.0),
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=4.0),
        make_alarm(alarm_type="AdjDown", raised_offset_seconds=9.0),
    )
    timing = computer.compute([alarms]).to_dict()
    assert timing == {
        "timeframeMs": 9000,
        "medianInterArrivalMs": 4500,
        "maxInterArrivalMs": 5000,
        "stddevInterArrivalMs": 500,
    }
    assert "meanInterArrivalSeconds" not in timing
    assert "stdDevSeconds" not in timing


def test_median_inter_arrival_used_not_mean():
    """AC-18 companion: medianInterArrivalMs is the MEDIAN of the gaps, not the mean."""
    computer = TimingComputer()
    alarms = (
        make_alarm(alarm_type="A", raised_offset_seconds=0.0),
        make_alarm(alarm_type="B", raised_offset_seconds=1.0),
        make_alarm(alarm_type="C", raised_offset_seconds=2.0),
        make_alarm(alarm_type="D", raised_offset_seconds=9.0),
    )
    timing = computer.compute([alarms]).to_dict()
    assert timing["medianInterArrivalMs"] == 1000
    assert timing["maxInterArrivalMs"] == 7000
    assert timing["medianInterArrivalMs"] != 3000  # not the mean


def test_timing_built_from_typed_alarms_in_pipeline():
    """timing on the emitted event is computed from alarms[].raisedAt (no resolver/lookup)."""
    params = default_params(min_support=0.5)
    txns = [_cascade_txn(f"t{i}", FIBER_CUT, spacing=1.0) for i in range(3)]
    # spacing 1s -> gaps 1000ms each; override with an explicit 4s/5s cascade for a worked example.
    txns = [
        make_transaction(
            trail_id=f"t{i}",
            alarms=[
                make_alarm(alarm_type="FiberFault", raised_offset_seconds=0.0),
                make_alarm(alarm_type="LinkDown", raised_offset_seconds=4.0),
                make_alarm(alarm_type="AdjDown", raised_offset_seconds=9.0),
            ],
        )
        for i in range(3)
    ]
    envelopes = run_pipeline(txns, [_fiber_scenario()], params)
    fiber = _find_by_anchor(envelopes, "SC-FIBER")
    assert fiber
    assert fiber[0].payload.timing["timeframeMs"] == 9000
    assert fiber[0].payload.timing["medianInterArrivalMs"] == 4500


def test_fallback_gap_metric_increments():
    """A too-thin burst uses the Knowledge base/fallback gap and increments the metric."""
    from pattern_miner.metrics import Metrics

    metrics = Metrics()
    windowing = default_windowing(min_burst_samples=5)
    windower = SessionWindower(windowing, metrics=metrics)
    alarms = [make_alarm(alarm_type=f"A{i}", raised_offset_seconds=i * 0.1) for i in range(2)]
    sessions = windower.sessions_for_trail("t", alarms, snapshot_id="s", domain="d")
    assert sessions[0].used_fallback_gap is True
    assert metrics.fallback_gap_used._value.get() >= 1
