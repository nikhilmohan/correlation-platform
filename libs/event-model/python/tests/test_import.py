"""Criterion 18 (Python side): the package installs and imports cleanly."""

from __future__ import annotations


def test_package_imports() -> None:
    import acp_event_model  # noqa: F401

    assert hasattr(acp_event_model, "Envelope")
    assert hasattr(acp_event_model, "deserialize")
    assert hasattr(acp_event_model, "serialize")
    assert hasattr(acp_event_model, "ManagedObjectId")


def test_all_nine_payloads_exported() -> None:
    import acp_event_model as m

    for name in [
        "AlarmEvent",
        "TopologyChangedEvent",
        "TrailsBuiltEvent",
        "CodebookGeneratedEvent",
        "TransactionEvent",
        "PatternMinedEvent",
        "PatternDiscoveredEvent",
        "PatternApprovedEvent",
        "CorrelationResultEvent",
    ]:
        assert hasattr(m, name), f"missing export: {name}"
