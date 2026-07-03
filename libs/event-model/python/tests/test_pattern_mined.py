"""Criteria 10, 11 (Python side): PatternMinedEvent no-RCA/lifecycle; provenance."""

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

import acp_event_model as m
from acp_event_model import PatternMinedEvent

from .conftest import load_fixture_dict


def _mined() -> dict:
    return copy.deepcopy(load_fixture_dict("PatternMinedEvent"))


# Criterion 10
def test_no_rca_lifecycle_patternid() -> None:
    field_names = set(PatternMinedEvent.model_fields)
    assert "rootCauseAlarmType" not in field_names
    assert "lifecycle" not in field_names
    assert "patternId" not in field_names


@pytest.mark.parametrize("forbidden", ["rootCauseAlarmType", "lifecycle", "patternId"])
def test_forbidden_fields_rejected(forbidden: str) -> None:
    env = _mined()
    env["payload"][forbidden] = "x"
    # extra="forbid" -> such input fields are rejected and never reach the wire.
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_forbidden_fields_absent_from_serialized_output() -> None:
    env = m.deserialize(_mined())
    out = json.loads(m.serialize(env))
    for forbidden in ("rootCauseAlarmType", "lifecycle", "patternId"):
        assert forbidden not in out["payload"]


# Criterion 11
def test_provenance_required() -> None:
    env = _mined()
    del env["payload"]["provenance"]
    with pytest.raises(ValidationError):
        m.deserialize(env)


@pytest.mark.parametrize("sub", ["sourceWindowId", "snapshotId", "codebookVersion"])
def test_provenance_subfields_required(sub: str) -> None:
    env = _mined()
    del env["payload"]["provenance"][sub]
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_trailid_top_level() -> None:
    """trailId is a top-level field, validated independently of provenance."""
    assert "trailId" in PatternMinedEvent.model_fields
    env = _mined()
    del env["payload"]["trailId"]
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_provenance_is_nested_object() -> None:
    env = m.deserialize(_mined())
    assert env.payload.provenance.sourceWindowId == "TXN-0001"
    assert env.payload.provenance.snapshotId == "SNAP-2026-06-08-001"
    assert env.payload.provenance.codebookVersion == "CODEBOOK-2026-06-08-001"


def test_provenance_domain_present_round_trips() -> None:
    """Optional `domain` lives in provenance and round-trips (fixture carries core-ip)."""
    env = m.deserialize(_mined())
    assert env.payload.provenance.domain == "core-ip"
    out = json.loads(m.serialize(env))
    assert out["payload"]["provenance"]["domain"] == "core-ip"


def test_provenance_domain_absent_is_optional() -> None:
    """`domain` is optional in provenance (not required) — absence is valid -> None."""
    env = _mined()
    del env["payload"]["provenance"]["domain"]
    typed = m.deserialize(env)
    assert typed.payload.provenance.domain is None


def test_provenance_anchor_scenario_id_present_round_trips() -> None:
    """(a) Optional `anchorScenarioId` lives in provenance and round-trips (fixture carries it)."""
    env = m.deserialize(_mined())
    assert env.payload.provenance.anchorScenarioId == "CODEBOOK-2026-06-08-001:FiberSpan:F-N0_N1"
    out = json.loads(m.serialize(env))
    assert (
        out["payload"]["provenance"]["anchorScenarioId"]
        == "CODEBOOK-2026-06-08-001:FiberSpan:F-N0_N1"
    )


def test_provenance_anchor_scenario_id_absent_is_optional() -> None:
    """(b) Backward-compat: a provenance lacking anchorScenarioId still validates -> None.

    null/absent = "unexplained" cascade (a first-class outcome, not an error).
    """
    env = _mined()
    del env["payload"]["provenance"]["anchorScenarioId"]
    typed = m.deserialize(env)
    assert typed.payload.provenance.anchorScenarioId is None
    # And it does not leak into the wire when absent (NON_NULL inclusion).
    out = json.loads(m.serialize(typed))
    assert "anchorScenarioId" not in out["payload"]["provenance"]


def test_provenance_anchor_scenario_id_not_required() -> None:
    """(c) anchorScenarioId is optional — NOT in provenance.required."""
    from acp_event_model import _generated

    fields = _generated.Provenance.model_fields
    assert "anchorScenarioId" in fields
    assert fields["anchorScenarioId"].is_required() is False


# Optional top-level sampleAlarms[] — a bounded sample of the real member alarms a
# pattern was mined from (operator review / XAI). Present + absent (backward-compat).


def test_sample_alarms_present_round_trips() -> None:
    """(a) Optional `sampleAlarms` round-trips: all 5 fields of each item survive."""
    env = m.deserialize(_mined())
    samples = env.payload.sampleAlarms
    assert samples is not None
    assert len(samples) == 2
    first = samples[0]
    assert first.alarmId == "ALM-0001262"
    assert first.alarmType == "lossOfSignal"
    assert first.managedObjectId == "IPLink:N6_N7"
    assert first.perceivedSeverity == "major"
    # raisedAt parsed as an aware datetime; check it round-trips to the wire.
    out = json.loads(m.serialize(env))
    wire_samples = out["payload"]["sampleAlarms"]
    assert len(wire_samples) == 2
    assert wire_samples[0]["alarmId"] == "ALM-0001262"
    assert wire_samples[0]["alarmType"] == "lossOfSignal"
    assert wire_samples[0]["managedObjectId"] == "IPLink:N6_N7"
    assert wire_samples[0]["perceivedSeverity"] == "major"
    assert "raisedAt" in wire_samples[0]


def test_sample_alarms_absent_is_optional() -> None:
    """(b) Backward-compat: a PatternMinedEvent WITHOUT sampleAlarms still validates -> None.

    Existing messages that never carried a sample must still deserialize.
    """
    env = _mined()
    del env["payload"]["sampleAlarms"]
    typed = m.deserialize(env)
    assert typed.payload.sampleAlarms is None
    # And it does not leak into the wire when absent (NON_NULL inclusion).
    out = json.loads(m.serialize(typed))
    assert "sampleAlarms" not in out["payload"]


def test_sample_alarms_not_required() -> None:
    """(c) sampleAlarms is optional — NOT in PatternMinedEvent.required."""
    fields = PatternMinedEvent.model_fields
    assert "sampleAlarms" in fields
    assert fields["sampleAlarms"].is_required() is False


def test_sample_alarm_item_fields_required_and_no_extras() -> None:
    """Each present sample alarm must be complete (5 required fields) and reject extras."""
    from acp_event_model import _generated

    item_fields = _generated.SampleAlarm.model_fields
    for f in ("alarmId", "alarmType", "raisedAt", "managedObjectId", "perceivedSeverity"):
        assert f in item_fields
        assert item_fields[f].is_required() is True

    # A present-but-incomplete sample alarm is rejected.
    env = _mined()
    del env["payload"]["sampleAlarms"][0]["perceivedSeverity"]
    with pytest.raises(ValidationError):
        m.deserialize(env)

    # additionalProperties:false -> an unknown field inside an item is rejected.
    env = _mined()
    env["payload"]["sampleAlarms"][0]["bogus"] = "x"
    with pytest.raises(ValidationError):
        m.deserialize(env)
