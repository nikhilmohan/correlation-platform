"""Structured JSON logging + Prometheus metrics.

Logs carry ``traceId``, ``snapshotId``, and ``domain`` where applicable (passed
via the ``extra=`` kwarg). Metrics mirror the design's metric set.
"""

from __future__ import annotations

import json
import logging
import sys
from typing import Any

from prometheus_client import Counter, Gauge, Histogram

_RESERVED = set(logging.makeLogRecord({}).__dict__) | {"message", "asctime"}


class JsonFormatter(logging.Formatter):
    """Render each log record as a single-line JSON object."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": self.formatTime(record, "%Y-%m-%dT%H:%M:%S%z"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }
        # Promote structured context (traceId / snapshotId / domain / ...).
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                payload[key] = value
        if record.exc_info:
            payload["exc_info"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str)


def configure_logging(level: str = "INFO") -> None:
    """Install the JSON formatter on the root logger (idempotent)."""
    root = logging.getLogger()
    root.setLevel(level.upper())
    for handler in list(root.handlers):
        root.removeHandler(handler)
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root.addHandler(handler)


def get_logger(name: str) -> logging.Logger:
    """Return a module logger."""
    return logging.getLogger(name)


# --- Prometheus metrics (design "Observability") ---
BUILDS_TOTAL = Counter("builds_total", "Trail builds attempted", ["domain"])
BUILD_FAILURES_TOTAL = Counter("build_failures_total", "Trail builds that failed", ["reason"])
BUILD_DURATION = Histogram("build_duration_seconds", "Trail build wall-clock duration")
TRAILS_BUILT = Counter("trails_built", "Trails persisted", ["domain"])
DLQ_MESSAGES_TOTAL = Counter("dlq_messages_total", "Messages routed to a DLQ")
POLICY_REFRESHES_TOTAL = Counter("policy_refreshes_total", "Policy cache invalidations", ["domain"])
QUERY_REQUESTS_TOTAL = Counter("query_requests_total", "Query-API requests", ["op"])
TRAIL_DISTINCT_IGP_AREAS = Gauge(
    "trail_distinct_igp_areas", "Distinct igpArea values across built trails", ["domain"]
)
