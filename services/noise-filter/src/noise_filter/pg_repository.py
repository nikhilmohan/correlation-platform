"""asyncpg-backed implementations of the run-stats + observed-chatter repositories (DA-14).

These talk to the NF-owned ``noise_filter`` PostgreSQL schema (``nf_run_stats`` and
``nf_observed_chatter``). Parameterized SQL, no ORM. A connection/query error on a READ raises
:class:`RepositoryUnavailable` (-> HTTP 503). Writes are best-effort (the caller — the recorders
— catches their failures). Run-stats insert is idempotent via ``ON CONFLICT (run_id) DO NOTHING``;
chatter upsert increments the count on the partial-unique chatter key.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from .repository import RepositoryUnavailable
from .stats import ChatterSignature, RunStatsRow

_RUN_COLS = (
    "run_id, run_timestamp, trail_id, snapshot_id, domain, window_start, window_end, "
    "eps, min_samples, window_size_seconds, algorithm, alarms_in, clusters_formed, "
    "alarms_kept, alarms_dropped, noise_ratio, storm_max_cluster_size, "
    "storm_reduction_ratio, retention_vs_oracle, hop_feature_enabled"
)


def _run_row_from_record(rec: Any) -> RunStatsRow:
    return RunStatsRow(
        run_id=str(rec["run_id"]),
        run_timestamp=rec["run_timestamp"],
        trail_id=rec["trail_id"],
        snapshot_id=rec["snapshot_id"],
        domain=rec["domain"],
        window_start=rec["window_start"],
        window_end=rec["window_end"],
        eps=rec["eps"],
        min_samples=rec["min_samples"],
        window_size_seconds=rec["window_size_seconds"],
        algorithm=rec["algorithm"],
        alarms_in=rec["alarms_in"],
        clusters_formed=rec["clusters_formed"],
        alarms_kept=rec["alarms_kept"],
        alarms_dropped=rec["alarms_dropped"],
        noise_ratio=rec["noise_ratio"],
        storm_max_cluster_size=rec["storm_max_cluster_size"],
        storm_reduction_ratio=rec["storm_reduction_ratio"],
        retention_vs_oracle=rec["retention_vs_oracle"],
        hop_feature_enabled=rec["hop_feature_enabled"],
    )


class PgRunStatsRepository:
    """asyncpg run-stats repository over ``noise_filter.nf_run_stats``."""

    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def insert_run(self, row: RunStatsRow) -> None:
        sql = (
            f"INSERT INTO noise_filter.nf_run_stats ({_RUN_COLS}) "
            "VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20) "
            "ON CONFLICT (run_id) DO NOTHING"
        )
        async with self._pool.acquire() as conn:
            await conn.execute(
                sql,
                row.run_id,
                row.run_timestamp,
                row.trail_id,
                row.snapshot_id,
                row.domain,
                row.window_start,
                row.window_end,
                row.eps,
                row.min_samples,
                row.window_size_seconds,
                row.algorithm,
                row.alarms_in,
                row.clusters_formed,
                row.alarms_kept,
                row.alarms_dropped,
                row.noise_ratio,
                row.storm_max_cluster_size,
                row.storm_reduction_ratio,
                row.retention_vs_oracle,
                row.hop_feature_enabled,
            )

    async def get_run(self, run_id: str) -> RunStatsRow | None:
        sql = f"SELECT {_RUN_COLS} FROM noise_filter.nf_run_stats WHERE run_id = $1"
        try:
            async with self._pool.acquire() as conn:
                rec = await conn.fetchrow(sql, run_id)
        except Exception as exc:  # noqa: BLE001
            raise RepositoryUnavailable(str(exc)) from exc
        return _run_row_from_record(rec) if rec else None

    async def list_runs(
        self,
        *,
        trail_id: str | None = None,
        from_ts: datetime | None = None,
        to_ts: datetime | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> tuple[list[RunStatsRow], int]:
        where: list[str] = []
        args: list[Any] = []
        if trail_id is not None:
            args.append(trail_id)
            where.append(f"trail_id = ${len(args)}")
        if from_ts is not None:
            args.append(from_ts)
            where.append(f"run_timestamp >= ${len(args)}")
        if to_ts is not None:
            args.append(to_ts)
            where.append(f"run_timestamp <= ${len(args)}")
        clause = (" WHERE " + " AND ".join(where)) if where else ""
        count_sql = f"SELECT count(*) AS c FROM noise_filter.nf_run_stats{clause}"
        args.append(limit)
        args.append(offset)
        list_sql = (
            f"SELECT {_RUN_COLS} FROM noise_filter.nf_run_stats{clause} "
            f"ORDER BY run_timestamp DESC, run_id DESC LIMIT ${len(args) - 1} OFFSET ${len(args)}"
        )
        try:
            async with self._pool.acquire() as conn:
                total = await conn.fetchval(count_sql, *args[:-2])
                recs = await conn.fetch(list_sql, *args)
        except Exception as exc:  # noqa: BLE001
            raise RepositoryUnavailable(str(exc)) from exc
        return [_run_row_from_record(r) for r in recs], int(total)


class PgObservedChatterRepository:
    """asyncpg observed-chatter repository over ``noise_filter.nf_observed_chatter``."""

    def __init__(self, pool: Any) -> None:
        self._pool = pool

    async def upsert_signature(self, sig: ChatterSignature) -> None:
        # Two conflict targets depending on NULL managedObjectId (partial unique indexes).
        if sig.managed_object_id is not None:
            conflict = "(managed_object_id, alarm_type, event_type, trail_id)"
        else:
            conflict = "(alarm_type, event_type, trail_id) WHERE managed_object_id IS NULL"
        sql = (
            "INSERT INTO noise_filter.nf_observed_chatter "
            "(managed_object_id, alarm_type, event_type, trail_id, occurrence_count, "
            " first_seen, last_seen) VALUES ($1,$2,$3,$4,1,now(),now()) "
            f"ON CONFLICT {conflict} DO UPDATE SET "
            "occurrence_count = noise_filter.nf_observed_chatter.occurrence_count + 1, "
            "last_seen = now()"
        )
        async with self._pool.acquire() as conn:
            await conn.execute(
                sql, sig.managed_object_id, sig.alarm_type, sig.event_type, sig.trail_id
            )

    async def list_signatures(
        self,
        *,
        alarm_type: str | None = None,
        trail_id: str | None = None,
        min_occurrence: int = 1,
        limit: int = 50,
        offset: int = 0,
    ) -> tuple[list[ChatterSignature], int]:
        where: list[str] = ["occurrence_count >= $1"]
        args: list[Any] = [min_occurrence]
        if alarm_type is not None:
            args.append(alarm_type)
            where.append(f"alarm_type = ${len(args)}")
        if trail_id is not None:
            args.append(trail_id)
            where.append(f"trail_id = ${len(args)}")
        clause = " WHERE " + " AND ".join(where)
        count_sql = f"SELECT count(*) AS c FROM noise_filter.nf_observed_chatter{clause}"
        args.append(limit)
        args.append(offset)
        list_sql = (
            "SELECT managed_object_id, alarm_type, event_type, trail_id, occurrence_count, "
            f"first_seen, last_seen FROM noise_filter.nf_observed_chatter{clause} "
            f"ORDER BY occurrence_count DESC, last_seen DESC, id "
            f"LIMIT ${len(args) - 1} OFFSET ${len(args)}"
        )
        try:
            async with self._pool.acquire() as conn:
                total = await conn.fetchval(count_sql, *args[:-2])
                recs = await conn.fetch(list_sql, *args)
        except Exception as exc:  # noqa: BLE001
            raise RepositoryUnavailable(str(exc)) from exc
        sigs = [
            ChatterSignature(
                managed_object_id=r["managed_object_id"],
                alarm_type=r["alarm_type"],
                event_type=r["event_type"],
                trail_id=r["trail_id"],
                occurrence_count=r["occurrence_count"],
                first_seen=r["first_seen"],
                last_seen=r["last_seen"],
            )
            for r in recs
        ]
        return sigs, int(total)
