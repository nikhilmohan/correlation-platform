"""DA-3c allowed-lateness / bounded-reorder finalization tests.

These prove the fix for the REAL root cause behind the P2 chain's collapsed retention: the
production ``alarms.enriched`` topic is keyed by ``managedObjectId`` (Enrichment), so a single
trail's alarms — which span many managed objects (Node, LineCard, Port, Interface, IPLink,
IGPAdjacency, LSP, FiberSpan…) — hash to DIFFERENT Kafka partitions and therefore arrive
**interleaved and out of event-time order** on the consumed stream. In a history batch replay the
corpus spans ~24 h of ``raisedAt``, so a trail's window members are scattered widely in arrival
order and forward watermark jumps in one partition precede the arrival of co-bucket siblings on
others.

Negative control (the OLD eager-watermark DA-3b model): finalizing bucket ``B`` as soon as ANY
alarm in a strictly-later bucket arrived for the trail. Under out-of-order arrival that closes
lower buckets PREMATURELY (as singletons) before their siblings land, then re-opens + re-finalizes
them as further singletons → thousands of DBSCAN-noise singletons, retention ~0.4%. Each test below
carries an explicit assertion that the OLD model fragments the same input, so it is a genuine
negative control (it would FAIL under DA-3b), and PASSES under DA-3c.
"""

from __future__ import annotations

import random

import pytest

from noise_filter.config import ModelParams
from noise_filter.windowing import TrailWindow, TrailWindower

from .conftest import build_pipeline
from .fixtures import BASE_TIME, make_alarm

WINDOW = 600  # seconds; one bucket == 10 minutes of event time


def _bucket_of(offset_seconds: float, window: int = WINDOW) -> int:
    return int((BASE_TIME.timestamp() + offset_seconds) // window)


def _eager_watermark_finalize(
    alarms_with_wall: list[tuple[object, float]],
) -> list[TrailWindow]:
    """Reference model of the OLD DA-3b eager watermark (the NEGATIVE CONTROL).

    Finalizes a (trailId, bucket) as soon as the per-trail high watermark reaches ``bucket + 1``
    (lag 0). This is exactly the pre-fix behaviour; used to prove the current out-of-order input
    fragments under it so the DA-3c tests are true negative controls (not vacuous).
    """
    windows: dict[tuple[str, int], TrailWindow] = {}
    watermark: dict[str, int] = {}
    finalized: list[TrailWindow] = []
    for alarm, _wall in alarms_with_wall:
        for trail_id in alarm.trailIds:  # type: ignore[attr-defined]
            bucket = int(alarm.raisedAt.timestamp() // WINDOW)  # type: ignore[attr-defined]
            prev = watermark.get(trail_id)
            if prev is None or bucket > prev:
                watermark[trail_id] = bucket
            win = windows.get((trail_id, bucket))
            if win is None:
                win = TrailWindow(
                    trail_id=trail_id, bucket_index=bucket, window_size_seconds=WINDOW
                )
                windows[(trail_id, bucket)] = win
            win.add(alarm)  # type: ignore[arg-type]
        # Eager finalize: any bucket the watermark has passed closes NOW.
        for key in list(windows):
            t, b = key
            if watermark.get(t, b) >= b + 1:
                finalized.append(windows.pop(key))
    finalized.extend(windows.values())
    return finalized


def test_out_of_order_same_bucket_cascade_stays_one_window() -> None:
    """A same-bucket cascade fed INTERLEAVED with other buckets/trails + forward watermark jumps
    ends up in ONE finalized window — NOT fragmented into singletons.

    NEGATIVE CONTROL: the reference eager-watermark model fragments the same interleaved input into
    multiple sub-``min_samples`` windows for the cascade's bucket; DA-3c keeps all N together.
    """
    # The cascade: N alarms of trail t1, ALL in the same event-time bucket B.
    cascade = [
        make_alarm(alarm_id=f"casc-{i}", raised_offset_seconds=float(i), trail_ids=["t1"])
        for i in range(8)
    ]
    # Interleave with a FORWARD-JUMPING alarm on t1 in a far-later bucket (arrives BEFORE some
    # cascade siblings — the exact premature-close trigger), plus noise on another trail.
    jumper = make_alarm(
        alarm_id="t1-jump",
        raised_offset_seconds=float(50 * WINDOW + 3),  # bucket B+50 on t1
        trail_ids=["t1"],
    )
    other = make_alarm(alarm_id="t2-x", raised_offset_seconds=1.0, trail_ids=["t2"])

    # Arrival order (out of event-time order): a couple of cascade, THEN the forward jumper, THEN
    # the rest of the cascade "late", interleaved with the other-trail alarm.
    arrival: list[tuple[object, float]] = []
    arrival.append((cascade[0], 1000.0))
    arrival.append((cascade[1], 1000.1))
    arrival.append((jumper, 1000.2))  # forward jump BEFORE the rest of B arrives
    arrival.append((other, 1000.3))
    for i, a in enumerate(cascade[2:], start=1):
        arrival.append((a, 1000.3 + i * 0.1))  # the late siblings

    # --- Negative control: the OLD eager watermark fragments the cascade ---
    old = _eager_watermark_finalize(arrival)
    old_cascade_windows = [
        w for w in old if w.trail_id == "t1" and w.bucket_index == _bucket_of(0.0)
    ]
    old_cascade_alarms = sum(len(w.alarms) for w in old_cascade_windows)
    # Under the old model the bucket closes early then the late siblings re-open a fresh window:
    # the cascade is split (more than one window and/or the first window holds < N).
    assert not (
        len(old_cascade_windows) == 1 and old_cascade_alarms == len(cascade)
    ), "eager-watermark negative control should FRAGMENT the cascade but did not"

    # --- DA-3c: allowed-lateness keeps it whole ---
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        allowed_lateness_buckets=6,
        idle_grace_seconds=10.0,
        backstop_seconds=300,
    )
    for alarm, wall in arrival:
        windower.add(alarm, now=wall)  # type: ignore[arg-type]
        # The poll loop runs pop_finalized frequently; nothing for bucket B should close while its
        # siblings are still arriving (idle grace not elapsed + watermark margin on B not exceeded
        # relative to B's OWN last add).
        windower.pop_finalized(now=wall)

    remaining = windower.drain_all()
    cascade_windows = [
        w for w in remaining if w.trail_id == "t1" and w.bucket_index == _bucket_of(0.0)
    ]
    assert len(cascade_windows) == 1
    assert {a.alarmId for a in cascade_windows[0].alarms} == {f"casc-{i}" for i in range(8)}


@pytest.mark.asyncio
async def test_out_of_order_cascade_clusters_together_end_to_end(
    run_repo, chatter_repo, metrics
) -> None:
    """Full pipeline: an out-of-order-delivered same-bucket cascade clusters as ONE (not noise).

    Proves the retention win end-to-end — the same alarms the eager model would have starved into
    DBSCAN-noise singletons (min_samples=3) are grouped and emitted as one TransactionEvent.
    """
    cascade = [
        make_alarm(alarm_id=f"c{i}", raised_offset_seconds=float(i), trail_ids=["t1"])
        for i in range(6)
    ]
    jumper = make_alarm(alarm_id="jump", raised_offset_seconds=float(30 * WINDOW), trail_ids=["t1"])
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        allowed_lateness_buckets=6,
        idle_grace_seconds=10.0,
        backstop_seconds=300,
    )
    # Interleave: 2 members, the forward jumper, then the rest — shuffled arrival.
    order = [cascade[0], cascade[1], jumper, cascade[4], cascade[2], cascade[5], cascade[3]]
    wall = 500.0
    finalized_mid: list[TrailWindow] = []
    for a in order:
        windower.add(a, now=wall)
        finalized_mid.extend(windower.pop_finalized(now=wall))
        wall += 0.5
    # No cascade-bucket window finalized mid-stream (its members were still arriving).
    mid_cascade = [
        w for w in finalized_mid if w.trail_id == "t1" and w.bucket_index == _bucket_of(0.0)
    ]
    assert mid_cascade == []

    remaining = windower.drain_all()
    win = next(w for w in remaining if w.trail_id == "t1" and w.bucket_index == _bucket_of(0.0))
    assert len(win.alarms) == 6

    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=WINDOW),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
    )
    out = await pipe.process_window(win)
    assert len(out.events) == 1
    assert set(out.events[0].payload.alarmIds) == {a.alarmId for a in cascade}


def test_wide_reorder_batch_each_group_finalizes_whole() -> None:
    """P2-style batch: many (trail,bucket) groups over a wide raisedAt span, delivered SHUFFLED.

    Every (trail,bucket) group must finalize WHOLE (all its members together); the count of
    fragmented / singleton finalizations must be near zero. This mirrors the real batch replay
    where a corpus spanning many buckets arrives in arrival-shuffled (managedObjectId-partitioned)
    order.

    NEGATIVE CONTROL: the eager-watermark reference model fragments this same shuffled input into
    many more windows than there are true (trail,bucket) groups.
    """
    rng = random.Random(1234)
    n_trails = 5
    buckets_per_trail = 20
    members_per_group = 6

    # Build the "true" groups: (trail, bucket) -> set of alarmIds.
    truth: dict[tuple[str, int], set[str]] = {}
    all_alarms = []
    for t in range(n_trails):
        trail = f"tr{t}"
        for b in range(buckets_per_trail):
            base = float(b * WINDOW + 1)  # one alarm per bucket-window, spread within it
            gid = (trail, _bucket_of(base))
            truth.setdefault(gid, set())
            for m in range(members_per_group):
                aid = f"{trail}-b{b}-m{m}"
                truth[gid].add(aid)
                all_alarms.append(
                    make_alarm(
                        alarm_id=aid,
                        raised_offset_seconds=base + m * 3.0,  # within the same bucket
                        trail_ids=[trail],
                    )
                )

    # SHUFFLE arrival order (out of event-time order — the real partition interleaving).
    rng.shuffle(all_alarms)

    # --- Negative control: eager watermark over the shuffled stream ---
    old = _eager_watermark_finalize([(a, 0.0) for a in all_alarms])
    old_group_count = len({(w.trail_id, w.bucket_index) for w in old})
    old_window_count = len(old)
    # Fragmentation: eager model produces MORE windows than distinct true groups (buckets re-open).
    assert (
        old_window_count > old_group_count
    ), "eager-watermark negative control should over-fragment the shuffled batch"

    # --- DA-3c: feed shuffled, advancing a monotone wall clock; drain at end-of-batch ---
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        allowed_lateness_buckets=6,
        idle_grace_seconds=10.0,
        backstop_seconds=100_000,  # long: this test drains at end-of-stream
    )
    wall = 0.0
    early: list[TrailWindow] = []
    for a in all_alarms:
        windower.add(a, now=wall)
        early.extend(windower.pop_finalized(now=wall))
        wall += 0.001  # dense arrival; wall barely advances (well under idle grace)
    remaining = windower.drain_all()
    finalized = early + remaining

    # One finalized window per true (trail,bucket) group, each WHOLE.
    by_group: dict[tuple[str, int], list[TrailWindow]] = {}
    for w in finalized:
        by_group.setdefault((w.trail_id, w.bucket_index), []).append(w)

    assert set(by_group) == set(truth)
    reopened_or_split = 0
    for gid, wins in by_group.items():
        if len(wins) != 1:
            reopened_or_split += 1
            continue
        if {a.alarmId for a in wins[0].alarms} != truth[gid]:
            reopened_or_split += 1
    assert reopened_or_split == 0, f"{reopened_or_split} groups fragmented under DA-3c"


def test_finalized_window_alarms_are_event_time_sorted() -> None:
    """buffer-and-sort: a finalized window's ``sorted_alarms`` is ordered by raisedAt regardless of
    the (out-of-order) arrival order."""
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        allowed_lateness_buckets=0,
        idle_grace_seconds=0.0,
        backstop_seconds=5,
    )
    # Add in reverse event-time order (out of order).
    for off in (30.0, 10.0, 20.0, 5.0):
        windower.add(make_alarm(alarm_id=f"a{int(off)}", raised_offset_seconds=off), now=0.0)
    win = windower.drain_all()[0]
    offsets = [(a.raisedAt - BASE_TIME).total_seconds() for a in win.sorted_alarms()]
    assert offsets == sorted(offsets)


def test_memory_bound_force_finalizes_oldest_windows_never_drops() -> None:
    """The max_open_windows cap force-finalizes the least-recently-added windows (never drops)."""
    forced: list[tuple[str, int]] = []
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        allowed_lateness_buckets=100,  # so nothing finalizes via the idle path
        idle_grace_seconds=100_000.0,
        backstop_seconds=100_000,  # so nothing finalizes via the backstop
        max_open_windows=3,
        on_force_finalize=lambda t, b: forced.append((t, b)),
    )
    # Open 5 distinct (trail, bucket) windows on distinct trails; cap is 3.
    added_ids = set()
    for i in range(5):
        aid = f"m{i}"
        added_ids.add(aid)
        windower.add(
            make_alarm(alarm_id=aid, raised_offset_seconds=float(i), trail_ids=[f"tr{i}"]),
            now=float(i),  # increasing last-add so eviction order is deterministic (oldest first)
        )
    evicted = windower.pop_finalized(now=10.0)
    # 5 opened, cap 3 -> 2 force-finalized (the 2 oldest), emitted not dropped.
    assert len(evicted) == 2
    assert forced == [("tr0", _bucket_of(0.0)), ("tr1", _bucket_of(1.0))]
    # No alarm lost: evicted windows + still-open windows cover all added ids.
    still_open = windower.drain_all()
    all_out_ids = {a.alarmId for w in evicted + still_open for a in w.alarms}
    assert all_out_ids == added_ids


def test_live_mode_finalizes_within_bound_no_excess_latency() -> None:
    """Live mode (event time ~= wall clock): a bucket finalizes promptly once idle, WITHOUT waiting
    for the long backstop — allowed-lateness must not add excessive live latency.

    A single live alarm's bucket finalizes after allowed_lateness buckets of event-time advance
    (driven by subsequent live alarms) + the short idle grace — bounded, far below the backstop.
    """
    lateness = 2
    grace = 15.0
    backstop = 3600
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        allowed_lateness_buckets=lateness,
        idle_grace_seconds=grace,
        backstop_seconds=backstop,
    )
    # Live: raisedAt tracks wall-clock. First alarm in bucket B at wall t=0.
    windower.add(make_alarm(alarm_id="live0", raised_offset_seconds=1.0), now=0.0)
    b = _bucket_of(1.0)
    # Subsequent live alarms advance event time (and wall clock) past B by > lateness buckets.
    for k in range(1, lateness + 2):
        windower.add(
            make_alarm(alarm_id=f"liveN{k}", raised_offset_seconds=float(k * WINDOW + 1)),
            now=float(k),  # a few wall seconds later
        )
    # Poll after the short idle grace elapsed (wall = grace + a bit), FAR below the 3600 s backstop.
    finalized = windower.pop_finalized(now=grace + 1.0)
    b_windows = [w for w in finalized if w.bucket_index == b]
    assert len(b_windows) == 1
    assert {a.alarmId for a in b_windows[0].alarms} == {"live0"}
    # It finalized via the idle path, NOT the backstop (poll time << backstop).
    assert grace + 1.0 < backstop
