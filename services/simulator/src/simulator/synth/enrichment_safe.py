"""P3 enrichment-safe cascade helpers (spec Task 24, AC 59-65; design §D).

On the P3 live path the **Enrichment** service (NOT the P2-only noise-filter) legitimately applies
dedup, self-clear/transient suppression, and flap-damping. Aligned cascades must survive it intact
so the whole cascade reaches the Correlation Engine and auto-correlates. These are **pure** helpers
(no I/O): they compute the reconciled inter-arrival spacing bounds, decide whether a pattern can be
synthesized enrichment-safe at all, and assert a built cascade is safe (used as a runtime
belt-and-braces + heavily in unit tests). Non-aligned/noise synthesis is exempt (AC 64) and never
calls into here.

Corrected enrichment-safe model (AC 60 premise fix): self-clear (``SelfClearStep``) and
flap-damping (``FlapDampStep``) are properties of RAISE+CLEAR BEHAVIOUR, **not** of alarmType
identity. ``SelfClearStep`` suppresses a raise only when a matching CLEARED event (same key incl.
alarmType) arrives within the source hold-time (a genuine transient raise/clear pair); with no
clear it releases the raise downstream intact. ``FlapDampStep`` collapses repeated raise/clear
flapping of the same alarm. Aligned cascade elements are **sustained single raises** — the
simulator emits each cascade element as one ``raised`` AlarmEvent and NEVER emits a matching
``cleared`` for it — so they are self-clear-safe and flap-safe regardless of alarmType. The same
alarmType being ALSO used as self-clearing NOISE (a raise+clear pair) elsewhere does not make a
sustained cascade raise a transient. There is therefore NO alarmType blocklist on aligned cascades;
enrichment-safety = distinct dedup keys + the sustained single-raise (no clear) construction.
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
    """The effective transient/self-clearing alarmType set (noise-only; NOT an aligned blocklist).

    Config override wins when non-empty (``P3_ENRICHMENT_TRANSIENT_TYPES``); otherwise the set is
    **pack-derived** from the domain pack's self-clearing noise classes — never hard-coded in
    ``synth`` (CLAUDE.md). Returns an immutable set.

    NOTE (AC 60 premise correction): this set is **no longer** used to exclude aligned-cascade
    elements. Transience is a per-instance raise/clear property, not an alarmType property, and
    aligned cascade elements are sustained single raises (no clear) so they are self-clear-safe and
    flap-safe regardless of alarmType. The knob/derivation is retained for the non-aligned/noise
    path only (noise IS allowed to be transient); it must never gate aligned cascades.
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
) -> None:
    """Assert a built aligned cascade is enrichment-safe (design §D; AC 59, 63; AC 60 corrected).

    The genuine enrichment-safety invariants for an aligned cascade — all by construction:
    (1) every element is a SUSTAINED single ``state="raised"`` event; the simulator never emits a
    matching ``cleared`` for a cascade member, so enrichment's ``SelfClearStep`` (release when no
    clear arrives) and ``FlapDampStep`` never suppress it — regardless of alarmType (AC 63);
    (2) no two elements share ``(managedObjectId, alarmType)`` within the dedup window — each
    element is on a DISTINCT managedObjectId so every element has a distinct enrichment dedup key
    and ``DedupStep`` never collapses them (AC 59).

    There is **no** alarmType blocklist: the former AC-60 "no transient/self-clearing alarmType as a
    cascade member" check conflated transience (a per-instance raise/clear property) with alarmType
    identity and wrongly excluded real patterns (QueueDrop -> ... -> CRCErrors; CRCErrors root
    cause). Since cascade elements are sustained raises with no clear, they are self-clear-safe and
    flap-safe even when the same alarmType is used as self-clearing NOISE elsewhere.

    Raises :class:`EnrichmentSafetyError` on any violation. Cheap runtime guard + used in tests.
    """
    ordered = sorted(alarms, key=lambda a: a.raised_at)
    seen: dict[tuple[str, str], SynthAlarm] = {}
    for alarm in ordered:
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
