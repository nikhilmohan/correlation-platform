"""[SAMPLE] Bounded, deterministic XAI member-alarm evidence for each mined pattern.

For every emitted ``PatternMinedEvent`` the miner attaches an optional ``sampleAlarms[]`` — a
small, representative set of the pattern's **real** member alarms (concrete evidence behind the
abstract ``sequence`` so a human reviewer can judge the pattern's trustworthiness during
review/approval). This is derived entirely from alarms the run **already holds in memory**
(``GroupPattern.matching_sessions[*].alarms``, real ``Alarm``s from the consumed
``TransactionEvent``s): there is **no new input, no fabrication, no persistence** — the miner
stays stateless ("emit and forget").

Selection (design [SAMPLE], OQ-SA-1/OQ-SA-2 resolved):

1. **Representative session** — a pattern may have several supporting sessions across
   trails/windows. Sample from **one** deterministically-chosen representative session so the
   sample is one real, single occurrence of the cascade (coherent evidence, not a
   cross-occurrence mash-up). To make the choice stable regardless of iteration order the
   selector sorts ``matching_sessions`` by ``(windowStart, trailId, sourceWindowId)`` ascending
   and takes the earliest ([0]) — the earliest-by-window-start occurrence.
2. **Map** each ``Alarm`` to a :class:`~acp_event_model._generated.SampleAlarm` (the five
   event-model fields: ``alarmId, alarmType, raisedAt, managedObjectId, perceivedSeverity``;
   ``eventType`` is dropped — it is not on ``SampleAlarm``).
3. **Dedup** by ``alarmId`` (keep first occurrence) so K *distinct* alarms are shown.
4. **Order** ascending by ``(raisedAt, alarmId)`` — chronological within the occurrence, with
   ``alarmId`` a stable tie-break for equal timestamps.
5. **Cap** to the first **K** (``params.sample_max_alarms``, Knowledge-sourced — no code
   literal). K or fewer distinct alarms -> all included; more -> exactly K.

Because the alarms come from a session whose token list contains the pattern's representative
``sequence``, **every sampled ``alarmType`` is a member of that ``sequence`` by construction**
(AC-24) — no filtering needed. When the representative session has no alarms (or, defensively, no
matching session exists), the selector returns ``[]`` — ``sampleAlarms=[]`` (or absent) is a valid
event (AC-25). Every step is a pure, order-stable function of the in-run session data, so re-mining
the same input yields byte-identical ``sampleAlarms[]`` (replay-safe determinism). No Spark is
involved — selection is pure Python over ``Session.alarms``, fully testable without a Spark runtime.
"""

from __future__ import annotations

from acp_event_model import Alarm
from acp_event_model._generated import SampleAlarm

from .mining import GroupPattern
from .windowing import Session


def _representative_session(sessions: list[Session]) -> Session | None:
    """The deterministic representative supporting session: earliest by window start.

    Sorts by ``(windowStart, trailId, sourceWindowId)`` ascending and returns the earliest, so the
    choice is stable regardless of the order the sessions arrive in. The window start is the
    ``raisedAt`` of the session's first (already chronologically-ordered) alarm; a session with no
    alarms sorts before any populated session but yields an empty sample downstream (AC-25).
    """
    if not sessions:
        return None

    def _key(session: Session) -> tuple[str, str, str]:
        window_start = session.alarms[0].raisedAt.isoformat() if session.alarms else ""
        return (window_start, session.trail_id, session.source_window_id)

    return min(sessions, key=_key)


def _to_sample_alarm(alarm: Alarm) -> SampleAlarm:
    """Map a typed ``Alarm`` to an event-model ``SampleAlarm`` (5 fields; drop ``eventType``)."""
    return SampleAlarm(
        alarmId=alarm.alarmId,
        alarmType=alarm.alarmType,
        raisedAt=alarm.raisedAt,
        managedObjectId=alarm.managedObjectId,
        perceivedSeverity=alarm.perceivedSeverity,
    )


class SampleAlarmSelector:
    """Selects the bounded, deterministic ``sampleAlarms[]`` for one anchored group's pattern.

    Pure Python — **no Spark**. Given a :class:`~pattern_miner.mining.GroupPattern` and the
    Knowledge-sourced cap ``K``, returns ``list[SampleAlarm]`` (possibly empty) per the design
    [SAMPLE] flow. Stateless and side-effect-free: the same input always yields the same output.
    """

    def select(self, group_pattern: GroupPattern, max_alarms: int) -> list[SampleAlarm]:
        """Return up to ``max_alarms`` deterministic ``SampleAlarm``s from the group's evidence.

        ``max_alarms`` is ``params.sample_max_alarms`` — the Knowledge-sourced cap K (no code
        default). A non-positive cap yields an empty sample (defensive; a valid, still-emitting
        event per AC-25). An empty representative session likewise yields ``[]``.
        """
        if max_alarms <= 0:
            return []

        session = _representative_session(group_pattern.matching_sessions)
        if session is None or not session.alarms:
            return []

        # Dedup by alarmId (keep first occurrence) so the cap yields K *distinct* alarms.
        by_id: dict[str, Alarm] = {}
        for alarm in session.alarms:
            if alarm.alarmId not in by_id:
                by_id[alarm.alarmId] = alarm

        # Order ascending by (raisedAt, alarmId): chronological within the occurrence, alarmId a
        # stable deterministic tie-break for equal timestamps.
        ordered = sorted(by_id.values(), key=lambda a: (a.raisedAt, a.alarmId))

        # Cap to the first K.
        return [_to_sample_alarm(a) for a in ordered[:max_alarms]]
