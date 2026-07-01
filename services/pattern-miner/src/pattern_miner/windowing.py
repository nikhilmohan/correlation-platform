"""Dynamic, activity/idle-driven session windowing (spec §Windowing, criteria 10-12).

The Miner treats ``TransactionEvent``s as inputs to **re-window**, not as final sessions. Per
trail it pools the typed ``alarms[]`` across the run's transactions, orders them by ``raisedAt``,
and splits them into **activity sessions**: a session is a contiguous burst that **closes when the
trail falls idle** — when the inter-arrival gap to the next alarm exceeds an *adaptive closing gap*
computed for that burst.

Adaptive gap (the OQ#50 hybrid): a Knowledge tempo-class floor + data-driven per-burst derivation,
clamped, with a Knowledge base/fallback gap::

    closingGap(burst) = clamp(
        multiplier * percentile(interArrivals(burst), p),   # data-driven, tempo-tracking
        lower = profileFloor(tempoClass(burst)),            # Knowledge per-class floor
        upper = maxClosingGap,                              # Knowledge ceiling
    )

with ``baseGap`` used when a burst has too few inter-arrivals to derive a stable percentile
(``< minBurstSamples``) or when no tempo-class profile matches. **Every parameter is
Knowledge-sourced — no hard-coded gap.**

The splitter is a two-pass algorithm because the closing gap depends on the burst's own
inter-arrival statistics: pass 1 provisionally segments on the ``baseGap`` to bound candidate
bursts and estimate each burst's tempo; pass 2 re-derives the adaptive gap per candidate burst and
re-splits. This makes a fast cascade (small adaptive gap, kept whole) and a slow build-up (large
adaptive gap, kept whole) both resolve to single sessions calibrated to their own tempo, while a
genuine idle period between two bursts (longer than any intra-burst gap) still splits them.

Timing/windowing read ``raisedAt`` directly from the typed ``alarms[]`` — no resolver.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass

from acp_event_model import Alarm

from .config import TempoProfile, WindowingParams
from .logging_setup import get_logger

log = get_logger(__name__)


@dataclass(frozen=True)
class Session:
    """One activity session: an ordered, trail-scoped burst of alarms with a stable window id."""

    trail_id: str
    snapshot_id: str
    domain: str | None
    alarms: tuple[Alarm, ...]
    source_window_id: str
    tempo_class: str
    closing_gap_seconds: float
    used_fallback_gap: bool

    @property
    def sequence(self) -> list[str]:
        """The ordered list of ``alarmType`` tokens (the canonical join token) for PrefixSpan."""
        return [a.alarmType for a in self.alarms]


def _percentile(values: list[float], p: float) -> float:
    """Linear-interpolation percentile of ``values`` (0 <= p <= 100). Empty -> 0.0."""
    if not values:
        return 0.0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (p / 100.0) * (len(ordered) - 1)
    lo = int(rank)
    hi = min(lo + 1, len(ordered) - 1)
    frac = rank - lo
    return ordered[lo] + (ordered[hi] - ordered[lo]) * frac


def _inter_arrivals(alarms: list[Alarm]) -> list[float]:
    """Consecutive-alarm gaps (seconds) within an ordered alarm list."""
    gaps: list[float] = []
    for prev, cur in zip(alarms, alarms[1:], strict=False):
        gaps.append((cur.raisedAt - prev.raisedAt).total_seconds())
    return gaps


class AdaptiveGap:
    """Closing idle gap + tempo class for a burst, from Knowledge ``WindowingParams``."""

    def __init__(self, params: WindowingParams) -> None:
        self._p = params

    def tempo_class(self, median_inter_arrival: float) -> str:
        """Classify a burst by its observed median inter-arrival against Knowledge thresholds.

        ``class_thresholds`` maps a class name to the *upper* median-inter-arrival bound (seconds)
        for that class; the first class (ascending bound) the median falls under wins. When no
        thresholds are authored, the profile whose floor is closest below the median is chosen; if
        none, ``default`` (or the sole profile, or ``"default"``) is used.
        """
        thresholds = self._p.class_thresholds
        if thresholds:
            for name, upper in sorted(thresholds.items(), key=lambda kv: kv[1]):
                if median_inter_arrival <= upper:
                    return name
            # above every threshold -> the slowest declared class
            return max(thresholds.items(), key=lambda kv: kv[1])[0]
        # No thresholds: pick the profile whose floor is the largest not exceeding the median.
        profiles = self._p.profiles
        if profiles:
            candidates = [
                (n, prof.floor_seconds)
                for n, prof in profiles.items()
                if prof.floor_seconds <= max(median_inter_arrival, 0.0)
            ]
            if candidates:
                return max(candidates, key=lambda kv: kv[1])[0]
            return min(profiles.items(), key=lambda kv: kv[1].floor_seconds)[0]
        return "default"

    def _profile(self, name: str) -> TempoProfile | None:
        return self._p.profiles.get(name)

    def closing_gap(self, burst: list[Alarm]) -> tuple[float, str, bool]:
        """Return ``(closing_gap_seconds, tempo_class, used_fallback)`` for a candidate burst.

        Data-driven core: ``multiplier * median(interArrivals)``. The **median** (p50) is the
        robust characterization of a burst's typical intra-burst cadence — using a high percentile
        (p95) here would be inflated by a single genuine idle gap inside a coarse candidate burst
        and would fail to split two sub-bursts (criterion 11). This is the design's own worked
        semantics ("``p=50`` is the median"). The Knowledge ``tempoPercentile`` is used to CLASSIFY
        the burst's tempo (its slow tail) for the tempo-class floor selection, not the split gap.
        The
        result is clamped to ``[profileFloor, maxClosingGap]``; the Knowledge base/fallback gap
        applies when the burst has too few inter-arrivals to derive a stable statistic.
        """
        gaps = _inter_arrivals(burst)
        p = self._p
        # Too few inter-arrivals to derive a stable statistic -> Knowledge base/fallback gap.
        if len(gaps) < max(p.min_burst_samples - 1, 1):
            return p.base_gap_seconds, "default", True

        median = _percentile(gaps, 50.0)
        # Classify tempo by the burst's slow-tail percentile (Knowledge-governed p).
        tempo = self.tempo_class(_percentile(gaps, p.tempo_percentile))
        data_driven = p.gap_multiplier * median

        profile = self._profile(tempo)
        floor = profile.floor_seconds if profile is not None else p.base_gap_seconds
        ceiling = p.max_closing_gap_seconds
        if profile is not None and profile.ceiling_seconds is not None:
            ceiling = min(ceiling, profile.ceiling_seconds)

        used_fallback = profile is None
        gap = max(data_driven, floor)
        gap = min(gap, ceiling)
        return gap, tempo, used_fallback


class SessionWindower:
    """Splits pooled per-trail alarms into adaptive activity sessions (criteria 10-12)."""

    def __init__(self, params: WindowingParams, *, metrics=None) -> None:
        self._params = params
        self._gap = AdaptiveGap(params)
        self._metrics = metrics

    def _split_on_gap(self, alarms: list[Alarm], gap_seconds: float) -> list[list[Alarm]]:
        """Split an ordered alarm list wherever the inter-arrival exceeds ``gap_seconds``."""
        if not alarms:
            return []
        bursts: list[list[Alarm]] = [[alarms[0]]]
        for prev, cur in zip(alarms, alarms[1:], strict=False):
            if (cur.raisedAt - prev.raisedAt).total_seconds() > gap_seconds:
                bursts.append([cur])
            else:
                bursts[-1].append(cur)
        return bursts

    def sessions_for_trail(
        self,
        trail_id: str,
        alarms: list[Alarm],
        *,
        snapshot_id: str,
        domain: str | None,
    ) -> list[Session]:
        """Window one trail's pooled alarms into adaptive sessions.

        Pass 1: provisionally segment on the Knowledge **ceiling** (``max_closing_gap_seconds``) to
        bound candidate bursts — this keeps ALL activity of a burst together (even a slow burst
        minutes apart) so the per-burst tempo can be measured; the base gap is NOT used here (using
        it as the pass-1 segmenter would shred a slow burst before its adaptive gap is derived).
        Pass 2: for each candidate burst re-derive the adaptive closing gap from that burst's own
        tempo and re-split — so a fast burst (small gap) stays whole and a slow burst (large gap)
        stays whole, while a genuine idle period between bursts (longer than the ceiling, or than
        the burst's own adaptive gap) still splits them.
        """
        ordered = sorted(alarms, key=lambda a: (a.raisedAt, a.alarmId))
        if not ordered:
            return []

        provisional = self._split_on_gap(ordered, self._params.max_closing_gap_seconds)

        sessions: list[Session] = []
        for candidate in provisional:
            gap, tempo, used_fallback = self._gap.closing_gap(candidate)
            for burst in self._split_on_gap(candidate, gap):
                session = self._make_session(
                    trail_id,
                    burst,
                    snapshot_id=snapshot_id,
                    domain=domain,
                    tempo_class=tempo,
                    closing_gap=gap,
                    used_fallback=used_fallback,
                )
                sessions.append(session)
                if self._metrics is not None and used_fallback:
                    self._metrics.fallback_gap_used.inc()
                log.info(
                    "session_window_finalized",
                    trail_id=trail_id,
                    tempo_class=tempo,
                    closing_gap_seconds=round(gap, 6),
                    used_fallback_gap=used_fallback,
                    alarm_count=len(burst),
                    source_window_id=session.source_window_id,
                )
        return sessions

    def _make_session(
        self,
        trail_id: str,
        burst: list[Alarm],
        *,
        snapshot_id: str,
        domain: str | None,
        tempo_class: str,
        closing_gap: float,
        used_fallback: bool,
    ) -> Session:
        start = burst[0].raisedAt.isoformat()
        end = burst[-1].raisedAt.isoformat()
        window_id = _source_window_id(trail_id, start, end, snapshot_id)
        return Session(
            trail_id=trail_id,
            snapshot_id=snapshot_id,
            domain=domain,
            alarms=tuple(burst),
            source_window_id=window_id,
            tempo_class=tempo_class,
            closing_gap_seconds=closing_gap,
            used_fallback_gap=used_fallback,
        )


def _source_window_id(trail_id: str, start_iso: str, end_iso: str, snapshot_id: str) -> str:
    """Composite, deterministic ``sourceWindowId`` for an adaptive session (spec §Windowing).

    Stable for a given input + boundary; distinguishes the multiple sessions a single
    trail/transaction can yield. Format: ``sw:<trailId>:<12-hex-of-sha256(trail,start,end,snap)>``.
    """
    digest = hashlib.sha256(f"{trail_id}|{start_iso}|{end_iso}|{snapshot_id}".encode()).hexdigest()[
        :12
    ]
    return f"sw:{trail_id}:{digest}"
