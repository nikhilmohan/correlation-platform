"""Transaction emission (DA-13).

For each dense storm cluster the emitter builds ONE :class:`TransactionEvent`, populating BOTH
``alarmIds[]`` and the typed ``alarms[]`` directly from the in-hand enriched ``AlarmEvent``s —
each ``alarms[]`` entry carries the SIX required fields (``alarmId``, ``alarmType``,
``eventType``, ``raisedAt``, ``managedObjectId``, ``perceivedSeverity``) copied verbatim from
the source alarm. ``alarmType`` is a PASS-THROUGH MIRROR (never derived/inferred). The two
arrays are the same set in the same order (sorted by ``raisedAt`` then ``alarmId``). The event
is validated against the ``TransactionEvent`` schema before publish (an empty/mismatched array
or a missing per-alarm field is a code bug — EH-3 class — never published).
"""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

import numpy as np
from acp_event_model import Alarm, AlarmEvent, TransactionEvent, TypedEnvelope

from .cluster import NOISE_LABEL
from .logging_setup import get_logger

log = get_logger(__name__)


@dataclass
class EmitResult:
    """Outcome of emitting a window: the events produced + aggregate counts for run-stats."""

    events: list[TypedEnvelope]
    alarms_in: int
    clusters_formed: int
    alarms_kept: int
    alarms_dropped: int
    noise_alarms: list[AlarmEvent]
    max_cluster_size: int | None


def _sort_key(alarm: AlarmEvent) -> tuple[datetime, str]:
    return (alarm.raisedAt, alarm.alarmId)


class TransactionEmitter:
    """Groups storm-cluster rows into validated :class:`TransactionEvent`s."""

    SOURCE = "noise-filter"

    def __init__(self, metrics=None) -> None:
        self._metrics = metrics

    def build_events(
        self,
        alarms: list[AlarmEvent],
        labels: np.ndarray,
        *,
        trail_id: str,
        snapshot_id: str,
        window_start: datetime,
        window_end: datetime,
        domain: str | None = None,
        trace_id: str | None = None,
    ) -> EmitResult:
        """Build one TransactionEvent envelope per dense cluster; drop noise-labeled alarms."""
        clusters: dict[int, list[AlarmEvent]] = {}
        noise: list[AlarmEvent] = []
        for alarm, label in zip(alarms, labels, strict=True):
            label = int(label)
            if label == NOISE_LABEL:
                noise.append(alarm)
            else:
                clusters.setdefault(label, []).append(alarm)

        events: list[TypedEnvelope] = []
        alarms_kept = 0
        max_cluster_size: int | None = None
        rep_trace = trace_id or (alarms[0].alarmId if alarms else str(uuid.uuid4()))

        for label in sorted(clusters):
            members = sorted(clusters[label], key=_sort_key)
            if not members:
                continue
            alarm_ids = [a.alarmId for a in members]
            typed = [
                Alarm(
                    alarmId=a.alarmId,
                    alarmType=a.alarmType,  # pass-through mirror — never derived
                    eventType=a.eventType,
                    raisedAt=a.raisedAt,
                    managedObjectId=a.managedObjectId,
                    perceivedSeverity=a.perceivedSeverity,
                )
                for a in members
            ]
            payload = TransactionEvent(
                transactionId=str(uuid.uuid4()),
                trailId=trail_id,
                snapshotId=snapshot_id,
                domain=domain,
                alarmIds=alarm_ids,
                alarms=typed,
                windowStart=window_start,
                windowEnd=window_end,
            )
            envelope = TypedEnvelope[TransactionEvent](
                eventId=str(uuid.uuid4()),
                type="TransactionEvent",
                schemaVersion=1,
                occurredAt=datetime.now(UTC),
                source=self.SOURCE,
                traceId=rep_trace,
                payload=payload,
            )
            # Pre-publish self-validation (EH-3): re-validate the wire dict against the schema.
            self._assert_valid(envelope)
            events.append(envelope)
            alarms_kept += len(members)
            max_cluster_size = max(max_cluster_size or 0, len(members))

        if self._metrics is not None:
            self._metrics.clusters_emitted.inc(len(events))
            self._metrics.transactions_emitted.inc(len(events))
            self._metrics.noise_points_dropped.inc(len(noise))

        return EmitResult(
            events=events,
            alarms_in=len(alarms),
            clusters_formed=len(events),
            alarms_kept=alarms_kept,
            alarms_dropped=len(alarms) - alarms_kept,
            noise_alarms=noise,
            max_cluster_size=max_cluster_size,
        )

    @staticmethod
    def _assert_valid(envelope: TypedEnvelope) -> None:
        """Round-trip the envelope to the wire dict and re-validate the payload (EH-3 guard)."""
        wire = envelope.to_dict()
        payload = wire["payload"]
        if not payload.get("alarmIds") or not payload.get("alarms"):
            raise ValueError("TransactionEvent with empty alarmIds/alarms must never be published")
        if len(payload["alarmIds"]) != len(payload["alarms"]):
            raise ValueError("alarmIds and alarms must be the same length and order")
        # Re-validate the payload against the typed model (catches a missing per-alarm field).
        TransactionEvent.model_validate(payload)
