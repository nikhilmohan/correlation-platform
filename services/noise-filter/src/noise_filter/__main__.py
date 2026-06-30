"""``python -m noise_filter`` entrypoint.

Subcommands:
  (default)  apply migrations + run the consume loop + HTTP server.
  migrate    apply yoyo migrations only (idempotent) then exit.
"""

from __future__ import annotations

import sys

from .config import Settings
from .migrate import apply_migrations


def main() -> None:  # pragma: no cover - process entrypoint
    if len(sys.argv) > 1 and sys.argv[1] == "migrate":
        settings = Settings()
        if not settings.noise_filter_db_url:
            raise SystemExit("NOISE_FILTER_DB_URL is required for migrate")
        apply_migrations(settings.noise_filter_db_url)
        return
    from .app import main as run_main

    run_main()


if __name__ == "__main__":  # pragma: no cover
    main()
