"""Integration-point client mechanics tests (retry/backoff + response shaping).

Exercises :func:`codebook_generator.clients.base.request_with_retry` (bounded retry on 5xx /
transport errors, 4xx unrecoverable) and the client response-shaping helpers (Knowledge
records normalization, Trail Builder ``getTrail``) — the spec's retry-with-backoff error
handling and the frozen producer shapes, with an injected no-op sleep.
"""

from __future__ import annotations

import httpx
import pytest

from codebook_generator.clients.base import IntegrationError, request_with_retry
from codebook_generator.clients.knowledge import KnowledgeClient, _payload, _records
from codebook_generator.clients.trail_builder import TrailBuilderClient
from codebook_generator.models import FaultOriginType, PropagationTemplate


def _client(handler) -> httpx.Client:  # noqa: ANN001
    return httpx.Client(transport=httpx.MockTransport(handler))


def _knowledge_client(handler) -> KnowledgeClient:  # noqa: ANN001
    return KnowledgeClient(
        fault_origins_base_url="http://k.test",
        propagation_templates_base_url="http://k.test",
        alarm_type_vocabulary_base_url="http://k.test",
        client=_client(handler),
        max_retries=0,
        backoff_ms=1,
    )


# The REAL Knowledge ``RecordResponse`` envelope shape (per services/knowledge/openapi.json):
# {domain, recordType, recordId, version, isCurrent, payload:{...domain fields...}}.
# The codebook-generator domain models live UNDER ``payload``, never at the envelope top level.
_FAULT_ORIGIN_RECORD = {
    "domain": "core-ip",
    "recordType": "faultOriginType",
    "recordId": "fo-fiberspan-fibercut",
    "version": "v1",
    "isCurrent": True,
    "payload": {
        "objectType": "FiberSpan",
        "originAlarmType": "FiberCut",
        "description": "A cut in a fiber span",
    },
}

_PROPAGATION_RECORD = {
    "domain": "core-ip",
    "recordType": "propagationTemplate",
    "recordId": "pt-hosts-fibercut-los",
    "version": "v1",
    "isCurrent": True,
    "payload": {
        "edgeType": "HOSTS",
        "trigger": {"objectType": "FiberSpan", "alarmType": "FiberCut"},
        "effect": {"objectType": "OpticalPort", "alarmType": "LOS"},
        "traversal": {"direction": "downstream", "cardinality": "one-to-many"},
        "ordering": 1,
    },
}


def test_retry_succeeds_after_transient_5xx() -> None:
    """A 5xx then a 200 succeeds within the retry budget."""
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        return httpx.Response(503 if calls["n"] == 1 else 200, json={"ok": True})

    client = _client(handler)
    resp = request_with_retry(
        lambda: client.get("http://x.test/y"),
        max_retries=2,
        backoff_ms=1,
        sleep=lambda _s: None,
    )
    assert resp.status_code == 200
    assert calls["n"] == 2


def test_4xx_is_unrecoverable() -> None:
    """A 4xx raises IntegrationError immediately (no retry)."""
    client = _client(lambda r: httpx.Response(404))
    with pytest.raises(IntegrationError):
        request_with_retry(
            lambda: client.get("http://x.test/y"),
            max_retries=3,
            backoff_ms=1,
            sleep=lambda _s: None,
        )


def test_5xx_exhaustion_raises() -> None:
    """Persistent 5xx raises IntegrationError after exhausting retries."""
    client = _client(lambda r: httpx.Response(500))
    with pytest.raises(IntegrationError):
        request_with_retry(
            lambda: client.get("http://x.test/y"),
            max_retries=1,
            backoff_ms=1,
            sleep=lambda _s: None,
        )


def test_transport_error_exhaustion_raises() -> None:
    """A persistent transport error raises IntegrationError after retries."""

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    client = _client(handler)
    with pytest.raises(IntegrationError):
        request_with_retry(
            lambda: client.get("http://x.test/y"),
            max_retries=1,
            backoff_ms=1,
            sleep=lambda _s: None,
        )


def test_records_normalizes_list_and_wrapped_shapes() -> None:
    """_records accepts a bare list, {records:[...]}, {items:[...]}, and unknown -> []."""
    assert _records([{"a": 1}]) == [{"a": 1}]
    assert _records({"records": [{"a": 1}]}) == [{"a": 1}]
    assert _records({"items": [{"b": 2}]}) == [{"b": 2}]
    assert _records(42) == []


def test_knowledge_vocabulary_accepts_bare_list_shape() -> None:
    """get_alarm_type_vocabulary handles both {alarmTypes:[...]} and a bare list."""
    client = _client(lambda r: httpx.Response(200, json=["A", "B"]))
    kc = KnowledgeClient(
        fault_origins_base_url="http://k.test",
        propagation_templates_base_url="http://k.test",
        alarm_type_vocabulary_base_url="http://k.test",
        client=client,
        max_retries=0,
        backoff_ms=1,
    )
    assert kc.get_alarm_type_vocabulary("core-ip") == ["A", "B"]


# --- Regression #224: parse the Knowledge record ENVELOPE's .payload, not the envelope. ---


def test_get_fault_origin_types_parses_record_payload() -> None:
    """#224: a real fault-origin RecordResponse envelope decodes into a FaultOriginType.

    The model fields (objectType/originAlarmType/description) live under ``payload``.
    """
    kc = _knowledge_client(lambda r: httpx.Response(200, json={"records": [_FAULT_ORIGIN_RECORD]}))
    result = kc.get_fault_origin_types("core-ip")
    assert result == [
        FaultOriginType(
            objectType="FiberSpan",
            originAlarmType="FiberCut",
            description="A cut in a fiber span",
        )
    ]


def test_get_propagation_templates_parses_record_payload() -> None:
    """#224: a real propagation-template RecordResponse envelope decodes into a template."""
    kc = _knowledge_client(lambda r: httpx.Response(200, json={"records": [_PROPAGATION_RECORD]}))
    result = kc.get_propagation_templates("core-ip")
    assert len(result) == 1
    template = result[0]
    assert isinstance(template, PropagationTemplate)
    assert template.edgeType == "HOSTS"
    assert template.trigger.objectType == "FiberSpan"
    assert template.trigger.alarmType == "FiberCut"
    assert template.effect.objectType == "OpticalPort"
    assert template.effect.alarmType == "LOS"


def test_fault_origin_envelope_top_level_is_not_a_valid_model() -> None:
    """Regression guard: validating the bare ENVELOPE (the old buggy behavior) must FAIL.

    A revert to ``FaultOriginType.model_validate(item)`` would parse the envelope (which has
    no ``objectType`` at the top level) and raise — this asserts that contract so a revert
    is caught. (Mirrors trail-builder #209's contract-pin style.)
    """
    with pytest.raises(Exception):  # noqa: B017,PT011 - pydantic ValidationError
        FaultOriginType.model_validate(_FAULT_ORIGIN_RECORD)


def test_propagation_template_envelope_top_level_is_not_a_valid_model() -> None:
    """Regression guard: the bare propagation ENVELOPE must FAIL model validation."""
    with pytest.raises(Exception):  # noqa: B017,PT011 - pydantic ValidationError
        PropagationTemplate.model_validate(_PROPAGATION_RECORD)


def test_record_without_payload_key_fails_clearly() -> None:
    """A malformed record missing ``payload`` must fail loudly, not silently pass."""
    malformed = {"recordType": "faultOriginType", "recordId": "x"}  # no payload
    kc = _knowledge_client(lambda r: httpx.Response(200, json={"records": [malformed]}))
    with pytest.raises(Exception):  # noqa: B017,PT011
        kc.get_fault_origin_types("core-ip")


def test_payload_extracts_payload_and_rejects_missing() -> None:
    """_payload returns the envelope's payload mapping and rejects a missing/invalid one."""
    assert _payload({"payload": {"objectType": "X"}}) == {"objectType": "X"}
    with pytest.raises(ValueError, match="payload"):
        _payload({"recordType": "faultOriginType"})
    with pytest.raises(ValueError, match="payload"):
        _payload({"payload": ["not", "a", "mapping"]})


def test_trail_builder_get_trail_returns_raw() -> None:
    """getTrail returns the raw trail detail body."""
    client = _client(lambda r: httpx.Response(200, json={"trailId": "T1", "members": ["a"]}))
    tb = TrailBuilderClient(base_url="http://tb.test", client=client, max_retries=0, backoff_ms=1)
    assert tb.get_trail("T1") == {"trailId": "T1", "members": ["a"]}
