"""acp-event-model — canonical event model (Pydantic v2 binding).

The single source of truth is the JSON Schema under ``libs/event-model/schema/``;
the model classes in :mod:`acp_event_model._generated` are produced from it by
``scripts/generate.py`` (never hand-authored). This package re-exports those
models plus the schema-agnostic helper layer: the codec, the schemaVersion
policy, the ``managedObjectId`` value type, and the ``type`` discriminator
registry.

Extensibility: payload models are open for subclassing in downstream services
(e.g. ``class EnrichedAlarmView(AlarmEvent): ...``) without editing this
library. Adding or removing a *wire* field is a contract change to the schema,
never a subclass.
"""

from __future__ import annotations

from ._generated import (
    AlarmEvent,
    CodebookGeneratedEvent,
    CorrelationResultEvent,
    Envelope,
    KnowledgeUpdatedEvent,
    PatternApprovedEvent,
    PatternDiscoveredEvent,
    PatternMinedEvent,
    Provenance,
    State,
    TopologyChangedEvent,
    TrailsBuiltEvent,
    TransactionEvent,
)
from .codec import CodecError, TypedEnvelope, deserialize, serialize
from .managed_object_id import (
    KNOWN_OBJECT_TYPES,
    ManagedObjectId,
    ManagedObjectIdError,
    is_valid,
    validate,
)
from .registry import TYPE_REGISTRY, UnknownEventTypeError, resolve_payload_type
from .version import SUPPORTED_MAJOR, SchemaVersionError, check_schema_version

__all__ = [
    # Envelope + payload models (generated from the schema)
    "Envelope",
    "AlarmEvent",
    "TopologyChangedEvent",
    "TrailsBuiltEvent",
    "CodebookGeneratedEvent",
    "TransactionEvent",
    "PatternMinedEvent",
    "PatternDiscoveredEvent",
    "PatternApprovedEvent",
    "CorrelationResultEvent",
    "KnowledgeUpdatedEvent",
    "Provenance",
    "State",
    # Codec
    "TypedEnvelope",
    "deserialize",
    "serialize",
    "CodecError",
    # schemaVersion policy
    "SUPPORTED_MAJOR",
    "SchemaVersionError",
    "check_schema_version",
    # managedObjectId
    "ManagedObjectId",
    "ManagedObjectIdError",
    "KNOWN_OBJECT_TYPES",
    "is_valid",
    "validate",
    # Discriminator registry
    "TYPE_REGISTRY",
    "UnknownEventTypeError",
    "resolve_payload_type",
]

__version__ = "0.1.0"
