"""Codebook Service client — Stage-2 fault-origin scenarios (domain-knowledge anchoring).

Built against the Codebook Service's **published OpenAPI** (``/openapi.json``, verified live —
never its source) and config-switchable mock/real by env (``CODEBOOK_CLIENT_MODE``). In ``mock``
mode the client still issues real HTTP calls so respx (generated from the collaborator's OpenAPI)
can intercept them in unit tests; in ``real`` mode it points at the live service.

REAL Codebook API (verified live against ``/openapi.json`` and mirrored in the design):

* ``GET /codebooks/active?domain={domain}&snapshotId={snapshotId}`` — resolves the single active
  codebook for a ``(domain, snapshotId)`` pair; **BOTH** query params are required (``snapshotId``
  alone returns 422). Returns ``CodebookMeta {codebookId, snapshotId, domain, ...}``. This is the
  OQ-3 resolution path: the miner already has ``domain`` + ``snapshotId`` at mining time, so it
  resolves the symbolic ``codebookVersion="current"`` to a concrete ``codebookId`` by snapshot; the
  symbolic value is kept verbatim in provenance (``codebookId`` is a runtime detail, not a schema
  field). There is NO ``/api/v1/...`` route.
* ``GET /codebooks/{codebookId}/scenarios`` — returns ``ScenarioListResponse {codebookId, domain,
  scenarios:[{scenarioId, faultOriginObjectId, faultOriginType,
  predictedSymptoms:[{alarmType, managedObjectId}], trailIds:[...]}]}``. The ordered
  ``predictedSymptoms[].alarmType`` list is the canonical fault-origin **symptom chain** used for
  Stage-2 cascade matching.

Transient errors (5xx / transport) are retried with config-driven back-off; on exhaustion the call
raises :class:`CodebookError` so the P2 run **fails fast** — Stage 2 cannot anchor without
scenarios, and mining must NOT proceed unanchored (that re-introduces the global-mining defect the
respec corrects). A 404 on ``/codebooks/active`` (no active codebook for the snapshot) also fails
fast with a clear reason (``no_active_codebook``). No domain literal, scenario id, or alarm type is
hard-coded anywhere here — the client is a generic, per-domain/snapshot template.
"""

from __future__ import annotations

import time
from dataclasses import dataclass
from typing import Any

import httpx

from .logging_setup import get_logger

log = get_logger(__name__)


class CodebookError(RuntimeError):
    """Raised when the active codebook / scenarios cannot be fetched from the Codebook Service."""


class NoActiveCodebookError(CodebookError):
    """Raised when ``GET /codebooks/active`` returns 404 (no active codebook for the snapshot)."""


@dataclass(frozen=True)
class Scenario:
    """A domain fault-origin scenario (the anchoring target) — the transient in-run shape.

    ``symptom_chain`` is the ordered ``predictedSymptoms[].alarmType`` list — the canonical
    fault-origin signature the :class:`~pattern_miner.anchoring.CascadeMatcher` scores cascades
    against. ``scenario_id`` is the anchor identity written to ``provenance.anchorScenarioId``.
    """

    scenario_id: str
    fault_origin_object_id: str
    fault_origin_type: str
    symptom_chain: tuple[str, ...]
    trail_ids: tuple[str, ...]


def _parse_scenario(raw: dict[str, Any]) -> Scenario:
    """Parse one scenario object from the ``ScenarioListResponse.scenarios[]`` array."""
    symptoms = raw.get("predictedSymptoms") or []
    chain = tuple(
        str(s["alarmType"])
        for s in symptoms
        if isinstance(s, dict) and s.get("alarmType") is not None
    )
    return Scenario(
        scenario_id=str(raw["scenarioId"]),
        fault_origin_object_id=str(raw.get("faultOriginObjectId", "")),
        fault_origin_type=str(raw.get("faultOriginType", "")),
        symptom_chain=chain,
        trail_ids=tuple(str(t) for t in (raw.get("trailIds") or [])),
    )


class CodebookClient:
    """Resolves the active codebook and fetches its fault-origin scenarios (Stage 2).

    The active-codebook read targets the real ``GET /codebooks/active?domain=&snapshotId=`` route
    (both params required); the scenarios read targets ``GET /codebooks/{codebookId}/scenarios``.
    Transient errors are retried with config-driven back-off; on exhaustion the call raises
    :class:`CodebookError` so the run fails fast (never unanchored mining — spec Error handling).
    """

    def __init__(
        self,
        base_url: str,
        *,
        retry_max: int = 5,
        retry_backoff_ms: int = 500,
        timeout: float = 10.0,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._retry_max = max(0, retry_max)
        self._retry_backoff_ms = max(0, retry_backoff_ms)
        self._timeout = timeout

    def _active_url(self) -> str:
        """Active-codebook resolution URL (no ``/api/v1`` prefix; matches the published spec)."""
        return f"{self._base_url}/codebooks/active"

    def _scenarios_url(self, codebook_id: str) -> str:
        """The scenarios URL for a resolved codebook (matches the published spec)."""
        return f"{self._base_url}/codebooks/{codebook_id}/scenarios"

    def _get(self, url: str, *, params: dict[str, str]) -> httpx.Response:
        """GET with config-driven retry/back-off; 404 raised immediately (not retried)."""
        last_exc: Exception | None = None
        attempts = self._retry_max + 1
        for attempt in range(attempts):
            try:
                resp = httpx.get(url, params=params, timeout=self._timeout)
                if resp.status_code == 404:
                    resp.raise_for_status()
                resp.raise_for_status()
                return resp
            except httpx.HTTPStatusError as exc:
                # 404 is a definitive answer (no active codebook / unknown codebook) — do not retry.
                if exc.response is not None and exc.response.status_code == 404:
                    raise
                last_exc = exc
                log.warning(
                    "codebook_fetch_failed",
                    url=url,
                    attempt=attempt + 1,
                    of=attempts,
                    error=str(exc),
                )
            except httpx.HTTPError as exc:
                last_exc = exc
                log.warning(
                    "codebook_fetch_failed",
                    url=url,
                    attempt=attempt + 1,
                    of=attempts,
                    error=str(exc),
                )
            if attempt < attempts - 1 and self._retry_backoff_ms:
                time.sleep((self._retry_backoff_ms / 1000.0) * (2**attempt))
        raise CodebookError(
            f"Codebook GET {url} failed after {attempts} attempt(s): {last_exc}"
        ) from last_exc

    def resolve_codebook_id(self, domain: str, snapshot_id: str) -> str:
        """Resolve the active codebook id for ``(domain, snapshotId)`` (OQ-3 path).

        Issues ``GET /codebooks/active?domain=&snapshotId=`` (both required) and returns the
        concrete ``codebookId``. A 404 raises :class:`NoActiveCodebookError` (fail fast — the run
        retries once the codebook is compiled; never falls back to unanchored global mining).
        """
        try:
            resp = self._get(
                self._active_url(), params={"domain": domain, "snapshotId": snapshot_id}
            )
        except httpx.HTTPStatusError as exc:
            raise NoActiveCodebookError(
                f"no active codebook for domain={domain} snapshotId={snapshot_id}"
            ) from exc
        body = resp.json()
        codebook_id = body.get("codebookId") if isinstance(body, dict) else None
        if not codebook_id:
            raise CodebookError(
                f"active-codebook response missing 'codebookId' for domain={domain} "
                f"snapshotId={snapshot_id}: {body}"
            )
        log.info(
            "codebook_resolved",
            domain=domain,
            snapshot_id=snapshot_id,
            codebook_id=str(codebook_id),
        )
        return str(codebook_id)

    def get_scenarios(self, codebook_id: str) -> list[Scenario]:
        """Fetch and parse the fault-origin scenarios for a resolved codebook.

        Issues ``GET /codebooks/{codebookId}/scenarios`` and parses the ``ScenarioListResponse``
        into typed :class:`Scenario`s, each with its ordered ``symptom_chain``.
        """
        try:
            resp = self._get(self._scenarios_url(codebook_id), params={})
        except httpx.HTTPStatusError as exc:
            raise CodebookError(f"unknown codebookId {codebook_id}") from exc
        body = resp.json()
        raw_scenarios = body.get("scenarios") if isinstance(body, dict) else None
        if not isinstance(raw_scenarios, list):
            raise CodebookError(
                f"scenarios response for {codebook_id} missing 'scenarios' array: {body}"
            )
        scenarios = [_parse_scenario(s) for s in raw_scenarios if isinstance(s, dict)]
        log.info("codebook_scenarios_fetched", codebook_id=codebook_id, count=len(scenarios))
        return scenarios

    def scenarios_for(self, domain: str, snapshot_id: str) -> list[Scenario]:
        """Resolve the active codebook then fetch its scenarios (the once-per-run convenience)."""
        codebook_id = self.resolve_codebook_id(domain, snapshot_id)
        return self.get_scenarios(codebook_id)
