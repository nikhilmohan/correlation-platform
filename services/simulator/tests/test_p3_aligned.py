"""P3 aligned-cascade synthesis + placement tests (AC 35, 36, 37).

Drives ``aligned_synth.build_cascade`` and the pack placement affinity: sequence->member mapping,
timing/session-window pacing, root-cause tagging, optional-element handling, and the any-member
fallback (all on real trail members).
"""

from __future__ import annotations

import random
from datetime import UTC, datetime

from simulator.domains.coreip import p3_placement
from simulator.domains.coreip.pack import CoreIPPack
from simulator.synth import aligned_synth
from simulator.synth.models import PatternView, TrailDetail, TrailMember

BASE = datetime(2026, 1, 1, tzinfo=UTC)


def _trail(trail_id: str, members: list[tuple[str, str]]) -> TrailDetail:
    return TrailDetail(
        trail_id=trail_id,
        members=tuple(TrailMember(m, ot) for m, ot in members),
    )


def _pattern(seq: list[tuple[str, bool]], root: str, **timing) -> PatternView:
    from simulator.synth.models import SequenceElement, SessionWindow, Timing

    return PatternView(
        pattern_id="pat-01",
        trail_id="trail-A",
        sequence=tuple(SequenceElement(at, opt) for at, opt in seq),
        root_cause_alarm_type=root,
        timing=Timing(
            median_inter_arrival_ms=timing.get("median", 500.0),
            max_inter_arrival_ms=timing.get("max", 1500.0),
            stddev_inter_arrival_ms=timing.get("stddev", 0.0),
        ),
        session_window=SessionWindow(window_ms=timing.get("window", 6000)),
    )


# --- AC 36 / 37: sequence -> affine member, timing, root cause -----------------------------
def test_ac36_cascade_follows_sequence_and_places_on_affine_members() -> None:
    pack = CoreIPPack()
    trail = _trail("trail-A", [("IPLink:ip-7", "IPLink"), ("IGPAdjacency:adj-3", "IGPAdjacency")])
    pattern = _pattern([("IPLinkDown", False), ("ISISAdjacencyDown", False)], root="IPLinkDown")
    cascade = aligned_synth.build_cascade(pack, pattern, trail, random.Random(7), BASE)
    assert [a.alarm_type for a in cascade.alarms] == ["IPLinkDown", "ISISAdjacencyDown"]
    assert cascade.alarms[0].managed_object_id == "IPLink:ip-7"
    assert cascade.alarms[1].managed_object_id == "IGPAdjacency:adj-3"


def test_ac36_inter_arrival_within_max_bound() -> None:
    pack = CoreIPPack()
    trail = _trail("trail-A", [("IPLink:ip-7", "IPLink"), ("IGPAdjacency:adj-3", "IGPAdjacency")])
    pattern = _pattern(
        [("IPLinkDown", False), ("ISISAdjacencyDown", False)],
        root="IPLinkDown",
        median=500,
        max=1500,
        stddev=250,
    )
    for seed in range(50):
        cascade = aligned_synth.build_cascade(pack, pattern, trail, random.Random(seed), BASE)
        gap_ms = (cascade.alarms[1].raised_at - cascade.alarms[0].raised_at).total_seconds() * 1000
        assert 0.0 <= gap_ms <= 1500.0


def test_ac37_root_cause_label_matches_pattern_root() -> None:
    pack = CoreIPPack()
    trail = _trail("trail-A", [("IPLink:ip-7", "IPLink"), ("IGPAdjacency:adj-3", "IGPAdjacency")])
    pattern = _pattern([("IPLinkDown", False), ("ISISAdjacencyDown", False)], root="IPLinkDown")
    cascade = aligned_synth.build_cascade(pack, pattern, trail, random.Random(1), BASE)
    root = next(a for a in cascade.alarms if a.is_root)
    assert root.alarm_type == "IPLinkDown"
    assert cascade.label.root_cause_alarm_type == "IPLinkDown"
    assert cascade.label.root_cause_alarm_id == root.alarm_id
    assert set(cascade.label.child_alarm_ids) == {
        a.alarm_id for a in cascade.alarms if not a.is_root
    }


def test_ac36_cascade_fits_inside_session_window() -> None:
    pack = CoreIPPack()
    trail = _trail(
        "trail-A",
        [("IPLink:ip-7", "IPLink"), ("IGPAdjacency:adj-3", "IGPAdjacency"), ("LSP:lsp-9", "LSP")],
    )
    # Wide gaps but a tight window -> must compress to fit.
    pattern = _pattern(
        [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", False)],
        root="IPLinkDown",
        median=5000,
        max=9000,
        stddev=0,
        window=2000,
    )
    cascade = aligned_synth.build_cascade(pack, pattern, trail, random.Random(3), BASE)
    span_ms = (cascade.alarms[-1].raised_at - cascade.alarms[0].raised_at).total_seconds() * 1000
    assert span_ms <= 2000.0


# --- AC 35: fallback places on a real trail member (no affine objectType) ------------------
def test_ac35_fallback_places_on_any_real_member() -> None:
    pack = CoreIPPack()
    # Trail has no IGPAdjacency member -> ISISAdjacencyDown falls back to any member.
    trail = _trail("trail-A", [("IPLink:ip-7", "IPLink")])
    pattern = _pattern([("IPLinkDown", False), ("ISISAdjacencyDown", False)], root="IPLinkDown")
    cascade = aligned_synth.build_cascade(pack, pattern, trail, random.Random(5), BASE)
    member_moids = {"IPLink:ip-7"}
    assert all(a.managed_object_id in member_moids for a in cascade.alarms)


def test_optional_element_omitted_at_zero_prob() -> None:
    pack = CoreIPPack()
    trail = _trail(
        "trail-A",
        [("IPLink:ip-7", "IPLink"), ("IGPAdjacency:adj-3", "IGPAdjacency"), ("LSP:lsp-9", "LSP")],
    )
    pattern = _pattern(
        [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", True)],
        root="IPLinkDown",
    )
    cascade = aligned_synth.build_cascade(
        pack, pattern, trail, random.Random(9), BASE, optional_include_prob=0.0
    )
    assert "LSPDown" not in [a.alarm_type for a in cascade.alarms]


def test_optional_element_included_by_default() -> None:
    pack = CoreIPPack()
    trail = _trail(
        "trail-A",
        [("IPLink:ip-7", "IPLink"), ("IGPAdjacency:adj-3", "IGPAdjacency"), ("LSP:lsp-9", "LSP")],
    )
    pattern = _pattern(
        [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", True)],
        root="IPLinkDown",
    )
    cascade = aligned_synth.build_cascade(
        pack, pattern, trail, random.Random(9), BASE, optional_include_prob=1.0
    )
    assert "LSPDown" in [a.alarm_type for a in cascade.alarms]


def test_root_forced_in_when_optional_and_present() -> None:
    """If the root type element is marked optional, it is never dropped."""
    pack = CoreIPPack()
    trail = _trail("trail-A", [("IPLink:ip-7", "IPLink")])
    pattern = _pattern([("IPLinkDown", True)], root="IPLinkDown")
    cascade = aligned_synth.build_cascade(
        pack, pattern, trail, random.Random(1), BASE, optional_include_prob=0.0
    )
    assert any(a.is_root and a.alarm_type == "IPLinkDown" for a in cascade.alarms)


# --- placement table (OQ-P3-1) is pack-authored -------------------------------------------
def test_placement_affinity_covers_known_alarm_types() -> None:
    affinity = CoreIPPack().placement_affinity()
    assert affinity["IPLinkDown"] == "IPLink"
    assert affinity["ISISAdjacencyDown"] == "IGPAdjacency"
    assert affinity["FiberFault"] == "FiberSpan"
    assert affinity["LSPDown"] == "LSP"
    assert p3_placement.affine_object_type("PortDown") == "Port"
    assert p3_placement.affine_object_type("NotAThing") is None
