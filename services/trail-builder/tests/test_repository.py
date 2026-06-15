"""IO-layer unit tests for ``TrailRepository`` (persist / supersede / retention / reads).

These run against the in-memory SQLite engine (``trailbuilder`` schema ATTACHed by
``make_engine``) provided by the ``engine`` fixture. They map to the spec ACs the
repository underpins:

- AC-4  ``getTrailsForObject`` completeness (``trail_ids_for_object``).
- AC-5  ``getTrail`` returns full member list + snapshotId + domain.
- AC-7  Idempotency: re-persisting the same (domain, snapshotId) supersedes, no dup rows.
- AC-10 Trails carry a non-empty domain; ``list_trails`` is domain-scoped (no leakage).
- AC-15 snapshotId/domain alignment; a new snapshotId leaves prior records intact.
- AC-17 ``list_trails`` enumerates exactly the trails for a snapshot+domain.
"""

from __future__ import annotations

import pytest

from trailbuilder.models import Trail
from trailbuilder.repository import TrailRepository


def _trail(
    trail_id: str,
    members: tuple[str, ...],
    domain: str = "core-ip",
    snapshot_id: str = "snap-1",
    igp_area: str | None = "area-0",
    srlg_group: str | None = None,
) -> Trail:
    return Trail(
        trail_id=trail_id,
        domain=domain,
        snapshot_id=snapshot_id,
        seed_managed_object_id=members[0],
        members=members,
        igp_area=igp_area,
        srlg_group=srlg_group,
    )


@pytest.fixture
def repo(engine) -> TrailRepository:
    return TrailRepository(engine, retention_snapshots=2)


def test_persist_and_get_trail_round_trips_members_domain_snapshot(repo: TrailRepository) -> None:
    """AC-5: getTrail returns the full member list, snapshotId and domain it was built from."""
    members = ("Node:A", "Interface:A.100", "IPLink:L1")
    repo.persist_build("core-ip", "snap-1", [_trail("t1", members)])

    got = repo.get_trail("t1")
    assert got is not None
    assert got.domain == "core-ip"
    assert got.snapshot_id == "snap-1"
    assert set(got.members) == set(members)
    assert got.member_count == len(members)
    # Every member conforms to the <objectType>:<id> scheme.
    assert all(":" in m for m in got.members)


def test_get_trail_unknown_returns_none(repo: TrailRepository) -> None:
    """AC-5 (negative): an unknown trailId yields None (API maps this to 404)."""
    assert repo.get_trail("does-not-exist") is None


def test_trail_ids_for_object_completeness(repo: TrailRepository) -> None:
    """AC-4: trail_ids_for_object returns exactly the trails an object belongs to."""
    repo.persist_build(
        "core-ip",
        "snap-1",
        [
            _trail("t1", ("Node:X", "Node:A")),
            _trail("t2", ("Node:X", "Node:B")),
            _trail("t3", ("Node:C", "Node:D")),
        ],
    )
    assert repo.trail_ids_for_object("Node:X", "core-ip") == ["t1", "t2"]
    assert repo.trail_ids_for_object("Node:C", "core-ip") == ["t3"]
    # An object not in any trail returns an empty list (not an error).
    assert repo.trail_ids_for_object("Node:ZZZ", "core-ip") == []


def test_trail_ids_for_object_is_domain_scoped(repo: TrailRepository) -> None:
    """AC-10: trail_ids_for_object does not leak across domains."""
    repo.persist_build("core-ip", "snap-1", [_trail("ta", ("Node:X",), domain="core-ip")])
    repo.persist_build("metro", "snap-1", [_trail("tb", ("Node:X",), domain="metro")])
    assert repo.trail_ids_for_object("Node:X", "core-ip") == ["ta"]
    assert repo.trail_ids_for_object("Node:X", "metro") == ["tb"]


def test_persist_build_is_idempotent_on_domain_snapshot(repo: TrailRepository) -> None:
    """AC-7: re-persisting the same (domain, snapshotId) supersedes — no duplicate rows."""
    trails = [_trail("t1", ("Node:A", "Node:B")), _trail("t2", ("Node:C",))]
    repo.persist_build("core-ip", "snap-1", trails)
    repo.persist_build("core-ip", "snap-1", trails)  # redelivery / rebuild

    listed = repo.list_trails("snap-1", "core-ip")
    assert {t.trail_id for t in listed} == {"t1", "t2"}
    # Members were replaced, not duplicated: getTrail returns the deduped member set.
    t1 = repo.get_trail("t1")
    assert t1 is not None
    assert sorted(t1.members) == ["Node:A", "Node:B"]


def test_list_trails_is_domain_scoped_same_snapshot(repo: TrailRepository) -> None:
    """AC-10: two builds for different domains on the same snapshot do not cross-leak."""
    repo.persist_build("core-ip", "snap-1", [_trail("ca", ("Node:A",), domain="core-ip")])
    repo.persist_build("metro", "snap-1", [_trail("mb", ("Node:B",), domain="metro")])

    core = repo.list_trails("snap-1", "core-ip")
    metro = repo.list_trails("snap-1", "metro")
    assert {t.trail_id for t in core} == {"ca"}
    assert {t.trail_id for t in metro} == {"mb"}
    assert all(t.domain == "core-ip" for t in core)
    assert all(t.domain == "metro" for t in metro)


def test_list_trails_enumerates_all_with_positive_member_count(repo: TrailRepository) -> None:
    """AC-17: list_trails returns exactly the N trails for a snapshot+domain, each member>0."""
    trails = [
        _trail("t1", ("Node:A", "Node:B")),
        _trail("t2", ("Node:C",)),
        _trail("t3", ("Node:D", "Node:E", "Node:F")),
    ]
    repo.persist_build("core-ip", "snap-1", trails)

    listed = repo.list_trails("snap-1", "core-ip")
    assert len(listed) == 3
    assert {t.trail_id for t in listed} == {"t1", "t2", "t3"}
    # Summaries carry the persisted member count without loading members.
    assert all(t.member_count > 0 for t in listed)
    assert all(t.domain for t in listed)


def test_list_trails_pagination(repo: TrailRepository) -> None:
    """list_trails honours limit/offset for the listTrails API."""
    trails = [_trail(f"t{i}", (f"Node:N{i}",)) for i in range(5)]
    repo.persist_build("core-ip", "snap-1", trails)

    page = repo.list_trails("snap-1", "core-ip", limit=2, offset=1)
    assert [t.trail_id for t in page] == ["t1", "t2"]


def test_new_snapshot_leaves_prior_snapshot_intact(repo: TrailRepository) -> None:
    """AC-15: a build for a new snapshotId creates new records, prior snapshot retained."""
    repo.persist_build("core-ip", "snap-1", [_trail("old", ("Node:A",), snapshot_id="snap-1")])
    repo.persist_build("core-ip", "snap-2", [_trail("new", ("Node:A",), snapshot_id="snap-2")])

    assert {t.trail_id for t in repo.list_trails("snap-1", "core-ip")} == {"old"}
    assert {t.trail_id for t in repo.list_trails("snap-2", "core-ip")} == {"new"}
    # Each carries the snapshotId from the build that created it.
    assert repo.get_trail("old").snapshot_id == "snap-1"  # type: ignore[union-attr]
    assert repo.get_trail("new").snapshot_id == "snap-2"  # type: ignore[union-attr]


def test_retention_prunes_oldest_snapshots_per_domain(repo: TrailRepository) -> None:
    """Retention (=2) keeps the two most-recent snapshots per domain, prunes older."""
    for i in range(1, 4):
        repo.persist_build(
            "core-ip", f"snap-{i}", [_trail(f"t{i}", ("Node:A",), snapshot_id=f"snap-{i}")]
        )
    # snap-1 is the oldest of three and must have been pruned.
    assert repo.list_trails("snap-1", "core-ip") == []
    assert {t.trail_id for t in repo.list_trails("snap-2", "core-ip")} == {"t2"}
    assert {t.trail_id for t in repo.list_trails("snap-3", "core-ip")} == {"t3"}


def test_resolve_current_to_latest_persisted_snapshot(repo: TrailRepository) -> None:
    """#226: the ``current`` sentinel resolves to the latest persisted snapshot for the domain.

    Trails are persisted under the CONCRETE snapshotId from the triggering event
    (e.g. SNAP-...). A query for ``current`` must map to that concrete snapshot,
    not look for one literally named "current".
    """
    repo.persist_build("core-ip", "SNAP-0001", [_trail("t1", ("Node:A",), snapshot_id="SNAP-0001")])
    repo.persist_build("core-ip", "SNAP-0002", [_trail("t2", ("Node:B",), snapshot_id="SNAP-0002")])

    assert repo.resolve_snapshot_id("current", "core-ip") == "SNAP-0002"


def test_resolve_previous_to_second_latest_snapshot(repo: TrailRepository) -> None:
    """#226: ``previous`` resolves to the immediately-prior snapshot (topology current|previous)."""
    repo.persist_build("core-ip", "SNAP-0001", [_trail("t1", ("Node:A",), snapshot_id="SNAP-0001")])
    repo.persist_build("core-ip", "SNAP-0002", [_trail("t2", ("Node:B",), snapshot_id="SNAP-0002")])

    assert repo.resolve_snapshot_id("previous", "core-ip") == "SNAP-0001"


def test_resolve_concrete_snapshot_id_passes_through(repo: TrailRepository) -> None:
    """#226: a concrete snapshotId is returned unchanged (no resolution applied)."""
    repo.persist_build("core-ip", "SNAP-0001", [_trail("t1", ("Node:A",), snapshot_id="SNAP-0001")])
    assert repo.resolve_snapshot_id("SNAP-0001", "core-ip") == "SNAP-0001"
    # A concrete id that does not exist still passes through (the list query then returns 0).
    assert repo.resolve_snapshot_id("SNAP-9999", "core-ip") == "SNAP-9999"


def test_resolve_current_is_domain_scoped(repo: TrailRepository) -> None:
    """#226: ``current`` resolves to the latest snapshot for the QUERIED domain, not globally."""
    repo.persist_build(
        "core-ip", "SNAP-CORE-1", [_trail("c1", ("Node:A",), snapshot_id="SNAP-CORE-1")]
    )
    repo.persist_build(
        "metro",
        "SNAP-METRO-1",
        [_trail("m1", ("Node:B",), domain="metro", snapshot_id="SNAP-METRO-1")],
    )

    assert repo.resolve_snapshot_id("current", "core-ip") == "SNAP-CORE-1"
    assert repo.resolve_snapshot_id("current", "metro") == "SNAP-METRO-1"


def test_resolve_sentinel_with_no_persisted_snapshots_returns_none(repo: TrailRepository) -> None:
    """#226: ``current``/``previous`` with nothing (or only one) persisted resolves to None.

    None lets the caller fall back to listing under the literal sentinel (count 0),
    rather than crashing — a graceful empty result.
    """
    assert repo.resolve_snapshot_id("current", "core-ip") is None
    repo.persist_build("core-ip", "SNAP-0001", [_trail("t1", ("Node:A",), snapshot_id="SNAP-0001")])
    # Only one snapshot persisted -> there is no "previous".
    assert repo.resolve_snapshot_id("previous", "core-ip") is None


def test_list_trails_resolves_current_to_latest_snapshot(repo: TrailRepository) -> None:
    """#226 (repo-level): list_trails under the resolved ``current`` returns the latest trails."""
    repo.persist_build(
        "core-ip",
        "SNAP-0439f418",
        [_trail(f"t{i}", (f"Node:N{i}",), snapshot_id="SNAP-0439f418") for i in range(10)],
    )
    resolved = repo.resolve_snapshot_id("current", "core-ip")
    assert resolved == "SNAP-0439f418"
    listed = repo.list_trails(resolved, "core-ip")
    assert len(listed) == 10


def test_retention_floor_is_at_least_one() -> None:
    """A retention configured below 1 is clamped to 1 (never prunes everything)."""
    from trailbuilder.db.engine import create_all_in_schema, make_engine

    eng = make_engine("sqlite://")
    create_all_in_schema(eng)
    repo = TrailRepository(eng, retention_snapshots=0)
    repo.persist_build("core-ip", "snap-1", [_trail("t1", ("Node:A",))])
    assert {t.trail_id for t in repo.list_trails("snap-1", "core-ip")} == {"t1"}
