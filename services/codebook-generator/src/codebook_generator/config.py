"""Env-only configuration (Pydantic ``BaseSettings``).

No URLs, thresholds, or domain names are hard-coded. Every outbound integration point has
a base-URL var and a ``MODE`` toggle (``MOCK`` | ``REAL``). Startup fails fast (raising
:class:`ConfigError`) when any required integration-point URL is unset — including
``KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL`` (spec criterion 7).
"""

from __future__ import annotations

import enum

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class ConfigError(RuntimeError):
    """Raised at startup when required configuration is missing or invalid."""


class IntegrationMode(enum.StrEnum):
    """Mock vs. real toggle for an outbound integration point."""

    MOCK = "MOCK"
    REAL = "REAL"


class Settings(BaseSettings):
    """Service configuration sourced exclusively from environment variables."""

    model_config = SettingsConfigDict(
        env_file=None,
        extra="ignore",
        case_sensitive=False,
    )

    # --- Kafka ---
    kafka_bootstrap_servers: str = Field(default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    kafka_consumer_group: str = Field(
        default="codebook-generator-trails.built", alias="KAFKA_CONSUMER_GROUP"
    )
    trails_built_topic: str = Field(default="trails.built", alias="TRAILS_BUILT_TOPIC")
    trails_built_dlq_topic: str = Field(default="trails.built.dlq", alias="TRAILS_BUILT_DLQ_TOPIC")
    codebook_generated_topic: str = Field(
        default="codebook.generated", alias="CODEBOOK_GENERATED_TOPIC"
    )
    codebook_generated_dlq_topic: str = Field(
        default="codebook.generated.dlq", alias="CODEBOOK_GENERATED_DLQ_TOPIC"
    )
    knowledge_updated_topic: str = Field(
        default="knowledge.updated", alias="KNOWLEDGE_UPDATED_TOPIC"
    )

    # --- Datastore ---
    database_url: str = Field(default="", alias="DATABASE_URL")

    # --- Domain ---
    default_domain: str = Field(default="core-ip", alias="DEFAULT_DOMAIN")

    # --- Integration points (URL + MODE each) ---
    topology_query_url: str = Field(default="", alias="TOPOLOGY_QUERY_URL")
    topology_query_mode: IntegrationMode = Field(
        default=IntegrationMode.MOCK, alias="TOPOLOGY_QUERY_MODE"
    )

    knowledge_fault_origins_url: str = Field(default="", alias="KNOWLEDGE_FAULT_ORIGINS_URL")
    knowledge_fault_origins_mode: IntegrationMode = Field(
        default=IntegrationMode.MOCK, alias="KNOWLEDGE_FAULT_ORIGINS_MODE"
    )

    knowledge_propagation_templates_url: str = Field(
        default="", alias="KNOWLEDGE_PROPAGATION_TEMPLATES_URL"
    )
    knowledge_propagation_templates_mode: IntegrationMode = Field(
        default=IntegrationMode.MOCK, alias="KNOWLEDGE_PROPAGATION_TEMPLATES_MODE"
    )

    knowledge_alarm_type_vocabulary_url: str = Field(
        default="", alias="KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL"
    )
    knowledge_alarm_type_vocabulary_mode: IntegrationMode = Field(
        default=IntegrationMode.MOCK, alias="KNOWLEDGE_ALARM_TYPE_VOCABULARY_MODE"
    )

    trail_builder_url: str = Field(default="", alias="TRAIL_BUILDER_URL")
    trail_builder_mode: IntegrationMode = Field(
        default=IntegrationMode.MOCK, alias="TRAIL_BUILDER_MODE"
    )

    # --- Retry / backoff (no hard-coded thresholds in business code) ---
    integration_max_retries: int = Field(default=3, alias="INTEGRATION_MAX_RETRIES")
    integration_backoff_ms: int = Field(default=200, alias="INTEGRATION_BACKOFF_MS")

    # --- Traversal bound (config, not a literal in the engine) ---
    traversal_max_depth: int = Field(default=8, alias="TRAVERSAL_MAX_DEPTH")

    # --- Observability ---
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")

    # Integration points whose URL is required for the service to run.
    _REQUIRED_URL_FIELDS = (
        "topology_query_url",
        "knowledge_fault_origins_url",
        "knowledge_propagation_templates_url",
        "knowledge_alarm_type_vocabulary_url",
        "trail_builder_url",
    )

    def require_integration_urls(self) -> None:
        """Fail fast when any required integration-point base URL is unset.

        Raises:
            ConfigError: listing every missing integration-point URL.
        """
        missing = [
            field.upper() for field in self._REQUIRED_URL_FIELDS if not getattr(self, field).strip()
        ]
        if missing:
            raise ConfigError(
                "missing required integration-point URL(s): " + ", ".join(sorted(missing))
            )

    def require_database_url(self) -> None:
        """Fail fast when ``DATABASE_URL`` is unset.

        Raises:
            ConfigError: when ``DATABASE_URL`` is empty.
        """
        if not self.database_url.strip():
            raise ConfigError("missing required DATABASE_URL")


def load_settings() -> Settings:
    """Build :class:`Settings` from the environment (no I/O beyond env read)."""
    return Settings()
