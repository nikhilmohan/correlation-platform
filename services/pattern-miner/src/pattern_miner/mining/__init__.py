"""PrefixSpan mining engines + the metrics-computing miner orchestrator.

``PrefixSpanMiner`` runs PrefixSpan over the session-windowed, trail-scoped ``alarmType``-token
sequences and computes support / confidence / lift per discovered ordered subsequence — pure
sequence mining, no topology. The frequent-subsequence discovery is delegated to a swappable
:class:`PrefixSpanEngine`:

- :class:`~pattern_miner.mining.spark_engine.SparkPrefixSpanEngine` — the real Spark MLlib
  ``PrefixSpan`` (the deployed engine; container-only, Spark not installed locally).
- :class:`~pattern_miner.mining.local_engine.LocalPrefixSpanEngine` — a pure-Python reference
  PrefixSpan with identical semantics, used by the local unit gate.
"""

from __future__ import annotations

from .engine import FreqSequence, PrefixSpanEngine
from .grouped_miner import GroupedMiner, GroupPattern
from .miner import MinedSequence, PrefixSpanMiner

__all__ = [
    "FreqSequence",
    "PrefixSpanEngine",
    "MinedSequence",
    "PrefixSpanMiner",
    "GroupedMiner",
    "GroupPattern",
]
