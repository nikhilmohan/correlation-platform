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
