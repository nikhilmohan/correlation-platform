"""OpenAPI contract drift-guard (spec: published OpenAPI 3.1 is the surface contract).

Asserts the checked-in ``services/codebook-generator/openapi.json`` is byte-identical to the
document the running FastAPI app would publish at ``/openapi.json``. A diff means the HTTP
surface drifted from the checked-in contract — a contract change requiring regeneration
(``python scripts/dump_openapi.py``) + review.
"""

from __future__ import annotations

import json
from pathlib import Path

from scripts.dump_openapi import render_openapi

_OPENAPI_PATH = Path(__file__).resolve().parents[1] / "openapi.json"


def test_checked_in_openapi_matches_live_app() -> None:
    """The checked-in openapi.json equals the app's live document (no drift)."""
    live = json.dumps(render_openapi(), indent=2, sort_keys=True) + "\n"
    on_disk = _OPENAPI_PATH.read_text()
    assert on_disk == live, "openapi.json drifted; regenerate with scripts/dump_openapi.py"


def test_openapi_is_3_1_and_publishes_all_endpoints() -> None:
    """The spec is OpenAPI 3.1 and publishes every contracted read endpoint."""
    doc = render_openapi()
    assert doc["openapi"].startswith("3.1")
    paths = set(doc["paths"])
    assert {
        "/health",
        "/metrics",
        "/codebooks",
        "/codebooks/active",
        "/codebooks/{codebookId}",
        "/codebooks/{codebookId}/scenarios",
        "/codebooks/{codebookId}/scenarios/{scenarioId}",
        "/codebooks/{codebookId}/trail-signatures",
    } <= paths
