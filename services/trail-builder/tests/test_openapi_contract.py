"""Guards the checked-in ``openapi.json`` against drift from the live FastAPI app.

The checked-in ``services/trail-builder/openapi.json`` is the FROZEN single
source of truth consumers (Codebook Generator, Enrichment, Noise Filter, web-ui)
generate their clients against. A surface change is a contract change requiring
``docs/architecture.md`` + human approval — this test fails loudly if the
generated surface drifts from the committed file (AC-16).
"""

from __future__ import annotations

import json
import pathlib

import httpx

from trailbuilder.api import create_app
from trailbuilder.clients.policy_client import KnowledgePolicyClient
from trailbuilder.clients.topology_client import TopologyClient
from trailbuilder.container import build_container

_OPENAPI = pathlib.Path(__file__).resolve().parent.parent / "openapi.json"


def test_checked_in_openapi_matches_live_app(settings, engine, producer) -> None:
    topo = TopologyClient(settings, client=httpx.Client(base_url="http://t"))
    policy = KnowledgePolicyClient(settings, client=httpx.Client(base_url="http://k"))
    container = build_container(
        settings, engine, producer, topology_client=topo, policy_client=policy
    )
    live = create_app(container).openapi()
    committed = json.loads(_OPENAPI.read_text())
    # Compare the contract-relevant surface (paths + component schemas).
    assert committed["openapi"] == live["openapi"] == "3.1.0"
    assert committed["paths"] == live["paths"], "openapi.json drifted — regenerate + review"
    assert committed["components"]["schemas"] == live["components"]["schemas"]
