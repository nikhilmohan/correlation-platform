"""P3 ground-truth label store (spec Task 18, AC 43).

Holds the per-cascade :class:`P3CascadeLabel` records + a per-run :class:`P3RunSummary`, both
persisted as JSONL/JSON under ``SIM_OUTPUT_DIR`` and served via the existing ``/labels`` surface
(the summary via ``GET /labels/p3-summary``). ``alignedFraction == alignedAlarms / totalAlarms``
computed from the per-cascade records, so the 60-70% KPI is directly computable.
"""

from __future__ import annotations

import json
from pathlib import Path

from simulator.synth.models import P3CascadeLabel, P3RunSummary


class P3LabelStore:
    """In-process P3 label index (cascade labels + run summary), exportable to JSONL/JSON."""

    def __init__(self) -> None:
        self._labels: list[P3CascadeLabel] = []
        self._summary: P3RunSummary | None = None

    def record(self, label: P3CascadeLabel) -> None:
        self._labels.append(label)

    def record_all(self, labels: list[P3CascadeLabel]) -> None:
        self._labels.extend(labels)

    def all(self) -> list[P3CascadeLabel]:
        return list(self._labels)

    def to_dicts(self) -> list[dict[str, object]]:
        return [label.to_json() for label in self._labels]

    def set_summary(self, summary: P3RunSummary) -> None:
        self._summary = summary

    @property
    def summary(self) -> P3RunSummary | None:
        return self._summary

    def compute_summary(self) -> P3RunSummary:
        """Compute the run summary from the recorded per-cascade labels (AC 43).

        ``pattern-aligned`` labels contribute their root + children. Non-aligned labels are either
        a partial cascade (children only) or a single alarm whose id is on ``rootCauseAlarmId``;
        :func:`_label_alarm_count` counts both cases uniformly.
        """
        aligned = sum(
            _label_alarm_count(label)
            for label in self._labels
            if label.scenario_type == "pattern-aligned"
        )
        non_aligned = sum(
            _label_alarm_count(label)
            for label in self._labels
            if label.scenario_type != "pattern-aligned"
        )
        total = aligned + non_aligned
        fraction = (aligned / total) if total else 0.0
        summary = P3RunSummary(
            total_alarms=total,
            aligned_alarms=aligned,
            non_aligned_alarms=non_aligned,
            aligned_fraction=fraction,
        )
        self._summary = summary
        return summary

    def export_labels(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w") as fh:
            for label in self._labels:
                fh.write(json.dumps(label.to_json()) + "\n")

    def export_summary(self, path: Path) -> None:
        if self._summary is None:
            self.compute_summary()
        assert self._summary is not None
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self._summary.to_json(), indent=2))


def _label_alarm_count(label: P3CascadeLabel) -> int:
    """Number of emitted alarms a label accounts for."""
    n = len(label.child_alarm_ids)
    if label.root_cause_alarm_id:
        n += 1
    return n
