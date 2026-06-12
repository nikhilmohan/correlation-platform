"""Criterion 14 (Python side): TransactionEvent required fields."""

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

import acp_event_model as m

from .conftest import load_fixture_dict

REQUIRED = [
    "transactionId",
    "trailId",
    "snapshotId",
    "alarmIds",
    "alarms",
    "windowStart",
    "windowEnd",
]

#: The five fields each `alarms[]` entry must carry (mirrored from AlarmEvent).
ALARM_REQUIRED = ["alarmId", "eventType", "raisedAt", "managedObjectId", "perceivedSeverity"]


def _ev() -> dict:
    return copy.deepcopy(load_fixture_dict("TransactionEvent"))


@pytest.mark.parametrize("field", REQUIRED)
def test_missing_field_rejected(field: str) -> None:
    env = _ev()
    del env["payload"][field]
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_valid() -> None:
    env = m.deserialize(_ev())
    assert env.payload.transactionId == "TXN-0001"
    assert len(env.payload.alarmIds) == 3


def test_domain_present_round_trips() -> None:
    """Optional `domain` is accepted and round-trips (fixture carries core-ip)."""
    env = m.deserialize(_ev())
    assert env.payload.domain == "core-ip"
    out = json.loads(m.serialize(env))
    assert out["payload"]["domain"] == "core-ip"


def test_domain_absent_is_optional() -> None:
    """`domain` is optional (not required) — absence is valid, deserializes to None."""
    ev = _ev()
    del ev["payload"]["domain"]
    env = m.deserialize(ev)
    assert env.payload.domain is None


def test_alarms_typed_detail() -> None:
    """`alarms[]` deserializes to typed per-alarm detail mirroring AlarmEvent fields."""
    env = m.deserialize(_ev())
    alarms = env.payload.alarms
    assert len(alarms) == 3
    first = alarms[0]
    assert isinstance(first, m.Alarm)
    assert first.alarmId == "ALM-0001"
    assert first.eventType == "communicationsAlarm"
    assert first.managedObjectId == "Port:PE1-LC2-P3"
    assert first.perceivedSeverity == "critical"
    assert first.raisedAt.isoformat().startswith("2026-06-08T12:30:05")


def test_alarms_order_preserved() -> None:
    """`alarms[]` is ORDERED — the Pattern Miner depends on sequence preservation."""
    env = m.deserialize(_ev())
    assert [a.alarmId for a in env.payload.alarms] == ["ALM-0001", "ALM-0002", "ALM-0003"]
    # Re-serialize and confirm the wire order is unchanged (no reordering on output).
    out = json.loads(m.serialize(env))
    assert [a["alarmId"] for a in out["payload"]["alarms"]] == [
        "ALM-0001",
        "ALM-0002",
        "ALM-0003",
    ]


def test_alarms_round_trip_matches_fixture() -> None:
    """`alarms[]` round-trips byte-equal to the golden fixture (cross-binding anchor)."""
    expected = _ev()
    env = m.deserialize(expected)
    out = json.loads(m.serialize(env))
    assert out["payload"]["alarms"] == expected["payload"]["alarms"]


@pytest.mark.parametrize("field", ALARM_REQUIRED)
def test_alarm_entry_missing_field_rejected(field: str) -> None:
    """Each `alarms[]` entry requires all five fields (additionalProperties:false)."""
    ev = _ev()
    del ev["payload"]["alarms"][0][field]
    with pytest.raises(ValidationError):
        m.deserialize(ev)


def test_alarm_entry_extra_field_rejected() -> None:
    """`alarms[]` entries are strict — unknown fields are rejected."""
    ev = _ev()
    ev["payload"]["alarms"][0]["unexpected"] = "x"
    with pytest.raises(ValidationError):
        m.deserialize(ev)


def test_alarm_entry_bad_managed_object_id_rejected() -> None:
    """`alarms[].managedObjectId` reuses the shared scheme; malformed ids are rejected."""
    ev = _ev()
    ev["payload"]["alarms"][0]["managedObjectId"] = "NoColon"
    with pytest.raises(ValidationError):
        m.deserialize(ev)


def test_alarms_empty_array_accepted() -> None:
    """`alarms` is required but an empty (still-ordered) array is a valid value."""
    ev = _ev()
    ev["payload"]["alarms"] = []
    env = m.deserialize(ev)
    assert env.payload.alarms == []
