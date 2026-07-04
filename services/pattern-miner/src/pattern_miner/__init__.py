"""pattern-miner — dynamic session windowing + PrefixSpan (Spark MLlib) sequence mining.

The ML-execution service for the Pattern Learning phase (P2). It consumes trail-scoped,
DBSCAN-cleaned ``TransactionEvent``s from ``transactions.clean``, re-windows the typed
``alarms[]`` into dynamic activity/idle sessions per trail (the closing gap adapts to each burst's
tempo), runs PrefixSpan over the session-windowed ``alarmType``-token sequences, computes
support/confidence/lift, and emits one ``PatternMinedEvent`` per discovered sequence on
``patterns.mined``.

It holds **no** pattern state — no RCA, no ``patternId``, no lifecycle, no codebook
reconciliation, no explainability, no Pattern Store, no topology access. Those belong exclusively
to the Pattern Manager. This boundary is enforced by the frozen ``PatternMinedEvent`` schema
(``extra="forbid"``).
"""

from __future__ import annotations

__all__ = ["__version__"]

__version__ = "0.1.0"
