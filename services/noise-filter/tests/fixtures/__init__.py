"""Synthetic AlarmEvent fixtures + mock collaborator builders for the Noise Filter tests.

No Simulator dependency: these are hand-built ``acp_event_model`` fixtures (a fiber-cut cascade,
storm bursts, two-fault windows, injected chatty alarms) plus respx/stub collaborators generated
from the collaborators' published OpenAPI shapes.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta

from acp_event_model import AlarmEvent, TypedEnvelope

BASE_TIME = datetime(2026, 6, 1, 12, 0, 0, tzinfo=UTC)


def make_alarm(
    *,
    alarm_id: str | None = None,
    managed_object_id: str = "Port:p1",
    event_type: str = "communicationsAlarm",
    alarm_type: str = "PortDown",
    perceived_severity: str = "major",
    raised_offset_seconds: float = 0.0,
    trail_ids: list[str] | None = None,
    probable_cause: str = "lossOfSignal",
) -> AlarmEvent:
    """Build one enriched AlarmEvent at ``BASE_TIME + offset`` on ``trail_ids`` (default ['t1'])."""
    return AlarmEvent(
        alarmId=alarm_id or f"a-{uuid.uuid4().hex[:8]}",
        managedObjectId=managed_object_id,
        eventType=event_type,
        probableCause=probable_cause,
        alarmType=alarm_type,
        perceivedSeverity=perceived_severity,
        raisedAt=BASE_TIME + timedelta(seconds=raised_offset_seconds),
        state="raised",
        trailIds=trail_ids or ["t1"],
    )


def envelope_for(alarm: AlarmEvent, *, event_id: str | None = None) -> TypedEnvelope:
    """Wrap an AlarmEvent in a canonical envelope (for ingest/dedupe tests)."""
    return TypedEnvelope[AlarmEvent](
        eventId=event_id or str(uuid.uuid4()),
        type="AlarmEvent",
        schemaVersion=1,
        occurredAt=alarm.raisedAt,
        source="enrichment",
        traceId="trace-1",
        payload=alarm,
    )


def fiber_cut_cascade(trail_id: str = "t1") -> list[AlarmEvent]:
    """A tight fiber-cut cascade: LOS -> LinkDown -> AdjDown -> LSPDown within a few seconds."""
    return [
        make_alarm(
            alarm_id="los-1",
            managed_object_id="FiberSpan:f1",
            alarm_type="FiberFault",
            event_type="communicationsAlarm",
            perceived_severity="critical",
            raised_offset_seconds=0.0,
            trail_ids=[trail_id],
        ),
        make_alarm(
            alarm_id="link-1",
            managed_object_id="IPLink:l1",
            alarm_type="LinkDown",
            event_type="communicationsAlarm",
            perceived_severity="major",
            raised_offset_seconds=0.5,
            trail_ids=[trail_id],
        ),
        make_alarm(
            alarm_id="adj-1",
            managed_object_id="IGPAdjacency:adj1",
            alarm_type="AdjDown",
            event_type="communicationsAlarm",
            perceived_severity="major",
            raised_offset_seconds=1.0,
            trail_ids=[trail_id],
        ),
        make_alarm(
            alarm_id="lsp-1",
            managed_object_id="LSP:lsp1",
            alarm_type="LSPDown",
            event_type="communicationsAlarm",
            perceived_severity="minor",
            raised_offset_seconds=1.5,
            trail_ids=[trail_id],
        ),
    ]


def chatty_alarm(trail_id: str = "t1") -> AlarmEvent:
    """A coincidental, temporally-distant outlier on the same trail (far from the cascade)."""
    return make_alarm(
        alarm_id="chatter-1",
        managed_object_id="Port:noisyport",
        alarm_type="QualityOfServiceAlarm",
        event_type="qualityOfServiceAlarm",
        perceived_severity="warning",
        raised_offset_seconds=95.0,  # far outside the cascade's tight density
        trail_ids=[trail_id],
    )


def storm(
    n: int, *, trail_id: str = "t1", start: float = 0.0, spread: float = 5.0
) -> list[AlarmEvent]:
    """A dense storm of ``n`` alarms from one fault: tightly packed in time on one trail."""
    out = []
    for i in range(n):
        out.append(
            make_alarm(
                alarm_id=f"storm-{trail_id}-{i}",
                managed_object_id=f"Port:p{i}",
                alarm_type="PortDown",
                event_type="communicationsAlarm",
                perceived_severity="major",
                raised_offset_seconds=start + (spread * i / max(n - 1, 1)),
                trail_ids=[trail_id],
            )
        )
    return out
