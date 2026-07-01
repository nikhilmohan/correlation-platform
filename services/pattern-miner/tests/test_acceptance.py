"""Acceptance criteria 1-12 + design-plan tests, mapped 1:1 to spec.md / design.md test plan.

All tests are pytest and run against the pure-Python ``LocalPrefixSpanEngine`` (Spark is
container-only; a ``spark``-marked test in ``test_spark_engine.py`` exercises the real MLlib
engine in ``local[*]`` in-container). Inputs are ``TransactionEvent``s with typed ``alarms[]``
populated inline — no resolver.
"""

from __future__ import annotations

import math

import pytest
from acp_event_model import PatternMinedEvent, Provenance
from pydantic import ValidationError

from pattern_miner.metrics import Metrics
from pattern_miner.timing import TimingComputer
from pattern_miner.windowing import SessionWindower

from .helpers import (
    default_params,
    default_windowing,
    make_alarm,
    make_transaction,
    mine_transactions,
)

FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]


def _fiber_cut_transaction(trail_id: str, base_offset: float = 0.0, spacing: float = 1.0):
    """One fiber-cut cascade session: FiberFault -> LinkDown -> AdjDown, tight burst."""
    alarms = [
        make_alarm(alarm_type=t, raised_offset_seconds=base_offset + i * spacing)
        for i, t in enumerate(FIBER_CUT)
    ]
    return make_transaction(trail_id=trail_id, alarms=alarms)


def _find_event(envelopes, sequence):
    for env in envelopes:
        if env.payload.sequence == sequence:
            return env
    return None


def _multi_session_trail(trail_id: str, session_specs: list[list[str]], *, gap: float = 300.0):
    """Build ONE trail whose alarms form multiple idle-separated sessions (one per spec).

    Each spec is an ordered list of alarmType tokens for one burst; bursts are separated by a large
    idle gap so the windower splits them into distinct sessions on the same trail. Support is then
    computed over that trail's sessions (the PrefixSpan scope).
    """
    alarms = []
    for s_idx, tokens in enumerate(session_specs):
        base = s_idx * gap
        for t_idx, token in enumerate(tokens):
            alarms.append(make_alarm(alarm_type=token, raised_offset_seconds=base + t_idx * 0.2))
    return make_transaction(trail_id=trail_id, alarms=alarms)


# --------------------------------------------------------------------------- AC 1
def test_fiber_cut_sequence_recovered_with_support():
    """AC1: the injected fiber-cut alarmType sequence is recovered with correct support."""
    # 4 sessions (distinct trails), all carrying the fiber-cut cascade -> support == 1.0.
    txns = [_fiber_cut_transaction(f"trail-{i}") for i in range(4)]
    params = default_params(min_support=0.5)
    envelopes = mine_transactions(txns, params)

    event = _find_event(envelopes, FIBER_CUT)
    assert event is not None, "fiber-cut sequence not mined"
    assert event.payload.sequence == FIBER_CUT
    # observed frequency = 4 sessions containing the sequence / 4 total sessions.
    assert math.isclose(event.payload.support, 1.0, rel_tol=1e-9)


# --------------------------------------------------------------------------- AC 2
def test_spurious_cooccurrence_surfaced_with_low_lift():
    """AC2: a frequent but statistically independent co-occurrence is emitted with lift ~ 1.0."""
    # Two independent items P and Q each appear in ~half the sessions, and jointly in the product
    # of their marginals -> lift near 1.0. Construct sessions so P and Q are independent.
    # ONE trail, 100 idle-separated sessions: 25 P&Q, 25 P-only, 25 Q-only, 25 R. Then P support
    # .5, Q support .5, P->Q support .25 == .5*.5 -> lift 1.0 (independent co-occurrence).
    specs = [["P", "Q"]] * 25 + [["P"]] * 25 + [["Q"]] * 25 + [["R"]] * 25
    txn = _multi_session_trail("t-indep", specs)

    params = default_params(min_support=0.2)
    envelopes = mine_transactions([txn], params)
    pq = _find_event(envelopes, ["P", "Q"])
    assert pq is not None, "independent P,Q co-occurrence not emitted"
    assert math.isclose(pq.payload.lift, 1.0, abs_tol=0.05), pq.payload.lift


# --------------------------------------------------------------------------- AC 3
def test_min_support_threshold_filters_and_restores():
    """AC3: raising minSupport above a sequence's support removes it; lowering restores it."""
    # ONE trail, 10 idle-separated sessions: 3 carry a rare sequence R1->R2 (support 0.3), 7 carry
    # a common A. Support is over the trail's 10 sessions (the PrefixSpan scope).
    specs = [["R1", "R2"]] * 3 + [["A"]] * 7
    txn = _multi_session_trail("t-rare", specs)

    high = default_params(min_support=0.5)
    envelopes_high = mine_transactions([txn], high)
    assert _find_event(envelopes_high, ["R1", "R2"]) is None, "rare seq should be filtered out"

    low = default_params(min_support=0.2)
    envelopes_low = mine_transactions([txn], low)
    assert _find_event(envelopes_low, ["R1", "R2"]) is not None, "rare seq should reappear"


# --------------------------------------------------------------------------- AC 4
def test_emitted_event_validates_against_frozen_model():
    """AC4: every emitted event validates against the frozen PatternMinedEvent; extras rejected."""
    txns = [_fiber_cut_transaction(f"t{i}") for i in range(3)]
    envelopes = mine_transactions(txns, default_params(min_support=0.5))
    assert envelopes
    for env in envelopes:
        # round-trips through the frozen model
        reparsed = PatternMinedEvent.model_validate(env.payload.model_dump())
        for fld in ("sequence", "support", "confidence", "lift", "trailId", "timing", "provenance"):
            assert getattr(reparsed, fld) is not None
    # injecting an extra field raises ValidationError (extra="forbid")
    good = envelopes[0].payload.model_dump()
    with pytest.raises(ValidationError):
        PatternMinedEvent.model_validate({**good, "unexpected": "x"})


# --------------------------------------------------------------------------- AC 5
def test_no_rca_or_lifecycle_fields_on_output():
    """AC5: no emitted event carries rootCauseAlarmType/patternId/lifecycle; constructing raises."""
    txns = [_fiber_cut_transaction(f"t{i}") for i in range(3)]
    envelopes = mine_transactions(txns, default_params(min_support=0.5))
    assert envelopes
    for env in envelopes:
        d = env.payload.model_dump()
        assert "rootCauseAlarmType" not in d
        assert "patternId" not in d
        assert "lifecycle" not in d

    base = envelopes[0].payload.model_dump()
    for forbidden in ("rootCauseAlarmType", "patternId", "lifecycle"):
        with pytest.raises(ValidationError):
            PatternMinedEvent.model_validate({**base, forbidden: "x"})


# --------------------------------------------------------------------------- AC 6
def test_provenance_present_and_codebook_version_from_knowledge():
    """AC6: provenance carries the 3 non-empty sub-fields; codebookVersion == Knowledge value."""
    txns = [_fiber_cut_transaction(f"t{i}") for i in range(3)]
    params = default_params(min_support=0.5, codebook_version="cb-v42")
    envelopes = mine_transactions(txns, params)
    assert envelopes
    for env in envelopes:
        prov = env.payload.provenance
        assert prov.sourceWindowId
        assert prov.snapshotId
        assert prov.codebookVersion == "cb-v42"

    # missing sub-field fails schema validation against the frozen Provenance model.
    with pytest.raises(ValidationError):
        Provenance(snapshotId="s", codebookVersion="c")  # missing sourceWindowId


def test_provenance_domain_propagated_from_transaction():
    """AC6 companion: provenance.domain equals the source TransactionEvent.domain."""
    alarms = [make_alarm(alarm_type=t, raised_offset_seconds=i) for i, t in enumerate(FIBER_CUT)]
    txns = [
        make_transaction(trail_id=f"t{i}", alarms=alarms, domain="ran-domain") for i in range(3)
    ]
    envelopes = mine_transactions(txns, default_params(min_support=0.5))
    assert envelopes
    for env in envelopes:
        assert env.payload.provenance.domain == "ran-domain"


# --------------------------------------------------------------------------- AC 9
def test_thresholds_sourced_from_knowledge():
    """AC9 runtime half: the values used by windowing/PrefixSpan are the Knowledge-supplied ones."""
    # ONE trail, 4 sessions; S1->S2 in exactly 1 -> support 0.25. minSupport 0.26 excludes it,
    # 0.24 includes it — proving the emitted output tracks the Knowledge-supplied threshold.
    specs = [["S1", "S2"]] + [["Z"]] * 3
    txn = _multi_session_trail("t-thr", specs)
    out_excl = mine_transactions([txn], default_params(min_support=0.26))
    assert _find_event(out_excl, ["S1", "S2"]) is None
    out_incl = mine_transactions([txn], default_params(min_support=0.24))
    assert _find_event(out_incl, ["S1", "S2"]) is not None


# --------------------------------------------------------------------------- AC 10
def test_different_tempo_trails_get_appropriate_boundaries():
    """AC10: a fast burst and a slow burst each stay as ONE session under adaptive windowing."""
    windowing = default_windowing(
        base_gap_seconds=5.0,
        gap_multiplier=3.0,
        tempo_percentile=95.0,
        max_closing_gap_seconds=600.0,
        profiles={"fast": 0.2, "slow": 30.0, "default": 5.0},
        class_thresholds={"fast": 1.0, "slow": 600.0},
    )
    windower = SessionWindower(windowing)

    # Fast burst: sub-second inter-arrivals (0.0, 0.1, 0.2, 0.3, 0.4).
    fast = [make_alarm(alarm_type=f"F{i}", raised_offset_seconds=i * 0.1) for i in range(5)]
    fast_sessions = windower.sessions_for_trail("fast", fast, snapshot_id="s", domain="core-ip")
    assert len(fast_sessions) == 1, f"fast burst over-split into {len(fast_sessions)}"

    # Slow burst: minutes apart (0, 60, 120, 180, 240 s).
    slow = [make_alarm(alarm_type=f"S{i}", raised_offset_seconds=i * 60.0) for i in range(5)]
    slow_sessions = windower.sessions_for_trail("slow", slow, snapshot_id="s", domain="core-ip")
    assert len(slow_sessions) == 1, f"slow burst truncated into {len(slow_sessions)}"


def test_fixed_slow_gap_would_split_fast_demonstrating_adaptation():
    """AC10 rationale: the slow burst is only kept whole because the gap adapts to its tempo."""
    # A fast-calibrated fixed gap (base 5s, tiny profiles) would truncate the slow burst; the
    # adaptive gap (multiplier * p95 of the slow inter-arrivals) keeps it whole.
    windowing = default_windowing(
        base_gap_seconds=5.0,
        gap_multiplier=3.0,
        tempo_percentile=95.0,
        max_closing_gap_seconds=600.0,
        profiles={"default": 5.0},
    )
    windower = SessionWindower(windowing)
    slow = [make_alarm(alarm_type=f"S{i}", raised_offset_seconds=i * 60.0) for i in range(4)]
    sessions = windower.sessions_for_trail("slow", slow, snapshot_id="s", domain="core-ip")
    assert len(sessions) == 1
    # the closing gap actually used tracks the burst tempo (>> the 5s base gap)
    assert sessions[0].closing_gap_seconds > 60.0


# --------------------------------------------------------------------------- AC 11
def test_idle_period_splits_trail_into_two_sessions():
    """AC11: two bursts separated by a clear idle period split into exactly two sessions."""
    windowing = default_windowing(
        base_gap_seconds=5.0, gap_multiplier=3.0, tempo_percentile=95.0, profiles={"default": 5.0}
    )
    windower = SessionWindower(windowing)
    # Burst 1: 0.0, 0.2, 0.4 ; long idle ; Burst 2: 100.0, 100.2, 100.4.
    burst1 = [make_alarm(alarm_type=f"A{i}", raised_offset_seconds=i * 0.2) for i in range(3)]
    burst2 = [
        make_alarm(alarm_type=f"B{i}", raised_offset_seconds=100.0 + i * 0.2) for i in range(3)
    ]
    sessions = windower.sessions_for_trail("t", burst1 + burst2, snapshot_id="s", domain="core-ip")
    assert len(sessions) == 2, f"expected 2 sessions, got {len(sessions)}"
    assert len(sessions[0].alarms) == 3
    assert len(sessions[1].alarms) == 3
    assert {a.alarmType for a in sessions[0].alarms} == {"A0", "A1", "A2"}
    assert {a.alarmType for a in sessions[1].alarms} == {"B0", "B1", "B2"}


# --------------------------------------------------------------------------- AC 12
def test_knowledge_windowing_config_changes_boundaries():
    """AC12: changing the Knowledge windowing config reshapes boundaries for the SAME input."""
    # Two bursts 20s apart. Under a small base gap they split into 2; under a large base gap they
    # merge into 1 — proving boundaries are a pure function of Knowledge params + input.
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

    assert len(s_small) != len(s_large), "windowing config change did not reshape boundaries"
    assert len(s_small) == 2
    assert len(s_large) == 1

    # Determinism: identical params + input -> identical boundaries.
    s_small_again = SessionWindower(small).sessions_for_trail(
        "t", alarms, snapshot_id="s", domain="d"
    )
    assert [sw.source_window_id for sw in s_small] == [sw.source_window_id for sw in s_small_again]


# ------------------------------------------------- design: sequence built from alarmType
def test_sequence_built_from_alarm_type_not_event_type():
    """The mined sequence is built from alarms[].alarmType, NOT eventType or probableCause."""
    # distinct alarmType per alarm; identical eventType category across all.
    alarms = [
        make_alarm(alarm_type=at, event_type="communicationsAlarm", raised_offset_seconds=i)
        for i, at in enumerate(FIBER_CUT)
    ]
    txns = [make_transaction(trail_id=f"t{i}", alarms=alarms) for i in range(3)]
    envelopes = mine_transactions(txns, default_params(min_support=0.5))
    event = _find_event(envelopes, FIBER_CUT)
    assert event is not None
    # sequence equals the alarmType tokens, and contains NONE of the eventType category values.
    assert event.payload.sequence == FIBER_CUT
    assert "communicationsAlarm" not in event.payload.sequence


def test_sequences_and_timing_built_from_typed_alarms():
    """Session sequences + timing come from TransactionEvent.alarms[] (no resolver/lookup)."""
    alarms = [
        make_alarm(alarm_type="FiberFault", raised_offset_seconds=0.0),
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=4.0),
        make_alarm(alarm_type="AdjDown", raised_offset_seconds=9.0),
    ]
    txns = [make_transaction(trail_id=f"t{i}", alarms=alarms) for i in range(3)]
    envelopes = mine_transactions(txns, default_params(min_support=0.5))
    event = _find_event(envelopes, FIBER_CUT)
    assert event is not None
    # timing computed from raisedAt gaps (4s, 5s) -> span 9000ms, median 4500ms.
    assert event.payload.timing["timeframeMs"] == 9000
    assert event.payload.timing["medianInterArrivalMs"] == 4500


# ------------------------------------------------- design: P2-GAP-10 timing keys
def test_timing_emits_ms_keys_for_session_window_deriver():
    """timing carries exactly the 4 ms keys; the old seconds keys are absent (worked example)."""
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
    """medianInterArrivalMs is the MEDIAN of the gaps (differs from the mean for skew input)."""
    computer = TimingComputer()
    # gaps 1000,1000,7000 ms -> median 1000, mean 3000, max 7000.
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


def test_timing_empty_when_no_inter_arrivals():
    """A degenerate single-alarm session yields all-zero timing (consumer thin-timing fallback)."""
    computer = TimingComputer()
    solo = (make_alarm(alarm_type="A", raised_offset_seconds=0.0),)
    assert computer.compute([solo]).to_dict() == {
        "timeframeMs": 0,
        "medianInterArrivalMs": 0,
        "maxInterArrivalMs": 0,
        "stddevInterArrivalMs": 0,
    }


def test_fallback_gap_metric_increments():
    """A too-thin burst uses the Knowledge base/fallback gap and increments the metric."""
    metrics = Metrics()
    windowing = default_windowing(min_burst_samples=5)  # any burst with <4 gaps -> fallback
    windower = SessionWindower(windowing, metrics=metrics)
    alarms = [make_alarm(alarm_type=f"A{i}", raised_offset_seconds=i * 0.1) for i in range(2)]
    sessions = windower.sessions_for_trail("t", alarms, snapshot_id="s", domain="d")
    assert sessions[0].used_fallback_gap is True
    assert metrics.fallback_gap_used._value.get() >= 1
