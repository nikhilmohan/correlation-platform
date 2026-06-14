"""Ingest loader/validator — skip generation, replay pre-created files (criteria 36-39).

Loads pre-created files and bypasses the generation stage:
  * **Topology snapshot** (P1): validated against the canonical ``snapshot.schema.json`` +
    every ``managedObjectId`` (reusing ``snapshot_writer``'s validator); uploaded verbatim.
  * **Alarm corpus** (P2/P3): each ``envelope.payload`` is reconstructed + validated against the
    frozen ``acp_event_model.AlarmEvent`` binding (incl. required ``alarmType``); yielded in seq
    order to ``replay`` and replayed verbatim (fresh ``eventId``, preserved payload + order).
  * **Labels** (P2/P3): parsed into the frozen label shape so the oracle works.

Malformed input fails fast (a structured error naming file+line) before any emission/upload;
``simulator_ingest_validation_errors_total`` is incremented.
"""

from __future__ import annotations

import json
from pathlib import Path

from acp_event_model import AlarmEvent
from pydantic import ValidationError

from simulator.engine import snapshot_writer
from simulator.obs import metrics


class IngestValidationError(ValueError):
    """Raised when an ingested file fails validation (fail-fast, exit 3)."""


def load_snapshot(path: str | Path) -> dict[str, object]:
    """Load + validate a pre-created topology snapshot file against the canonical schema."""
    p = Path(path)
    try:
        snapshot = json.loads(p.read_text())
    except (OSError, json.JSONDecodeError) as exc:
        metrics.INGEST_VALIDATION_ERRORS.inc()
        raise IngestValidationError(f"cannot read snapshot file {p}: {exc}") from exc
    try:
        snapshot_writer.validate_snapshot(snapshot)
    except snapshot_writer.SnapshotValidationError as exc:
        metrics.INGEST_VALIDATION_ERRORS.inc()
        raise IngestValidationError(f"ingested snapshot {p} failed validation: {exc}") from exc
    return snapshot


def load_corpus(path: str | Path) -> list[AlarmEvent]:
    """Load a corpus JSONL, validate every payload vs the frozen binding, return ordered events.

    Fails fast (before returning anything) on the first malformed line.
    """
    p = Path(path)
    try:
        lines = p.read_text().splitlines()
    except OSError as exc:
        metrics.INGEST_VALIDATION_ERRORS.inc()
        raise IngestValidationError(f"cannot read corpus file {p}: {exc}") from exc

    records: list[tuple[int, dict]] = []
    for line_no, raw in enumerate(lines, start=1):
        raw = raw.strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError as exc:
            metrics.INGEST_VALIDATION_ERRORS.inc()
            raise IngestValidationError(f"corpus {p}:{line_no}: bad JSON: {exc}") from exc
        if "corpusVersion" in obj:  # header line
            continue
        records.append((line_no, obj))

    records.sort(key=lambda r: r[1].get("seq", 0))
    events: list[AlarmEvent] = []
    for line_no, obj in records:
        envelope = obj.get("envelope")
        if not isinstance(envelope, dict) or "payload" not in envelope:
            metrics.INGEST_VALIDATION_ERRORS.inc()
            raise IngestValidationError(f"corpus {p}:{line_no}: missing envelope.payload")
        try:
            event = AlarmEvent.model_validate(envelope["payload"])
        except ValidationError as exc:
            metrics.INGEST_VALIDATION_ERRORS.inc()
            raise IngestValidationError(
                f"corpus {p}:{line_no}: payload failed AlarmEvent validation: {exc}"
            ) from exc
        events.append(event)
    return events
