"""Structured JSON logging tests (non-functional: observability).

Verifies :mod:`codebook_generator.logging_config` renders single-line JSON carrying the
mandated fields (level, timestamp, service, message) plus context fields (traceId, domain,
snapshotId) when supplied on the log call's ``extra``, and that ``configure_logging`` installs
exactly one JSON handler idempotently.
"""

from __future__ import annotations

import json
import logging

from codebook_generator.logging_config import (
    SERVICE_NAME,
    JsonFormatter,
    configure_logging,
    get_logger,
)


def _format(record: logging.LogRecord) -> dict:
    return json.loads(JsonFormatter().format(record))


def test_json_formatter_emits_core_fields() -> None:
    """A bare log record renders level/service/message/timestamp."""
    record = logging.LogRecord("x", logging.INFO, __file__, 1, "hello", args=(), exc_info=None)
    payload = _format(record)
    assert payload["level"] == "INFO"
    assert payload["service"] == SERVICE_NAME
    assert payload["message"] == "hello"
    assert "timestamp" in payload


def test_json_formatter_includes_context_fields() -> None:
    """Context fields (traceId, domain, snapshotId) surface from the record extras."""
    record = logging.LogRecord("x", logging.INFO, __file__, 1, "compiled", args=(), exc_info=None)
    record.traceId = "trace-1"
    record.domain = "core-ip"
    record.snapshotId = "snap-X"
    payload = _format(record)
    assert payload["traceId"] == "trace-1"
    assert payload["domain"] == "core-ip"
    assert payload["snapshotId"] == "snap-X"


def test_json_formatter_renders_exception() -> None:
    """An exc_info record renders the exception text."""
    try:
        raise ValueError("boom")
    except ValueError:
        import sys

        record = logging.LogRecord(
            "x", logging.ERROR, __file__, 1, "failed", args=(), exc_info=sys.exc_info()
        )
    payload = _format(record)
    assert "boom" in payload["exception"]


def test_configure_logging_installs_single_json_handler() -> None:
    """configure_logging is idempotent — one JSON handler on the root logger."""
    configure_logging("DEBUG")
    configure_logging("INFO")
    root = logging.getLogger()
    assert len(root.handlers) == 1
    assert isinstance(root.handlers[0].formatter, JsonFormatter)
    assert get_logger("codebook_generator.test").name == "codebook_generator.test"
