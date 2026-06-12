"""Criterion 4 (Python side): envelope round-trip per payload type (9 tests).

serialize(envelope+payload) -> JSON -> deserialize equals the original
(required + optional fields preserved).
"""

from __future__ import annotations

import json

import pytest

import acp_event_model as m

from .conftest import PAYLOAD_TYPES, load_fixture_text


@pytest.mark.parametrize("payload_type", PAYLOAD_TYPES)
def test_round_trip(payload_type: str) -> None:
    original_text = load_fixture_text(payload_type)
    env1 = m.deserialize(original_text)

    serialized = m.serialize(env1)
    env2 = m.deserialize(serialized)

    # Round-tripping through JSON yields an equal typed object.
    assert env2.payload == env1.payload
    assert env2.eventId == env1.eventId
    assert env2.type == env1.type
    assert env2.schemaVersion == env1.schemaVersion
    assert env2.source == env1.source
    assert env2.traceId == env1.traceId

    # And canonically equal wire bytes (idempotent serialization).
    assert json.loads(serialized) == json.loads(m.serialize(env2))
    assert json.loads(serialized) == json.loads(original_text)


def test_round_trip_alarm_with_optionals() -> None:
    """clearedAt + vendorRaw present round-trip correctly (optionals preserved)."""
    env_dict = {
        "eventId": "11111111-1111-4111-8111-111111111111",
        "type": "AlarmEvent",
        "schemaVersion": 1,
        "occurredAt": "2026-06-08T12:34:56Z",
        "source": "simulator",
        "traceId": "trace-x",
        "payload": {
            "alarmId": "ALM-9",
            "managedObjectId": "Node:PE1",
            "eventType": "communicationsAlarm",
            "probableCause": "lossOfSignal",
            "alarmType": "LOS",
            "perceivedSeverity": "critical",
            "raisedAt": "2026-06-08T12:34:55Z",
            "clearedAt": "2026-06-08T12:40:00Z",
            "state": "cleared",
            "vendorRaw": {"foo": "bar", "n": 1},
            "trailIds": ["TRAIL-1"],
        },
    }
    env = m.deserialize(env_dict)
    out = json.loads(m.serialize(env))
    assert out["payload"]["clearedAt"] == "2026-06-08T12:40:00Z"
    assert out["payload"]["vendorRaw"] == {"foo": "bar", "n": 1}
    assert out["payload"]["state"] == "cleared"
