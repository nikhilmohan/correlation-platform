"""``KnowledgePolicyClient`` — domain-scoped trail policy from the Knowledge API.

Reads the ``trailPolicy`` record for a domain via the frozen Knowledge read API. The
RecordController route is ``GET /domains/{domain}/{recordTypePathSegment}/{recordId}``
where ``recordTypePathSegment`` is the kebab-case form of the ``RecordType`` enum
(``TRAIL_POLICY`` -> ``trail-policies``) and ``recordId`` is the seeded, slash-bearing
id ``core-ip/trailPolicy/default``. The recordId is URL-encoded so its slashes survive
as a single path segment; Knowledge ``URLDecoder.decode``s it once on the way in. So the
canonical request is::

    GET /domains/{domain}/trail-policies/core-ip%2FtrailPolicy%2Fdefault

-> ``RecordResponse`` whose ``payload`` is the trail policy. Caches per domain;
``invalidate(domain)`` drops the cache so the next ``get_policy`` re-fetches (driven by
``knowledge.updated``). No policy value is hard-coded.
"""

from __future__ import annotations

from urllib.parse import quote

import httpx

from ..config import Settings
from ..models import Boundary, SrlgRule, TrailPolicy
from ._retry import backoff_before_retry
from .errors import IntegrationError

# Knowledge's kebab-case RecordType.pathSegment() for TRAIL_POLICY (NOT the "trailPolicy"
# enum id). Drift from Knowledge's published segments is caught by a unit test.
POLICY_PATH_SEGMENT = "trail-policies"
# The seeded recordId for the default trail policy (core-ip.json). It is a slash-bearing
# id; URL-encoded into a single path segment below.
POLICY_RECORD_ID = "core-ip/trailPolicy/default"


def _policy_path(domain: str) -> str:
    """Build the exact Knowledge RecordController path for ``domain``'s trail policy.

    The recordId's embedded slashes are percent-encoded (``safe=""``) so the whole
    recordId is one path segment, matching Knowledge's single ``URLDecoder.decode``.
    """
    return f"/domains/{domain}/{POLICY_PATH_SEGMENT}/{quote(POLICY_RECORD_ID, safe='')}"


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
            self._client.get(_policy_path(self._settings.default_domain))
            return True
        except httpx.HTTPError:
            return False

    # --- internals ---

    def _fetch(self, domain: str) -> TrailPolicy:
        attempts = max(1, self._settings.http_retry_max)
        backoff_ms = self._settings.http_retry_backoff_ms
        last: Exception | None = None
        for i in range(attempts):
            try:
                resp = self._client.get(_policy_path(domain))
                resp.raise_for_status()
                body = resp.json()
                return _policy_from_record(body)
            except (httpx.HTTPError, ValueError, KeyError) as exc:
                last = exc
                backoff_before_retry(i, attempts, backoff_ms)
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
