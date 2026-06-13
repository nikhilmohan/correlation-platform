"""Observability tests: JSON log formatting + logging configuration.

Covers the structured-JSON requirement (logs carry traceId / snapshotId /
domain via ``extra=``) and the idempotent root-logger configuration.
"""

from __future__ import annotations

import json
import logging

from trailbuilder.observability import JsonFormatter, configure_logging, get_logger


def test_json_formatter_emits_single_line_json() -> None:
    record = logging.makeLogRecord(
        {"msg": "build done", "levelname": "INFO", "name": "trailbuilder.test"}
    )
    out = JsonFormatter().format(record)
    payload = json.loads(out)
    assert payload["message"] == "build done"
    assert payload["level"] == "INFO"
    assert payload["logger"] == "trailbuilder.test"
    assert "ts" in payload


def test_json_formatter_promotes_structured_context() -> None:
    """traceId / snapshotId / domain passed via extra= surface as top-level keys."""
    record = logging.makeLogRecord(
        {
            "msg": "built",
            "levelname": "INFO",
            "name": "t",
            "traceId": "trace-1",
            "snapshotId": "snap-1",
            "domain": "core-ip",
        }
    )
    payload = json.loads(JsonFormatter().format(record))
    assert payload["traceId"] == "trace-1"
    assert payload["snapshotId"] == "snap-1"
    assert payload["domain"] == "core-ip"


def test_json_formatter_includes_exception() -> None:
    try:
        raise ValueError("boom")
    except ValueError:
        import sys

        record = logging.makeLogRecord({"msg": "failed", "levelname": "ERROR", "name": "t"})
        record.exc_info = sys.exc_info()
        payload = json.loads(JsonFormatter().format(record))
    assert "exc_info" in payload
    assert "ValueError" in payload["exc_info"]


def test_configure_logging_installs_json_handler_idempotently() -> None:
    configure_logging("DEBUG")
    root = logging.getLogger()
    assert root.level == logging.DEBUG
    handlers_after_first = list(root.handlers)
    assert len(handlers_after_first) == 1
    assert isinstance(handlers_after_first[0].formatter, JsonFormatter)
    # Re-running replaces (does not stack) handlers.
    configure_logging("INFO")
    assert len(root.handlers) == 1
    assert root.level == logging.INFO


def test_get_logger_returns_named_logger() -> None:
    log = get_logger("trailbuilder.example")
    assert log.name == "trailbuilder.example"
