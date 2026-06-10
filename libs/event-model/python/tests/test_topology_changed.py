"""TopologyChangedEvent (Python side): required fields + optional `domain`.

`domain` is the optional snapshot-domain field added alongside `snapshotId`
(multi-domain consistency). It is OPTIONAL/backward-compatible: present and
absent are both valid, and when present it round-trips.
"""

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

import acp_event_model as m

from .conftest import load_fixture_dict


def _ev() -> dict:
    return copy.deepcopy(load_fixture_dict("TopologyChangedEvent"))


@pytest.mark.parametrize("field", ["snapshotId", "changeType", "nodes", "edges"])
def test_missing_required_field_rejected(field: str) -> None:
    env = _ev()
    del env["payload"][field]
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_valid() -> None:
    env = m.deserialize(_ev())
    assert env.payload.snapshotId == "SNAP-2026-06-08-001"
    assert env.payload.changeType == "full-load"


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
