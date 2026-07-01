"""Trail-windowing + dedupe.

:class:`DedupeCache` drops second deliveries of an ``eventId`` (Kafka at-least-once).
:class:`TrailWindower` buckets each enriched :class:`AlarmEvent` per ``(trailId, bucketIndex)``
into coarse tumbling windows of width ``windowSize`` (Knowledge param), retaining the FULL
enriched alarm objects (so the emitter can populate the typed ``alarms[]`` array).

Finalization model — DA-3c (allowed-lateness / bounded-reorder + buffer-sort + backstop)
----------------------------------------------------------------------------------------
The bucket index is derived from the alarm's **event time** (``raisedAt``), so a logical window
is a slice of *event time*, independent of when its member alarms physically arrive over
wall-clock. The REAL topic (``alarms.enriched``) is keyed by ``managedObjectId`` by Enrichment,
so a single trail's alarms (which span many managed objects — Node, LineCard, Port, Interface,
IPLink, IGPAdjacency, LSP, FiberSpan…) hash to DIFFERENT Kafka partitions and therefore arrive
**interleaved and out of event-time order** on the consumed stream. During a history batch replay
spanning ~24 h of ``raisedAt``, a trail's window members are scattered widely in arrival order.

The earlier "eager watermark" model (DA-3b) closed bucket ``B`` as soon as an alarm in a
strictly-later bucket arrived for the trail. Under real out-of-order arrival that finalizes
buckets PREMATURELY: a forward jump on one partition closes lower buckets as singletons before
their co-bucket siblings land on other partitions; the siblings then arrive "late", re-open the
bucket, and re-finalize as further singletons. That collapsed retention (observed: ~3200
singleton windows, 1 cluster, ~0.4% trail-eligible retention).

DA-3c fixes this by finalizing on **per-bucket idleness with an allowed-lateness margin**, not on
watermark advance alone. A ``(trailId, bucket)`` is only finalized once it has gone quiet — no new
alarm has been assigned to *that bucket* — for long enough (measured on BOTH event-time watermark
progress and wall-clock) that any remaining cross-partition siblings have had time to land. Because
every alarm of a batch DOES eventually arrive (just out of order), the correct outcome is that each
``(trailId, bucket)`` collects ALL its members before it closes.

Rule — a ``(trailId, bucket)`` window finalizes when EITHER:

* **(a) allowed-lateness idle**: the trail's event-time high watermark has advanced at least
  ``allowed_lateness_buckets`` buckets beyond this bucket's *own last add* (i.e. the stream has
  moved well past it AND nothing new has landed in it since) **AND** at least ``idle_grace_seconds``
  of wall-clock have elapsed since its last add. Both conditions guard against wide reordering:
  the watermark margin absorbs event-time skew, the wall-clock grace absorbs arrival-time skew
  (Enrichment's self-clear hold, partition drain lag). Crucially this is keyed on the bucket's OWN
  last-add, so while co-bucket siblings keep arriving the bucket stays open; OR
* **(b) wall-clock backstop**: ``backstop_seconds`` of wall-clock have elapsed since its last add
  (end-of-stream / genuinely-idle flush). The backstop is the memory-safety valve and the
  final-bucket flush; it is set ABOVE the maximum upstream release cadence so it never fires while
  a bucket is still actively collecting siblings.

When a bucket finalizes, its retained alarms are **sorted by ``raisedAt``** (buffer-and-sort), so
arrival order never affects the emitted window's ordering. An out-of-order alarm whose ``raisedAt``
belongs to a still-open bucket lands in that bucket (not a fresh singleton) — that is the whole
point of the margin.

Memory bound
------------
Buffered buckets are bounded two ways: (1) the backstop always eventually evicts any bucket
``backstop_seconds`` after its last add, so a stalled/idle trail cannot pin memory forever; and
(2) ``max_open_windows`` caps the number of simultaneously-open ``(trailId, bucket)`` windows —
when exceeded, the least-recently-added windows are force-finalized (emitted, never dropped) and a
metric/log fires. A pathological stream therefore cannot OOM: worst case it force-finalizes early
(degrading toward the old fragmentation) but never grows unbounded and never silently drops alarms.

Reopen safety
-------------
A late alarm for a bucket that WAS already finalized re-opens that ``(trailId, bucket)`` window
(logged / metric-counted) rather than silently starting a fresh sub-threshold singleton that DBSCAN
would drop. With a correctly-sized allowed-lateness margin this is rare (it only happens beyond the
configured reorder tolerance).
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
    """An open trail-window: the full enriched alarms bucketed for one ``(trailId, bucket)``.

    ``last_added_watermark`` records the trail's event-time high watermark at the moment of this
    bucket's most recent add, so allowed-lateness idleness is measured against how far event time
    has since advanced beyond *this bucket's own* last member (not merely past the bucket boundary).
    """

    trail_id: str
    bucket_index: int
    window_size_seconds: int
    alarms: list[AlarmEvent] = field(default_factory=list)
    opened_monotonic: float = field(default_factory=time.monotonic)
    last_added_monotonic: float = field(default_factory=time.monotonic)
    last_added_watermark: int = 0

    @property
    def window_start(self) -> datetime:
        return datetime.fromtimestamp(self.bucket_index * self.window_size_seconds, tz=UTC)

    @property
    def window_end(self) -> datetime:
        return self.window_start + timedelta(seconds=self.window_size_seconds)

    def add(
        self, alarm: AlarmEvent, *, now: float | None = None, watermark: int | None = None
    ) -> None:
        # Guard against the same alarmId landing twice (belt-and-braces with DedupeCache).
        if any(a.alarmId == alarm.alarmId for a in self.alarms):
            return
        self.alarms.append(alarm)
        self.last_added_monotonic = time.monotonic() if now is None else now
        if watermark is not None:
            self.last_added_watermark = watermark

    def sorted_alarms(self) -> list[AlarmEvent]:
        """Return the buffered alarms ordered by ``raisedAt`` (buffer-and-sort, DA-3c).

        Arrival order is out-of-order under the real ``managedObjectId``-keyed partitioning, so the
        emitted window is deterministically event-time ordered regardless of how members arrived.
        """
        return sorted(self.alarms, key=lambda a: (a.raisedAt, a.alarmId))


class TrailWindower:
    """Open per-``(trailId, bucketIndex)`` windows; finalizes on allowed-lateness idleness (DA-3c).

    Finalization (DA-3c) is driven by **per-bucket idleness** with an **allowed-lateness margin**
    (measured on BOTH the per-trail event-time watermark AND wall-clock) plus a **wall-clock
    backstop** that is also the memory-safety valve. See the module docstring for the full model and
    rationale: the real ``alarms.enriched`` topic is keyed by ``managedObjectId``, so one trail's
    alarms are scattered across Kafka partitions and arrive interleaved / out of event-time order;
    a bucket must therefore stay open until its OWN members stop arriving, not merely until event
    time crosses the bucket boundary.

    Per-trail isolation: alarms on different trails never share a window (DA-3). An alarm carrying
    multiple ``trailIds`` is bucketed into each.

    Parameters
    ----------
    window_size_provider:
        ``() -> current windowSize`` (Knowledge param), read per add so refreshes apply.
    allowed_lateness_buckets:
        Reorder tolerance in *whole buckets of event time*. A bucket is eligible for the (a)
        idle-finalize path only once the trail's high watermark has advanced at least this many
        buckets beyond the bucket's OWN last add. Sized to absorb the maximum expected
        cross-partition event-time skew for a trail's alarms.
    idle_grace_seconds:
        Wall-clock grace that must also elapse since a bucket's last add before the (a) path
        finalizes it — absorbs arrival-time skew (partition drain lag, Enrichment self-clear hold)
        independently of event-time progress.
    backstop_seconds:
        Wall-clock backstop / memory valve. A bucket with no new member for this long is flushed
        regardless of watermark (end-of-stream / idle / final bucket). MUST exceed the maximum
        upstream release cadence so it never fires while a bucket is still actively collecting
        cross-partition siblings.
    max_open_windows:
        Hard cap on simultaneously-open ``(trailId, bucket)`` windows (memory bound). When exceeded,
        the least-recently-added windows are force-finalized (emitted, never dropped) so the buffer
        cannot grow unbounded.
    on_reopen:
        Optional callback ``(trail_id, bucket_index)`` when a late alarm re-opens an
        already-finalized bucket (logging / metrics). Never raises into the add path.
    on_force_finalize:
        Optional callback ``(trail_id, bucket_index)`` when the ``max_open_windows`` cap
        force-finalizes a window early (memory-pressure eviction). Never raises into the add path.
    """

    def __init__(
        self,
        *,
        window_size_provider: Callable[[], int],
        allowed_lateness_buckets: int = 0,
        idle_grace_seconds: float = 0.0,
        backstop_seconds: int = 60,
        max_open_windows: int = 200_000,
        on_reopen: Callable[[str, int], None] | None = None,
        on_force_finalize: Callable[[str, int], None] | None = None,
        # Back-compat alias: DA-3b's watermark lag maps onto the DA-3c allowed-lateness margin.
        watermark_lag_buckets: int | None = None,
    ) -> None:
        self._window_size_provider = window_size_provider
        lateness = (
            allowed_lateness_buckets if watermark_lag_buckets is None else watermark_lag_buckets
        )
        self._allowed_lateness = max(0, lateness)
        self._idle_grace = max(0.0, idle_grace_seconds)
        self._backstop = backstop_seconds
        self._max_open = max(1, max_open_windows)
        self._on_reopen = on_reopen
        self._on_force_finalize = on_force_finalize
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
            watermark = self._watermark[trail_id]

            key = (trail_id, bucket)
            win = self._windows.get(key)
            if win is None:
                # A late alarm for a bucket already finalized: re-open rather than start a fresh
                # singleton that DBSCAN would drop. With a correct lateness margin this is rare.
                fin_high = self._finalized_high.get(trail_id)
                if fin_high is not None and bucket <= fin_high and self._on_reopen is not None:
                    self._on_reopen(trail_id, bucket)
                win = TrailWindow(
                    trail_id=trail_id,
                    bucket_index=bucket,
                    window_size_seconds=window_size,
                    opened_monotonic=clock,
                    last_added_monotonic=clock,
                    last_added_watermark=watermark,
                )
                self._windows[key] = win
            win.add(alarm, now=clock, watermark=watermark)

    def _enforce_memory_bound(self) -> list[TrailWindow]:
        """Force-finalize the least-recently-added windows if the open-window cap is exceeded."""
        if len(self._windows) <= self._max_open:
            return []
        # Evict oldest-by-last-add first (LRU on activity) until back under the cap.
        ordered = sorted(self._windows.items(), key=lambda kv: kv[1].last_added_monotonic)
        evicted: list[TrailWindow] = []
        overflow = len(self._windows) - self._max_open
        for key, win in ordered[:overflow]:
            trail_id, bucket = key
            evicted.append(win)
            del self._windows[key]
            self._mark_finalized(trail_id, bucket)
            if self._on_force_finalize is not None:
                self._on_force_finalize(trail_id, bucket)
        return evicted

    def _mark_finalized(self, trail_id: str, bucket: int) -> None:
        prev_fin = self._finalized_high.get(trail_id)
        if prev_fin is None or bucket > prev_fin:
            self._finalized_high[trail_id] = bucket

    def pop_finalized(self, *, now: float | None = None) -> list[TrailWindow]:
        """Return + remove windows eligible for finalization (DA-3c allowed-lateness + backstop).

        A ``(trailId, bucket)`` window finalizes when EITHER:

        * **allowed-lateness idle** — the trail's high watermark has advanced at least
          ``allowed_lateness_buckets`` beyond the bucket's OWN last add AND ``idle_grace_seconds``
          of wall-clock have elapsed since that last add (nothing new has landed in the bucket for
          long enough that out-of-order siblings have all arrived); OR
        * **wall-clock backstop** — ``backstop_seconds`` of wall-clock elapsed since its last add
          (end-of-stream / idle / memory valve).

        Also enforces the ``max_open_windows`` memory bound by force-finalizing the oldest windows.
        """
        clock = time.monotonic() if now is None else now
        ready: list[TrailWindow] = []
        for key, win in list(self._windows.items()):
            trail_id, bucket = key
            watermark = self._watermark.get(trail_id, bucket)
            idle_seconds = clock - win.last_added_monotonic
            # (a) allowed-lateness idle: event time has moved well past THIS bucket's last member
            #     AND wall-clock grace has elapsed since it (so out-of-order siblings have landed).
            lateness_done = watermark >= win.last_added_watermark + 1 + self._allowed_lateness
            grace_done = idle_seconds >= self._idle_grace
            idle_finalize = lateness_done and grace_done
            # (b) backstop / memory valve.
            backstop_done = idle_seconds >= self._backstop
            if idle_finalize or backstop_done:
                ready.append(win)
                del self._windows[key]
                self._mark_finalized(trail_id, bucket)
        ready.extend(self._enforce_memory_bound())
        return ready

    def drain_all(self) -> list[TrailWindow]:
        """Finalize every open window immediately (used in tests + shutdown / end-of-stream)."""
        ready = list(self._windows.values())
        for trail_id, bucket in list(self._windows):
            self._mark_finalized(trail_id, bucket)
        self._windows.clear()
        return ready
