"""Trail-windowing + dedupe.

:class:`DedupeCache` drops second deliveries of an ``eventId`` (Kafka at-least-once).
:class:`TrailWindower` buckets each enriched :class:`AlarmEvent` per ``(trailId, bucketIndex)``
into coarse tumbling windows of width ``windowSize`` (Knowledge param), retaining the FULL
enriched alarm objects (so the emitter can populate the typed ``alarms[]`` array).
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from acp_event_model import AlarmEvent


class DedupeCache:
    """Bounded TTL set of seen ``eventId``s. Second delivery of an id returns ``False``."""

    def __init__(self, ttl_seconds: int = 900, max_size: int = 100_000) -> None:
        self._ttl = ttl_seconds
        self._max_size = max_size
        self._seen: dict[str, float] = {}

    def seen_before(self, event_id: str, *, now: float | None = None) -> bool:
        """Record ``event_id``; return ``True`` if it was already present (a duplicate)."""
        clock = time.monotonic() if now is None else now
        self._evict(clock)
        if event_id in self._seen:
            return True
        if len(self._seen) >= self._max_size:
            # Drop the oldest entry to stay bounded.
            oldest = min(self._seen, key=self._seen.__getitem__)
            del self._seen[oldest]
        self._seen[event_id] = clock
        return False

    def _evict(self, clock: float) -> None:
        cutoff = clock - self._ttl
        expired = [k for k, t in self._seen.items() if t < cutoff]
        for k in expired:
            del self._seen[k]


@dataclass
class TrailWindow:
    """An open trail-window: the full enriched alarms bucketed for one ``(trailId, bucket)``."""

    trail_id: str
    bucket_index: int
    window_size_seconds: int
    alarms: list[AlarmEvent] = field(default_factory=list)
    opened_monotonic: float = field(default_factory=time.monotonic)
    last_added_monotonic: float = field(default_factory=time.monotonic)

    @property
    def window_start(self) -> datetime:
        return datetime.fromtimestamp(self.bucket_index * self.window_size_seconds, tz=UTC)

    @property
    def window_end(self) -> datetime:
        return self.window_start + timedelta(seconds=self.window_size_seconds)

    def add(self, alarm: AlarmEvent, *, now: float | None = None) -> None:
        # Guard against the same alarmId landing twice (belt-and-braces with DedupeCache).
        if any(a.alarmId == alarm.alarmId for a in self.alarms):
            return
        self.alarms.append(alarm)
        self.last_added_monotonic = time.monotonic() if now is None else now


class TrailWindower:
    """Maintains open per-``(trailId, bucketIndex)`` windows; finalizes on a grace trigger.

    Per-trail isolation: alarms on different trails never share a window (DA-3). An alarm
    carrying multiple ``trailIds`` is bucketed into each.
    """

    def __init__(self, *, window_size_provider, grace_seconds: int = 5) -> None:
        # window_size_provider() -> current windowSize (read per add so refreshes apply).
        self._window_size_provider = window_size_provider
        self._grace = grace_seconds
        self._windows: dict[tuple[str, int], TrailWindow] = {}

    def _bucket_index(self, raised_at: datetime, window_size: int) -> int:
        epoch = raised_at.astimezone(UTC).timestamp()
        return int(epoch // window_size)

    def add(self, alarm: AlarmEvent, *, now: float | None = None) -> None:
        """Bucket ``alarm`` into a window per each ``trailId`` it carries."""
        window_size = self._window_size_provider()
        for trail_id in alarm.trailIds:
            bucket = self._bucket_index(alarm.raisedAt, window_size)
            key = (trail_id, bucket)
            win = self._windows.get(key)
            if win is None:
                win = TrailWindow(
                    trail_id=trail_id,
                    bucket_index=bucket,
                    window_size_seconds=window_size,
                )
                self._windows[key] = win
            win.add(alarm, now=now)

    def pop_finalized(self, *, now: float | None = None) -> list[TrailWindow]:
        """Return + remove windows whose grace period since last-added has elapsed."""
        clock = time.monotonic() if now is None else now
        ready: list[TrailWindow] = []
        for key, win in list(self._windows.items()):
            if clock - win.last_added_monotonic >= self._grace:
                ready.append(win)
                del self._windows[key]
        return ready

    def drain_all(self) -> list[TrailWindow]:
        """Finalize every open window immediately (used in tests + shutdown)."""
        ready = list(self._windows.values())
        self._windows.clear()
        return ready
