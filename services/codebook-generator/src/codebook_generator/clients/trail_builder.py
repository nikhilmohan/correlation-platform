"""Trail Builder Service client — trail membership (``trail-builder-trails``).

Built against the frozen Trail Builder producer shape:
- ``GET /trails/by-object?managedObjectId=&domain=`` (``domain`` REQUIRED) ->
  ``TrailsForObjectResponse { managedObjectId, domain, trailIds }`` (spec criterion 4).
- ``GET /trails/{trailId}`` -> trail detail.

This service only reads trail membership for tagging; it never builds trails.
"""

from __future__ import annotations

from typing import Any

import httpx

from ..models import TrailsForObjectResponse
from .base import request_with_retry


class TrailBuilderClient:
    """Resolves the trail(s) a symptom object belongs to, domain-scoped."""

    def __init__(
        self,
        *,
        base_url: str,
        client: httpx.Client,
        max_retries: int,
        backoff_ms: int,
    ) -> None:
        self._url = base_url.rstrip("/")
        self._client = client
        self._max_retries = max_retries
        self._backoff_ms = backoff_ms

    def get_trails_for_object(self, managed_object_id: str, domain: str) -> TrailsForObjectResponse:
        """``GET /trails/by-object?managedObjectId=&domain=`` (domain required)."""
        resp = request_with_retry(
            lambda: self._client.get(
                f"{self._url}/trails/by-object",
                params={"managedObjectId": managed_object_id, "domain": domain},
            ),
            max_retries=self._max_retries,
            backoff_ms=self._backoff_ms,
        )
        return TrailsForObjectResponse.model_validate(resp.json())

    def get_trail(self, trail_id: str) -> dict[str, Any]:
        """``GET /trails/{trailId}`` -> trail detail (raw)."""
        resp = request_with_retry(
            lambda: self._client.get(f"{self._url}/trails/{trail_id}"),
            max_retries=self._max_retries,
            backoff_ms=self._backoff_ms,
        )
        return resp.json()
