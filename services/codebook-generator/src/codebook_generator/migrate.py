"""Schema migration runner (yoyo-migrations).

Applies the versioned plain-SQL migrations in ``migrations/`` against ``DATABASE_URL`` at
container startup, BEFORE the consumer/API start. Idempotent (yoyo's applied-migration
ledger + ``IF NOT EXISTS`` DDL). Startup aborts (non-zero exit / raised error) if migrations
fail (spec criteria 27-29).
"""

from __future__ import annotations

from pathlib import Path

from yoyo import get_backend, read_migrations

from .logging_config import get_logger

logger = get_logger(__name__)


def migrations_dir() -> Path:
    """Absolute path to the service's ``migrations/`` directory.

    ``migrate.py`` lives at ``<svc>/src/codebook_generator/migrate.py``, so the service root
    is ``parents[2]`` and the migrations live alongside it under ``<svc>/migrations``.
    """
    return Path(__file__).resolve().parents[2] / "migrations"


def apply_migrations(database_url: str, path: Path | None = None) -> None:
    """Apply all pending migrations against ``database_url``.

    Args:
        database_url: a yoyo/DB-API connection string (postgres).
        path: migrations directory; defaults to the service's ``migrations/``.

    Raises:
        Exception: propagated from yoyo when a migration fails (startup must abort).
    """
    directory = path or migrations_dir()
    logger.info("applying migrations from %s", directory)
    backend = get_backend(database_url)
    migrations = read_migrations(str(directory))
    with backend.lock():
        backend.apply_migrations(backend.to_apply(migrations))
    logger.info("migrations applied")
