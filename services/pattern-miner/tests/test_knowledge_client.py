"""Knowledge mining-params client — RecordResponse .payload envelope + real path/shape.

The respx mocks mirror the REAL cp-knowledge contract (verified live against the pattern-miner
record): the single-record route ``GET /domains/{domain}/{recordType}/{recordId}`` with a
URL-encoded recordId, the kebab-case ``model-params`` recordType, and the ``payload.params`` array
of ``{key, type, value}`` entries (NOT flat top-level fields). This is the enveloped shape.
"""

from __future__ import annotations

from urllib.parse import quote

import httpx
import pytest
import respx

from pattern_miner.knowledge import (
    MODEL_PARAMS_RECORD_TYPE,
    KnowledgeError,
    MiningParamsClient,
)

KNOWLEDGE_URL = "http://knowledge.test"
DOMAIN = "core-ip"
RECORD_ID = "core-ip/modelParams/pattern-miner"
MODEL_PARAMS_PATH = f"/domains/{DOMAIN}/{MODEL_PARAMS_RECORD_TYPE}/{quote(RECORD_ID, safe='')}"


def _record_envelope(payload: dict) -> dict:
    """The Knowledge read API RecordResponse ENVELOPE (content under .payload)."""
    return {
        "domain": DOMAIN,
        "recordType": "modelParams",
        "recordId": RECORD_ID,
        "version": "v1",
        "isCurrent": True,
        "payload": payload,
    }


def _live_payload() -> dict:
    """The EXACT payload shape served by the live cp-knowledge pattern-miner record."""
    return {
        "paramSet": "pattern-miner",
        "params": [
            {
                "key": "prefixspan.minSupport",
                "type": "number",
                "value": 0.3,
                "min": 0.0,
                "max": 1.0,
            },
            {"key": "prefixspan.maxPatternLength", "type": "integer", "value": 10},
            {"key": "prefixspan.maxSequenceCount", "type": "integer", "value": 1000},
            {"key": "window.adaptive.baseGapSeconds", "type": "number", "value": 5.0, "unit": "s"},
            {"key": "window.adaptive.gapMultiplier", "type": "number", "value": 3.0},
            {"key": "window.adaptive.tempoPercentile", "type": "number", "value": 95.0},
            {
                "key": "window.adaptive.profiles",
                "type": "object",
                "value": {"fast": 0.5, "slow": 30.0, "default": 5.0},
            },
            {
                "key": "anchoring.matchConfidenceThreshold",
                "type": "number",
                "value": 0.6,
                "min": 0.0,
                "max": 1.0,
            },
            {"key": "anchoring.weights.order", "type": "number", "value": 0.7},
            {"key": "anchoring.weights.jaccard", "type": "number", "value": 0.3},
            {"key": "codebookVersion", "type": "string", "value": "current"},
            {"key": "sample.maxAlarms", "type": "integer", "value": 10},
        ],
    }


def _client() -> MiningParamsClient:
    return MiningParamsClient(
        KNOWLEDGE_URL, domain=DOMAIN, record_id=RECORD_ID, retry_max=1, retry_backoff_ms=0
    )


@respx.mock
def test_fetch_parses_live_enveloped_shape():
    """The client unwraps .payload.params[] and maps the dotted keys into typed MiningParams."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(200, json=_record_envelope(_live_payload()))
    )
    params = _client().fetch()
    assert params.min_support == 0.3
    assert params.max_pattern_length == 10
    assert params.max_sequence_count == 1000
    assert params.codebook_version == "current"
    w = params.windowing
    assert w.base_gap_seconds == 5.0
    assert w.gap_multiplier == 3.0
    assert w.tempo_percentile == 95.0
    assert w.profiles["fast"].floor_seconds == 0.5
    assert w.profiles["slow"].floor_seconds == 30.0
    # Stage-2 anchoring block parsed from the same record.
    assert params.anchoring.match_confidence_threshold == 0.6
    assert params.anchoring.w_order == 0.7
    assert params.anchoring.w_jaccard == 0.3
    # [SAMPLE] AC-26: the sample cap K is read from the same record's ``sample.maxAlarms`` key.
    assert params.sample_max_alarms == 10


@respx.mock
def test_fetch_sample_max_alarms_sourced_from_knowledge_no_code_change():
    """AC-23/AC-26: changing the Knowledge ``sample.maxAlarms`` changes K with no code change."""
    payload = _live_payload()
    for entry in payload["params"]:
        if entry["key"] == "sample.maxAlarms":
            entry["value"] = 3
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(200, json=_record_envelope(payload))
    )
    assert _client().fetch().sample_max_alarms == 3


@respx.mock
def test_fetch_missing_sample_max_alarms_raises():
    """AC-26: ``sample.maxAlarms`` is required (no code default) — a missing key fails fast."""
    payload = _live_payload()
    payload["params"] = [p for p in payload["params"] if p["key"] != "sample.maxAlarms"]
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(200, json=_record_envelope(payload))
    )
    with pytest.raises(KnowledgeError):
        _client().fetch()


@respx.mock
def test_fetch_requires_payload_envelope_not_flat():
    """RECURRING-BUG GUARD: a flat (non-enveloped) body is rejected — content must be .payload."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(200, json=_live_payload())  # flat, no envelope
    )
    with pytest.raises(KnowledgeError):
        _client().fetch()


@respx.mock
def test_fetch_missing_required_key_raises():
    """A model-params record missing a required dotted key fails fast (no code default)."""
    payload = _live_payload()
    payload["params"] = [p for p in payload["params"] if p["key"] != "prefixspan.minSupport"]
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(200, json=_record_envelope(payload))
    )
    with pytest.raises(KnowledgeError):
        _client().fetch()


@respx.mock
def test_fetch_retries_then_fails_on_persistent_error():
    """Transient errors are retried; on exhaustion the fetch raises (fail fast, no stale params)."""
    route = respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(return_value=httpx.Response(503))
    with pytest.raises(KnowledgeError):
        _client().fetch()
    assert route.call_count == 2  # retry_max=1 -> 2 attempts total


@respx.mock
def test_fetch_recovers_after_transient_error():
    """A transient failure followed by success returns the parsed params (retry works)."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        side_effect=[
            httpx.Response(503),
            httpx.Response(200, json=_record_envelope(_live_payload())),
        ]
    )
    params = _client().fetch()
    assert params.min_support == 0.3
