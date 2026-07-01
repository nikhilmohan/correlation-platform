"""Prometheus collectors (design "Config & observability").

A dedicated registry is used so tests can construct an isolated metrics set without colliding
on the global default registry. The module-level singletons back the running service.
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram


class Metrics:
    """Bundle of all Noise-Filter Prometheus collectors, bound to one registry."""

    def __init__(self, registry: CollectorRegistry | None = None) -> None:
        self.registry = registry or CollectorRegistry()
        r = self.registry

        self.alarms_consumed = Counter(
            "nf_alarms_consumed_total", "AlarmEvents consumed from alarms.enriched", registry=r
        )
        self.windows_finalized = Counter(
            "nf_windows_finalized_total", "Trail-windows finalized", registry=r
        )
        self.windows_reopened = Counter(
            "nf_windows_reopened_total",
            "Already-finalized (trailId,bucket) windows re-opened by a late alarm",
            registry=r,
        )
        self.windows_force_finalized = Counter(
            "nf_windows_force_finalized_total",
            "Open (trailId,bucket) windows force-finalized by the max_open_windows memory bound",
            registry=r,
        )
        self.clusters_emitted = Counter(
            "nf_clusters_emitted_total", "Dense storm clusters emitted", registry=r
        )
        self.noise_points_dropped = Counter(
            "nf_noise_points_dropped_total", "Sparse noise points dropped", registry=r
        )
        self.transactions_emitted = Counter(
            "nf_transactions_emitted_total", "TransactionEvents emitted", registry=r
        )
        self.storm_reduction_ratio = Histogram(
            "nf_storm_reduction_ratio", "alarms_in / clusters_formed per window", registry=r
        )
        self.duplicates_dropped = Counter(
            "nf_duplicates_dropped_total", "Duplicate eventIds dropped", registry=r
        )
        self.dlq = Counter("nf_dlq_total", "Messages routed to DLQ", ["reason"], registry=r)
        self.topology_attr_skip = Counter(
            "nf_topology_attr_skip_total",
            "Attribute lookups skipped (Topology unavailable)",
            registry=r,
        )
        self.hop_feature_skip = Counter(
            "nf_hop_feature_skip_total",
            "Hop-distance dim skipped (Trail Builder unavailable)",
            registry=r,
        )
        self.knowledge_refresh = Counter(
            "nf_knowledge_refresh_total", "Knowledge param refreshes", registry=r
        )
        self.knowledge_refresh_failures = Counter(
            "nf_knowledge_refresh_failures_total", "Knowledge refresh failures", registry=r
        )
        self.snapshot_unresolved = Counter(
            "nf_snapshot_unresolved_total", "Windows with unresolvable snapshotId", registry=r
        )
        self.windows_no_cluster = Counter(
            "nf_windows_no_cluster_total", "Windows yielding no dense cluster", registry=r
        )
        self.produce_failures = Counter(
            "nf_produce_failures_total", "transactions.clean produce failures", registry=r
        )
        self.stats_write_failures = Counter(
            "nf_stats_write_failures_total", "run-stats write failures", registry=r
        )
        self.runstats_read_errors = Counter(
            "nf_runstats_read_errors_total", "read-API errors (DB unreachable)", registry=r
        )
        self.chatter_write_failures = Counter(
            "nf_chatter_write_failures_total", "observed-chatter write failures", registry=r
        )
        self.chatter_signatures_recorded = Counter(
            "nf_chatter_signatures_recorded_total",
            "observed-chatter signatures recorded",
            registry=r,
        )
        self.dbscan_duration = Histogram(
            "nf_dbscan_duration_seconds", "DBSCAN run duration", registry=r
        )
        self.window_size_alarms = Histogram(
            "nf_window_size_alarms", "alarms per finalized window", registry=r
        )
