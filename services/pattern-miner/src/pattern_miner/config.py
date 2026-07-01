"""Configuration: env-sourced :class:`Settings` and the Knowledge-sourced typed mining params.

**No mining or windowing threshold is hard-coded here.** ``MiningParams`` / ``WindowingParams`` /
``TempoProfile`` hold values fetched from the Knowledge Service at runtime; there is no default
``minSupport`` / ``maxPatternLength`` / session-gap literal anywhere in this module (spec
criterion 9). ``Settings`` carries only Kafka/Knowledge wiring and operational knobs (URLs, modes,
retry policy, Spark master, log level) — never a correlation threshold.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class ClientMode(StrEnum):
    """Integration-point resolution mode (config-switchable mock vs real)."""

    mock = "mock"
    real = "real"


class MiningEngineKind(StrEnum):
    """Which PrefixSpan engine backs the miner.

    ``spark`` = the real Spark MLlib ``PrefixSpan`` (container-only; the deployed engine).
    ``local`` = a pure-Python reference PrefixSpan with identical semantics, used by the local unit
    gate where Spark/PySpark is not installed (per CLAUDE.md). The engine is a config toggle, not a
    behaviour change: both discover the same frequent ordered subsequences.
    """

    spark = "spark"
    local = "local"


class Settings(BaseSettings):
    """Process configuration from environment variables (no hard-coded secrets/URLs/thresholds)."""

    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    kafka_bootstrap_servers: str = Field(default="localhost:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    consumer_group_id: str = Field(
        default="pattern-miner-transactions.clean", alias="CONSUMER_GROUP_ID"
    )
    transactions_clean_topic: str = Field(
        default="transactions.clean", alias="TRANSACTIONS_CLEAN_TOPIC"
    )
    patterns_mined_topic: str = Field(default="patterns.mined", alias="PATTERNS_MINED_TOPIC")
    dlq_topic: str = Field(default="transactions.clean.dlq", alias="DLQ_TOPIC")

    knowledge_base_url: str = Field(default="http://knowledge:8080", alias="KNOWLEDGE_BASE_URL")
    knowledge_client_mode: ClientMode = Field(
        default=ClientMode.real, alias="KNOWLEDGE_CLIENT_MODE"
    )
    knowledge_domain: str = Field(default="core-ip", alias="KNOWLEDGE_DOMAIN")
    knowledge_model_params_record_id: str = Field(
        default="core-ip/modelParams/pattern-miner", alias="KNOWLEDGE_MODEL_PARAMS_RECORD_ID"
    )
    knowledge_retry_max: int = Field(default=5, alias="KNOWLEDGE_RETRY_MAX")
    knowledge_retry_backoff_ms: int = Field(default=500, alias="KNOWLEDGE_RETRY_BACKOFF_MS")

    # Codebook Service (Stage 2 domain-knowledge anchoring). Only wiring/operational knobs live
    # here — the fault-origin scenario SET and every anchoring THRESHOLD come from Codebook +
    # Knowledge at runtime, never from a code/env default (spec Non-functional, AC-17).
    codebook_base_url: str = Field(default="http://codebook-api:8000", alias="CODEBOOK_BASE_URL")
    codebook_client_mode: ClientMode = Field(default=ClientMode.real, alias="CODEBOOK_CLIENT_MODE")
    codebook_retry_max: int = Field(default=5, alias="CODEBOOK_RETRY_MAX")
    codebook_retry_backoff_ms: int = Field(default=500, alias="CODEBOOK_RETRY_BACKOFF_MS")

    spark_master: str = Field(default="local[*]", alias="SPARK_MASTER")
    mining_engine: MiningEngineKind = Field(default=MiningEngineKind.spark, alias="MINING_ENGINE")

    # How long a P2 mining run pools transactions before it flushes a batch to the miner. This is
    # a batching/latency knob, NOT a windowing (session) gap — the session gap is Knowledge-sourced
    # and adaptive. Operational default only.
    batch_flush_seconds: float = Field(default=2.0, alias="BATCH_FLUSH_SECONDS")

    log_level: str = Field(default="INFO", alias="LOG_LEVEL")
    http_port: int = Field(default=8080, alias="HTTP_PORT")


@dataclass(frozen=True)
class TempoProfile:
    """A Knowledge-authored tempo-class floor (and optional ceiling) for the adaptive gap.

    ``floor_seconds`` is the smallest closing gap allowed for a burst in this tempo class;
    ``ceiling_seconds`` (optional) caps it. Both come from the Knowledge
    ``window.adaptive.profiles`` map — never a code literal.
    """

    name: str
    floor_seconds: float
    ceiling_seconds: float | None = None


@dataclass(frozen=True)
class WindowingParams:
    """Immutable, Knowledge-sourced adaptive session-windowing parameters (spec §Windowing).

    The closing idle gap for a burst is::

        closingGap(burst) = clamp(
            multiplier * percentile(interArrivals(burst), p),
            lower = profileFloor(tempoClass(burst)),
            upper = max_closing_gap_seconds,
        )

    with the ``base_gap_seconds`` fallback used when a burst has too few inter-arrivals to derive a
    stable percentile (``< min_burst_samples``) or when no tempo-class profile matches. **Every**
    field here originates from the Knowledge model-params record; none is a hard-coded default.
    """

    base_gap_seconds: float
    gap_multiplier: float
    tempo_percentile: float
    max_closing_gap_seconds: float
    min_burst_samples: int
    profiles: dict[str, TempoProfile] = field(default_factory=dict)
    # Observed-median thresholds (seconds) that map a burst to a tempo class. Optional: when a
    # profile named for the class exists it supplies the floor; the class boundaries themselves are
    # Knowledge-authored. When absent, the profile whose floor best matches is selected.
    class_thresholds: dict[str, float] = field(default_factory=dict)


@dataclass(frozen=True)
class AnchoringParams:
    """Immutable, Knowledge-sourced Stage-2 domain-knowledge anchoring parameters.

    Every value originates from the Knowledge model-params record's ``anchoring.*`` keys — there is
    **no** hard-coded matching threshold or scorer weight (spec AC-7, AC-17). Onboarding a new
    domain re-authors these in Knowledge; no code change.

    * ``match_confidence_threshold`` — the confidence in ``[0, 1]`` a cascade-vs-scenario match must
      reach to anchor; below it the cascade is "unexplained". **Required** (no default).
    * ``scoring_method`` — selects the scorer (the reusable template ships one:
      ``ordered_subsequence_jaccard`` = weighted LCS-ratio + Jaccard). A structural token, not a
      threshold; the Knowledge value is used when present.
    * ``grouping_keys`` — the anchor-grouping key list (default ``["scenarioId"]`` — one group per
      fault-origin scenario). Domain-agnostic structural default, not a domain literal.
    * ``tie_break`` — deterministic tie-break token for equal-confidence scenarios
      (``chain_length_then_scenario_id``). Structural, not a threshold.
    * ``w_order`` / ``w_jaccard`` — scorer weights (sum to 1). **Required** (no default) —
      Knowledge-sourced tuning knobs, not code literals.
    """

    match_confidence_threshold: float
    w_order: float
    w_jaccard: float
    scoring_method: str
    tie_break: str
    grouping_keys: tuple[str, ...]


@dataclass(frozen=True)
class MiningParams:
    """Immutable snapshot of the Knowledge-sourced mining configuration for one run.

    Sourced entirely from the Knowledge model-params record (recordId
    ``core-ip/modelParams/pattern-miner``) — ``prefixspan.*``, ``window.adaptive.*``,
    ``anchoring.*``, and ``codebookVersion``. No value here is a code default (spec criteria 9, 6).
    """

    min_support: float
    max_pattern_length: int
    max_sequence_count: int
    windowing: WindowingParams
    anchoring: AnchoringParams
    codebook_version: str
