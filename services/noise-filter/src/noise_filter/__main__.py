"""``python -m noise_filter`` entrypoint.

Subcommands:
  (default)  apply migrations + run the consume loop + HTTP server.
  migrate    apply schema migrations only (idempotent, over asyncpg) then exit.
"""

from __future__ import annotations

import asyncio
import sys

from .config import Settings
from .migrate import apply_migrations_asyncpg


async def _migrate(db_url: str) -> None:
    import asyncpg

    from .app import _asyncpg_url

    pool = await asyncpg.create_pool(_asyncpg_url(db_url))
    try:
        await apply_migrations_asyncpg(pool)
    finally:
        await pool.close()


def main() -> None:  # pragma: no cover - process entrypoint
    if len(sys.argv) > 1 and sys.argv[1] == "migrate":
        settings = Settings()
        if not settings.noise_filter_db_url:
            raise SystemExit("NOISE_FILTER_DB_URL is required for migrate")
        asyncio.run(_migrate(settings.noise_filter_db_url))
        return
    from .app import main as run_main

    run_main()


if __name__ == "__main__":  # pragma: no cover
    main()
