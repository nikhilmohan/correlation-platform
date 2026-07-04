"""Stage-3 mining-metric contracts (support / confidence / lift) — engine-level, group-scoped.

These exercise ``PrefixSpanMiner`` directly over a group's session sequences (the exact scope
Stage 3 runs it in): support = within-scope frequency, lift ~ 1.0 for independent co-occurrence.
The min-support filter/restore behaviour within a group is also asserted here (AC-11 unit half).
"""

from __future__ import annotations

import math

from pattern_miner.mining import PrefixSpanMiner
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine


def _miner():
    return PrefixSpanMiner(LocalPrefixSpanEngine())


def _find(mined, sequence):
    for m in mined:
        if list(m.sequence) == sequence:
            return m
    return None


def test_support_equals_within_scope_frequency():
    """support == (sessions containing the sequence) / (total sessions in scope)."""
    seqs = [["FiberFault", "LinkDown", "AdjDown"]] * 4
    mined = _miner().mine(seqs, min_support=0.5, max_pattern_length=10, max_sequence_count=1000)
    m = _find(mined, ["FiberFault", "LinkDown", "AdjDown"])
    assert m is not None
    assert math.isclose(m.support, 1.0, rel_tol=1e-9)


def test_spurious_cooccurrence_has_low_lift():
    """A frequent but statistically independent co-occurrence is scored with lift ~ 1.0."""
    seqs = [["P", "Q"]] * 25 + [["P"]] * 25 + [["Q"]] * 25 + [["R"]] * 25
    mined = _miner().mine(seqs, min_support=0.2, max_pattern_length=10, max_sequence_count=1000)
    pq = _find(mined, ["P", "Q"])
    assert pq is not None
    assert math.isclose(pq.lift, 1.0, abs_tol=0.05), pq.lift


def test_min_support_filters_and_restores_within_scope():
    """Raising minSupport above a sequence's support removes it; lowering restores it."""
    seqs = [["R1", "R2"]] * 3 + [["A"]] * 7  # R1->R2 support 0.3
    high = _miner().mine(seqs, min_support=0.5, max_pattern_length=10, max_sequence_count=1000)
    assert _find(high, ["R1", "R2"]) is None
    low = _miner().mine(seqs, min_support=0.2, max_pattern_length=10, max_sequence_count=1000)
    assert _find(low, ["R1", "R2"]) is not None
