"""Persistent service entrypoint — ``python -m simulator serve`` (spec Task 25, AC 76/77).

Runs the Simulator as a long-lived process: the FastAPI app (all existing read routes plus the
``/synth/run`` + ``/synth/status`` trigger endpoints) stays up continuously on uvicorn. A triggered
P3 synth run executes on a background worker thread owned by the :class:`RunManager`, so
``/health`` and ``/metrics`` remain responsive during a run. The one-shot CLI (``--phase …``) is
unchanged; this module only adds the service launch mode.
"""

from __future__ import annotations

import logging

from simulator.api.app import RunState, create_app
from simulator.config.settings import ConfigError, Settings, load_settings
from simulator.obs.logging import configure_logging, get_logger, log_event
from simulator.synth import p2_run, p3_run
from simulator.synth.mine_run_manager import MineRunManager
from simulator.synth.run_guard import RunGuard
from simulator.synth.run_manager import RunManager

_log = get_logger("simulator.serve")


def _settings_env() -> dict[str, str]:  # pragma: no cover - process env read
    import os

    return dict(os.environ)


def _settings_provider() -> Settings:
    return load_settings(_settings_env())


def _producer_factory(settings: Settings):  # pragma: no cover - real Kafka boundary
    from simulator.integrations.kafka_producer import KafkaProducer

    return KafkaProducer(settings.kafka_bootstrap_servers or "")


def build_run_manager(state: RunState, guard: RunGuard | None = None) -> RunManager:
    """Wire a RunManager: fresh env Settings per run, a real Kafka producer, the P3 pipeline.

    Overrides from the POST body are applied on top of the per-run env Settings inside the manager;
    every other P3 knob comes from env/config only. On completion the run's P3 labels are pushed
    onto the shared ``RunState`` so ``/labels`` reflects the latest run (mirrors the CLI path). A
    shared ``guard`` makes the synth run mutually exclusive with the P2 mine run.
    """

    def on_labels(labels: object) -> None:
        if labels is not None:
            state.p3_labels = labels  # type: ignore[assignment]

    return RunManager(
        _settings_provider,
        _producer_factory,
        run_synth=p3_run.run_synth,
        on_labels=on_labels,
        guard=guard,
    )


def build_mine_manager(guard: RunGuard | None = None) -> MineRunManager:
    """Wire a MineRunManager: fresh env Settings per run, a real Kafka producer, the P2 corpus path.

    The mine run REUSES the existing P2 generate pipeline (``p2_run.run_corpus`` →
    ``run.run_replay_phase`` with phase=p2 / SIM_MODE=generate) to emit the labeled corpus onto
    ``alarms.history``. Overrides from the POST body (``scenarioInstances``/``seed``) are applied on
    top of the per-run env Settings inside the manager; every other corpus knob and the real
    topology/knowledge/trail-builder collaborators come from the serve container's env. The shared
    ``guard`` makes the mine run mutually exclusive with the P3 synth run.
    """
    return MineRunManager(
        _settings_provider,
        _producer_factory,
        run_corpus=p2_run.run_corpus,
        guard=guard,
    )


def main(argv: list[str] | None = None) -> int:  # pragma: no cover - uvicorn process boundary
    configure_logging()
    log = get_logger("simulator.serve")
    try:
        settings = load_settings(_settings_env())
        configure_logging(settings.log_level)
    except (ConfigError, ValueError) as exc:
        log_event(log, logging.ERROR, "config.invalid", str(exc))
        return 3

    state = RunState(started=True)
    # ONE shared guard across both triggers so a synth run and a mine run can never run
    # concurrently (both drive the simulator's single producer).
    guard = RunGuard()
    run_manager = build_run_manager(state, guard)
    mine_manager = build_mine_manager(guard)
    app = create_app(state, run_manager=run_manager, mine_manager=mine_manager)

    log_event(
        log,
        logging.INFO,
        "serve.start",
        f"simulator persistent service listening on :{settings.http_port}",
        port=settings.http_port,
    )

    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=settings.http_port, log_config=None)
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
