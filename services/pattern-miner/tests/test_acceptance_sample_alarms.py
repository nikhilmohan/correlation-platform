"""[SAMPLE] Acceptance criteria AC-22..AC-26 — sampleAlarms[] XAI member-alarm evidence.

End-to-end through the 3-stage pipeline (pure-Python LocalPrefixSpanEngine — no Spark): builds
``TransactionEvent`` cascades, anchors + mines them, and asserts the emitted ``PatternMinedEvent``
carries a bounded, deterministic, model-valid ``sampleAlarms[]`` derived from the pattern's real
member alarms. K (the cap) is driven through ``default_params(sample_max_alarms=...)`` — mirroring
the Knowledge ``sample.maxAlarms`` value — so changing K here changes the emitted length with no
service code change (the pure-parse + client tests prove K flows from Knowledge). AC-26's
no-hardcoded-literal half is asserted in ``test_no_hardcoded_thresholds.py``.
"""

from __future__ import annotations

from acp_event_model import PatternMinedEvent

from .helpers import default_params, make_alarm, make_scenario, make_transaction, run_pipeline

FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]


def _fiber_scenario():
    return make_scenario(
        scenario_id="SC-FIBER", fault_origin_type="FiberCut", symptom_chain=FIBER_CUT
    )


def _fiber_cascade_txn(trail_id, *, spacing=1.0, snapshot_id="snap-1"):
    """One tight fiber-cut cascade (FiberFault -> LinkDown -> AdjDown) on a trail."""
    alarms = [
        make_alarm(
            alarm_type=t,
            raised_offset_seconds=i * spacing,
            alarm_id=f"{trail_id}-{i}-{t}",
            managed_object_id=f"Port:{i}",
        )
        for i, t in enumerate(FIBER_CUT)
    ]
    return make_transaction(trail_id=trail_id, alarms=alarms, snapshot_id=snapshot_id)


def _fiber_pattern(envelopes) -> PatternMinedEvent:
    """The single anchored fiber-cut PatternMinedEvent payload."""
    fiber = [e for e in envelopes if e.payload.provenance.anchorScenarioId == "SC-FIBER"]
    assert len(fiber) == 1
    return fiber[0].payload


# --------------------------------------------------------------------- AC-22


def test_ac22_sample_alarms_present_with_five_fields_and_validates():
    """AC-22: anchored event carries a non-empty sampleAlarms[]; each entry has the 5 fields."""
    txns = [_fiber_cascade_txn("t1"), _fiber_cascade_txn("t2")]
    envelopes = run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=10))
    payload = _fiber_pattern(envelopes)

    assert payload.sampleAlarms  # present and non-empty
    for sa in payload.sampleAlarms:
        assert sa.alarmId
        assert sa.alarmType
        assert sa.raisedAt is not None
        assert sa.managedObjectId and ":" in sa.managedObjectId  # <objectType>:<id>
        assert sa.perceivedSeverity

    # The whole event validates against the (synced) event-model with sampleAlarms present.
    reparsed = PatternMinedEvent.model_validate(payload.model_dump())
    assert reparsed.sampleAlarms is not None
    assert len(reparsed.sampleAlarms) == len(payload.sampleAlarms)


# --------------------------------------------------------------------- AC-23 / AC-26


def test_ac23_bounded_to_k_and_changing_k_changes_length():
    """AC-23/AC-26: >K alarms -> at most K; changing K (Knowledge) changes length, no code."""
    # One cascade whose session has 6 distinct alarms (3 tokens x 2 repeats of the chain).
    alarms = []
    for rep in range(2):
        for i, t in enumerate(FIBER_CUT):
            alarms.append(
                make_alarm(
                    alarm_type=t,
                    raised_offset_seconds=rep * 3 + i,
                    alarm_id=f"a-{rep}-{i}",
                    managed_object_id=f"Port:{i}",
                )
            )
    txn = make_transaction(trail_id="t1", alarms=alarms)
    # A second trail so the group has support > 1 and PrefixSpan yields the chain.
    txns = [txn, _fiber_cascade_txn("t2")]

    env_k2 = run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=2))
    env_k5 = run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=5))

    assert len(_fiber_pattern(env_k2).sampleAlarms) == 2
    assert len(_fiber_pattern(env_k5).sampleAlarms) == 5


def test_ac23_all_included_when_fewer_than_k():
    """AC-23: a session with K or fewer alarms includes them all (never pads)."""
    txns = [_fiber_cascade_txn("t1"), _fiber_cascade_txn("t2")]  # 3 alarms per session
    envelopes = run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=25))
    assert len(_fiber_pattern(envelopes).sampleAlarms) == 3


# --------------------------------------------------------------------- AC-24


def test_ac24_every_sampled_type_is_member_of_sequence():
    """AC-24: every sampled alarmType is a member of the pattern's mined sequence[]."""
    txns = [_fiber_cascade_txn("t1"), _fiber_cascade_txn("t2")]
    payload = _fiber_pattern(
        run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=10))
    )
    sequence = set(payload.sequence)
    assert sequence  # a non-empty mined sequence
    for sa in payload.sampleAlarms:
        assert sa.alarmType in sequence


# --------------------------------------------------------------------- AC-25


def test_ac25_unexplained_group_event_still_validates_with_sample():
    """AC-25/AC-21: the unexplained group emits a valid event; sampleAlarms optional + valid."""
    # A cascade that matches NO scenario -> unexplained group.
    noise = make_transaction(
        trail_id="t-noise",
        alarms=[
            make_alarm(alarm_type="Zztop", raised_offset_seconds=0, alarm_id="n-0"),
            make_alarm(alarm_type="Zztop", raised_offset_seconds=1, alarm_id="n-1"),
        ],
    )
    noise2 = make_transaction(
        trail_id="t-noise2",
        alarms=[
            make_alarm(alarm_type="Zztop", raised_offset_seconds=0, alarm_id="m-0"),
            make_alarm(alarm_type="Zztop", raised_offset_seconds=1, alarm_id="m-1"),
        ],
    )
    envelopes = run_pipeline(
        [noise, noise2], [_fiber_scenario()], default_params(sample_max_alarms=10)
    )
    unexplained = [e for e in envelopes if e.payload.provenance.anchorScenarioId is None]
    assert len(unexplained) == 1
    payload = unexplained[0].payload
    # Validates; sampleAlarms is present (optional) and each type is a member of the sequence.
    reparsed = PatternMinedEvent.model_validate(payload.model_dump())
    assert reparsed is not None
    for sa in payload.sampleAlarms or []:
        assert sa.alarmType in set(payload.sequence)


def test_ac25_empty_sample_event_still_validates():
    """AC-25: an event with an explicitly empty sampleAlarms[] still validates (optional field)."""
    txns = [_fiber_cascade_txn("t1"), _fiber_cascade_txn("t2")]
    payload = _fiber_pattern(
        run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=10))
    )
    empty = payload.model_dump()
    empty["sampleAlarms"] = []
    reparsed = PatternMinedEvent.model_validate(empty)
    assert reparsed.sampleAlarms == []


def test_ac25_absent_sample_event_still_validates():
    """AC-25: an event OMITTING sampleAlarms validates (field not in required)."""
    txns = [_fiber_cascade_txn("t1"), _fiber_cascade_txn("t2")]
    payload = _fiber_pattern(
        run_pipeline(txns, [_fiber_scenario()], default_params(sample_max_alarms=10))
    )
    absent = payload.model_dump()
    absent.pop("sampleAlarms", None)
    reparsed = PatternMinedEvent.model_validate(absent)
    assert reparsed.sampleAlarms is None


# --------------------------------------------------------------------- AC-26 (determinism)


def test_ac26_deterministic_sample_on_repeat():
    """AC-26 (determinism): re-running the same input yields identical sampleAlarms[] (replay)."""
    txns = [_fiber_cascade_txn("t1"), _fiber_cascade_txn("t2")]
    params = default_params(sample_max_alarms=10)
    first = _fiber_pattern(run_pipeline(txns, [_fiber_scenario()], params))
    second = _fiber_pattern(run_pipeline(txns, [_fiber_scenario()], params))
    assert [sa.model_dump() for sa in first.sampleAlarms] == [
        sa.model_dump() for sa in second.sampleAlarms
    ]
