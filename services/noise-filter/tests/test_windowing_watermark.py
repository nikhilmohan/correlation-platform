"""Event-time watermark finalization tests (Defect #5 — batch-replay windowing).

NOTE: DA-3b's eager watermark is superseded by DA-3c (allowed-lateness / bounded-reorder — see
``test_windowing_out_of_order.py`` and the DA-3c module docstring). These tests remain valid: they
exercise the DA-3c model with the *zero-lateness* special case (``allowed_lateness_buckets=0``,
``idle_grace_seconds=0``, via the ``watermark_lag_buckets`` back-compat alias), where per-bucket
idleness reduces to "the bucket's own last add is behind the current watermark". They continue to
prove same-bucket members never fragment mid-trickle over wall-clock.

These tests prove the fix for the wall-clock-inactivity finalization defect: a single logical
``(trailId, bucket)`` window whose member alarms dribble into the windower over wall-clock LONGER
than the old 5 s grace must still be grouped together (not finalized mid-trickle into DBSCAN-noise
singletons). Finalization is driven by event-time progress with a wall-clock backstop for
idle/end-of-stream only.

The ``test_dribble_*`` cases are the NEGATIVE CONTROL for the old code: under the old
wall-clock-grace TrailWindower (``grace_seconds=5``, finalize when
``now - last_added >= grace``), advancing the injected clock by > 5 s between adds while keeping
``raisedAt`` in one bucket finalized each alarm as its own singleton — so ``pop_finalized`` would
have returned N one-alarm windows and DBSCAN(min_samples=3) would have dropped them all. Under the
new watermark model they stay in ONE window and cluster together.
"""

from __future__ import annotations

import pytest

from noise_filter.config import ModelParams
from noise_filter.windowing import TrailWindower

from .conftest import build_pipeline
from .fixtures import BASE_TIME, make_alarm, storm
from .helpers import make_window  # noqa: F401 — kept for parity with sibling tests

WINDOW = 600  # seconds; one bucket == 10 minutes of event time


def _bucket_of(offset_seconds: float, window: int = WINDOW) -> int:
    return int((BASE_TIME.timestamp() + offset_seconds) // window)


def test_dribble_over_wall_clock_stays_one_window() -> None:
    """A logical window's members dribbling in over > old-5s grace stay in ONE window.

    NEGATIVE CONTROL: with the OLD wall-clock-grace windower, advancing ``now`` by 8 s between
    adds (> 5 s grace) while keeping every ``raisedAt`` in the SAME bucket would have finalized
    each alarm into its own singleton on the intervening ``pop_finalized``. Here, because the
    event-time watermark never advances past the bucket, nothing finalizes mid-trickle.
    """
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=0,
        backstop_seconds=60,
    )
    # Five alarms, ALL in the same event-time bucket (raisedAt within a few seconds), but arriving
    # 8 wall-clock seconds apart — mimicking Enrichment's ~20 s self-clear-hold dribble in batch.
    wall = 1000.0
    for i in range(5):
        windower.add(
            make_alarm(alarm_id=f"d{i}", raised_offset_seconds=float(i)),  # same bucket
            now=wall,
        )
        # The consumer loop polls (and calls pop_finalized) ~every second between alarms. Under the
        # OLD wall-clock code, a poll at last_added + 5 s would have flushed the prior alarm as a
        # singleton BEFORE the next alarm arrived (the defect). Model those intervening polls: with
        # the watermark model nothing finalizes because event time never advances past the bucket.
        for step in range(1, 8):
            assert windower.pop_finalized(now=wall + step) == []
        wall += 8.0  # advance wall-clock by MORE than the old 5 s grace

    # End-of-stream: drain. All five must be in ONE window (never prematurely split).
    remaining = windower.drain_all()
    assert len(remaining) == 1
    assert {a.alarmId for a in remaining[0].alarms} == {f"d{i}" for i in range(5)}


@pytest.mark.asyncio
async def test_dribble_then_clusters_together(run_repo, chatter_repo, metrics) -> None:
    """Full pipeline: dribbled same-bucket storm stays grouped and DBSCAN keeps it as ONE cluster.

    Proves the retention win end-to-end: the same alarms that the old code would have starved into
    singletons (DBSCAN min_samples=3 => all noise, retention ~0) are here clustered together.
    """
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=0,
        backstop_seconds=60,
    )
    alarms = storm(6, spread=5.0)  # dense storm, all within one bucket
    wall = 500.0
    finalized_mid = []
    for a in alarms:
        windower.add(a, now=wall)
        # Intervening poll-loop cycles (the negative control against the old 5 s wall-clock grace).
        for step in range(1, 8):
            finalized_mid.extend(windower.pop_finalized(now=wall + step))
        wall += 8.0  # > old grace between each add
    # Nothing finalized mid-trickle.
    assert finalized_mid == []

    windows = windower.drain_all()
    assert len(windows) == 1
    win = windows[0]
    assert len(win.alarms) == 6

    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=WINDOW),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
    )
    out = await pipe.process_window(win)
    assert len(out.events) == 1
    assert set(out.events[0].payload.alarmIds) == {a.alarmId for a in alarms}


def test_watermark_finalizes_bucket_when_later_bucket_arrives() -> None:
    """Bucket B stays open until an alarm in B+1 arrives; then B finalizes with all its members."""
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=0,
        backstop_seconds=3600,  # backstop effectively disabled for this test
    )
    # Two alarms in bucket B.
    windower.add(make_alarm(alarm_id="b0", raised_offset_seconds=1.0), now=0.0)
    windower.add(make_alarm(alarm_id="b1", raised_offset_seconds=2.0), now=0.0)
    # No later bucket yet -> B not finalized (event time has not advanced past B).
    assert windower.pop_finalized(now=1.0) == []

    # An alarm in bucket B+1 arrives -> watermark advances past B -> B finalizes with BOTH members.
    windower.add(make_alarm(alarm_id="next", raised_offset_seconds=float(WINDOW + 1)), now=0.0)
    finalized = windower.pop_finalized(now=1.0)
    assert len(finalized) == 1
    assert finalized[0].bucket_index == _bucket_of(1.0)
    assert {a.alarmId for a in finalized[0].alarms} == {"b0", "b1"}

    # B+1 itself is still open (no successor yet).
    assert windower.pop_finalized(now=1.0) == []


def test_watermark_lag_defers_finalization() -> None:
    """A watermark lag of 1 keeps bucket B open until event time reaches B+2 (out-of-order)."""
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=1,
        backstop_seconds=3600,
    )
    windower.add(make_alarm(alarm_id="b0", raised_offset_seconds=1.0), now=0.0)
    # Bucket B+1 arrives: with lag=1, B is NOT yet finalized (need watermark >= B+2).
    windower.add(make_alarm(alarm_id="b1", raised_offset_seconds=float(WINDOW + 1)), now=0.0)
    assert windower.pop_finalized(now=0.0) == []
    # Bucket B+2 arrives: now watermark >= B+2 -> B finalizes.
    windower.add(make_alarm(alarm_id="b2", raised_offset_seconds=float(2 * WINDOW + 1)), now=0.0)
    finalized = windower.pop_finalized(now=0.0)
    assert {w.bucket_index for w in finalized} == {_bucket_of(1.0)}


def test_backstop_flushes_idle_final_bucket() -> None:
    """A truly-final bucket with no successor flushes once the wall-clock backstop elapses."""
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=0,
        backstop_seconds=30,
    )
    windower.add(make_alarm(alarm_id="only", raised_offset_seconds=1.0), now=100.0)
    # Before the backstop: still open (no successor advanced the watermark).
    assert windower.pop_finalized(now=120.0) == []
    # After the backstop (>= 30 s idle): flushed.
    finalized = windower.pop_finalized(now=131.0)
    assert len(finalized) == 1
    assert {a.alarmId for a in finalized[0].alarms} == {"only"}


def test_live_mode_finalizes_promptly_no_latency_regression() -> None:
    """Live mode: event time ~= wall clock, so the next real-time alarm finalizes the prior bucket.

    Asserts a window finalizes as soon as the stream crosses into the next bucket (bounded by one
    windowSize of event time), WITHOUT waiting for the long wall-clock backstop — no latency
    regression vs. the intent of prompt live finalization.
    """
    backstop = 600
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=0,
        backstop_seconds=backstop,
    )
    # Live: raisedAt tracks wall-clock. Alarm in bucket B at wall t=0.
    windower.add(make_alarm(alarm_id="live0", raised_offset_seconds=5.0), now=0.0)
    # Next live alarm crosses into bucket B+1 at wall t only slightly later (well under backstop).
    windower.add(make_alarm(alarm_id="live1", raised_offset_seconds=float(WINDOW + 5)), now=2.0)
    finalized = windower.pop_finalized(now=2.0)
    # B finalized promptly (t=2 s, far below the 600 s backstop) purely from event-time advance.
    assert len(finalized) == 1
    assert {a.alarmId for a in finalized[0].alarms} == {"live0"}


def test_late_alarm_reopens_finalized_bucket_not_silent_singleton() -> None:
    """A late alarm for an already-finalized bucket re-opens it (callback fired), not dropped."""
    reopened: list[tuple[str, int]] = []
    windower = TrailWindower(
        window_size_provider=lambda: WINDOW,
        watermark_lag_buckets=0,
        backstop_seconds=3600,
        on_reopen=lambda trail, bucket: reopened.append((trail, bucket)),
    )
    b = _bucket_of(1.0)
    windower.add(make_alarm(alarm_id="b0", raised_offset_seconds=1.0), now=0.0)
    # Advance event time to finalize bucket B.
    windower.add(make_alarm(alarm_id="next", raised_offset_seconds=float(WINDOW + 1)), now=0.0)
    assert len(windower.pop_finalized(now=0.0)) == 1  # B finalized

    # A LATE alarm for bucket B arrives -> re-open (callback), not a silent lost singleton.
    windower.add(make_alarm(alarm_id="late", raised_offset_seconds=2.0), now=0.0)
    assert reopened == [("t1", b)]
    reopened_windows = windower.drain_all()
    reopened_b = [w for w in reopened_windows if w.bucket_index == b]
    assert len(reopened_b) == 1
    assert {a.alarmId for a in reopened_b[0].alarms} == {"late"}
