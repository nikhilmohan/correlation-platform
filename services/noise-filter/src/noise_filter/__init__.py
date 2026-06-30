"""Noise Filter service — Phase-2 DBSCAN storm reduction over ``alarms.enriched``.

Primary mission: collapse post-dedup alarm storms from a single propagating fault into ONE
clean ``TransactionEvent`` on ``transactions.clean`` (dropping coincidental in-window noise),
so the Pattern Miner receives storm-reduced, incident-dense sequences. Secondary: subtle
outlier removal. All thresholds (``eps``/``minSamples``/``windowSize``/feature config) are
sourced from the Knowledge Service — nothing is hard-coded.
"""

from __future__ import annotations

__version__ = "0.1.0"
