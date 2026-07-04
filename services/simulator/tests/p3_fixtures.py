"""Shared P3 synthesis test fixtures — mock API bodies mirroring the collaborators' OpenAPI.

The mock bodies below are the shapes the Simulator consumes from the collaborators' *published*
OpenAPI (Pattern Manager PatternView, Trail Builder TrailDetail, Topology SnapshotSummaryDto) — not
their source. Kept in one place so every P3 test drives the same realistic shapes.
"""

from __future__ import annotations

from typing import Any


def pattern_view(
    pattern_id: str,
    trail_id: str,
    sequence: list[tuple[str, bool]],
    root_cause: str,
    *,
    median_ms: float = 500.0,
    max_ms: float = 1500.0,
    stddev_ms: float = 250.0,
    window_ms: int = 6000,
    sample_alarms: list[tuple[str, str]] | None = None,
) -> dict[str, Any]:
    """A PatternView body (the subset P3 reads) mirroring Pattern Manager's published shape.

    ``sample_alarms`` is a list of ``(managedObjectId, alarmType)`` mirroring the published
    ``sampleAlarms[]`` field. When omitted it defaults to one sampleAlarm per sequence element,
    placing each alarmType on a ``<AffineObjectType>:<id>`` managedObjectId consistent with the
    canonical trail fixtures — so discovery derives required objectTypes from real sample prefixes
    exactly like the Correlation Engine does.
    """
    if sample_alarms is None:
        sample_alarms = [
            (f"{_default_object_type(at)}:{pattern_id}-{i}", at)
            for i, (at, _opt) in enumerate(sequence)
        ]
    return {
        "patternId": pattern_id,
        "trailId": trail_id,
        "sequence": [{"alarmType": at, "optional": opt} for at, opt in sequence],
        "rootCauseAlarmType": root_cause,
        "timing": {
            "timeframeMs": 4000,
            "medianInterArrivalMs": median_ms,
            "maxInterArrivalMs": max_ms,
            "stddevInterArrivalMs": stddev_ms,
        },
        "sessionWindow": {"windowMs": window_ms, "type": "gap"},
        "sampleAlarms": [{"managedObjectId": moid, "alarmType": at} for moid, at in sample_alarms],
    }


# alarmType -> objectType used to synthesize default sampleAlarm managedObjectIds in fixtures. Kept
# aligned with the canonical trail members so default fixtures are self-consistently hostable.
_DEFAULT_SAMPLE_OBJECT_TYPE = {
    "IPLinkDown": "IPLink",
    "LinkDown": "IPLink",
    "ISISAdjacencyDown": "IGPAdjacency",
    "AdjDown": "IGPAdjacency",
    "OSPFAdjacencyDown": "IGPAdjacency",
    "LSPDown": "LSP",
    "InterfaceDown": "Interface",
    "InterfaceErrors": "Interface",
    "CRCErrors": "Port",
    "PortDown": "Port",
    "LOS": "FiberSpan",
    "QueueDrop": "Interface",
}


def _default_object_type(alarm_type: str) -> str:
    return _DEFAULT_SAMPLE_OBJECT_TYPE.get(alarm_type, "IPLink")


def pattern_page(items: list[dict[str, Any]]) -> dict[str, Any]:
    """The frozen PatternPage envelope Pattern Manager's GET /patterns returns."""
    return {"items": items, "total": len(items), "limit": 200, "offset": 0}


def trail_detail(
    trail_id: str,
    members: list[tuple[str, str]],
    *,
    snapshot_id: str = "snap-000123",
    igp_area: str | None = "area-0",
) -> dict[str, Any]:
    """A TrailDetail body mirroring Trail Builder's published shape."""
    return {
        "trailId": trail_id,
        "domain": "core-ip",
        "snapshotId": snapshot_id,
        "igpArea": igp_area,
        "srlgGroup": None,
        "memberCount": len(members),
        "members": [{"managedObjectId": moid, "objectType": ot} for moid, ot in members],
    }


def snapshot_summary(snapshot_id: str = "snap-000123") -> dict[str, Any]:
    """A SnapshotSummaryDto body mirroring Topology's published shape."""
    return {
        "snapshotId": snapshot_id,
        "domain": "core-ip",
        "changeType": "full",
        "status": "lifted",
        "nodeCount": 312,
        "edgeCount": 540,
        "ingestedAt": "2026-06-29T12:00:00Z",
    }


# A canonical two-pattern / two-trail fixture used across the P3 tests.
TRAIL_A_MEMBERS = [
    ("IPLink:ip-7", "IPLink"),
    ("IGPAdjacency:adj-3", "IGPAdjacency"),
    ("LSP:lsp-9", "LSP"),
]
TRAIL_B_MEMBERS = [
    ("FiberSpan:fib-1", "FiberSpan"),
    ("IPLink:ip-2", "IPLink"),
    ("VPNService:vpn-5", "VPNService"),
]
