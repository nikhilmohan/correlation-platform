"""Schema-migration tests (yoyo-migrations).

Two layers:

1. **Literal DDL** — the checked-in ``migrations/0001_codebook_schema.sql`` carries the exact
   Postgres DDL the spec requires: ``CREATE SCHEMA IF NOT EXISTS codebook`` (nothing lands in
   ``public``), the schema-qualified ``codebook.codebooks`` / ``codebook.scenarios`` /
   ``codebook.processed_events`` tables, and the partial-unique one-active index. This is the
   authoritative DDL applied against Postgres at container startup.

2. **yoyo apply mechanism** — yoyo discovers the migration, applies it through its backend, and
   records it in the applied-migration ledger so a re-run is a no-op (idempotency). Postgres is
   not available in the unit environment, so the apply path is proven against a yoyo SQLite
   backend using a dialect-translated copy of the same migration (same statements, SQLite
   syntax) — exercising :func:`codebook_generator.migrate.apply_migrations`'s discovery + apply
   + ledger contract without requiring a live Postgres.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from yoyo import get_backend, read_migrations

from codebook_generator.migrate import apply_migrations, migrations_dir

_MIGRATIONS = Path(__file__).resolve().parents[1] / "migrations"
_SQL = (_MIGRATIONS / "0001_codebook_schema.sql").read_text()


# --------------------------------------------------------------------------- #
# 1. Literal DDL assertions (the authoritative Postgres migration content).   #
# --------------------------------------------------------------------------- #
def test_migration_creates_codebook_schema_idempotently() -> None:
    """The migration creates the owned ``codebook`` schema (nothing in ``public``)."""
    assert "CREATE SCHEMA IF NOT EXISTS codebook;" in _SQL


def test_migration_tables_are_schema_qualified() -> None:
    """Every table is created under the ``codebook`` schema."""
    assert "CREATE TABLE IF NOT EXISTS codebook.codebooks" in _SQL
    assert "CREATE TABLE IF NOT EXISTS codebook.scenarios" in _SQL
    assert "CREATE TABLE IF NOT EXISTS codebook.processed_events" in _SQL


def test_migration_declares_first_class_domain_and_active_columns() -> None:
    """``domain`` is NOT NULL (first-class) and ``active`` defaults true on codebooks."""
    assert "domain             text        NOT NULL" in _SQL
    assert "active             boolean     NOT NULL DEFAULT true" in _SQL


def test_migration_declares_one_active_partial_unique_index() -> None:
    """The one-active-per-(domain, snapshot_id) partial-unique index is present."""
    assert "CREATE UNIQUE INDEX IF NOT EXISTS uq_codebooks_one_active" in _SQL
    assert "ON codebook.codebooks (domain, snapshot_id)" in _SQL
    assert "WHERE active = true" in _SQL


def test_migration_scenarios_use_jsonb_symptoms_and_text_array_trails() -> None:
    """Scenarios persist ordered symptoms as jsonb and trail tags as a text[] array."""
    assert "predicted_symptoms      jsonb  NOT NULL" in _SQL
    assert "trail_ids               text[] NOT NULL" in _SQL


def test_migrations_dir_resolves_to_service_migrations() -> None:
    """The runtime migrations_dir() points at the service's migrations/ directory."""
    assert migrations_dir() == _MIGRATIONS


# --------------------------------------------------------------------------- #
# 2. yoyo apply mechanism + ledger (idempotency) against a SQLite backend.    #
# --------------------------------------------------------------------------- #
# SQLite-dialect translation of the same migration statements (CREATE SCHEMA and
# jsonb/text[] have no SQLite equivalents; the apply + ledger contract is identical).
_SQLITE_MIGRATION = """\
CREATE TABLE IF NOT EXISTS codebooks (
    codebook_id text PRIMARY KEY,
    snapshot_id text NOT NULL,
    domain text NOT NULL,
    active integer NOT NULL DEFAULT 1,
    scenario_count integer NOT NULL,
    knowledge_version text,
    compiled_at text NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_codebooks_one_active
    ON codebooks (domain, snapshot_id) WHERE active = 1;
CREATE TABLE IF NOT EXISTS scenarios (
    scenario_id text PRIMARY KEY,
    codebook_id text NOT NULL,
    fault_origin_object_id text NOT NULL,
    fault_origin_type text NOT NULL,
    predicted_symptoms text NOT NULL,
    trail_ids text NOT NULL
);
CREATE TABLE IF NOT EXISTS processed_events (
    event_id text PRIMARY KEY,
    codebook_id text,
    processed_at text NOT NULL
);
"""


@pytest.fixture
def sqlite_migrations(tmp_path: Path) -> Path:
    """A migrations dir with one SQLite-dialect migration for the apply-mechanism test."""
    (tmp_path / "0001_codebook_schema.sql").write_text(_SQLITE_MIGRATION)
    return tmp_path


def test_yoyo_discovers_the_checked_in_migration() -> None:
    """yoyo reads exactly the one versioned migration the service ships."""
    migrations = list(read_migrations(str(_MIGRATIONS)))
    assert [m.id for m in migrations] == ["0001_codebook_schema"]


def test_apply_migrations_applies_and_creates_tables(
    sqlite_migrations: Path, tmp_path: Path
) -> None:
    """apply_migrations runs the DDL through the yoyo backend and creates the tables."""
    db = tmp_path / "cb.db"
    url = f"sqlite:///{db}"
    apply_migrations(url, path=sqlite_migrations)

    backend = get_backend(url)
    cur = backend.cursor()
    cur.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
    tables = {row[0] for row in cur.fetchall()}
    assert {"codebooks", "scenarios", "processed_events"} <= tables


def test_apply_migrations_is_idempotent_via_ledger(sqlite_migrations: Path, tmp_path: Path) -> None:
    """Re-running apply_migrations is a no-op (yoyo applied-migration ledger)."""
    db = tmp_path / "cb.db"
    url = f"sqlite:///{db}"
    apply_migrations(url, path=sqlite_migrations)
    # Second run must not error (ledger marks 0001 applied).
    apply_migrations(url, path=sqlite_migrations)

    backend = get_backend(url)
    migrations = read_migrations(str(sqlite_migrations))
    # Nothing left to apply on the second pass.
    assert list(backend.to_apply(migrations)) == []
