"""Shared pytest fixtures: load the golden JSON fixtures owned by the schema.

The fixtures under ``libs/event-model/schema/fixtures/`` are the cross-binding
contract anchor — the *same* files are read by the Java binding's tests. Tests
here read them as the canonical wire bytes.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

# tests/ -> python/ -> event-model/ ; schema is a sibling of python/
SCHEMA_DIR = Path(__file__).resolve().parent.parent.parent / "schema"
FIXTURES_DIR = SCHEMA_DIR / "fixtures"

#: The canonical payload type strings (= fixture stems = `type` values).
PAYLOAD_TYPES: list[str] = [
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
    "AlarmStatusChange",
]


def load_fixture_text(payload_type: str) -> str:
    """Return the raw JSON text of the golden fixture for ``payload_type``."""
    return (FIXTURES_DIR / f"{payload_type}.json").read_text(encoding="utf-8")


def load_fixture_dict(payload_type: str) -> dict:
    """Return the golden fixture for ``payload_type`` as a dict."""
    return json.loads(load_fixture_text(payload_type))


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES_DIR


@pytest.fixture
def schema_dir() -> Path:
    return SCHEMA_DIR
