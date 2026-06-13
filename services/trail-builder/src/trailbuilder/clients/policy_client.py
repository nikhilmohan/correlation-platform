"""``KnowledgePolicyClient`` — domain-scoped trail policy from the Knowledge API.

Reads the ``trailPolicy`` record for a domain via the frozen Knowledge read API
(``GET /domains/{domain}/trailPolicy/default`` -> ``RecordResponse`` whose
``payload`` is the trail policy). Caches per domain; ``invalidate(domain)`` drops
the cache so the next ``get_policy`` re-fetches (driven by ``knowledge.updated``).
No policy value is hard-coded.
"""

from __future__ import annotations

import httpx

from ..config import Settings
from ..models import Boundary, SrlgRule, TrailPolicy
from .errors import IntegrationError

_RECORD_ID = "default"


class KnowledgePolicyClient:
    """Fetches + caches the per-domain trail policy."""

    def __init__(self, settings: Settings, client: httpx.Client | None = None) -> None:
        self._settings = settings
        self._base = settings.knowledge_service_base_url.rstrip("/")
        self._client = client or httpx.Client(
            base_url=self._base, timeout=settings.http_timeout_seconds
        )
        self._cache: dict[str, TrailPolicy] = {}

    def get_policy(self, domain: str) -> TrailPolicy:
        """Return the domain's trail policy (cached after the first fetch)."""
        cached = self._cache.get(domain)
        if cached is not None:
            return cached
        try:
            policy = self._fetch(domain)
        except IntegrationError:
            if self._settings.knowledge_stale_ok and domain in self._cache:
                return self._cache[domain]
            raise
        self._cache[domain] = policy
        return policy

    def invalidate(self, domain: str) -> None:
        """Drop the cached policy for ``domain`` so the next access re-fetches."""
        self._cache.pop(domain, None)

    def ping(self) -> bool:
        try:
            self._client.get(f"/domains/{self._settings.default_domain}/trailPolicy/{_RECORD_ID}")
            return True
        except httpx.HTTPError:
            return False

    # --- internals ---

    def _fetch(self, domain: str) -> TrailPolicy:
        attempts = max(1, self._settings.http_retry_max)
        last: Exception | None = None
        for _ in range(attempts):
            try:
                resp = self._client.get(f"/domains/{domain}/trailPolicy/{_RECORD_ID}")
                resp.raise_for_status()
                body = resp.json()
                return _policy_from_record(body)
            except (httpx.HTTPError, ValueError, KeyError) as exc:
                last = exc
        raise IntegrationError("knowledge", f"trailPolicy fetch for {domain!r} failed: {last}")


def _policy_from_record(record: dict) -> TrailPolicy:
    """Decode a Knowledge ``RecordResponse`` into a :class:`TrailPolicy`."""
    payload = record.get("payload", record)
    try:
        edges = tuple(payload["closureEdgeTypes"])
        boundary = payload["boundary"]
        srlg = payload["srlgRule"]
    except KeyError as exc:
        raise IntegrationError("knowledge", f"trailPolicy payload missing {exc}") from exc
    if not edges:
        raise IntegrationError("knowledge", "trailPolicy.closureEdgeTypes is empty")
    return TrailPolicy(
        closure_edge_types=edges,
        boundary=Boundary(
            type=boundary.get("type", "none"),
            attribute_key=boundary.get("attributeKey"),
        ),
        srlg_rule=SrlgRule(
            mode=srlg.get("mode", "none"),
            srlg_edge_type=srlg.get("srlgEdgeType"),
        ),
    )
