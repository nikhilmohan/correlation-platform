"""Criteria 15, 16 (Python side): managedObjectId valid accepted / invalid rejected.

The scheme is domain-agnostic: ``objectType`` is any alphanumeric token starting
with a letter (the per-domain valid set is authored in the Knowledge Service, not
here). So Core-IP ids AND non-Core-IP ids (e.g. ``Site:...``, ``gNodeB:...``) are
accepted; only malformed shapes are rejected.
"""

from __future__ import annotations

import pytest

from acp_event_model import (
    KNOWN_OBJECT_TYPES,
    ManagedObjectId,
    ManagedObjectIdError,
    is_valid,
    validate,
)

# Core-IP ids (still valid under the relaxed pattern) plus non-Core-IP examples
# proving the scheme is domain-agnostic.
VALID = [
    # Core IP MVP set
    "Port:PE1-LC2-P3",
    "FiberSpan:SPAN-AB-01",
    "Node:PE1",
    "LineCard:PE1-LC2",
    "IPLink:PE1-PE2",
    "IGPAdjacency:PE1-PE2-ISIS",
    "LSP:PE1-PE3-primary",
    "VPNService:VPN-100",
    "SRLG:SRLG-7",
    # Domain-agnostic: types not in the Core-IP set still validate.
    "Site:LON-01",
    "gNodeB:g-7",
]

INVALID = [
    "NoColon",  # no colon separator
    "Node:",  # empty id
    "Node:a:b",  # colon in id
    ":x",  # empty objectType
    "9bad:x",  # objectType must start with a letter
    "Port:PE1:P3",  # id contains a colon
    "",  # empty string
]


# Criterion 15: valid accepted (Core-IP and non-Core-IP)
@pytest.mark.parametrize("value", VALID)
def test_valid_accepted(value: str) -> None:
    assert is_valid(value)
    assert validate(value) == value
    moi = ManagedObjectId.parse(value)
    assert str(moi) == value


def test_non_core_ip_object_type_accepted() -> None:
    # The relaxed pattern accepts object types outside the Core-IP example set;
    # the per-domain valid set is enforced in the Knowledge Service, not here.
    for value in ("Site:LON-01", "gNodeB:g-7"):
        moi = ManagedObjectId.parse(value)
        assert moi.object_type not in (
            "Node",
            "LineCard",
            "Port",
            "IPLink",
            "IGPAdjacency",
            "LSP",
            "VPNService",
            "FiberSpan",
            "SRLG",
        )
        assert is_valid(value)


def test_core_ip_example_set_is_non_normative_but_all_valid() -> None:
    # KNOWN_OBJECT_TYPES is a reference list only; every example still validates.
    for object_type in KNOWN_OBJECT_TYPES:
        assert is_valid(f"{object_type}:example-id")
    assert "Site" in KNOWN_OBJECT_TYPES


# Criterion 16: invalid rejected
@pytest.mark.parametrize("value", INVALID)
def test_invalid_rejected(value: str) -> None:
    assert not is_valid(value)
    with pytest.raises(ManagedObjectIdError):
        validate(value)
    with pytest.raises(ManagedObjectIdError):
        ManagedObjectId.parse(value)


def test_non_string_rejected() -> None:
    assert not is_valid(123)
    with pytest.raises(ManagedObjectIdError):
        validate(None)
