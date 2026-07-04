"""Data-access layer for the two NF-owned PostgreSQL tables.

Two repository protocols (run-stats, observed-chatter) with two implementations:

* :class:`InMemoryRunStatsRepository` / :class:`InMemoryObservedChatterRepository` — the design's
  permitted in-memory stand-in for unit tests (no live PostgreSQL needed for the unit gate).
* :class:`PgRunStatsRepository` / :class:`PgObservedChatterRepository` — ``asyncpg``-backed
  (DA-14: asyncpg is Apache-2.0; psycopg3 is LGPL) production implementations.

Both write paths are best-effort; the read API depends on the store being reachable. A
``RepositoryUnavailable`` raised by a read maps to HTTP 503 in the API (EH-13, EH-17).
"""

from __future__ import annotations

from datetime import datetime
from typing import TYPE_CHECKING, Protocol, runtime_checkable

if TYPE_CHECKING:
    from .stats import ChatterSignature, RunStatsRow


class RepositoryUnavailable(RuntimeError):
    """Raised by a read when the backing store is unreachable (-> HTTP 503)."""


@runtime_checkable
class RunStatsRepository(Protocol):
    """Single owner of the ``nf_run_stats`` schema."""

    async def insert_run(self, row: RunStatsRow) -> None: ...

    async def get_run(self, run_id: str) -> RunStatsRow | None: ...

    async def list_runs(
        self,
        *,
        trail_id: str | None = None,
        from_ts: datetime | None = None,
        to_ts: datetime | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> tuple[list[RunStatsRow], int]: ...


@runtime_checkable
class ObservedChatterRepository(Protocol):
    """Single owner of the ``nf_observed_chatter`` schema."""

    async def upsert_signature(self, sig: ChatterSignature) -> None: ...

    async def list_signatures(
        self,
        *,
        alarm_type: str | None = None,
        trail_id: str | None = None,
        min_occurrence: int = 1,
        limit: int = 50,
        offset: int = 0,
    ) -> tuple[list[ChatterSignature], int]: ...


# --------------------------------------------------------------------------- #
# In-memory stand-ins (unit tests)                                            #
# --------------------------------------------------------------------------- #
class InMemoryRunStatsRepository:
    """In-memory run-stats store. ``insert_run`` is idempotent on ``run_id`` (ON CONFLICT
    DO NOTHING semantics). ``fail`` simulates a write/read failure for EH tests."""

    def __init__(self, *, fail: bool = False, read_fail: bool = False) -> None:
        self.fail = fail
        self.read_fail = read_fail
        self._rows: dict[str, RunStatsRow] = {}

    async def insert_run(self, row: RunStatsRow) -> None:
        if self.fail:
            raise RuntimeError("simulated run-stats write failure")
        self._rows.setdefault(row.run_id, row)  # ON CONFLICT (run_id) DO NOTHING

    async def get_run(self, run_id: str) -> RunStatsRow | None:
        if self.read_fail:
            raise RepositoryUnavailable("simulated DB unreachable")
        return self._rows.get(run_id)

    async def list_runs(
        self,
        *,
        trail_id: str | None = None,
        from_ts: datetime | None = None,
        to_ts: datetime | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> tuple[list[RunStatsRow], int]:
        if self.read_fail:
            raise RepositoryUnavailable("simulated DB unreachable")
        rows = list(self._rows.values())
        if trail_id is not None:
            rows = [r for r in rows if r.trail_id == trail_id]
        if from_ts is not None:
            rows = [r for r in rows if r.run_timestamp >= from_ts]
        if to_ts is not None:
            rows = [r for r in rows if r.run_timestamp <= to_ts]
        rows.sort(key=lambda r: (r.run_timestamp, r.run_id), reverse=True)
        total = len(rows)
        return rows[offset : offset + limit], total


class InMemoryObservedChatterRepository:
    """In-memory observed-chatter store. ``upsert_signature`` increments the count on the
    partial-unique chatter key (NULL managedObjectId is well-defined)."""

    def __init__(self, *, fail: bool = False, read_fail: bool = False) -> None:
        self.fail = fail
        self.read_fail = read_fail
        self._rows: dict[tuple[str | None, str, str, str | None], ChatterSignature] = {}

    async def upsert_signature(self, sig: ChatterSignature) -> None:
        if self.fail:
            raise RuntimeError("simulated observed-chatter write failure")
        key = (sig.managed_object_id, sig.alarm_type, sig.event_type, sig.trail_id)
        existing = self._rows.get(key)
        if existing is None:
            self._rows[key] = sig
        else:
            existing.occurrence_count += 1
            existing.last_seen = sig.last_seen

    async def list_signatures(
        self,
        *,
        alarm_type: str | None = None,
        trail_id: str | None = None,
        min_occurrence: int = 1,
        limit: int = 50,
        offset: int = 0,
    ) -> tuple[list[ChatterSignature], int]:
        if self.read_fail:
            raise RepositoryUnavailable("simulated DB unreachable")
        rows = list(self._rows.values())
        if alarm_type is not None:
            rows = [r for r in rows if r.alarm_type == alarm_type]
        if trail_id is not None:
            rows = [r for r in rows if r.trail_id == trail_id]
        rows = [r for r in rows if r.occurrence_count >= min_occurrence]
        rows.sort(key=lambda r: (r.occurrence_count, r.last_seen), reverse=True)
        total = len(rows)
        return rows[offset : offset + limit], total
