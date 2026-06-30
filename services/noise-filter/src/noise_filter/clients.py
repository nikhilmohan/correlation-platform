"""HTTP clients for the collaborating services (Knowledge, Topology, Trail Builder).

Every client is built against the collaborator's *published OpenAPI* (never its source) and is
config-switchable mock/real by env (`*_CLIENT_MODE`). In ``mock`` mode the client still issues
HTTP calls (so respx, generated from the collaborator's OpenAPI, can intercept them in unit
tests); in ``real`` mode it points at the live service.

IMPORTANT (recurring envelope bug): Knowledge record reads return a **RecordResponse ENVELOPE**
``{ "recordId", "recordType", "version", "domain", "payload": {...} }``. Consumers MUST read
``.payload`` — never the top level. :meth:`KnowledgeClient.fetch_model_params` and
:meth:`KnowledgeClient.fetch_feature_config` both unwrap ``payload`` explicitly.

REAL Knowledge API (verified live against cp-knowledge ``/openapi.json``): records are served by
the generic recordType route ``GET /domains/{domain}/{recordType}`` (a LIST of RecordResponse
envelopes) and ``GET /domains/{domain}/{recordType}/{recordId}`` (one envelope; the recordId
contains slashes and MUST be URL-encoded). The recordType path segment is **kebab-case**
(``model-params``) even though the recordId carries the camelCase ``modelParams`` token. There is
NO ``/api/v1/records/...`` route. The DBSCAN params live in the model-params record's
``payload.params`` array as ``{key, value}`` entries (e.g. ``dbscan.epsilon``,
``dbscan.minSamples``, ``window.sizeSeconds``) — NOT as flat top-level fields. The same record
carries the feature toggles (``feature.attributeKeys``, ``feature.hopDistance.enabled``,
``feature.objectTypeLayer.enabled``); there is no separate ``feature-config`` recordType (a
``feature-config`` GET 400s), so feature config is derived from the model-params record with
documented fallbacks for any absent knob.
"""

from __future__ import annotations

from typing import Any
from urllib.parse import quote

import httpx

from .config import FeatureSettings, ModelParams
from .logging_setup import get_logger

log = get_logger(__name__)

# Knowledge domain + recordType path segments (verified live). recordType is kebab-case in the
# path even though the recordId token is camelCase.
KNOWLEDGE_DOMAIN = "core-ip"
MODEL_PARAMS_RECORD_TYPE = "model-params"
# Full recordId of the noise-filter model-params record (carries the camelCase token + slashes).
MODEL_PARAMS_RECORD_ID = "core-ip/modelParams/noise-filter"


def _params_to_map(payload: dict[str, Any]) -> dict[str, Any]:
    """Flatten a Knowledge model-params ``payload.params`` array into a ``{key: value}`` map.

    Real model-params payloads are ``{"params": [{"key", "value", ...}], "paramSet": ...}``.
    Returns ``{}`` when no ``params`` array is present (caller applies documented fallbacks).
    """
    params = payload.get("params")
    if not isinstance(params, list):
        return {}
    out: dict[str, Any] = {}
    for entry in params:
        if isinstance(entry, dict) and "key" in entry:
            out[str(entry["key"])] = entry.get("value")
    return out


class KnowledgeClient:
    """Fetches DBSCAN model params + feature config from the Knowledge Service.

    Both reads target the real ``GET /domains/{domain}/{recordType}/{recordId}`` route and unwrap
    the RecordResponse ``payload`` envelope (recurring-bug guard).
    """

    def __init__(self, base_url: str, *, timeout: float = 10.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def _record_url(self, record_type: str, record_id: str) -> str:
        """Build the live single-record URL ``/domains/{domain}/{recordType}/{recordId}``.

        The recordId contains slashes (``core-ip/modelParams/noise-filter``) so it MUST be fully
        percent-encoded (``safe=""``) into a single path segment, or the router 404s.
        """
        return (
            f"{self._base_url}/domains/{KNOWLEDGE_DOMAIN}/{record_type}/{quote(record_id, safe='')}"
        )

    def _get_record_payload(self, record_type: str, record_id: str) -> dict[str, Any]:
        """GET the single record and return the unwrapped ``payload`` map.

        The Knowledge read API returns a RecordResponse ENVELOPE; the actual model-params content
        lives under ``payload`` (NOT at the top level).
        """
        url = self._record_url(record_type, record_id)
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
        """Fetch + parse the DBSCAN model params from the RecordResponse payload.params array."""
        payload = self._get_record_payload(MODEL_PARAMS_RECORD_TYPE, MODEL_PARAMS_RECORD_ID)
        p = _params_to_map(payload)
        return ModelParams(
            eps=float(p["dbscan.epsilon"]),
            min_samples=int(p["dbscan.minSamples"]),
            window_size_seconds=int(p["window.sizeSeconds"]),
            algorithm=str(p.get("dbscan.algorithm", "dbscan")),
        )

    def fetch_feature_config(self) -> FeatureSettings:
        """Derive the active feature config from the model-params RecordResponse payload.

        Feature toggles live in the same model-params record (there is no separate feature-config
        recordType in the real Knowledge service); any absent knob falls back to the documented
        default rather than crashing.
        """
        defaults = FeatureSettings.fallback()
        try:
            payload = self._get_record_payload(MODEL_PARAMS_RECORD_TYPE, MODEL_PARAMS_RECORD_ID)
        except (httpx.HTTPError, ValueError) as exc:
            log.warning("feature_config_fetch_failed_using_defaults", error=str(exc))
            return defaults
        p = _params_to_map(payload)
        keys = p.get("feature.attributeKeys", []) or []
        if not isinstance(keys, list):
            keys = []
        return FeatureSettings(
            attribute_keys=tuple(str(k) for k in keys),
            hop_distance_enabled=bool(p.get("feature.hopDistance.enabled", False)),
            hop_traversal_max_depth=int(p.get("feature.hopTraversalMaxDepth", 8)),
            # Encoding knobs co-tuned with eps — Knowledge-authored, not code literals.
            time_scale_seconds=float(
                p.get("feature.timeScaleSeconds", defaults.time_scale_seconds)
            ),
            categorical_weight=float(
                p.get("feature.categoricalWeight", defaults.categorical_weight)
            ),
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
        """GET /trails/{trailId} -> TrailContext (members, edges, seed, snapshotId).

        Real trail-builder serves NO ``/api/v1`` prefix (verified live: ``/trails/{trailId}``).
        The live ``TrailDetail`` carries members + snapshotId (+ domain/igpArea/srlgGroup);
        ``edges`` and ``seed`` are not in the contract, so the tolerant extraction below yields
        empty/None.
        """
        url = f"{self._base_url}/trails/{quote(trail_id, safe='')}"
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
