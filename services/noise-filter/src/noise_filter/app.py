"""Process entrypoint wiring (not on the unit-test gate; omitted from coverage).

Wires config -> clients -> Knowledge param load (startup gate, EH-4) -> PostgreSQL pool +
migrations -> FastAPI read API -> the Kafka consume loop. The DB being unreachable does NOT block
the pipeline (stats are best-effort); the read API reports the store as degraded.
"""

from __future__ import annotations

import asyncio
import contextlib
import time

from .api import ApiState, create_app
from .clients import KnowledgeClient, TopologyClient, TrailBuilderClient
from .cluster import Clusterer
from .config import (
    FeatureConfig,
    FeatureSettings,
    ModelParams,
    ParamStore,
    Settings,
)
from .emit import TransactionEmitter
from .features import FeatureVectorizer, HopDistanceResolver
from .ingest import DlqPublisher, MessageRouter, RouteDecision
from .kafka_io import TransactionProducer, make_consumer
from .logging_setup import configure_logging, get_logger
from .metrics import Metrics
from .migrate import apply_migrations
from .pipeline import Pipeline
from .refresh import ParamLoader
from .repository import InMemoryObservedChatterRepository, InMemoryRunStatsRepository
from .stats import ObservedChatterRecorder, RunStatsRecorder
from .windowing import DedupeCache, TrailWindower

log = get_logger(__name__)


def build_components(settings: Settings, metrics: Metrics):
    """Construct the pipeline + API state from settings (clients chosen by feature config)."""
    param_store = ParamStore(ModelParams.fallback())
    feature_config = FeatureConfig(FeatureSettings.fallback())

    knowledge = KnowledgeClient(settings.knowledge_service_url)
    loader = ParamLoader(knowledge, param_store, feature_config, metrics=metrics)

    # PostgreSQL repositories: asyncpg pool is created lazily in run(); start with in-memory
    # stand-ins so the read API + pipeline are constructible even before the DB connects.
    run_repo = InMemoryRunStatsRepository()
    chatter_repo = InMemoryObservedChatterRepository()

    api_state = ApiState(
        run_stats_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics_registry=metrics.registry,
        max_limit=settings.read_api_max_limit,
    )
    api_state.metrics = metrics

    return param_store, feature_config, loader, run_repo, chatter_repo, api_state


def _build_pipeline(settings, metrics, param_store, feature_config, run_repo, chatter_repo):
    features = feature_config.get()
    topology_client = (
        TopologyClient(settings.topology_service_url) if features.attribute_keys else None
    )
    trail_builder = TrailBuilderClient(settings.trail_builder_url)
    hop_resolver = HopDistanceResolver(trail_builder) if features.hop_distance_enabled else None
    vectorizer = FeatureVectorizer(
        topology_client=topology_client, hop_resolver=hop_resolver, metrics=metrics
    )
    return Pipeline(
        param_store=param_store,
        feature_config=feature_config,
        vectorizer=vectorizer,
        clusterer=Clusterer(metrics=metrics),
        emitter=TransactionEmitter(metrics=metrics),
        run_stats_recorder=RunStatsRecorder(run_repo, metrics=metrics),
        chatter_recorder=ObservedChatterRecorder(chatter_repo, metrics=metrics),
        trail_builder_client=trail_builder,
        producer=TransactionProducer(settings.kafka_bootstrap_servers),
        metrics=metrics,
    )


async def run() -> None:  # pragma: no cover - integration entrypoint
    settings = Settings()
    configure_logging(settings.log_level)
    metrics = Metrics()

    if settings.noise_filter_db_url:
        with contextlib.suppress(Exception):
            apply_migrations(settings.noise_filter_db_url)

    param_store, feature_config, loader, run_repo, chatter_repo, api_state = build_components(
        settings, metrics
    )

    # Startup gate: refuse readiness until Knowledge params load (EH-4), bounded retry.
    deadline = time.monotonic() + 120
    while True:
        try:
            loader.load()
            api_state.params_loaded = True
            break
        except Exception as exc:  # noqa: BLE001
            log.error("knowledge_load_failed", error=str(exc))
            if time.monotonic() > deadline:
                raise
            await asyncio.sleep(3)

    pipeline = _build_pipeline(
        settings, metrics, param_store, feature_config, run_repo, chatter_repo
    )
    dedupe = DedupeCache(ttl_seconds=settings.dedupe_ttl_seconds)
    router = MessageRouter(dedupe, metrics=metrics)
    windower = TrailWindower(
        window_size_provider=lambda: param_store.get().window_size_seconds,
        grace_seconds=settings.window_grace_seconds,
    )
    producer = TransactionProducer(settings.kafka_bootstrap_servers)
    dlq = DlqPublisher(producer.raw)

    consumer = make_consumer(
        settings.kafka_bootstrap_servers,
        settings.kafka_consumer_group_id,
        ["alarms.enriched", "knowledge.updated"],
    )
    api_state.kafka_connected = True

    log.info("noise_filter_started", port=settings.http_port)
    while True:  # pragma: no cover
        msg = consumer.poll(1.0)
        for win in windower.pop_finalized():
            await pipeline.process_window(win)
        if msg is None:
            continue
        if msg.error():
            log.error("kafka_error", error=str(msg.error()))
            continue
        if msg.topic() == "knowledge.updated":
            loader.handle_knowledge_updated()
            consumer.commit(msg)
            continue
        result = router.route(msg.value())
        if result.decision == RouteDecision.DLQ:
            dlq.publish(msg.value(), reason=result.dlq_reason, error=result.error or "")
        elif result.decision == RouteDecision.ACCEPT and result.alarm is not None:
            windower.add(result.alarm)
        consumer.commit(msg)


def main() -> None:  # pragma: no cover
    asyncio.run(run())


app_factory = create_app  # re-export for `uvicorn noise_filter.app:get_app`
