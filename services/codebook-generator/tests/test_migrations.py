"""Schema-migration tests (yoyo-migrations).

Two layers:

1. **Literal DDL** — the packaged ``codebook_generator/migrations/0001_codebook_schema.sql``
   carries the exact Postgres DDL the spec requires: ``CREATE SCHEMA IF NOT EXISTS codebook``
   (nothing lands in ``public``), the schema-qualified ``codebook.codebooks`` /
   ``codebook.scenarios`` / ``codebook.processed_events`` tables, and the partial-unique
   one-active index. This is the authoritative DDL applied against Postgres at container
   startup. The SQL ships as **package data** inside the installed ``codebook_generator``
   package, so it is read here via the same install-robust resolution
   (:func:`codebook_generator.migrate.migrations_dir`) the runtime uses — passing identically
   for a source checkout, a non-editable wheel install, and the Docker image.

2. **yoyo apply mechanism** — yoyo discovers the migration, applies it through its backend, and
   records it in the applied-migration ledger so a re-run is a no-op (idempotency). Postgres is
   not available in the unit environment, so the apply path is proven against a yoyo SQLite
   backend using a dialect-translated copy of the same migration (same statements, SQLite
   syntax) — exercising :func:`codebook_generator.migrate.apply_migrations`'s discovery + apply
   + ledger contract without requiring a live Postgres.
"""

from __future__ import annotations

import os
from importlib.resources import files
from pathlib import Path

import pytest
from yoyo import get_backend, read_migrations

from codebook_generator import migrate
from codebook_generator.migrate import (
    MIGRATIONS_DIR_ENV,
    apply_migrations,
    build_backend,
    migrations_dir,
    yoyo_url,
)

# Resolve the migrations the same install-robust way the runtime does (importlib.resources over
# the installed package), so these tests pass against a non-editable wheel install where the SQL
# lives under site-packages — NOT at a fixed source-tree offset.
_MIGRATIONS = Path(str(files("codebook_generator") / "migrations")).resolve()
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


def test_migrations_dir_resolves_to_packaged_migrations() -> None:
    """migrations_dir() resolves to the packaged migrations dir for ANY install layout.

    Resolution is via importlib.resources over the installed package, so it holds for an
    editable checkout, a non-editable wheel install (site-packages), and the Docker image —
    and the directory actually contains the shipped SQL.
    """
    resolved = migrations_dir()
    assert resolved == _MIGRATIONS
    assert (resolved / "0001_codebook_schema.sql").is_file()


def test_migrations_dir_honours_env_override(tmp_path: Path) -> None:
    """$CODEBOOK_MIGRATIONS_DIR overrides the packaged location (operator escape hatch)."""
    prior = os.environ.get(MIGRATIONS_DIR_ENV)
    os.environ[MIGRATIONS_DIR_ENV] = str(tmp_path)
    try:
        assert migrations_dir() == tmp_path.resolve()
    finally:
        if prior is None:
            os.environ.pop(MIGRATIONS_DIR_ENV, None)
        else:
            os.environ[MIGRATIONS_DIR_ENV] = prior


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


# --------------------------------------------------------------------------- #
# 3. yoyo URL normalization (the single DATABASE_URL serves two libraries).    #
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    ("database_url", "expected"),
    [
        # The documented prod form: SQLAlchemy's +pg8000 driver suffix must be stripped so
        # yoyo's get_backend accepts the scheme (the container-startup bug).
        (
            "postgresql+pg8000://correlation:correlation@postgres:5432/correlation",
            "postgresql://correlation:correlation@postgres:5432/correlation",
        ),
        ("postgresql+psycopg2://u:p@h:5432/db", "postgresql://u:p@h:5432/db"),
        # Plain DB-API schemes pass through unchanged.
        ("postgresql://u:p@h:5432/db", "postgresql://u:p@h:5432/db"),
        ("sqlite:///cb.db", "sqlite:///cb.db"),
        ("sqlite://", "sqlite://"),
    ],
)
def test_yoyo_url_strips_sqlalchemy_driver_suffix(database_url: str, expected: str) -> None:
    """yoyo_url removes the SQLAlchemy ``+<driver>`` scheme suffix yoyo cannot parse.

    Regression for the container-only failure: the same ``DATABASE_URL`` feeds SQLAlchemy
    (which needs ``postgresql+pg8000://``) and yoyo (which only understands ``postgresql://``).
    """
    assert yoyo_url(database_url) == expected


def test_yoyo_url_only_rewrites_the_scheme() -> None:
    """A ``+`` elsewhere in the URL (e.g. a password) is left untouched."""
    url = "postgresql+pg8000://user:p+ss@host:5432/db"
    assert yoyo_url(url) == "postgresql://user:p+ss@host:5432/db"


# --------------------------------------------------------------------------- #
# 4. Backend selection: pg8000 URLs use the pg8000-backed Postgres backend.    #
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize(
    "database_url",
    [
        "postgresql+pg8000://correlation:correlation@postgres:5432/correlation",
        "postgres+pg8000://u:p@h:5432/db",
        "pg8000://u:p@h:5432/db",
    ],
)
def test_build_backend_uses_pg8000_for_pg8000_schemes(
    database_url: str, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A ``+pg8000`` (or ``pg8000``) scheme builds the pg8000 backend, never yoyo's psycopg one.

    The pg8000 backend connects in its constructor, so the driver connect + init_database are
    stubbed: the assertion is purely on *which* backend class is selected for the scheme.
    """
    seen: dict[str, object] = {}

    class _FakeBackend:
        def __init__(self, dburi: object, table: str) -> None:
            seen["dburi"] = dburi
            seen["table"] = table

        def init_database(self) -> None:
            seen["init_database"] = True

    def _fail_get_backend(_url: str) -> object:  # pragma: no cover - must not be reached
        raise AssertionError("pg8000 scheme must not fall through to yoyo.get_backend")

    monkeypatch.setattr(migrate, "_Pg8000Backend", _FakeBackend)
    monkeypatch.setattr(migrate, "get_backend", _fail_get_backend)

    backend = build_backend(database_url)

    assert isinstance(backend, _FakeBackend)
    assert seen["init_database"] is True
    assert seen["table"] == "_yoyo_migration"


def test_build_backend_delegates_other_schemes_to_yoyo(monkeypatch: pytest.MonkeyPatch) -> None:
    """Non-pg8000 schemes (e.g. the SQLite test backend) go through yoyo's get_backend."""
    captured: dict[str, str] = {}
    sentinel = object()

    def _fake_get_backend(url: str) -> object:
        captured["url"] = url
        return sentinel

    monkeypatch.setattr(migrate, "get_backend", _fake_get_backend)

    # A SQLAlchemy +driver suffix on a non-pg8000 scheme is normalized before get_backend.
    result = build_backend("postgresql+psycopg2://u:p@h:5432/db")

    assert result is sentinel
    assert captured["url"] == "postgresql://u:p@h:5432/db"
