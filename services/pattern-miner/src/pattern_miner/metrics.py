"""Prometheus collectors (design "Config & observability").

A dedicated registry lets tests build an isolated metric set without colliding on the global
default registry; the module-level singleton backs the running service.
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram


class Metrics:
    """Bundle of all pattern-miner Prometheus collectors, bound to one registry."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        r = self.registry

        self.transactions_consumed = Counter(
            "pm_transactions_consumed_total",
            "TransactionEvents consumed from transactions.clean",
            registry=r,
        )
        self.duplicates_dropped = Counter(
            "pm_duplicates_dropped_total", "Duplicate eventIds dropped (dedupe)", registry=r
        )
        self.dlq = Counter("pm_dlq_total", "Messages routed to the DLQ", ["reason"], registry=r)
        self.mining_runs = Counter("pm_mining_runs_total", "Mining runs executed", registry=r)
        self.mining_failures = Counter(
            "pm_mining_failures_total", "Mining runs that failed", registry=r
        )
        self.sequences_mined = Counter(
            "pm_sequences_mined_total", "Frequent sequences discovered", registry=r
        )
        self.patterns_emitted = Counter(
            "pm_patterns_emitted_total", "PatternMinedEvents emitted to patterns.mined", registry=r
        )
        self.fallback_gap_used = Counter(
            "pm_fallback_gap_used_total",
            "Sessions closed with the Knowledge base/fallback gap",
            registry=r,
        )
        self.knowledge_fetch_failures = Counter(
            "pm_knowledge_fetch_failures_total",
            "Knowledge mining-params fetch failures",
            registry=r,
        )
        self.codebook_fetch_failures = Counter(
            "pm_codebook_fetch_failures_total",
            "Codebook scenarios/active-codebook fetch failures (Stage 2)",
            registry=r,
        )
        self.cascades_anchored = Counter(
            "pm_cascades_anchored_total",
            "Candidate cascades anchored to a fault-origin scenario (Stage 2)",
            registry=r,
        )
        self.cascades_unexplained = Counter(
            "pm_cascades_unexplained_total",
            "Candidate cascades with no confident scenario match (unexplained)",
            registry=r,
        )
        self.produce_failures = Counter(
            "pm_produce_failures_total", "patterns.mined produce failures", registry=r
        )
        self.mining_duration = Histogram(
            "pm_mining_duration_seconds", "Mining run duration", registry=r
        )
        self.last_run_session_count = Gauge(
            "pm_last_run_session_count", "Sessions in the last mining run", registry=r
        )
        self.last_run_sequence_count = Gauge(
            "pm_last_run_sequence_count", "Sequences discovered in the last mining run", registry=r
        )
        self.anchored_group_count = Gauge(
            "pm_anchored_group_count",
            "Distinct anchored fault-origin groups in the last mining run",
            registry=r,
        )
        # [BATCH-CAP] trail-aligned batch-cap + SparkContext resilience observability.
        self.mining_sub_runs = Counter(
            "pm_mining_sub_runs_total",
            "Bounded whole-trail mining sub-runs executed (batch-cap)",
            registry=r,
        )
        self.spark_recreate_attempts = Counter(
            "pm_spark_recreate_attempts_total",
            "SparkSession recreate attempts after a detected gateway/driver death",
            registry=r,
        )
        self.spark_recreate_failures = Counter(
            "pm_spark_recreate_failures_total",
            "Runs failed clean after SparkSession recreate exhaustion",
            registry=r,
        )
        self.last_flush_sub_run_count = Gauge(
            "pm_last_flush_sub_run_count",
            "Number of bounded sub-runs the last flush was chunked into",
            registry=r,
        )
