"""Operational HTTP surface: ``/health`` + ``/metrics`` ONLY (no business API, no OpenAPI spec).

pattern-miner is a stateless Spark job; per the spec it exposes no HTTP contract surface beyond
liveness/readiness and Prometheus metrics. ``/health`` reports Kafka + Knowledge reachability as
seen by the running process (flags flipped by the entrypoint).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fastapi import FastAPI, Response
from prometheus_client import CONTENT_TYPE_LATEST, CollectorRegistry, generate_latest
from pydantic import BaseModel


class HealthModel(BaseModel):
    status: str
    kafka: str
    knowledge: str
    codebook: str
    spark: str


@dataclass
class ApiState:
    """Mutable liveness/readiness flags shared with the entrypoint.

    ``spark_ready`` is the [BATCH-CAP] Spark-subsystem readiness flag: it starts ready, dips to
    not-ready only after SparkSession recreate exhaustion, and **self-heals** back to ready on the
    next successful session build. It never latches DOWN (Startup-Robustness Standard) and never
    stops the consumer — the process keeps consuming so a later flush can recover.
    """

    metrics_registry: CollectorRegistry
    kafka_connected: bool = field(default=False)
    knowledge_ready: bool = field(default=False)
    codebook_ready: bool = field(default=False)
    spark_ready: bool = field(default=True)


def create_app(state: ApiState) -> FastAPI:
    """Build the FastAPI app serving ``/health`` + ``/metrics`` (operational only)."""
    app = FastAPI(
        title="pattern-miner (operational)",
        description="Stateless PrefixSpan mining job — operational endpoints only.",
        version="0.1.0",
    )

    @app.get("/health", response_model=HealthModel)
    def health() -> HealthModel:  # pragma: no cover - exercised via TestClient/integration
        ok = (
            state.kafka_connected
            and state.knowledge_ready
            and state.codebook_ready
            and state.spark_ready
        )
        return HealthModel(
            status="ok" if ok else "starting",
            kafka="up" if state.kafka_connected else "down",
            knowledge="up" if state.knowledge_ready else "down",
            codebook="up" if state.codebook_ready else "down",
            spark="up" if state.spark_ready else "down",
        )

    @app.get("/metrics")
    def metrics() -> Response:  # pragma: no cover - exercised via TestClient/integration
        return Response(
            content=generate_latest(state.metrics_registry),
            media_type=CONTENT_TYPE_LATEST,
        )

    return app
