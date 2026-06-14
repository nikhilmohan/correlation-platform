"""Knowledge Service scenario-config client tests (config-switchable local/real).

The Simulator only *consumes* scenario config; it never authors it. ``KNOWLEDGE_MODE=local``
(default) reads from a local file; ``=real`` fetches from the Knowledge Service via httpx (built
against Knowledge's published OpenAPI, mocked here as transport).
"""

from __future__ import annotations

import json

import httpx
import pytest

from simulator.integrations import knowledge_client
from simulator.integrations.knowledge_client import (
    HttpKnowledgeClient,
    LocalKnowledgeClient,
)


def test_local_mode_is_default(tmp_path) -> None:
    client = knowledge_client.make_client("local", None)
    assert isinstance(client, LocalKnowledgeClient)


def test_local_client_reads_config_file(tmp_path) -> None:
    cfg = tmp_path / "scenario-config.json"
    cfg.write_text(json.dumps({"noiseRate": 0.1, "scenarios": ["fiber-cut"]}))
    client = LocalKnowledgeClient(cfg)
    out = client.scenario_config()
    assert out["noiseRate"] == 0.1
    assert out["scenarios"] == ["fiber-cut"]


def test_local_client_missing_file_returns_empty() -> None:
    assert LocalKnowledgeClient("/no/such/file.json").scenario_config() == {}


def test_local_client_no_path_returns_empty() -> None:
    assert LocalKnowledgeClient(None).scenario_config() == {}


def test_real_mode_requires_base_url() -> None:
    with pytest.raises(ValueError, match="KNOWLEDGE_API_BASE_URL"):
        knowledge_client.make_client("real", None)


def test_real_mode_builds_http_client() -> None:
    client = knowledge_client.make_client("real", "http://knowledge:8080")
    assert isinstance(client, HttpKnowledgeClient)


def test_real_client_fetches_scenario_config() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, json={"noiseRate": 0.25})

    transport = httpx.MockTransport(handler)
    client = HttpKnowledgeClient("http://knowledge:8080/", client=httpx.Client(transport=transport))
    out = client.scenario_config()
    assert out == {"noiseRate": 0.25}
    assert seen["url"] == "http://knowledge:8080/knowledge/scenario-config"


def test_real_client_raises_on_http_error() -> None:
    transport = httpx.MockTransport(lambda req: httpx.Response(500, text="boom"))
    client = HttpKnowledgeClient("http://knowledge:8080", client=httpx.Client(transport=transport))
    with pytest.raises(httpx.HTTPStatusError):
        client.scenario_config()
