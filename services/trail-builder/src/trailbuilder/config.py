"""Environment-driven settings (Pydantic ``BaseSettings``).

No URLs, thresholds, policy values, or domain defaults are hard-coded in source:
they all resolve from environment variables here. Trail-policy bounds (IGP-area,
SRLG, dependency-edge set) are NOT config — they are read from the Knowledge
Service at build time per the design.
"""

from __future__ import annotations

from enum import StrEnum

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class ServiceMode(StrEnum):
    """Integration-point toggle: stubbed vs. live collaborator."""

    mock = "mock"
    real = "real"


class Settings(BaseSettings):
    """All runtime configuration, sourced from the environment.

    Note: ``DEFAULT_DOMAIN`` is the backward-compat fallback applied ONLY on the
    Kafka event-ingestion path (a legacy ``topology.changed`` whose optional
    ``domain`` is absent). It is NEVER applied to the HTTP query API, where
    ``domain`` is strictly required (Q1 + Q7 in the design).
    """

    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    # --- Integration points (no hard-coded URLs) ---
    topology_service_base_url: str = Field(
        "http://topology:8080", alias="TOPOLOGY_SERVICE_BASE_URL"
    )
    topology_service_mode: ServiceMode = Field(ServiceMode.mock, alias="TOPOLOGY_SERVICE_MODE")
    knowledge_service_base_url: str = Field(
        "http://knowledge:8080", alias="KNOWLEDGE_SERVICE_BASE_URL"
    )
    knowledge_service_mode: ServiceMode = Field(ServiceMode.mock, alias="KNOWLEDGE_SERVICE_MODE")
    knowledge_stale_ok: bool = Field(False, alias="KNOWLEDGE_STALE_OK")

    # --- Persistence ---
    database_url: str = Field(
        "postgresql+psycopg://correlation:correlation@postgres:5432/correlation",
        alias="DATABASE_URL",
    )
    db_schema: str = Field("trailbuilder", alias="TRAILBUILDER_DB_SCHEMA")

    # --- Kafka ---
    kafka_bootstrap_servers: str = Field("kafka:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    kafka_consumer_group: str = Field("trail-builder", alias="KAFKA_CONSUMER_GROUP")
    topology_changed_topic: str = Field("topology.changed", alias="TOPOLOGY_CHANGED_TOPIC")
    topology_changed_dlq_topic: str = Field(
        "topology.changed.dlq", alias="TOPOLOGY_CHANGED_DLQ_TOPIC"
    )
    knowledge_updated_topic: str = Field("knowledge.updated", alias="KNOWLEDGE_UPDATED_TOPIC")
    trails_built_topic: str = Field("trails.built", alias="TRAILS_BUILT_TOPIC")

    # --- Trail build behaviour ---
    trail_retention_snapshots: int = Field(2, alias="TRAIL_RETENTION_SNAPSHOTS")
    # Backward-compat fallback for a legacy topology.changed without `domain`.
    # Applied ONLY on the Kafka event path — never on the HTTP query API.
    default_domain: str = Field("core-ip", alias="DEFAULT_DOMAIN")
    # The Topology snapshot scoping token for the in-scope (event-carried) snapshot.
    topology_snapshot_scope: str = Field("current", alias="TOPOLOGY_SNAPSHOT_SCOPE")
    # Bound on the bounded-traversal depth handed to Topology (`maxDepth`).
    # Default 12: a trail closure is bounded per IGP area (the policy `boundary`
    # igp-area prune). On the synthesized P1 topology the per-area
    # dependency-closure diameter is ~8-10 hops — the vertical dependency stack
    # Node->LineCard->Port->Interface->IPLink->LSP->VPNService is 6 hops, plus
    # ~3-4 lateral hops within a 6-8-node area. 12 covers that with headroom so
    # trails are COMPLETE (a too-shallow depth truncates trails and fragments
    # downstream pattern discovery / auto-correlation), and stays well within
    # Topology's traversal maxDepth cap (raised to 32, published in its openapi).
    # Env-configurable via TRAVERSAL_MAX_DEPTH.
    traversal_max_depth: int = Field(12, alias="TRAVERSAL_MAX_DEPTH")

    # --- HTTP client retry ---
    http_retry_max: int = Field(3, alias="HTTP_RETRY_MAX")
    http_retry_backoff_ms: int = Field(200, alias="HTTP_RETRY_BACKOFF_MS")
    http_timeout_seconds: float = Field(10.0, alias="HTTP_TIMEOUT_SECONDS")

    # --- Auth (optional; disabled when unset) ---
    rebuild_api_token: str | None = Field(None, alias="REBUILD_API_TOKEN")

    # --- Observability ---
    log_level: str = Field("INFO", alias="LOG_LEVEL")
    service_name: str = Field("trail-builder", alias="SERVICE_NAME")


def get_settings(**overrides: object) -> Settings:
    """Construct a fresh :class:`Settings` (reads the current environment).

    ``overrides`` let tests inject values without touching the process env.
    """
    return Settings(**overrides)  # type: ignore[arg-type]
