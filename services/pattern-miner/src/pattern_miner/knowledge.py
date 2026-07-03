"""Knowledge Service client — mining params for the pattern-miner param set.

Built against the Knowledge Service's *published OpenAPI* (never its source) and config-switchable
mock/real by env (``KNOWLEDGE_CLIENT_MODE``). In ``mock`` mode the client still issues real HTTP
calls (so respx, generated from the collaborator's OpenAPI, can intercept them in unit tests); in
``real`` mode it points at the live service.

IMPORTANT (recurring envelope bug): the Knowledge record read returns a **RecordResponse
ENVELOPE** ``{recordId, recordType, version, domain, isCurrent, payload:{paramSet, params:[...]}}``.
Consumers MUST read ``.payload`` — never the top level — and then ``.payload.params[]``.

REAL Knowledge API (verified live against cp-knowledge ``/openapi.json`` and the pattern-miner
record): records are served by the generic recordType route
``GET /domains/{domain}/{recordType}/{recordId}`` (one envelope; the recordId contains slashes and
MUST be URL-encoded into a single path segment). The recordType path segment is **kebab-case**
(``model-params``) even though the recordId carries the camelCase ``modelParams`` token. There is
NO ``/api/v1/...`` route and NO flat ``/knowledge/model-params`` path. Mining params live in the
record's ``payload.params`` array as ``{key, type, value, min?, max?, unit?}`` entries with the
dotted keys ``prefixspan.minSupport``, ``prefixspan.maxPatternLength``,
``prefixspan.maxSequenceCount``, ``window.adaptive.baseGapSeconds``,
``window.adaptive.gapMultiplier``, ``window.adaptive.tempoPercentile``,
``window.adaptive.profiles``, ``codebookVersion`` — NOT as flat top-level fields.
"""

from __future__ import annotations

import time
from typing import Any
from urllib.parse import quote

import httpx

from .config import AnchoringParams, MiningParams, TempoProfile, WindowingParams
from .logging_setup import get_logger

log = get_logger(__name__)

# recordType path segment is kebab-case in the URL even though the recordId token is camelCase.
MODEL_PARAMS_RECORD_TYPE = "model-params"

# Dotted param keys as authored in the Knowledge model-params record (verified live).
KEY_MIN_SUPPORT = "prefixspan.minSupport"
KEY_MAX_PATTERN_LENGTH = "prefixspan.maxPatternLength"
KEY_MAX_SEQUENCE_COUNT = "prefixspan.maxSequenceCount"
KEY_BASE_GAP_SECONDS = "window.adaptive.baseGapSeconds"
KEY_GAP_MULTIPLIER = "window.adaptive.gapMultiplier"
KEY_TEMPO_PERCENTILE = "window.adaptive.tempoPercentile"
KEY_MAX_CLOSING_GAP_SECONDS = "window.adaptive.maxClosingGapSeconds"
KEY_MIN_BURST_SAMPLES = "window.adaptive.minBurstSamples"
KEY_PROFILES = "window.adaptive.profiles"
KEY_CLASS_THRESHOLDS = "window.adaptive.classThresholds"
KEY_CODEBOOK_VERSION = "codebookVersion"

# Stage-2 domain-knowledge anchoring keys (authored in the same model-params record).
KEY_MATCH_CONFIDENCE_THRESHOLD = "anchoring.matchConfidenceThreshold"
KEY_SCORING_METHOD = "anchoring.scoringMethod"
KEY_GROUPING_KEYS = "anchoring.groupingKeys"
KEY_TIE_BREAK = "anchoring.tieBreak"
KEY_W_ORDER = "anchoring.weights.order"
KEY_W_JACCARD = "anchoring.weights.jaccard"

# [BATCH-CAP] Optional operational batching override (NOT a mining threshold). When Knowledge
# authors it the value overrides the MAX_TRAILS_PER_BATCH env default; when absent the env wins.
KEY_MAX_TRAILS_PER_BATCH = "batching.maxTrailsPerBatch"

# Structural (non-threshold, non-domain) fallbacks for the anchoring scorer selection and grouping.
# These are the reusable-template defaults — a scorer NAME, a tie-break RULE, and a grouping KEY
# NAME — none is a correlation threshold or a domain literal, so authoring them in Knowledge is
# optional. The confidence threshold and the scorer WEIGHTS are required and never defaulted.
DEFAULT_SCORING_METHOD = "ordered_subsequence_jaccard"
DEFAULT_TIE_BREAK = "chain_length_then_scenario_id"
DEFAULT_GROUPING_KEYS: tuple[str, ...] = ("scenarioId",)


class KnowledgeError(RuntimeError):
    """Raised when mining params cannot be fetched/parsed from the Knowledge Service."""


def _params_to_map(payload: dict[str, Any]) -> dict[str, Any]:
    """Flatten a Knowledge model-params ``payload.params`` array into a ``{key: value}`` map.

    Real model-params payloads are ``{"params": [{"key", "value", ...}], "paramSet": ...}``.
    """
    params = payload.get("params")
    if not isinstance(params, list):
        return {}
    out: dict[str, Any] = {}
    for entry in params:
        if isinstance(entry, dict) and "key" in entry:
            out[str(entry["key"])] = entry.get("value")
    return out


class MiningParamsClient:
    """Fetches the typed :class:`MiningParams` from the Knowledge Service model-params record.

    The read targets the real ``GET /domains/{domain}/{recordType}/{recordId}`` route and unwraps
    the RecordResponse ``payload`` envelope, then maps the dotted-key ``payload.params[]`` into the
    typed ``MiningParams`` / ``WindowingParams``. Transient errors are retried with config-driven
    back-off; on exhaustion the call raises :class:`KnowledgeError` so the run fails fast (no
    stale/default thresholds — spec Error handling).
    """

    def __init__(
        self,
        base_url: str,
        *,
        domain: str,
        record_id: str,
        retry_max: int = 5,
        retry_backoff_ms: int = 500,
        timeout: float = 10.0,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._domain = domain
        self._record_id = record_id
        self._retry_max = max(0, retry_max)
        self._retry_backoff_ms = max(0, retry_backoff_ms)
        self._timeout = timeout

    @property
    def domain(self) -> str:
        return self._domain

    @property
    def record_id(self) -> str:
        return self._record_id

    def _record_url(self, record_type: str, record_id: str) -> str:
        """Build the live single-record URL ``/domains/{domain}/{recordType}/{recordId}``.

        The recordId contains slashes (``core-ip/modelParams/pattern-miner``) so it MUST be fully
        percent-encoded (``safe=""``) into a single path segment, or the router 404s.
        """
        return (
            f"{self._base_url}/domains/{self._domain}/{record_type}" f"/{quote(record_id, safe='')}"
        )

    def _get_record_payload(self) -> dict[str, Any]:
        """GET the single record and return the unwrapped ``payload`` map (recurring-bug guard)."""
        url = self._record_url(MODEL_PARAMS_RECORD_TYPE, self._record_id)
        resp = httpx.get(url, timeout=self._timeout)
        resp.raise_for_status()
        body = resp.json()
        if not isinstance(body, dict) or "payload" not in body:
            raise KnowledgeError(
                f"Knowledge record {self._record_id!r} response is not a RecordResponse envelope "
                f"(missing 'payload'); got keys "
                f"{sorted(body) if isinstance(body, dict) else body}"
            )
        payload = body["payload"]
        if not isinstance(payload, dict):
            raise KnowledgeError(f"Knowledge record {self._record_id!r} payload is not an object")
        return payload

    def fetch(self) -> MiningParams:
        """Fetch + parse mining params, retrying transient failures with back-off then failing."""
        last_exc: Exception | None = None
        attempts = self._retry_max + 1
        for attempt in range(attempts):
            try:
                payload = self._get_record_payload()
                params = _parse_mining_params(payload)
                log.info(
                    "mining_params_fetched",
                    record_id=self._record_id,
                    min_support=params.min_support,
                    max_pattern_length=params.max_pattern_length,
                    codebook_version=params.codebook_version,
                )
                return params
            except (httpx.HTTPError, KnowledgeError, KeyError, ValueError, TypeError) as exc:
                last_exc = exc
                log.warning(
                    "mining_params_fetch_failed",
                    attempt=attempt + 1,
                    of=attempts,
                    error=str(exc),
                )
                if attempt < attempts - 1 and self._retry_backoff_ms:
                    time.sleep((self._retry_backoff_ms / 1000.0) * (2**attempt))
        raise KnowledgeError(
            f"mining params could not be fetched from Knowledge after {attempts} attempt(s): "
            f"{last_exc}"
        ) from last_exc


def _parse_profiles(raw: Any) -> dict[str, TempoProfile]:
    """Parse the ``window.adaptive.profiles`` map into ``{class: TempoProfile}``.

    The Knowledge value may be either ``{"fast": 0.5, "slow": 30.0}`` (floor-only, live shape) or
    ``{"fast": {"floor": 0.5, "ceiling": 2.0}}`` (floor+ceiling). Both are accepted.
    """
    if not isinstance(raw, dict):
        return {}
    out: dict[str, TempoProfile] = {}
    for name, value in raw.items():
        if isinstance(value, dict):
            floor = value.get("floor", value.get("floorSeconds"))
            ceiling = value.get("ceiling", value.get("ceilingSeconds"))
            if floor is None:
                continue
            out[str(name)] = TempoProfile(
                name=str(name),
                floor_seconds=float(floor),
                ceiling_seconds=None if ceiling is None else float(ceiling),
            )
        elif isinstance(value, int | float):
            out[str(name)] = TempoProfile(name=str(name), floor_seconds=float(value))
    return out


def _require(m: dict[str, Any], key: str) -> Any:
    if key not in m or m[key] is None:
        raise KnowledgeError(f"Knowledge model-params record is missing required key {key!r}")
    return m[key]


def _parse_grouping_keys(raw: Any) -> tuple[str, ...]:
    """Parse ``anchoring.groupingKeys`` (list or comma-string) -> tuple; else structural default."""
    if isinstance(raw, list) and raw:
        return tuple(str(k) for k in raw)
    if isinstance(raw, str) and raw.strip():
        return tuple(part.strip() for part in raw.split(",") if part.strip())
    return DEFAULT_GROUPING_KEYS


def _parse_anchoring_params(m: dict[str, Any]) -> AnchoringParams:
    """Map the ``anchoring.*`` dotted keys into typed :class:`AnchoringParams`.

    The confidence threshold and the two scorer weights are **required** (fail fast, never a code
    default — spec AC-7/AC-17). Scorer method, tie-break, and grouping keys have structural
    (non-threshold, non-domain) template defaults, applied only when Knowledge does not author them.
    """
    threshold = float(_require(m, KEY_MATCH_CONFIDENCE_THRESHOLD))
    w_order = float(_require(m, KEY_W_ORDER))
    w_jaccard = float(_require(m, KEY_W_JACCARD))
    return AnchoringParams(
        match_confidence_threshold=threshold,
        w_order=w_order,
        w_jaccard=w_jaccard,
        scoring_method=str(m.get(KEY_SCORING_METHOD) or DEFAULT_SCORING_METHOD),
        tie_break=str(m.get(KEY_TIE_BREAK) or DEFAULT_TIE_BREAK),
        grouping_keys=_parse_grouping_keys(m.get(KEY_GROUPING_KEYS)),
    )


def _parse_mining_params(payload: dict[str, Any]) -> MiningParams:
    """Map a model-params ``payload.params[]`` map into the typed :class:`MiningParams`.

    Required keys are pulled from the dotted-key map; a missing required key raises
    :class:`KnowledgeError` (fail fast — never substitute a code default, spec criterion 9). The
    windowing ``maxClosingGapSeconds`` / ``minBurstSamples`` / ``classThresholds`` are optional
    refinements: when absent, ``maxClosingGapSeconds`` falls back to the base gap ceiling derived
    from the Knowledge base gap (still Knowledge-sourced, never a code literal) and
    ``minBurstSamples`` defaults to 2 (the minimum count that yields a single inter-arrival — a
    structural constant, not a tunable threshold).
    """
    m = _params_to_map(payload)

    base_gap = float(_require(m, KEY_BASE_GAP_SECONDS))
    multiplier = float(_require(m, KEY_GAP_MULTIPLIER))
    percentile = float(_require(m, KEY_TEMPO_PERCENTILE))
    profiles = _parse_profiles(m.get(KEY_PROFILES))

    # max closing gap: Knowledge-authored when present; else derive a Knowledge-sourced ceiling from
    # the slowest profile floor or the base gap (never a code literal).
    if m.get(KEY_MAX_CLOSING_GAP_SECONDS) is not None:
        max_closing = float(m[KEY_MAX_CLOSING_GAP_SECONDS])
    else:
        profile_ceiling = max((p.floor_seconds for p in profiles.values()), default=base_gap)
        max_closing = max(profile_ceiling, base_gap)

    class_thresholds_raw = m.get(KEY_CLASS_THRESHOLDS)
    class_thresholds = (
        {str(k): float(v) for k, v in class_thresholds_raw.items()}
        if isinstance(class_thresholds_raw, dict)
        else {}
    )

    # minBurstSamples: 2 is the structural minimum (one inter-arrival) — a definitional constant,
    # not a hard-coded windowing threshold; Knowledge may override it.
    min_burst_samples = int(m.get(KEY_MIN_BURST_SAMPLES, 2))

    windowing = WindowingParams(
        base_gap_seconds=base_gap,
        gap_multiplier=multiplier,
        tempo_percentile=percentile,
        max_closing_gap_seconds=max_closing,
        min_burst_samples=min_burst_samples,
        profiles=profiles,
        class_thresholds=class_thresholds,
    )

    # [BATCH-CAP] Optional operational override of the trail-batch cap. Absent -> None (the env
    # MAX_TRAILS_PER_BATCH default applies). Not required, never a mining threshold.
    raw_cap = m.get(KEY_MAX_TRAILS_PER_BATCH)
    max_trails_per_batch = int(raw_cap) if raw_cap is not None else None

    return MiningParams(
        min_support=float(_require(m, KEY_MIN_SUPPORT)),
        max_pattern_length=int(_require(m, KEY_MAX_PATTERN_LENGTH)),
        max_sequence_count=int(_require(m, KEY_MAX_SEQUENCE_COUNT)),
        windowing=windowing,
        anchoring=_parse_anchoring_params(m),
        codebook_version=str(_require(m, KEY_CODEBOOK_VERSION)),
        max_trails_per_batch=max_trails_per_batch,
    )
