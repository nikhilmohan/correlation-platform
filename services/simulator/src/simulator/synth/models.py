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
class SampleAlarm:
    """One representative real member alarm a pattern was mined from (from ``SampleAlarmView``).

    Pattern Manager's published ``PatternView`` carries ``sampleAlarms[]`` — a bounded sample of the
    real alarms the pattern was mined from — each with a ``managedObjectId`` (``<objectType>:<id>``)
    and its ``alarmType``. These are the *authoritative* objects the pattern actually touched, and
    the Correlation Engine derives a pattern's required objectTypes from the ``managedObjectId``
    prefixes here — so P3 discovery uses the same source (not a theoretical affinity table).
    """

    managed_object_id: str
    alarm_type: str

    @property
    def object_type(self) -> str:
        """The objectType prefix of ``managedObjectId`` (``<objectType>:<id>``), as CE reads it."""
        return self.managed_object_id.split(":", 1)[0]

    @classmethod
    def from_api(cls, obj: dict[str, Any]) -> SampleAlarm:
        return cls(
            managed_object_id=str(obj["managedObjectId"]),
            alarm_type=str(obj["alarmType"]),
        )

    def to_json(self) -> dict[str, str]:
        return {"managedObjectId": self.managed_object_id, "alarmType": self.alarm_type}


@dataclass(frozen=True)
class PatternView:
    """The subset of Pattern Manager's ``PatternView`` P3 synthesis consumes."""

    pattern_id: str
    trail_id: str
    sequence: tuple[SequenceElement, ...]
    root_cause_alarm_type: str
    timing: Timing = field(default_factory=Timing)
    session_window: SessionWindow | None = None
    sample_alarms: tuple[SampleAlarm, ...] = ()

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
        sample_alarms = tuple(
            SampleAlarm.from_api(s)
            for s in (obj.get("sampleAlarms") or [])
            if s.get("managedObjectId")
        )
        return cls(
            pattern_id=str(obj["patternId"]),
            trail_id=str(obj["trailId"]),
            sequence=seq,
            root_cause_alarm_type=str(obj["rootCauseAlarmType"]),
            timing=Timing.from_json(obj.get("timing")),
            session_window=session_window,
            sample_alarms=sample_alarms,
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
            "sampleAlarms": [s.to_json() for s in self.sample_alarms],
        }

    @classmethod
    def from_json(cls, obj: dict[str, Any]) -> PatternView:
        # from_api handles both the persisted and the live shapes (same field names).
        return cls.from_api(obj)


@dataclass(frozen=True)
class TrailSummary:
    """One trail-list item (from Trail Builder ``GET /trails`` -> ``TrailSummary``).

    The paged list endpoint returns lightweight summaries (no ``members``); the members are
    fetched lazily per candidate via ``get_trail`` during compatible-trail discovery.
    """

    trail_id: str
    domain: str = ""
    member_count: int = 0
    igp_area: str | None = None
    srlg_group: str | None = None

    @classmethod
    def from_api(cls, obj: dict[str, Any]) -> TrailSummary:
        return cls(
            trail_id=str(obj["trailId"]),
            domain=str(obj.get("domain", "")),
            member_count=int(obj.get("memberCount", 0)),
            igp_area=(str(obj["igpArea"]) if obj.get("igpArea") else None),
            srlg_group=(str(obj["srlgGroup"]) if obj.get("srlgGroup") else None),
        )


@dataclass(frozen=True)
class CompatibleTrail:
    """A discovered trail that hosts a pattern (Task 21 / AC 47-49). Cached in config snapshot."""

    trail_id: str
    igp_area: str | None = None

    def to_json(self) -> dict[str, Any]:
        return {"trailId": self.trail_id, "igpArea": self.igp_area}

    @classmethod
    def from_json(cls, obj: dict[str, Any]) -> CompatibleTrail:
        return cls(
            trail_id=str(obj["trailId"]),
            igp_area=(str(obj["igpArea"]) if obj.get("igpArea") else None),
        )


@dataclass(frozen=True)
class CompatibleTrailSet:
    """A pattern's discovered compatible-trail set + its discovery igp-area (Task 21 / AC 48)."""

    pattern_id: str
    discovery_area: str | None
    trails: tuple[CompatibleTrail, ...] = ()

    def to_json(self) -> dict[str, Any]:
        return {
            "discoveryArea": self.discovery_area,
            "trails": [t.to_json() for t in self.trails],
        }

    @classmethod
    def from_json(cls, pattern_id: str, obj: dict[str, Any]) -> CompatibleTrailSet:
        return cls(
            pattern_id=pattern_id,
            discovery_area=(str(obj["discoveryArea"]) if obj.get("discoveryArea") else None),
            trails=tuple(CompatibleTrail.from_json(t) for t in (obj.get("trails") or [])),
        )


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
    """Per-cascade ground-truth label (spec Task 18, AC 43; network-wide adds AC 54).

    Network-wide P3 additionally records ``instance_index`` (>=2 for staggered trail-repeats, AC 56)
    and ``igp_area`` per cascade so incidents can be shown to span multiple distinct trails/areas
    (AC 53, 54). Both are additive JSON keys; single-trail P3 keeps ``instance_index=1`` and a
    ``None`` area, so existing consumers are unaffected.
    """

    pattern_id: str
    trail_id: str
    root_cause_alarm_id: str
    root_cause_alarm_type: str
    child_alarm_ids: list[str] = field(default_factory=list)
    scenario_type: str = "pattern-aligned"
    instance_index: int = 1
    igp_area: str | None = None

    def to_json(self) -> dict[str, Any]:
        return {
            "patternId": self.pattern_id,
            "trailId": self.trail_id,
            "rootCauseAlarmId": self.root_cause_alarm_id,
            "rootCauseAlarmType": self.root_cause_alarm_type,
            "childAlarmIds": list(self.child_alarm_ids),
            "scenarioType": self.scenario_type,
            "instanceIndex": self.instance_index,
            "igpArea": self.igp_area,
        }


@dataclass
class P3RunSummary:
    """Per-run summary (spec Task 18, AC 43; network-wide adds AC 51/53/55/65).

    Network-wide P3 adds the spread + enrichment fields (all additive JSON keys, defaulted so a
    single-trail summary is unaffected): ``distinct_trails_used``/``distinct_areas_used`` (AC 53),
    ``shortfall_cascades`` (AC 55), ``enrichment_safe_count`` (the expected-correlatable count for
    the CE cross-check, AC 51/65), ``enrichment_conflict_patterns`` (AC 62/65), and
    ``aligned_fraction_emitted`` (the over-provisioned emitted fraction, AC 51).
    """

    total_alarms: int
    aligned_alarms: int
    non_aligned_alarms: int
    aligned_fraction: float
    distinct_trails_used: int = 0
    distinct_areas_used: int = 0
    shortfall_cascades: int = 0
    enrichment_safe_count: int = 0
    enrichment_conflict_patterns: list[str] = field(default_factory=list)
    aligned_fraction_emitted: float = 0.0
    # The EXPECTED CE-correlated alarm count the closed-loop controller sized the plan to produce
    # (== enrichment_safe_count for network-wide runs). Recorded alongside the emitted aligned count
    # so the target basis (correlated yield, not emitted length) is transparent (AC 51).
    expected_correlated_alarms: int = 0

    def to_json(self) -> dict[str, Any]:
        return {
            "totalAlarms": self.total_alarms,
            "alignedAlarms": self.aligned_alarms,
            "nonAlignedAlarms": self.non_aligned_alarms,
            "alignedFraction": self.aligned_fraction,
            "distinctTrailsUsed": self.distinct_trails_used,
            "distinctAreasUsed": self.distinct_areas_used,
            "shortfallCascades": self.shortfall_cascades,
            "enrichmentSafeCount": self.enrichment_safe_count,
            "enrichmentConflictPatterns": list(self.enrichment_conflict_patterns),
            "alignedFractionEmitted": self.aligned_fraction_emitted,
            "expectedCorrelatedAlarms": self.expected_correlated_alarms,
        }


def _opt_float(value: Any) -> float | None:
    if value is None:
        return None
    return float(value)
