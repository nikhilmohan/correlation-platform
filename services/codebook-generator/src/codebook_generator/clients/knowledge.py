"""Knowledge Service client — three domain-scoped integration points.

Built against the frozen Knowledge ``recordType``-generic routes:
- ``GET /domains/{domain}/fault-origin-types``      (``knowledge-fault-origins``)
- ``GET /domains/{domain}/propagation-templates``   (``knowledge-propagation-templates``)
- ``GET /domains/{domain}/alarm-type-vocabulary``   (``knowledge-alarm-type-vocabulary``)

All pass ``domain`` as the path param (spec criterion 14). Responses are cached per
``(domain, knowledgeVersion)`` via the in-process cache; ``knowledge.updated`` invalidates a
domain's cache. Nothing is authored here — read-only.
"""

from __future__ import annotations

import httpx

from ..models import FaultOriginType, PropagationTemplate
from .base import request_with_retry


class KnowledgeClient:
    """Reads domain-scoped fault-origins, templates, and alarm-type vocabulary."""

    def __init__(
        self,
        *,
        fault_origins_base_url: str,
        propagation_templates_base_url: str,
        alarm_type_vocabulary_base_url: str,
        client: httpx.Client,
        max_retries: int,
        backoff_ms: int,
    ) -> None:
        self._fo_url = fault_origins_base_url.rstrip("/")
        self._pt_url = propagation_templates_base_url.rstrip("/")
        self._av_url = alarm_type_vocabulary_base_url.rstrip("/")
        self._client = client
        self._max_retries = max_retries
        self._backoff_ms = backoff_ms

    def _get(self, base_url: str, path: str) -> httpx.Response:
        return request_with_retry(
            lambda: self._client.get(f"{base_url}{path}"),
            max_retries=self._max_retries,
            backoff_ms=self._backoff_ms,
        )

    def get_fault_origin_types(self, domain: str) -> list[FaultOriginType]:
        """``GET /domains/{domain}/fault-origin-types`` -> fault-origin type records."""
        resp = self._get(self._fo_url, f"/domains/{domain}/fault-origin-types")
        return [FaultOriginType.model_validate(item) for item in _records(resp.json())]

    def get_propagation_templates(self, domain: str) -> list[PropagationTemplate]:
        """``GET /domains/{domain}/propagation-templates`` -> propagation templates."""
        resp = self._get(self._pt_url, f"/domains/{domain}/propagation-templates")
        return [PropagationTemplate.model_validate(item) for item in _records(resp.json())]

    def get_alarm_type_vocabulary(self, domain: str) -> list[str]:
        """``GET /domains/{domain}/alarm-type-vocabulary`` -> the ``alarmTypes`` token set."""
        resp = self._get(self._av_url, f"/domains/{domain}/alarm-type-vocabulary")
        body = resp.json()
        if isinstance(body, dict):
            return list(body.get("alarmTypes", []))
        return list(body)


def _records(body: object) -> list[dict]:
    """Normalize a list-or-{records:[...]} response shape into a list of records."""
    if isinstance(body, dict):
        records = body.get("records", body.get("items", []))
        return list(records)
    if isinstance(body, list):
        return list(body)
    return []
