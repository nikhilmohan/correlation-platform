"""Topology snapshot-listing client — config-switchable mock/real (spec Task 13, AC 44).

``GET /topology/snapshots`` returns the published ``SnapshotListDto`` with a ``snapshots[]`` array
of ``SnapshotSummaryDto``. This is the *listing* surface (distinct from the P1 ``topology_client``
which only *uploads*); P3 synthesis never calls ``POST /topology/snapshots`` (spec AC 42). Built
against Topology's published OpenAPI, never its source.
"""

from __future__ import annotations

import time

import httpx

from simulator.synth.models import SnapshotSummary

SNAPSHOTS_PATH = "/topology/snapshots"


class SnapshotListError(RuntimeError):
    """Raised when the snapshot listing fails after bounded retry."""


class MockTopologySnapshotClient:
    """In-process stub returning a configured ``SnapshotSummary[]`` (no network, call-counted)."""

    def __init__(self, snapshots: list[dict[str, object]] | None = None) -> None:
        self._snapshots = list(snapshots or [])
        self.calls = 0

    def list_snapshots(self, domain: str | None = None) -> list[SnapshotSummary]:
        self.calls += 1
        return [SnapshotSummary.from_json(s) for s in self._snapshots]


class HttpTopologySnapshotClient:
    """Real ``httpx`` client against the published ``GET /topology/snapshots`` endpoint."""

    def __init__(
        self, base_url: str, *, max_attempts: int = 4, client: httpx.Client | None = None
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._max_attempts = max_attempts
        self._client = client or httpx.Client(timeout=30.0)
        self.calls = 0

    def list_snapshots(self, domain: str | None = None) -> list[SnapshotSummary]:
        url = f"{self._base_url}{SNAPSHOTS_PATH}"
        params = {"domain": domain} if domain else None
        last_exc: Exception | None = None
        for attempt in range(self._max_attempts):
            self.calls += 1
            try:
                resp = self._client.get(url, params=params)
                if resp.status_code == 200:
                    body = resp.json()
                    return [SnapshotSummary.from_json(s) for s in (body.get("snapshots") or [])]
                last_exc = SnapshotListError(
                    f"unexpected status {resp.status_code} from {url}: {resp.text[:200]}"
                )
            except httpx.HTTPError as exc:
                last_exc = exc
            if attempt < self._max_attempts - 1:
                time.sleep(min(2.0**attempt * 0.1, 2.0))
        raise SnapshotListError(
            f"list snapshots from {url} failed after {self._max_attempts} attempts"
        ) from last_exc


def make_client(
    mode: str, base_url: str | None, *, http_client: httpx.Client | None = None
) -> MockTopologySnapshotClient | HttpTopologySnapshotClient:
    """Build the Topology snapshot client for the configured mode (switch = no code change)."""
    if mode == "real":
        if not base_url:
            raise ValueError("TOPOLOGY_API_BASE_URL required when TOPOLOGY_API_MODE=real")
        return HttpTopologySnapshotClient(base_url, client=http_client)
    return MockTopologySnapshotClient()
