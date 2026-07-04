"""Integration-tagged Testcontainers tests against a REAL PostgreSQL.

These exercise the asyncpg repositories + yoyo migrations against a real Postgres (the unit gate
uses in-memory stand-ins; this is the live catch-net per the platform's "integration tests must
actually run" standard). Marked ``integration`` so the unit gate can deselect them when no Docker
is available (``pytest -m "not integration"``); the integration stage runs them.
"""

from __future__ import annotations

import pytest

pytestmark = pytest.mark.integration

asyncpg = pytest.importorskip("asyncpg")
testcontainers_postgres = pytest.importorskip("testcontainers.postgres")
from testcontainers.postgres import PostgresContainer  # noqa: E402

from noise_filter.migrate import apply_migrations_asyncpg  # noqa: E402
from noise_filter.pg_repository import (  # noqa: E402
    PgObservedChatterRepository,
    PgRunStatsRepository,
)
from noise_filter.stats import ChatterSignature  # noqa: E402

from .test_pg_repository import _row  # noqa: E402


@pytest.fixture(scope="module")
def pg_url():
    with PostgresContainer("postgres:16-alpine") as pg:
        url = pg.get_connection_url().replace("postgresql+psycopg2://", "postgresql://")
        yield url


@pytest.fixture
async def pool(pg_url):
    p = await asyncpg.create_pool(pg_url)
    # Apply migrations over the asyncpg pool (permissive-only; no psycopg2). Twice => idempotent.
    await apply_migrations_asyncpg(p)
    await apply_migrations_asyncpg(p)
    yield p
    await p.close()


@pytest.mark.asyncio
async def test_run_stats_round_trip_real_postgres(pool):
    repo = PgRunStatsRepository(pool)
    row = _row()
    await repo.insert_run(row)
    await repo.insert_run(row)  # ON CONFLICT DO NOTHING idempotency
    rows, total = await repo.list_runs(trail_id=row.trail_id)
    assert total == 1
    assert rows[0].run_id == row.run_id


@pytest.mark.asyncio
async def test_observed_chatter_upsert_aggregates_real_postgres(pool):
    repo = PgObservedChatterRepository(pool)
    sig = ChatterSignature(
        managed_object_id="Port:z", alarm_type="QoS", event_type="x", trail_id="t1"
    )
    await repo.upsert_signature(sig)
    await repo.upsert_signature(sig)
    sigs, total = await repo.list_signatures(alarm_type="QoS")
    assert total == 1
    assert sigs[0].occurrence_count == 2


@pytest.mark.asyncio
async def test_observed_chatter_null_mo_partial_index_real_postgres(pool):
    repo = PgObservedChatterRepository(pool)
    sig = ChatterSignature(
        managed_object_id=None, alarm_type="SrcChatter", event_type="x", trail_id="t1"
    )
    await repo.upsert_signature(sig)
    await repo.upsert_signature(sig)
    sigs, _ = await repo.list_signatures(alarm_type="SrcChatter")
    assert len(sigs) == 1 and sigs[0].occurrence_count == 2 and sigs[0].managed_object_id is None
