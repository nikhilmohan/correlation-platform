"""``IdempotencyStore`` — at-least-once dedupe on envelope ``eventId``.

Backed by the ``processed_event`` table; :meth:`mark_if_new` is an atomic
insert-if-absent: it returns ``True`` the first time an ``eventId`` is seen and
``False`` on every redelivery. The event is marked processed only after a
successful build (the caller controls when).
"""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import Engine, insert, select

from .db import tables


class IdempotencyStore:
    """Dedupe consumed events on their envelope ``eventId``."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def seen(self, event_id: str) -> bool:
        """Return ``True`` if ``event_id`` has already been processed."""
        with self._engine.connect() as conn:
            row = conn.execute(
                select(tables.processed_event.c.event_id).where(
                    tables.processed_event.c.event_id == event_id
                )
            ).first()
        return row is not None

    def mark_processed(self, event_id: str, snapshot_id: str, domain: str) -> bool:
        """Record ``event_id`` as processed. Returns ``False`` if already present.

        The insert is the atomic dedupe point: a concurrent or redelivered event
        with the same id loses the race and gets ``False``.
        """
        try:
            with self._engine.begin() as conn:
                conn.execute(
                    insert(tables.processed_event).values(
                        event_id=event_id,
                        snapshot_id=snapshot_id,
                        domain=domain,
                        processed_at=datetime.now(UTC),
                    )
                )
            return True
        except Exception:
            # Unique-violation (already processed) — idempotent no-op.
            return False
