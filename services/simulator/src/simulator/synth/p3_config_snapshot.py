"""P3 config snapshot — the reusable persisted (topology + trails + patterns) artifact.

Spec Task 14 / OQ-P3-2. A single versioned JSON file under ``SIM_OUTPUT_DIR`` (default
``p3-config-snapshot.json``, overridable via ``P3_CONFIG_SNAPSHOT_PATH``) holding everything a
standalone P3 run needs, so repeated randomized runs never re-fetch and a captured config drives
runs even when the live services are down or have moved on (AC 34, 42). Not a Kafka artifact, not
shared with other services. Load fails fast on an unknown major ``schemaVersion`` (stale/corrupt).
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from simulator.synth.models import (
    CompatibleTrailSet,
    PatternView,
    SnapshotSummary,
    TrailDetail,
    TrailMember,
)

# schemaVersion 2 adds the additive ``compatibleTrails`` block (Task 21 / AC 48). v1 files load
# unchanged (no cached compatible trails -> discovery runs on the next network-wide run).
SCHEMA_VERSION = 2
SUPPORTED_MAJOR = {1, 2}


class P3ConfigSnapshotError(ValueError):
    """Raised when a persisted P3 config snapshot is stale, corrupt, or inconsistent."""


@dataclass
class P3ConfigSnapshot:
    """The captured deployed state a P3 synthesis run reads from."""

    domain: str
    patterns: list[PatternView] = field(default_factory=list)
    trails: dict[str, TrailDetail] = field(default_factory=dict)
    source_snapshots: list[SnapshotSummary] = field(default_factory=list)
    captured_at: str = ""
    schema_version: int = SCHEMA_VERSION
    # Network-wide (schemaVersion 2, additive): cached compatible-trail sets keyed by patternId so a
    # second run makes zero GET /trails calls (AC 48). Absent (empty) -> discovery runs on the next
    # network-wide run (v1 load compatibility).
    compatible_trails: dict[str, CompatibleTrailSet] = field(default_factory=dict)

    def has_compatible_trails(self) -> bool:
        """True when this snapshot already carries a cached compatible-trail set (AC 48)."""
        return bool(self.compatible_trails)

    def moid_universe(self) -> set[str]:
        """Every valid ``managedObjectId`` a P3 alarm may reference (all trail members)."""
        return {m.managed_object_id for trail in self.trails.values() for m in trail.members}

    def to_json(self) -> dict[str, object]:
        return {
            "schemaVersion": self.schema_version,
            "capturedAt": self.captured_at or datetime.now(tz=UTC).isoformat(),
            "domain": self.domain,
            "sourceSnapshots": [s.to_json() for s in self.source_snapshots],
            "trails": {
                trail_id: {
                    "snapshotId": trail.snapshot_id,
                    "igpArea": trail.igp_area,
                    "members": [m.to_json() for m in trail.members],
                }
                for trail_id, trail in self.trails.items()
            },
            "patterns": [p.to_json() for p in self.patterns],
            "compatibleTrails": {
                pattern_id: cts.to_json() for pattern_id, cts in self.compatible_trails.items()
            },
        }


def save(snapshot: P3ConfigSnapshot, path: Path) -> None:
    """Write the versioned P3 config snapshot JSON (creates parent dirs)."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(snapshot.to_json(), indent=2))


def load(path: Path) -> P3ConfigSnapshot:
    """Load + validate a persisted P3 config snapshot (fail-fast on stale/corrupt/inconsistent)."""
    try:
        obj = json.loads(Path(path).read_text())
    except (OSError, json.JSONDecodeError) as exc:
        raise P3ConfigSnapshotError(f"cannot read P3 config snapshot {path}: {exc}") from exc

    version = obj.get("schemaVersion")
    if version not in SUPPORTED_MAJOR:
        raise P3ConfigSnapshotError(
            f"P3 config snapshot {path} schemaVersion={version!r} unsupported "
            f"(supported majors: {sorted(SUPPORTED_MAJOR)})"
        )

    trails: dict[str, TrailDetail] = {}
    for trail_id, tobj in (obj.get("trails") or {}).items():
        members = tuple(TrailMember.from_json(m) for m in (tobj.get("members") or []))
        trails[str(trail_id)] = TrailDetail(
            trail_id=str(trail_id),
            members=members,
            snapshot_id=(str(tobj["snapshotId"]) if tobj.get("snapshotId") else None),
            igp_area=(str(tobj["igpArea"]) if tobj.get("igpArea") else None),
        )

    patterns = [PatternView.from_json(p) for p in (obj.get("patterns") or [])]
    # Consistency: every pattern's trailId must resolve in trails.
    for p in patterns:
        if p.trail_id not in trails:
            raise P3ConfigSnapshotError(
                f"P3 config snapshot {path} pattern {p.pattern_id!r} references "
                f"unresolved trailId {p.trail_id!r}"
            )

    compatible_trails: dict[str, CompatibleTrailSet] = {}
    for pattern_id, cobj in (obj.get("compatibleTrails") or {}).items():
        compatible_trails[str(pattern_id)] = CompatibleTrailSet.from_json(str(pattern_id), cobj)

    return P3ConfigSnapshot(
        domain=str(obj.get("domain", "")),
        patterns=patterns,
        trails=trails,
        source_snapshots=[SnapshotSummary.from_json(s) for s in (obj.get("sourceSnapshots") or [])],
        captured_at=str(obj.get("capturedAt", "")),
        schema_version=int(version),
        compatible_trails=compatible_trails,
    )
