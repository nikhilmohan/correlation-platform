"""Criterion 13 (Python side): CodebookGeneratedEvent required fields."""

from __future__ import annotations

import copy

import pytest
from pydantic import ValidationError

import acp_event_model as m

from .conftest import load_fixture_dict


def _ev() -> dict:
    return copy.deepcopy(load_fixture_dict("CodebookGeneratedEvent"))


@pytest.mark.parametrize("field", ["snapshotId", "scenarioCount", "codebookId"])
def test_missing_field_rejected(field: str) -> None:
    env = _ev()
    del env["payload"][field]
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_valid() -> None:
    env = m.deserialize(_ev())
    assert env.payload.scenarioCount == 12
    assert env.payload.codebookId == "CODEBOOK-2026-06-08-001"
