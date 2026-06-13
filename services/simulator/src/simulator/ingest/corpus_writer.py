"""Export corpus writer — taps the emit point to write the wire stream (criteria 11, 40).

When ``EXPORT_CORPUS_FILE`` is set in a *generate* run, the writer is registered as the replay
emit tap: one JSONL line per emitted ``TypedEnvelope[AlarmEvent]`` in emit order with the target
topic. Reuses ``acp_event_model`` serialization (no new serialization), so the corpus is exactly
what went on the wire — re-ingest reproduces it identically. The format is the Simulator-owned,
versioned corpus contract (header line + record lines).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from acp_event_model import TypedEnvelope

CORPUS_VERSION = 1


class CorpusWriter:
    """Append-only writer for the export corpus file (header + seq-numbered records)."""

    def __init__(self, path: Path, source_run_id: str, phase: str, topic: str) -> None:
        self._path = Path(path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._fh = self._path.open("w")
        self._seq = 0
        self._records: list[dict[str, Any]] = []
        self._header = {
            "corpusVersion": CORPUS_VERSION,
            "sourceRunId": source_run_id,
            "phase": phase,
            "topic": topic,
        }
        # header written on close (once count is known)

    def tap(self, topic: str, envelope: TypedEnvelope[Any]) -> None:
        """Record one emitted envelope (the replay emit tap)."""
        record = {"seq": self._seq, "topic": topic, "envelope": envelope.to_dict()}
        self._records.append(record)
        self._seq += 1

    @property
    def count(self) -> int:
        return self._seq

    def close(self) -> None:
        """Flush the header + all records to disk and close the file."""
        header = dict(self._header)
        header["count"] = self._seq
        self._fh.write(json.dumps(header) + "\n")
        for record in self._records:
            self._fh.write(json.dumps(record) + "\n")
        self._fh.close()

    def __enter__(self) -> CorpusWriter:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
