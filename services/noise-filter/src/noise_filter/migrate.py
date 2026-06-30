"""Schema migration runner (DA-15).

Versioned SQL migrations ship inside the package (``noise_filter/migrations/*.sql``) and are
applied at startup. All migration SQL is idempotent (``CREATE ... IF NOT EXISTS``) so re-running is
a no-op.

Two runners are provided:

* :func:`apply_migrations_asyncpg` — applies the SQL over the live **asyncpg** pool the service
  already owns. This is what the running service uses (B2): it needs NO extra sync driver, keeping
  the dependency set permissive-only (asyncpg is Apache-2.0; psycopg2 — yoyo's postgres backend —
  is LGPL and is deliberately NOT a dependency here).
* :func:`apply_migrations` — the yoyo-based sync runner, retained for CI/local tooling that has a
  sync driver available (e.g. SQLite); NOT on the service's runtime path.
"""

from __future__ import annotations

import os
from importlib import resources
from pathlib import Path
from typing import Any

from .logging_setup import get_logger

log = get_logger(__name__)


def migrations_dir() -> Path:
    """Resolve the directory holding the packaged ``*.sql`` migrations."""
    override = os.environ.get("NOISE_FILTER_MIGRATIONS_DIR")
    if override:
        return Path(override)
    return Path(str(resources.files("noise_filter").joinpath("migrations")))


def _ordered_migration_sql() -> list[tuple[str, str]]:
    """Return ``(filename, sql)`` for each packaged migration, in version order."""
    files = sorted(migrations_dir().glob("*.sql"))
    return [(f.name, f.read_text()) for f in files]


async def apply_migrations_asyncpg(pool: Any) -> None:
    """Apply the packaged migrations over a live asyncpg ``pool`` (idempotent, B2).

    Each file's SQL is run inside one transaction. All statements use ``IF NOT EXISTS`` so applying
    the same migration repeatedly (every startup) is a no-op — no version table needed.
    """
    migrations = _ordered_migration_sql()
    async with pool.acquire() as conn:
        for name, sql in migrations:
            async with conn.transaction():
                await conn.execute(sql)
            log.info("migration_applied", migration=name)
    log.info("migrations_applied_asyncpg", count=len(migrations))


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
