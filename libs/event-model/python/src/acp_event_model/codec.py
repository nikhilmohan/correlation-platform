"""(De)serialization helpers — the codec (spec criteria 4, 5, 6).

The codec orchestrates the deserialize flow defined in the design:

  parse JSON -> validate envelope shape (required fields) -> check
  schemaVersion (reject major >= 2) -> resolve payload class by ``type``
  (reject unknown) -> validate + construct the typed payload.

On the wire, the canonical format (design "Canonical wire format") is enforced:
ISO-8601 UTC timestamps with a ``Z`` suffix, integer ``schemaVersion``, lowercase
enums, optional fields omitted when absent, empty arrays emitted as ``[]``,
``managedObjectId`` as a single string, and unknown fields rejected
(``extra="forbid"`` on every generated model).
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

from pydantic import (
    BaseModel,
    ConfigDict,
    SerializeAsAny,
    ValidationError,
    field_serializer,
)

from ._generated import Envelope as _GeneratedEnvelope
from .registry import resolve_payload_type
from .version import check_schema_version


class CodecError(ValueError):
    """Raised when an envelope cannot be (de)serialized per the contract."""


class TypedEnvelope[P: BaseModel](BaseModel):
    """Envelope with its payload resolved to a concrete typed model.

    Mirrors the envelope's seven wire fields exactly; ``payload`` holds the
    typed payload instance rather than a raw dict. Serializing emits the
    canonical wire JSON.
    """

    model_config = ConfigDict(extra="forbid")

    eventId: str
    type: str
    schemaVersion: int
    occurredAt: Any
    source: str
    traceId: str
    # SerializeAsAny: serialize the payload by its *runtime* type (the concrete
    # payload model), not by the declared generic bound, so all payload fields
    # are emitted on the wire.
    payload: SerializeAsAny[P]

    @field_serializer("occurredAt")
    def _ser_occurred_at(self, value: Any) -> Any:
        return _iso_utc(value)

    def to_json(self) -> str:
        """Serialize to canonical wire JSON (UTC ``Z`` times, optionals omitted)."""
        return self.model_dump_json(exclude_none=True, by_alias=True)

    def to_dict(self) -> dict[str, Any]:
        """Serialize to a canonical wire ``dict`` (JSON-compatible types)."""
        return json.loads(self.to_json())


def _iso_utc(value: Any) -> Any:
    """Render a datetime as ISO-8601 UTC with a trailing ``Z``.

    Non-datetime values pass through unchanged (Pydantic has already validated
    the field type).
    """
    if isinstance(value, datetime):
        utc = value.astimezone(UTC).replace(tzinfo=None)
        text = utc.isoformat(timespec="milliseconds" if utc.microsecond else "seconds")
        return text + "Z"
    return value


def _ensure_dict(data: str | bytes | bytearray | dict[str, Any]) -> dict[str, Any]:
    if isinstance(data, dict):
        return data
    try:
        parsed = json.loads(data)
    except (json.JSONDecodeError, TypeError) as exc:
        raise CodecError(f"input is not valid JSON: {exc}") from exc
    if not isinstance(parsed, dict):
        raise CodecError("envelope JSON must be an object")
    return parsed


def deserialize(data: str | bytes | bytearray | dict[str, Any]) -> TypedEnvelope[BaseModel]:
    """Deserialize wire JSON (or a dict) into a :class:`TypedEnvelope`.

    Raises:
        CodecError: malformed JSON, missing/extra envelope fields.
        SchemaVersionError: ``schemaVersion`` major not supported (>= 2).
        UnknownEventTypeError: ``type`` resolves to no payload class.
        pydantic.ValidationError: payload fails its schema (required field,
            enum, ``managedObjectId`` pattern, etc.).
    """
    raw = _ensure_dict(data)

    # 1. Validate envelope shape (required envelope fields, no extras).
    try:
        envelope = _GeneratedEnvelope.model_validate(raw)
    except ValidationError as exc:
        raise CodecError(f"invalid envelope: {exc}") from exc

    # 2. schemaVersion policy (reject major >= 2).
    check_schema_version(envelope.schemaVersion)

    # 3. Resolve payload class by `type` (reject unknown type).
    payload_cls = resolve_payload_type(envelope.type.value)

    # 4. Validate + construct the typed payload.
    payload = payload_cls.model_validate(raw["payload"])

    return TypedEnvelope[BaseModel](
        eventId=str(envelope.eventId),
        type=envelope.type.value,
        schemaVersion=envelope.schemaVersion,
        occurredAt=envelope.occurredAt,
        source=envelope.source,
        traceId=envelope.traceId,
        payload=payload,
    )


def serialize(envelope: TypedEnvelope[Any]) -> str:
    """Serialize a :class:`TypedEnvelope` to canonical wire JSON."""
    return envelope.to_json()
