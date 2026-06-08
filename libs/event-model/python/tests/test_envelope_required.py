"""Criterion 6 (Python side): required envelope fields enforced (7 fields)."""

from __future__ import annotations

import pytest

import acp_event_model as m

from .conftest import load_fixture_dict

ENVELOPE_FIELDS = [
    "eventId",
    "type",
    "schemaVersion",
    "occurredAt",
    "source",
    "traceId",
    "payload",
]


@pytest.mark.parametrize("field", ENVELOPE_FIELDS)
def test_missing_field_rejected(field: str) -> None:
    env = load_fixture_dict("AlarmEvent")
    del env[field]
    with pytest.raises(m.CodecError):
        m.deserialize(env)


def test_unknown_top_level_field_rejected() -> None:
    env = load_fixture_dict("AlarmEvent")
    env["bogus"] = "x"
    with pytest.raises(m.CodecError):
        m.deserialize(env)
