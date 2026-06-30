"""Knowledge client + param refresh (AC-8 path) — RecordResponse .payload envelope guard.

The mocks here mirror the REAL cp-knowledge contract (verified live): the single-record route
``GET /domains/{domain}/{recordType}/{recordId}`` with a URL-encoded recordId, the kebab-case
``model-params`` recordType, and the ``payload.params`` array of ``{key, value}`` entries (NOT
flat top-level fields). Feature config is derived from the same model-params record.
"""

from __future__ import annotations

from urllib.parse import quote

import httpx
import respx

from noise_filter.clients import (
    MODEL_PARAMS_RECORD_ID,
    MODEL_PARAMS_RECORD_TYPE,
    KnowledgeClient,
)
from noise_filter.config import FeatureConfig, FeatureSettings, ModelParams, ParamStore
from noise_filter.metrics import Metrics
from noise_filter.refresh import ParamLoader

KNOWLEDGE_URL = "http://knowledge.test"

# Real single-record route: /domains/core-ip/model-params/<url-encoded recordId>.
MODEL_PARAMS_PATH = (
    f"/domains/core-ip/{MODEL_PARAMS_RECORD_TYPE}/{quote(MODEL_PARAMS_RECORD_ID, safe='')}"
)


def _record_envelope(record_id: str, payload: dict) -> dict:
    """The Knowledge read API RecordResponse ENVELOPE (content under .payload)."""
    return {
        "recordId": record_id,
        "recordType": "modelParams",
        "version": "v1",
        "domain": "core-ip",
        "isCurrent": True,
        "payload": payload,
    }


def _params_payload(
    eps: float,
    min_samples: int,
    window: int,
    *,
    attribute_keys: list[str] | None = None,
    hop_enabled: bool = False,
    extra: list[dict] | None = None,
) -> dict:
    """Build a REAL model-params payload: a `params` array of {key, value} entries."""
    params: list[dict] = [
        {"key": "dbscan.epsilon", "value": eps},
        {"key": "dbscan.minSamples", "value": min_samples},
        {"key": "window.sizeSeconds", "value": window},
        {"key": "feature.attributeKeys", "value": attribute_keys or []},
        {"key": "feature.hopDistance.enabled", "value": hop_enabled},
    ]
    if extra:
        params.extend(extra)
    return {"params": params, "paramSet": "noise-filter"}


@respx.mock
def test_knowledge_reads_unwrap_record_response_payload():
    """RECURRING-BUG GUARD: model-params + feature-config are read from .payload, NOT top level."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(
            200,
            json=_record_envelope(
                MODEL_PARAMS_RECORD_ID,
                _params_payload(
                    1.25,
                    4,
                    300,
                    attribute_keys=["equipmentType"],
                    hop_enabled=True,
                    extra=[{"key": "feature.hopTraversalMaxDepth", "value": 6}],
                ),
            ),
        )
    )

    client = KnowledgeClient(KNOWLEDGE_URL)
    params = client.fetch_model_params()
    assert params.eps == 1.25 and params.min_samples == 4 and params.window_size_seconds == 300

    features = client.fetch_feature_config()
    assert features.attribute_keys == ("equipmentType",)
    assert features.hop_distance_enabled is True
    assert features.hop_traversal_max_depth == 6


@respx.mock
def test_feature_config_reads_encoding_knobs_from_knowledge():
    """M1: timeScaleSeconds + categoricalWeight are Knowledge-sourced (not code literals)."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(
            200,
            json=_record_envelope(
                MODEL_PARAMS_RECORD_ID,
                _params_payload(
                    0.5,
                    3,
                    120,
                    extra=[
                        {"key": "feature.timeScaleSeconds", "value": 30.0},
                        {"key": "feature.categoricalWeight", "value": 0.7},
                    ],
                ),
            ),
        )
    )
    features = KnowledgeClient(KNOWLEDGE_URL).fetch_feature_config()
    assert features.time_scale_seconds == 30.0
    assert features.categorical_weight == 0.7


@respx.mock
def test_feature_config_encoding_knobs_fall_back_when_absent():
    """When Knowledge omits the encoding knobs, the documented fallback defaults apply."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(
            200,
            json=_record_envelope(MODEL_PARAMS_RECORD_ID, _params_payload(0.5, 3, 120)),
        )
    )
    features = KnowledgeClient(KNOWLEDGE_URL).fetch_feature_config()
    assert features.time_scale_seconds == FeatureSettings.fallback().time_scale_seconds
    assert features.categorical_weight == FeatureSettings.fallback().categorical_weight


@respx.mock
def test_feature_config_falls_back_when_record_unavailable():
    """If the model-params record is unreachable, feature config degrades to documented defaults."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(return_value=httpx.Response(500))
    features = KnowledgeClient(KNOWLEDGE_URL).fetch_feature_config()
    assert features == FeatureSettings.fallback()


@respx.mock
def test_knowledge_read_rejects_non_envelope_response():
    """A flat (non-envelope) response is rejected — guards against the recurring flat-shape bug."""
    respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}").mock(
        return_value=httpx.Response(
            200, json={"params": [{"key": "dbscan.epsilon", "value": 0.5}]}  # flat, no .payload
        )
    )
    client = KnowledgeClient(KNOWLEDGE_URL)
    try:
        client.fetch_model_params()
        raise AssertionError("expected ValueError for non-envelope response")
    except ValueError as exc:
        assert "envelope" in str(exc)


@respx.mock
def test_param_loader_atomic_swap_on_refresh():
    """DA-8 / AC-8: ParamLoader.handle_knowledge_updated re-fetches + atomically swaps params."""
    payload_v1 = _params_payload(0.5, 3, 120)
    payload_v2 = _params_payload(2.0, 2, 300)

    mp_route = respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}")
    mp_route.side_effect = [
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, payload_v1)),
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, payload_v1)),  # fc read
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, payload_v2)),
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, payload_v2)),  # fc read
    ]

    param_store = ParamStore(ModelParams.fallback())
    feature_config = FeatureConfig(FeatureSettings.fallback())
    metrics = Metrics()
    loader = ParamLoader(
        KnowledgeClient(KNOWLEDGE_URL), param_store, feature_config, metrics=metrics
    )

    loader.load()
    assert param_store.get().eps == 0.5
    ok = loader.handle_knowledge_updated()
    assert ok is True
    assert param_store.get().eps == 2.0  # hot-swapped, no restart


@respx.mock
def test_knowledge_refresh_failure_keeps_last_good():
    """EH-6: a refresh failure keeps the last-good params and counts the failure."""
    good = _params_payload(0.7, 3, 120)
    mp_route = respx.get(f"{KNOWLEDGE_URL}{MODEL_PARAMS_PATH}")
    mp_route.side_effect = [
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, good)),  # load mp
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, good)),  # load fc
        httpx.Response(500),  # refresh mp fails
    ]
    param_store = ParamStore(ModelParams.fallback())
    feature_config = FeatureConfig(FeatureSettings.fallback())
    metrics = Metrics()
    loader = ParamLoader(
        KnowledgeClient(KNOWLEDGE_URL), param_store, feature_config, metrics=metrics
    )
    loader.load()
    assert param_store.get().eps == 0.7
    ok = loader.handle_knowledge_updated()
    assert ok is False
    assert param_store.get().eps == 0.7  # last-good retained
    assert metrics.knowledge_refresh_failures._value.get() == 1
