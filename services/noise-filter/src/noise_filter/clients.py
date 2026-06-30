"""HTTP clients for the collaborating services (Knowledge, Topology, Trail Builder).

Every client is built against the collaborator's *published OpenAPI* (never its source) and is
config-switchable mock/real by env (`*_CLIENT_MODE`). In ``mock`` mode the client still issues
HTTP calls (so respx, generated from the collaborator's OpenAPI, can intercept them in unit
tests); in ``real`` mode it points at the live service.

IMPORTANT (recurring envelope bug): Knowledge record reads return a **RecordResponse ENVELOPE**
``{ "recordId", "recordType", "version", "domain", "payload": {...} }``. Consumers MUST read
``.payload`` — never the top level. :meth:`KnowledgeClient.fetch_model_params` and
:meth:`KnowledgeClient.fetch_feature_config` both unwrap ``payload`` explicitly.
"""

from __future__ import annotations

from typing import Any

import httpx

from .config import FeatureSettings, ModelParams
from .logging_setup import get_logger

log = get_logger(__name__)

# Knowledge recordIds (design: core-ip/modelParams/noise-filter).
MODEL_PARAMS_RECORD_ID = "core-ip/modelParams/noise-filter"
FEATURE_CONFIG_RECORD_ID = "core-ip/featureConfig/noise-filter"


class KnowledgeClient:
    """Fetches DBSCAN model params + feature config from the Knowledge Service.

    Both reads unwrap the RecordResponse ``payload`` envelope (recurring-bug guard).
    """

    def __init__(self, base_url: str, *, timeout: float = 10.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def _get_record_payload(self, record_id: str) -> dict[str, Any]:
        """GET /api/v1/records/{recordId} and return the unwrapped ``payload`` map.

        The Knowledge read API returns a RecordResponse ENVELOPE; the actual model-params /
        feature-config content lives under ``payload`` (NOT at the top level).
        """
        url = f"{self._base_url}/api/v1/records/{record_id}"
        resp = httpx.get(url, timeout=self._timeout)
        resp.raise_for_status()
        body = resp.json()
        if not isinstance(body, dict) or "payload" not in body:
            raise ValueError(
                f"Knowledge record {record_id!r} response is not a RecordResponse envelope "
                f"(missing 'payload'); got keys {sorted(body) if isinstance(body, dict) else body}"
            )
        payload = body["payload"]
        if not isinstance(payload, dict):
            raise ValueError(f"Knowledge record {record_id!r} payload is not an object")
        return payload

    def fetch_model_params(self) -> ModelParams:
        """Fetch + parse the DBSCAN model params from the RecordResponse payload."""
        payload = self._get_record_payload(MODEL_PARAMS_RECORD_ID)
        return ModelParams(
            eps=float(payload["eps"]),
            min_samples=int(payload["minSamples"]),
            window_size_seconds=int(payload["windowSize"]),
            algorithm=str(payload.get("algorithm", "dbscan")),
        )

    def fetch_feature_config(self) -> FeatureSettings:
        """Fetch + parse the active feature config from the RecordResponse payload."""
        payload = self._get_record_payload(FEATURE_CONFIG_RECORD_ID)
        keys = payload.get("attributeKeys", []) or []
        return FeatureSettings(
            attribute_keys=tuple(str(k) for k in keys),
            hop_distance_enabled=bool(payload.get("hopDistanceEnabled", False)),
            hop_traversal_max_depth=int(payload.get("hopTraversalMaxDepth", 8)),
        )


class TopologyClient:
    """Fetches a node's ``attributes`` map by managedObjectId.

    Built against the Topology Service published op ``GET /topology/nodes/{managedObjectId}``
    returning a ``NodeDto`` (``managedObjectId``, ``objectType``, ``domain``, ``name``,
    ``attributes``, ``snapshotId``). Instantiated only when an attribute feature is enabled.
    """

    def __init__(self, base_url: str, *, timeout: float = 10.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def fetch_attributes(self, managed_object_id: str) -> dict[str, Any]:
        """Return the node's ``attributes`` map ({} on unknown node / missing attributes)."""
        url = f"{self._base_url}/topology/nodes/{managed_object_id}"
        resp = httpx.get(url, timeout=self._timeout)
        resp.raise_for_status()
        body = resp.json()
        attrs = body.get("attributes") if isinstance(body, dict) else None
        return attrs if isinstance(attrs, dict) else {}


class TrailContext:
    """Resolved trail context from ``getTrail(trailId)``: members, edges, seed, snapshotId."""

    def __init__(
        self,
        *,
        trail_id: str,
        snapshot_id: str,
        domain: str | None,
        member_ids: list[str],
        edges: list[tuple[str, str]],
        seed_id: str | None,
    ) -> None:
        self.trail_id = trail_id
        self.snapshot_id = snapshot_id
        self.domain = domain
        self.member_ids = member_ids
        self.edges = edges  # directed (src -> dst) dependency edges
        self.seed_id = seed_id


class TrailBuilderClient:
    """Calls ``getTrail(trailId)`` for snapshotId provenance + hop-distance seed/edges.

    Built against the Trail Builder published OpenAPI; instantiated only when the hop-distance
    feature is enabled OR when snapshotId provenance needs it.
    """

    def __init__(self, base_url: str, *, timeout: float = 10.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def get_trail(self, trail_id: str) -> TrailContext:
        """GET /api/v1/trails/{trailId} -> TrailContext (members, edges, seed, snapshotId)."""
        url = f"{self._base_url}/api/v1/trails/{trail_id}"
        resp = httpx.get(url, timeout=self._timeout)
        resp.raise_for_status()
        body = resp.json()
        members = body.get("members", []) or []
        member_ids = [str(m.get("managedObjectId")) for m in members if m.get("managedObjectId")]
        edges_raw = body.get("edges", []) or []
        edges: list[tuple[str, str]] = []
        for e in edges_raw:
            src = e.get("from") or e.get("source") or e.get("src")
            dst = e.get("to") or e.get("target") or e.get("dst")
            if src and dst:
                edges.append((str(src), str(dst)))
        seed = body.get("seed") or body.get("root") or body.get("faultOrigin")
        seed_id = str(seed) if seed else None
        return TrailContext(
            trail_id=trail_id,
            snapshot_id=str(body["snapshotId"]),
            domain=body.get("domain"),
            member_ids=member_ids,
            edges=edges,
            seed_id=seed_id,
        )
