"""Topology Service client — graph query API (``topology-query`` integration point).

Built against the frozen Topology producer shapes:
- list by type: ``GET /topology/nodes?objectType=&domain=&snapshotId=current|previous``
  -> ``NodeListDto`` (spec criterion 15).
- bounded traverse: ``GET /topology/traversal?start=&relation=&...&maxDepth=&crossDomain=false``
  -> ``TraversalDto``.

API-only: this service never holds graph credentials or runs graph queries (single-owner
invariant). The trigger event's snapshot is the ``current`` snapshot for the domain.
"""

from __future__ import annotations

import httpx

from ..models import NodeListDto, TraversalDto
from .base import request_with_retry

SNAPSHOT_CURRENT = "current"
SNAPSHOT_PREVIOUS = "previous"


class TopologyClient:
    """Lists fault-origin instances and fetches bounded graph closures."""

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

    def list_objects_by_type(
        self, object_type: str, domain: str, snapshot_id: str = SNAPSHOT_CURRENT
    ) -> NodeListDto:
        """``GET /topology/nodes`` scoped by ``objectType``, ``domain``, ``snapshotId``."""
        resp = request_with_retry(
            lambda: self._client.get(
                f"{self._url}/topology/nodes",
                params={
                    "objectType": object_type,
                    "domain": domain,
                    "snapshotId": snapshot_id,
                },
            ),
            max_retries=self._max_retries,
            backoff_ms=self._backoff_ms,
        )
        return NodeListDto.model_validate(resp.json())

    def traverse(
        self,
        start: str,
        relations: list[str],
        domain: str,
        max_depth: int,
        snapshot_id: str = SNAPSHOT_CURRENT,
    ) -> TraversalDto:
        """``GET /topology/traversal`` — bounded traverse (one ``relation`` per edge type)."""
        # httpx repeats a list param as relation=a&relation=b (frozen producer shape).
        params: list[tuple[str, str]] = [
            ("start", start),
            ("domain", domain),
            ("maxDepth", str(max_depth)),
            ("crossDomain", "false"),
            ("snapshotId", snapshot_id),
        ]
        params.extend(("relation", r) for r in relations)
        resp = request_with_retry(
            lambda: self._client.get(f"{self._url}/topology/traversal", params=params),
            max_retries=self._max_retries,
            backoff_ms=self._backoff_ms,
        )
        return TraversalDto.model_validate(resp.json())
