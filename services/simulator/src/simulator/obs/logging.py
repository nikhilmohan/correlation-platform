"""Structured JSON logging on stdout (one object per line).

Every log line is a single JSON object carrying at least ``ts``/``level``/``event``/``msg``;
``runId``/``scenarioId``/``mode`` and arbitrary extra keys are merged in when supplied. The
formatter is deliberately dependency-free (stdlib ``logging`` + ``json``) so a missing-config
fatal error can still be reported as structured JSON before exit (criterion 18).
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from typing import Any

_RESERVED = {
    "name",
    "msg",
    "args",
    "levelname",
    "levelno",
    "pathname",
    "filename",
    "module",
    "exc_info",
    "exc_text",
    "stack_info",
    "lineno",
    "funcName",
    "created",
    "msecs",
    "relativeCreated",
    "thread",
    "threadName",
    "processName",
    "process",
    "taskName",
    "event",
}


class JsonFormatter(logging.Formatter):
    """Render a log record as a single-line JSON object."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "event": getattr(record, "event", record.name),
            "msg": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                payload[key] = value
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload, default=str, sort_keys=False)


def configure_logging(level: str = "INFO") -> None:
    """Install the JSON formatter on the root logger writing to stdout."""
    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())


def get_logger(name: str = "simulator") -> logging.Logger:
    """Return a named logger (the root JSON handler does the formatting)."""
    return logging.getLogger(name)


def log_event(
    logger: logging.Logger,
    level: int,
    event: str,
    msg: str,
    **fields: Any,
) -> None:
    """Emit a structured event with an ``event`` tag and arbitrary extra fields."""
    logger.log(level, msg, extra={"event": event, **fields})
