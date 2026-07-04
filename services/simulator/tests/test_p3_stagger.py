"""P3 cascade stagger tests (M1 — defeat Correlation-Engine session collapse -> preserve the KPI).

The Correlation Engine keys sessions by ``(trailId, patternId)`` and windows on ``raisedAt``. If
many same-(trail, pattern) cascades all start at one ``base_time`` they COLLAPSE into ~one incident,
sinking the realized auto-correlation rate (the 60-70% KPI). These tests prove:

- N cascades of the SAME (trailId, patternId) get start times separated by STRICTLY MORE than that
  pattern's ``sessionWindow.windowMs`` (so each forms its own CE session -> its own incident); and
- a fixed seed reproduces identical cascade start times (AC 41 preserved).
"""

from __future__ import annotations

import random
from datetime import UTC, datetime

from simulator.domains.coreip.pack import CoreIPPack
from simulator.synth import aligned_controller
from simulator.synth.models import PatternView, TrailDetail
from tests import p3_fixtures as fx

_EPOCH = datetime(2026, 1, 1, tzinfo=UTC)
_WINDOW_MS = 6000


def _single_pattern() -> list[PatternView]:
    # One pattern on one trail -> the round-robin instantiates it repeatedly (the collapse case).
    return [
        PatternView.from_api(
            fx.pattern_view(
                "pat-01",
                "trail-A",
                [("IPLinkDown", False), ("ISISAdjacencyDown", False)],
                "IPLinkDown",
                window_ms=_WINDOW_MS,
            )
        )
    ]


def _trails() -> dict[str, TrailDetail]:
    return {"trail-A": TrailDetail.from_api(fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS))}


def _plan(seed: int):
    return aligned_controller.plan(
        CoreIPPack(),
        _single_pattern(),
        _trails(),
        total_alarms=400,  # large T so many aligned cascades are instantiated
        aligned_fraction=0.9,
        rng=random.Random(seed),
        base_time=_EPOCH,
        stagger_margin=1.5,
        stagger_jitter_ms=2000.0,
    )


def _cascade_starts(plan) -> list[datetime]:
    # A cascade's start is its first (root) alarm's raised_at.
    return [min(a.raised_at for a in c.alarms) for c in plan.cascades]


def test_same_trail_pattern_cascades_separated_beyond_window() -> None:
    plan = _plan(seed=13)
    starts = sorted(_cascade_starts(plan))
    assert len(starts) >= 3, "need several repeats to exercise the collapse case"
    for earlier, later in zip(starts, starts[1:], strict=False):
        gap_ms = (later - earlier).total_seconds() * 1000.0
        assert gap_ms > _WINDOW_MS, (
            f"consecutive same-(trail,pattern) cascades separated by {gap_ms}ms "
            f"which is not strictly > sessionWindow {_WINDOW_MS}ms — CE sessions would collapse"
        )


def test_stagger_is_seed_reproducible() -> None:
    starts_a = _cascade_starts(_plan(seed=99))
    starts_b = _cascade_starts(_plan(seed=99))
    assert starts_a == starts_b
    # A different seed changes the jitter (offsets differ) — the offset is seeded, not fixed.
    starts_c = _cascade_starts(_plan(seed=100))
    assert starts_a != starts_c
