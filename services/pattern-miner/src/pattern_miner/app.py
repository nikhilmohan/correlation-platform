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
from .assemble import ThreeStagePipeline, chunk_trail_batches, group_transactions
from .codebook import CodebookClient, CodebookError, NoActiveCodebookError
from .config import MiningEngineKind, MiningParams, Settings
from .emit import PatternEmitter
from .ingest import Dedup, DlqPublisher, MessageRouter, RouteDecision
from .kafka_io import PatternProducer, make_consumer
from .knowledge import MiningParamsClient
from .logging_setup import configure_logging, get_logger
from .metrics import Metrics
from .mining import GroupedMiner, PrefixSpanMiner
from .mining.engine import PrefixSpanEngine
from .timing import TimingComputer
from .windowing import SessionWindower

log = get_logger(__name__)


class SparkRecreateExhaustedError(RuntimeError):
    """[BATCH-CAP] Raised when a dead SparkSession cannot be recreated within the bounded attempts.

    Signals the flush must fail **clean** (offsets not committed, replayable) — never a silent
    permanent wedge; ``/health`` reports Spark not-ready until the next successful build.
    """


def is_gateway_death(exc: BaseException) -> bool:
    """[BATCH-CAP] True iff ``exc`` looks like a Py4J/driver gateway death (recoverable by reset).

    Matches ``Py4JNetworkError`` / ``Py4JError`` (by class name, so pyspark need not be importable
    locally), the empty-answer message, and connection-refused ``OSError``/``ConnectionError`` — the
    death classes the design lists. A recoverable death triggers ``engine.reset()`` + bounded
    recreate; any other error fails the run without a recreate loop.
    """
    name = type(exc).__name__
    if name in {"Py4JNetworkError", "Py4JError", "Py4JJavaError"}:
        return True
    if isinstance(exc, ConnectionError | ConnectionRefusedError):
        return True
    text = str(exc).lower()
    if isinstance(exc, OSError) and ("connection refused" in text or "connection reset" in text):
        return True
    return "answer from java side is empty" in text or "connection refused" in text


def effective_trail_cap(settings: Settings, params: MiningParams) -> int:
    """[BATCH-CAP] The active whole-trail sub-run cap: Knowledge override else the env default.

    The cap is an operational batching knob, so ``MAX_TRAILS_PER_BATCH`` (env) supplies a deployable
    default and an optional Knowledge ``batching.maxTrailsPerBatch`` overrides it centrally — no cap
    literal in mining/pipeline logic.
    """
    if params.max_trails_per_batch is not None:
        return params.max_trails_per_batch
    return settings.max_trails_per_batch


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


def build_codebook_client(settings: Settings) -> CodebookClient:
    """Construct the Codebook client (Stage 2) from env config (no hard-coded URL)."""
    return CodebookClient(
        settings.codebook_base_url,
        retry_max=settings.codebook_retry_max,
        retry_backoff_ms=settings.codebook_retry_backoff_ms,
    )


def _batches_by_key(trail_batches, default_domain: str):
    """Group trail batches by their ``(domain, snapshotId)`` codebook-resolution key."""
    grouped: dict[tuple[str, str], list] = {}
    for tb in trail_batches:
        key = (tb.domain or default_domain, tb.snapshot_id)
        grouped.setdefault(key, []).append(tb)
    return grouped


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
    codebook: CodebookClient,
    metrics: Metrics,
    api_state: ApiState,
    stop_event: threading.Event,
) -> None:
    """Blocking confluent-kafka consume->mine->produce loop (runs on a worker thread)."""
    miner = PrefixSpanMiner(engine, metrics=metrics)
    grouped_miner = GroupedMiner(miner, metrics=metrics)
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

    def run_sub_run(pipeline, group_batches, scenarios, params) -> list:
        """[BATCH-CAP] Run one bounded sub-run with SparkContext resilience.

        On a detected gateway-death error class (:func:`is_gateway_death`) drop the dead session
        (``engine.reset()``) and retry, bounded by ``SPARK_RECREATE_MAX_ATTEMPTS`` with
        ``SPARK_RECREATE_BACKOFF_MS`` back-off; on the next successful build Spark readiness
        self-heals. On recreate exhaustion raise :class:`SparkRecreateExhaustedError` so the FLUSH
        fails clean (offsets uncommitted). Non-gateway errors propagate unchanged (no recreate).
        """
        attempts = max(0, settings.spark_recreate_max_attempts)
        for attempt in range(attempts + 1):
            try:
                envelopes = pipeline.run(group_batches, scenarios, params)
                api_state.spark_ready = True  # a successful run proves the engine is healthy
                return envelopes
            except (
                BaseException
            ) as exc:  # noqa: BLE001 — only gateway deaths recreate; else re-raise
                if not is_gateway_death(exc):
                    raise
                if attempt >= attempts:
                    metrics.spark_recreate_failures.inc()
                    api_state.spark_ready = False
                    raise SparkRecreateExhaustedError(
                        f"SparkSession recreate exhausted after {attempts} attempt(s): {exc}"
                    ) from exc
                metrics.spark_recreate_attempts.inc()
                api_state.spark_ready = False
                log.error(
                    "spark_gateway_death_detected",
                    attempt=attempt + 1,
                    of=attempts,
                    error=str(exc),
                )
                engine.reset()  # drop the dead session; next pipeline.run rebuilds via _get_spark
                if settings.spark_recreate_backoff_ms:
                    time.sleep((settings.spark_recreate_backoff_ms / 1000.0) * (2**attempt))
                log.info("spark_session_recreated", attempt=attempt + 1)
        return []  # unreachable (loop returns or raises)

    def flush() -> None:
        nonlocal pending, last_flush
        last_flush = time.monotonic()
        if not pending:
            return
        batch, pending = pending, []
        try:
            params = knowledge.fetch()
            windower = SessionWindower(params.windowing, metrics=metrics)
            pipeline = ThreeStagePipeline(windower, grouped_miner, timing, metrics=metrics)
            metrics.mining_runs.inc()

            # Stage 2 needs the domain's fault-origin scenarios; resolve the active codebook per
            # distinct (domain, snapshotId) in the run (single snapshot per run in practice).
            # A Codebook failure fails the run fast (no unanchored global mining) — offsets NOT
            # committed, so replay retries once Codebook returns.
            trail_batches = group_transactions(batch)
            scenarios_by_key: dict[tuple[str, str], list] = {}
            for tb in trail_batches:
                key = (tb.domain or settings.knowledge_domain, tb.snapshot_id)
                if key not in scenarios_by_key:
                    scenarios_by_key[key] = codebook.scenarios_for(key[0], key[1])

            # [BATCH-CAP] Partition the flush's WHOLE trails into disjoint bounded sub-runs (never
            # splitting a trail). Each sub-run anchors -> groups -> per-group PrefixSpan -> emits
            # independently, bounding the Stage-3 collect. Offsets commit ONCE after ALL sub-runs.
            cap = effective_trail_cap(settings, params)
            emitted_total = 0
            sub_run_index = 0
            sub_run_total = 0
            for key, group_batches in _batches_by_key(
                trail_batches, settings.knowledge_domain
            ).items():
                for sub_run in chunk_trail_batches(group_batches, cap):
                    sub_run_total += 1
                    envelopes = run_sub_run(pipeline, sub_run, scenarios_by_key[key], params)
                    emitter.emit(envelopes)
                    emitted_total += len(envelopes)
                    metrics.mining_sub_runs.inc()
                    log.info(
                        "mining_sub_run_completed",
                        sub_run_index=sub_run_index,
                        trails_in_sub_run=len(sub_run),
                        emitted=len(envelopes),
                    )
                    sub_run_index += 1
            metrics.last_flush_sub_run_count.set(sub_run_total)

            # Only after every sub-run succeeded do we advance offsets (replay unit = the flush).
            consumer.commit()
            log.info(
                "mining_run_completed",
                transactions=len(batch),
                emitted=emitted_total,
                sub_runs=sub_run_total,
            )
        except CodebookError as exc:  # Stage-2 anchoring unavailable: fail fast, do NOT commit.
            metrics.mining_failures.inc()
            metrics.codebook_fetch_failures.inc()
            log.error("mining_run_failed", reason="codebook_unavailable", error=str(exc))
        except (
            SparkRecreateExhaustedError
        ) as exc:  # Spark could not recover: fail clean, no commit.
            metrics.mining_failures.inc()
            log.error("mining_run_failed", reason="spark_recreate_exhausted", error=str(exc))
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


async def gate_on_codebook(
    codebook: CodebookClient,
    settings: Settings,
    api_state: ApiState,
    *,
    deadline_seconds: int = 120,
) -> None:
    """Block readiness until the Codebook Service is reachable; bounded retry then raise.

    Reachability is probed with a lightweight resolve using the configured domain and a probe
    snapshot; a 404 (no active codebook for that probe snapshot) still proves the service is UP, so
    readiness is satisfied. Only transport/5xx exhaustion blocks readiness.
    """
    deadline = time.monotonic() + deadline_seconds
    while True:
        try:
            # A 404 (no active codebook for the probe snapshot) still proves the service is UP.
            with contextlib.suppress(NoActiveCodebookError):
                codebook.resolve_codebook_id(settings.knowledge_domain, "_readiness_probe_")
            api_state.codebook_ready = True
            return
        except Exception as exc:  # noqa: BLE001 — transport/5xx exhaustion: service not reachable
            log.error("codebook_gate_failed", error=str(exc))
            if time.monotonic() > deadline:
                raise
            await asyncio.sleep(3)


async def serve(settings: Settings, metrics: Metrics) -> None:
    """Run the HTTP server + Kafka consume loop concurrently until SIGTERM."""
    engine = build_engine(settings)
    knowledge = build_knowledge_client(settings)
    codebook = build_codebook_client(settings)

    api_state = ApiState(metrics_registry=metrics.registry)
    await gate_on_knowledge(knowledge, api_state)
    await gate_on_codebook(codebook, settings, api_state)

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
            codebook=codebook,
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
