"""Simulator entrypoint / CLI orchestrator (criteria 18, 41).

Wires CLI parsing → config validation (fail-fast) → phase execution. Every exit path emits a
structured JSON log line; any non-zero exit before emission guarantees zero events were produced.
Exit codes: 0 success/help/dry-run; 2 usage; 3 invalid config / malformed ingest; 4 dependency
failure. Network/process boundaries (real Kafka, uvicorn) live behind injected seams, so this
module is excluded from unit coverage while the testable core (``cli``/``run``) is covered.
"""

from __future__ import annotations

import logging
import sys

from simulator import cli, run
from simulator.config.settings import ConfigError, load_settings
from simulator.ingest.corpus_loader import IngestValidationError
from simulator.obs.logging import configure_logging, get_logger, log_event


def main(argv: list[str] | None = None) -> int:  # pragma: no cover - process orchestration
    argv = list(sys.argv[1:] if argv is None else argv)
    if cli.wants_help(argv):
        print(cli.USAGE)
        return 0

    configure_logging()
    log = get_logger("simulator.main")

    try:
        plan = cli.build_plan(argv)
    except cli.UsageError as exc:
        configure_logging()
        log_event(log, logging.ERROR, "cli.usage_error", str(exc))
        print(cli.USAGE, file=sys.stderr)
        return 2

    try:
        settings = load_settings({**_settings_env(), **_plan_env(plan)})
        configure_logging(settings.log_level)
    except (ConfigError, ValueError) as exc:
        log_event(log, logging.ERROR, "config.invalid", str(exc))
        return 3

    log_event(
        log,
        logging.INFO,
        "run.start",
        f"starting phase={settings.phase} mode={settings.sim_mode}",
        phase=settings.phase,
        mode=settings.sim_mode,
        dry_run=plan.dry_run,
    )

    try:
        if plan.dry_run:
            return _dry_run(settings, log)
        if settings.phase == "p1":
            client = run.make_topology_client(settings)
            outcome = run.run_p1(settings, client)
        elif settings.sim_mode == "synth":
            from simulator.integrations.kafka_producer import KafkaProducer

            producer = KafkaProducer(settings.kafka_bootstrap_servers or "")
            outcome = run.run_synth_phase(settings, producer)
        else:
            from simulator.integrations.kafka_producer import KafkaProducer

            producer = KafkaProducer(settings.kafka_bootstrap_servers or "")
            outcome = run.run_replay_phase(settings, producer)
        log_event(
            log,
            logging.INFO,
            "run.complete",
            "run complete",
            phase=outcome.phase,
            emitted=outcome.emitted,
            snapshotId=outcome.snapshot_id,
        )
        return 0
    except IngestValidationError as exc:
        log_event(log, logging.ERROR, "ingest.invalid", str(exc))
        return 3
    except Exception as exc:  # dependency failure
        log_event(log, logging.ERROR, "run.dependency_failure", str(exc))
        return 4


def _dry_run(settings, log) -> int:  # pragma: no cover
    log_event(
        log,
        logging.INFO,
        "run.dry_run",
        "config + inputs valid; no events emitted",
        phase=settings.phase,
        mode=settings.sim_mode,
    )
    return 0


def _settings_env() -> dict[str, str]:  # pragma: no cover
    import os

    return dict(os.environ)


def _plan_env(plan: cli.CliPlan) -> dict[str, str]:  # pragma: no cover
    return plan.env_overrides


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
