"""Trail Builder read client — config-switchable mock/real (spec Task 13, AC 33, 44).

``GET /trails/{trailId}`` returns the published ``TrailDetail`` with ``members[]`` of
``TrailMember {managedObjectId, objectType}`` (OQ-P3-5 resolved — ``objectType`` is declared per
member, consumed as-is; NOT derived from the moid prefix). Built against Trail Builder's published
OpenAPI, never its source. A ``404`` returns a typed :class:`TrailNotFound` sentinel (not an abort)
so the caller can drop the pattern and continue.
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from simulator.synth.models import TrailDetail

TRAIL_PATH = "/trails"


class TrailNotFound:
    """Sentinel: the requested ``trailId`` returned HTTP 404 (drop the pattern, warn, continue)."""

    def __init__(self, trail_id: str) -> None:
        self.trail_id = trail_id


class TrailFetchError(RuntimeError):
    """Raised when a trail fetch fails (non-404) after bounded retry."""


class MockTrailBuilderClient:
    """In-process stub returning configured ``TrailDetail`` bodies (404 for unknown ids)."""

    def __init__(self, trails: dict[str, dict[str, Any]] | None = None) -> None:
        self._trails = dict(trails or {})
        self.calls = 0
        self.requested: list[str] = []

    def get_trail(self, trail_id: str) -> TrailDetail | TrailNotFound:
        self.calls += 1
        self.requested.append(trail_id)
        body = self._trails.get(trail_id)
        if body is None:
            return TrailNotFound(trail_id)
        return TrailDetail.from_api(body)


class HttpTrailBuilderClient:
    """Real ``httpx`` client against the published ``GET /trails/{trailId}`` endpoint."""

    def __init__(
        self, base_url: str, *, max_attempts: int = 4, client: httpx.Client | None = None
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._max_attempts = max_attempts
        self._client = client or httpx.Client(timeout=30.0)
        self.calls = 0

    def get_trail(self, trail_id: str) -> TrailDetail | TrailNotFound:
        url = f"{self._base_url}{TRAIL_PATH}/{trail_id}"
        last_exc: Exception | None = None
        for attempt in range(self._max_attempts):
            self.calls += 1
            try:
                resp = self._client.get(url)
                if resp.status_code == 200:
                    return TrailDetail.from_api(resp.json())
                if resp.status_code == 404:
                    return TrailNotFound(trail_id)
                last_exc = TrailFetchError(
                    f"unexpected status {resp.status_code} from {url}: {resp.text[:200]}"
                )
            except httpx.HTTPError as exc:
                last_exc = exc
            if attempt < self._max_attempts - 1:
                time.sleep(min(2.0**attempt * 0.1, 2.0))
        raise TrailFetchError(
            f"get trail {trail_id} from {url} failed after {self._max_attempts} attempts"
        ) from last_exc


def make_client(
    mode: str, base_url: str | None, *, http_client: httpx.Client | None = None
) -> MockTrailBuilderClient | HttpTrailBuilderClient:
    """Build the Trail Builder client for the configured mode (switch requires no code change)."""
    if mode == "real":
        if not base_url:
            raise ValueError("TRAIL_BUILDER_API_BASE_URL required when TRAIL_BUILDER_API_MODE=real")
        return HttpTrailBuilderClient(base_url, client=http_client)
    return MockTrailBuilderClient()
