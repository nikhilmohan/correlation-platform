"""Codec helper behaviour: input forms, error paths, canonical timestamp output."""

from __future__ import annotations

import json

import pytest

import acp_event_model as m

from .conftest import load_fixture_text


def test_deserialize_accepts_bytes() -> None:
    raw = load_fixture_text("AlarmEvent").encode("utf-8")
    env = m.deserialize(raw)
    assert env.type == "AlarmEvent"


def test_to_dict_matches_to_json() -> None:
    env = m.deserialize(load_fixture_text("AlarmEvent"))
    assert env.to_dict() == json.loads(env.to_json())


def test_bad_json_rejected() -> None:
    with pytest.raises(m.CodecError):
        m.deserialize("{not valid json")


def test_non_object_json_rejected() -> None:
    with pytest.raises(m.CodecError):
        m.deserialize("[1, 2, 3]")


def test_millisecond_timestamp_serializes_with_z() -> None:
    env_dict = json.loads(load_fixture_text("AlarmEvent"))
    env_dict["occurredAt"] = "2026-06-08T12:34:56.123Z"
    out = m.deserialize(env_dict).to_dict()
    assert out["occurredAt"] == "2026-06-08T12:34:56.123Z"


def test_offset_timestamp_normalized_to_utc_z() -> None:
    env_dict = json.loads(load_fixture_text("AlarmEvent"))
    env_dict["occurredAt"] = "2026-06-08T14:34:56+02:00"
    out = m.deserialize(env_dict).to_dict()
    assert out["occurredAt"] == "2026-06-08T12:34:56Z"
