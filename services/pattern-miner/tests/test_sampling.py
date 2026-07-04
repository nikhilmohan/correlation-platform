"""[SAMPLE] Pure-Python unit tests for ``sampling.SampleAlarmSelector`` (no Spark).

Exercises the bounded, deterministic member-alarm selection over synthetic ``Session`` /
``GroupPattern`` objects — the AC-22..26 behaviours that do NOT need the Spark PrefixSpan runtime.
The event-level, validate-against-the-model assertions (AC-22/AC-24/AC-25) run end-to-end through
the pipeline in ``test_acceptance_sample_alarms.py``; here we test the selector in isolation.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from acp_event_model._generated import SampleAlarm

from pattern_miner.mining import GroupPattern
from pattern_miner.mining.miner import MinedSequence
from pattern_miner.sampling import SampleAlarmSelector
from pattern_miner.windowing import Session

from .helpers import make_alarm

BASE = datetime(2026, 1, 1, 12, 0, 0, tzinfo=UTC)


def _session(
    *,
    trail_id: str,
    alarms,
    snapshot_id: str = "snap-1",
    source_window_id: str | None = None,
) -> Session:
    """Build a synthetic ``Session`` (no Spark, no windower) for selector tests."""
    return Session(
        trail_id=trail_id,
        snapshot_id=snapshot_id,
        domain="core-ip",
        alarms=tuple(alarms),
        source_window_id=source_window_id or f"sw:{trail_id}:{uuid.uuid4().hex[:8]}",
        tempo_class="default",
        closing_gap_seconds=5.0,
        used_fallback_gap=False,
    )


def _group_pattern(*, sequence, matching_sessions) -> GroupPattern:
    """Build a synthetic ``GroupPattern`` with a given representative sequence + sessions."""
    return GroupPattern(
        scenario_id="scenario-A",
        mined=MinedSequence(
            sequence=tuple(sequence), support=1.0, confidence=1.0, lift=1.0, freq=1
        ),
        matching_sessions=list(matching_sessions),
    )


def test_maps_five_fields_from_real_alarms():
    """AC-22 (selector half): each SampleAlarm carries the 5 event-model fields from an Alarm."""
    alarm = make_alarm(
        alarm_type="LinkDown",
        raised_offset_seconds=1,
        alarm_id="a-1",
        managed_object_id="Port:7",
        perceived_severity="major",
    )
    gp = _group_pattern(
        sequence=["LinkDown"],
        matching_sessions=[_session(trail_id="t1", alarms=[alarm])],
    )
    out = SampleAlarmSelector().select(gp, 10)
    assert len(out) == 1
    sa = out[0]
    assert isinstance(sa, SampleAlarm)
    assert sa.alarmId == "a-1"
    assert sa.alarmType == "LinkDown"
    assert sa.raisedAt == alarm.raisedAt
    assert sa.managedObjectId == "Port:7"
    assert sa.perceivedSeverity == "major"


def test_caps_to_k_when_more_than_k_alarms():
    """AC-23: a session with more than K alarms yields at most K SampleAlarms."""
    alarms = [
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=i, alarm_id=f"a-{i}")
        for i in range(8)
    ]
    gp = _group_pattern(
        sequence=["LinkDown"], matching_sessions=[_session(trail_id="t1", alarms=alarms)]
    )
    assert len(SampleAlarmSelector().select(gp, 3)) == 3


def test_includes_all_when_fewer_than_k():
    """AC-23: K or fewer distinct alarms -> all are included."""
    alarms = [
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=i, alarm_id=f"a-{i}")
        for i in range(3)
    ]
    gp = _group_pattern(
        sequence=["LinkDown"], matching_sessions=[_session(trail_id="t1", alarms=alarms)]
    )
    assert len(SampleAlarmSelector().select(gp, 10)) == 3


def test_changing_k_changes_length():
    """AC-23/AC-26: K is a passed-in Knowledge value; changing it changes the length, no code."""
    alarms = [
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=i, alarm_id=f"a-{i}")
        for i in range(9)
    ]
    gp = _group_pattern(
        sequence=["LinkDown"], matching_sessions=[_session(trail_id="t1", alarms=alarms)]
    )
    selector = SampleAlarmSelector()
    assert len(selector.select(gp, 2)) == 2
    assert len(selector.select(gp, 5)) == 5


def test_ordered_by_raised_at_then_alarm_id():
    """Ordering: ascending by (raisedAt, alarmId); alarmId breaks ties at equal timestamps."""
    a_late = make_alarm(alarm_type="AdjDown", raised_offset_seconds=30, alarm_id="a-late")
    a_early = make_alarm(alarm_type="FiberFault", raised_offset_seconds=1, alarm_id="a-early")
    # Two alarms at the SAME timestamp -> alarmId breaks the tie deterministically.
    a_tie_b = make_alarm(alarm_type="LinkDown", raised_offset_seconds=10, alarm_id="a-tie-b")
    a_tie_a = make_alarm(alarm_type="LinkDown", raised_offset_seconds=10, alarm_id="a-tie-a")
    gp = _group_pattern(
        sequence=["FiberFault", "LinkDown", "AdjDown"],
        matching_sessions=[_session(trail_id="t1", alarms=[a_late, a_tie_b, a_early, a_tie_a])],
    )
    out = SampleAlarmSelector().select(gp, 10)
    assert [sa.alarmId for sa in out] == ["a-early", "a-tie-a", "a-tie-b", "a-late"]


def test_dedup_by_alarm_id_keeps_first():
    """Repeated alarmId is deduped (keep first) BEFORE the cap so K distinct alarms are shown."""
    a1 = make_alarm(alarm_type="LinkDown", raised_offset_seconds=1, alarm_id="dup")
    a2 = make_alarm(alarm_type="LinkDown", raised_offset_seconds=2, alarm_id="dup")
    a3 = make_alarm(alarm_type="AdjDown", raised_offset_seconds=3, alarm_id="unique")
    gp = _group_pattern(
        sequence=["LinkDown", "AdjDown"],
        matching_sessions=[_session(trail_id="t1", alarms=[a1, a2, a3])],
    )
    out = SampleAlarmSelector().select(gp, 10)
    ids = [sa.alarmId for sa in out]
    assert ids == ["dup", "unique"]  # one 'dup', ordered ascending


def test_every_sampled_type_is_member_of_sequence():
    """AC-24: every sampled alarmType is a member of the pattern's sequence (construction)."""
    alarms = [
        make_alarm(alarm_type="FiberFault", raised_offset_seconds=1, alarm_id="a-1"),
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=2, alarm_id="a-2"),
        make_alarm(alarm_type="AdjDown", raised_offset_seconds=3, alarm_id="a-3"),
    ]
    sequence = ["FiberFault", "LinkDown", "AdjDown"]
    gp = _group_pattern(
        sequence=sequence, matching_sessions=[_session(trail_id="t1", alarms=alarms)]
    )
    out = SampleAlarmSelector().select(gp, 10)
    assert {sa.alarmType for sa in out} <= set(sequence)


def test_representative_session_is_earliest_by_window_start():
    """OQ-SA-2: samples from the earliest-by-window-start session (deterministic occurrence)."""
    early = _session(
        trail_id="t-early",
        alarms=[make_alarm(alarm_type="LinkDown", raised_offset_seconds=1, alarm_id="early-1")],
    )
    late = _session(
        trail_id="t-late",
        alarms=[make_alarm(alarm_type="LinkDown", raised_offset_seconds=500, alarm_id="late-1")],
    )
    # Pass sessions in reverse (late first) — selection must still pick the earliest.
    gp = _group_pattern(sequence=["LinkDown"], matching_sessions=[late, early])
    out = SampleAlarmSelector().select(gp, 10)
    assert [sa.alarmId for sa in out] == ["early-1"]


def test_representative_tie_broken_by_trail_then_window():
    """Equal window starts are tie-broken by (trailId, sourceWindowId) — fully deterministic."""
    ts = make_alarm(alarm_type="LinkDown", raised_offset_seconds=5, alarm_id="s-a")
    ts2 = make_alarm(alarm_type="LinkDown", raised_offset_seconds=5, alarm_id="s-b")
    s_b = _session(trail_id="t-b", alarms=[ts2], source_window_id="sw:b")
    s_a = _session(trail_id="t-a", alarms=[ts], source_window_id="sw:a")
    gp = _group_pattern(sequence=["LinkDown"], matching_sessions=[s_b, s_a])
    out = SampleAlarmSelector().select(gp, 10)
    assert [sa.alarmId for sa in out] == ["s-a"]  # trail t-a sorts before t-b


def test_empty_session_yields_empty_sample():
    """AC-25: a representative session with no alarms yields an empty sample (no error)."""
    gp = _group_pattern(
        sequence=["LinkDown"], matching_sessions=[_session(trail_id="t1", alarms=[])]
    )
    assert SampleAlarmSelector().select(gp, 10) == []


def test_no_matching_sessions_yields_empty_sample():
    """AC-25: no matching session at all -> empty sample (defensive, no error)."""
    gp = _group_pattern(sequence=["LinkDown"], matching_sessions=[])
    assert SampleAlarmSelector().select(gp, 10) == []


def test_non_positive_k_yields_empty_sample():
    """A non-positive K yields an empty sample (defensive; still a valid emitting event)."""
    alarms = [make_alarm(alarm_type="LinkDown", raised_offset_seconds=1, alarm_id="a-1")]
    gp = _group_pattern(
        sequence=["LinkDown"], matching_sessions=[_session(trail_id="t1", alarms=alarms)]
    )
    assert SampleAlarmSelector().select(gp, 0) == []


def test_deterministic_on_repeat():
    """AC-26 (determinism): the same input yields identical sampleAlarms on repeat (replay-safe)."""
    alarms = [
        make_alarm(alarm_type="LinkDown", raised_offset_seconds=i, alarm_id=f"a-{i}")
        for i in range(6)
    ]
    sessions = [
        _session(trail_id="t2", alarms=alarms[3:]),
        _session(trail_id="t1", alarms=alarms[:3]),
    ]
    gp = _group_pattern(sequence=["LinkDown"], matching_sessions=sessions)
    selector = SampleAlarmSelector()
    first = [sa.model_dump() for sa in selector.select(gp, 4)]
    second = [sa.model_dump() for sa in selector.select(gp, 4)]
    assert first == second


def test_sample_alarms_validate_against_event_model():
    """AC-22: a produced SampleAlarm validates against the (synced) event-model SampleAlarm."""
    alarm = make_alarm(
        alarm_type="LinkDown",
        raised_offset_seconds=1,
        alarm_id="a-1",
        managed_object_id="Port:1",
    )
    gp = _group_pattern(
        sequence=["LinkDown"], matching_sessions=[_session(trail_id="t1", alarms=[alarm])]
    )
    out = SampleAlarmSelector().select(gp, 10)
    # Round-trips through the model (extra="forbid"); all 5 fields present + well-typed.
    reparsed = SampleAlarm.model_validate(out[0].model_dump())
    assert reparsed == out[0]
    assert timedelta(0) <= reparsed.raisedAt - BASE  # ISO-8601 aware datetime
