"""``managedObjectId`` value type and validation (spec criteria 7, 15, 16).

The ``managedObjectId`` is the shared identity binding between alarms (Simulator)
and the topology graph (Topology Service). Wire format is a single string
``"<objectType>:<id>"`` where ``objectType`` is one of the nine known typed
graph layers and ``id`` is a stable, non-empty string containing no colon.

The validation rule here mirrors the JSON Schema ``pattern`` in
``schema/common/managedObjectId.schema.json`` — the schema is the source of
truth; this module is a thin, schema-agnostic helper (it references the known
type names, not any payload field list).
"""

from __future__ import annotations

import re
from dataclasses import dataclass

#: The nine known typed graph layers (Solution Design §5). Frozen contract.
KNOWN_OBJECT_TYPES: tuple[str, ...] = (
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

#: Wire-format pattern: ``<knownObjectType>:<non-empty id with no colon>``.
PATTERN: re.Pattern[str] = re.compile(r"^(" + "|".join(KNOWN_OBJECT_TYPES) + r"):[^:]+$")


class ManagedObjectIdError(ValueError):
    """Raised when a ``managedObjectId`` string is not well-formed."""


@dataclass(frozen=True)
class ManagedObjectId:
    """Parsed ``managedObjectId``: an ``objectType`` and an ``id``.

    Construct via :meth:`parse` (validates) or :meth:`__init__` with already
    valid components. ``str(moi)`` returns the canonical wire form.
    """

    object_type: str
    id: str

    def __post_init__(self) -> None:
        validate(f"{self.object_type}:{self.id}")

    @classmethod
    def parse(cls, value: str) -> ManagedObjectId:
        """Parse and validate a wire string into a :class:`ManagedObjectId`."""
        validate(value)
        object_type, _, identifier = value.partition(":")
        return cls(object_type=object_type, id=identifier)

    def __str__(self) -> str:
        return f"{self.object_type}:{self.id}"


def is_valid(value: object) -> bool:
    """Return ``True`` iff ``value`` is a well-formed ``managedObjectId`` string."""
    return isinstance(value, str) and PATTERN.fullmatch(value) is not None


def validate(value: object) -> str:
    """Validate a ``managedObjectId`` string, returning it on success.

    Raises:
        ManagedObjectIdError: if ``value`` is not of the form
            ``<knownObjectType>:<non-empty-id>``.
    """
    if not is_valid(value):
        raise ManagedObjectIdError(
            f"invalid managedObjectId {value!r}: expected "
            f"'<objectType>:<id>' with objectType in {KNOWN_OBJECT_TYPES} "
            f"and a non-empty id containing no colon"
        )
    assert isinstance(value, str)
    return value
