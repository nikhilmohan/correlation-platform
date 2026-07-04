"""Prometheus metrics registry for the Simulator (criterion 17).

A single module-level :class:`CollectorRegistry` holds every metric the design's *Config &
observability* section enumerates. Tests assert ``simulator_alarms_emitted_total`` is present
in the ``/metrics`` exposition; the integration harness reads the labelled counters to compute
the §10 oracle metrics. The registry is isolated (not the global default) so a fresh process /
test gets clean state.
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Gauge, generate_latest

REGISTRY = CollectorRegistry()

# --- emission counters --------------------------------------------------------------------
ALARMS_EMITTED = Counter(
    "simulator_alarms_emitted_total",
    "Total AlarmEvents emitted, labelled by target topic, scenario and canonical alarmType.",
    ["topic", "scenario", "alarmType"],
    registry=REGISTRY,
)
SCENARIOS_INJECTED = Counter(
    "simulator_scenarios_injected_total",
    "Scenario instances injected, labelled by scenario type.",
    ["scenario"],
    registry=REGISTRY,
)
BACKGROUND_ALARMS = Counter(
    "simulator_background_alarms_total",
    "Background (non-pattern) alarms emitted.",
    registry=REGISTRY,
)
NOISE_ALARMS = Counter(
    "simulator_noise_alarms_total",
    "Noise alarms emitted, labelled by noise class.",
    ["class"],
    registry=REGISTRY,
)
HARD_NOISE_ALARMS = Counter(
    "simulator_hard_noise_alarms_total",
    "Noise alarms placed near a cascade (DBSCAN stress).",
    registry=REGISTRY,
)
PRODUCE_ERRORS = Counter(
    "simulator_produce_errors_total",
    "Kafka produce/delivery errors.",
    registry=REGISTRY,
)

# --- P3 topology-and-pattern-driven synthesis ---------------------------------------------
P3_PLACEMENT_FALLBACK = Counter(
    "simulator_p3_placement_fallback_total",
    "P3 aligned-cascade placements that fell back to any trail member (no affine objectType), "
    "labelled by alarmType.",
    ["alarmType"],
    registry=REGISTRY,
)
P3_ALIGNED_ALARMS = Counter(
    "simulator_p3_aligned_alarms_total",
    "P3 pattern-aligned alarms emitted.",
    registry=REGISTRY,
)
P3_NONALIGNED_ALARMS = Counter(
    "simulator_p3_nonaligned_alarms_total",
    "P3 non-aligned alarms emitted, labelled by scenarioType.",
    ["scenarioType"],
    registry=REGISTRY,
)

# --- ingest / export ----------------------------------------------------------------------
INGESTED_ALARMS = Counter(
    "simulator_ingested_alarms_total",
    "Alarms replayed verbatim from an ingested corpus, labelled by topic.",
    ["topic"],
    registry=REGISTRY,
)
INGEST_VALIDATION_ERRORS = Counter(
    "simulator_ingest_validation_errors_total",
    "Malformed corpus/snapshot lines rejected during ingest (fail-fast).",
    registry=REGISTRY,
)
EXPORTED_CORPUS_RECORDS = Counter(
    "simulator_exported_corpus_records_total",
    "Alarms written to the export corpus file.",
    registry=REGISTRY,
)

# --- gauges -------------------------------------------------------------------------------
PACING_DRIFT_MS = Gauge(
    "simulator_pacing_drift_ms",
    "Most recent live-replay pacing drift in milliseconds.",
    registry=REGISTRY,
)
SNAPSHOT_NODES = Gauge(
    "simulator_snapshot_nodes",
    "Node count in the generated/ingested topology snapshot.",
    registry=REGISTRY,
)
SNAPSHOT_SITES = Gauge(
    "simulator_snapshot_sites",
    "Distinct grounded Site nodes in the snapshot.",
    registry=REGISTRY,
)
SNAPSHOT_INTERFACES = Gauge(
    "simulator_snapshot_interfaces",
    "Interface node count in the snapshot.",
    registry=REGISTRY,
)
SNAPSHOT_IGP_AREAS = Gauge(
    "simulator_snapshot_igp_areas",
    "Distinct igpArea values emitted in the snapshot.",
    registry=REGISTRY,
)
SNAPSHOT_EDGES = Gauge(
    "simulator_snapshot_edges",
    "Edge count in the snapshot, labelled by relation.",
    ["relation"],
    registry=REGISTRY,
)
TARGET_ALARMS = Gauge(
    "simulator_target_alarms",
    "Resolved TOTAL_ALARMS target when set.",
    registry=REGISTRY,
)
DISTINCT_SCENARIOS = Gauge(
    "simulator_distinct_scenarios",
    "Count of distinct labelled scenario types in the run.",
    registry=REGISTRY,
)
MODE = Gauge(
    "simulator_mode",
    "Current data-source mode (1 = active).",
    ["mode"],
    registry=REGISTRY,
)
P3_ALIGNED_FRACTION = Gauge(
    "simulator_p3_aligned_fraction",
    "Realized P3 pattern-aligned fraction for the last synth run.",
    registry=REGISTRY,
)


def render() -> bytes:
    """Render the Prometheus text exposition for the Simulator registry."""
    return generate_latest(REGISTRY)
