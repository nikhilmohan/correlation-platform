"""Criterion 14 (Python side): TransactionEvent required fields."""

from __future__ import annotations

import copy

import pytest
from pydantic import ValidationError

import acp_event_model as m

from .conftest import load_fixture_dict

REQUIRED = ["transactionId", "trailId", "snapshotId", "alarmIds", "windowStart", "windowEnd"]


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
