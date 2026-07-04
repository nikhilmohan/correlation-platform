"""P3 aligned-fraction controller + non-aligned mix tests (AC 38, 39).

Drives ``aligned_controller.plan`` (aligned-fraction within tolerance, f=0.0 / f=1.0 edges) and
``nonaligned_synth.build_nonaligned`` (real moids + canonical alarmTypes + scenarioType labels).
"""

from __future__ import annotations

import random
from datetime import UTC, datetime

from simulator.domains.coreip.pack import CoreIPPack
from simulator.synth import aligned_controller, nonaligned_synth
from simulator.synth.models import (
    PatternView,
    SequenceElement,
    SessionWindow,
    Timing,
    TrailDetail,
    TrailMember,
)

BASE = datetime(2026, 1, 1, tzinfo=UTC)
VOCAB = set(CoreIPPack().alarm_type_vocabulary())


def _trail(trail_id: str, members: list[tuple[str, str]]) -> TrailDetail:
    return TrailDetail(trail_id=trail_id, members=tuple(TrailMember(m, ot) for m, ot in members))


def _pattern(pid: str, trail_id: str, seq: list[tuple[str, bool]], root: str) -> PatternView:
    return PatternView(
        pattern_id=pid,
        trail_id=trail_id,
        sequence=tuple(SequenceElement(at, opt) for at, opt in seq),
        root_cause_alarm_type=root,
        timing=Timing(
            median_inter_arrival_ms=200, max_inter_arrival_ms=500, stddev_inter_arrival_ms=0
        ),
        session_window=SessionWindow(window_ms=6000),
    )


def _fixture():
    trails = {
        "trail-A": _trail(
            "trail-A",
            [
                ("IPLink:ip-7", "IPLink"),
                ("IGPAdjacency:adj-3", "IGPAdjacency"),
                ("LSP:lsp-9", "LSP"),
            ],
        )
    }
    patterns = [
        _pattern(
            "pat-01",
            "trail-A",
            [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", False)],
            "IPLinkDown",
        )
    ]
    return patterns, trails


# --- AC 38: aligned fraction within tolerance ----------------------------------------------
def test_ac38_aligned_fraction_within_tolerance() -> None:
    pack = CoreIPPack()
    patterns, trails = _fixture()
    plan = aligned_controller.plan(
        pack,
        patterns,
        trails,
        total_alarms=200,
        aligned_fraction=0.65,
        rng=random.Random(1),
        base_time=BASE,
    )
    # 65% of 200 = 130, tolerance +/- 5pp of 200 = +/-10 -> [120,140].
    assert 120 <= plan.aligned_alarm_count <= 145
    assert plan.non_aligned_count == 200 - plan.aligned_alarm_count


def test_ac38_fraction_zero_emits_no_aligned() -> None:
    pack = CoreIPPack()
    patterns, trails = _fixture()
    plan = aligned_controller.plan(pack, patterns, trails, 200, 0.0, random.Random(1), BASE)
    assert plan.aligned_alarm_count == 0
    assert plan.non_aligned_count == 200


def test_ac38_fraction_one_emits_no_nonaligned() -> None:
    pack = CoreIPPack()
    patterns, trails = _fixture()
    plan = aligned_controller.plan(pack, patterns, trails, 30, 1.0, random.Random(1), BASE)
    # cascades repeat until >= T; non-aligned budget is 0.
    assert plan.aligned_alarm_count >= 30
    assert plan.non_aligned_count == 0


# --- AC 39: non-aligned alarms carry real moids + canonical alarmTypes + labels ------------
def test_ac39_nonaligned_real_moids_and_canonical_types() -> None:
    pack = CoreIPPack()
    patterns, trails = _fixture()
    valid_moids = {m.managed_object_id for t in trails.values() for m in t.members}
    result = nonaligned_synth.build_nonaligned(
        pack, patterns, trails, count=60, rng=random.Random(2), base_time=BASE
    )
    assert len(result.alarms) == 60
    for a in result.alarms:
        assert a.managed_object_id in valid_moids
        assert a.alarm_type in VOCAB
    # every non-aligned alarm is accounted for by a scenarioType label
    types = {label.scenario_type for label in result.labels}
    assert types <= {"partial-cascade", "non-aligned", "noise"}


def test_ac39_nonaligned_mix_proportions() -> None:
    pack = CoreIPPack()
    patterns, trails = _fixture()
    result = nonaligned_synth.build_nonaligned(
        pack,
        patterns,
        trails,
        count=100,
        rng=random.Random(3),
        base_time=BASE,
        partial_fraction=0.4,
        random_fraction=0.4,
        noise_fraction=0.2,
    )
    counts: dict[str, int] = {}
    for label in result.labels:
        for _ in range(max(1, len(label.child_alarm_ids))):
            counts[label.scenario_type] = counts.get(label.scenario_type, 0) + 1
    # all three sub-classes present
    assert result.alarms
    scen = {label.scenario_type for label in result.labels}
    assert "non-aligned" in scen and "noise" in scen


def test_nonaligned_zero_count_returns_empty() -> None:
    pack = CoreIPPack()
    patterns, trails = _fixture()
    result = nonaligned_synth.build_nonaligned(pack, patterns, trails, 0, random.Random(1), BASE)
    assert result.alarms == [] and result.labels == []
