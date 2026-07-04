"""P3 collaborator read-client tests — config-switchable mock/real (AC 32, 33, 44).

Each client mirrors ``topology_client``'s mock/real ``make_client`` pattern; the real transport is
tested against the collaborators' *published* endpoint shapes via ``httpx.MockTransport`` (never the
collaborator's source). Switching mode requires no code change (AC 44).
"""

from __future__ import annotations

import httpx
import pytest

from simulator.integrations import (
    pattern_manager_client,
    topology_snapshot_client,
    trail_builder_client,
)
from simulator.integrations.pattern_manager_client import (
    HttpPatternManagerClient,
    MockPatternManagerClient,
)
from simulator.integrations.topology_snapshot_client import (
    HttpTopologySnapshotClient,
    MockTopologySnapshotClient,
)
from simulator.integrations.trail_builder_client import (
    HttpTrailBuilderClient,
    MockTrailBuilderClient,
    TrailNotFound,
)
from tests import p3_fixtures as fx


# --- Pattern Manager (AC 32, 44) -----------------------------------------------------------
def test_pm_mock_mode_returns_configured_patterns() -> None:
    body = [fx.pattern_view("pat-01", "trail-A", [("IPLinkDown", False)], "IPLinkDown")]
    client = pattern_manager_client.make_client("mock", None)
    assert isinstance(client, MockPatternManagerClient)
    client._items = body  # inject
    patterns = client.list_approved()
    assert [p.pattern_id for p in patterns] == ["pat-01"]
    assert client.calls == 1


def test_pm_real_requires_base_url() -> None:
    with pytest.raises(ValueError, match="PATTERN_MANAGER_API_BASE_URL"):
        pattern_manager_client.make_client("real", None)


def test_pm_switch_mode_no_code_change() -> None:
    mock_c = pattern_manager_client.make_client("mock", None)
    real_c = pattern_manager_client.make_client("real", "http://pm:8080")
    assert type(mock_c) is not type(real_c)


def test_pm_real_reads_patternpage_envelope() -> None:
    page = fx.pattern_page(
        [fx.pattern_view("pat-01", "trail-A", [("IPLinkDown", False)], "IPLinkDown")]
    )
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        return httpx.Response(200, json=page)

    client = HttpPatternManagerClient(
        "http://pm:8080", client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    patterns = client.list_approved()
    assert len(patterns) == 1
    assert "lifecycle=approved" in str(seen["url"])


def test_pm_real_pages_until_total_reached() -> None:
    p = fx.pattern_view("pat-x", "trail-A", [("IPLinkDown", False)], "IPLinkDown")
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        offset = int(dict(request.url.params).get("offset", "0"))
        calls["n"] += 1
        # total 3, page size returns 2 then 1
        if offset == 0:
            return httpx.Response(
                200, json={"items": [p, p], "total": 3, "limit": 200, "offset": 0}
            )
        return httpx.Response(200, json={"items": [p], "total": 3, "limit": 200, "offset": 2})

    client = HttpPatternManagerClient(
        "http://pm:8080", client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    patterns = client.list_approved()
    assert len(patterns) == 3
    assert calls["n"] == 2


# --- Trail Builder (AC 33, 44) -------------------------------------------------------------
def test_tb_mock_returns_members_and_404_sentinel() -> None:
    client = MockTrailBuilderClient({"trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS)})
    ok = client.get_trail("trail-A")
    assert not isinstance(ok, TrailNotFound)
    assert [m.object_type for m in ok.members] == ["IPLink", "IGPAdjacency", "LSP"]
    missing = client.get_trail("trail-ZZZ")
    assert isinstance(missing, TrailNotFound)
    assert missing.trail_id == "trail-ZZZ"


def test_tb_real_requires_base_url() -> None:
    with pytest.raises(ValueError, match="TRAIL_BUILDER_API_BASE_URL"):
        trail_builder_client.make_client("real", None)


def test_tb_real_get_trail_and_404() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("trail-A"):
            return httpx.Response(200, json=fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS))
        return httpx.Response(404, text="not found")

    client = HttpTrailBuilderClient(
        "http://tb:8080", client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    ok = client.get_trail("trail-A")
    assert not isinstance(ok, TrailNotFound)
    assert ok.members[0].managed_object_id == "IPLink:ip-7"
    assert isinstance(client.get_trail("trail-missing"), TrailNotFound)


def test_tb_object_type_consumed_as_is_not_derived_from_moid() -> None:
    """OQ-P3-5: objectType is read from the API member, not parsed from the moid prefix."""
    # A member whose objectType deliberately differs from its moid prefix proves we use the field.
    body = fx.trail_detail("trail-A", [("IPLink:ip-7", "SpecialType")])
    client = MockTrailBuilderClient({"trail-A": body})
    trail = client.get_trail("trail-A")
    assert trail.members[0].object_type == "SpecialType"


# --- Topology snapshots (AC 44) ------------------------------------------------------------
def test_ts_mock_returns_summaries() -> None:
    client = MockTopologySnapshotClient([fx.snapshot_summary("snap-1")])
    assert isinstance(client, MockTopologySnapshotClient)
    snaps = client.list_snapshots("core-ip")
    assert snaps[0].snapshot_id == "snap-1"
    assert client.calls == 1


def test_ts_real_requires_base_url() -> None:
    with pytest.raises(ValueError, match="TOPOLOGY_API_BASE_URL"):
        topology_snapshot_client.make_client("real", None)


def test_ts_real_reads_snapshotlistdto() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"snapshots": [fx.snapshot_summary("snap-9")]})

    client = HttpTopologySnapshotClient(
        "http://topo:8080", client=httpx.Client(transport=httpx.MockTransport(handler))
    )
    snaps = client.list_snapshots("core-ip")
    assert snaps[0].snapshot_id == "snap-9"
    assert snaps[0].node_count == 312


# --- retry / error paths (bounded retry mirrors topology_client) ---------------------------
def test_pm_real_retries_then_raises() -> None:
    from simulator.integrations.pattern_manager_client import PatternFetchError

    client = HttpPatternManagerClient(
        "http://pm:8080",
        max_attempts=2,
        client=httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(503))),
    )
    with pytest.raises(PatternFetchError, match="failed after 2 attempts"):
        client.list_approved()


def test_tb_real_retries_then_raises_on_non_404() -> None:
    from simulator.integrations.trail_builder_client import TrailFetchError

    client = HttpTrailBuilderClient(
        "http://tb:8080",
        max_attempts=2,
        client=httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(500))),
    )
    with pytest.raises(TrailFetchError, match="failed after 2 attempts"):
        client.get_trail("trail-A")


def test_ts_real_retries_then_raises() -> None:
    from simulator.integrations.topology_snapshot_client import SnapshotListError

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route")

    client = HttpTopologySnapshotClient(
        "http://topo:8080",
        max_attempts=2,
        client=httpx.Client(transport=httpx.MockTransport(boom)),
    )
    with pytest.raises(SnapshotListError):
        client.list_snapshots("core-ip")
