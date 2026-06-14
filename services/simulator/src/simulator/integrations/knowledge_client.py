"""Knowledge Service scenario-config client — config-switchable local/real (optional).

``KNOWLEDGE_MODE=local`` (default) reads scenario/threshold config from local files; ``=real``
fetches from the Knowledge Service API. The Simulator does not author scenario config — it only
consumes it. For the MVP the local mode is the default; the real client is built against
Knowledge's published OpenAPI, never its source.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import httpx


class LocalKnowledgeClient:
    """Read scenario/threshold config from a local file (default/mock)."""

    def __init__(self, config_path: str | Path | None = None) -> None:
        self._config_path = Path(config_path) if config_path else None

    def scenario_config(self) -> dict[str, Any]:
        if self._config_path and self._config_path.exists():
            return json.loads(self._config_path.read_text())
        return {}


class HttpKnowledgeClient:
    """Fetch scenario config from the real Knowledge Service (integration)."""

    def __init__(self, base_url: str, *, client: httpx.Client | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = client or httpx.Client(timeout=15.0)

    def scenario_config(self) -> dict[str, Any]:
        resp = self._client.get(f"{self._base_url}/knowledge/scenario-config")
        resp.raise_for_status()
        return resp.json()


def make_client(
    mode: str, base_url: str | None, config_path: str | Path | None = None
) -> LocalKnowledgeClient | HttpKnowledgeClient:
    if mode == "real":
        if not base_url:
            raise ValueError("KNOWLEDGE_API_BASE_URL required when KNOWLEDGE_MODE=real")
        return HttpKnowledgeClient(base_url)
    return LocalKnowledgeClient(config_path)
