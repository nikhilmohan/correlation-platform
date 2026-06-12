"""sessionWindow on PatternDiscoveredEvent + PatternApprovedEvent (Python side).

The `sessionWindow` is the per-pattern session-window RULE the Correlation Engine
uses to govern each correlation instance's lifetime — an authored operational
directive (populated by the Pattern Manager), distinct from the descriptive
`timing` statistics, which stay as-is. It is a structured object
(`windowMs` int + `type` enum) and is REQUIRED on BOTH events.

These tests double as the cross-binding wire-format anchor: the same golden
fixtures (PatternDiscoveredEvent.json / PatternApprovedEvent.json) are read by
the Java binding's tests, so both bindings must agree on `sessionWindow`.
"""

from __future__ import annotations

import copy
import json

import pytest
from pydantic import ValidationError

import acp_event_model as m
from acp_event_model import (
    PatternApprovedEvent,
    PatternDiscoveredEvent,
    SessionWindow,
)

from .conftest import load_fixture_dict

#: The two pattern events that carry the shared `sessionWindow` rule.
PATTERN_EVENTS = ["PatternDiscoveredEvent", "PatternApprovedEvent"]

#: The sub-fields the `sessionWindow` object requires.
SESSION_WINDOW_REQUIRED = ["windowMs", "type"]


def _ev(payload_type: str) -> dict:
    return copy.deepcopy(load_fixture_dict(payload_type))


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
def test_session_window_round_trips(payload_type: str) -> None:
    """`sessionWindow` deserializes to a typed nested model and round-trips byte-equal."""
    expected = _ev(payload_type)
    env = m.deserialize(expected)
    sw = env.payload.sessionWindow
    assert isinstance(sw, SessionWindow)
    assert sw.windowMs == 60000
    assert sw.type.value == "gap-based"
    out = json.loads(m.serialize(env))
    assert out["payload"]["sessionWindow"] == expected["payload"]["sessionWindow"]


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
def test_session_window_required_on_event(payload_type: str) -> None:
    """`sessionWindow` is a REQUIRED top-level field on both events."""
    model = {
        "PatternDiscoveredEvent": PatternDiscoveredEvent,
        "PatternApprovedEvent": PatternApprovedEvent,
    }[payload_type]
    assert "sessionWindow" in model.model_fields
    assert model.model_fields["sessionWindow"].is_required()

    ev = _ev(payload_type)
    del ev["payload"]["sessionWindow"]
    with pytest.raises(ValidationError):
        m.deserialize(ev)


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
@pytest.mark.parametrize("sub", SESSION_WINDOW_REQUIRED)
def test_session_window_subfield_required(payload_type: str, sub: str) -> None:
    """Both `windowMs` and `type` are required sub-fields of `sessionWindow`."""
    ev = _ev(payload_type)
    del ev["payload"]["sessionWindow"][sub]
    with pytest.raises(ValidationError):
        m.deserialize(ev)


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
def test_session_window_type_accepts_fixed(payload_type: str) -> None:
    """`type` accepts the other enum value `fixed`."""
    ev = _ev(payload_type)
    ev["payload"]["sessionWindow"]["type"] = "fixed"
    env = m.deserialize(ev)
    assert env.payload.sessionWindow.type.value == "fixed"


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
def test_session_window_type_rejects_invalid(payload_type: str) -> None:
    """`type` is a closed enum — any value other than gap-based/fixed is rejected."""
    ev = _ev(payload_type)
    ev["payload"]["sessionWindow"]["type"] = "rolling"
    with pytest.raises(ValidationError):
        m.deserialize(ev)


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
def test_session_window_window_ms_must_be_integer(payload_type: str) -> None:
    """`windowMs` is an integer — a non-numeric value is rejected."""
    ev = _ev(payload_type)
    ev["payload"]["sessionWindow"]["windowMs"] = "soon"
    with pytest.raises(ValidationError):
        m.deserialize(ev)


@pytest.mark.parametrize("payload_type", PATTERN_EVENTS)
def test_session_window_rejects_extra_field(payload_type: str) -> None:
    """`sessionWindow` is additionalProperties:false — unknown sub-fields are rejected."""
    ev = _ev(payload_type)
    ev["payload"]["sessionWindow"]["graceMs"] = 1000
    with pytest.raises(ValidationError):
        m.deserialize(ev)


def test_session_window_shared_model_both_events() -> None:
    """Both events bind `sessionWindow` to the SAME shared SessionWindow model."""
    assert PatternDiscoveredEvent.model_fields["sessionWindow"].annotation is SessionWindow
    assert PatternApprovedEvent.model_fields["sessionWindow"].annotation is SessionWindow


def test_timing_unchanged_alongside_session_window() -> None:
    """`timing` is untouched: still present, a free-form object, distinct from sessionWindow."""
    env = m.deserialize(_ev("PatternApprovedEvent"))
    assert env.payload.timing == {"meanInterArrivalSeconds": 4.5, "stdDevSeconds": 1.2}
    # sessionWindow is a separate, additional field — not derived from / merged into timing.
    out = json.loads(m.serialize(env))
    assert "timing" in out["payload"]
    assert "sessionWindow" in out["payload"]
