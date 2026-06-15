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
        """``GET /domains/{domain}/fault-origin-types`` -> fault-origin type records.

        Each item is a Knowledge ``RecordResponse`` envelope (``{recordType, recordId,
        payload:{...}}``); the model fields live under ``payload`` (see #224).
        """
        resp = self._get(self._fo_url, f"/domains/{domain}/fault-origin-types")
        return [FaultOriginType.model_validate(_payload(item)) for item in _records(resp.json())]

    def get_propagation_templates(self, domain: str) -> list[PropagationTemplate]:
        """``GET /domains/{domain}/propagation-templates`` -> propagation templates.

        Each item is a Knowledge ``RecordResponse`` envelope; the template fields live under
        ``payload`` (see #224).
        """
        resp = self._get(self._pt_url, f"/domains/{domain}/propagation-templates")
        return [
            PropagationTemplate.model_validate(_payload(item)) for item in _records(resp.json())
        ]

    def get_alarm_type_vocabulary(self, domain: str) -> list[str]:
        """``GET /domains/{domain}/alarm-type-vocabulary`` -> the ``alarmTypes`` token set.

        Served via Knowledge's generic ``recordType`` route as a ``RecordResponse`` envelope
        (or a LIST of them): ``{recordType:"alarmTypeVocabulary", recordId, version, isCurrent,
        payload:{alarmTypes:[...]}}``. The tokens live under ``payload.alarmTypes`` — NOT at the
        envelope top level (#233, same envelope-vs-payload class as #224). Selects the current
        record (``isCurrent`` true, else the sole record) and returns its ``alarmTypes`` as a
        list of hashable token strings so downstream ``set(vocabulary)`` works.
        """
        resp = self._get(self._av_url, f"/domains/{domain}/alarm-type-vocabulary")
        record = _current_record(_records(resp.json()))
        return [str(token) for token in _payload(record).get("alarmTypes", [])]


def _records(body: object) -> list[dict]:
    """Normalize a list-or-{records:[...]} response shape into a list of records."""
    if isinstance(body, dict):
        records = body.get("records", body.get("items", []))
        return list(records)
    if isinstance(body, list):
        return list(body)
    return []


def _current_record(records: list[dict]) -> dict:
    """Select the current record from a list of Knowledge ``RecordResponse`` envelopes.

    The generic record route returns a LIST of envelopes; pick the one flagged
    ``isCurrent`` true, else (the common single-record case) the sole record. An empty list
    is malformed for a seeded record and fails clearly rather than yielding an empty token set
    that would silently weaken downstream vocabulary validation (#233).
    """
    if not records:
        raise ValueError("Knowledge returned no alarm-type-vocabulary record")
    for record in records:
        if isinstance(record, dict) and record.get("isCurrent") is True:
            return record
    return records[0]


def _payload(record: object) -> dict:
    """Return a Knowledge ``RecordResponse`` envelope's ``payload`` mapping.

    The Knowledge frozen contract wraps domain fields in a record envelope
    (``{recordType, recordId, payload:{...}}``); the codebook-generator models are decoded
    from ``payload``. A record without a mapping ``payload`` is malformed and fails clearly
    rather than being silently dropped (see #224).
    """
    if isinstance(record, dict) and isinstance(record.get("payload"), dict):
        return record["payload"]
    raise ValueError(f"Knowledge record is missing a mapping 'payload': {record!r}")
