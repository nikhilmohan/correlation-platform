"""Trail-windowing + dedupe.

:class:`DedupeCache` drops second deliveries of an ``eventId`` (Kafka at-least-once).
:class:`TrailWindower` buckets each enriched :class:`AlarmEvent` per ``(trailId, bucketIndex)``
into coarse tumbling windows of width ``windowSize`` (Knowledge param), retaining the FULL
enriched alarm objects (so the emitter can populate the typed ``alarms[]`` array).

Finalization model (event-time watermark + wall-clock backstop)
---------------------------------------------------------------
The bucket index is derived from the alarm's **event time** (``raisedAt``), so a logical window
is a slice of *event time*, independent of when its member alarms physically arrive over
wall-clock. Finalization is therefore driven by **event-time progress**, not wall-clock idleness:

* Each trail tracks a **high watermark** = the highest bucket index seen for that trail. A bucket
  ``B`` for trail ``T`` is finalized once the stream for ``T`` has moved past ``B`` — i.e. an alarm
  with ``raisedAt`` in a strictly later bucket (``B + 1 + watermark_lag_buckets``) has arrived.
  This makes batch/history replay correct: buckets finalize as event time advances regardless of
  how their members dribble in over wall-clock (e.g. Enrichment's ~20 s self-clear hold), and keeps
  live mode correct too because there event time ≈ wall clock, so the next real-time alarm advances
  the watermark and finalizes the prior bucket promptly.
* A **wall-clock backstop** (``backstop_seconds``) flushes a bucket that has seen no successor for a
  long idle period (end-of-stream / genuinely-final bucket). It is deliberately set ABOVE the
  maximum upstream release cadence (Enrichment's self-clear hold + sweep, ~15-20 s) so it never
  fires mid-trickle — it exists only for the truly-final / idle case.

A late alarm for a bucket that was already finalized re-opens that ``(trailId, bucket)`` window
(logged/metric-counted) rather than silently starting a fresh sub-threshold singleton that DBSCAN
would drop; with a correctly-sized watermark lag this is rare.
"""

from __future__ import annotations

import time
from collections.abc import Callable
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
    """Maintains open per-``(trailId, bucketIndex)`` windows; finalizes on an event-time watermark.

    Finalization is driven by **event-time progress** (a per-trail high watermark computed from
    incoming ``raisedAt`` bucket indices), with a **wall-clock backstop** for idle/end-of-stream
    buckets. See the module docstring for the full model and the rationale (batch-replay members
    dribble in over wall-clock but share one event-time bucket, so they must not finalize
    prematurely on wall-clock idleness).

    Per-trail isolation: alarms on different trails never share a window (DA-3). An alarm carrying
    multiple ``trailIds`` is bucketed into each.

    Parameters
    ----------
    window_size_provider:
        ``() -> current windowSize`` (Knowledge param), read per add so refreshes apply.
    watermark_lag_buckets:
        How many *additional* whole buckets of event time must elapse past a bucket before it is
        finalized. ``0`` (default) finalizes bucket ``B`` as soon as an alarm lands in bucket
        ``B+1`` (or later) for that trail; a larger lag tolerates out-of-order event time.
    backstop_seconds:
        Wall-clock idle backstop. A bucket with no successor advancing the watermark is flushed once
        this many seconds of wall-clock have elapsed since its last add. MUST exceed the maximum
        upstream release cadence (Enrichment self-clear hold + sweep) so it never fires mid-trickle.
    on_reopen:
        Optional callback invoked with ``(trail_id, bucket_index)`` when a late alarm re-opens an
        already-finalized bucket (for logging / metrics). Never raises into the add path.
    """

    def __init__(
        self,
        *,
        window_size_provider: Callable[[], int],
        watermark_lag_buckets: int = 0,
        backstop_seconds: int = 60,
        on_reopen: Callable[[str, int], None] | None = None,
    ) -> None:
        self._window_size_provider = window_size_provider
        self._watermark_lag = max(0, watermark_lag_buckets)
        self._backstop = backstop_seconds
        self._on_reopen = on_reopen
        self._windows: dict[tuple[str, int], TrailWindow] = {}
        # Per-trail high watermark: the highest bucket index observed for that trail (event time).
        self._watermark: dict[str, int] = {}
        # Highest bucket index already finalized per trail, so a late alarm can be detected as a
        # re-open of a finalized bucket (vs. a normal not-yet-finalized add).
        self._finalized_high: dict[str, int] = {}

    def _bucket_index(self, raised_at: datetime, window_size: int) -> int:
        epoch = raised_at.astimezone(UTC).timestamp()
        return int(epoch // window_size)

    def add(self, alarm: AlarmEvent, *, now: float | None = None) -> None:
        """Bucket ``alarm`` per each ``trailId`` it carries, advancing the event-time watermark."""
        window_size = self._window_size_provider()
        clock = time.monotonic() if now is None else now
        for trail_id in alarm.trailIds:
            bucket = self._bucket_index(alarm.raisedAt, window_size)
            # Advance the per-trail event-time high watermark.
            prev = self._watermark.get(trail_id)
            if prev is None or bucket > prev:
                self._watermark[trail_id] = bucket

            key = (trail_id, bucket)
            win = self._windows.get(key)
            if win is None:
                # A late alarm for a bucket already finalized: re-open rather than start a fresh
                # singleton that DBSCAN would drop. With a correct watermark lag this is rare.
                fin_high = self._finalized_high.get(trail_id)
                if fin_high is not None and bucket <= fin_high and self._on_reopen is not None:
                    self._on_reopen(trail_id, bucket)
                win = TrailWindow(
                    trail_id=trail_id,
                    bucket_index=bucket,
                    window_size_seconds=window_size,
                    opened_monotonic=clock,
                    last_added_monotonic=clock,
                )
                self._windows[key] = win
            win.add(alarm, now=clock)

    def pop_finalized(self, *, now: float | None = None) -> list[TrailWindow]:
        """Return + remove windows finalized by the event-time watermark or the wall-clock backstop.

        A ``(trailId, bucket)`` window finalizes when EITHER:

        * event time has advanced past it — the trail's high watermark has reached
          ``bucket + 1 + watermark_lag_buckets`` (the stream has moved into a later bucket); OR
        * the wall-clock backstop has elapsed since its last add (idle / end-of-stream flush).
        """
        clock = time.monotonic() if now is None else now
        ready: list[TrailWindow] = []
        for key, win in list(self._windows.items()):
            trail_id, bucket = key
            watermark = self._watermark.get(trail_id, bucket)
            event_time_done = watermark >= bucket + 1 + self._watermark_lag
            backstop_done = (clock - win.last_added_monotonic) >= self._backstop
            if event_time_done or backstop_done:
                ready.append(win)
                del self._windows[key]
                prev_fin = self._finalized_high.get(trail_id)
                if prev_fin is None or bucket > prev_fin:
                    self._finalized_high[trail_id] = bucket
        return ready

    def drain_all(self) -> list[TrailWindow]:
        """Finalize every open window immediately (used in tests + shutdown / end-of-stream)."""
        ready = list(self._windows.values())
        for trail_id, bucket in list(self._windows):
            prev_fin = self._finalized_high.get(trail_id)
            if prev_fin is None or bucket > prev_fin:
                self._finalized_high[trail_id] = bucket
        self._windows.clear()
        return ready
