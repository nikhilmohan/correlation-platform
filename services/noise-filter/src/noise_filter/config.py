"""Configuration: env-sourced :class:`Settings`, plus the hot-swappable Knowledge-sourced
:class:`ParamStore` (DBSCAN params) and :class:`FeatureConfig` (active feature set).

No DBSCAN/window/feature threshold is hard-coded here: ``ModelParams`` and ``FeatureConfig``
hold values fetched from the Knowledge Service. The fallback defaults referenced in
``ModelParams.fallback()`` exist only as the documented last-resort per the design and are NOT
used while the Knowledge Service is reachable (the service refuses to become ready if params
cannot be loaded — EH-4).
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field, replace
from enum import StrEnum

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class ClientMode(StrEnum):
    """Integration-point resolution mode (config-switchable mock vs real)."""

    mock = "mock"
    real = "real"


class Settings(BaseSettings):
    """Process configuration from environment variables (no hard-coded secrets/URLs)."""

    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    kafka_bootstrap_servers: str = Field(default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    kafka_consumer_group_id: str = Field(
        default="noise-filter-alarms.enriched", alias="KAFKA_CONSUMER_GROUP_ID"
    )

    knowledge_service_url: str = Field(
        default="http://knowledge:8080", alias="KNOWLEDGE_SERVICE_URL"
    )
    knowledge_client_mode: ClientMode = Field(
        default=ClientMode.real, alias="KNOWLEDGE_CLIENT_MODE"
    )

    topology_service_url: str = Field(default="http://topology:8080", alias="TOPOLOGY_SERVICE_URL")
    topology_client_mode: ClientMode = Field(default=ClientMode.real, alias="TOPOLOGY_CLIENT_MODE")

    trail_builder_url: str = Field(default="http://trail-builder:8080", alias="TRAIL_BUILDER_URL")
    trail_builder_client_mode: ClientMode = Field(
        default=ClientMode.real, alias="TRAIL_BUILDER_CLIENT_MODE"
    )

    noise_filter_db_url: str = Field(default="", alias="NOISE_FILTER_DB_URL")

    log_level: str = Field(default="INFO", alias="LOG_LEVEL")
    http_port: int = Field(default=8080, alias="HTTP_PORT")

    # Operational (not domain) tunables — sane env-overridable defaults, not correlation thresholds.
    dedupe_ttl_seconds: int = Field(default=900, alias="DEDUPE_TTL_SECONDS")
    window_grace_seconds: int = Field(default=5, alias="WINDOW_GRACE_SECONDS")
    read_api_default_limit: int = Field(default=50, alias="READ_API_DEFAULT_LIMIT")
    read_api_max_limit: int = Field(default=500, alias="READ_API_MAX_LIMIT")


@dataclass(frozen=True)
class ModelParams:
    """Immutable DBSCAN/window parameter snapshot (Knowledge-sourced).

    These values are authored in the Knowledge Service ``modelParams`` record for the domain
    (recordId ``core-ip/modelParams/noise-filter``). They are NEVER hard-coded into the
    pipeline; the ``fallback`` factory is only a documented last resort.
    """

    eps: float
    min_samples: int
    window_size_seconds: int
    algorithm: str = "dbscan"

    @staticmethod
    def fallback() -> ModelParams:
        """Documented last-resort defaults (design fallback; not used while Knowledge is up)."""
        return ModelParams(eps=0.5, min_samples=3, window_size_seconds=120, algorithm="dbscan")


@dataclass(frozen=True)
class FeatureSettings:
    """Immutable Knowledge-sourced feature-config snapshot.

    ``attribute_keys`` is the ordered set of device/connection attribute keys to add as feature
    dimensions (e.g. ``equipmentType``); empty => no Topology call. ``hop_distance_enabled``
    toggles the single soft hop-distance dimension; when off, no Trail Builder hop call is made.
    """

    attribute_keys: tuple[str, ...] = ()
    hop_distance_enabled: bool = False
    hop_traversal_max_depth: int = 8

    @staticmethod
    def fallback() -> FeatureSettings:
        """Documented last-resort defaults: base-four features only (no attribute/hop dims)."""
        return FeatureSettings(attribute_keys=(), hop_distance_enabled=False)


@dataclass
class ParamStore:
    """Thread-safe, hot-swappable holder of the current :class:`ModelParams` snapshot (DA-8)."""

    _params: ModelParams
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False)

    def get(self) -> ModelParams:
        with self._lock:
            return self._params

    def set(self, params: ModelParams) -> None:
        """Atomically replace the whole snapshot (no half-old/half-new reads)."""
        with self._lock:
            self._params = params


@dataclass
class FeatureConfig:
    """Thread-safe, hot-swappable holder of the current :class:`FeatureSettings` (DA-8)."""

    _features: FeatureSettings
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False)

    def get(self) -> FeatureSettings:
        with self._lock:
            return self._features

    def set(self, features: FeatureSettings) -> None:
        with self._lock:
            self._features = features

    def with_overrides(self, **kwargs: object) -> FeatureSettings:
        """Return a copy of the current settings with field overrides (test/utility helper)."""
        with self._lock:
            return replace(self._features, **kwargs)  # type: ignore[arg-type]
