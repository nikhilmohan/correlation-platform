"""Pattern Manager read client — config-switchable mock/real (spec Task 13, AC 32, 44).

``GET /patterns?lifecycle=approved`` returns the frozen ``PatternPage`` envelope
``{items[PatternView], total, limit, offset}`` (never a bare array). Built against Pattern
Manager's *published OpenAPI*, never its source. ``PATTERN_MANAGER_API_MODE=mock`` uses an
in-process stub (no network, call-counted); ``=real`` pages via ``httpx`` against
``{PATTERN_MANAGER_API_BASE_URL}/patterns``.
"""

from __future__ import annotations

import time
from typing import Any

import httpx

from simulator.synth.models import PatternView

PATTERNS_PATH = "/patterns"
_PAGE_LIMIT = 200


class PatternFetchError(RuntimeError):
    """Raised when the approved-pattern fetch fails after bounded retry."""


class MockPatternManagerClient:
    """In-process stub returning a configured ``PatternView[]`` (mirrors ``PatternPage``)."""

    def __init__(self, patterns: list[dict[str, Any]] | None = None) -> None:
        self._items = list(patterns or [])
        self.calls = 0

    def list_approved(self) -> list[PatternView]:
        self.calls += 1
        return [PatternView.from_api(p) for p in self._items]


class HttpPatternManagerClient:
    """Real ``httpx`` client paging the published ``GET /patterns`` envelope."""

    def __init__(
        self, base_url: str, *, max_attempts: int = 4, client: httpx.Client | None = None
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._max_attempts = max_attempts
        self._client = client or httpx.Client(timeout=30.0)
        self.calls = 0

    def list_approved(self) -> list[PatternView]:
        url = f"{self._base_url}{PATTERNS_PATH}"
        items: list[PatternView] = []
        offset = 0
        while True:
            page = self._get_page(url, offset)
            page_items = page.get("items") or []
            items.extend(PatternView.from_api(p) for p in page_items)
            total = int(page.get("total", len(items)))
            offset += len(page_items)
            if not page_items or offset >= total:
                break
        return items

    def _get_page(self, url: str, offset: int) -> dict[str, Any]:
        params = {"lifecycle": "approved", "limit": _PAGE_LIMIT, "offset": offset}
        last_exc: Exception | None = None
        for attempt in range(self._max_attempts):
            self.calls += 1
            try:
                resp = self._client.get(url, params=params)
                if resp.status_code == 200:
                    return resp.json()
                last_exc = PatternFetchError(
                    f"unexpected status {resp.status_code} from {url}: {resp.text[:200]}"
                )
            except httpx.HTTPError as exc:
                last_exc = exc
            if attempt < self._max_attempts - 1:
                time.sleep(min(2.0**attempt * 0.1, 2.0))
        raise PatternFetchError(
            f"list approved patterns from {url} failed after {self._max_attempts} attempts"
        ) from last_exc


def make_client(
    mode: str, base_url: str | None, *, http_client: httpx.Client | None = None
) -> MockPatternManagerClient | HttpPatternManagerClient:
    """Build the Pattern Manager client for the configured mode (switch requires no code change)."""
    if mode == "real":
        if not base_url:
            raise ValueError(
                "PATTERN_MANAGER_API_BASE_URL required when PATTERN_MANAGER_API_MODE=real"
            )
        return HttpPatternManagerClient(base_url, client=http_client)
    return MockPatternManagerClient()
