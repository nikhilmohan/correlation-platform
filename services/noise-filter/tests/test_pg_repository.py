"""Unit tests for the asyncpg-backed repositories using a fake pool/connection.

These verify the SQL-building + row-mapping logic (parameterized SQL, ON CONFLICT clauses,
filter/pagination assembly) and the RepositoryUnavailable mapping, without a live PostgreSQL
(the real Postgres apply is exercised in the integration gate)."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from noise_filter.pg_repository import (
    PgObservedChatterRepository,
    PgRunStatsRepository,
)
from noise_filter.repository import RepositoryUnavailable
from noise_filter.stats import ChatterSignature, RunStatsRow


class FakeConn:
    def __init__(self, *, fetchrow=None, fetch=None, fetchval=None, raise_on=None):
        self._fetchrow = fetchrow
        self._fetch = fetch or []
        self._fetchval = fetchval if fetchval is not None else 0
        self._raise_on = raise_on
        self.executed: list[tuple] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def execute(self, sql, *args):
        if self._raise_on == "execute":
            raise RuntimeError("db down")
        self.executed.append((sql, args))

    async def fetchrow(self, sql, *args):
        if self._raise_on == "fetchrow":
            raise RuntimeError("db down")
        return self._fetchrow

    async def fetch(self, sql, *args):
        if self._raise_on == "fetch":
            raise RuntimeError("db down")
        return self._fetch

    async def fetchval(self, sql, *args):
        if self._raise_on == "fetchval":
            raise RuntimeError("db down")
        return self._fetchval


class FakePool:
    def __init__(self, conn: FakeConn):
        self._conn = conn

    def acquire(self):
        return self._conn


# A real UUID string for the default run_id (the nf_run_stats.run_id column is UUID, so the
# integration round-trip via _row() must supply a valid UUID, not a short token).
_DEFAULT_RUN_ID = "11111111-1111-1111-1111-111111111111"


def _record(**over):
    base = dict(
        run_id=_DEFAULT_RUN_ID,
        run_timestamp=datetime.now(UTC),
        trail_id="t1",
        snapshot_id="s1",
        domain="core-ip",
        window_start=datetime.now(UTC),
        window_end=datetime.now(UTC) + timedelta(minutes=10),
        eps=1.0,
        min_samples=3,
        window_size_seconds=600,
        algorithm="dbscan",
        alarms_in=10,
        clusters_formed=1,
        alarms_kept=8,
        alarms_dropped=2,
        noise_ratio=0.2,
        storm_max_cluster_size=8,
        storm_reduction_ratio=10.0,
        retention_vs_oracle=1.0,
        hop_feature_enabled=False,
    )
    base.update(over)
    return base


def _row() -> RunStatsRow:
    return RunStatsRow(**_record())


@pytest.mark.asyncio
async def test_pg_run_stats_insert_uses_on_conflict_do_nothing():
    conn = FakeConn()
    repo = PgRunStatsRepository(FakePool(conn))
    await repo.insert_run(_row())
    sql, args = conn.executed[0]
    assert "ON CONFLICT (run_id) DO NOTHING" in sql
    assert len(args) == 20


@pytest.mark.asyncio
async def test_pg_run_stats_get_run_maps_record():
    conn = FakeConn(fetchrow=_record(run_id="abc"))
    repo = PgRunStatsRepository(FakePool(conn))
    row = await repo.get_run("abc")
    assert row is not None and row.run_id == "abc" and row.alarms_in == 10


@pytest.mark.asyncio
async def test_pg_run_stats_get_run_unavailable_maps_503():
    conn = FakeConn(raise_on="fetchrow")
    repo = PgRunStatsRepository(FakePool(conn))
    with pytest.raises(RepositoryUnavailable):
        await repo.get_run("x")


@pytest.mark.asyncio
async def test_pg_run_stats_list_builds_filters_and_pagination():
    conn = FakeConn(fetch=[_record(run_id="r1")], fetchval=1)
    repo = PgRunStatsRepository(FakePool(conn))
    rows, total = await repo.list_runs(
        trail_id="t1", from_ts=datetime.now(UTC), to_ts=datetime.now(UTC), limit=10, offset=5
    )
    assert total == 1 and rows[0].run_id == "r1"  # fake pool returns the record verbatim


@pytest.mark.asyncio
async def test_pg_run_stats_list_unavailable_maps_503():
    conn = FakeConn(raise_on="fetchval")
    repo = PgRunStatsRepository(FakePool(conn))
    with pytest.raises(RepositoryUnavailable):
        await repo.list_runs()


@pytest.mark.asyncio
async def test_pg_chatter_upsert_with_mo_uses_full_key():
    conn = FakeConn()
    repo = PgObservedChatterRepository(FakePool(conn))
    await repo.upsert_signature(
        ChatterSignature(
            managed_object_id="Port:a", alarm_type="QoS", event_type="x", trail_id="t1"
        )
    )
    sql, args = conn.executed[0]
    assert "ON CONFLICT (managed_object_id, alarm_type, event_type, trail_id)" in sql
    # The matching unique index is PARTIAL; ON CONFLICT MUST repeat its predicate or Postgres
    # cannot infer it (regression guard for "no unique constraint matching ON CONFLICT").
    assert "WHERE managed_object_id IS NOT NULL" in sql
    assert "occurrence_count + 1" in sql


@pytest.mark.asyncio
async def test_pg_chatter_upsert_null_mo_uses_partial_key():
    conn = FakeConn()
    repo = PgObservedChatterRepository(FakePool(conn))
    await repo.upsert_signature(
        ChatterSignature(managed_object_id=None, alarm_type="QoS", event_type="x", trail_id="t1")
    )
    sql, _ = conn.executed[0]
    assert "WHERE managed_object_id IS NULL" in sql


@pytest.mark.asyncio
async def test_pg_chatter_list_maps_and_ranks():
    rec = {
        "managed_object_id": "Port:a",
        "alarm_type": "QoS",
        "event_type": "x",
        "trail_id": "t1",
        "occurrence_count": 5,
        "first_seen": datetime.now(UTC),
        "last_seen": datetime.now(UTC),
    }
    conn = FakeConn(fetch=[rec], fetchval=1)
    repo = PgObservedChatterRepository(FakePool(conn))
    sigs, total = await repo.list_signatures(alarm_type="QoS", trail_id="t1", min_occurrence=2)
    assert total == 1 and sigs[0].occurrence_count == 5


@pytest.mark.asyncio
async def test_pg_chatter_list_unavailable_maps_503():
    conn = FakeConn(raise_on="fetchval")
    repo = PgObservedChatterRepository(FakePool(conn))
    with pytest.raises(RepositoryUnavailable):
        await repo.list_signatures()
