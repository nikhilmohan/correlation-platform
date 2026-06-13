"""Shared pytest fixtures.

The Codebook Store is exercised against an in-memory SQLite database with a ``codebook``
schema attached (so the schema-qualified Table metadata resolves identically to Postgres),
and the partial-unique one-active-codebook index created portably. Outbound integration
points are backed by domain-parameterized respx mocks generated from the collaborators'
frozen producer shapes.
"""

from __future__ import annotations

from collections.abc import Iterator

import httpx
import pytest
from sqlalchemy import create_engine, event, text
from sqlalchemy.engine import Engine
from sqlalchemy.pool import StaticPool

from codebook_generator.store import CodebookStore, _metadata


@pytest.fixture
def engine() -> Iterator[Engine]:
    """In-memory SQLite engine with a ``codebook`` schema + service tables/indexes."""
    eng = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
        future=True,
    )

    @event.listens_for(eng, "connect")
    def _attach_schema(dbapi_conn, _record):  # noqa: ANN001
        # Attach an in-memory DB under the name `codebook` so `codebook.<table>` resolves.
        dbapi_conn.execute("ATTACH DATABASE ':memory:' AS codebook")

    _metadata.create_all(eng)
    # Partial-unique index enforcing exactly one active codebook per (domain, snapshot_id).
    with eng.begin() as conn:
        conn.execute(
            text(
                "CREATE UNIQUE INDEX IF NOT EXISTS codebook.uq_codebooks_one_active "
                "ON codebooks (domain, snapshot_id) WHERE active = 1"
            )
        )
    yield eng
    eng.dispose()


@pytest.fixture
def store(engine: Engine) -> CodebookStore:
    """A Codebook Store backed by the in-memory engine."""
    return CodebookStore(engine)


class FakeProducer:
    """In-memory MessageProducer capturing produced messages per topic."""

    def __init__(self) -> None:
        self.messages: list[tuple[str, bytes, bytes | None]] = []
        self.fail_topics: set[str] = set()

    def produce(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        if topic in self.fail_topics:
            raise RuntimeError(f"simulated produce failure on {topic}")
        self.messages.append((topic, value, key))

    def flush(self, timeout: float | None = None) -> int:
        return 0

    def topic_messages(self, topic: str) -> list[bytes]:
        return [v for (t, v, _k) in self.messages if t == topic]


@pytest.fixture
def fake_producer() -> FakeProducer:
    return FakeProducer()
