"""KnowledgeUpdatedEvent (Python side): required fields, optional recordId,
additionalProperties rejection, round-trip.

KnowledgeUpdatedEvent is a minimal refresh trigger carried on knowledge.updated:
it tells consumers WHAT changed (recordType / recordId / version / domain) so they
re-fetch the specific version via the Knowledge API; the knowledge itself is not in
the event.
"""

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

import acp_event_model as m
from acp_event_model import KnowledgeUpdatedEvent

from .conftest import load_fixture_dict


def _ev() -> dict:
    return copy.deepcopy(load_fixture_dict("KnowledgeUpdatedEvent"))


@pytest.mark.parametrize("field", ["recordType", "version", "domain"])
def test_missing_required_field_rejected(field: str) -> None:
    env = _ev()
    del env["payload"][field]
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_record_id_optional() -> None:
    """recordId absent is valid (a broader change of that recordType)."""
    env = _ev()
    del env["payload"]["recordId"]
    typed = m.deserialize(env)
    assert typed.payload.recordId is None
    # Omitted on the wire when absent.
    out = json.loads(m.serialize(typed))
    assert "recordId" not in out["payload"]


def test_additional_properties_rejected() -> None:
    env = _ev()
    env["payload"]["unexpected"] = "x"
    with pytest.raises(ValidationError):
        m.deserialize(env)


def test_valid() -> None:
    typed = m.deserialize(_ev())
    assert isinstance(typed.payload, KnowledgeUpdatedEvent)
    assert typed.payload.recordType == "propagationTemplate"
    assert typed.payload.recordId == "PROP-TMPL-CORE-IP-042"
    assert typed.payload.version == "3"
    assert typed.payload.domain == "core-ip"


def test_round_trip() -> None:
    original = _ev()
    typed = m.deserialize(original)
    out = json.loads(m.serialize(typed))
    assert out == original
