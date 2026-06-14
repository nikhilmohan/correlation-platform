"""INT-IGPAREA — the load-bearing AC-2 guarantee on **real** Simulator data (#225).

This is the integration assertion the round-8 gate proved was missing: it runs the
*real* Trail Builder closure over a **Simulator-generated** multi-area topology
served by the **real** Topology + Knowledge services (grounded ``igpArea``, never
injected), and asserts the area-component bound holds end-to-end:

  (a) every built trail's area-bearing members resolve to a single ``igpArea``;
  (b) the multi-area topology yields **multiple** trails and **no** whole-network
      trail — the largest trail is strictly smaller than the whole connected
      dependency component (the exact round-8 181-member failure);
  (c) a grounding/seam precondition that FAILS LOUDLY if ``igpArea`` ever stops
      flowing list/neighbors -> client -> closure (>= 2 distinct areas present and a
      sampled Topology ``GET /topology/nodes`` carries ``attributes.igpArea``);
  (d) FiberSpan/IPLink-seeded trails are single-area, not whole-network.

It is marked ``@pytest.mark.integration`` and is therefore EXCLUDED from the default
(mock-only) unit gate (see ``pyproject.toml`` addopts ``-m 'not integration'``). It
runs in the integration-test stage against the live Compose stack
(``TOPOLOGY_SERVICE_MODE=real``, ``KNOWLEDGE_SERVICE_MODE=real``). When its live
dependencies are not configured/reachable, it SKIPS with a clear reason rather than
failing — the always-on guard for #225 is the unit fixture
``test_area_less_mesh_does_not_fuse_areas``; this is the real-data catch-net.

Env it reads (all from the integration stack; no hard-coded URLs):
  - ``TRAILBUILDER_BASE_URL``     — Trail Builder query API (e.g. http://trail-builder:8000)
  - ``TOPOLOGY_SERVICE_BASE_URL`` — Topology query API (to resolve member igpArea)
  - ``INT_IGPAREA_DOMAIN``        — domain to query (default ``core-ip``)
  - ``INT_IGPAREA_SNAPSHOT_ID``   — the Simulator-generated snapshot to assert over
"""

from __future__ import annotations

import os

import pytest

pytestmark = pytest.mark.integration

httpx = pytest.importorskip("httpx", reason="httpx required for the live integration call")

_TRAILBUILDER_URL = os.getenv("TRAILBUILDER_BASE_URL")
_TOPOLOGY_URL = os.getenv("TOPOLOGY_SERVICE_BASE_URL")
_DOMAIN = os.getenv("INT_IGPAREA_DOMAIN", "core-ip")
_SNAPSHOT = os.getenv("INT_IGPAREA_SNAPSHOT_ID")

_AREA_LESS_SEED_TYPES = ("FiberSpan", "IPLink")


def _require_live() -> tuple[str, str, str]:
    """Skip (not fail) when the live integration deps are not wired in this env."""
    if not _TRAILBUILDER_URL or not _TOPOLOGY_URL or not _SNAPSHOT:
        pytest.skip(
            "INT-IGPAREA requires the integration stack: set TRAILBUILDER_BASE_URL, "
            "TOPOLOGY_SERVICE_BASE_URL and INT_IGPAREA_SNAPSHOT_ID (real Topology + "
            "Knowledge + a Simulator-generated multi-area snapshot)."
        )
    for url in (_TRAILBUILDER_URL, _TOPOLOGY_URL):
        try:
            httpx.get(f"{url.rstrip('/')}/health", timeout=2.0)
        except httpx.HTTPError as exc:  # not reachable in this env -> skip, don't fail
            pytest.skip(f"INT-IGPAREA dependency unreachable ({url}): {exc}")
    return _TRAILBUILDER_URL.rstrip("/"), _TOPOLOGY_URL.rstrip("/"), _SNAPSHOT


def _list_trails(tb_url: str, snapshot_id: str) -> list[dict]:
    resp = httpx.get(
        f"{tb_url}/trails",
        params={"snapshotId": snapshot_id, "domain": _DOMAIN},
        timeout=10.0,
    )
    resp.raise_for_status()
    return list(resp.json().get("trails", []))


def _get_trail(tb_url: str, trail_id: str) -> dict:
    resp = httpx.get(f"{tb_url}/trails/{trail_id}", timeout=10.0)
    resp.raise_for_status()
    return resp.json()


def _node_igp_area(topo_url: str, managed_object_id: str) -> str | None:
    """Resolve a member's grounded ``igpArea`` from the real Topology node read."""
    resp = httpx.get(
        f"{topo_url}/topology/nodes/{managed_object_id}",
        params={"domain": _DOMAIN, "snapshotId": "current"},
        timeout=10.0,
    )
    if resp.status_code != 200:
        return None
    return (resp.json().get("attributes") or {}).get("igpArea")


def _member_areas(topo_url: str, members: list[dict]) -> set[str]:
    areas: set[str] = set()
    for m in members:
        area = _node_igp_area(topo_url, m["managedObjectId"])
        if area is not None:
            areas.add(area)
    return areas


def test_int_igparea_area_bounded_no_whole_network() -> None:
    """INT-IGPAREA (a)+(b)+(c)+(d) on real Simulator-generated multi-area data."""
    tb_url, topo_url, snapshot_id = _require_live()

    summaries = _list_trails(tb_url, snapshot_id)
    assert summaries, "expected the Simulator-generated build to produce trails"

    trails = [_get_trail(tb_url, s["trailId"]) for s in summaries]
    member_id_sets = [{m["managedObjectId"] for m in t["members"]} for t in trails]

    # (c) Grounding + seam precondition — fail LOUDLY if igpArea stopped flowing.
    all_areas: set[str] = set()
    for t in trails:
        all_areas |= _member_areas(topo_url, t["members"])
    assert len(all_areas) >= 2, (
        "INT-IGPAREA grounding precondition FAILED: fewer than 2 distinct igpArea "
        f"values across all trail members ({all_areas}). igpArea is not flowing "
        "list/neighbors -> client -> closure on real data (the #225 seam regression)."
    )
    # Seam proof: a sampled Topology node read carries attributes.igpArea.
    sample_resp = httpx.get(
        f"{topo_url}/topology/nodes",
        params={"objectType": "Node", "domain": _DOMAIN, "snapshotId": "current"},
        timeout=10.0,
    )
    sample_resp.raise_for_status()
    sample_nodes = sample_resp.json().get("nodes", [])
    assert any(
        (n.get("attributes") or {}).get("igpArea") for n in sample_nodes
    ), "INT-IGPAREA seam proof FAILED: no sampled Node carries attributes.igpArea"

    # (a) No trail spans two IGP areas (area-bounded).
    for t in trails:
        areas = _member_areas(topo_url, t["members"])
        assert len(areas) <= 1, f"trail {t['trailId']} spans igp areas {areas}"

    # (b) No whole-network trail: the largest trail is strictly smaller than the
    #     union of all members reached across the build (the round-8 181-member fuse).
    whole_network = set().union(*member_id_sets) if member_id_sets else set()
    largest = max(len(ms) for ms in member_id_sets)
    assert len(trails) >= 2, "expected multiple area-bounded trails on a multi-area topology"
    assert largest < len(whole_network), (
        f"whole-network trail detected on real data: largest trail {largest} "
        f">= reachable union {len(whole_network)} (the round-8 #225 failure)"
    )

    # (d) Area-less-seed bound: FiberSpan/IPLink-seeded trails are single-area.
    for t in trails:
        seed = t.get("seedManagedObjectId") or t.get("seed", "")
        if any(seed.startswith(f"{p}:") for p in _AREA_LESS_SEED_TYPES):
            areas = _member_areas(topo_url, t["members"])
            assert (
                len(areas) <= 1
            ), f"area-less-seeded trail {t['trailId']} (seed {seed}) spans areas {areas}"
