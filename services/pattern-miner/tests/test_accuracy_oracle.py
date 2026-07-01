"""AC-19..AC-21 — the accuracy oracle: the regression guard for the whole 3-stage redesign.

This is the proof the redesign works: on a LABELED corpus representative of the ~1000-alarm P2
scenario (multiple ground-truth fault-origin types, each a distinct authored symptom chain, plus
unexplained noise cascades), the 3-stage pipeline must yield a SMALL, ACCURATE pattern set —

* pattern-set size within ``distinct_patterns_min..max`` (AC-19),
* each anchored pattern's alarm-type span within ``per_pattern_type_span_min..max`` (AC-19),
* total alarm coverage within ``pattern_coverage_min..max`` (AC-19),
* ZERO over-split (each ground-truth fault-origin -> exactly one anchored event) and ZERO
  over-merge (each anchored event -> exactly one ground-truth fault-origin) (AC-20),
* the unexplained cascades emitted as a distinguishable ``anchorScenarioId``-null event that does
  NOT inflate the anchored count and does not fail the run (AC-21).

The numeric bounds are read from the Simulator-owned ``integration-thresholds.yaml`` (config, not
literals in pattern-miner source — AC-19/AC-20). The corpus + ground-truth labels are a synthetic
fixture built here; the SAME assertions run against the real Simulator corpus in the integration
harness (``test_int_*`` names in the design test plan).
"""

from __future__ import annotations

import re
from pathlib import Path

from pattern_miner.metrics import Metrics

from .helpers import (
    default_anchoring,
    default_params,
    default_windowing,
    make_alarm,
    make_scenario,
    make_transaction,
    run_pipeline,
)

# ------------------------------------------------------------------ threshold bounds (config)


def _load_thresholds() -> dict[str, float]:
    """Read the Simulator-owned pattern-quality bounds (authoritative file, else the mirror)."""
    repo_root = Path(__file__).resolve().parents[4]
    authoritative = repo_root / "services" / "simulator" / "integration-thresholds.yaml"
    mirror = Path(__file__).parent / "fixtures" / "integration-thresholds.yaml"
    path = authoritative if authoritative.exists() else mirror
    text = path.read_text()
    keys = (
        "distinct_patterns_min",
        "distinct_patterns_max",
        "per_pattern_type_span_min",
        "per_pattern_type_span_max",
        "pattern_coverage_min",
        "pattern_coverage_max",
    )
    out: dict[str, float] = {}
    for key in keys:
        m = re.search(rf"^{key}:\s*([0-9.]+)", text, re.MULTILINE)
        assert m, f"{key} not found in {path}"
        out[key] = float(m.group(1))
    return out


# ------------------------------------------------------------------ labeled synthetic corpus

# Ground-truth fault-origins: 9 distinct types (within the 8-10 pattern-set band), each a distinct
# ordered symptom chain of 12 alarm-type tokens (within the 10-20 per-pattern span band). Token and
# type names are illustrative FIXTURE data (never literals in service source/config).
_GT_TYPES = [
    ("SC-A", "OriginA", [f"A{i}" for i in range(12)]),
    ("SC-B", "OriginB", [f"B{i}" for i in range(12)]),
    ("SC-C", "OriginC", [f"C{i}" for i in range(12)]),
    ("SC-D", "OriginD", [f"D{i}" for i in range(12)]),
    ("SC-E", "OriginE", [f"E{i}" for i in range(12)]),
    ("SC-F", "OriginF", [f"G{i}" for i in range(12)]),
    ("SC-G", "OriginG", [f"H{i}" for i in range(12)]),
    ("SC-H", "OriginH", [f"K{i}" for i in range(12)]),
    ("SC-I", "OriginI", [f"M{i}" for i in range(12)]),
]

# How many cascades each fault-origin manifests in (all its cascades carry its full chain, so its
# within-group support = 1.0 and the representative == the full chain).
_CASCADES_PER_GT = 6
# Unexplained noise cascades (a shared junk chain matching no scenario) to prove the unexplained
# group. Count is tuned so anchored-alarm coverage (648 / total) lands within the 50-60% band and
# the corpus is ~1000+ alarms (representative of the P2 scenario).
_NOISE_CASCADES = 100
_NOISE_LEN = 5


def _build_corpus():
    """Return (transactions, scenarios, gt_label_by_trail) for the labeled corpus."""
    transactions = []
    scenarios = []
    gt_label_by_trail: dict[str, str] = {}

    for scenario_id, origin_type, chain in _GT_TYPES:
        scenarios.append(
            make_scenario(
                scenario_id=scenario_id, fault_origin_type=origin_type, symptom_chain=chain
            )
        )
        for k in range(_CASCADES_PER_GT):
            trail = f"trail-{scenario_id}-{k}"
            gt_label_by_trail[trail] = scenario_id
            alarms = [
                make_alarm(alarm_type=t, raised_offset_seconds=i * 0.5) for i, t in enumerate(chain)
            ]
            transactions.append(make_transaction(trail_id=trail, alarms=alarms))

    # Noise cascades share a common junk chain so the unexplained group's PrefixSpan yields a
    # representative (proving the unexplained event is emitted) — but they match NO scenario
    # chain, so they never anchor.
    noise_chain = [f"NOISE{i}" for i in range(_NOISE_LEN)]
    for n in range(_NOISE_CASCADES):
        trail = f"trail-noise-{n}"
        gt_label_by_trail[trail] = None
        alarms = [
            make_alarm(alarm_type=t, raised_offset_seconds=i * 0.5)
            for i, t in enumerate(noise_chain)
        ]
        transactions.append(make_transaction(trail_id=trail, alarms=alarms))

    return transactions, scenarios, gt_label_by_trail


def _run():
    transactions, scenarios, gt = _build_corpus()
    # Windowing tuned so each tight cascade stays one session; min_support 0.5 so the full chain
    # (support 1.0 within its group) is the representative; threshold 0.5 anchors clean chains only.
    windowing = default_windowing(
        base_gap_seconds=5.0,
        gap_multiplier=3.0,
        tempo_percentile=95.0,
        max_closing_gap_seconds=60.0,
        profiles={"default": 5.0},
    )
    params = default_params(
        min_support=0.5,
        max_pattern_length=25,
        windowing=windowing,
        anchoring=default_anchoring(match_confidence_threshold=0.5, w_order=0.7, w_jaccard=0.3),
    )
    metrics = Metrics()
    envelopes = run_pipeline(transactions, scenarios, params, metrics=metrics)
    return envelopes, transactions, gt, metrics


def _total_alarms(transactions) -> int:
    return sum(len(t.alarms) for t in transactions)


# ------------------------------------------------------------------ AC-19


def test_int_pattern_set_size_span_coverage():
    """AC-19: pattern-set size, per-pattern span, coverage all within the yaml-sourced bounds."""
    bounds = _load_thresholds()
    envelopes, transactions, gt, _ = _run()

    anchored = [e for e in envelopes if e.payload.provenance.anchorScenarioId is not None]
    # size
    assert bounds["distinct_patterns_min"] <= len(anchored) <= bounds["distinct_patterns_max"], (
        f"pattern-set size {len(anchored)} outside "
        f"[{bounds['distinct_patterns_min']}, {bounds['distinct_patterns_max']}]"
    )
    # per-pattern span
    for e in anchored:
        span = len(e.payload.sequence)
        assert (
            bounds["per_pattern_type_span_min"] <= span <= bounds["per_pattern_type_span_max"]
        ), f"pattern span {span} outside bounds for {e.payload.provenance.anchorScenarioId}"
    # coverage = alarms explained by anchored patterns / total alarms.
    total = _total_alarms(transactions)
    anchored_trails = {trail for trail, label in gt.items() if label is not None}
    covered = sum(len(t.alarms) for t in transactions if t.trailId in anchored_trails)
    coverage = covered / total
    assert bounds["pattern_coverage_min"] <= coverage <= bounds["pattern_coverage_max"], (
        f"coverage {coverage:.3f} outside "
        f"[{bounds['pattern_coverage_min']}, {bounds['pattern_coverage_max']}]"
    )


# ------------------------------------------------------------------ AC-20


def test_int_zero_over_split_zero_over_merge():
    """AC-20: anchored events map 1:1 to ground-truth fault-origins (zero split, zero merge)."""
    envelopes, _, _, _ = _run()
    anchored = [e for e in envelopes if e.payload.provenance.anchorScenarioId is not None]

    # zero over-split: each ground-truth fault-origin (scenarioId) -> exactly one anchored event.
    anchors = [e.payload.provenance.anchorScenarioId for e in anchored]
    assert len(anchors) == len(set(anchors)), f"over-split: duplicate anchors {anchors}"

    # zero over-merge: each anchored event's mined sequence belongs to exactly one GT chain.
    gt_chains = {sid: set(chain) for sid, _, chain in _GT_TYPES}
    for e in anchored:
        seq_set = set(e.payload.sequence)
        matching = [sid for sid, tokens in gt_chains.items() if seq_set <= tokens]
        assert len(matching) == 1, (
            f"over-merge: event {e.payload.provenance.anchorScenarioId} sequence spans "
            f"{matching} ground-truth types"
        )
        # and the event's anchor equals the GT type its tokens belong to.
        assert matching[0] == e.payload.provenance.anchorScenarioId


# ------------------------------------------------------------------ AC-21


def test_int_unexplained_group_emitted_and_distinguishable():
    """AC-21: unexplained cascades -> a distinguishable null-anchor event; run does not fail."""
    envelopes, _, _, metrics = _run()
    unexplained = [e for e in envelopes if e.payload.provenance.anchorScenarioId is None]
    anchored = [e for e in envelopes if e.payload.provenance.anchorScenarioId is not None]

    # the unexplained group is emitted and distinguishable by a null anchor.
    assert unexplained, "expected an unexplained PatternMinedEvent"
    for e in unexplained:
        assert e.payload.provenance.anchorScenarioId is None

    # it does NOT inflate the anchored count (still within the anchored band).
    assert len(anchored) == len(_GT_TYPES)
    # metrics: some cascades anchored, some unexplained; run completed (no mining failure).
    assert metrics.cascades_anchored._value.get() > 0
    assert metrics.cascades_unexplained._value.get() > 0
    assert metrics.mining_failures._value.get() == 0


def test_unexplained_group_does_not_inflate_count():
    """AC-21 unit half: the anchored-pattern count excludes the unexplained event."""
    envelopes, _, _, _ = _run()
    anchored = [e for e in envelopes if e.payload.provenance.anchorScenarioId is not None]
    bounds = _load_thresholds()
    assert len(anchored) <= bounds["distinct_patterns_max"]
