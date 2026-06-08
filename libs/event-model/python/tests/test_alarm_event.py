"""Criteria 7, 8, 9 (Python side): AlarmEvent managedObjectId / state / optionals."""

from __future__ import annotations

import copy

import pytest
from pydantic import ValidationError

import acp_event_model as m

from .conftest import load_fixture_dict


def _alarm() -> dict:
    return copy.deepcopy(load_fixture_dict("AlarmEvent"))


# Criterion 7
def test_missing_managed_object_id_rejected() -> None:
    env = _alarm()
    del env["payload"]["managedObjectId"]
    with pytest.raises(ValidationError):
        m.deserialize(env)


# Criterion 8
def test_invalid_state_rejected() -> None:
    env = _alarm()
    env["payload"]["state"] = "flapping"
    with pytest.raises(ValidationError):
        m.deserialize(env)


@pytest.mark.parametrize("state", ["raised", "cleared"])
def test_valid_states(state: str) -> None:
    env = _alarm()
    env["payload"]["state"] = state
    result = m.deserialize(env)
    assert result.payload.state.value == state


# Criterion 9
def test_optional_fields_absent() -> None:
    env = _alarm()
    env["payload"].pop("clearedAt", None)
    env["payload"].pop("vendorRaw", None)
    result = m.deserialize(env)
    assert result.payload.clearedAt is None
    assert result.payload.vendorRaw is None
    # Absent optionals are omitted from the wire output, not emitted as null.
    import json

    out = json.loads(m.serialize(result))
    assert "clearedAt" not in out["payload"]
    assert "vendorRaw" not in out["payload"]


def test_managed_object_id_pattern_enforced_on_alarm() -> None:
    env = _alarm()
    env["payload"]["managedObjectId"] = "Switch:X1"  # unknown objectType
    with pytest.raises(ValidationError):
        m.deserialize(env)
