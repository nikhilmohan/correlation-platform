"""``python -m pattern_miner`` entrypoint — run the consume->mine->produce loop + HTTP server."""

from __future__ import annotations

from .app import main

if __name__ == "__main__":  # pragma: no cover
    main()
