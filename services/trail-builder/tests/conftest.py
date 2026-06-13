"""Shared pytest fixtures + a CI bootstrap for the local event-model binding.

CI installs only ``ruff black pytest pytest-cov`` for a Python service, so this
conftest bootstraps the service + its sibling ``acp-event-model`` into the
environment on first import when they are not already present. Locally (after
``pip install -e .[dev]``) the bootstrap is a no-op.
"""

from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

_HERE = pathlib.Path(__file__).resolve()
_SERVICE_ROOT = _HERE.parent.parent
_REPO_ROOT = _SERVICE_ROOT.parent.parent
_EVENT_MODEL = _REPO_ROOT / "libs" / "event-model" / "python"


def _ensure(module: str, install_target: str) -> None:
    if importlib.util.find_spec(module) is None:
        subprocess.run(
            [sys.executable, "-m", "pip", "install", "-q", "-e", install_target], check=True
        )


# Make `src/` importable even without an editable install (CI scaffold path).
_SRC = _SERVICE_ROOT / "src"
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

_ensure("acp_event_model", str(_EVENT_MODEL))
for _runtime_dep in ("fastapi", "httpx", "respx", "networkx", "sqlalchemy", "prometheus_client"):
    _ensure(_runtime_dep, str(_SERVICE_ROOT))

import pytest  # noqa: E402

from trailbuilder.config import Settings, get_settings  # noqa: E402
from trailbuilder.db.engine import create_all_in_schema, make_engine  # noqa: E402


class FakeProducer:
    """In-memory Kafka producer capturing produced messages per topic."""

    def __init__(self) -> None:
        self.messages: list[tuple[str, bytes]] = []

    def produce(self, topic: str, value: bytes, key: bytes | None = None) -> None:
        self.messages.append((topic, value))

    def flush(self, timeout: float = 5.0) -> int:
        return 0

    def for_topic(self, topic: str) -> list[bytes]:
        return [v for t, v in self.messages if t == topic]


@pytest.fixture
def settings() -> Settings:
    return get_settings(
        TOPOLOGY_SERVICE_BASE_URL="http://topology.test",
        KNOWLEDGE_SERVICE_BASE_URL="http://knowledge.test",
        TOPOLOGY_SERVICE_MODE="mock",
        KNOWLEDGE_SERVICE_MODE="mock",
        DATABASE_URL="sqlite://",
        TRAIL_RETENTION_SNAPSHOTS=2,
        DEFAULT_DOMAIN="core-ip",
        HTTP_RETRY_MAX=1,
    )


@pytest.fixture
def engine(settings: Settings):
    eng = make_engine(settings.database_url)
    create_all_in_schema(eng)
    try:
        yield eng
    finally:
        # Dispose the connection pool so the in-memory SQLite connection is
        # closed deterministically (silences ResourceWarnings in the suite).
        eng.dispose()


@pytest.fixture
def producer() -> FakeProducer:
    return FakeProducer()
