"""AlarmStatusChange (Python side): round-trip, required fields, newStatus enum,
additionalProperties rejection, registry resolution.

AlarmStatusChange is a generic alarm-lifecycle status-change event carried on
``alarms.status.changed``: any service may fire it, and the Alarm Manager
consumes it to keep live alarm status in sync. It is deliberately minimal and
carries no correlation context (that lives on CorrelationResultEvent).
"""

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

import acp_event_model as m
from acp_event_model import AlarmStatusChange
from acp_event_model.registry import resolve_payload_type

from .conftest import load_fixture_dict

VALID_STATUSES = ["open", "in-progress", "correlated", "cleared", "reverted-open"]


def _ev() -> dict:
    return copy.deepcopy(load_fixture_dict("AlarmStatusChange"))


def test_round_trip() -> None:
    """The golden fixture round-trips through the Python binding unchanged."""
    original = _ev()
    typed = m.deserialize(original)
    out = json.loads(m.serialize(typed))
    assert out == original


def test_valid() -> None:
    typed = m.deserialize(_ev())
    assert isinstance(typed.payload, AlarmStatusChange)
    assert typed.payload.alarmId == "ALM-0001"
    assert typed.payload.newStatus.value == "correlated"
    assert typed.payload.source == "correlation-engine"


@pytest.mark.parametrize("field", ["alarmId", "newStatus", "source", "changedAt"])
def test_missing_required_field_rejected(field: str) -> None:
    env = _ev()
    del env["payload"][field]
    with pytest.raises(ValidationError):
        m.deserialize(env)


@pytest.mark.parametrize("status", VALID_STATUSES)
def test_new_status_enum_accepts_all_five_values(status: str) -> None:
    env = _ev()
    env["payload"]["newStatus"] = status
    typed = m.deserialize(env)
    assert typed.payload.newStatus.value == status


def test_new_status_enum_rejects_invalid_value() -> None:
    env = _ev()
    env["payload"]["newStatus"] = "flapping"
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_additional_properties_rejected() -> None:
    """additionalProperties:false — an unknown sub-field is rejected (no
    correlation context like incidentId/role belongs here)."""
    env = _ev()
    env["payload"]["incidentId"] = "INC-0001"
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_registry_resolves_alarm_status_change() -> None:
    assert resolve_payload_type("AlarmStatusChange") is AlarmStatusChange
    # And end to end via the codec discriminator.
    typed = m.deserialize(_ev())
    assert type(typed.payload) is AlarmStatusChange
