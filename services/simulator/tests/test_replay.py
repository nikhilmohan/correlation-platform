"""Replay strategy tests (criteria 8, 9, 24, 37).

History mode (P2) batch-replays onto ``alarms.history`` only; live mode (P3) replays onto
``alarms.live`` only, with wall-clock pacing (inter-event sleep proportional to the configured
pacing multiplier). Every emit wraps the payload in a fresh-``eventId`` envelope and, when a tap
is configured, hands the wire envelope to the corpus writer. The real Kafka producer is replaced
by an in-memory double; the live clock is replaced by an injected sleeper.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from acp_event_model import AlarmEvent, TypedEnvelope

from simulator.engine import replay
from simulator.engine.models import SynthAlarm


class FakeProducer:
    """In-memory AlarmProducer double recording every (topic, envelope)."""

    def __init__(self) -> None:
        self.sent: list[tuple[str, TypedEnvelope]] = []
        self.flushed = 0

    def produce(self, topic: str, envelope: TypedEnvelope) -> None:
        self.sent.append((topic, envelope))

    def flush(self) -> None:
        self.flushed += 1


def _alarm(idx: int, *, offset_s: float = 0.0, scenario: str | None = None) -> SynthAlarm:
    base = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)
    return SynthAlarm(
        alarm_id=f"a{idx}",
        managed_object_id="Node:n1",
        alarm_type="NodeDown",
        event_type="communicationsAlarm",
        probable_cause="nodeFailure",
        perceived_severity="critical",
        raised_at=base + timedelta(seconds=offset_s),
        trace_id=f"t{idx}",
        scenario_id=scenario,
    )


def _event(idx: int, *, offset_s: float = 0.0) -> AlarmEvent:
    base = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)
    return AlarmEvent(
        alarmId=f"e{idx}",
        managedObjectId="Node:n1",
        eventType="communicationsAlarm",
        probableCause="nodeFailure",
        alarmType="NodeDown",
        perceivedSeverity="critical",
        raisedAt=base + timedelta(seconds=offset_s),
        state="raised",
        trailIds=[],
    )


def test_synth_to_event_carries_canonical_alarm_type() -> None:
    ev = replay.synth_to_event(_alarm(1))
    assert ev.alarmType == "NodeDown"
    assert ev.alarmId == "a1"


def test_wrap_envelope_assigns_fresh_event_id() -> None:
    ev = _event(1)
    e1 = replay.wrap_envelope(ev, trace_id="tr")
    e2 = replay.wrap_envelope(ev, trace_id="tr")
    assert e1.eventId != e2.eventId
    assert e1.source == "simulator"
    assert e1.payload.alarmId == "e1"


def test_ac8_batch_replay_lands_only_on_history() -> None:
    prod = FakeProducer()
    n = replay.BatchReplay(prod).replay_synth([_alarm(1), _alarm(2, scenario="fiber-cut")])
    assert n == 2
    topics = {t for t, _ in prod.sent}
    assert topics == {replay.HISTORY_TOPIC}
    assert replay.LIVE_TOPIC not in topics
    assert prod.flushed == 1


def test_ac8_make_replay_p2_is_batch_history() -> None:
    strat = replay.make_replay("p2", FakeProducer(), 1.0)
    assert isinstance(strat, replay.BatchReplay)
    assert strat.topic == replay.HISTORY_TOPIC


def test_ac9_live_replay_lands_only_on_live() -> None:
    prod = FakeProducer()
    strat = replay.make_replay("p3", prod, 0.0)
    n = strat.replay_synth([_alarm(1), _alarm(2, offset_s=1.0)])
    assert n == 2
    topics = {t for t, _ in prod.sent}
    assert topics == {replay.LIVE_TOPIC}
    assert replay.HISTORY_TOPIC not in topics


def test_ac9_live_pacing_sleeps_proportional_to_multiplier() -> None:
    slept: list[float] = []
    prod = FakeProducer()
    strat = replay.LiveReplay(prod, pacing_multiplier=2.0, sleeper=slept.append)
    # three alarms 1s apart -> two inter-event gaps, each 1s * 2.0 = 2.0s
    strat.replay_synth([_alarm(1, offset_s=0), _alarm(2, offset_s=1), _alarm(3, offset_s=2)])
    assert slept == [2.0, 2.0]
    assert all(s > 0 for s in slept)


def test_ac9_pacing_zero_emits_without_sleeping() -> None:
    slept: list[float] = []
    strat = replay.LiveReplay(FakeProducer(), pacing_multiplier=0.0, sleeper=slept.append)
    strat.replay_synth([_alarm(1, offset_s=0), _alarm(2, offset_s=5)])
    assert slept == []


def test_live_ingest_replay_paces_events() -> None:
    slept: list[float] = []
    prod = FakeProducer()
    strat = replay.LiveReplay(prod, pacing_multiplier=1.0, sleeper=slept.append)
    n = strat.replay_events([_event(1, offset_s=0), _event(2, offset_s=3)])
    assert n == 2
    assert slept == [3.0]
    assert {t for t, _ in prod.sent} == {replay.LIVE_TOPIC}


def test_batch_ingest_replay_preserves_payload_verbatim() -> None:
    prod = FakeProducer()
    n = replay.BatchReplay(prod).replay_events([_event(1), _event(2)])
    assert n == 2
    assert [e.payload.alarmId for _, e in prod.sent] == ["e1", "e2"]
    assert prod.flushed == 1


def test_tap_receives_every_emitted_envelope() -> None:
    tapped: list[tuple[str, TypedEnvelope]] = []
    prod = FakeProducer()
    replay.BatchReplay(prod, tap=lambda t, e: tapped.append((t, e))).replay_synth(
        [_alarm(1), _alarm(2)]
    )
    assert len(tapped) == 2
    assert [e.payload.alarmId for _, e in tapped] == ["a1", "a2"]


def test_tap_records_batch_ingest_stream() -> None:
    tapped: list[str] = []
    replay.BatchReplay(
        FakeProducer(), tap=lambda t, e: tapped.append(e.payload.alarmId)
    ).replay_events([_event(1), _event(2)])
    assert tapped == ["e1", "e2"]


def test_tap_records_live_synth_and_ingest_streams() -> None:
    tapped: list[str] = []
    strat = replay.LiveReplay(
        FakeProducer(), pacing_multiplier=0.0, tap=lambda t, e: tapped.append(e.payload.alarmId)
    )
    strat.replay_synth([_alarm(1)])
    strat.replay_events([_event(2)])
    assert tapped == ["a1", "e2"]
