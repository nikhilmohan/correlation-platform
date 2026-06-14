"""Ground-truth label store + JSONL export/import (criteria 10, 10a, 29, 39).

One record per injected scenario in the frozen retrieval shape
``{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType,
children[]}``. ``rootCauseAlarmType`` is the root alarm's canonical ``alarmType`` token so the
RCA oracle compares on the canonical join-token space. The same JSONL file is exported by a
generate run and re-loaded by an ingest run.
"""

from __future__ import annotations

import json
from pathlib import Path

from simulator.engine.models import GroundTruthLabel


class LabelStore:
    """In-process ground-truth index, exportable to / importable from JSONL."""

    def __init__(self) -> None:
        self._by_id: dict[str, GroundTruthLabel] = {}
        self._order: list[str] = []

    def record(self, label: GroundTruthLabel) -> None:
        if label.scenario_id not in self._by_id:
            self._order.append(label.scenario_id)
        self._by_id[label.scenario_id] = label

    def all(self) -> list[GroundTruthLabel]:
        return [self._by_id[sid] for sid in self._order]

    def get(self, scenario_id: str) -> GroundTruthLabel | None:
        return self._by_id.get(scenario_id)

    def distinct_scenario_types(self) -> set[str]:
        return {label.scenario_type for label in self._by_id.values()}

    def to_dicts(self) -> list[dict[str, object]]:
        return [label_to_dict(label) for label in self.all()]

    def export_to_file(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w") as fh:
            for label in self.all():
                fh.write(json.dumps(label_to_dict(label)) + "\n")

    def load_from_file(self, path: Path) -> None:
        for line_no, raw in enumerate(Path(path).read_text().splitlines(), start=1):
            raw = raw.strip()
            if not raw:
                continue
            try:
                obj = json.loads(raw)
                label = label_from_dict(obj)
            except (json.JSONDecodeError, KeyError, TypeError) as exc:
                raise ValueError(f"malformed labels file {path}:{line_no}: {exc}") from exc
            self.record(label)


def label_to_dict(label: GroundTruthLabel) -> dict[str, object]:
    """Render a label in the frozen retrieval/JSONL shape."""
    return {
        "scenarioId": label.scenario_id,
        "scenarioType": label.scenario_type,
        "rootCause": label.root_cause,
        "rootCauseManagedObjectId": label.root_cause_managed_object_id,
        "rootCauseAlarmType": label.root_cause_alarm_type,
        "children": list(label.children),
    }


def label_from_dict(obj: dict[str, object]) -> GroundTruthLabel:
    """Parse a label from the frozen JSONL shape (used by ingest)."""
    return GroundTruthLabel(
        scenario_id=str(obj["scenarioId"]),
        scenario_type=str(obj["scenarioType"]),
        root_cause=str(obj["rootCause"]),
        root_cause_managed_object_id=str(obj["rootCauseManagedObjectId"]),
        root_cause_alarm_type=str(obj["rootCauseAlarmType"]),
        children=[str(c) for c in obj.get("children", [])],  # type: ignore[union-attr]
    )
