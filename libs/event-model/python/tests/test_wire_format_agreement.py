"""Criterion 1 (Python side): wire-format agreement via the shared golden fixtures.

Each binding deserializes each golden fixture to a typed object with identical
field values, and re-serializes to JSON canonically equal to the fixture. The
Java binding reads the *same* files, so agreement is proven without a polyglot
CI job.
"""

from __future__ import annotations

import json

import pytest

import acp_event_model as m

from .conftest import PAYLOAD_TYPES, load_fixture_dict, load_fixture_text


@pytest.mark.parametrize("payload_type", PAYLOAD_TYPES)
def test_deserialize_golden(payload_type: str) -> None:
    env = m.deserialize(load_fixture_text(payload_type))
    assert env.type == payload_type
    assert type(env.payload).__name__ == payload_type


@pytest.mark.parametrize("payload_type", PAYLOAD_TYPES)
def test_serialize_matches_golden(payload_type: str) -> None:
    expected = load_fixture_dict(payload_type)
    env = m.deserialize(load_fixture_text(payload_type))
    produced = json.loads(env.to_json())
    assert produced == expected, f"{payload_type}: re-serialized JSON drifted from golden fixture"
