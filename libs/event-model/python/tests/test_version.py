"""Criterion 3 (Python side): unknown major schemaVersion rejected.

schemaVersion=1 deserializes OK; schemaVersion=2 raises a validation error.
"""

from __future__ import annotations

import pytest

import acp_event_model as m
from acp_event_model.version import SchemaVersionError, check_schema_version

from .conftest import load_fixture_dict


def test_accepts_v1() -> None:
    env = load_fixture_dict("AlarmEvent")
    env["schemaVersion"] = 1
    assert m.deserialize(env).schemaVersion == 1


def test_rejects_v2() -> None:
    env = load_fixture_dict("AlarmEvent")
    env["schemaVersion"] = 2
    with pytest.raises(SchemaVersionError):
        m.deserialize(env)


def test_rejects_higher_majors() -> None:
    for version in (3, 99):
        with pytest.raises(SchemaVersionError):
            check_schema_version(version)


def test_policy_unit_accepts_one() -> None:
    check_schema_version(1)  # no raise
