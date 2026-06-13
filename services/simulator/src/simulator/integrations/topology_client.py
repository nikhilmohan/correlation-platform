"""Topology ingestion client — config-switchable mock/real (criteria 15, 15a, 36).

Uploads the topology snapshot file to the Topology Service's **frozen** ingestion endpoint
``POST /topology/snapshots``, which returns **HTTP 200** ``SnapshotIngestResponse
{snapshotId, domain, status, nodeCount, edgeCount, changeType}``; the client reads ``snapshotId``
from that 200 body. Built against Topology's *published OpenAPI*, never its source.
``TOPOLOGY_API_MODE=mock`` returns a synthetic 200 of the same frozen shape (no network);
``=real`` POSTs to ``{TOPOLOGY_API_BASE_URL}/topology/snapshots`` via ``httpx``.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import httpx

INGEST_PATH = "/topology/snapshots"


class TopologyUploadError(RuntimeError):
    """Raised when the Topology ingestion upload fails after bounded retry."""


@dataclass
class SnapshotIngestResponse:
    """The frozen 200 response shape from ``POST /topology/snapshots``."""

    snapshotId: str
    domain: str
    status: str
    nodeCount: int
    edgeCount: int
    changeType: str

    @classmethod
    def from_body(cls, body: dict[str, Any]) -> SnapshotIngestResponse:
        return cls(
            snapshotId=str(body["snapshotId"]),
            domain=str(body.get("domain", "")),
            status=str(body.get("status", "")),
            nodeCount=int(body.get("nodeCount", 0)),
            edgeCount=int(body.get("edgeCount", 0)),
            changeType=str(body.get("changeType", "")),
        )


class MockTopologyClient:
    """In-process stub mirroring the frozen 200 ``SnapshotIngestResponse`` (no network)."""

    def __init__(self) -> None:
        self.last_uploaded: dict[str, Any] | None = None
        self.calls = 0

    def upload(self, snapshot: dict[str, Any]) -> SnapshotIngestResponse:
        self.calls += 1
        self.last_uploaded = snapshot
        return SnapshotIngestResponse(
            snapshotId=f"snap-mock-{self.calls:04d}",
            domain=str(snapshot.get("domain", "")),
            status="lifted",
            nodeCount=len(snapshot.get("nodes", [])),
            edgeCount=len(snapshot.get("edges", [])),
            changeType="full",
        )


class HttpTopologyClient:
    """Real ``httpx`` client against the published Topology ingestion endpoint."""

    def __init__(self, base_url: str, *, max_attempts: int = 4, client: httpx.Client | None = None):
        self._base_url = base_url.rstrip("/")
        self._max_attempts = max_attempts
        self._client = client or httpx.Client(timeout=30.0)

    def upload(self, snapshot: dict[str, Any]) -> SnapshotIngestResponse:
        url = f"{self._base_url}{INGEST_PATH}"
        last_exc: Exception | None = None
        for attempt in range(self._max_attempts):
            try:
                resp = self._client.post(url, json=snapshot)
                if resp.status_code == 200:
                    return SnapshotIngestResponse.from_body(resp.json())
                last_exc = TopologyUploadError(
                    f"unexpected status {resp.status_code} from {url}: {resp.text[:200]}"
                )
            except httpx.HTTPError as exc:  # transport error
                last_exc = exc
            if attempt < self._max_attempts - 1:
                time.sleep(min(2.0**attempt * 0.1, 2.0))
        raise TopologyUploadError(f"upload to {url} failed after {self._max_attempts} attempts") from last_exc


def make_client(
    mode: str, base_url: str | None, *, http_client: httpx.Client | None = None
) -> MockTopologyClient | HttpTopologyClient:
    """Build the topology client for the configured mode (switch requires no code change)."""
    if mode == "real":
        if not base_url:
            raise ValueError("TOPOLOGY_API_BASE_URL required when TOPOLOGY_API_MODE=real")
        return HttpTopologyClient(base_url, client=http_client)
    return MockTopologyClient()
