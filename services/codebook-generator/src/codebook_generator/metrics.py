"""Prometheus metrics (design Config & observability).

Counters/gauges labelled by ``domain`` where applicable, plus integration-point latency
histograms. Uses a dedicated registry so tests can read values in isolation.
"""

from __future__ import annotations

from prometheus_client import CollectorRegistry, Counter, Histogram, generate_latest

REGISTRY = CollectorRegistry()

events_consumed_total = Counter(
    "codebook_events_consumed_total",
    "trails.built events consumed.",
    ["domain"],
    registry=REGISTRY,
)
compiled_total = Counter(
    "codebook_compiled_total",
    "Codebooks compiled.",
    ["domain"],
    registry=REGISTRY,
)
scenarios_generated_total = Counter(
    "codebook_scenarios_generated_total",
    "Scenarios generated.",
    ["domain"],
    registry=REGISTRY,
)
errors_total = Counter(
    "codebook_errors_total",
    "Compilation errors.",
    ["domain"],
    registry=REGISTRY,
)
dlq_routed_total = Counter(
    "codebook_dlq_routed_total",
    "Messages routed to a DLQ.",
    ["topic"],
    registry=REGISTRY,
)
integration_latency_seconds = Histogram(
    "codebook_integration_latency_seconds",
    "Integration-point call latency.",
    ["integration_point"],
    registry=REGISTRY,
)


def render_latest() -> bytes:
    """Render the Prometheus text exposition."""
    return generate_latest(REGISTRY)
