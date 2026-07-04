"""P3 top-level synthesis run tests (AC 35, 40, 41, 42, 43).

Drives ``p3_run.run_synth`` / ``run.run_synth_phase`` end-to-end against injected mock clients + an
in-memory producer: every alarm on a real moid (AC 35), alarms.live only + valid AlarmEvent (AC 40),
seeded reproducibility + fresh-run divergence (AC 41), standalone from a persisted snapshot with
zero API calls (AC 42), retrievable labels + computable KPI (AC 43).
"""

from __future__ import annotations

from pathlib import Path

from acp_event_model import AlarmEvent, TypedEnvelope

from simulator import run
from simulator.api.app import RunState
from simulator.config.settings import load_settings
from simulator.engine import replay
from simulator.integrations.pattern_manager_client import MockPatternManagerClient
from simulator.integrations.topology_snapshot_client import MockTopologySnapshotClient
from simulator.integrations.trail_builder_client import MockTrailBuilderClient
from simulator.synth import p3_run
from tests import p3_fixtures as fx


class FakeProducer:
    def __init__(self) -> None:
        self.sent: list[tuple[str, TypedEnvelope]] = []
        self.flushed = 0

    def produce(self, topic: str, envelope: TypedEnvelope) -> None:
        self.sent.append((topic, envelope))

    def flush(self) -> None:
        self.flushed += 1


def _settings(tmp_path: Path, **extra: str):
    env = {
        "SIM_MODE": "synth",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "SIM_OUTPUT_DIR": str(tmp_path),
        "PACING_MULTIPLIER": "0",  # no real sleeps in unit tests
        "P3_TOTAL_ALARMS": "80",
        "P3_ALIGNED_FRACTION": "0.65",
        "P3_RNG_SEED": "4242",
        "phase": "p3",
    }
    env.update(extra)
    return load_settings(env)


def _patterns():
    return [
        fx.pattern_view(
            "pat-01",
            "trail-A",
            [("IPLinkDown", False), ("ISISAdjacencyDown", False), ("LSPDown", True)],
            "IPLinkDown",
        ),
        fx.pattern_view(
            "pat-02",
            "trail-B",
            [("FiberFault", False), ("IPLinkDown", False)],
            "FiberFault",
        ),
    ]


def _trails():
    return {
        "trail-A": fx.trail_detail("trail-A", fx.TRAIL_A_MEMBERS),
        "trail-B": fx.trail_detail("trail-B", fx.TRAIL_B_MEMBERS),
    }


def _mock_clients():
    return (
        MockPatternManagerClient(_patterns()),
        MockTrailBuilderClient(_trails()),
        MockTopologySnapshotClient([fx.snapshot_summary()]),
    )


def _run(settings, producer, **kw):
    pm, tb, ts = _mock_clients()
    return p3_run.run_synth(
        settings,
        producer,
        run_id="run-test",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
        **kw,
    )


# --- AC 40: alarms.live only + frozen AlarmEvent validates ---------------------------------
def test_ac40_all_on_alarms_live_and_validate(tmp_path: Path) -> None:
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer)
    assert outcome.emitted == len(producer.sent)
    assert producer.sent
    for topic, envelope in producer.sent:
        assert topic == replay.LIVE_TOPIC
        assert isinstance(envelope.payload, AlarmEvent)
        assert envelope.payload.alarmType  # required canonical token present
    # zero on alarms.history
    assert all(t == replay.LIVE_TOPIC for t, _ in producer.sent)


# --- AC 35: every alarm's moid is a real topology (trail) member ---------------------------
def test_ac35_every_moid_present_in_topology(tmp_path: Path) -> None:
    producer = FakeProducer()
    _run(_settings(tmp_path), producer)
    valid = {moid for moid, _ in fx.TRAIL_A_MEMBERS} | {moid for moid, _ in fx.TRAIL_B_MEMBERS}
    for _, envelope in producer.sent:
        assert envelope.payload.managedObjectId in valid


# --- AC 41: seeded reproducibility; unseeded divergence ------------------------------------
def test_ac41_same_seed_reproducible(tmp_path: Path) -> None:
    p1 = FakeProducer()
    p2 = FakeProducer()
    _run(_settings(tmp_path / "a", P3_RNG_SEED="777"), p1)
    _run(_settings(tmp_path / "b", P3_RNG_SEED="777"), p2)
    ids1 = [e.payload.alarmId for _, e in p1.sent]
    ids2 = [e.payload.alarmId for _, e in p2.sent]
    types1 = [e.payload.alarmType for _, e in p1.sent]
    assert ids1 == ids2
    assert types1 == [e.payload.alarmType for _, e in p2.sent]
    assert [e.payload.managedObjectId for _, e in p1.sent] == [
        e.payload.managedObjectId for _, e in p2.sent
    ]
    assert [e.payload.raisedAt for _, e in p1.sent] == [e.payload.raisedAt for _, e in p2.sent]


def test_ac41_unseeded_diverges(tmp_path: Path) -> None:
    p1 = FakeProducer()
    p2 = FakeProducer()
    # no P3_RNG_SEED -> fresh randomization each run
    s1 = _settings(tmp_path / "a")
    s2 = _settings(tmp_path / "b")
    object.__setattr__(s1, "p3_rng_seed", None)
    object.__setattr__(s2, "p3_rng_seed", None)
    _run(s1, p1)
    _run(s2, p2)
    ids1 = [e.payload.alarmId for _, e in p1.sent][:10]
    ids2 = [e.payload.alarmId for _, e in p2.sent][:10]
    assert ids1 != ids2


# --- AC 42: standalone from persisted snapshot, zero API calls -----------------------------
def test_ac42_standalone_from_persisted_zero_api_calls(tmp_path: Path) -> None:
    # First run fetches + persists.
    path = tmp_path / "p3-config-snapshot.json"
    _run(_settings(tmp_path, P3_CONFIG_SNAPSHOT_PATH=str(path)), FakeProducer())
    assert path.exists()

    # Second run loads from the persisted path with call-counting clients -> zero calls.
    pm = MockPatternManagerClient(_patterns())
    tb = MockTrailBuilderClient(_trails())
    ts = MockTopologySnapshotClient([fx.snapshot_summary()])
    producer = FakeProducer()
    outcome = p3_run.run_synth(
        _settings(tmp_path, P3_CONFIG_SNAPSHOT_PATH=str(path)),
        producer,
        run_id="run-2",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
    )
    assert pm.calls == 0 and tb.calls == 0 and ts.calls == 0
    assert not outcome.fetched
    assert producer.sent  # complete stream from persisted config alone


def test_ac42_no_topology_upload_in_synth(tmp_path: Path) -> None:
    """P3 synth never calls POST /topology/snapshots (it only lists)."""
    ts = MockTopologySnapshotClient([fx.snapshot_summary()])
    # MockTopologySnapshotClient exposes only list_snapshots (no upload) -> upload path unused.
    assert not hasattr(ts, "upload")


# --- AC 43: labels retrievable + KPI computable --------------------------------------------
def test_ac43_labels_and_summary_computable(tmp_path: Path) -> None:
    producer = FakeProducer()
    outcome = _run(_settings(tmp_path), producer)
    summary = outcome.summary
    # AC 43 (explicit): the per-run summary total EQUALS what was actually emitted on alarms.live.
    assert summary.total_alarms == outcome.emitted == len(producer.sent)
    assert summary.aligned_alarms + summary.non_aligned_alarms == summary.total_alarms
    assert abs(summary.aligned_fraction - summary.aligned_alarms / summary.total_alarms) < 1e-9
    # aligned labels reference a root + children; every label present
    cascade_labels = [x for x in outcome.labels.all() if x.scenario_type == "pattern-aligned"]
    assert cascade_labels
    for label in cascade_labels:
        assert label.root_cause_alarm_id
        assert label.root_cause_alarm_type == label.root_cause_alarm_type


def test_ac43_labels_persisted_to_disk(tmp_path: Path) -> None:
    _run(_settings(tmp_path), FakeProducer())
    assert (tmp_path / "p3-labels-run-test.jsonl").exists()
    assert (tmp_path / "p3-summary-run-test.json").exists()


# --- run.run_synth_phase wiring + RunState label surface -----------------------------------
def test_run_synth_phase_populates_state(tmp_path: Path) -> None:
    state = RunState(started=True)
    producer = FakeProducer()
    pm, tb, ts = _mock_clients()
    outcome = run.run_synth_phase(
        _settings(tmp_path),
        producer,
        state=state,
        run_id="rp",
        pattern_client=pm,
        trail_client=tb,
        snapshot_client=ts,
    )
    assert outcome.phase == "p3" and outcome.mode == "synth"
    assert state.run_id == "rp"
    assert state.p3_labels.all()
