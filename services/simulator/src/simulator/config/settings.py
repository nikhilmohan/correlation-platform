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
Mode = Literal["generate", "ingest", "synth"]


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

    # P3 topology-and-pattern-driven synthesis integrations (config-switchable mock/real)
    pattern_manager_api_mode: Literal["mock", "real"] = Field(
        default="mock", alias="PATTERN_MANAGER_API_MODE"
    )
    pattern_manager_api_base_url: str | None = Field(
        default=None, alias="PATTERN_MANAGER_API_BASE_URL"
    )
    trail_builder_api_mode: Literal["mock", "real"] = Field(
        default="mock", alias="TRAIL_BUILDER_API_MODE"
    )
    trail_builder_api_base_url: str | None = Field(default=None, alias="TRAIL_BUILDER_API_BASE_URL")

    # P3 synthesis knobs (no hard-coded thresholds — env/CLI overridable)
    synth_domain: str = Field(default="core-ip", alias="SYNTH_DOMAIN")
    p3_aligned_fraction: float = Field(default=0.65, alias="P3_ALIGNED_FRACTION")
    p3_total_alarms: int = Field(default=500, alias="P3_TOTAL_ALARMS")
    p3_rng_seed: int | None = Field(default=None, alias="P3_RNG_SEED")
    p3_config_snapshot_path: str | None = Field(default=None, alias="P3_CONFIG_SNAPSHOT_PATH")
    p3_optional_include_prob: float = Field(default=1.0, alias="P3_OPTIONAL_INCLUDE_PROB")
    # Stagger controls (M1): successive cascades on the same (trailId, patternId) are separated by
    # windowMs * P3_STAGGER_MARGIN (+ a seeded jitter up to P3_STAGGER_JITTER_MS) so each forms its
    # own Correlation-Engine session/incident. margin > 1.0 guarantees strictly-more-than-windowMs.
    p3_stagger_margin: float = Field(default=1.5, alias="P3_STAGGER_MARGIN")
    p3_stagger_jitter_ms: float = Field(default=2000.0, alias="P3_STAGGER_JITTER_MS")
    # Fraction of a pattern's sessionWindow the in-window cascade span is compressed to fit inside
    # (leaves a margin so the last alarm lands strictly inside the window). No hard-coded literal.
    p3_in_window_margin: float = Field(default=0.9, alias="P3_IN_WINDOW_MARGIN")
    p3_partial_cascade_fraction: float = Field(default=0.4, alias="P3_PARTIAL_CASCADE_FRACTION")
    p3_random_alarm_fraction: float = Field(default=0.4, alias="P3_RANDOM_ALARM_FRACTION")
    p3_noise_fraction: float = Field(default=0.2, alias="P3_NOISE_FRACTION")

    # P3 network-wide emission + closed-loop auto-correlation target (additive; behind the flag).
    # When P3_NETWORK_WIDE is off (or the target is unset) the existing single-trail path runs
    # byte-for-byte unchanged (AC 58). All items env/CLI overridable; no hard-coded thresholds.
    p3_network_wide: bool = Field(default=False, alias="P3_NETWORK_WIDE")
    # The CE-measured post-enrichment target correlatedAlarmCount/totalAlarmsProcessed. Unset ->
    # single-trail behaviour (AC 58). Range [0,1].
    p3_auto_correlation_target: float | None = Field(
        default=None, alias="P3_AUTO_CORRELATION_TARGET"
    )
    p3_target_tolerance: float = Field(default=0.03, alias="P3_TARGET_TOLERANCE")
    p3_max_cascades_per_trail: int = Field(default=3, alias="P3_MAX_CASCADES_PER_TRAIL")
    # Per-cascade CORRELATED yield: the fraction of a cascade's EMITTED alarms that survive to be
    # counted by the Correlation Engine as correlated members. On the live path each cascade of
    # emitted length L yields FEWER than L correlated alarms: enrichment legitimately trims ~1
    # element and CE fires at partialMatchTolerance (needs N-1 of N), so an incident holds ~L-1
    # members, not L. Live-measured on this platform: emitting 150 aligned alarms yielded 91
    # CE-correlated => ~0.61 correlated-per-emitted-aligned on a first bench read. The controller
    # sizes the number of aligned cascades by this yield so P3_AUTO_CORRELATION_TARGET lands the
    # CE-measured rate without manual over-provision-margin tuning. When > 0 this flat fraction is
    # used directly (expected correlated per cascade = yield * L); when <= 0 the controller DERIVES
    # the yield from each pattern's length + tolerance (L - expected_enrichment_trim, bounded by the
    # N-tolerance firing floor).
    #
    # Default 0.66 is the MEASURED ACTUAL correlated-per-emitted-aligned yield on this platform.
    # Derivation: at estimate 0.61 with TARGET=0.6 the controller emitted 0.6/0.61 ~= 0.984 of T
    # as aligned and CE realized ~0.653 (over-target). The realized rate = emitted_aligned_fraction
    # * actual_yield, so actual_yield ~= 0.653 / 0.984 ~= 0.66. Feeding that back as the estimate
    # makes the controller emit 0.6/0.66 ~= 0.91 of T aligned and realize ~0.60 — TARGET centers on
    # the realized rate. Ops can override P3_CASCADE_YIELD for a different enrichment/tolerance cfg.
    p3_cascade_yield: float = Field(default=0.66, alias="P3_CASCADE_YIELD")
    # CE partial-match tolerance: an incident fires when N - tolerance of a pattern's N elements
    # match (default 1 => N-1). Used only by the DERIVED yield path (P3_CASCADE_YIELD <= 0) as the
    # firing floor. Overridable so ops can mirror the deployed Knowledge partialMatchTolerance.
    p3_partial_match_tolerance: int = Field(default=1, alias="P3_PARTIAL_MATCH_TOLERANCE")
    # Expected number of cascade elements enrichment legitimately trims on the live path (~1).
    # Used only by the DERIVED yield path (P3_CASCADE_YIELD <= 0): expected correlated per cascade
    # = L - trim, bounded below by the N-tolerance firing floor. Overridable per enrichment config.
    p3_expected_enrichment_trim: int = Field(default=1, alias="P3_EXPECTED_ENRICHMENT_TRIM")
    # Legacy blunt lever: emitted aligned fraction = TARGET / (1 - margin). SUBSUMED by the yield
    # model above and now defaulted to 0.0 (no over-provision). Kept as an ADDITIONAL fudge factor
    # for ops whose enrichment differs materially. Range [0,1).
    p3_enrichment_over_provision_margin: float = Field(
        default=0.0, alias="P3_ENRICHMENT_OVER_PROVISION_MARGIN"
    )
    # Must match the deployed enrichment FilterParams.dedupWindow (config-not-contract). Drives the
    # enrichment-safe inter-arrival lower bound; ms; > 0.
    p3_enrichment_dedup_window_ms: int = Field(default=2000, alias="P3_ENRICHMENT_DEDUP_WINDOW_MS")
    # Comma-separated transient/self-clearing alarmTypes excluded from aligned cascades (AC 60).
    # Empty -> pack-derived (the pack's self-clearing noise classes). Never hard-coded in synth.
    p3_enrichment_transient_types: str = Field(default="", alias="P3_ENRICHMENT_TRANSIENT_TYPES")
    # epsilon so the enrichment-safe spacing lower bound lo = dedup_ms * (1 + margin) is strictly
    # above the dedup window (AC 61). >= 0.
    p3_dedup_spacing_margin: float = Field(default=0.1, alias="P3_DEDUP_SPACING_MARGIN")

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

    @property
    def resolved_p3_config_snapshot_path(self) -> str:
        """The P3 config snapshot path (explicit override or the default under SIM_OUTPUT_DIR)."""
        if self.p3_config_snapshot_path:
            return self.p3_config_snapshot_path
        return f"{self.sim_output_dir.rstrip('/')}/p3-config-snapshot.json"

    @property
    def p3_network_wide_active(self) -> bool:
        """Network-wide P3 is active when the flag is on OR an auto-correlation target is set.

        The target implies network-wide (design config table: "auto-``true`` when target set"); the
        flag alone also enables it. When both are false/unset the existing single-trail path runs
        unchanged (AC 58).
        """
        return self.p3_network_wide or self.p3_auto_correlation_target is not None

    def p3_transient_type_set(self) -> set[str]:
        """The configured transient/self-clearing alarmType set excluded from aligned cascades.

        Parsed from ``P3_ENRICHMENT_TRANSIENT_TYPES`` (comma-separated). An empty value means
        "pack-derived" and the pack fills it in (see ``enrichment_safe.transient_types``); this
        accessor only parses the explicit override, never a hard-coded default (CLAUDE.md).
        """
        return {t.strip() for t in self.p3_enrichment_transient_types.split(",") if t.strip()}

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

    if s.sim_mode == "synth":
        _validate_synth(s)

    if s.total_alarms is not None:
        floor = _minable_floor(len(s.selected_scenarios), s.background_fraction, s.noise_rate)
        if s.total_alarms < floor:
            raise ConfigError(
                f"TOTAL_ALARMS={s.total_alarms} below minable floor {floor} "
                f"(5 x scenarios x min-cascade / signal_fraction)"
            )


def _validate_synth(s: Settings) -> None:
    """P3 synthesis fail-fast validation (AC 44, 46) — runs before any alarm emission."""
    # synth pins to P3 / alarms.live and needs Kafka to emit.
    if not s.kafka_bootstrap_servers:
        raise ConfigError("KAFKA_BOOTSTRAP_SERVERS required for synth mode")
    if not (0.0 <= s.p3_aligned_fraction <= 1.0):
        raise ConfigError(f"P3_ALIGNED_FRACTION={s.p3_aligned_fraction} out of range [0,1]")
    if s.p3_total_alarms < 1:
        raise ConfigError("P3_TOTAL_ALARMS must be >= 1")
    if not (0.0 <= s.p3_optional_include_prob <= 1.0):
        raise ConfigError(
            f"P3_OPTIONAL_INCLUDE_PROB={s.p3_optional_include_prob} out of range [0,1]"
        )
    if s.p3_stagger_margin <= 1.0:
        raise ConfigError(
            f"P3_STAGGER_MARGIN={s.p3_stagger_margin} must be > 1.0 (strictly separate cascades "
            "on the same (trailId, patternId) beyond their sessionWindow)"
        )
    if s.p3_stagger_jitter_ms < 0:
        raise ConfigError("P3_STAGGER_JITTER_MS must be >= 0")
    if not (0.0 < s.p3_in_window_margin <= 1.0):
        raise ConfigError(f"P3_IN_WINDOW_MARGIN={s.p3_in_window_margin} out of range (0,1]")
    mix = s.p3_partial_cascade_fraction + s.p3_random_alarm_fraction + s.p3_noise_fraction
    if abs(mix - 1.0) > 1e-6:
        raise ConfigError(
            "P3 non-aligned mix fractions (partial+random+noise) must sum to 1.0, " f"got {mix:.4f}"
        )
    # Collaborator URLs required when the corresponding mode is real (AC 44/46). Only enforced
    # when a re-fetch is required (no persisted config snapshot present is a runtime concern; the
    # missing-URL check is a config concern enforced unconditionally so `real` never runs URL-less).
    if s.pattern_manager_api_mode == "real" and not s.pattern_manager_api_base_url:
        raise ConfigError(
            "PATTERN_MANAGER_API_BASE_URL required when PATTERN_MANAGER_API_MODE=real"
        )
    if s.trail_builder_api_mode == "real" and not s.trail_builder_api_base_url:
        raise ConfigError("TRAIL_BUILDER_API_BASE_URL required when TRAIL_BUILDER_API_MODE=real")
    if s.topology_api_mode == "real" and not s.topology_api_base_url:
        raise ConfigError("TOPOLOGY_API_BASE_URL required when TOPOLOGY_API_MODE=real")
    _validate_network_wide(s)


def _validate_network_wide(s: Settings) -> None:
    """Fail-fast range checks for the P3 network-wide knobs (design error-handling table).

    Enforced unconditionally so an invalid value never reaches a run; when network-wide is
    inactive the knobs keep their (valid) defaults so this is a no-op for the single-trail path.
    """
    target = s.p3_auto_correlation_target
    if target is not None and not (0.0 <= target <= 1.0):
        raise ConfigError(f"P3_AUTO_CORRELATION_TARGET={target} out of range [0,1]")
    if s.p3_target_tolerance <= 0.0:
        raise ConfigError(f"P3_TARGET_TOLERANCE={s.p3_target_tolerance} must be > 0")
    if s.p3_max_cascades_per_trail < 1:
        raise ConfigError(f"P3_MAX_CASCADES_PER_TRAIL={s.p3_max_cascades_per_trail} must be >= 1")
    if not (0.0 <= s.p3_enrichment_over_provision_margin < 1.0):
        raise ConfigError(
            f"P3_ENRICHMENT_OVER_PROVISION_MARGIN={s.p3_enrichment_over_provision_margin} "
            "out of range [0,1)"
        )
    if s.p3_enrichment_dedup_window_ms <= 0:
        raise ConfigError(
            f"P3_ENRICHMENT_DEDUP_WINDOW_MS={s.p3_enrichment_dedup_window_ms} must be > 0"
        )
    if s.p3_dedup_spacing_margin < 0.0:
        raise ConfigError(f"P3_DEDUP_SPACING_MARGIN={s.p3_dedup_spacing_margin} must be >= 0")
    if s.p3_cascade_yield > 1.0:
        raise ConfigError(f"P3_CASCADE_YIELD={s.p3_cascade_yield} must be <= 1.0")
    if s.p3_partial_match_tolerance < 0:
        raise ConfigError(f"P3_PARTIAL_MATCH_TOLERANCE={s.p3_partial_match_tolerance} must be >= 0")
    if s.p3_expected_enrichment_trim < 0:
        raise ConfigError(
            f"P3_EXPECTED_ENRICHMENT_TRIM={s.p3_expected_enrichment_trim} must be >= 0"
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
