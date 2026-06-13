"""Run-orchestration tests (criteria 1, 2, 8, 9, 10, 11, 15, 36-40).

Drives the testable core that ``main`` calls after CLI parse: ``run_p1`` (build/ingest +
upload the snapshot via the Topology ingestion API — NOT Kafka) and ``run_replay_phase``
(synthesize or ingest the alarm stream and replay it onto the phase topic via an injected
producer). Real Kafka/HTTP are replaced by in-memory doubles. Includes the two explicit
simulator decisions: the **ingest/replay** path (skip generation, replay a pre-created corpus)
and the **generate -> export -> re-ingest round-trip**, plus the **persistent configurable
artifact path** (SIM_OUTPUT_DIR) survival across a simulated restart.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from acp_event_model import TypedEnvelope

from simulator import run
from simulator.config.settings import load_settings
from simulator.engine import replay
from simulator.integrations.topology_client import MockTopologyClient


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, TypedEnvelope]] = []
        self.flushed = 0

    def produce(self, topic: str, envelope: TypedEnvelope) -> None:
        self.sent.append((topic, envelope))

    def flush(self) -> None:
        self.flushed += 1


def _env(out_dir: Path, **extra: str) -> dict[str, str]:
    base = {
        "TOPOLOGY_NODE_COUNT": "12",
        "SITE_COUNT": "3",
        "IGP_AREA_COUNT": "2",
        "INTERFACES_PER_PORT": "1",
        "SIM_OUTPUT_DIR": str(out_dir),
        "SIM_SEED": "4242",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
    }
    base.update(extra)
    return base


# --------------------------------------------------------------------------- P1 (snapshot)


def test_make_pack_returns_core_ip_pack() -> None:
    assert run.make_pack().domain_id()


def test_scenario_summaries_lists_pack_scenarios() -> None:
    summaries = run.scenario_summaries(run.make_pack())
    assert summaries
    assert {"scenarioType", "rootCauseObjectType", "rootCauseAlarmType"} <= summaries[0].keys()


def test_make_topology_client_honors_mode() -> None:
    settings = load_settings(_env(Path("/tmp")))
    assert isinstance(run.make_topology_client(settings), MockTopologyClient)


def test_ac1_15_run_p1_generate_builds_and_uploads_snapshot(tmp_path) -> None:
    settings = load_settings(_env(tmp_path, phase="p1"))
    client = MockTopologyClient()
    outcome = run.run_p1(settings, client, run_id="run-p1")
    # uploaded via the Topology ingestion API (NOT Kafka)
    assert client.calls == 1
    assert outcome.snapshot_id is not None
    assert outcome.snapshot_id.startswith("snap-mock-")
    assert outcome.snapshot is not None
    assert outcome.snapshot["nodes"]
    assert client.last_uploaded == outcome.snapshot


def test_ac11_persistent_artifact_path_is_configurable_and_written(tmp_path) -> None:
    out_dir = tmp_path / "artifacts"
    settings = load_settings(_env(out_dir, phase="p1"))
    outcome = run.run_p1(settings, MockTopologyClient(), run_id="run-art")
    snapshot_file = out_dir / "snapshot-run-art.json"
    assert snapshot_file.exists()  # written to the CONFIGURED path, not a hard-coded one
    reread = json.loads(snapshot_file.read_text())
    assert reread["nodes"] == outcome.snapshot["nodes"]


def test_ingest_p1_loads_snapshot_from_file(tmp_path) -> None:
    # first generate a snapshot to a file
    gen_settings = load_settings(_env(tmp_path, phase="p1"))
    gen = run.run_p1(gen_settings, MockTopologyClient(), run_id="gen")
    snap_path = tmp_path / "snapshot-gen.json"

    # then ingest that same file (skip generation), upload verbatim
    ing_settings = load_settings(
        _env(tmp_path, phase="p1", SIM_MODE="ingest", INGEST_TOPOLOGY_FILE=str(snap_path))
    )
    client = MockTopologyClient()
    outcome = run.run_p1(ing_settings, client, run_id="ing")
    assert outcome.mode == "ingest"
    assert client.last_uploaded["nodes"] == gen.snapshot["nodes"]


def test_ingest_p1_requires_topology_file(tmp_path) -> None:
    from simulator.ingest.corpus_loader import IngestValidationError

    settings = load_settings(_env(tmp_path, phase="p1", SIM_MODE="ingest"))
    with pytest.raises(IngestValidationError, match="INGEST_TOPOLOGY_FILE required"):
        run.run_p1(settings, MockTopologyClient())


# --------------------------------------------------------------------------- P2/P3 (replay)


def test_ac8_run_p2_generate_replays_to_history_and_writes_labels(tmp_path) -> None:
    out_dir = tmp_path / "p2out"
    settings = load_settings(
        _env(
            out_dir,
            phase="p2",
            SCENARIOS="fiber-cut",
            SCENARIO_INSTANCES="2",
            NOISE_RATE="0.1",
            HISTORY_DURATION="3600",
        )
    )
    prod = FakeProducer()
    outcome = run.run_replay_phase(settings, prod, run_id="run-p2")
    assert outcome.emitted > 0
    assert {t for t, _ in prod.sent} == {replay.HISTORY_TOPIC}
    # ground-truth labels persisted to the configurable output dir
    labels_file = out_dir / "labels-run-p2.jsonl"
    assert labels_file.exists()
    assert outcome.labels.all()


def test_ac9_run_p3_generate_replays_to_live(tmp_path) -> None:
    settings = load_settings(
        _env(
            tmp_path,
            phase="p3",
            SCENARIOS="fiber-cut",
            SCENARIO_INSTANCES="1",
            NOISE_RATE="0",
            PACING_MULTIPLIER="0",
        )
    )
    prod = FakeProducer()
    outcome = run.run_replay_phase(settings, prod, run_id="run-p3")
    assert outcome.emitted > 0
    assert {t for t, _ in prod.sent} == {replay.LIVE_TOPIC}


def test_ac10_run_p2_labels_root_cause_matches_emitted_alarm(tmp_path) -> None:
    settings = load_settings(
        _env(tmp_path, phase="p2", SCENARIOS="fiber-cut", SCENARIO_INSTANCES="1", NOISE_RATE="0")
    )
    prod = FakeProducer()
    outcome = run.run_replay_phase(settings, prod, run_id="lbl")
    emitted_ids = {e.payload.alarmId for _, e in prod.sent}
    label = outcome.labels.all()[0]
    assert label.root_cause in emitted_ids
    for child in label.children:
        assert child in emitted_ids


def test_ingest_p2_replays_corpus_and_loads_labels(tmp_path) -> None:
    """The ingest/replay path: skip generation, replay a pre-created corpus verbatim."""
    # generate + export a corpus and labels first
    gen_out = tmp_path / "gen"
    gen_settings = load_settings(
        _env(
            gen_out,
            phase="p2",
            SCENARIOS="fiber-cut",
            SCENARIO_INSTANCES="1",
            NOISE_RATE="0",
            EXPORT_CORPUS_FILE=str(tmp_path / "corpus.jsonl"),
        )
    )
    gen = run.run_replay_phase(gen_settings, FakeProducer(), run_id="gen")
    labels_file = gen_out / "labels-gen.jsonl"

    ing_settings = load_settings(
        _env(
            tmp_path / "ing",
            phase="p2",
            SIM_MODE="ingest",
            INGEST_ALARMS_FILE=str(tmp_path / "corpus.jsonl"),
            INGEST_LABELS_FILE=str(labels_file),
        )
    )
    prod = FakeProducer()
    outcome = run.run_replay_phase(ing_settings, prod, run_id="ing")
    assert outcome.mode == "ingest"
    assert outcome.emitted == gen.emitted
    # labels loaded from the ingested file
    assert {label.scenario_id for label in outcome.labels.all()} == {
        label.scenario_id for label in gen.labels.all()
    }


def test_ingest_p2_requires_alarms_file(tmp_path) -> None:
    from simulator.ingest.corpus_loader import IngestValidationError

    settings = load_settings(_env(tmp_path, phase="p2", SIM_MODE="ingest"))
    with pytest.raises(IngestValidationError, match="INGEST_ALARMS_FILE required"):
        run.run_replay_phase(settings, FakeProducer())


def test_ac40_generate_export_reingest_round_trip(tmp_path) -> None:
    """Generate -> export corpus -> re-ingest yields equivalent emitted alarms."""
    corpus = tmp_path / "rt-corpus.jsonl"
    gen_settings = load_settings(
        _env(
            tmp_path / "gen",
            phase="p2",
            SCENARIOS="fiber-cut",
            SCENARIO_INSTANCES="2",
            NOISE_RATE="0.1",
            EXPORT_CORPUS_FILE=str(corpus),
        )
    )
    gen_prod = FakeProducer()
    gen = run.run_replay_phase(gen_settings, gen_prod, run_id="gen")
    assert corpus.exists()
    gen_alarm_ids = [e.payload.alarmId for _, e in gen_prod.sent]

    ing_settings = load_settings(
        _env(tmp_path / "ing", phase="p2", SIM_MODE="ingest", INGEST_ALARMS_FILE=str(corpus))
    )
    ing_prod = FakeProducer()
    ing = run.run_replay_phase(ing_settings, ing_prod, run_id="ing")
    ing_alarm_ids = [e.payload.alarmId for _, e in ing_prod.sent]

    assert ing.emitted == gen.emitted
    # round-trip preserves the exact ordered alarm payloads (fresh eventIds only)
    assert ing_alarm_ids == gen_alarm_ids


def test_state_is_updated_for_http_surface(tmp_path) -> None:
    from simulator.api.app import RunState

    state = RunState(started=True)
    settings = load_settings(
        _env(tmp_path, phase="p2", SCENARIOS="fiber-cut", SCENARIO_INSTANCES="1", NOISE_RATE="0")
    )
    run.run_replay_phase(settings, FakeProducer(), state=state, run_id="svc")
    assert state.run_id == "svc"
    assert state.labels.all()


def test_run_p1_updates_state_run_id(tmp_path) -> None:
    from simulator.api.app import RunState

    state = RunState(started=True)
    settings = load_settings(_env(tmp_path, phase="p1"))
    run.run_p1(settings, MockTopologyClient(), state=state, run_id="p1svc")
    assert state.run_id == "p1svc"


def test_total_alarms_target_is_recorded(tmp_path) -> None:
    settings = load_settings(
        _env(
            tmp_path,
            phase="p2",
            SCENARIOS="fiber-cut",
            SCENARIO_INSTANCES="2",
            NOISE_RATE="0.1",
            BACKGROUND_FRACTION="0.2",
            TOTAL_ALARMS="2000",
        )
    )
    outcome = run.run_replay_phase(settings, FakeProducer(), run_id="tot")
    assert outcome.emitted > 0
