"""Configuration (env-driven, validated at startup — fail-fast, criterion 18).

All thresholds, sizes, rates and integration URLs come from environment variables (no
hard-coded values). :class:`Settings` is a Pydantic ``BaseSettings`` so every knob has a
documented default and a validated range. Missing/invalid required config raises
:class:`ConfigError`, which ``main`` turns into a structured-log fatal + non-zero exit *before*
any event is emitted.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from simulator.config.demo_profiles import DEMO_PROFILES, PROFILE_NAMES
from simulator.domains.coreip import geo_catalogue
from simulator.domains.coreip.scenario_library import SCENARIO_TYPES

Phase = Literal["p1", "p2", "p3"]
Mode = Literal["generate", "ingest"]


class ConfigError(ValueError):
    """Raised when configuration is missing or invalid (fail-fast)."""


class Settings(BaseSettings):
    """Validated Simulator configuration loaded from the environment."""

    model_config = SettingsConfigDict(extra="ignore", populate_by_name=True)

    # run shape
    phase: Phase = "p2"
    sim_mode: Mode = Field(default="generate", alias="SIM_MODE")
    demo_profile: str | None = Field(default=None, alias="DEMO_PROFILE")

    # ingest / export files
    ingest_topology_file: str | None = Field(default=None, alias="INGEST_TOPOLOGY_FILE")
    ingest_alarms_file: str | None = Field(default=None, alias="INGEST_ALARMS_FILE")
    ingest_labels_file: str | None = Field(default=None, alias="INGEST_LABELS_FILE")
    export_corpus_file: str | None = Field(default=None, alias="EXPORT_CORPUS_FILE")

    # integrations
    kafka_bootstrap_servers: str | None = Field(default=None, alias="KAFKA_BOOTSTRAP_SERVERS")
    topology_api_mode: Literal["mock", "real"] = Field(default="mock", alias="TOPOLOGY_API_MODE")
    topology_api_base_url: str | None = Field(default=None, alias="TOPOLOGY_API_BASE_URL")
    knowledge_mode: Literal["local", "real"] = Field(default="local", alias="KNOWLEDGE_MODE")
    knowledge_api_base_url: str | None = Field(default=None, alias="KNOWLEDGE_API_BASE_URL")

    # topology shape
    topology_node_count: int = Field(default=20, alias="TOPOLOGY_NODE_COUNT")
    site_count: int = Field(default=3, alias="SITE_COUNT")
    devices_per_site: int | None = Field(default=None, alias="DEVICES_PER_SITE")
    interfaces_per_port: int = Field(default=1, alias="INTERFACES_PER_PORT")
    igp_area_count: int = Field(default=3, alias="IGP_AREA_COUNT")

    # synthesis
    sim_seed: int | None = Field(default=None, alias="SIM_SEED")
    scenarios: str = Field(default=",".join(SCENARIO_TYPES), alias="SCENARIOS")
    scenario_instances: int = Field(default=8, alias="SCENARIO_INSTANCES")
    total_alarms: int | None = Field(default=None, alias="TOTAL_ALARMS")
    jitter_stddev_ms: float = Field(default=300.0, alias="JITTER_STDDEV_MS")
    base_interval_ms: float = Field(default=400.0, alias="BASE_INTERVAL_MS")
    background_interval_ms: float = Field(default=2000.0, alias="BACKGROUND_INTERVAL_MS")
    background_fraction: float = Field(default=0.3, alias="BACKGROUND_FRACTION")
    noise_rate: float = Field(default=0.2, alias="NOISE_RATE")
    noise_mix: str = Field(
        default="flapping:0.4,transient:0.3,chatty:0.2,coincidental:0.1", alias="NOISE_MIX"
    )
    hard_noise_fraction: float = Field(default=0.4, alias="HARD_NOISE_FRACTION")

    # timing windows
    history_start: datetime | None = Field(default=None, alias="HISTORY_START")
    history_end: datetime | None = Field(default=None, alias="HISTORY_END")
    history_duration_s: float | None = Field(default=None, alias="HISTORY_DURATION")
    pacing_multiplier: float = Field(default=1.0, alias="PACING_MULTIPLIER")

    # output / observability
    sim_output_dir: str = Field(default="/data/sim", alias="SIM_OUTPUT_DIR")
    http_port: int = Field(default=8080, alias="HTTP_PORT")
    log_level: str = Field(default="INFO", alias="LOG_LEVEL")

    # -- derived helpers ---------------------------------------------------------------
    @property
    def selected_scenarios(self) -> list[str]:
        return [s.strip() for s in self.scenarios.split(",") if s.strip()]

    @property
    def noise_mix_weights(self) -> dict[str, float]:
        out: dict[str, float] = {}
        for token in self.noise_mix.split(","):
            token = token.strip()
            if not token:
                continue
            name, _, weight = token.partition(":")
            out[name.strip()] = float(weight) if weight else 1.0
        return out

    def resolved_history_window(self) -> tuple[datetime, datetime]:
        """Resolve the (start, end) P2 history window from the configured knobs."""
        end = self.history_end or datetime.now(tz=UTC)
        if self.history_start is not None:
            start = self.history_start
        elif self.history_duration_s is not None:
            start = end - timedelta(seconds=self.history_duration_s)
        else:
            start = end - timedelta(hours=24)
        return start, end


def apply_profile(values: dict[str, object], explicit: set[str]) -> dict[str, object]:
    """Merge a ``DEMO_PROFILE`` bundle into raw values, never overriding explicit env keys.

    ``explicit`` is the set of Settings field names the operator supplied via env (those win).
    """
    profile_name = values.get("demo_profile")
    if not profile_name:
        return values
    if profile_name not in DEMO_PROFILES:
        raise ConfigError(f"DEMO_PROFILE={profile_name!r} not one of {PROFILE_NAMES}")
    for key, val in DEMO_PROFILES[profile_name].items():
        if key not in explicit:
            values[key] = val
    return values


def _validate(s: Settings) -> None:  # noqa: C901 - flat range checks
    if s.demo_profile is not None and s.demo_profile not in PROFILE_NAMES:
        raise ConfigError(f"DEMO_PROFILE={s.demo_profile!r} not one of {PROFILE_NAMES}")
    if not (10 <= s.topology_node_count <= 200):
        raise ConfigError(f"TOPOLOGY_NODE_COUNT={s.topology_node_count} out of range [10,200]")
    cat_size = geo_catalogue.CATALOGUE_SIZE
    upper = min(s.topology_node_count, cat_size)
    if not (1 <= s.site_count <= upper):
        raise ConfigError(
            f"SITE_COUNT={s.site_count} out of range [1,{upper}] "
            f"(node count / geo-catalogue size {cat_size})"
        )
    if s.devices_per_site is not None and s.devices_per_site < 1:
        raise ConfigError("DEVICES_PER_SITE must be >= 1 when set")
    if not (1 <= s.interfaces_per_port <= 8):
        raise ConfigError(f"INTERFACES_PER_PORT={s.interfaces_per_port} out of range [1,8]")
    if not (1 <= s.igp_area_count <= 8):
        raise ConfigError(f"IGP_AREA_COUNT={s.igp_area_count} out of range [1,8]")
    if s.jitter_stddev_ms < 0:
        raise ConfigError("JITTER_STDDEV_MS must be >= 0")
    if s.base_interval_ms <= 0 or s.background_interval_ms <= 0:
        raise ConfigError("BASE_INTERVAL_MS / BACKGROUND_INTERVAL_MS must be > 0")
    if not (0.0 <= s.noise_rate <= 1.0):
        raise ConfigError("NOISE_RATE must be in [0,1]")
    if not (0.0 <= s.background_fraction <= 1.0):
        raise ConfigError("BACKGROUND_FRACTION must be in [0,1]")
    if not (0.0 <= s.hard_noise_fraction <= 1.0):
        raise ConfigError("HARD_NOISE_FRACTION must be in [0,1]")
    if s.scenario_instances < 1:
        raise ConfigError("SCENARIO_INSTANCES must be >= 1")
    unknown = set(s.selected_scenarios) - set(SCENARIO_TYPES)
    if unknown:
        raise ConfigError(f"unknown scenarios {sorted(unknown)}; valid: {list(SCENARIO_TYPES)}")
    if s.topology_api_mode == "real" and not s.topology_api_base_url:
        raise ConfigError("TOPOLOGY_API_BASE_URL required when TOPOLOGY_API_MODE=real")
    if s.knowledge_mode == "real" and not s.knowledge_api_base_url:
        raise ConfigError("KNOWLEDGE_API_BASE_URL required when KNOWLEDGE_MODE=real")

    # phase-specific requirements
    if s.phase in ("p2", "p3"):
        if s.sim_mode == "generate" and not s.kafka_bootstrap_servers:
            raise ConfigError("KAFKA_BOOTSTRAP_SERVERS required for P2/P3")
        if s.sim_mode == "generate":
            start, end = s.resolved_history_window()
            if s.phase == "p2" and start >= end:
                raise ConfigError("HISTORY_START must be < HISTORY_END")

    if s.total_alarms is not None:
        floor = _minable_floor(len(s.selected_scenarios), s.background_fraction, s.noise_rate)
        if s.total_alarms < floor:
            raise ConfigError(
                f"TOTAL_ALARMS={s.total_alarms} below minable floor {floor} "
                f"(5 x scenarios x min-cascade / signal_fraction)"
            )


def _minable_floor(num_scenarios: int, background_fraction: float, noise_rate: float) -> int:
    """Lowest TOTAL_ALARMS that still leaves ≥5 minable instances per scenario."""
    min_cascade = 5  # smallest grounded cascade (lsp-te-failure tail)
    signal = max(1e-6, 1.0 - background_fraction - noise_rate)
    return int((5 * max(1, num_scenarios) * min_cascade) / signal)


def load_settings(env: dict[str, str] | None = None) -> Settings:
    """Load + validate :class:`Settings` (optionally from an explicit env mapping for tests)."""
    if env is None:
        raw = Settings()
    else:
        raw = Settings(**_from_env(env))
    explicit = set(raw.model_fields_set)
    merged = raw.model_dump()
    merged = apply_profile(merged, explicit)
    settings = Settings(**merged)
    _validate(settings)
    return settings


_ALIAS_BY_FIELD = {name: f.alias or name for name, f in Settings.model_fields.items()}
_FIELD_BY_ALIAS = {v: k for k, v in _ALIAS_BY_FIELD.items()}


def _from_env(env: dict[str, str]) -> dict[str, object]:
    """Map an explicit ENV_VAR mapping onto Settings field names (for deterministic tests)."""
    out: dict[str, object] = {}
    for key, value in env.items():
        field = _FIELD_BY_ALIAS.get(key)
        if field is not None:
            out[field] = value
    return out
