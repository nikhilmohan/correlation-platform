"""Topology ingestion client tests (criteria 15, 15a, 36).

The Simulator uploads the topology snapshot to the Topology Service's *frozen* ingestion
endpoint ``POST /topology/snapshots`` (NOT Kafka). The integration point is config-switchable:
``TOPOLOGY_API_MODE=mock`` uses an in-process stub (no network); ``=real`` POSTs via httpx to
the published OpenAPI endpoint. Switching mode requires no code change. The real client is
built against the *published* endpoint contract, never Topology's source, so the HTTP transport
is mocked here.
"""

from __future__ import annotations

import httpx
import pytest

from simulator.integrations import topology_client
from simulator.integrations.topology_client import (
    HttpTopologyClient,
    MockTopologyClient,
    SnapshotIngestResponse,
    TopologyUploadError,
)

SNAPSHOT = {
    "domain": "core-ip",
    "nodes": [{"managedObjectId": "Node:n1", "objectType": "Node"}],
    "edges": [{"relation": "HOSTED_ON", "from": "LineCard:lc1", "to": "Node:n1"}],
}


def _ok_body() -> dict[str, object]:
    return {
        "snapshotId": "snap-real-1",
        "domain": "core-ip",
        "status": "lifted",
        "nodeCount": 1,
        "edgeCount": 1,
        "changeType": "full",
    }


def test_ac15_mock_mode_uses_in_process_stub_no_network() -> None:
    client = topology_client.make_client("mock", None)
    assert isinstance(client, MockTopologyClient)
    resp = client.upload(SNAPSHOT)
    assert isinstance(resp, SnapshotIngestResponse)
    assert resp.snapshotId.startswith("snap-mock-")
    assert resp.nodeCount == 1 and resp.edgeCount == 1
    assert client.last_uploaded == SNAPSHOT


def test_ac15_real_mode_targets_configured_base_url() -> None:
    client = topology_client.make_client("real", "http://topology:8080")
    assert isinstance(client, HttpTopologyClient)


def test_ac15a_real_mode_requires_base_url() -> None:
    with pytest.raises(ValueError, match="TOPOLOGY_API_BASE_URL"):
        topology_client.make_client("real", None)


def test_ac15_switching_mode_requires_no_code_change() -> None:
    """Same make_client call, different env-sourced mode -> different transport."""
    mock_c = topology_client.make_client("mock", None)
    real_c = topology_client.make_client("real", "http://topology:8080")
    assert type(mock_c) is not type(real_c)


def test_ac36_real_client_posts_to_frozen_ingest_path() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["method"] = request.method
        return httpx.Response(200, json=_ok_body())

    transport = httpx.MockTransport(handler)
    http = httpx.Client(transport=transport)
    client = HttpTopologyClient("http://topology:8080", client=http)

    resp = client.upload(SNAPSHOT)

    assert seen["method"] == "POST"
    assert seen["url"] == "http://topology:8080/topology/snapshots"
    assert resp.snapshotId == "snap-real-1"
    assert resp.changeType == "full"


def test_real_client_reads_snapshot_id_from_200_body() -> None:
    transport = httpx.MockTransport(lambda req: httpx.Response(200, json=_ok_body()))
    client = HttpTopologyClient("http://topology:8080/", client=httpx.Client(transport=transport))
    resp = client.upload(SNAPSHOT)
    assert resp.snapshotId == "snap-real-1"


def test_real_client_retries_then_raises_on_persistent_error() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(503, text="unavailable")

    transport = httpx.MockTransport(handler)
    client = HttpTopologyClient(
        "http://topology:8080", max_attempts=3, client=httpx.Client(transport=transport)
    )
    with pytest.raises(TopologyUploadError, match="failed after 3 attempts"):
        client.upload(SNAPSHOT)
    assert calls["n"] == 3


def test_real_client_retries_then_succeeds() -> None:
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if calls["n"] < 2:
            return httpx.Response(500, text="boom")
        return httpx.Response(200, json=_ok_body())

    transport = httpx.MockTransport(handler)
    client = HttpTopologyClient(
        "http://topology:8080", max_attempts=4, client=httpx.Client(transport=transport)
    )
    resp = client.upload(SNAPSHOT)
    assert resp.snapshotId == "snap-real-1"
    assert calls["n"] == 2


def test_real_client_wraps_transport_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route to host")

    transport = httpx.MockTransport(handler)
    client = HttpTopologyClient(
        "http://topology:8080", max_attempts=2, client=httpx.Client(transport=transport)
    )
    with pytest.raises(TopologyUploadError):
        client.upload(SNAPSHOT)


def test_snapshot_ingest_response_from_body_defaults() -> None:
    resp = SnapshotIngestResponse.from_body({"snapshotId": "s1"})
    assert resp.snapshotId == "s1"
    assert resp.nodeCount == 0 and resp.edgeCount == 0
    assert resp.domain == "" and resp.status == "" and resp.changeType == ""
