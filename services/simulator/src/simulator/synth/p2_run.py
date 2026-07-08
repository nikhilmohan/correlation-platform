"""P2 corpus generation run — the mine-corpus trigger's pipeline entry (spec AC 90-91).

``run_corpus(settings, producer, run_id, progress)`` generates the labeled P2 alarm CORPUS and
emits it onto the frozen ``alarms.history`` topic — the exact path a manual ``python -m simulator
--phase p2`` (SIM_MODE=generate) drives. It REUSES the existing generate orchestration
(:func:`simulator.run.run_replay_phase`); it introduces no new synthesis engine, no new Kafka
topic, and no new payload. Downstream, the Enrichment Service enriches ``alarms.history``, the
Noise Filter clusters it to ``transactions.clean``, and the (already live) Pattern Miner mines it
into pattern drafts — so the Simulator only needs to GENERATE + EMIT the corpus here.

Progress: the injected :class:`ProgressSink` is threaded through the batch replay so the emit loop
increments ``alarmsEmitted`` per produced alarm and the corpus total is published via ``set_total``
— exactly the pattern the P3 synth trigger uses, so ``GET /mine/status`` can report live counters.
"""

from __future__ import annotations

from dataclasses import dataclass

from simulator.config.settings import Settings
from simulator.integrations.producer import AlarmProducer
from simulator.synth.progress import ProgressSink


@dataclass
class CorpusOutcome:
    """The result of a P2 corpus generation run (the count the mine-status summary reports)."""

    run_id: str
    emitted: int


def run_corpus(
    settings: Settings,
    producer: AlarmProducer,
    *,
    run_id: str,
    progress: ProgressSink | None = None,
) -> CorpusOutcome:
    """Generate the P2 alarm corpus and emit it onto ``alarms.history`` (reusing the generate path).

    ``settings`` MUST already be pinned to the P2 generate path (``phase='p2'``,
    ``sim_mode='generate'``) — the mine run manager guarantees this via
    :func:`derive_mine_settings`. The real topology/knowledge/trail-builder collaborators are
    resolved from ``settings`` (env-driven mock/real), so a serve container in ``real`` mode
    grounds the corpus in the current snapshot's trails exactly like a manual P2 run.
    """
    # Import here to avoid a module-import cycle (run.py imports the API app which imports nothing
    # from synth at import time, but keeping this local mirrors run_synth_phase's lazy import).
    from simulator import run as run_module

    outcome = run_module.run_replay_phase(
        settings,
        producer,
        run_id=run_id,
        progress=progress,
    )
    return CorpusOutcome(run_id=run_id, emitted=outcome.emitted)
