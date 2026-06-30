"""Process entrypoint wiring (the assembled running service).

Wires config -> clients -> Knowledge param load (startup gate, EH-4) -> PostgreSQL pool +
migrations -> asyncpg-backed repositories -> FastAPI read API (served over HTTP_PORT by uvicorn)
-> the Kafka consume loop. The HTTP server and the Kafka pipeline run CONCURRENTLY in one process
(uvicorn as an asyncio task; the blocking confluent-kafka consume loop on a worker thread driving
the async pipeline back on the event loop).

The DB being unreachable does NOT block the pipeline (run-stats / observed-chatter writes are
best-effort); the read API reports the store as ``degraded`` and write paths swallow the failure.
The asyncpg pool, when reachable, is what makes the owned ``nf_run_stats`` / ``nf_observed_chatter``
tables persist across restarts (durability behind AC-11/12/14/19/20/21) — the in-memory repos are a
unit-test stand-in only.
"""

from __future__ import annotations

import asyncio
import contextlib
import signal
import threading
import time
from typing import Any

import uvicorn

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
from .migrate import apply_migrations_asyncpg
from .pipeline import Pipeline
from .refresh import ParamLoader
from .repository import (
    InMemoryObservedChatterRepository,
    InMemoryRunStatsRepository,
    ObservedChatterRepository,
    RunStatsRepository,
)
from .stats import ObservedChatterRecorder, RunStatsRecorder
from .windowing import DedupeCache, TrailWindower

log = get_logger(__name__)


async def build_pg_repositories(
    db_url: str,
) -> tuple[Any, RunStatsRepository, ObservedChatterRepository]:
    """Create an asyncpg pool from ``db_url`` and the Pg* repositories bound to it (B2).

    Returns ``(pool, run_repo, chatter_repo)``. The caller owns closing the pool. Imports asyncpg
    + the Pg repos lazily so the unit gate (which uses the in-memory stand-ins) needs neither a
    live DB nor the asyncpg driver loaded.
    """
    import asyncpg

    from .pg_repository import PgObservedChatterRepository, PgRunStatsRepository

    pool = await asyncpg.create_pool(_asyncpg_url(db_url))
    return pool, PgRunStatsRepository(pool), PgObservedChatterRepository(pool)


def _asyncpg_url(db_url: str) -> str:
    """Normalize a DB URL to the plain ``postgresql://`` form asyncpg accepts."""
    url = db_url
    if url.startswith("postgresql+asyncpg://"):
        url = "postgresql://" + url[len("postgresql+asyncpg://") :]
    elif url.startswith("postgres://"):
        url = "postgresql://" + url[len("postgres://") :]
    return url


def build_components(
    settings: Settings,
    metrics: Metrics,
    *,
    run_repo: RunStatsRepository | None = None,
    chatter_repo: ObservedChatterRepository | None = None,
):
    """Construct the param/feature stores, Knowledge loader and API state.

    ``run_repo`` / ``chatter_repo`` are injected by :func:`run` (the real asyncpg-backed Pg repos
    when a DB URL is configured; otherwise the in-memory stand-ins). Defaulting to in-memory keeps
    the function constructible without a DB for tests.
    """
    param_store = ParamStore(ModelParams.fallback())
    feature_config = FeatureConfig(FeatureSettings.fallback())

    knowledge = KnowledgeClient(settings.knowledge_service_url)
    loader = ParamLoader(knowledge, param_store, feature_config, metrics=metrics)

    run_repo = run_repo if run_repo is not None else InMemoryRunStatsRepository()
    chatter_repo = chatter_repo if chatter_repo is not None else InMemoryObservedChatterRepository()

    api_state = ApiState(
        run_stats_repo=run_repo,
        chatter_repo=chatter_repo,
        metrics_registry=metrics.registry,
        max_limit=settings.read_api_max_limit,
    )
    api_state.metrics = metrics

    return param_store, feature_config, loader, run_repo, chatter_repo, api_state


def build_pipeline(settings, metrics, param_store, feature_config, run_repo, chatter_repo):
    """Assemble the per-window :class:`Pipeline` from the stores + repositories."""
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


def make_http_server(app, port: int) -> uvicorn.Server:
    """Build a programmatic uvicorn server for ``app`` bound to ``0.0.0.0:port`` (B1)."""
    config = uvicorn.Config(
        app,
        host="0.0.0.0",  # noqa: S104 — container service must bind all interfaces
        port=port,
        log_level="info",
        lifespan="off",
    )
    return uvicorn.Server(config)


async def gate_on_knowledge(
    loader: ParamLoader, api_state: ApiState, *, deadline_seconds: int = 120
) -> None:
    """Block readiness until Knowledge params load (EH-4); bounded retry, raise past deadline."""
    deadline = time.monotonic() + deadline_seconds
    while True:
        try:
            loader.load()
            api_state.params_loaded = True
            return
        except Exception as exc:  # noqa: BLE001
            log.error("knowledge_load_failed", error=str(exc))
            if time.monotonic() > deadline:
                raise
            await asyncio.sleep(3)


def consume_loop(
    *,
    settings: Settings,
    pipeline: Pipeline,
    loader: ParamLoader,
    api_state: ApiState,
    loop: asyncio.AbstractEventLoop,
    stop_event: threading.Event,
) -> None:
    """Blocking confluent-kafka consume loop (runs on a worker thread).

    The async pipeline coroutines are dispatched back onto the main event ``loop`` via
    ``run_coroutine_threadsafe`` so the (async) Pg writes execute on the loop that owns the pool.
    Exits when ``stop_event`` is set (SIGTERM / shutdown).
    """
    dedupe = DedupeCache(ttl_seconds=settings.dedupe_ttl_seconds)
    router = MessageRouter(dedupe, metrics=pipeline._metrics)  # noqa: SLF001 — same package
    windower = TrailWindower(
        window_size_provider=lambda: pipeline._params.get().window_size_seconds,  # noqa: SLF001
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
    log.info("noise_filter_consumer_started")

    def process(win) -> None:
        future = asyncio.run_coroutine_threadsafe(pipeline.process_window(win), loop)
        future.result()  # surface pipeline errors on the consumer thread

    try:
        while not stop_event.is_set():
            msg = consumer.poll(1.0)
            for win in windower.pop_finalized():
                process(win)
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
    finally:
        with contextlib.suppress(Exception):
            consumer.close()
        log.info("noise_filter_consumer_stopped")


async def serve(settings: Settings, metrics: Metrics) -> None:
    """Run the HTTP server + Kafka consume loop concurrently until SIGTERM (B1 + B2).

    Order: migrate -> asyncpg pool + Pg repos -> Knowledge startup gate -> start uvicorn AND the
    consumer thread -> await both, draining cleanly on shutdown.
    """
    pool = None
    run_repo: RunStatsRepository
    chatter_repo: ObservedChatterRepository

    if settings.noise_filter_db_url:
        try:
            pool, run_repo, chatter_repo = await build_pg_repositories(settings.noise_filter_db_url)
            # Migrations over the live asyncpg pool so the schema the Pg repos write to exists
            # BEFORE the first window (DA-15, B2) — no extra sync driver needed (permissive-only).
            await apply_migrations_asyncpg(pool)
            log.info("pg_pool_ready")
        except Exception as exc:  # noqa: BLE001 — DB best-effort; degrade to in-memory
            log.error("pg_pool_failed", error=str(exc))
            if pool is not None:
                with contextlib.suppress(Exception):
                    await pool.close()
                pool = None
            run_repo = InMemoryRunStatsRepository()
            chatter_repo = InMemoryObservedChatterRepository()
    else:
        log.warning("noise_filter_db_url_unset_using_in_memory_store")
        run_repo = InMemoryRunStatsRepository()
        chatter_repo = InMemoryObservedChatterRepository()

    param_store, feature_config, loader, run_repo, chatter_repo, api_state = build_components(
        settings, metrics, run_repo=run_repo, chatter_repo=chatter_repo
    )

    await gate_on_knowledge(loader, api_state)

    pipeline = build_pipeline(
        settings, metrics, param_store, feature_config, run_repo, chatter_repo
    )

    app = create_app(api_state)
    server = make_http_server(app, settings.http_port)

    loop = asyncio.get_running_loop()
    stop_event = threading.Event()

    def _request_stop(*_a: object) -> None:
        log.info("shutdown_signal_received")
        stop_event.set()
        server.should_exit = True

    with contextlib.suppress(NotImplementedError):
        loop.add_signal_handler(signal.SIGTERM, _request_stop)
        loop.add_signal_handler(signal.SIGINT, _request_stop)

    log.info("noise_filter_started", port=settings.http_port)
    consumer_task = asyncio.create_task(
        asyncio.to_thread(
            consume_loop,
            settings=settings,
            pipeline=pipeline,
            loader=loader,
            api_state=api_state,
            loop=loop,
            stop_event=stop_event,
        )
    )
    http_task = asyncio.create_task(server.serve())
    try:
        # If EITHER task ends (HTTP exits, consumer dies, or SIGTERM), tear down BOTH so the
        # process never lingers half-alive. The consumer thread exits on the stop_event; uvicorn
        # exits on should_exit.
        await asyncio.wait({http_task, consumer_task}, return_when=asyncio.FIRST_COMPLETED)
    finally:
        stop_event.set()
        server.should_exit = True
        await asyncio.gather(http_task, consumer_task, return_exceptions=True)
        if pool is not None:
            with contextlib.suppress(Exception):
                await pool.close()


async def run() -> None:
    """Configure logging + metrics, then serve (the async process body)."""
    settings = Settings()
    configure_logging(settings.log_level)
    metrics = Metrics()
    await serve(settings, metrics)


def main() -> None:  # pragma: no cover - thin asyncio.run shim
    asyncio.run(run())


app_factory = create_app  # re-export for `uvicorn noise_filter.app:get_app`
