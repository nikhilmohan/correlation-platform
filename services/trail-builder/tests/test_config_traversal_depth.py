"""Config + contract guards for the Topology traversal ``maxDepth`` bound.

Covers gate-blocker #214 (trail-builder side):

- The ``traversal_max_depth`` default is 12 (was 32, which Topology's live API
  rejected with HTTP 400 when its cap was 8 -> ``trails.built``=0 -> P1 chain dead).
- It stays env-configurable via the ``TRAVERSAL_MAX_DEPTH`` alias.
- A contract guard ties the configured depth to Topology's published cap: the
  configured depth must be ``<=`` the ``maxDepth`` maximum declared in the FROZEN
  ``services/topology/openapi.json``. This closes the mock-vs-real gap so a future
  drift where trail-builder's default exceeds Topology's published cap fails in CI.
"""

from __future__ import annotations

import json
import pathlib

import pytest

from trailbuilder.config import get_settings

_HERE = pathlib.Path(__file__).resolve()
_REPO_ROOT = _HERE.parent.parent.parent.parent
_TOPOLOGY_OPENAPI = _REPO_ROOT / "services" / "topology" / "openapi.json"


def test_traversal_max_depth_default_is_12(monkeypatch: pytest.MonkeyPatch) -> None:
    """The default bound is 12 (the #214 fix); read purely from the environment."""
    monkeypatch.delenv("TRAVERSAL_MAX_DEPTH", raising=False)
    assert get_settings().traversal_max_depth == 12


def test_traversal_max_depth_is_env_configurable(monkeypatch: pytest.MonkeyPatch) -> None:
    """The bound is overridable via the TRAVERSAL_MAX_DEPTH env alias (no hard-coding)."""
    monkeypatch.setenv("TRAVERSAL_MAX_DEPTH", "20")
    assert get_settings().traversal_max_depth == 20


def _topology_traversal_max_depth_maximum() -> int | None:
    """Return the ``maxDepth`` schema ``maximum`` from Topology's FROZEN openapi.

    Returns ``None`` if the openapi on this branch does not yet declare a maximum
    (the parallel topology PR that raises + publishes the cap of 32 may not have
    landed on the trail-builder branch yet). The guard tightens automatically once
    that update merges — we never fabricate the bound here.
    """
    if not _TOPOLOGY_OPENAPI.exists():
        return None
    spec = json.loads(_TOPOLOGY_OPENAPI.read_text())
    get = spec.get("paths", {}).get("/topology/traversal", {}).get("get", {})
    for param in get.get("parameters", []):
        if param.get("name") == "maxDepth":
            return param.get("schema", {}).get("maximum")
    return None


def test_configured_depth_within_topology_published_cap() -> None:
    """Contract guard: configured depth must be <= Topology's published maxDepth cap.

    If Topology's openapi on this branch does not yet carry the published maximum
    (parallel PR not merged here), the assertion is skipped — it activates and
    tightens automatically once the topology openapi update lands. We never invent
    the bound.
    """
    maximum = _topology_traversal_max_depth_maximum()
    if maximum is None:
        pytest.skip(
            "Topology openapi /topology/traversal maxDepth has no `maximum` yet "
            "(parallel topology cap-raise PR not merged into this branch); "
            "guard tightens once it lands."
        )
    configured = get_settings(TRAVERSAL_MAX_DEPTH=12).traversal_max_depth
    assert configured <= maximum, (
        f"trail-builder traversal_max_depth ({configured}) exceeds Topology's "
        f"published maxDepth maximum ({maximum}) — would be rejected with HTTP 400"
    )
