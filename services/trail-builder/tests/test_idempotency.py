"""IO-layer unit tests for ``IdempotencyStore`` (AC-7).

``IdempotencyStore`` backs the at-least-once dedupe on the envelope ``eventId``:
``mark_processed`` is the atomic insert-if-absent point so a redelivered
``topology.changed`` does not produce a second build / second ``trails.built``.
Run against the in-memory SQLite ``engine`` fixture.
"""

from __future__ import annotations

from trailbuilder.idempotency import IdempotencyStore


def test_seen_false_before_mark(engine) -> None:
    """AC-7: an event id is not 'seen' until it has been marked processed."""
    store = IdempotencyStore(engine)
    assert store.seen("evt-1") is False


def test_mark_processed_then_seen(engine) -> None:
    """AC-7: marking an event records it; subsequent seen() returns True."""
    store = IdempotencyStore(engine)
    assert store.mark_processed("evt-1", "snap-1", "core-ip") is True
    assert store.seen("evt-1") is True


def test_mark_processed_is_idempotent_on_redelivery(engine) -> None:
    """AC-7: a redelivered event (same eventId) loses the insert race -> False."""
    store = IdempotencyStore(engine)
    assert store.mark_processed("evt-1", "snap-1", "core-ip") is True
    # Same envelope id delivered again: no-op, returns False (no second build).
    assert store.mark_processed("evt-1", "snap-1", "core-ip") is False
    assert store.seen("evt-1") is True


def test_distinct_event_ids_are_independent(engine) -> None:
    """Distinct event ids are tracked independently (no false dedupe)."""
    store = IdempotencyStore(engine)
    assert store.mark_processed("evt-1", "snap-1", "core-ip") is True
    assert store.mark_processed("evt-2", "snap-1", "core-ip") is True
    assert store.seen("evt-1") is True
    assert store.seen("evt-2") is True
    assert store.seen("evt-3") is False
