"""Schema migration runner (yoyo, DA-15).

Versioned SQL migrations ship inside the package (``noise_filter/migrations/*.sql``) and are
applied at startup. Idempotent (``CREATE ... IF NOT EXISTS`` + yoyo's own version table scoped
to the ``noise_filter`` schema) — re-running is a no-op.
"""

from __future__ import annotations

import os
from importlib import resources
from pathlib import Path

from .logging_setup import get_logger

log = get_logger(__name__)


def migrations_dir() -> Path:
    """Resolve the directory holding the packaged ``*.sql`` migrations."""
    override = os.environ.get("NOISE_FILTER_MIGRATIONS_DIR")
    if override:
        return Path(override)
    return Path(str(resources.files("noise_filter").joinpath("migrations")))


def _yoyo_db_url(db_url: str) -> str:
    """yoyo needs a sync driver URL. Map ``postgresql+asyncpg://`` / ``postgres://`` to
    ``postgresql://`` (yoyo uses its bundled backend)."""
    url = db_url
    if url.startswith("postgresql+asyncpg://"):
        url = "postgresql://" + url[len("postgresql+asyncpg://") :]
    elif url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://") :]
    return url


def apply_migrations(db_url: str) -> None:
    """Apply all pending migrations to ``db_url`` (idempotent)."""
    from yoyo import get_backend, read_migrations

    backend = get_backend(_yoyo_db_url(db_url))
    migrations = read_migrations(str(migrations_dir()))
    with backend.lock():
        backend.apply_migrations(backend.to_apply(migrations))
    log.info("migrations_applied", count=len(migrations), dir=str(migrations_dir()))
