"""ProgressSink — live per-run emission counters for ``GET /synth/status`` (spec Task 27, AC 69).

The P3 emit loop increments this sink once per produced alarm; the HTTP status handler reads a
consistent snapshot of the four counters lock-free-enough (a tiny lock guards the cheap int
writes, reads copy four ints — no contention with the ~ms-paced emit loop). When the run pipeline
is invoked without a sink (the CLI one-shot path) the increments are simply never called, so CLI
behaviour is byte-for-byte unchanged.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass


@dataclass(frozen=True)
class ProgressSnapshot:
    """An immutable read of the four progress counters (the ``progress`` status object)."""

    alarmsEmitted: int
    alarmsTotal: int
    alignedEmitted: int
    nonAlignedEmitted: int

    def to_json(self) -> dict[str, int]:
        return {
            "alarmsEmitted": self.alarmsEmitted,
            "alarmsTotal": self.alarmsTotal,
            "alignedEmitted": self.alignedEmitted,
            "nonAlignedEmitted": self.nonAlignedEmitted,
        }


class ProgressSink:
    """Thread-safe counters updated by the emit loop and read by the status handler."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._emitted = 0
        self._total = 0
        self._aligned = 0
        self._non_aligned = 0

    def set_total(self, total: int) -> None:
        """Record the effective (post-override) planned alarm total for this run."""
        with self._lock:
            self._total = int(total)

    def inc_emitted(self, *, aligned: bool) -> None:
        """Count one produced alarm; ``aligned`` splits it into the aligned/non-aligned tallies."""
        with self._lock:
            self._emitted += 1
            if aligned:
                self._aligned += 1
            else:
                self._non_aligned += 1

    def snapshot(self) -> ProgressSnapshot:
        """Return a consistent copy of the four counters."""
        with self._lock:
            return ProgressSnapshot(
                alarmsEmitted=self._emitted,
                alarmsTotal=self._total,
                alignedEmitted=self._aligned,
                nonAlignedEmitted=self._non_aligned,
            )
