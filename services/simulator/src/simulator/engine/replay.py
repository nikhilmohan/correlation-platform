"""Replay strategies — Batch (history) and Live (wall-clock paced) (criteria 8, 9, 24, 37).

``BatchReplay`` fire-and-flushes the whole ordered stream onto ``alarms.history`` (P2); the
timestamps already fall inside the configured history window. ``LiveReplay`` emits onto
``alarms.live`` (P3) with wall-clock pacing: it sleeps the inter-event gap scaled by
``PACING_MULTIPLIER`` against a monotonic clock so inter-event delay is > 0 for pacing > 0.

Each ``SynthAlarm`` (or an ingested ``AlarmEvent``) is wrapped in a fresh ``TypedEnvelope`` with
a new ``eventId`` per emit, then handed to the injected producer (real Kafka or test double) and,
when configured, tapped by the corpus writer so the export file is exactly the wire stream.
"""

from __future__ import annotations

import time
import uuid
from collections.abc import Callable
from datetime import datetime

from acp_event_model import AlarmEvent, TypedEnvelope

from simulator.engine.models import SynthAlarm
from simulator.integrations.producer import AlarmProducer
from simulator.obs import metrics

HISTORY_TOPIC = "alarms.history"
LIVE_TOPIC = "alarms.live"

# A tap is called once per emitted (topic, envelope) so the corpus writer records the wire stream.
EmitTap = Callable[[str, TypedEnvelope[AlarmEvent]], None]


def synth_to_event(alarm: SynthAlarm) -> AlarmEvent:
    """Construct a frozen ``AlarmEvent`` payload from a synthesized alarm."""
    return AlarmEvent(
        alarmId=alarm.alarm_id,
        managedObjectId=alarm.managed_object_id,
        eventType=alarm.event_type,
        probableCause=alarm.probable_cause,
        alarmType=alarm.alarm_type,
        perceivedSeverity=alarm.perceived_severity,
        raisedAt=alarm.raised_at,
        state=alarm.state,
        trailIds=[],
    )


def wrap_envelope(event: AlarmEvent, *, trace_id: str) -> TypedEnvelope[AlarmEvent]:
    """Wrap an ``AlarmEvent`` in a fresh-``eventId`` envelope (source=simulator)."""
    return TypedEnvelope[AlarmEvent](
        eventId=str(uuid.uuid4()),
        type="AlarmEvent",
        schemaVersion=1,
        occurredAt=event.raisedAt,
        source="simulator",
        traceId=trace_id or event.alarmId,
        payload=event,
    )


def _count(topic: str, event: AlarmEvent, scenario: str) -> None:
    metrics.ALARMS_EMITTED.labels(topic=topic, scenario=scenario, alarmType=event.alarmType).inc()


class BatchReplay:
    """Batch replay onto ``alarms.history`` (fire-and-flush; timestamps set the window)."""

    topic = HISTORY_TOPIC

    def __init__(self, producer: AlarmProducer, tap: EmitTap | None = None) -> None:
        self._producer = producer
        self._tap = tap

    def replay_synth(self, alarms: list[SynthAlarm]) -> int:
        n = 0
        for alarm in alarms:
            event = synth_to_event(alarm)
            envelope = wrap_envelope(event, trace_id=alarm.trace_id)
            self._producer.produce(self.topic, envelope)
            _count(self.topic, event, alarm.scenario_id or ("noise" if alarm.is_noise else "background"))
            if self._tap:
                self._tap(self.topic, envelope)
            n += 1
        self._producer.flush()
        return n

    def replay_events(self, events: list[AlarmEvent]) -> int:
        """Replay pre-built ``AlarmEvent``s verbatim (ingest path)."""
        n = 0
        for event in events:
            envelope = wrap_envelope(event, trace_id=event.alarmId)
            self._producer.produce(self.topic, envelope)
            metrics.INGESTED_ALARMS.labels(topic=self.topic).inc()
            _count(self.topic, event, "ingest")
            if self._tap:
                self._tap(self.topic, envelope)
            n += 1
        self._producer.flush()
        return n


class LiveReplay:
    """Live replay onto ``alarms.live`` with wall-clock pacing against a monotonic clock."""

    topic = LIVE_TOPIC

    def __init__(
        self,
        producer: AlarmProducer,
        pacing_multiplier: float = 1.0,
        tap: EmitTap | None = None,
        sleeper: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._producer = producer
        self._pacing = pacing_multiplier
        self._tap = tap
        self._sleep = sleeper
        self._clock = clock

    def _pace(self, prev: datetime | None, cur: datetime) -> None:
        if prev is None or self._pacing <= 0:
            return
        gap_s = (cur - prev).total_seconds() * self._pacing
        if gap_s > 0:
            self._sleep(gap_s)

    def replay_synth(self, alarms: list[SynthAlarm]) -> int:
        n = 0
        prev: datetime | None = None
        for alarm in alarms:
            self._pace(prev, alarm.raised_at)
            prev = alarm.raised_at
            event = synth_to_event(alarm)
            envelope = wrap_envelope(event, trace_id=alarm.trace_id)
            self._producer.produce(self.topic, envelope)
            _count(self.topic, event, alarm.scenario_id or ("noise" if alarm.is_noise else "background"))
            if self._tap:
                self._tap(self.topic, envelope)
            n += 1
        self._producer.flush()
        return n

    def replay_events(self, events: list[AlarmEvent]) -> int:
        n = 0
        prev: datetime | None = None
        for event in events:
            self._pace(prev, event.raisedAt)
            prev = event.raisedAt
            envelope = wrap_envelope(event, trace_id=event.alarmId)
            self._producer.produce(self.topic, envelope)
            metrics.INGESTED_ALARMS.labels(topic=self.topic).inc()
            _count(self.topic, event, "ingest")
            if self._tap:
                self._tap(self.topic, envelope)
            n += 1
        self._producer.flush()
        return n


def make_replay(
    phase: str, producer: AlarmProducer, pacing_multiplier: float, tap: EmitTap | None = None
) -> BatchReplay | LiveReplay:
    """Pick the replay strategy for the phase (P2 → batch/history, P3 → live)."""
    if phase == "p3":
        return LiveReplay(producer, pacing_multiplier=pacing_multiplier, tap=tap)
    return BatchReplay(producer, tap=tap)
