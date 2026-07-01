"""Spark MLlib ``PrefixSpan`` engine (the deployed miner; container-only).

Spark/PySpark is not installed on the host (CLAUDE.md) — this module imports ``pyspark`` lazily so
the rest of the service (and the local unit gate) loads without it. It is exercised by the
``spark``-marked tests in ``local[*]`` mode inside the test/CI container, and by the deployed image.

The Miner builds one Spark ``sequences`` DataFrame row per session — an ordered list of
single-item arrays, each item an ``alarmType`` token (the canonical join token). Spark MLlib
``PrefixSpan(minSupport, maxPatternLength).findFrequentSequentialPatterns(df)`` returns
``freqSequences`` (``sequence: array<array<item>>`` + ``freq: long``); we flatten each returned
sequence (single-item element sets) back to an ordered token tuple.
"""

from __future__ import annotations

from typing import Any

from .engine import FreqSequence


class SparkPrefixSpanEngine:
    """Runs Spark MLlib ``PrefixSpan`` over the session sequences (pure sequence mining)."""

    def __init__(self, master: str = "local[*]", spark: Any | None = None) -> None:
        self._master = master
        self._spark = spark
        self._owns_spark = spark is None

    def _get_spark(self) -> Any:
        if self._spark is not None:
            return self._spark
        from pyspark.sql import SparkSession

        self._spark = (
            SparkSession.builder.master(self._master)
            .appName("pattern-miner-prefixspan")
            .config("spark.ui.enabled", "false")
            .config("spark.sql.shuffle.partitions", "1")
            .getOrCreate()
        )
        return self._spark

    def run(
        self,
        sequences: list[list[str]],
        *,
        min_support: float,
        max_pattern_length: int,
    ) -> list[FreqSequence]:
        if not sequences:
            return []
        from pyspark.ml.fpm import PrefixSpan

        spark = self._get_spark()
        # Each session -> a Spark "sequence": an array of single-item element sets.
        rows = [(idx, [[item] for item in seq]) for idx, seq in enumerate(sequences)]
        df = spark.createDataFrame(rows, ["id", "sequence"])

        prefix_span = PrefixSpan(
            minSupport=min_support,
            maxPatternLength=max_pattern_length,
            maxLocalProjDBSize=32_000_000,
            sequenceCol="sequence",
        )
        freq = prefix_span.findFrequentSequentialPatterns(df).collect()

        results: list[FreqSequence] = []
        for r in freq:
            # r["sequence"] is array<array<item>> (single-item element sets) -> flatten to tokens.
            flat = tuple(str(element[0]) for element in r["sequence"] if element)
            if flat:
                results.append(FreqSequence(sequence=flat, freq=int(r["freq"])))
        results.sort(key=lambda fs: (-fs.freq, fs.sequence))
        return results

    def close(self) -> None:
        """Stop the Spark session if this engine created it."""
        if self._spark is not None and self._owns_spark:
            self._spark.stop()
            self._spark = None
