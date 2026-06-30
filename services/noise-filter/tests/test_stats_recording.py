"""Run-stats + observed-chatter recording ACs (AC-11, 13, 19, 20, 23) + supporting."""

from __future__ import annotations

import pytest

from noise_filter.config import ModelParams
from noise_filter.repository import (
    InMemoryObservedChatterRepository,
    InMemoryRunStatsRepository,
)
from noise_filter.stats import isclose

from .conftest import build_pipeline
from .fixtures import chatty_alarm, fiber_cut_cascade
from .helpers import make_window

NORMAL = ModelParams(eps=1.0, min_samples=3, window_size_seconds=600)


@pytest.mark.asyncio
async def test_run_stats_row_correctness(run_repo, chatter_repo, metrics):
    """AC-11: one row with correct alarmsIn/Dropped/Kept/clustersFormed/noiseRatio/params."""
    alarms = fiber_cut_cascade() + [chatty_alarm()]
    win = make_window(alarms)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    out = await pipe.process_window(win)

    rows, total = await run_repo.list_runs()
    assert total == 1
    row = rows[0]
    kept_ids = {aid for ev in out.events for aid in ev.payload.alarmIds}
    assert row.alarms_in == len(alarms)
    assert row.alarms_kept == len(kept_ids)
    assert row.alarms_dropped == row.alarms_in - row.alarms_kept
    assert row.clusters_formed == len(out.events)
    assert isclose(row.noise_ratio, row.alarms_dropped / row.alarms_in, abs_tol=1e-9)
    assert row.eps == NORMAL.eps
    assert row.min_samples == NORMAL.min_samples
    assert row.window_size_seconds == NORMAL.window_size_seconds
    assert row.algorithm == NORMAL.algorithm


@pytest.mark.asyncio
async def test_stats_write_failure_does_not_block_emission(chatter_repo, metrics):
    """AC-13: run-stats write failing still emits TransactionEvents; failure metric increments."""
    failing_repo = InMemoryRunStatsRepository(fail=True)
    win = make_window(fiber_cut_cascade())
    pipe = build_pipeline(
        params=NORMAL, run_repo=failing_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 1  # emission unaffected
    assert out.run_stats_written is False
    assert metrics.stats_write_failures._value.get() == 1


@pytest.mark.asyncio
async def test_observed_chatter_signature_recorded_from_dropped_noise(
    run_repo, chatter_repo, metrics
):
    """AC-19: dropped chatty alarm yields a signature row; kept cluster members do not."""
    chatty = chatty_alarm()
    alarms = fiber_cut_cascade() + [chatty]
    win = make_window(alarms)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    await pipe.process_window(win)

    sigs, total = await chatter_repo.list_signatures()
    assert total == 1
    sig = sigs[0]
    assert sig.managed_object_id == chatty.managedObjectId
    assert sig.alarm_type == chatty.alarmType
    assert sig.event_type == chatty.eventType
    assert sig.trail_id == "t1"
    assert sig.occurrence_count >= 1
    assert sig.first_seen is not None and sig.last_seen is not None
    # No kept (cluster-member) alarm produced a signature.
    kept_types = {a.alarmType for a in fiber_cut_cascade()}
    assert all(s.alarm_type not in kept_types for s in sigs)


@pytest.mark.asyncio
async def test_observed_chatter_occurrence_count_aggregates_across_runs(
    run_repo, chatter_repo, metrics
):
    """AC-20: same signature labeled noise in N windows -> ONE row, occurrence_count == N."""
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    N = 3
    for _ in range(N):
        win = make_window(fiber_cut_cascade() + [chatty_alarm()])
        await pipe.process_window(win)

    sigs, total = await chatter_repo.list_signatures()
    assert total == 1  # one row, not N
    assert sigs[0].occurrence_count == N
    assert sigs[0].first_seen <= sigs[0].last_seen


@pytest.mark.asyncio
async def test_observed_chatter_write_failure_does_not_block_emission(run_repo, metrics):
    """AC-23: observed-chatter write failing still emits TransactionEvents; failure metric inc."""
    failing_chatter = InMemoryObservedChatterRepository(fail=True)
    win = make_window(fiber_cut_cascade() + [chatty_alarm()])
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=failing_chatter, metrics=metrics
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 1
    assert out.chatter_signatures == 0
    assert metrics.chatter_write_failures._value.get() == 1


@pytest.mark.asyncio
async def test_observed_chatter_counted_once_per_window(run_repo, chatter_repo, metrics):
    """Algorithm step F2: a signature on several noise alarms in ONE window counts once."""
    from .fixtures import make_alarm

    # Two noise alarms in ONE window sharing the SAME (managedObjectId, alarmType, eventType).
    noise = [
        make_alarm(
            alarm_id=f"n{i}",
            managed_object_id="Port:same",
            alarm_type="QoS",
            event_type="qualityOfServiceAlarm",
            perceived_severity="warning",
            raised_offset_seconds=40.0 + i * 40.0,
        )
        for i in range(2)
    ]
    win = make_window(fiber_cut_cascade() + noise)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    await pipe.process_window(win)
    sigs, total = await chatter_repo.list_signatures()
    same = [s for s in sigs if s.managed_object_id == "Port:same"]
    assert len(same) == 1
    assert same[0].occurrence_count == 1  # counted once per window, not twice


@pytest.mark.asyncio
async def test_observed_chatter_null_managed_object_upserts_one_row(chatter_repo):
    """DA-16: source-level chatter with null managedObjectId upserts ONE row (partial index)."""
    from noise_filter.stats import ChatterSignature

    sig1 = ChatterSignature(
        managed_object_id=None, alarm_type="QoS", event_type="qualityOfServiceAlarm", trail_id="t1"
    )
    sig2 = ChatterSignature(
        managed_object_id=None, alarm_type="QoS", event_type="qualityOfServiceAlarm", trail_id="t1"
    )
    await chatter_repo.upsert_signature(sig1)
    await chatter_repo.upsert_signature(sig2)
    sigs, total = await chatter_repo.list_signatures()
    assert total == 1
    assert sigs[0].occurrence_count == 2
    assert sigs[0].managed_object_id is None


@pytest.mark.asyncio
async def test_observed_chatter_signature_keyed_on_managed_object_and_alarm_type(
    chatter_repo, metrics
):
    """DA-16: the chatter key matches Enrichment's known-chatter shape; alarmType verbatim."""
    from noise_filter.stats import ObservedChatterRecorder

    chatty = chatty_alarm()
    rec = ObservedChatterRecorder(chatter_repo, metrics=metrics)
    n = await rec.record([chatty], trail_id="t1")
    assert n == 1
    sigs, _ = await chatter_repo.list_signatures()
    assert sigs[0].managed_object_id == chatty.managedObjectId
    assert sigs[0].alarm_type == chatty.alarmType  # verbatim mirror, not derived
