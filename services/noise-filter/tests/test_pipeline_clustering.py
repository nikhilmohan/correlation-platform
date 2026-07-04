"""Clustering / storm-reduction acceptance criteria (AC 1, 2, 3, 8, 9, 15, 17)."""

from __future__ import annotations

import math

import pytest

from noise_filter.config import FeatureSettings, ModelParams

from .conftest import build_pipeline, make_trail_ctx
from .fixtures import chatty_alarm, fiber_cut_cascade, make_alarm, storm
from .helpers import make_window

# Standard "normal" params: relative-time-dominant encoding clusters tight cascades/storms.
NORMAL = ModelParams(eps=1.0, min_samples=3, window_size_seconds=600)
TIGHT = ModelParams(eps=0.05, min_samples=6, window_size_seconds=600)
LOOSE = ModelParams(eps=2.0, min_samples=2, window_size_seconds=600)


@pytest.mark.asyncio
async def test_chatty_alarm_dropped_from_cascade(run_repo, chatter_repo, metrics):
    """AC-1: one TransactionEvent with the cascade ids, excluding the chatty alarm id."""
    alarms = fiber_cut_cascade() + [chatty_alarm()]
    win = make_window(alarms)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 1
    ids = set(out.events[0].payload.alarmIds)
    assert ids == {"los-1", "link-1", "adj-1", "lsp-1"}
    assert "chatter-1" not in ids


@pytest.mark.asyncio
async def test_cascade_cluster_preserved_intact(run_repo, chatter_repo, metrics):
    """AC-2: only the cascade alarms -> one event containing every cascade alarm, none dropped."""
    alarms = fiber_cut_cascade()
    win = make_window(alarms)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 1
    assert set(out.events[0].payload.alarmIds) == {a.alarmId for a in alarms}


@pytest.mark.asyncio
async def test_dbscan_params_from_knowledge_change_results(run_repo, chatter_repo, metrics):
    """AC-3: tight params produce fewer/no dense clusters; loose produce at least one."""
    alarms = fiber_cut_cascade()
    win_tight = make_window(alarms)
    win_loose = make_window(alarms)

    tight_pipe = build_pipeline(
        params=TIGHT, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    loose_pipe = build_pipeline(
        params=LOOSE, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    tight_out = await tight_pipe.process_window(win_tight)
    loose_out = await loose_pipe.process_window(win_loose)

    assert len(tight_out.events) < len(loose_out.events) or len(tight_out.events) == 0
    assert len(loose_out.events) >= 1


@pytest.mark.asyncio
async def test_knowledge_param_refresh_changes_labeling(run_repo, chatter_repo, metrics):
    """AC-8: changing params at runtime (param-store swap) changes labeling for a fixed input."""
    from noise_filter.config import FeatureConfig, ParamStore

    alarms = fiber_cut_cascade()
    param_store = ParamStore(TIGHT)
    feature_config = FeatureConfig(FeatureSettings.fallback())

    # Build a pipeline whose param store we mutate (simulating a knowledge.updated refresh).
    from noise_filter.cluster import Clusterer
    from noise_filter.emit import TransactionEmitter
    from noise_filter.features import FeatureVectorizer
    from noise_filter.pipeline import Pipeline
    from noise_filter.stats import ObservedChatterRecorder, RunStatsRecorder

    from .conftest import FakeTrailBuilderClient

    tb = FakeTrailBuilderClient(make_trail_ctx())
    pipe = Pipeline(
        param_store=param_store,
        feature_config=feature_config,
        vectorizer=FeatureVectorizer(metrics=metrics),
        clusterer=Clusterer(metrics=metrics),
        emitter=TransactionEmitter(metrics=metrics),
        run_stats_recorder=RunStatsRecorder(run_repo, metrics=metrics),
        chatter_recorder=ObservedChatterRecorder(chatter_repo, metrics=metrics),
        trail_builder_client=tb,
        metrics=metrics,
    )

    before = await pipe.process_window(make_window(alarms))
    # Runtime refresh: swap to loose params (no restart).
    param_store.set(LOOSE)
    after = await pipe.process_window(make_window(alarms))

    assert len(before.events) != len(after.events)
    assert len(after.events) >= 1


@pytest.mark.asyncio
async def test_noise_filter_effectiveness_meets_thresholds(run_repo, chatter_repo, metrics):
    """AC-9: keep >= ceil(M*0.9) real ids, drop all but <= floor(N*0.1) noise ids."""
    real = storm(10, trail_id="t1", start=0.0, spread=4.0)  # M real cascade alarms
    # N coincidental noise alarms, temporally scattered far from the storm core.
    noise = [
        make_alarm(
            alarm_id=f"noise-{i}",
            managed_object_id=f"Port:noise{i}",
            alarm_type="QualityOfServiceAlarm",
            event_type="qualityOfServiceAlarm",
            perceived_severity="warning",
            raised_offset_seconds=60.0 + i * 30.0,
            trail_ids=["t1"],
        )
        for i in range(4)
    ]
    M, N = len(real), len(noise)
    win = make_window(real + noise)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    out = await pipe.process_window(win)

    kept = {aid for ev in out.events for aid in ev.payload.alarmIds}
    real_ids = {a.alarmId for a in real}
    noise_ids = {a.alarmId for a in noise}
    kept_real = len(kept & real_ids)
    kept_noise = len(kept & noise_ids)

    assert kept_real >= math.ceil(M * 0.9)
    assert kept_noise <= math.floor(N * 0.1)


@pytest.mark.asyncio
async def test_storm_reduction_single_fault_one_transaction(run_repo, chatter_repo, metrics):
    """AC-15: N>=10 storm from one fault -> ONE event; reduction ratio >= 5; row N/1."""
    alarms = storm(12, trail_id="t1", start=0.0, spread=5.0)
    win = make_window(alarms)
    pipe = build_pipeline(
        params=NORMAL, run_repo=run_repo, chatter_repo=chatter_repo, metrics=metrics
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 1
    rows, _ = await run_repo.list_runs()
    assert len(rows) == 1
    row = rows[0]
    assert row.alarms_in == 12
    assert row.clusters_formed == 1
    assert (row.alarms_in / row.clusters_formed) >= 5


@pytest.mark.asyncio
async def test_long_cascade_preserved_whole(run_repo, chatter_repo, metrics):
    """AC-17: multi-hop cascade with legitimate inter-layer timing gaps stays ONE event, whole."""
    # fiber-cut root, then LinkDown, then AdjDown after convergence delay, then LSPDown.
    cascade = [
        make_alarm(
            alarm_id="root",
            managed_object_id="FiberSpan:f1",
            alarm_type="FiberFault",
            perceived_severity="critical",
            raised_offset_seconds=0.0,
        ),
        make_alarm(
            alarm_id="link",
            managed_object_id="IPLink:l1",
            alarm_type="LinkDown",
            raised_offset_seconds=2.0,
        ),
        make_alarm(
            alarm_id="adj",
            managed_object_id="IGPAdjacency:a1",
            alarm_type="AdjDown",
            raised_offset_seconds=6.0,
        ),  # late, protocol convergence delay
        make_alarm(
            alarm_id="lsp",
            managed_object_id="LSP:s1",
            alarm_type="LSPDown",
            raised_offset_seconds=9.0,
        ),  # far hop, late
    ]
    win = make_window(cascade)
    # Loose-ish eps to bridge the legitimate inter-layer gaps (Knowledge-tuned in practice).
    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
    )
    out = await pipe.process_window(win)

    assert len(out.events) == 1
    assert set(out.events[0].payload.alarmIds) == {"root", "link", "adj", "lsp"}
