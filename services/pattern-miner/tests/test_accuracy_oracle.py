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
    group_sessions,
    make_alarm,
    make_scenario,
    make_transaction,
    run_pipeline,
    window_sessions,
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


def _build_params():
    # Windowing tuned so each tight cascade stays one session; min_support 0.5 so the full chain
    # (support 1.0 within its group) is the representative; threshold 0.5 anchors clean chains only.
    windowing = default_windowing(
        base_gap_seconds=5.0,
        gap_multiplier=3.0,
        tempo_percentile=95.0,
        max_closing_gap_seconds=60.0,
        profiles={"default": 5.0},
    )
    return default_params(
        min_support=0.5,
        max_pattern_length=25,
        windowing=windowing,
        anchoring=default_anchoring(match_confidence_threshold=0.5, w_order=0.7, w_jaccard=0.3),
    )


def _run():
    transactions, scenarios, gt = _build_corpus()
    params = _build_params()
    metrics = Metrics()
    envelopes = run_pipeline(transactions, scenarios, params, metrics=metrics)
    return envelopes, transactions, gt, metrics


def _anchored_groups(transactions, scenarios, params):
    """Re-derive the pipeline's OWN Stage-1 (windowing) + Stage-2 (anchoring) result.

    Runs the exact same windower + AnchorGrouper the mining pipeline uses, so the returned groups
    are the pipeline's actual anchoring decision — NOT the ground-truth labels. Coverage computed
    from these groups is load-bearing: if anchoring regressed (everything -> null, or all-merged),
    the non-null-anchor alarm count moves and the coverage assertion fails.
    """
    sessions = window_sessions(transactions, params)
    return group_sessions(sessions, scenarios, params)


def _total_alarms(transactions) -> int:
    return sum(len(t.alarms) for t in transactions)


# ------------------------------------------------------------------ AC-19


def test_int_pattern_set_size_span_coverage():
    """AC-19: pattern-set size, per-pattern span, coverage all within the yaml-sourced bounds."""
    bounds = _load_thresholds()
    transactions, scenarios, _ = _build_corpus()
    params = _build_params()
    metrics = Metrics()
    envelopes = run_pipeline(transactions, scenarios, params, metrics=metrics)

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
    # coverage = alarms the PIPELINE actually anchored / total input alarms.
    # Derived from the pipeline's OWN Stage-1 windowing + Stage-2 anchoring (same windower +
    # AnchorGrouper the mining run uses) — NOT the ground-truth labels. So if anchoring regressed
    # (all cascades -> null, or all-merged into one over-broad group) this number moves and fails.
    groups = _anchored_groups(transactions, scenarios, params)
    total = _total_alarms(transactions)
    covered = sum(len(s.alarms) for g in groups if not g.is_unexplained for s in g.sessions)
    coverage = covered / total
    # cross-check the derived groups agree with what the pipeline actually emitted as anchored.
    emitted_anchors = {e.payload.provenance.anchorScenarioId for e in anchored}
    grouped_anchors = {g.scenario_id for g in groups if not g.is_unexplained}
    assert (
        emitted_anchors == grouped_anchors
    ), f"anchored emissions {emitted_anchors} != anchored groups {grouped_anchors}"
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


# ------------------------------------------------------------------ [BATCH-CAP] BC-9 with capping


def _consolidate_by_anchor(envelopes):
    """Downstream anchor-identity consolidation (pattern-manager's job), applied in-test.

    The batch cap can emit the SAME anchorScenarioId in more than one sub-run (if a fault-origin's
    trails spread across chunks). Consolidation collapses those into one pattern per anchor — this
    is what makes the accuracy oracle hold WITH capping on (design [BATCH-CAP], BC-9). Unexplained
    (null-anchor) events are consolidated together too. Representative sequence = the longest
    emitted for that anchor (matches the single-run representative for the labeled corpus).
    """
    by_anchor: dict = {}
    for e in envelopes:
        key = e.payload.provenance.anchorScenarioId
        prev = by_anchor.get(key)
        if prev is None or len(e.payload.sequence) > len(prev.payload.sequence):
            by_anchor[key] = e
    return list(by_anchor.values())


def _run_capped(cap: int):
    """Run the SAME labeled oracle corpus, but chunked into sub-runs of at-most ``cap`` trails."""
    transactions, scenarios, _ = _build_corpus()
    params = _build_params()
    metrics = Metrics()

    from pattern_miner.assemble import chunk_trail_batches, group_transactions

    from .helpers import build_pipeline

    pipeline = build_pipeline(windowing=params.windowing, metrics=metrics)
    trail_batches = group_transactions([(t, "trace-1") for t in transactions])
    sub_runs = chunk_trail_batches(trail_batches, cap)
    assert len(sub_runs) > 1, "cap must force MULTIPLE sub-runs to exercise the batch-cap path"

    all_envelopes = []
    for sub_run in sub_runs:
        all_envelopes.extend(pipeline.run(sub_run, scenarios, params))
    return all_envelopes, metrics


def test_accuracy_oracle_preserved_with_capping():
    """BC-9: with a small cap forcing MULTIPLE sub-runs, after consolidation the pattern set is

    identical to the single-run oracle result — same anchored set, same spans, zero over-split /
    over-merge, coverage in band. Capping must NOT change AC-19/AC-20 quality.
    """
    bounds = _load_thresholds()
    # cap 5 with 6 trails/origin + 100 noise trails -> many sub-runs, and each origin's 6 trails
    # straddle a chunk boundary (so the same anchor is emitted in >1 sub-run pre-consolidation).
    envelopes, metrics = _run_capped(cap=5)

    # Pre-consolidation: at least one anchor appears in more than one sub-run (proves the cap split
    # a fault-origin's trails — the case consolidation must handle).
    raw_anchors = [
        e.payload.provenance.anchorScenarioId
        for e in envelopes
        if e.payload.provenance.anchorScenarioId
    ]
    assert len(raw_anchors) > len(set(raw_anchors)), "expected a repeated anchor across sub-runs"

    consolidated = _consolidate_by_anchor(envelopes)
    anchored = [e for e in consolidated if e.payload.provenance.anchorScenarioId is not None]

    # AC-19 size + span after consolidation == the single-run oracle bands.
    assert bounds["distinct_patterns_min"] <= len(anchored) <= bounds["distinct_patterns_max"]
    for e in anchored:
        span = len(e.payload.sequence)
        assert bounds["per_pattern_type_span_min"] <= span <= bounds["per_pattern_type_span_max"]

    # AC-20 zero over-split (1:1 anchors) + zero over-merge (each seq in exactly one GT chain).
    anchors = [e.payload.provenance.anchorScenarioId for e in anchored]
    assert len(anchors) == len(set(anchors)), f"over-split after consolidation: {anchors}"
    gt_chains = {sid: set(chain) for sid, _, chain in _GT_TYPES}
    for e in anchored:
        seq_set = set(e.payload.sequence)
        matching = [sid for sid, tokens in gt_chains.items() if seq_set <= tokens]
        assert (
            len(matching) == 1
        ), f"over-merge: {e.payload.provenance.anchorScenarioId} -> {matching}"
        assert matching[0] == e.payload.provenance.anchorScenarioId

    # the anchored set equals the full ground-truth set (no fault-origin lost by chunking).
    assert set(anchors) == {sid for sid, _, _ in _GT_TYPES}
    # the run completed with no mining failure and the unexplained group survived consolidation.
    assert metrics.mining_failures._value.get() == 0
    assert any(e.payload.provenance.anchorScenarioId is None for e in consolidated)


def test_capped_matches_uncapped_anchored_set():
    """BC-9: the consolidated capped pattern set == the uncapped single-run anchored set."""
    uncapped, _, _, _ = _run()
    uncapped_anchored = {
        e.payload.provenance.anchorScenarioId
        for e in uncapped
        if e.payload.provenance.anchorScenarioId is not None
    }
    uncapped_seqs = {
        tuple(e.payload.sequence)
        for e in uncapped
        if e.payload.provenance.anchorScenarioId is not None
    }

    capped_envelopes, _ = _run_capped(cap=5)
    capped_anchored = _consolidate_by_anchor(capped_envelopes)
    capped_anchor_ids = {
        e.payload.provenance.anchorScenarioId
        for e in capped_anchored
        if e.payload.provenance.anchorScenarioId is not None
    }
    capped_seqs = {
        tuple(e.payload.sequence)
        for e in capped_anchored
        if e.payload.provenance.anchorScenarioId is not None
    }
    assert capped_anchor_ids == uncapped_anchored
    assert capped_seqs == uncapped_seqs  # same representatives -> no over-split/over-merge
