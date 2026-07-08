"""Run orchestration — wires config + pack + engine for P1/P2/P3, generate + ingest.

This is the testable core ``main`` calls after parsing the CLI. It is deliberately separate from
argument parsing so the full generate/ingest/export flows can be unit-tested against an injected
producer + topology client (no real Kafka/HTTP). The functions return a :class:`RunOutcome`
recording counts, the snapshotId, and the populated label store.
"""

from __future__ import annotations

import random
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from simulator.api.app import RunState
from simulator.config.settings import Settings
from simulator.domains.coreip.pack import CoreIPPack
from simulator.engine import replay, scenario_runner, snapshot_writer, topology_builder
from simulator.engine.domain_pack import DomainPack, TopologyParams
from simulator.engine.labels import LabelStore
from simulator.ingest import corpus_loader
from simulator.ingest.corpus_writer import CorpusWriter
from simulator.integrations import topology_client
from simulator.integrations.producer import AlarmProducer
from simulator.obs import metrics

if TYPE_CHECKING:
    from simulator.synth.progress import ProgressSink


@dataclass
class RunOutcome:
    run_id: str
    phase: str
    mode: str
    snapshot_id: str | None = None
    emitted: int = 0
    snapshot: dict | None = None
    labels: LabelStore = field(default_factory=LabelStore)
    resolved_instances: int | None = None
    background_count: int = 0
    noise_count: int = 0


def make_pack() -> DomainPack:
    """Return the active domain pack (Core-IP is the only MVP pack)."""
    return CoreIPPack()


def _seeded_rng(settings: Settings) -> random.Random:
    seed = settings.sim_seed if settings.sim_seed is not None else random.randrange(1 << 30)
    return random.Random(seed)


def scenario_summaries(pack: DomainPack) -> list[dict[str, object]]:
    """Scenario-def summaries for the /scenarios endpoint."""
    return [
        {
            "scenarioType": s.scenario_type,
            "rootCauseObjectType": s.fault_origin_type,
            "rootCauseAlarmType": s.root_alarm_type,
        }
        for s in pack.scenario_library()
    ]


def build_topology_snapshot(
    settings: Settings, pack: DomainPack, rng: random.Random, out_dir: Path, run_id: str
) -> dict:
    """Build + validate + write the topology snapshot file (generate P1)."""
    params = TopologyParams(
        node_count=settings.topology_node_count,
        site_count=settings.site_count,
        interfaces_per_port=settings.interfaces_per_port,
        igp_area_count=settings.igp_area_count,
        devices_per_site=settings.devices_per_site,
    )
    result = topology_builder.build_topology(pack, params, rng)
    snapshot_path = out_dir / f"snapshot-{run_id}.json"
    snapshot = snapshot_writer.write_snapshot(result.graph, pack.domain_id(), snapshot_path)
    _record_snapshot_metrics(snapshot, len(result.sites), result.igp_areas, result.graph)
    return snapshot


def _record_snapshot_metrics(snapshot: dict, sites: int, igp_areas: list[str], graph) -> None:
    metrics.SNAPSHOT_NODES.set(len(snapshot["nodes"]))
    metrics.SNAPSHOT_SITES.set(sites)
    metrics.SNAPSHOT_INTERFACES.set(
        sum(1 for n in snapshot["nodes"] if n["objectType"] == "Interface")
    )
    metrics.SNAPSHOT_IGP_AREAS.set(len(igp_areas))
    rel_counts: dict[str, int] = {}
    for e in snapshot["edges"]:
        rel_counts[e["relation"]] = rel_counts.get(e["relation"], 0) + 1
    for rel, count in rel_counts.items():
        metrics.SNAPSHOT_EDGES.labels(relation=rel).set(count)


def run_p1(
    settings: Settings, client, *, state: RunState | None = None, run_id: str | None = None
) -> RunOutcome:
    """P1: generate (or ingest) the snapshot, upload it, record snapshotId."""
    run_id = run_id or uuid.uuid4().hex[:12]
    pack = make_pack()
    out_dir = Path(settings.sim_output_dir)
    metrics.MODE.labels(mode=settings.sim_mode).set(1)

    if settings.sim_mode == "ingest":
        if not settings.ingest_topology_file:
            raise corpus_loader.IngestValidationError("INGEST_TOPOLOGY_FILE required for P1 ingest")
        snapshot = corpus_loader.load_snapshot(settings.ingest_topology_file)
    else:
        rng = _seeded_rng(settings)
        snapshot = build_topology_snapshot(settings, pack, rng, out_dir, run_id)

    response = client.upload(snapshot)
    outcome = RunOutcome(
        run_id=run_id,
        phase="p1",
        mode=settings.sim_mode,
        snapshot_id=response.snapshotId,
        snapshot=snapshot,
    )
    if state is not None:
        state.run_id = run_id
    return outcome


def run_replay_phase(
    settings: Settings,
    producer: AlarmProducer,
    *,
    state: RunState | None = None,
    run_id: str | None = None,
    progress: ProgressSink | None = None,
) -> RunOutcome:
    """P2/P3: synthesize (or ingest) the alarm stream and replay it onto the phase topic.

    ``progress`` (optional) is the HTTP-trigger's :class:`ProgressSink`: the emit loop increments it
    per produced alarm and the effective corpus total is published via ``set_total`` so a status
    handler can report live counters. The CLI one-shot path passes ``None`` (no-op).
    """
    run_id = run_id or uuid.uuid4().hex[:12]
    pack = make_pack()
    out_dir = Path(settings.sim_output_dir)
    labels = LabelStore()
    metrics.MODE.labels(mode=settings.sim_mode).set(1)

    corpus_writer: CorpusWriter | None = None
    target_topic = replay.LIVE_TOPIC if settings.phase == "p3" else replay.HISTORY_TOPIC
    if settings.export_corpus_file:
        corpus_writer = CorpusWriter(
            Path(settings.export_corpus_file), run_id, settings.phase, target_topic
        )
    tap = corpus_writer.tap if corpus_writer else None
    strategy = replay.make_replay(
        settings.phase, producer, settings.pacing_multiplier, tap, progress=progress
    )

    outcome = RunOutcome(run_id=run_id, phase=settings.phase, mode=settings.sim_mode)
    try:
        if settings.sim_mode == "ingest":
            if not settings.ingest_alarms_file:
                raise corpus_loader.IngestValidationError(
                    "INGEST_ALARMS_FILE required for P2/P3 ingest"
                )
            events = corpus_loader.load_corpus(settings.ingest_alarms_file)
            if settings.ingest_labels_file:
                labels.load_from_file(Path(settings.ingest_labels_file))
            if progress is not None:
                progress.set_total(len(events))
            emitted = strategy.replay_events(events)
        else:
            rng = _seeded_rng(settings)
            params = TopologyParams(
                node_count=settings.topology_node_count,
                site_count=settings.site_count,
                interfaces_per_port=settings.interfaces_per_port,
                igp_area_count=settings.igp_area_count,
                devices_per_site=settings.devices_per_site,
            )
            graph = topology_builder.build_topology(pack, params, rng).graph
            start, end = settings.resolved_history_window()
            result = scenario_runner.synthesize(pack, graph, settings, rng, start, end)
            labels = result.labels
            outcome.resolved_instances = result.resolved_instances
            outcome.background_count = result.background_count
            outcome.noise_count = result.noise_count
            for scenario in settings.selected_scenarios:
                inst = sum(1 for label in labels.all() if label.scenario_type == scenario)
                if inst:
                    metrics.SCENARIOS_INJECTED.labels(scenario=scenario).inc(inst)
            metrics.BACKGROUND_ALARMS.inc(result.background_count)
            for a in result.alarms:
                if a.is_noise and a.noise_class:
                    metrics.NOISE_ALARMS.labels(**{"class": a.noise_class}).inc()
                    if a.is_hard_noise:
                        metrics.HARD_NOISE_ALARMS.inc()
            if settings.total_alarms is not None:
                metrics.TARGET_ALARMS.set(settings.total_alarms)
            labels.export_to_file(out_dir / f"labels-{run_id}.jsonl")
            if progress is not None:
                progress.set_total(len(result.alarms))
            emitted = strategy.replay_synth(result.alarms)
    finally:
        if corpus_writer is not None:
            corpus_writer.close()
            metrics.EXPORTED_CORPUS_RECORDS.inc(corpus_writer.count)

    metrics.DISTINCT_SCENARIOS.set(len(labels.distinct_scenario_types()))
    outcome.emitted = emitted
    outcome.labels = labels
    if state is not None:
        state.run_id = run_id
        state.labels = labels
    return outcome


def run_synth_phase(
    settings: Settings,
    producer: AlarmProducer,
    *,
    state: RunState | None = None,
    run_id: str | None = None,
    pattern_client=None,
    trail_client=None,
    snapshot_client=None,
) -> RunOutcome:
    """P3 synth: read deployed topology+trails+patterns, synthesize onto ``alarms.live``."""
    from simulator.synth import p3_run

    run_id = run_id or uuid.uuid4().hex[:12]
    outcome = p3_run.run_synth(
        settings,
        producer,
        run_id=run_id,
        pack=make_pack(),
        pattern_client=pattern_client,
        trail_client=trail_client,
        snapshot_client=snapshot_client,
    )
    result = RunOutcome(run_id=run_id, phase="p3", mode="synth", emitted=outcome.emitted)
    if state is not None:
        state.run_id = run_id
        state.p3_labels = outcome.labels
    return result


def make_topology_client(settings: Settings):
    """Build the configured topology client (mock/real)."""
    return topology_client.make_client(settings.topology_api_mode, settings.topology_api_base_url)
