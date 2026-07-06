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
from simulator.synth import p3_run
from simulator.synth.run_manager import RunManager

_log = get_logger("simulator.serve")


def _settings_env() -> dict[str, str]:  # pragma: no cover - process env read
    import os

    return dict(os.environ)


def build_run_manager(state: RunState) -> RunManager:
    """Wire a RunManager: fresh env Settings per run, a real Kafka producer, the P3 pipeline.

    Overrides from the POST body are applied on top of the per-run env Settings inside the manager;
    every other P3 knob comes from env/config only. On completion the run's P3 labels are pushed
    onto the shared ``RunState`` so ``/labels`` reflects the latest run (mirrors the CLI path).
    """

    def settings_provider() -> Settings:
        return load_settings(_settings_env())

    def producer_factory(settings: Settings):  # pragma: no cover - real Kafka boundary
        from simulator.integrations.kafka_producer import KafkaProducer

        return KafkaProducer(settings.kafka_bootstrap_servers or "")

    def on_labels(labels: object) -> None:
        if labels is not None:
            state.p3_labels = labels  # type: ignore[assignment]

    return RunManager(
        settings_provider,
        producer_factory,
        run_synth=p3_run.run_synth,
        on_labels=on_labels,
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
    run_manager = build_run_manager(state)
    app = create_app(state, run_manager=run_manager)

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
