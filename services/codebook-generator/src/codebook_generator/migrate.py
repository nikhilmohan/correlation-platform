"""Schema migration runner (yoyo-migrations).

Applies the versioned plain-SQL migrations packaged at ``codebook_generator/migrations/``
against ``DATABASE_URL`` at container startup, BEFORE the consumer/API start. Idempotent
(yoyo's applied-migration ledger + ``IF NOT EXISTS`` DDL). Startup aborts (non-zero exit /
raised error) if migrations fail (spec criteria 27-29).

Resolution is **install-robust**: the SQL ships as package data inside the
``codebook_generator`` package, so it is located the same way whether the service runs from a
source checkout, an installed wheel, or the Docker image — never by walking ``__file__``
parents (which breaks under a non-editable / site-packages install). An optional
``CODEBOOK_MIGRATIONS_DIR`` env var overrides the location (operator escape hatch).
"""

from __future__ import annotations

import os
from importlib.resources import files
from pathlib import Path

from yoyo import get_backend, read_migrations

from .logging_config import get_logger

logger = get_logger(__name__)

#: Env var to override the migrations directory. When unset, the migrations packaged inside
#: the installed ``codebook_generator`` package are used.
MIGRATIONS_DIR_ENV = "CODEBOOK_MIGRATIONS_DIR"


def migrations_dir() -> Path:
    """Absolute path to the migrations directory, resolved install-robustly.

    Order of resolution:

    1. ``$CODEBOOK_MIGRATIONS_DIR`` if set (operator override).
    2. The ``migrations/`` package-data directory shipped *inside* the installed
       ``codebook_generator`` package (via :func:`importlib.resources.files`). This resolves
       identically for a source checkout, a wheel install, and the Docker image, because the
       SQL travels with the package rather than living at a fixed source-tree offset.
    """
    override = os.environ.get(MIGRATIONS_DIR_ENV)
    if override:
        return Path(override).resolve()
    return Path(str(files("codebook_generator") / "migrations")).resolve()


def apply_migrations(database_url: str, path: Path | None = None) -> None:
    """Apply all pending migrations against ``database_url``.

    Args:
        database_url: a yoyo/DB-API connection string (postgres).
        path: migrations directory; defaults to the packaged ``migrations/``.

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
