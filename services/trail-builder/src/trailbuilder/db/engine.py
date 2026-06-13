"""Engine factory + schema bootstrap for tests/local without Alembic.

Production startup runs ``alembic upgrade head`` (see migrations/). For unit
tests against SQLite (or a fresh local DB) ``create_all_in_schema`` builds the
schema + tables directly from the pinned metadata.
"""

from __future__ import annotations

from sqlalchemy import Engine, create_engine, event, text
from sqlalchemy.pool import StaticPool

from . import tables  # noqa: F401  (registers the tables on `metadata`)
from .metadata import SCHEMA, metadata


def make_engine(database_url: str) -> Engine:
    """Create a SQLAlchemy engine for ``database_url``.

    For SQLite (tests) the ``trailbuilder`` schema is mapped to an attached
    in-memory database via ``ATTACH``, so the schema-qualified table names
    resolve identically to PostgreSQL. A ``StaticPool`` keeps a single shared
    connection so the attached in-memory schema persists across operations.
    """
    if database_url.startswith("sqlite"):
        engine = create_engine(
            database_url,
            future=True,
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        _attach_sqlite_schema(engine)
        return engine
    return create_engine(database_url, future=True)


def _attach_sqlite_schema(engine: Engine) -> None:
    """Attach an in-memory schema database named ``trailbuilder`` to SQLite.

    Also enables ``PRAGMA foreign_keys=ON`` so the ``trail_member`` ON DELETE
    CASCADE fires when a trail is superseded — matching PostgreSQL semantics (SQLite
    leaves FK enforcement off by default, which would otherwise orphan members and
    break the supersede-on-rebuild idempotency path).
    """

    @event.listens_for(engine, "connect")
    def _attach(dbapi_conn, _rec):  # type: ignore[no-untyped-def]
        cur = dbapi_conn.cursor()
        cur.execute(f"ATTACH DATABASE ':memory:' AS {SCHEMA}")
        cur.execute("PRAGMA foreign_keys=ON")
        cur.close()


def create_all_in_schema(engine: Engine) -> None:
    """Create the schema (PostgreSQL) and all tables from the pinned metadata."""
    if engine.dialect.name == "postgresql":
        with engine.begin() as conn:
            conn.execute(text(f"CREATE SCHEMA IF NOT EXISTS {SCHEMA}"))
    metadata.create_all(engine)
