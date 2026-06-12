"""Discriminator registry: ``type`` string -> payload class, 1:1 (criterion 5).

This is a thin, schema-agnostic helper. It references the generated payload
*classes* (whose names equal the wire ``type`` strings) but no field lists, so
it does not break the single-source-of-truth guarantee.
"""

from __future__ import annotations

from pydantic import BaseModel

from ._generated import (
    AlarmEvent,
    AlarmStatusChange,
    CodebookGeneratedEvent,
    CorrelationResultEvent,
    KnowledgeUpdatedEvent,
    PatternApprovedEvent,
    PatternDiscoveredEvent,
    PatternMinedEvent,
    TopologyChangedEvent,
    TrailsBuiltEvent,
    TransactionEvent,
)

#: Maps each canonical ``type`` discriminator string to its payload class.
TYPE_REGISTRY: dict[str, type[BaseModel]] = {
    "AlarmEvent": AlarmEvent,
    "TopologyChangedEvent": TopologyChangedEvent,
    "TrailsBuiltEvent": TrailsBuiltEvent,
    "CodebookGeneratedEvent": CodebookGeneratedEvent,
    "TransactionEvent": TransactionEvent,
    "PatternMinedEvent": PatternMinedEvent,
    "PatternDiscoveredEvent": PatternDiscoveredEvent,
    "PatternApprovedEvent": PatternApprovedEvent,
    "CorrelationResultEvent": CorrelationResultEvent,
    "KnowledgeUpdatedEvent": KnowledgeUpdatedEvent,
    "AlarmStatusChange": AlarmStatusChange,
}


class UnknownEventTypeError(ValueError):
    """Raised when a ``type`` string resolves to no payload class."""


def resolve_payload_type(event_type: str) -> type[BaseModel]:
    """Return the payload class for ``event_type``.

    Raises:
        UnknownEventTypeError: if ``event_type`` is not one of the
            registered discriminator strings.
    """
    try:
        return TYPE_REGISTRY[event_type]
    except KeyError:
        raise UnknownEventTypeError(
            f"unknown event type {event_type!r}: " f"expected one of {sorted(TYPE_REGISTRY)}"
        ) from None
