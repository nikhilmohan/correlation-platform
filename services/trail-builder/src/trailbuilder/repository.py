"""``TrailRepository`` — transactional persist, supersession, retention, reads.

Persists a build's trails for ``(domain, snapshot_id)`` in one transaction that
first deletes any prior rows for that exact pair (so re-delivery/rebuild never
duplicates), then inserts the new set, then prunes snapshots beyond the retention
window. Also serves the three query-API reads.
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import Engine, delete, func, insert, select

from .db import tables
from .models import Trail, object_type_of

# Snapshot-scope sentinels — the same ``current|previous`` model the Topology
# Service exposes on its query API. The web-ui (compose sets ``SNAPSHOT_ID=current``)
# queries trails with the literal ``current``; the trail store persists each build
# under the CONCRETE snapshotId carried by the triggering ``topology.changed`` event
# (e.g. ``SNAP-0439f418-...``), never "current". These sentinels are therefore
# resolved to that concrete id at query time (#226). Any other token is treated as
# a concrete snapshotId and passed through unchanged.
SNAPSHOT_CURRENT = "current"
SNAPSHOT_PREVIOUS = "previous"
_SNAPSHOT_SENTINELS = frozenset({SNAPSHOT_CURRENT, SNAPSHOT_PREVIOUS})


class TrailRepository:
    """Reads/writes the ``trailbuilder`` trail store."""

    def __init__(self, engine: Engine, retention_snapshots: int = 2) -> None:
        self._engine = engine
        self._retention = max(1, retention_snapshots)

    # --- Writes ---

    def persist_build(self, domain: str, snapshot_id: str, trails: list[Trail]) -> None:
        """Replace the trail set for ``(domain, snapshot_id)`` then prune old snapshots.

        One transaction: supersede the exact pair, insert the new trails +
        members, then drop snapshots beyond the retention window for the domain.
        """
        now = datetime.now(UTC)
        with self._engine.begin() as conn:
            # Supersede the exact (domain, snapshot) pair (members cascade).
            conn.execute(
                delete(tables.trail).where(
                    tables.trail.c.domain == domain,
                    tables.trail.c.snapshot_id == snapshot_id,
                )
            )
            for t in trails:
                conn.execute(
                    insert(tables.trail).values(
                        trail_id=t.trail_id,
                        domain=t.domain,
                        snapshot_id=t.snapshot_id,
                        seed_managed_object_id=t.seed_managed_object_id,
                        igp_area=t.igp_area,
                        srlg_group=t.srlg_group,
                        member_count=t.member_count,
                        built_at=now,
                    )
                )
                conn.execute(
                    insert(tables.trail_member),
                    [
                        {
                            "trail_id": t.trail_id,
                            "domain": t.domain,
                            "snapshot_id": t.snapshot_id,
                            "managed_object_id": m,
                            "object_type": object_type_of(m),
                        }
                        for m in t.members
                    ],
                )
            self._prune_old_snapshots(conn, domain)

    def _prune_old_snapshots(self, conn, domain: str) -> None:  # type: ignore[no-untyped-def]
        """Keep the most-recent ``retention`` snapshots per domain; drop older ones."""
        rows = conn.execute(
            select(tables.trail.c.snapshot_id, func.max(tables.trail.c.built_at).label("ts"))
            .where(tables.trail.c.domain == domain)
            .group_by(tables.trail.c.snapshot_id)
            .order_by(func.max(tables.trail.c.built_at).desc())
        ).all()
        keep = {r.snapshot_id for r in rows[: self._retention]}
        stale = [r.snapshot_id for r in rows if r.snapshot_id not in keep]
        if stale:
            conn.execute(
                delete(tables.trail).where(
                    tables.trail.c.domain == domain,
                    tables.trail.c.snapshot_id.in_(stale),
                )
            )

    # --- Reads (query API) ---

    def resolve_snapshot_id(self, snapshot_id: str, domain: str) -> str | None:
        """Resolve a ``current``/``previous`` sentinel to a concrete persisted snapshotId.

        Consistent with the Topology Service's ``current|previous`` snapshot model
        (#226). ``current`` → the most-recently-built snapshot for ``domain``;
        ``previous`` → the one built immediately before it. A non-sentinel token is
        a concrete snapshotId and is returned unchanged (callers query it directly).

        Returns ``None`` when a sentinel cannot be resolved (no snapshot persisted
        for the domain, or no second snapshot for ``previous``) so the caller can
        return an empty, non-error result rather than crashing.

        Snapshots are ranked by ``built_at`` desc — the same ordering the retention
        prune uses, so "current" here is the snapshot the latest build persisted.
        """
        if snapshot_id not in _SNAPSHOT_SENTINELS:
            return snapshot_id
        ordered = self._snapshots_newest_first(domain)
        index = 0 if snapshot_id == SNAPSHOT_CURRENT else 1
        if index < len(ordered):
            return ordered[index]
        return None

    def _snapshots_newest_first(self, domain: str) -> list[str]:
        """Distinct snapshotIds for ``domain``, most-recently-built first."""
        with self._engine.connect() as conn:
            rows = conn.execute(
                select(tables.trail.c.snapshot_id, func.max(tables.trail.c.built_at).label("ts"))
                .where(tables.trail.c.domain == domain)
                .group_by(tables.trail.c.snapshot_id)
                .order_by(func.max(tables.trail.c.built_at).desc())
            ).all()
        return [r.snapshot_id for r in rows]

    def trail_ids_for_object(self, managed_object_id: str, domain: str) -> list[str]:
        """All trail ids the object belongs to within ``domain`` (possibly empty)."""
        with self._engine.connect() as conn:
            rows = conn.execute(
                select(tables.trail_member.c.trail_id)
                .where(
                    tables.trail_member.c.domain == domain,
                    tables.trail_member.c.managed_object_id == managed_object_id,
                )
                .distinct()
                .order_by(tables.trail_member.c.trail_id)
            ).all()
        return [r.trail_id for r in rows]

    def get_trail(self, trail_id: str) -> Trail | None:
        """Return the full trail (with members) or ``None`` if unknown."""
        with self._engine.connect() as conn:
            row = conn.execute(
                select(tables.trail).where(tables.trail.c.trail_id == trail_id)
            ).first()
            if row is None:
                return None
            members = conn.execute(
                select(tables.trail_member.c.managed_object_id)
                .where(tables.trail_member.c.trail_id == trail_id)
                .order_by(tables.trail_member.c.managed_object_id)
            ).all()
        return Trail(
            trail_id=row.trail_id,
            domain=row.domain,
            snapshot_id=row.snapshot_id,
            seed_managed_object_id=row.seed_managed_object_id,
            members=tuple(m.managed_object_id for m in members),
            igp_area=row.igp_area,
            srlg_group=row.srlg_group,
        )

    def list_trails(
        self, snapshot_id: str, domain: str, limit: int | None = None, offset: int = 0
    ) -> list[Trail]:
        """All trail summaries for ``(snapshot_id, domain)`` (members not loaded)."""
        with self._engine.connect() as conn:
            stmt = (
                select(tables.trail)
                .where(
                    tables.trail.c.snapshot_id == snapshot_id,
                    tables.trail.c.domain == domain,
                )
                .order_by(tables.trail.c.trail_id)
                .offset(offset)
            )
            if limit is not None:
                stmt = stmt.limit(limit)
            rows = conn.execute(stmt).all()
        return [
            Trail(
                trail_id=r.trail_id,
                domain=r.domain,
                snapshot_id=r.snapshot_id,
                seed_managed_object_id=r.seed_managed_object_id,
                members=(),
                igp_area=r.igp_area,
                srlg_group=r.srlg_group,
                persisted_member_count=r.member_count,
            )
            for r in rows
        ]
