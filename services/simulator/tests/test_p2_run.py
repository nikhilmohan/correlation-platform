"""P2 corpus run tests — p2_run.run_corpus reuses the generate/history pipeline (spec AC 90-91).

``run_corpus`` must drive the EXISTING P2 generate path (``run.run_replay_phase`` with
``phase=p2`` / ``SIM_MODE=generate``) that emits the labeled alarm corpus onto the frozen
``alarms.history`` topic — no new synthesis engine, no new topic. Here a recording producer double
captures the emitted envelopes so the run is fast and broker-free, and the ProgressSink is asserted
to advance as the corpus is generated.
"""

from __future__ import annotations

from typing import Any

from simulator.config.settings import load_settings
from simulator.engine import replay
from simulator.synth import p2_run
from simulator.synth.progress import ProgressSink


class _RecordingProducer:
    """An in-memory AlarmProducer double — records (topic, envelope) per produce."""

    def __init__(self) -> None:
        self.produced: list[tuple[str, Any]] = []
        self.flushed = 0

    def produce(self, topic: str, envelope: Any) -> None:
        self.produced.append((topic, envelope))

    def flush(self) -> None:
        self.flushed += 1


def _settings(**over: str):
    env = {
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "TOPOLOGY_NODE_COUNT": "12",
        "SITE_COUNT": "2",
        "SCENARIO_INSTANCES": "2",
        "SIM_SEED": "7",
        "SIM_OUTPUT_DIR": "/tmp",
    }
    env.update(over)
    return load_settings(env)


def test_run_corpus_emits_to_history_topic() -> None:
    producer = _RecordingProducer()
    outcome = p2_run.run_corpus(_settings(), producer, run_id="r1", progress=None)
    assert outcome.emitted > 0
    # every emitted alarm lands on the frozen history topic (P2 batch path), no live/other topic
    topics = {t for t, _ in producer.produced}
    assert topics == {replay.HISTORY_TOPIC}
    assert len(producer.produced) == outcome.emitted
    assert producer.flushed >= 1


def test_run_corpus_reports_progress() -> None:
    producer = _RecordingProducer()
    progress = ProgressSink()
    outcome = p2_run.run_corpus(_settings(), producer, run_id="r2", progress=progress)
    snap = progress.snapshot()
    assert snap.alarmsEmitted == outcome.emitted
    assert snap.alarmsTotal >= snap.alarmsEmitted
    # aligned+non-aligned tallies partition the emitted total
    assert snap.alignedEmitted + snap.nonAlignedEmitted == snap.alarmsEmitted


def test_run_corpus_reproducible_under_seed() -> None:
    p1, p2 = _RecordingProducer(), _RecordingProducer()
    o1 = p2_run.run_corpus(_settings(SIM_SEED="99"), p1, run_id="a", progress=None)
    o2 = p2_run.run_corpus(_settings(SIM_SEED="99"), p2, run_id="b", progress=None)
    assert o1.emitted == o2.emitted


def test_run_corpus_scenario_instances_scales_corpus() -> None:
    small = _RecordingProducer()
    large = _RecordingProducer()
    o_small = p2_run.run_corpus(_settings(SCENARIO_INSTANCES="1"), small, run_id="s", progress=None)
    o_large = p2_run.run_corpus(_settings(SCENARIO_INSTANCES="6"), large, run_id="l", progress=None)
    assert o_large.emitted > o_small.emitted
