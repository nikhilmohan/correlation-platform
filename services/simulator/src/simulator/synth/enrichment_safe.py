"""P3 enrichment-safe cascade helpers (spec Task 24, AC 59-65; design §D).

On the P3 live path the **Enrichment** service (NOT the P2-only noise-filter) legitimately applies
dedup, self-clear/transient suppression, and flap-damping. Aligned cascades must survive it intact
so the whole cascade reaches the Correlation Engine and auto-correlates. These are **pure** helpers
(no I/O): they compute the reconciled inter-arrival spacing bounds, decide whether a pattern can be
synthesized enrichment-safe at all, resolve the effective transient-type set (config override or
pack-derived), and assert a built cascade is safe (used as a runtime belt-and-braces + heavily in
unit tests). Non-aligned/noise synthesis is exempt (AC 64) and never calls into here.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass

from simulator.engine.domain_pack import DomainPack
from simulator.engine.models import SynthAlarm


@dataclass(frozen=True)
class SpacingBounds:
    """Reconciled per-cascade inter-arrival spacing bounds (ms), design §D rule 3 (corrected).

    ``hi`` is the largest inter-arrival gap that still keeps the whole cascade within the session
    window given its length. ``lo`` is a small NATURAL floor (never the dedup window) — aligned
    cascades fire DISTINCT alarmTypes on DISTINCT managedObjectIds, so every element has a distinct
    enrichment dedup key ``(path, source, managedObjectId, eventType, alarmType, state)`` and
    enrichment's DedupStep never collapses them. The old dedup-window floor was based on a false
    premise (that consecutive distinct-key elements would be deduped) and needlessly excluded every
    real cascade whose per-gap budget was below that floor; it has been removed.
    """

    lo_ms: float
    hi_ms: float


@dataclass(frozen=True)
class SpacingConflict:
    """The cascade length cannot fit inside the session window at all (degenerate — AC 62).

    Now only triggers for the degenerate ``session_window_ms <= 0`` case; distinct-key cascades are
    never excluded for a dedup-window/session-window conflict (that premise was incorrect).
    """

    pattern_id: str
    dedup_window_ms: float
    session_window_ms: float
    cascade_length: int
    reason: str


def reconcile_spacing(
    dedup_window_ms: float,
    session_window_ms: float,
    cascade_length: int,
    *,
    spacing_margin: float,
    in_window_margin: float,
) -> SpacingBounds | SpacingConflict:
    """Reconcile the per-cascade inter-arrival spacing to fit the session window (corrected model).

    Aligned cascades are enrichment-safe **by construction**: each element is placed on a distinct
    managedObjectId and each sequence position is a distinct alarmType, so every element has a
    distinct enrichment dedup key and enrichment's DedupStep (which only collapses REPEATED
    IDENTICAL ``(path, source, managedObjectId, eventType, alarmType, state)``) never applies.
    There is therefore NO need to space elements above the dedup window — the cascade uses its
    natural pattern timing, which already fits inside the session window.

    - upper budget ``budget = session_window_ms * in_window_margin`` so the whole cascade stays
      inside the session window (AC 61b); the per-gap upper bound is ``budget / (n - 1)``;
    - lower bound is a small natural floor (``0``) — the dedup-window floor was removed because it
      was based on a false premise and blanket-excluded every real pattern (see class docstring).

    ``dedup_window_ms`` / ``spacing_margin`` are retained in the signature (used elsewhere only for
    the narrow genuine-duplicate guard) but no longer act as an inter-arrival floor. Returns a
    :class:`SpacingConflict` only in the degenerate ``session_window_ms <= 0`` case; a distinct-key
    cascade is never excluded for a dedup/window conflict. A single-element cascade never conflicts.
    """
    del dedup_window_ms, spacing_margin  # no longer used as an inter-arrival floor (see docstring)
    n = max(1, cascade_length)
    if n <= 1:
        # No inter-arrival gap needed; hi is irrelevant but kept coherent for the caller.
        return SpacingBounds(lo_ms=0.0, hi_ms=0.0)
    if session_window_ms <= 0:
        return SpacingConflict(
            pattern_id="",
            dedup_window_ms=0.0,
            session_window_ms=session_window_ms,
            cascade_length=n,
            reason="sessionWindow.windowMs <= 0 (degenerate)",
        )
    budget = session_window_ms * in_window_margin
    hi_per_gap = budget / (n - 1)
    return SpacingBounds(lo_ms=0.0, hi_ms=hi_per_gap)


def transient_types(pack: DomainPack, override: Iterable[str]) -> frozenset[str]:
    """The effective transient/self-clearing alarmType set excluded from aligned cascades (AC 60).

    Config override wins when non-empty (``P3_ENRICHMENT_TRANSIENT_TYPES``); otherwise the set is
    **pack-derived** from the domain pack's self-clearing noise classes — never hard-coded in
    ``synth`` (CLAUDE.md). Returns an immutable set.
    """
    override_set = {t for t in override if t}
    if override_set:
        return frozenset(override_set)
    derived: set[str] = set()
    for nc in pack.noise_classes():
        if nc.self_clearing:
            derived.update(nc.alarm_types)
    return frozenset(derived)


class EnrichmentSafetyError(AssertionError):
    """A synthesized aligned cascade violates an enrichment-safe invariant (should never happen)."""


def assert_cascade_safe(
    alarms: list[SynthAlarm],
    *,
    dedup_window_ms: float,
    transients: frozenset[str],
) -> None:
    """Assert a built aligned cascade is enrichment-safe (design §D; AC 59, 60, 63).

    Checks, by construction, that: (1) no two elements share ``(managedObjectId, alarmType)`` within
    the dedup window (AC 59); (2) no element's ``alarmType`` is a transient/self-clearing type
    (AC 60); (3) every element is ``state="raised"`` and no ``(managedObjectId, alarmType)`` repeats
    with an alternating raised/cleared pair (AC 63). Raises :class:`EnrichmentSafetyError` on any
    violation. Used as a cheap runtime guard and heavily in unit tests.
    """
    ordered = sorted(alarms, key=lambda a: a.raised_at)
    seen: dict[tuple[str, str], SynthAlarm] = {}
    for alarm in ordered:
        if alarm.alarm_type in transients:
            raise EnrichmentSafetyError(
                f"transient alarmType {alarm.alarm_type!r} in aligned cascade (AC 60)"
            )
        if alarm.state != "raised":
            raise EnrichmentSafetyError(
                f"non-raised state {alarm.state!r} in aligned cascade (AC 63)"
            )
        key = (alarm.managed_object_id, alarm.alarm_type)
        prior = seen.get(key)
        if prior is not None:
            gap_ms = abs((alarm.raised_at - prior.raised_at).total_seconds()) * 1000.0
            if gap_ms <= dedup_window_ms:
                raise EnrichmentSafetyError(
                    f"duplicate {key} within dedup window {dedup_window_ms}ms (AC 59)"
                )
        seen[key] = alarm
