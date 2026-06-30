"""Knowledge client + param refresh (AC-8 path) — RecordResponse .payload envelope guard."""

from __future__ import annotations

import httpx
import respx

from noise_filter.clients import (
    FEATURE_CONFIG_RECORD_ID,
    MODEL_PARAMS_RECORD_ID,
    KnowledgeClient,
)
from noise_filter.config import FeatureConfig, FeatureSettings, ModelParams, ParamStore
from noise_filter.metrics import Metrics
from noise_filter.refresh import ParamLoader

KNOWLEDGE_URL = "http://knowledge.test"


def _record_envelope(record_id: str, payload: dict) -> dict:
    """The Knowledge read API RecordResponse ENVELOPE (content under .payload)."""
    return {
        "recordId": record_id,
        "recordType": "modelParams",
        "version": "v1",
        "domain": "core-ip",
        "payload": payload,
    }


@respx.mock
def test_knowledge_reads_unwrap_record_response_payload():
    """RECURRING-BUG GUARD: model-params + feature-config are read from .payload, NOT top level."""
    respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{MODEL_PARAMS_RECORD_ID}").mock(
        return_value=httpx.Response(
            200,
            json=_record_envelope(
                MODEL_PARAMS_RECORD_ID,
                {"eps": 1.25, "minSamples": 4, "windowSize": 300, "algorithm": "dbscan"},
            ),
        )
    )
    respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{FEATURE_CONFIG_RECORD_ID}").mock(
        return_value=httpx.Response(
            200,
            json=_record_envelope(
                FEATURE_CONFIG_RECORD_ID,
                {
                    "attributeKeys": ["equipmentType"],
                    "hopDistanceEnabled": True,
                    "hopTraversalMaxDepth": 6,
                },
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
def test_knowledge_read_rejects_non_envelope_response():
    """A flat (non-envelope) response is rejected — guards against the recurring flat-shape bug."""
    respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{MODEL_PARAMS_RECORD_ID}").mock(
        return_value=httpx.Response(
            200, json={"eps": 0.5, "minSamples": 3, "windowSize": 120}  # flat, no .payload
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
    payload_v1 = {"eps": 0.5, "minSamples": 3, "windowSize": 120, "algorithm": "dbscan"}
    payload_v2 = {"eps": 2.0, "minSamples": 2, "windowSize": 300, "algorithm": "dbscan"}
    fc_payload = {"attributeKeys": [], "hopDistanceEnabled": False}

    mp_route = respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{MODEL_PARAMS_RECORD_ID}")
    mp_route.side_effect = [
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, payload_v1)),
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, payload_v2)),
    ]
    respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{FEATURE_CONFIG_RECORD_ID}").mock(
        return_value=httpx.Response(
            200, json=_record_envelope(FEATURE_CONFIG_RECORD_ID, fc_payload)
        )
    )

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
    good = {"eps": 0.7, "minSamples": 3, "windowSize": 120, "algorithm": "dbscan"}
    fc = {"attributeKeys": [], "hopDistanceEnabled": False}
    mp_route = respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{MODEL_PARAMS_RECORD_ID}")
    mp_route.side_effect = [
        httpx.Response(200, json=_record_envelope(MODEL_PARAMS_RECORD_ID, good)),
        httpx.Response(500),  # refresh fails
    ]
    respx.get(f"{KNOWLEDGE_URL}/api/v1/records/{FEATURE_CONFIG_RECORD_ID}").mock(
        return_value=httpx.Response(200, json=_record_envelope(FEATURE_CONFIG_RECORD_ID, fc))
    )
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
