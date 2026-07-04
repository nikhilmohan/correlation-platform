"""Local PrefixSpan engine + miner metrics — algorithm-level correctness (Spark-equivalent)."""

from __future__ import annotations

import math

from pattern_miner.metrics import Metrics
from pattern_miner.mining import PrefixSpanMiner
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine


def test_prefixspan_finds_non_contiguous_ordered_subsequences():
    """Spark MLlib semantics: A->C is frequent even though C is not immediately after A."""
    engine = LocalPrefixSpanEngine()
    sequences = [["A", "B", "C"], ["A", "B", "C"], ["A", "X", "C"], ["A", "X", "C"]]
    result = {
        fs.sequence: fs.freq for fs in engine.run(sequences, min_support=0.5, max_pattern_length=10)
    }
    assert result[("A", "C")] == 4  # non-contiguous, all 4 sequences
    assert result[("A", "B", "C")] == 2


def test_prefixspan_respects_max_pattern_length():
    engine = LocalPrefixSpanEngine()
    sequences = [["A", "B", "C", "D"]] * 4
    result = engine.run(sequences, min_support=0.5, max_pattern_length=2)
    assert all(len(fs.sequence) <= 2 for fs in result)


def test_prefixspan_freq_is_sequence_support_not_occurrences():
    """A repeated item within a single sequence counts once for that sequence (sequence support)."""
    engine = LocalPrefixSpanEngine()
    sequences = [["A", "A", "A"], ["B"]]
    result = {
        fs.sequence: fs.freq for fs in engine.run(sequences, min_support=0.5, max_pattern_length=10)
    }
    assert result[("A",)] == 1  # one sequence contains A, not three


def test_empty_input_yields_no_sequences():
    assert LocalPrefixSpanEngine().run([], min_support=0.5, max_pattern_length=5) == []


def test_miner_computes_confidence_from_prefix():
    """confidence(A->B) = freq(A->B)/freq(A)."""
    miner = PrefixSpanMiner(LocalPrefixSpanEngine(), metrics=Metrics())
    # A in 4 sessions; A->B in 2 -> confidence 0.5, support 0.5.
    sequences = [["A", "B"], ["A", "B"], ["A"], ["A"]]
    mined = {
        m.sequence: m
        for m in miner.mine(
            sequences, min_support=0.4, max_pattern_length=10, max_sequence_count=100
        )
    }
    ab = mined[("A", "B")]
    assert math.isclose(ab.support, 0.5)
    assert math.isclose(ab.confidence, 0.5)  # 2/4


def test_miner_truncates_to_max_sequence_count():
    miner = PrefixSpanMiner(LocalPrefixSpanEngine())
    sequences = [["A", "B", "C", "D", "E"]] * 5
    mined = miner.mine(sequences, min_support=0.5, max_pattern_length=10, max_sequence_count=3)
    assert len(mined) == 3


def test_single_item_sequence_has_lift_one():
    miner = PrefixSpanMiner(LocalPrefixSpanEngine())
    sequences = [["A"]] * 5
    mined = {
        m.sequence: m
        for m in miner.mine(
            sequences, min_support=0.5, max_pattern_length=10, max_sequence_count=100
        )
    }
    assert math.isclose(mined[("A",)].lift, 1.0)
