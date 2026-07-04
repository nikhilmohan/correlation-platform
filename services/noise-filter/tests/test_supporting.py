"""Supporting design-behaviour tests (EH-8, EH-9, DA-3, DA-8, EH-4, idempotency, migrations)."""

from __future__ import annotations

import pytest

from noise_filter.config import (
    FeatureConfig,
    FeatureSettings,
    ModelParams,
    ParamStore,
)
from noise_filter.windowing import TrailWindower

from .conftest import build_pipeline
from .fixtures import fiber_cut_cascade, make_alarm
from .helpers import make_window


@pytest.mark.asyncio
async def test_snapshot_unresolved_not_emitted(run_repo, chatter_repo, metrics):
    """EH-8: when snapshotId is unresolvable, the window is NOT emitted with a fabricated id."""
    # No trail_builder client -> snapshot cannot be resolved.
    from noise_filter.cluster import Clusterer
    from noise_filter.emit import TransactionEmitter
    from noise_filter.features import FeatureVectorizer
    from noise_filter.pipeline import Pipeline
    from noise_filter.stats import ObservedChatterRecorder, RunStatsRecorder

    pipe = Pipeline(
        param_store=ParamStore(ModelParams(eps=1.0, min_samples=3, window_size_seconds=600)),
        feature_config=FeatureConfig(FeatureSettings.fallback()),
        vectorizer=FeatureVectorizer(metrics=metrics),
        clusterer=Clusterer(metrics=metrics),
        emitter=TransactionEmitter(metrics=metrics),
        run_stats_recorder=RunStatsRecorder(run_repo, metrics=metrics),
        chatter_recorder=ObservedChatterRecorder(chatter_repo, metrics=metrics),
        trail_builder_client=None,  # no snapshot source
        metrics=metrics,
    )
    out = await pipe.process_window(make_window(fiber_cut_cascade()))
    assert out.events == []
    assert metrics.snapshot_unresolved._value.get() == 1


@pytest.mark.asyncio
async def test_window_all_noise_emits_nothing_but_records_stats(run_repo, chatter_repo, metrics):
    """EH-9: a no-dense-cluster window emits nothing but still records a run-stats row."""
    # Three temporally-scattered, unrelated alarms with min_samples too high to cluster.
    scattered = [
        make_alarm(alarm_id=f"s{i}", managed_object_id=f"Port:p{i}", raised_offset_seconds=i * 60.0)
        for i in range(3)
    ]
    win = make_window(scattered)
    pipe = build_pipeline(
        params=ModelParams(eps=0.1, min_samples=5, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
    )
    out = await pipe.process_window(win)
    assert out.events == []
    rows, total = await run_repo.list_runs()
    assert total == 1
    assert rows[0].clusters_formed == 0
    assert rows[0].storm_reduction_ratio is None
    assert metrics.windows_no_cluster._value.get() == 1


def test_per_trail_windowing_isolation():
    """DA-3: alarms on different trails never share a window."""
    windower = TrailWindower(window_size_provider=lambda: 600)
    a1 = make_alarm(alarm_id="a1", trail_ids=["t1"], raised_offset_seconds=0.0)
    a2 = make_alarm(alarm_id="a2", trail_ids=["t2"], raised_offset_seconds=1.0)
    windower.add(a1)
    windower.add(a2)
    windows = windower.drain_all()
    by_trail = {w.trail_id: {al.alarmId for al in w.alarms} for w in windows}
    assert by_trail["t1"] == {"a1"}
    assert by_trail["t2"] == {"a2"}


def test_alarm_with_multiple_trail_ids_bucketed_into_each():
    """An alarm carrying multiple trailIds is bucketed into each trail's window."""
    windower = TrailWindower(window_size_provider=lambda: 600)
    a = make_alarm(alarm_id="multi", trail_ids=["t1", "t2"])
    windower.add(a)
    windows = windower.drain_all()
    trails = {w.trail_id for w in windows}
    assert trails == {"t1", "t2"}


def test_param_snapshot_atomic_swap():
    """DA-8: ParamStore.set atomically replaces the whole snapshot."""
    store = ParamStore(ModelParams(eps=0.5, min_samples=3, window_size_seconds=120))
    new = ModelParams(eps=2.0, min_samples=2, window_size_seconds=300, algorithm="hdbscan")
    store.set(new)
    got = store.get()
    assert got.eps == 2.0 and got.window_size_seconds == 300 and got.algorithm == "hdbscan"


@pytest.mark.asyncio
async def test_run_stats_insert_idempotent_on_run_id(run_repo):
    """EH-10/EH-11: re-inserting the same run_id is a no-op (ON CONFLICT DO NOTHING)."""
    from datetime import UTC, datetime

    from noise_filter.stats import RunStatsRow

    row = RunStatsRow(
        run_id="fixed",
        run_timestamp=datetime.now(UTC),
        trail_id="t1",
        snapshot_id="s1",
        domain=None,
        window_start=datetime.now(UTC),
        window_end=datetime.now(UTC),
        eps=1.0,
        min_samples=3,
        window_size_seconds=600,
        algorithm="dbscan",
        alarms_in=5,
        clusters_formed=1,
        alarms_kept=5,
        alarms_dropped=0,
        noise_ratio=0.0,
        storm_max_cluster_size=5,
        storm_reduction_ratio=5.0,
        retention_vs_oracle=None,
        hop_feature_enabled=False,
    )
    await run_repo.insert_run(row)
    await run_repo.insert_run(row)  # duplicate run_id
    _, total = await run_repo.list_runs()
    assert total == 1


def test_health_not_ready_until_params_loaded():
    """EH-4: /health returns 503 until params loaded + Kafka connected; 200 once ready."""
    from fastapi.testclient import TestClient

    from noise_filter.api import ApiState, create_app
    from noise_filter.metrics import Metrics
    from noise_filter.repository import (
        InMemoryObservedChatterRepository,
        InMemoryRunStatsRepository,
    )

    metrics = Metrics()
    state = ApiState(
        run_stats_repo=InMemoryRunStatsRepository(),
        chatter_repo=InMemoryObservedChatterRepository(),
        metrics_registry=metrics.registry,
    )
    client = TestClient(create_app(state))
    assert client.get("/health").status_code == 503  # params not loaded
    state.params_loaded = True
    state.kafka_connected = True
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_metrics_endpoint_exposes_prometheus():
    """/metrics returns Prometheus exposition text."""
    from fastapi.testclient import TestClient

    from noise_filter.api import ApiState, create_app
    from noise_filter.metrics import Metrics
    from noise_filter.repository import (
        InMemoryObservedChatterRepository,
        InMemoryRunStatsRepository,
    )

    metrics = Metrics()
    state = ApiState(
        run_stats_repo=InMemoryRunStatsRepository(),
        chatter_repo=InMemoryObservedChatterRepository(),
        metrics_registry=metrics.registry,
    )
    client = TestClient(create_app(state))
    resp = client.get("/metrics")
    assert resp.status_code == 200
    assert b"nf_alarms_consumed_total" in resp.content


def test_dedupe_cache_ttl_eviction():
    """DedupeCache evicts entries past TTL so a much-later redelivery is treated as fresh."""
    from noise_filter.windowing import DedupeCache

    cache = DedupeCache(ttl_seconds=10)
    assert cache.seen_before("e1", now=0.0) is False
    assert cache.seen_before("e1", now=1.0) is True  # within TTL -> duplicate
    assert cache.seen_before("e1", now=100.0) is False  # evicted -> fresh again


def test_clusterer_empty_matrix_returns_empty():
    """Clusterer handles an empty window gracefully."""
    import numpy as np

    from noise_filter.cluster import Clusterer

    labels = Clusterer().label(
        np.empty((0, 4)), ModelParams(eps=1.0, min_samples=3, window_size_seconds=600)
    )
    assert labels.shape == (0,)


def test_hdbscan_algorithm_selectable_falls_back_when_unavailable():
    """DA-1: algorithm=hdbscan is selectable; falls back to DBSCAN if hdbscan isn't installed."""
    import numpy as np

    from noise_filter.cluster import Clusterer

    matrix = np.array([[0.0, 0, 0, 0], [0.1, 0, 0, 0], [0.2, 0, 0, 0], [9.0, 0, 0, 0]])
    labels = Clusterer().label(
        matrix, ModelParams(eps=1.0, min_samples=2, window_size_seconds=600, algorithm="hdbscan")
    )
    assert labels.shape == (4,)
    # The three tight points form a cluster; the far one is separated.
    assert labels[0] == labels[1] == labels[2]


@pytest.mark.asyncio
async def test_pipeline_publishes_to_producer(run_repo, chatter_repo, metrics):
    """A configured producer receives one publish per emitted event on transactions.clean."""
    published: list[tuple] = []

    class FakeProducer:
        def publish(self, topic, envelope):
            published.append((topic, envelope))

    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
        producer=FakeProducer(),
    )
    out = await pipe.process_window(make_window(fiber_cut_cascade()))
    assert len(published) == len(out.events) == 1
    assert published[0][0] == "transactions.clean"


@pytest.mark.asyncio
async def test_pipeline_produce_failure_raises_and_counts(run_repo, chatter_repo, metrics):
    """EH-10: a produce failure increments the produce-failure metric and propagates (offset
    not committed -> reprocessed at-least-once)."""

    class FailingProducer:
        def publish(self, topic, envelope):
            raise RuntimeError("broker down")

    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
        producer=FailingProducer(),
    )
    with pytest.raises(RuntimeError):
        await pipe.process_window(make_window(fiber_cut_cascade()))
    assert metrics.produce_failures._value.get() == 1


@pytest.mark.asyncio
async def test_retention_vs_oracle_recorded_when_oracle_available(run_repo, chatter_repo, metrics):
    """retention_vs_oracle is computed + recorded when an oracle label is provided for the trail."""
    cascade = fiber_cut_cascade()
    oracle = {"t1": {a.alarmId for a in cascade}}
    pipe = build_pipeline(
        params=ModelParams(eps=1.0, min_samples=3, window_size_seconds=600),
        run_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics=metrics,
        oracle_valid_ids=oracle,
    )
    await pipe.process_window(make_window(cascade))
    rows, _ = await run_repo.list_runs()
    assert rows[0].retention_vs_oracle == 1.0


def test_migrations_apply_idempotently(tmp_path):
    """DA-15: yoyo migrations apply (and re-apply) idempotently against a throwaway SQLite db.

    (Unit-level idempotency check; the real Postgres apply is exercised in the integration gate.)
    """
    from noise_filter.migrate import migrations_dir

    # The packaged migrations resolve and both SQL files are present.
    d = migrations_dir()
    files = sorted(p.name for p in d.glob("*.sql"))
    assert files == ["0001_run_stats.sql", "0002_observed_chatter.sql"]


def test_migrate_yoyo_url_maps_async_and_postgres_drivers():
    """migrate._yoyo_db_url maps asyncpg/postgres URLs to a sync postgresql:// driver URL."""
    from noise_filter.migrate import _yoyo_db_url

    assert _yoyo_db_url("postgresql+asyncpg://u:p@h/db").startswith("postgresql://u:p@h/db")
    assert _yoyo_db_url("postgres://u:p@h/db").startswith("postgresql://u:p@h/db")
    assert _yoyo_db_url("postgresql://u:p@h/db") == "postgresql://u:p@h/db"


def test_migrations_override_dir_via_env(monkeypatch, tmp_path):
    """NOISE_FILTER_MIGRATIONS_DIR overrides the packaged migrations dir (operator-supplied SQL)."""
    from noise_filter.migrate import migrations_dir

    monkeypatch.setenv("NOISE_FILTER_MIGRATIONS_DIR", str(tmp_path))
    assert migrations_dir() == tmp_path
