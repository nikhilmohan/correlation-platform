"""Uvicorn bootstrap for the HTTP surface (process boundary — integration-only).

Excluded from unit coverage: it starts a real uvicorn server. Unit tests exercise the app via
``fastapi.testclient`` against ``create_app``. ``run_server`` is used by ``main`` when serving the
HTTP surface alongside a run.
"""

from __future__ import annotations

import threading

import uvicorn

from simulator.api.app import RunState, create_app


def run_server(state: RunState, port: int) -> None:  # pragma: no cover
    app = create_app(state)
    uvicorn.run(app, host="0.0.0.0", port=port, log_config=None)


def start_in_thread(state: RunState, port: int) -> threading.Thread:  # pragma: no cover
    thread = threading.Thread(target=run_server, args=(state, port), daemon=True)
    thread.start()
    return thread
