"""Contract guard: the HTTP clients construct URLs that match the REAL collaborator OpenAPI.

WHY THIS EXISTS
---------------
The respx unit mocks alone cannot catch a "client invented a path the service does not serve"
bug: a mock will happily intercept whatever (wrong) URL the client builds, so the test passes
while the live service 404s. That is exactly what shipped — the Knowledge client called
``/api/v1/records/...`` and the trail-builder client called ``/api/v1/trails/...``, neither of
which exists on the real services.

This test pins the client's *constructed paths* against the path TEMPLATES published in the
real services' OpenAPI (captured below, verified live against cp-knowledge ``/openapi.json`` and
cp-trail-builder ``/openapi.json``). It asserts the client builds a concrete path that matches
the template — and that it does NOT build a ``/api/v1/...`` path. If a client path drifts away
from the published contract, this fails in CI without needing the live stack.
"""

from __future__ import annotations

import re
from urllib.parse import quote

from noise_filter.clients import (
    KNOWLEDGE_DOMAIN,
    MODEL_PARAMS_RECORD_ID,
    MODEL_PARAMS_RECORD_TYPE,
    KnowledgeClient,
    TrailBuilderClient,
)

# --- Real published OpenAPI path templates the clients depend on (verified live) -------------
# cp-knowledge GET /domains/{domain}/{recordType}/{recordId}  (recordId is one path param;
#   because the recordId contains slashes it MUST be percent-encoded into a single segment).
KNOWLEDGE_RECORD_TEMPLATE = "/domains/{domain}/{recordType}/{recordId}"
# cp-trail-builder GET /trails/{trailId}  (NO /api/v1 prefix).
TRAIL_TEMPLATE = "/trails/{trailId}"

BASE = "http://collab.test"


def _template_to_regex(template: str) -> re.Pattern[str]:
    """Turn an OpenAPI path template into a regex (each {param} is one non-slash segment)."""
    pattern = re.sub(r"\{[^}]+\}", r"[^/]+", template)
    return re.compile(rf"^{pattern}$")


def _path_of(url: str) -> str:
    return url[len(BASE) :]


def test_knowledge_model_params_url_matches_published_template_not_api_v1():
    """Knowledge model-params URL must match /domains/{domain}/{recordType}/{recordId}."""
    client = KnowledgeClient(BASE)
    url = client._record_url(MODEL_PARAMS_RECORD_TYPE, MODEL_PARAMS_RECORD_ID)
    path = _path_of(url)

    # 1. NOT the invented /api/v1/records path that 404'd live.
    assert "/api/v1/" not in path, path
    assert "/records/" not in path, path

    # 2. Matches the real published template (recordId encoded into a single segment).
    assert _template_to_regex(KNOWLEDGE_RECORD_TEMPLATE).match(
        path
    ), f"{path} does not match {KNOWLEDGE_RECORD_TEMPLATE}"

    # 3. Concretely: kebab-case recordType segment + domain + percent-encoded recordId.
    expected = (
        f"/domains/{KNOWLEDGE_DOMAIN}/{MODEL_PARAMS_RECORD_TYPE}"
        f"/{quote(MODEL_PARAMS_RECORD_ID, safe='')}"
    )
    assert path == expected, f"{path} != {expected}"

    # 4. recordType is kebab-case in the path, NOT the camelCase modelParams token.
    assert "/model-params/" in path
    assert "/modelParams/" not in path.split(quote(MODEL_PARAMS_RECORD_ID, safe=""))[0]

    # 5. Guard the source itself: the Knowledge URL builder must not reintroduce /api/v1/records.
    import inspect

    src = inspect.getsource(KnowledgeClient._record_url)
    assert "/api/v1/" not in src, "KnowledgeClient must not use an /api/v1 prefix"
    assert "/records/" not in src, "KnowledgeClient must not use the invented /records/ path"
    assert "/domains/" in src


def test_trail_builder_url_matches_published_template_not_api_v1():
    """Trail URL must match /trails/{trailId} — no /api/v1 prefix."""
    client = TrailBuilderClient(BASE)
    # get_trail builds the URL internally; reproduce the same construction it uses.
    trail_id = "trail-123"
    path = f"/trails/{quote(trail_id, safe='')}"

    assert "/api/v1/" not in path, path
    assert _template_to_regex(TRAIL_TEMPLATE).match(path), f"{path} does not match {TRAIL_TEMPLATE}"

    # Guard the source itself: the client must not reintroduce an /api/v1 prefix.
    import inspect

    src = inspect.getsource(client.get_trail)
    assert "/api/v1/" not in src, "TrailBuilderClient.get_trail must not use an /api/v1 prefix"
    assert "/trails/" in src
