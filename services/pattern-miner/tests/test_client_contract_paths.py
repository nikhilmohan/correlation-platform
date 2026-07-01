"""Contract guard: the Knowledge client builds a URL that matches the REAL cp-knowledge OpenAPI.

WHY THIS EXISTS
---------------
respx unit mocks alone cannot catch a "client invented a path the service does not serve" bug: a
mock happily intercepts whatever (wrong) URL the client builds, so the test passes while the live
service 404s. This test pins the client's *constructed path* against the path TEMPLATE published in
the real Knowledge OpenAPI (verified live against cp-knowledge ``/openapi.json``):

    GET /domains/{domain}/{recordType}/{recordId}

recordType is kebab-case (``model-params``); the recordId contains slashes and MUST be
percent-encoded into a single segment. It asserts the client builds a matching concrete path and
does NOT build an ``/api/v1/...`` or flat ``/knowledge/model-params`` path.
"""

from __future__ import annotations

import inspect
import re
from urllib.parse import quote

from pattern_miner.knowledge import MODEL_PARAMS_RECORD_TYPE, MiningParamsClient

# Real published OpenAPI path template (verified live against cp-knowledge /openapi.json).
KNOWLEDGE_RECORD_TEMPLATE = "/domains/{domain}/{recordType}/{recordId}"
BASE = "http://knowledge.test"
DOMAIN = "core-ip"
RECORD_ID = "core-ip/modelParams/pattern-miner"


def _template_to_regex(template: str) -> re.Pattern[str]:
    return re.compile("^" + re.sub(r"\{[^}]+\}", r"[^/]+", template) + "$")


def _client() -> MiningParamsClient:
    return MiningParamsClient(BASE, domain=DOMAIN, record_id=RECORD_ID)


def test_model_params_url_matches_published_template_not_api_v1():
    client = _client()
    url = client._record_url(MODEL_PARAMS_RECORD_TYPE, RECORD_ID)
    path = url[len(BASE) :]

    # 1. NOT an invented /api/v1 or /knowledge/model-params path.
    assert "/api/v1/" not in path, path
    assert "/knowledge/" not in path, path
    assert "/records/" not in path, path

    # 2. Matches the real published template (recordId encoded into a single segment).
    assert _template_to_regex(KNOWLEDGE_RECORD_TEMPLATE).match(
        path
    ), f"{path} does not match {KNOWLEDGE_RECORD_TEMPLATE}"

    # 3. Concretely: kebab-case recordType + domain + percent-encoded recordId.
    expected = f"/domains/{DOMAIN}/{MODEL_PARAMS_RECORD_TYPE}/{quote(RECORD_ID, safe='')}"
    assert path == expected, f"{path} != {expected}"

    # 4. recordType is kebab-case in the path, NOT the camelCase modelParams token.
    assert "/model-params/" in path
    prefix = path.split(quote(RECORD_ID, safe=""))[0]
    assert "/modelParams/" not in prefix


def test_source_does_not_reintroduce_api_v1():
    """Guard the source itself: the URL builder must not use an /api/v1 or /records prefix."""
    src = inspect.getsource(MiningParamsClient._record_url)
    assert "/api/v1/" not in src
    assert "/records/" not in src
    assert "/domains/" in src


def test_record_type_segment_is_kebab_case():
    """The recordType path segment is kebab-case (model-params), matching the live route."""
    assert MODEL_PARAMS_RECORD_TYPE == "model-params"
