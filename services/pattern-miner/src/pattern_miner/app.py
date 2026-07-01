"""Process entrypoint wiring (the assembled running service).

Wires config -> mining engine (Spark MLlib in the container; pure-Python locally) -> Knowledge
mining-params client -> windowing/miner/timing/assembler -> the Kafka consume->mine->produce loop,
served CONCURRENTLY with the ``/health`` + ``/metrics`` HTTP server in one process (uvicorn as an
asyncio task; the blocking confluent-kafka consume loop on a worker thread).

The loop pools ``transactions.clean`` messages, and every ``BATCH_FLUSH_SECONDS`` (or when the
poll idles) flushes the pooled batch: fetch fresh mining params from Knowledge, group by trail,
window -> PrefixSpan -> emit one ``PatternMinedEvent`` per discovered sequence, and only then commit
the batch's offsets (at-least-once + ``eventId`` dedupe make replay safe). Poison messages go to
``transactions.clean.dlq``.
"""

from __future__ import annotations

import asyncio
import contextlib
import signal
import threading
import time

import uvicorn

from .api import ApiState, create_app
from .assemble import PatternAssembler, group_transactions
from .config import MiningEngineKind, Settings
from .emit import PatternEmitter
from .ingest import Dedup, DlqPublisher, MessageRouter, RouteDecision
from .kafka_io import PatternProducer, make_consumer
from .knowledge import MiningParamsClient
from .logging_setup import configure_logging, get_logger
from .metrics import Metrics
from .mining import PrefixSpanMiner
from .mining.engine import PrefixSpanEngine
from .timing import TimingComputer
from .windowing import SessionWindower

log = get_logger(__name__)


def build_engine(settings: Settings) -> PrefixSpanEngine:
    """Select the PrefixSpan engine: real Spark MLlib in the container, pure-Python locally."""
    if settings.mining_engine is MiningEngineKind.local:
        from .mining.local_engine import LocalPrefixSpanEngine

        return LocalPrefixSpanEngine()
    from .mining.spark_engine import SparkPrefixSpanEngine

    return SparkPrefixSpanEngine(master=settings.spark_master)


def build_knowledge_client(settings: Settings) -> MiningParamsClient:
    """Construct the Knowledge mining-params client from env config (no hard-coded URL)."""
    return MiningParamsClient(
        settings.knowledge_base_url,
        domain=settings.knowledge_domain,
        record_id=settings.knowledge_model_params_record_id,
        retry_max=settings.knowledge_retry_max,
        retry_backoff_ms=settings.knowledge_retry_backoff_ms,
    )


def make_http_server(app, port: int) -> uvicorn.Server:
    """Build a programmatic uvicorn server for ``app`` bound to ``0.0.0.0:port``."""
    config = uvicorn.Config(
        app,
        host="0.0.0.0",  # noqa: S104 — container service must bind all interfaces
        port=port,
        log_level="info",
        lifespan="off",
    )
    return uvicorn.Server(config)


async def gate_on_knowledge(
    knowledge: MiningParamsClient, api_state: ApiState, *, deadline_seconds: int = 120
) -> None:
    """Block readiness until the first mining-params fetch succeeds; bounded retry then raise."""
    deadline = time.monotonic() + deadline_seconds
    while True:
        try:
            knowledge.fetch()
            api_state.knowledge_ready = True
            return
        except Exception as exc:  # noqa: BLE001
            log.error("knowledge_gate_failed", error=str(exc))
            if time.monotonic() > deadline:
                raise
            await asyncio.sleep(3)


def consume_loop(
    *,
    settings: Settings,
    engine: PrefixSpanEngine,
    knowledge: MiningParamsClient,
    metrics: Metrics,
    api_state: ApiState,
    stop_event: threading.Event,
) -> None:
    """Blocking confluent-kafka consume->mine->produce loop (runs on a worker thread)."""
    miner = PrefixSpanMiner(engine, metrics=metrics)
    timing = TimingComputer()
    producer = PatternProducer(settings.kafka_bootstrap_servers)
    dlq = DlqPublisher(producer.raw, settings.dlq_topic)
    emitter = PatternEmitter(producer, settings.patterns_mined_topic, metrics=metrics)
    dedup = Dedup()
    router = MessageRouter(dedup, metrics=metrics)

    consumer = make_consumer(
        settings.kafka_bootstrap_servers,
        settings.consumer_group_id,
        [settings.transactions_clean_topic],
    )
    api_state.kafka_connected = True
    log.info("pattern_miner_consumer_started")

    pending: list = []  # list[(TransactionEvent, traceId)]
    last_flush = time.monotonic()

    def flush() -> None:
        nonlocal pending, last_flush
        last_flush = time.monotonic()
        if not pending:
            return
        batch, pending = pending, []
        try:
            params = knowledge.fetch()
            windower = SessionWindower(params.windowing, metrics=metrics)
            assembler = PatternAssembler(windower, miner, timing, metrics=metrics)
            metrics.mining_runs.inc()
            envelopes = []
            for trail_batch in group_transactions(batch):
                envelopes.extend(assembler.mine_batch(trail_batch, params))
            emitter.emit(envelopes)
            consumer.commit()
            log.info("mining_run_completed", transactions=len(batch), emitted=len(envelopes))
        except Exception as exc:  # noqa: BLE001 — fail the batch, do NOT commit; replay-safe
            metrics.mining_failures.inc()
            log.error("mining_run_failed", error=str(exc))

    try:
        while not stop_event.is_set():
            msg = consumer.poll(1.0)
            now = time.monotonic()
            if msg is None:
                if now - last_flush >= settings.batch_flush_seconds:
                    flush()
                continue
            if msg.error():
                log.error("kafka_error", error=str(msg.error()))
                continue
            result = router.route(msg.value())
            if result.decision == RouteDecision.DLQ:
                dlq.publish(msg.value(), reason=result.dlq_reason or "", error=result.error or "")
                consumer.commit(msg)
            elif result.decision == RouteDecision.ACCEPT and result.transaction is not None:
                pending.append((result.transaction, result.trace_id))
            else:  # duplicate
                consumer.commit(msg)
            if now - last_flush >= settings.batch_flush_seconds:
                flush()
    finally:
        with contextlib.suppress(Exception):
            flush()
        with contextlib.suppress(Exception):
            producer.flush()
        with contextlib.suppress(Exception):
            consumer.close()
        log.info("pattern_miner_consumer_stopped")


async def serve(settings: Settings, metrics: Metrics) -> None:
    """Run the HTTP server + Kafka consume loop concurrently until SIGTERM."""
    engine = build_engine(settings)
    knowledge = build_knowledge_client(settings)

    api_state = ApiState(metrics_registry=metrics.registry)
    await gate_on_knowledge(knowledge, api_state)

    app = create_app(api_state)
    server = make_http_server(app, settings.http_port)

    stop_event = threading.Event()
    loop = asyncio.get_running_loop()

    def _request_stop(*_a: object) -> None:
        log.info("shutdown_signal_received")
        stop_event.set()
        server.should_exit = True

    with contextlib.suppress(NotImplementedError):
        loop.add_signal_handler(signal.SIGTERM, _request_stop)
        loop.add_signal_handler(signal.SIGINT, _request_stop)

    log.info("pattern_miner_started", port=settings.http_port)
    consumer_task = asyncio.create_task(
        asyncio.to_thread(
            consume_loop,
            settings=settings,
            engine=engine,
            knowledge=knowledge,
            metrics=metrics,
            api_state=api_state,
            stop_event=stop_event,
        )
    )
    http_task = asyncio.create_task(server.serve())
    try:
        await asyncio.wait({http_task, consumer_task}, return_when=asyncio.FIRST_COMPLETED)
    finally:
        stop_event.set()
        server.should_exit = True
        await asyncio.gather(http_task, consumer_task, return_exceptions=True)


async def run() -> None:
    """Configure logging + metrics, then serve (the async process body)."""
    settings = Settings()
    configure_logging(settings.log_level)
    metrics = Metrics()
    await serve(settings, metrics)


def main() -> None:  # pragma: no cover - thin asyncio.run shim
    asyncio.run(run())
