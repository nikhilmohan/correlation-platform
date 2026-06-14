"""Structured JSON logging tests (criterion 18 — fatal config errors reported as JSON).

Every log line is a single JSON object carrying ``ts``/``level``/``event``/``msg`` plus any
merged extra fields. The formatter is stdlib-only so a missing-config fatal can still be
reported as structured JSON before exit.
"""

from __future__ import annotations

import json
import logging

from simulator.obs import logging as obs_logging


def _capture(record: logging.LogRecord) -> dict:
    return json.loads(obs_logging.JsonFormatter().format(record))


def test_formatter_emits_single_json_object_with_core_fields() -> None:
    rec = logging.LogRecord(
        name="simulator.test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="hello %s",
        args=("world",),
        exc_info=None,
    )
    out = _capture(rec)
    assert out["level"] == "INFO"
    assert out["msg"] == "hello world"
    assert out["event"] == "simulator.test"  # falls back to logger name
    assert "ts" in out


def test_formatter_merges_extra_event_tag_and_fields() -> None:
    rec = logging.LogRecord(
        name="x", level=logging.ERROR, pathname=__file__, lineno=1, msg="m", args=(), exc_info=None
    )
    rec.event = "config.invalid"  # type: ignore[attr-defined]
    rec.phase = "p2"  # type: ignore[attr-defined]
    out = _capture(rec)
    assert out["event"] == "config.invalid"
    assert out["phase"] == "p2"


def test_formatter_renders_exception() -> None:
    try:
        raise ValueError("boom")
    except ValueError:
        import sys

        rec = logging.LogRecord(
            name="x",
            level=logging.ERROR,
            pathname=__file__,
            lineno=1,
            msg="failed",
            args=(),
            exc_info=sys.exc_info(),
        )
    out = _capture(rec)
    assert "ValueError: boom" in out["exc"]


def test_configure_logging_installs_json_handler_to_stdout(capsys) -> None:
    obs_logging.configure_logging("DEBUG")
    log = obs_logging.get_logger("simulator.cfgtest")
    log.info("startup")
    captured = capsys.readouterr().out.strip().splitlines()
    payload = json.loads(captured[-1])
    assert payload["msg"] == "startup"
    assert logging.getLogger().level == logging.DEBUG


def test_log_event_helper_emits_structured_event(capsys) -> None:
    obs_logging.configure_logging("INFO")
    log = obs_logging.get_logger("simulator.evt")
    obs_logging.log_event(log, logging.ERROR, "run.dependency_failure", "kafka down", attempts=3)
    payload = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert payload["event"] == "run.dependency_failure"
    assert payload["msg"] == "kafka down"
    assert payload["attempts"] == 3
    assert payload["level"] == "ERROR"
