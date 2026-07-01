"""Real Spark MLlib PrefixSpan engine in local[*] — CONTAINER-ONLY (marker: spark).

Spark/PySpark is not installed on the host (CLAUDE.md), so this test is deselected by the local
unit gate and runs INSIDE the test/CI container after ``pip install .[spark]`` (a JRE is present in
the image). It proves the deployed :class:`SparkPrefixSpanEngine` discovers the same frequent
ordered ``alarmType`` sequences the pure-Python reference does — i.e. the engine toggle is a
runtime choice, not a behaviour change.

Run in-container:  ``pytest -m spark``
"""

from __future__ import annotations

import pytest

pytestmark = pytest.mark.spark

pyspark = pytest.importorskip("pyspark")

from pyspark.sql import SparkSession  # noqa: E402

from pattern_miner.assemble import PatternAssembler, group_transactions  # noqa: E402
from pattern_miner.metrics import Metrics  # noqa: E402
from pattern_miner.mining import PrefixSpanMiner  # noqa: E402
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine  # noqa: E402
from pattern_miner.mining.spark_engine import SparkPrefixSpanEngine  # noqa: E402
from pattern_miner.timing import TimingComputer  # noqa: E402
from pattern_miner.windowing import SessionWindower  # noqa: E402

from .helpers import default_params, default_windowing, make_alarm, make_transaction  # noqa: E402

FIBER_CUT = ["FiberFault", "LinkDown", "AdjDown"]


@pytest.fixture(scope="module")
def spark():
    session = (
        SparkSession.builder.master("local[*]")
        .appName("pattern-miner-test")
        .config("spark.ui.enabled", "false")
        .config("spark.sql.shuffle.partitions", "1")
        .getOrCreate()
    )
    yield session
    session.stop()


def test_spark_prefixspan_matches_local_reference(spark):
    """Spark MLlib PrefixSpan returns the same frequent sequences as the pure-Python engine."""
    sequences = [FIBER_CUT, FIBER_CUT, FIBER_CUT, ["FiberFault", "LinkDown"], ["X", "Y"]]
    spark_engine = SparkPrefixSpanEngine(spark=spark)
    local_engine = LocalPrefixSpanEngine()

    spark_res = {
        fs.sequence: fs.freq
        for fs in spark_engine.run(sequences, min_support=0.4, max_pattern_length=10)
    }
    local_res = {
        fs.sequence: fs.freq
        for fs in local_engine.run(sequences, min_support=0.4, max_pattern_length=10)
    }
    assert spark_res == local_res
    assert spark_res[tuple(FIBER_CUT)] == 3


def test_spark_fiber_cut_mined_end_to_end_with_provenance(spark):
    """Full window->Spark PrefixSpan->assemble path emits the fiber-cut PatternMinedEvent."""
    metrics = Metrics()
    windower = SessionWindower(default_windowing(), metrics=metrics)
    miner = PrefixSpanMiner(SparkPrefixSpanEngine(spark=spark), metrics=metrics)
    assembler = PatternAssembler(windower, miner, TimingComputer(), metrics=metrics)

    # ONE trail, several idle-separated sessions all carrying the fiber-cut cascade.
    all_alarms = []
    for s in range(4):
        base = s * 300.0
        for i, t in enumerate(FIBER_CUT):
            all_alarms.append(make_alarm(alarm_type=t, raised_offset_seconds=base + i))
    txn = make_transaction(trail_id="trail-fc", alarms=all_alarms)
    batch = group_transactions([(txn, "trace-1")])[0]

    envelopes = assembler.mine_batch(batch, default_params(min_support=0.5))
    fc = next((e for e in envelopes if e.payload.sequence == FIBER_CUT), None)
    assert fc is not None
    assert fc.payload.provenance.codebookVersion == "current"
    assert fc.payload.provenance.sourceWindowId.startswith("sw:trail-fc:")
