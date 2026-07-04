"""P3 top-level synthesis run (spec Task 17/18/19, AC 34-43).

``run_synth(settings, producer)``: load-from-persisted or re-fetch the P3 config snapshot -> plan
aligned cascades (aligned-fraction controller) -> synthesize the non-aligned remainder -> interleave
by ``raisedAt`` -> emit via the REUSED ``engine/replay:LiveReplay`` onto ``alarms.live`` -> record
+ persist the P3 labels and run summary. Reproducible under ``P3_RNG_SEED`` (AC 41); standalone from
a persisted snapshot with zero API calls (AC 34, 42).
"""

from __future__ import annotations

import logging
import random
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from simulator.config.settings import Settings
from simulator.domains.coreip.pack import CoreIPPack
from simulator.engine import replay
from simulator.engine.domain_pack import DomainPack
from simulator.engine.models import SynthAlarm
from simulator.integrations.producer import AlarmProducer
from simulator.obs import metrics
from simulator.obs.logging import get_logger, log_event
from simulator.synth import aligned_controller, nonaligned_synth, p3_config_snapshot, p3_fetch
from simulator.synth.models import P3RunSummary
from simulator.synth.p3_config_snapshot import P3ConfigSnapshot
from simulator.synth.p3_labels import P3LabelStore

_log = get_logger("simulator.synth.p3_run")

# Fixed epoch used as the cascade base time in seeded runs (reproducible raisedAt — AC 41).
_EPOCH = datetime(2026, 1, 1, tzinfo=UTC)


@dataclass
class SynthOutcome:
    """The result of a P3 synthesis run (counts + populated label store)."""

    run_id: str
    emitted: int
    labels: P3LabelStore
    summary: P3RunSummary
    fetched: bool


def resolve_config(
    settings: Settings,
    *,
    pattern_client=None,
    trail_client=None,
    snapshot_client=None,
) -> tuple[P3ConfigSnapshot, bool]:
    """Load the P3 config snapshot from disk if present (zero API calls), else fetch + persist."""
    path = Path(settings.resolved_p3_config_snapshot_path)
    if settings.p3_config_snapshot_path and path.exists():
        log_event(
            _log,
            logging.INFO,
            "p3.config_snapshot_loaded",
            f"loaded P3 config snapshot from {path} (no API calls)",
            path=str(path),
        )
        return p3_config_snapshot.load(path), False

    config = p3_fetch.fetch_config(
        settings,
        pattern_client=pattern_client,
        trail_client=trail_client,
        snapshot_client=snapshot_client,
    )
    p3_config_snapshot.save(config, path)
    log_event(
        _log,
        logging.INFO,
        "p3.config_snapshot_persisted",
        f"persisted P3 config snapshot to {path}",
        path=str(path),
        patterns=len(config.patterns),
        trails=len(config.trails),
    )
    return config, True


def _seed(settings: Settings) -> tuple[random.Random, int]:
    if settings.p3_rng_seed is not None:
        seed = settings.p3_rng_seed
    else:
        seed = random.randrange(1 << 30)
        log_event(
            _log,
            logging.INFO,
            "p3.seed_chosen",
            f"no P3_RNG_SEED supplied; chose {seed} (re-supply for reproducibility)",
            seed=seed,
        )
    return random.Random(seed), seed


def run_synth(
    settings: Settings,
    producer: AlarmProducer,
    *,
    run_id: str,
    pack: DomainPack | None = None,
    pattern_client=None,
    trail_client=None,
    snapshot_client=None,
) -> SynthOutcome:
    """Execute a full P3 synthesis run and emit onto ``alarms.live``."""
    pack = pack or CoreIPPack()
    metrics.MODE.labels(mode="synth").set(1)

    config, fetched = resolve_config(
        settings,
        pattern_client=pattern_client,
        trail_client=trail_client,
        snapshot_client=snapshot_client,
    )

    rng, _seed_value = _seed(settings)
    # A seeded run pins base_time to a fixed epoch so raisedAt is reproducible too (AC 41);
    # an unseeded run uses wall-clock so successive fresh runs differ.
    base_time = _EPOCH if settings.p3_rng_seed is not None else datetime.now(tz=UTC)

    plan = aligned_controller.plan(
        pack,
        config.patterns,
        config.trails,
        settings.p3_total_alarms,
        settings.p3_aligned_fraction,
        rng,
        base_time,
        optional_include_prob=settings.p3_optional_include_prob,
    )

    labels = P3LabelStore()
    aligned_alarms: list[SynthAlarm] = []
    for cascade in plan.cascades:
        aligned_alarms.extend(cascade.alarms)
        labels.record(cascade.label)
    metrics.P3_ALIGNED_ALARMS.inc(len(aligned_alarms))

    non_aligned = nonaligned_synth.build_nonaligned(
        pack,
        config.patterns,
        config.trails,
        plan.non_aligned_count,
        rng,
        base_time,
        partial_fraction=settings.p3_partial_cascade_fraction,
        random_fraction=settings.p3_random_alarm_fraction,
        noise_fraction=settings.p3_noise_fraction,
        background_interval_ms=settings.background_interval_ms,
    )
    labels.record_all(non_aligned.labels)

    # Interleave aligned + non-aligned strictly by raisedAt so LiveReplay pacing is coherent.
    stream = sorted(aligned_alarms + non_aligned.alarms, key=lambda a: a.raised_at)

    strategy = replay.LiveReplay(producer, pacing_multiplier=settings.pacing_multiplier)
    emitted = strategy.replay_synth(stream)

    summary = labels.compute_summary()
    metrics.P3_ALIGNED_FRACTION.set(summary.aligned_fraction)

    out_dir = Path(settings.sim_output_dir)
    labels.export_labels(out_dir / f"p3-labels-{run_id}.jsonl")
    labels.export_summary(out_dir / f"p3-summary-{run_id}.json")

    log_event(
        _log,
        logging.INFO,
        "p3.run_complete",
        "P3 synthesis complete",
        emitted=emitted,
        aligned=summary.aligned_alarms,
        nonAligned=summary.non_aligned_alarms,
        alignedFraction=round(summary.aligned_fraction, 4),
    )
    return SynthOutcome(
        run_id=run_id,
        emitted=emitted,
        labels=labels,
        summary=summary,
        fetched=fetched,
    )
