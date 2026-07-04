"""P3 synthesis typed models (spec Tasks 13-18).

Typed carriers for the collaborators' *read* payloads (parsed from their published OpenAPI
shapes) and the Simulator-owned P3 artifacts (config snapshot + cascade labels + run summary).
These are internal generation-side types — the only *wire* type is the frozen ``AlarmEvent`` on
``alarms.live``. Nothing here changes any contract.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class SequenceElement:
    """One ordered element of a pattern's sequence (from ``SequenceElementView``)."""

    alarm_type: str
    optional: bool = False


@dataclass(frozen=True)
class SessionWindow:
    """A pattern's derived session-window rule (from ``SessionWindowView``)."""

    window_ms: int
    type: str = "gap"


@dataclass(frozen=True)
class Timing:
    """A pattern's mined timing (from PatternView.timing JsonNode; all fields optional)."""

    timeframe_ms: float | None = None
    median_inter_arrival_ms: float | None = None
    max_inter_arrival_ms: float | None = None
    stddev_inter_arrival_ms: float | None = None

    @classmethod
    def from_json(cls, obj: dict[str, Any] | None) -> Timing:
        obj = obj or {}
        return cls(
            timeframe_ms=_opt_float(obj.get("timeframeMs")),
            median_inter_arrival_ms=_opt_float(obj.get("medianInterArrivalMs")),
            max_inter_arrival_ms=_opt_float(obj.get("maxInterArrivalMs")),
            stddev_inter_arrival_ms=_opt_float(obj.get("stddevInterArrivalMs")),
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "timeframeMs": self.timeframe_ms,
            "medianInterArrivalMs": self.median_inter_arrival_ms,
            "maxInterArrivalMs": self.max_inter_arrival_ms,
            "stddevInterArrivalMs": self.stddev_inter_arrival_ms,
        }


@dataclass(frozen=True)
class PatternView:
    """The subset of Pattern Manager's ``PatternView`` P3 synthesis consumes."""

    pattern_id: str
    trail_id: str
    sequence: tuple[SequenceElement, ...]
    root_cause_alarm_type: str
    timing: Timing = field(default_factory=Timing)
    session_window: SessionWindow | None = None

    @classmethod
    def from_api(cls, obj: dict[str, Any]) -> PatternView:
        seq = tuple(
            SequenceElement(alarm_type=str(e["alarmType"]), optional=bool(e.get("optional", False)))
            for e in (obj.get("sequence") or [])
        )
        sw_obj = obj.get("sessionWindow")
        session_window = (
            SessionWindow(window_ms=int(sw_obj["windowMs"]), type=str(sw_obj.get("type", "gap")))
            if sw_obj and sw_obj.get("windowMs") is not None
            else None
        )
        return cls(
            pattern_id=str(obj["patternId"]),
            trail_id=str(obj["trailId"]),
            sequence=seq,
            root_cause_alarm_type=str(obj["rootCauseAlarmType"]),
            timing=Timing.from_json(obj.get("timing")),
            session_window=session_window,
        )

    def to_json(self) -> dict[str, Any]:
        return {
            "patternId": self.pattern_id,
            "trailId": self.trail_id,
            "sequence": [
                {"alarmType": e.alarm_type, "optional": e.optional} for e in self.sequence
            ],
            "rootCauseAlarmType": self.root_cause_alarm_type,
            "timing": self.timing.to_json(),
            "sessionWindow": (
                None
                if self.session_window is None
                else {"windowMs": self.session_window.window_ms, "type": self.session_window.type}
            ),
        }

    @classmethod
    def from_json(cls, obj: dict[str, Any]) -> PatternView:
        # from_api handles both the persisted and the live shapes (same field names).
        return cls.from_api(obj)


@dataclass(frozen=True)
class TrailMember:
    """One trail member (from Trail Builder ``TrailMember``)."""

    managed_object_id: str
    object_type: str

    def to_json(self) -> dict[str, str]:
        return {"managedObjectId": self.managed_object_id, "objectType": self.object_type}

    @classmethod
    def from_json(cls, obj: dict[str, Any]) -> TrailMember:
        return cls(
            managed_object_id=str(obj["managedObjectId"]),
            object_type=str(obj["objectType"]),
        )


@dataclass(frozen=True)
class TrailDetail:
    """A trail's members + provenance (from Trail Builder ``GET /trails/{trailId}``)."""

    trail_id: str
    members: tuple[TrailMember, ...]
    snapshot_id: str | None = None
    igp_area: str | None = None

    @classmethod
    def from_api(cls, obj: dict[str, Any]) -> TrailDetail:
        members = tuple(
            TrailMember(
                managed_object_id=str(m["managedObjectId"]),
                object_type=str(m["objectType"]),
            )
            for m in (obj.get("members") or [])
        )
        return cls(
            trail_id=str(obj["trailId"]),
            members=members,
            snapshot_id=(str(obj["snapshotId"]) if obj.get("snapshotId") else None),
            igp_area=(str(obj["igpArea"]) if obj.get("igpArea") else None),
        )


@dataclass(frozen=True)
class SnapshotSummary:
    """A deployed topology snapshot (from Topology ``SnapshotSummaryDto``)."""

    snapshot_id: str
    domain: str = ""
    node_count: int = 0
    edge_count: int = 0

    def to_json(self) -> dict[str, Any]:
        return {
            "snapshotId": self.snapshot_id,
            "domain": self.domain,
            "nodeCount": self.node_count,
            "edgeCount": self.edge_count,
        }

    @classmethod
    def from_json(cls, obj: dict[str, Any]) -> SnapshotSummary:
        return cls(
            snapshot_id=str(obj["snapshotId"]),
            domain=str(obj.get("domain", "")),
            node_count=int(obj.get("nodeCount", 0)),
            edge_count=int(obj.get("edgeCount", 0)),
        )


@dataclass
class P3CascadeLabel:
    """Per-cascade ground-truth label (spec Task 18, AC 43)."""

    pattern_id: str
    trail_id: str
    root_cause_alarm_id: str
    root_cause_alarm_type: str
    child_alarm_ids: list[str] = field(default_factory=list)
    scenario_type: str = "pattern-aligned"

    def to_json(self) -> dict[str, Any]:
        return {
            "patternId": self.pattern_id,
            "trailId": self.trail_id,
            "rootCauseAlarmId": self.root_cause_alarm_id,
            "rootCauseAlarmType": self.root_cause_alarm_type,
            "childAlarmIds": list(self.child_alarm_ids),
            "scenarioType": self.scenario_type,
        }


@dataclass
class P3RunSummary:
    """Per-run summary (spec Task 18, AC 43) — the 60-70% KPI is directly computable from it."""

    total_alarms: int
    aligned_alarms: int
    non_aligned_alarms: int
    aligned_fraction: float

    def to_json(self) -> dict[str, Any]:
        return {
            "totalAlarms": self.total_alarms,
            "alignedAlarms": self.aligned_alarms,
            "nonAlignedAlarms": self.non_aligned_alarms,
            "alignedFraction": self.aligned_fraction,
        }


def _opt_float(value: Any) -> float | None:
    if value is None:
        return None
    return float(value)
