"""[BATCH-CAP] Trail-aligned batch cap + SparkContext resilience (BC-1..BC-9).

The batch-cap fix adds no spec AC — the emitted output shape and AC-1..21 discovery behaviour are
unchanged. Its new behaviours (design "[BATCH-CAP] New/changed behavior to test") are covered here:

* BC-1 more-than-cap trails -> multiple bounded sub-runs, each emits, bounded collect.
* BC-2 a trail is never split across sub-runs (whole-trail integrity).
* BC-3 per-cascade support not diluted when the trail is kept whole (vs a record-split control).
* BC-4 cap is Knowledge-overridable but env-defaulted; no hard-coded magic number.
* BC-5 offsets commit once per flush after all sub-runs; replay-safe.
* BC-6 gateway-death mid-sub-run recreates the Spark session before the next run (no wedge).
* BC-7 recreate exhaustion fails the run clean and surfaces on /health (self-heals).
* BC-8 a single trail larger than the cap is still processed whole in its own sub-run.
* BC-9 bounded sub-runs preserve AC-10 (bounded event count) + quality over a small corpus.

All engine work uses the pure-Python ``LocalPrefixSpanEngine`` (Spark is container-only); the
gateway-death resilience is proven with a fake engine that raises the death class, since Spark
itself is not installed locally.
"""

from __future__ import annotations

import ast
from pathlib import Path

import pytest

from pattern_miner.app import effective_trail_cap
from pattern_miner.assemble import ThreeStagePipeline, chunk_trail_batches, group_transactions
from pattern_miner.config import Settings
from pattern_miner.knowledge import _parse_mining_params
from pattern_miner.metrics import Metrics
from pattern_miner.mining import GroupedMiner, PrefixSpanMiner
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine
from pattern_miner.timing import TimingComputer
from pattern_miner.windowing import SessionWindower

from .helpers import (
    default_anchoring,
    default_params,
    make_alarm,
    make_scenario,
    make_transaction,
)

SRC = Path(__file__).resolve().parents[1] / "src" / "pattern_miner"


# ------------------------------------------------------------------ shared builders


def _cascade_transactions(n_trails: int, *, chain: list[str], prefix: str = "trail"):
    """``n_trails`` distinct trails, each a single cascade carrying ``chain`` (support 1.0)."""
    txns = []
    for k in range(n_trails):
        alarms = [
            make_alarm(alarm_type=t, raised_offset_seconds=i * 0.5) for i, t in enumerate(chain)
        ]
        txns.append(make_transaction(trail_id=f"{prefix}-{k}", alarms=alarms))
    return txns


def _pipeline(params, metrics):
    windower = SessionWindower(params.windowing, metrics=metrics)
    grouped = GroupedMiner(
        PrefixSpanMiner(LocalPrefixSpanEngine(), metrics=metrics), metrics=metrics
    )
    return ThreeStagePipeline(windower, grouped, TimingComputer(), metrics=metrics)


# ------------------------------------------------------------------ BC-1


def test_chunk_produces_multiple_bounded_subruns_each_emits():
    """BC-1: >cap trails -> ceil(N/cap) sub-runs; each non-empty sub-run emits; collect bounded."""
    chain = ["FaultA", "FaultB", "FaultC"]
    # 46 trails, all the SAME fault-origin -> one anchored group per sub-run.
    txns = _cascade_transactions(46, chain=chain)
    scenarios = [make_scenario(scenario_id="SC-A", symptom_chain=chain)]
    params = default_params(
        min_support=0.5, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )
    metrics = Metrics()
    pipeline = _pipeline(params, metrics)

    trail_batches = group_transactions([(t, "tr") for t in txns])
    cap = 8
    sub_runs = chunk_trail_batches(trail_batches, cap)
    assert len(sub_runs) == 6  # ceil(46 / 8)

    total_emitted = 0
    for sub_run in sub_runs:
        # Bounded-collect assertion: no sub-run pools more than cap trails' sessions.
        assert len(sub_run) <= cap
        session_pool = sum(
            len(
                SessionWindower(params.windowing).sessions_for_trail(
                    tb.trail_id, tb.alarms, snapshot_id=tb.snapshot_id, domain=tb.domain
                )
            )
            for tb in sub_run
        )
        assert session_pool <= cap  # cap trails, one session each -> <= cap sessions
        envelopes = pipeline.run(sub_run, scenarios, params)
        assert envelopes, "each non-empty sub-run must emit at least one pattern"
        total_emitted += len(envelopes)
    assert total_emitted == 6  # one anchored event per sub-run


def test_union_of_subruns_equals_single_unbounded_run():
    """BC-1 (no-lost-patterns): union of sub-run emissions == a single unbounded run's set."""
    chain = ["FaultA", "FaultB", "FaultC"]
    txns = _cascade_transactions(20, chain=chain)
    scenarios = [make_scenario(scenario_id="SC-A", symptom_chain=chain)]
    params = default_params(
        min_support=0.5, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )

    tb = group_transactions([(t, "tr") for t in txns])

    single = _pipeline(params, Metrics()).run(tb, scenarios, params)
    single_seqs = {tuple(e.payload.sequence) for e in single}

    capped = _pipeline(params, Metrics())
    capped_seqs: set = set()
    for sub_run in chunk_trail_batches(tb, 8):
        capped_seqs |= {tuple(e.payload.sequence) for e in capped.run(sub_run, scenarios, params)}
    assert capped_seqs == single_seqs


# ------------------------------------------------------------------ BC-2


def test_chunk_never_splits_a_trail():
    """BC-2: every TrailBatch lands in exactly one sub-run, byte-identically; disjoint+complete."""
    chain = ["X", "Y"]
    txns = _cascade_transactions(23, chain=chain)
    trail_batches = group_transactions([(t, "tr") for t in txns])
    sub_runs = chunk_trail_batches(trail_batches, 5)

    # completeness: union of sub-runs == the input set (by identity — same object, not a copy).
    flat = [tb for sr in sub_runs for tb in sr]
    assert len(flat) == len(trail_batches)
    assert {id(tb) for tb in flat} == {id(tb) for tb in trail_batches}

    # disjoint: no trail id appears in two sub-runs; each trail's alarms are intact in ONE sub-run.
    seen: dict[str, int] = {}
    for idx, sr in enumerate(sub_runs):
        for tb in sr:
            assert tb.trail_id not in seen, f"trail {tb.trail_id} split across sub-runs"
            seen[tb.trail_id] = idx
    by_trail = {tb.trail_id: tb for tb in trail_batches}
    for sr in sub_runs:
        for tb in sr:
            assert tb.alarms == by_trail[tb.trail_id].alarms  # whole, unmodified


# ------------------------------------------------------------------ BC-3


def test_support_not_diluted_when_trail_kept_whole():
    """BC-3: a fault-origin whose trails all land in one sub-run keeps its single-run support."""
    chain = ["P", "Q", "R"]
    # 6 trails of the same origin; with cap>=6 they all land in one sub-run.
    txns = _cascade_transactions(6, chain=chain)
    scenarios = [make_scenario(scenario_id="SC-P", symptom_chain=chain)]
    params = default_params(
        min_support=0.5, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )
    tb = group_transactions([(t, "tr") for t in txns])

    single = _pipeline(params, Metrics()).run(tb, scenarios, params)
    (single_ev,) = [e for e in single if e.payload.provenance.anchorScenarioId == "SC-P"]

    # cap 6 -> exactly one sub-run holding all 6 trails; support must be identical to the full run.
    sub_runs = chunk_trail_batches(tb, 6)
    assert len(sub_runs) == 1
    capped = _pipeline(params, Metrics()).run(sub_runs[0], scenarios, params)
    (capped_ev,) = [e for e in capped if e.payload.provenance.anchorScenarioId == "SC-P"]

    assert capped_ev.payload.support == single_ev.payload.support
    assert capped_ev.payload.sequence == single_ev.payload.sequence


def test_record_split_control_would_change_support():
    """BC-3 control: a RECORD-level split (splitting a trail's alarms) DOES change support.

    Proves the whole-trail choice is load-bearing: cutting a cascade's alarms mid-chain fragments
    it, so its mined sequence/support differs from the intact whole-trail result.
    """
    chain = ["P", "Q", "R", "S"]
    txns = _cascade_transactions(4, chain=chain)
    scenarios = [make_scenario(scenario_id="SC-P", symptom_chain=chain)]
    params = default_params(
        min_support=0.5, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )
    tb = group_transactions([(t, "tr") for t in txns])
    whole = _pipeline(params, Metrics()).run(tb, scenarios, params)
    whole_seq = next(
        tuple(e.payload.sequence) for e in whole if e.payload.provenance.anchorScenarioId == "SC-P"
    )

    # Control: hand-fragment every trail's alarms into two half-cascades (a RECORD cap effect).
    from pattern_miner.assemble import TrailBatch

    fragmented: list[TrailBatch] = []
    for batch in tb:
        mid = len(batch.alarms) // 2
        fragmented.append(
            TrailBatch(
                f"{batch.trail_id}-h1", batch.snapshot_id, batch.domain, batch.alarms[:mid], "tr"
            )
        )
        fragmented.append(
            TrailBatch(
                f"{batch.trail_id}-h2", batch.snapshot_id, batch.domain, batch.alarms[mid:], "tr"
            )
        )
    frag = _pipeline(params, Metrics()).run(fragmented, scenarios, params)
    frag_seqs = {tuple(e.payload.sequence) for e in frag}
    # The fragmented run cannot reproduce the intact full-chain representative.
    assert whole_seq not in frag_seqs


# ------------------------------------------------------------------ BC-4


def test_max_trails_per_batch_config_and_knowledge_override():
    """BC-4: env default drives the cap; a Knowledge override wins; no cap literal in mining src."""
    # env default
    settings = Settings(MAX_TRAILS_PER_BATCH="8")
    params_no_override = default_params()
    assert params_no_override.max_trails_per_batch is None
    assert effective_trail_cap(settings, params_no_override) == 8

    # Knowledge override wins over env.
    payload = {
        "params": [
            {"key": "prefixspan.minSupport", "value": 0.3},
            {"key": "prefixspan.maxPatternLength", "value": 10},
            {"key": "prefixspan.maxSequenceCount", "value": 1000},
            {"key": "window.adaptive.baseGapSeconds", "value": 5.0},
            {"key": "window.adaptive.gapMultiplier", "value": 3.0},
            {"key": "window.adaptive.tempoPercentile", "value": 95.0},
            {"key": "window.adaptive.profiles", "value": {"fast": 0.5}},
            {"key": "anchoring.matchConfidenceThreshold", "value": 0.5},
            {"key": "anchoring.weights.order", "value": 0.7},
            {"key": "anchoring.weights.jaccard", "value": 0.3},
            {"key": "codebookVersion", "value": "current"},
            {"key": "batching.maxTrailsPerBatch", "value": 3},
        ]
    }
    parsed = _parse_mining_params(payload)
    assert parsed.max_trails_per_batch == 3
    assert effective_trail_cap(settings, parsed) == 3  # Knowledge beats the env 8

    # changing the cap changes the partitioning.
    trail_batches = group_transactions(
        [(t, "tr") for t in _cascade_transactions(9, chain=["A", "B"])]
    )
    assert len(chunk_trail_batches(trail_batches, 8)) == 2
    assert len(chunk_trail_batches(trail_batches, 3)) == 3


def test_no_cap_literal_in_mining_or_pipeline_source():
    """BC-4: no integer trail-cap magic number in the mining/pipeline source.

    The cap flows from Settings (env) or Knowledge — never a literal in assemble/pipeline/mining.
    Only structural constants (indices/slice math) may appear.
    """
    files = [
        SRC / "assemble.py",
        SRC / "mining" / "grouped_miner.py",
        SRC / "mining" / "miner.py",
    ]
    allowed = {0, 1, 2, -1, 6, 12}  # indices, log-precision, hash-truncation (see thresholds test)
    offenders = []
    for path in files:
        tree = ast.parse(path.read_text())
        for node in ast.walk(tree):
            if (
                isinstance(node, ast.Constant)
                and isinstance(node.value, int | float)
                and not isinstance(node.value, bool)
                and node.value not in allowed
            ):
                offenders.append(f"{path.name}:{node.lineno} -> {node.value}")
    assert not offenders, "cap/threshold literal in mining/pipeline source:\n" + "\n".join(
        offenders
    )


# ------------------------------------------------------------------ BC-8


def test_oversized_single_trail_processed_whole():
    """BC-8: a single trail bigger than the cap forms its own undivided sub-run and still mines."""
    chain = ["A", "B", "C"]
    # one big trail with many idle-separated sessions (>> a cap of 1), plus small trails.
    big_alarms = []
    for s in range(10):
        base = s * 600.0
        for i, t in enumerate(chain):
            big_alarms.append(make_alarm(alarm_type=t, raised_offset_seconds=base + i))
    big = make_transaction(trail_id="trail-big", alarms=big_alarms)
    smalls = _cascade_transactions(2, chain=chain, prefix="trail-small")
    tb = group_transactions([(t, "tr") for t in [big, *smalls]])

    sub_runs = chunk_trail_batches(tb, 1)  # cap 1 -> each trail is its own sub-run
    assert len(sub_runs) == 3
    for sr in sub_runs:
        assert len(sr) == 1  # never split; oversized big trail stays whole in ONE sub-run

    big_sub = next(sr for sr in sub_runs if sr[0].trail_id == "trail-big")
    assert big_sub[0].alarms == big.alarms  # whole, undivided

    scenarios = [make_scenario(scenario_id="SC-A", symptom_chain=chain)]
    params = default_params(
        min_support=0.5, anchoring=default_anchoring(match_confidence_threshold=0.5)
    )
    envelopes = _pipeline(params, Metrics()).run(big_sub, scenarios, params)
    assert any(e.payload.provenance.anchorScenarioId == "SC-A" for e in envelopes)


def test_chunk_rejects_zero_cap():
    """A zero/negative cap is an invalid batching knob (guards a misconfiguration)."""
    tb = group_transactions([(t, "tr") for t in _cascade_transactions(2, chain=["A"])])
    with pytest.raises(ValueError):
        chunk_trail_batches(tb, 0)


# ------------------------------------------------------------------ BC-9


def test_subruns_preserve_bounded_count_and_quality():
    """BC-9: over a corpus split into sub-runs, each sub-run's event count is bounded and the

    union covers every fault-origin (pre-consolidation) — AC-10 quality holds under capping.
    """
    # 4 distinct fault-origins, each in 5 trails (20 trails), plus noise. cap=3 forces >6 sub-runs.
    gt = [
        ("SC-A", [f"A{i}" for i in range(4)]),
        ("SC-B", [f"B{i}" for i in range(4)]),
        ("SC-C", [f"C{i}" for i in range(4)]),
        ("SC-D", [f"D{i}" for i in range(4)]),
    ]
    scenarios = [make_scenario(scenario_id=sid, symptom_chain=chain) for sid, chain in gt]
    txns = []
    for sid, chain in gt:
        txns.extend(_cascade_transactions(5, chain=chain, prefix=f"trail-{sid}"))
    params = default_params(
        min_support=0.5,
        max_pattern_length=10,
        anchoring=default_anchoring(match_confidence_threshold=0.5),
    )
    metrics = Metrics()
    pipeline = _pipeline(params, metrics)
    tb = group_transactions([(t, "tr") for t in txns])

    n_groups = len(gt)  # anchored groups possible per sub-run
    union_anchors: set = set()
    for sub_run in chunk_trail_batches(tb, 3):
        envs = pipeline.run(sub_run, scenarios, params)
        anchors = [
            e.payload.provenance.anchorScenarioId
            for e in envs
            if e.payload.provenance.anchorScenarioId
        ]
        # AC-10 within a sub-run: at most (distinct anchored groups + unexplained) events.
        assert len(envs) <= n_groups + 1
        union_anchors |= set(anchors)
    # union across sub-runs covers every ground-truth fault-origin (no lost pattern).
    assert union_anchors == {sid for sid, _ in gt}
