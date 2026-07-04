"""PrefixSpan miner orchestrator: run the engine + compute support / confidence / lift.

Metrics (spec §Metrics, criteria 1, 2, 3):

- ``support``   = (sessions containing the ordered sequence) / (total sessions in scope) — the
  observed frequency (criterion 1 asserts this equals the observed frequency within tolerance).
- ``confidence`` = P(full sequence | its longest proper prefix), from the engine frequency counts.
  For a length-1 sequence there is no proper prefix; confidence = its support.
- ``lift``      = observed joint support / product of the constituent single-item marginal
  supports (the independence baseline). A spurious high-support co-occurrence of independent items
  yields ``lift`` near 1.0 (criterion 2).

``conviction`` is deliberately NOT computed (out of MVP; not in the frozen ``PatternMinedEvent``
schema). No topology graph is consulted — pure sequence mining.
"""

from __future__ import annotations

from dataclasses import dataclass

from .engine import FreqSequence, PrefixSpanEngine


@dataclass(frozen=True)
class MinedSequence:
    """A discovered ordered ``alarmType`` sequence with its MVP metrics."""

    sequence: tuple[str, ...]
    support: float
    confidence: float
    lift: float
    freq: int


class PrefixSpanMiner:
    """Runs a :class:`PrefixSpanEngine` and computes support/confidence/lift per sequence."""

    def __init__(self, engine: PrefixSpanEngine, *, metrics=None) -> None:
        self._engine = engine
        self._metrics = metrics

    def mine(
        self,
        sequences: list[list[str]],
        *,
        min_support: float,
        max_pattern_length: int,
        max_sequence_count: int,
    ) -> list[MinedSequence]:
        """Discover frequent ordered subsequences and compute their MVP metrics.

        Returns at most ``max_sequence_count`` sequences ordered by descending support (then
        lexicographically), truncated after metric computation.
        """
        total = len(sequences)
        if total == 0:
            return []

        freq_sequences = self._engine.run(
            sequences, min_support=min_support, max_pattern_length=max_pattern_length
        )
        if not freq_sequences:
            return []

        freq_by_seq: dict[tuple[str, ...], int] = {fs.sequence: fs.freq for fs in freq_sequences}
        # Single-item marginal counts for lift. A single-item frequent sequence's freq already IS
        # its marginal; for items not frequent enough to be returned, count directly (they still
        # participate in a longer frequent sequence's lift baseline).
        marginal_count = self._marginal_counts(sequences, freq_by_seq)

        mined: list[MinedSequence] = []
        for fs in freq_sequences:
            support = fs.freq / total
            confidence = self._confidence(fs, freq_by_seq)
            lift = self._lift(fs, support, marginal_count, total)
            mined.append(
                MinedSequence(
                    sequence=fs.sequence,
                    support=support,
                    confidence=confidence,
                    lift=lift,
                    freq=fs.freq,
                )
            )

        mined.sort(key=lambda m: (-m.support, m.sequence))
        if max_sequence_count > 0:
            mined = mined[:max_sequence_count]
        if self._metrics is not None:
            self._metrics.sequences_mined.inc(len(mined))
        return mined

    @staticmethod
    def _confidence(fs: FreqSequence, freq_by_seq: dict[tuple[str, ...], int]) -> float:
        """P(sequence | longest proper prefix). Length-1 -> confidence == support-equivalent 1.0.

        For a length-1 sequence there is no proper prefix, so the conditional collapses to the
        prior; we report ``1.0`` (the sequence given the empty prefix is certain over the sequences
        that contain it). For longer sequences, ``freq(seq) / freq(prefix)``.
        """
        if len(fs.sequence) <= 1:
            return 1.0
        prefix = fs.sequence[:-1]
        prefix_freq = freq_by_seq.get(prefix)
        if not prefix_freq:
            return 0.0
        return fs.freq / prefix_freq

    @staticmethod
    def _marginal_counts(
        sequences: list[list[str]], freq_by_seq: dict[tuple[str, ...], int]
    ) -> dict[str, int]:
        """Per-item count of sequences containing that item (single-item marginal support)."""
        counts: dict[str, int] = {}
        for seq in sequences:
            for item in set(seq):
                counts[item] = counts.get(item, 0) + 1
        # Prefer the engine's own single-item freq where present (identical, but keeps consistency).
        for token, freq in freq_by_seq.items():
            if len(token) == 1:
                counts[token[0]] = freq
        return counts

    @staticmethod
    def _lift(
        fs: FreqSequence,
        support: float,
        marginal_count: dict[str, int],
        total: int,
    ) -> float:
        """observed joint support / product of constituent single-item marginal supports.

        A single-item sequence has lift 1.0 by definition (it is its own marginal). For a
        multi-item sequence, the independence baseline is the product of each distinct constituent
        item's marginal support; ``lift`` near 1.0 flags a spurious independent co-occurrence
        (criterion 2).
        """
        distinct_items = list(dict.fromkeys(fs.sequence))
        if len(distinct_items) <= 1:
            return 1.0
        baseline = 1.0
        for item in distinct_items:
            marginal = marginal_count.get(item, 0) / total
            if marginal <= 0.0:
                return 0.0
            baseline *= marginal
        if baseline <= 0.0:
            return 0.0
        return support / baseline
