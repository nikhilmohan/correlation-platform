"""Criterion 5 (Python side): `type` discriminates to exactly one payload.

Each of the nine `type` strings resolves to exactly its payload class; an
unrecognized `type` raises.
"""

from __future__ import annotations

import pytest

import acp_event_model as m
from acp_event_model.registry import UnknownEventTypeError, resolve_payload_type

from .conftest import PAYLOAD_TYPES, load_fixture_dict


@pytest.mark.parametrize("payload_type", PAYLOAD_TYPES)
def test_resolves(payload_type: str) -> None:
    cls = resolve_payload_type(payload_type)
    assert cls.__name__ == payload_type
    # And via the codec end to end.
    env = m.deserialize(load_fixture_dict(payload_type))
    assert type(env.payload) is cls


def test_each_type_resolves_to_a_distinct_class() -> None:
    classes = {resolve_payload_type(t) for t in PAYLOAD_TYPES}
    assert len(classes) == len(PAYLOAD_TYPES)  # 1:1, no collisions


def test_unknown_type_rejected_registry() -> None:
    with pytest.raises(UnknownEventTypeError):
        resolve_payload_type("FooEvent")


def test_unknown_type_rejected_codec() -> None:
    env = load_fixture_dict("AlarmEvent")
    env["type"] = "FooEvent"
    # The envelope enum rejects the unknown discriminator before dispatch.
    with pytest.raises((UnknownEventTypeError, m.CodecError)):
        m.deserialize(env)
