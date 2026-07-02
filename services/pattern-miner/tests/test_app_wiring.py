"""Entrypoint wiring: engine selection, Knowledge client builder, transaction grouping."""

from __future__ import annotations

from pattern_miner.app import build_codebook_client, build_engine, build_knowledge_client
from pattern_miner.assemble import group_transactions
from pattern_miner.codebook import CodebookClient
from pattern_miner.config import MiningEngineKind, Settings
from pattern_miner.mining.engine import PrefixSpanEngine
from pattern_miner.mining.local_engine import LocalPrefixSpanEngine

from .helpers import make_alarm, make_transaction


def test_build_engine_local_selects_pure_python():
    settings = Settings(MINING_ENGINE="local")
    engine = build_engine(settings)
    assert isinstance(engine, LocalPrefixSpanEngine)
    assert isinstance(engine, PrefixSpanEngine)


def test_build_engine_spark_default_is_lazy():
    """The default (spark) engine constructs without importing pyspark until run() is called."""
    settings = Settings(MINING_ENGINE="spark", SPARK_MASTER="local[*]")
    # Building the engine object must NOT require pyspark to be installed (lazy import in run()).
    engine = build_engine(settings)
    assert engine.__class__.__name__ == "SparkPrefixSpanEngine"


def test_build_knowledge_client_uses_env_config_no_hardcoded_url():
    settings = Settings(
        KNOWLEDGE_BASE_URL="http://kb.example:8080",
        KNOWLEDGE_DOMAIN="core-ip",
        KNOWLEDGE_MODEL_PARAMS_RECORD_ID="core-ip/modelParams/pattern-miner",
    )
    client = build_knowledge_client(settings)
    assert client.domain == "core-ip"
    assert client.record_id == "core-ip/modelParams/pattern-miner"
    url = client._record_url("model-params", client.record_id)
    assert url.startswith("http://kb.example:8080/domains/core-ip/model-params/")


def test_build_codebook_client_uses_env_config_no_hardcoded_url():
    settings = Settings(
        CODEBOOK_BASE_URL="http://cb.example:8080",
        CODEBOOK_RETRY_MAX="2",
        CODEBOOK_RETRY_BACKOFF_MS="10",
    )
    client = build_codebook_client(settings)
    assert isinstance(client, CodebookClient)
    # active/scenarios URLs are built off the env base URL, with no /api/v1 prefix.
    assert client._active_url() == "http://cb.example:8080/codebooks/active"
    assert client._scenarios_url("cb-1") == "http://cb.example:8080/codebooks/cb-1/scenarios"
    assert "/api/v1/" not in client._active_url()


def test_group_transactions_pools_per_trail():
    a1 = [make_alarm(alarm_type="A", raised_offset_seconds=0)]
    a2 = [make_alarm(alarm_type="B", raised_offset_seconds=1)]
    t1 = make_transaction(trail_id="trail-x", alarms=a1, snapshot_id="snap-1")
    t2 = make_transaction(trail_id="trail-x", alarms=a2, snapshot_id="snap-1")
    t3 = make_transaction(trail_id="trail-y", alarms=a1, snapshot_id="snap-2")

    batches = group_transactions([(t1, "tr"), (t2, "tr"), (t3, "tr")])
    by_trail = {b.trail_id: b for b in batches}
    assert set(by_trail) == {"trail-x", "trail-y"}
    assert len(by_trail["trail-x"].alarms) == 2  # pooled across t1 + t2
    assert len(by_trail["trail-y"].alarms) == 1


def test_settings_defaults_have_no_mining_thresholds():
    """Settings carries only wiring/operational config — never a mining threshold."""
    settings = Settings()
    assert settings.transactions_clean_topic == "transactions.clean"
    assert settings.patterns_mined_topic == "patterns.mined"
    assert settings.dlq_topic == "transactions.clean.dlq"
    assert settings.mining_engine in (MiningEngineKind.spark, MiningEngineKind.local)
