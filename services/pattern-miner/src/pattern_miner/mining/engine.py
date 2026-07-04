"""PrefixSpan engine protocol + the frequent-sequence result type.

An engine takes a list of *sequences* — each sequence an ordered list of single-item element sets,
each item an ``alarmType`` token — plus ``minSupport`` (relative) and ``maxPatternLength``, and
returns every frequent ordered subsequence with its absolute occurrence count. This is exactly the
Spark MLlib ``PrefixSpan`` contract (``freqSequences``: ``sequence`` + ``freq``), so the local
reference and the Spark engine are interchangeable.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable


@dataclass(frozen=True)
class FreqSequence:
    """A frequent ordered subsequence + its absolute count (Spark ``freqSequences``)."""

    sequence: tuple[str, ...]
    freq: int


@runtime_checkable
class PrefixSpanEngine(Protocol):
    """Discovers frequent ordered subsequences (pure sequence mining — no topology)."""

    def run(
        self,
        sequences: list[list[str]],
        *,
        min_support: float,
        max_pattern_length: int,
    ) -> list[FreqSequence]:
        """Return every frequent ordered subsequence meeting ``min_support`` (relative freq).

        ``sequences`` is one list of ``alarmType`` tokens per session (each token a single-item
        element set). ``min_support`` is a fraction in ``(0, 1]``. ``max_pattern_length`` caps the
        length of a returned subsequence.
        """
        ...

    def reset(self) -> None:
        """[BATCH-CAP] Drop any cached backing session so the next ``run`` recreates it.

        After a driver/gateway death the cached ``SparkSession`` survives as a **dead handle** and
        every later ``run`` fails until reset. Calling ``reset()`` nulls that handle so the next
        ``run`` (via ``_get_spark``) builds a fresh session — the SparkContext self-heal. The local
        engine has no backing session and implements this as a no-op.
        """
        ...

    def is_healthy(self) -> bool:
        """[BATCH-CAP] True iff the engine is ready to run (no known-dead backing session).

        The local engine is always healthy; the Spark engine is healthy unless a death was detected
        and the session not yet recreated. Drives the ``/health`` Spark-readiness flag.
        """
        ...
