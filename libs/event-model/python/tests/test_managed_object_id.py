"""Criteria 15, 16 (Python side): managedObjectId valid accepted / invalid rejected."""

from __future__ import annotations

import pytest

from acp_event_model import (
    KNOWN_OBJECT_TYPES,
    ManagedObjectId,
    ManagedObjectIdError,
    is_valid,
    validate,
)

VALID = [
    "Port:PE1-LC2-P3",
    "FiberSpan:SPAN-AB-01",
    "Node:PE1",
    "LineCard:PE1-LC2",
    "IPLink:PE1-PE2",
    "IGPAdjacency:PE1-PE2-ISIS",
    "LSP:PE1-PE3-primary",
    "VPNService:VPN-100",
    "SRLG:SRLG-7",
]

INVALID = [
    "Switch:X1",  # (a) unknown objectType
    "Port:",  # (b) empty id
    "PE1-LC2-P3",  # (c) no colon separator
    "",  # (d) empty string
]


# Criterion 15
@pytest.mark.parametrize("value", VALID)
def test_valid_accepted(value: str) -> None:
    assert is_valid(value)
    assert validate(value) == value
    moi = ManagedObjectId.parse(value)
    assert str(moi) == value
    assert moi.object_type in KNOWN_OBJECT_TYPES


def test_all_nine_known_types_have_a_valid_example() -> None:
    covered = {ManagedObjectId.parse(v).object_type for v in VALID}
    assert covered == set(KNOWN_OBJECT_TYPES)


# Criterion 16
@pytest.mark.parametrize("value", INVALID)
def test_invalid_rejected(value: str) -> None:
    assert not is_valid(value)
    with pytest.raises(ManagedObjectIdError):
        validate(value)
    with pytest.raises(ManagedObjectIdError):
        ManagedObjectId.parse(value)


def test_id_with_colon_rejected() -> None:
    # id must contain no colon characters.
    assert not is_valid("Port:PE1:P3")


def test_non_string_rejected() -> None:
    assert not is_valid(123)
    with pytest.raises(ManagedObjectIdError):
        validate(None)
