"""Millisecond-keyed inter-arrival timing statistics (spec §Timing, P2-GAP-10).

``PatternMinedEvent.timing`` is an OPEN object (``additionalProperties: true``) in the frozen
schema — this module pins the producer-side *contract-of-shape*: the exact keys + units the sole
consumer (the Pattern Manager ``SessionWindowDeriver``) reads. The four canonical sub-fields, all
in **milliseconds**, computed from the per-alarm ``raisedAt`` across the sessions that match a
discovered sequence:

- ``timeframeMs`` — ``max(raisedAt) - min(raisedAt)`` of a matching session, taken as the
  **median** over all matching session occurrences.
- ``medianInterArrivalMs`` — **median** of consecutive-alarm gaps across the matching sessions
  (median, not mean — the deriver computes ``cv`` from it).
- ``maxInterArrivalMs`` — **max** consecutive-alarm gap across the matching sessions.
- ``stddevInterArrivalMs`` — **population stddev** of the consecutive-alarm gaps across the
  matching sessions.

Gaps/spans are whole milliseconds (integer). Inter-arrivals are gaps WITHIN each matching session
(never across the idle boundary between sessions). If a sequence has no inter-arrival sample at all,
all four keys are ``0`` (the consumer's documented thin-timing fallback handles it).
"""

from __future__ import annotations

import statistics
from dataclasses import dataclass

from acp_event_model import Alarm


@dataclass(frozen=True)
class Timing:
    """The canonical ms-keyed timing object emitted on ``PatternMinedEvent.timing``."""

    timeframe_ms: int
    median_inter_arrival_ms: int
    max_inter_arrival_ms: int
    stddev_inter_arrival_ms: int

    def to_dict(self) -> dict[str, int]:
        """Exact wire keys the Pattern Manager ``SessionWindowDeriver`` consumes."""
        return {
            "timeframeMs": self.timeframe_ms,
            "medianInterArrivalMs": self.median_inter_arrival_ms,
            "maxInterArrivalMs": self.max_inter_arrival_ms,
            "stddevInterArrivalMs": self.stddev_inter_arrival_ms,
        }


def _ms(seconds: float) -> int:
    """A duration in whole milliseconds (integer)."""
    return int(round(seconds * 1000.0))


def _session_gaps_ms(alarms: tuple[Alarm, ...]) -> list[int]:
    """Consecutive-alarm inter-arrival gaps (ms) within one ordered session occurrence."""
    ordered = sorted(alarms, key=lambda a: (a.raisedAt, a.alarmId))
    gaps: list[int] = []
    for prev, cur in zip(ordered, ordered[1:], strict=False):
        gaps.append(_ms((cur.raisedAt - prev.raisedAt).total_seconds()))
    return gaps


def _session_span_ms(alarms: tuple[Alarm, ...]) -> int:
    """Span (ms) of one session occurrence: max(raisedAt) - min(raisedAt)."""
    if len(alarms) < 2:
        return 0
    ordered = sorted(alarms, key=lambda a: (a.raisedAt, a.alarmId))
    return _ms((ordered[-1].raisedAt - ordered[0].raisedAt).total_seconds())


class TimingComputer:
    """Aggregates ms timing statistics over all session occurrences matching a sequence."""

    def compute(self, matching_sessions: list[tuple[Alarm, ...]]) -> Timing:
        """Compute the canonical ms timing object over the alarm sets of matching sessions."""
        spans: list[int] = []
        all_gaps: list[int] = []
        for alarms in matching_sessions:
            if len(alarms) >= 2:
                spans.append(_session_span_ms(alarms))
            all_gaps.extend(_session_gaps_ms(alarms))

        if not all_gaps:
            return Timing(0, 0, 0, 0)

        timeframe = int(round(statistics.median(spans))) if spans else 0
        median_gap = int(round(statistics.median(all_gaps)))
        max_gap = max(all_gaps)
        stddev_gap = int(round(statistics.pstdev(all_gaps))) if len(all_gaps) > 1 else 0
        return Timing(
            timeframe_ms=timeframe,
            median_inter_arrival_ms=median_gap,
            max_inter_arrival_ms=max_gap,
            stddev_inter_arrival_ms=stddev_gap,
        )
