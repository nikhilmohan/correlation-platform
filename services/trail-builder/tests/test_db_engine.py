"""IO-layer unit tests for the engine factory + schema bootstrap (``db/engine.py``).

These cover the test/local path: a SQLite engine with the ``trailbuilder`` schema
ATTACHed as an in-memory database so schema-qualified table names resolve exactly as
on PostgreSQL, and ``create_all_in_schema`` building the tables from the pinned metadata.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import insert, inspect, select

from trailbuilder.db import tables
from trailbuilder.db.engine import create_all_in_schema, make_engine
from trailbuilder.db.metadata import SCHEMA


def test_make_engine_sqlite_attaches_schema_and_creates_tables() -> None:
    """SQLite engine has the trailbuilder schema attached and all tables created."""
    eng = make_engine("sqlite://")
    create_all_in_schema(eng)
    names = set(inspect(eng).get_table_names(schema=SCHEMA))
    assert {"trail", "trail_member", "processed_event"} <= names


def test_schema_qualified_round_trip_on_sqlite() -> None:
    """A schema-qualified insert/select round-trips against the attached schema."""
    eng = make_engine("sqlite://")
    create_all_in_schema(eng)
    with eng.begin() as conn:
        conn.execute(
            insert(tables.processed_event).values(
                event_id="e1",
                snapshot_id="s1",
                domain="core-ip",
                processed_at=datetime.now(UTC),
            )
        )
    with eng.connect() as conn:
        row = conn.execute(
            select(tables.processed_event.c.event_id).where(
                tables.processed_event.c.event_id == "e1"
            )
        ).first()
    assert row is not None and row.event_id == "e1"


def test_static_pool_shares_attached_schema_across_connections() -> None:
    """The StaticPool keeps the attached in-memory schema alive across connections."""
    eng = make_engine("sqlite://")
    create_all_in_schema(eng)
    # A fresh connection from the same engine still sees the attached schema's tables.
    with eng.connect() as conn:
        result = conn.execute(select(tables.trail.c.trail_id)).all()
    assert result == []
