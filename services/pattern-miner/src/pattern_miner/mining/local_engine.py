"""Pure-Python reference PrefixSpan (Spark MLlib-equivalent), for the local unit gate.

Spark/PySpark is container-only (CLAUDE.md), so the local unit gate runs the algorithm-level
acceptance criteria against this reference engine. It implements the same PrefixSpan semantics as
Spark MLlib for the single-item-per-element case the Miner uses:

- Input: a list of sequences; each sequence is an ordered list of ``alarmType`` tokens (one token
  per element set — the shape the Miner builds).
- A pattern ``p`` (an ordered token list) is *contained* in a sequence ``s`` iff ``p`` is a
  subsequence of ``s`` (order preserved, gaps allowed — NOT required to be contiguous), matching
  Spark MLlib's ordered-subsequence semantics.
- ``freq`` is the number of *input sequences* that contain the pattern (sequence support, not
  occurrence multiplicity) — again matching Spark MLlib's ``freqSequences.freq``.
- A pattern is frequent iff ``freq / total_sequences >= min_support``.
- ``max_pattern_length`` caps the pattern length.

The implementation is the canonical prefix-projected-database PrefixSpan (Pei et al.), which is the
algorithm Spark MLlib implements, so results (sequence + freq) are identical.
"""

from __future__ import annotations

from .engine import FreqSequence


class LocalPrefixSpanEngine:
    """Prefix-projected-database PrefixSpan over single-item element sets (Spark-equivalent)."""

    def run(
        self,
        sequences: list[list[str]],
        *,
        min_support: float,
        max_pattern_length: int,
    ) -> list[FreqSequence]:
        total = len(sequences)
        if total == 0 or max_pattern_length < 1:
            return []
        min_count = min_support * total

        results: list[FreqSequence] = []
        # Projected DB: list of (sequence, start-offset) pointers; initially the whole DB from 0.
        initial_proj: list[tuple[list[str], int]] = [(s, 0) for s in sequences]
        self._mine(
            prefix=(),
            projected=initial_proj,
            min_count=min_count,
            max_len=max_pattern_length,
            total=total,
            results=results,
        )
        # Deterministic order: descending freq, then lexicographic sequence.
        results.sort(key=lambda fs: (-fs.freq, fs.sequence))
        return results

    def _mine(
        self,
        *,
        prefix: tuple[str, ...],
        projected: list[tuple[list[str], int]],
        min_count: float,
        max_len: int,
        total: int,
        results: list[FreqSequence],
    ) -> None:
        if len(prefix) >= max_len:
            return
        # Count, per candidate next-item, the number of DISTINCT sequences that can extend the
        # prefix (each sequence contributes at most once — sequence support).
        item_seq_count: dict[str, int] = {}
        # For each item, remember the projected DB (one entry per sequence, positioned AFTER the
        # first occurrence of the item at/after the current offset).
        item_projection: dict[str, list[tuple[list[str], int]]] = {}

        for sequence, start in projected:
            seen_in_this_sequence: set[str] = set()
            i = start
            while i < len(sequence):
                item = sequence[i]
                if item not in seen_in_this_sequence:
                    seen_in_this_sequence.add(item)
                    item_seq_count[item] = item_seq_count.get(item, 0) + 1
                    item_projection.setdefault(item, []).append((sequence, i + 1))
                i += 1

        for item, count in item_seq_count.items():
            if count < min_count:
                continue
            new_prefix = (*prefix, item)
            results.append(FreqSequence(sequence=new_prefix, freq=count))
            self._mine(
                prefix=new_prefix,
                projected=item_projection[item],
                min_count=min_count,
                max_len=max_len,
                total=total,
                results=results,
            )
