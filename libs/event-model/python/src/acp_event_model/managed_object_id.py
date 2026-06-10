"""``managedObjectId`` value type and validation (spec criteria 7, 15, 16).

The ``managedObjectId`` is the shared identity binding between alarms (Simulator)
and the topology graph (Topology Service). Wire format is a single string
``"<objectType>:<id>"`` where ``objectType`` is **any alphanumeric token starting
with a letter** and ``id`` is a stable, non-empty string containing no colon.

The scheme is **domain-agnostic**: the event-model does not enumerate the valid
object types. The valid object-type set *per domain* is authored in the Knowledge
Service, not frozen here. (Core IP's MVP set is ``Node``, ``LineCard``, ``Port``,
``IPLink``, ``IGPAdjacency``, ``LSP``, ``VPNService``, ``FiberSpan``, ``SRLG``,
``Site`` — but these are examples, not a validation constraint.)

The validation rule here mirrors the JSON Schema ``pattern`` in
``schema/common/managedObjectId.schema.json`` — the schema is the source of
truth; this module is a thin helper that validates the generic shape only (it
does **not** reference any per-domain object-type list).
"""

from __future__ import annotations

import re
from dataclasses import dataclass

#: Core IP's MVP object-type set (Solution Design §5), provided for reference and
#: convenience only. This is **non-normative**: it is NOT used by validation, and
#: the per-domain valid set is authored in the Knowledge Service, not here. Other
#: domains use their own types (e.g. ``Site``, ``gNodeB``), which validate fine.
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
    "Site",
)

#: Wire-format pattern: ``<objectType>:<id>`` where ``objectType`` is any token
#: starting with a letter then alphanumerics, and ``id`` is non-empty with no
#: colon. Domain-agnostic — no object-type enumeration is baked in.
PATTERN: re.Pattern[str] = re.compile(r"^[A-Za-z][A-Za-z0-9]*:[^:]+$")


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
            ``<objectType>:<non-empty-id>`` where ``objectType`` is an
            alphanumeric token starting with a letter and ``id`` contains no
            colon.
    """
    if not is_valid(value):
        raise ManagedObjectIdError(
            f"invalid managedObjectId {value!r}: expected "
            f"'<objectType>:<id>' with objectType an alphanumeric token "
            f"starting with a letter and a non-empty id containing no colon"
        )
    assert isinstance(value, str)
    return value
