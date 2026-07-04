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
from dataclasses import dataclass, field
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
from simulator.synth import (
    aligned_controller,
    aligned_synth,
    enrichment_safe,
    nonaligned_synth,
    p3_config_snapshot,
    p3_fetch,
    p3_schedule,
    target_controller,
)
from simulator.synth.aligned_synth import AlignedCascade
from simulator.synth.models import P3RunSummary
from simulator.synth.p3_config_snapshot import P3ConfigSnapshot
from simulator.synth.p3_labels import P3LabelStore

_log = get_logger("simulator.synth.p3_run")


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
    pack: DomainPack | None = None,
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
        pack=pack,
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


@dataclass
class _NetworkWideMeta:
    """Network-wide bookkeeping carried into the run summary (empty for single-trail)."""

    active: bool = False
    shortfall_cascades: int = 0
    enrichment_conflict_patterns: list[str] = field(default_factory=list)
    target_emitted_fraction: float = 0.0


def _plan_single_trail(
    settings: Settings,
    config: P3ConfigSnapshot,
    pack: DomainPack,
    rng: random.Random,
    base_time,
) -> tuple[list[AlignedCascade], int, _NetworkWideMeta]:
    """Existing single-trail P3 path (AC 58) — byte-for-byte unchanged behaviour."""
    plan = aligned_controller.plan(
        pack,
        config.patterns,
        config.trails,
        settings.p3_total_alarms,
        settings.p3_aligned_fraction,
        rng,
        base_time,
        optional_include_prob=settings.p3_optional_include_prob,
        stagger_margin=settings.p3_stagger_margin,
        stagger_jitter_ms=settings.p3_stagger_jitter_ms,
        in_window_margin=settings.p3_in_window_margin,
    )
    return plan.cascades, plan.non_aligned_count, _NetworkWideMeta(active=False)


def _plan_network_wide(
    settings: Settings,
    config: P3ConfigSnapshot,
    pack: DomainPack,
    rng: random.Random,
    base_time,
) -> tuple[list[AlignedCascade], int, _NetworkWideMeta]:
    """Network-wide path: target-controller plan -> one enrichment-safe cascade per PlanEntry.

    Cascades on repeated trails are staggered by strictly more than the pattern's session window
    (reusing the ``aligned_controller`` stagger primitive) so each becomes its own CE incident
    (AC 56). Every cascade is built enrichment-safe (distinct objects, reconciled spacing) and
    asserted safe (AC 59/61/63).
    """
    from datetime import timedelta

    patterns_by_id = {p.pattern_id: p for p in config.patterns}
    nw_plan = target_controller.plan_network_wide(
        settings, config.patterns, config.compatible_trails, rng
    )

    cascades: list[AlignedCascade] = []
    # Per-(trailId, patternId) stagger cursor (ms) so repeats are separated by > windowMs (AC 56).
    start_cursors: dict[tuple[str, str], float] = {}
    for entry in nw_plan.entries:
        pattern = patterns_by_id[entry.pattern_id]
        trail = config.trails.get(entry.trail_id)
        if trail is None:
            continue
        bounds = nw_plan.spacing[entry.pattern_id]
        window_ms = (
            float(pattern.session_window.window_ms)
            if pattern.session_window and pattern.session_window.window_ms
            else float(settings.p3_enrichment_dedup_window_ms) * 4
        )
        key = (entry.trail_id, entry.pattern_id)
        offset_ms = aligned_controller._next_start_offset_ms(
            key,
            window_ms,
            start_cursors,
            rng,
            stagger_margin=settings.p3_stagger_margin,
            stagger_jitter_ms=settings.p3_stagger_jitter_ms,
        )
        cascade = aligned_synth.build_cascade(
            pack,
            pattern,
            trail,
            rng,
            base_time + timedelta(milliseconds=offset_ms),
            optional_include_prob=settings.p3_optional_include_prob,
            in_window_margin=settings.p3_in_window_margin,
            spacing_lo_ms=bounds.lo_ms,
            spacing_hi_ms=bounds.hi_ms,
            instance_index=entry.instance_index,
            igp_area=entry.igp_area,
        )
        enrichment_safe.assert_cascade_safe(
            cascade.alarms,
            dedup_window_ms=float(settings.p3_enrichment_dedup_window_ms),
        )
        cascades.append(cascade)

    aligned_count = sum(len(c.alarms) for c in cascades)
    non_aligned_count = max(0, settings.p3_total_alarms - aligned_count)
    metrics.P3_CASCADE_SHORTFALL.set(nw_plan.shortfall_cascades)
    metrics.P3_ENRICHMENT_CONFLICT.set(len(nw_plan.enrichment_conflict_patterns))
    meta = _NetworkWideMeta(
        active=True,
        shortfall_cascades=nw_plan.shortfall_cascades,
        enrichment_conflict_patterns=list(nw_plan.enrichment_conflict_patterns),
        target_emitted_fraction=nw_plan.target_emitted_fraction,
    )
    return cascades, non_aligned_count, meta


def _apply_network_wide_summary(
    summary: P3RunSummary, cascades: list[AlignedCascade], meta: _NetworkWideMeta
) -> None:
    """Populate network-wide summary fields from the emitted cascades + plan meta (AC 51/53/65)."""
    if not meta.active:
        # Single-trail: keep the pre-network-wide summary shape (additive fields defaulted/zero).
        summary.aligned_fraction_emitted = summary.aligned_fraction
        return
    aligned = [c for c in cascades if c.label.scenario_type == "pattern-aligned"]
    trails = {c.label.trail_id for c in aligned}
    areas = {c.label.igp_area for c in aligned if c.label.igp_area is not None}
    summary.distinct_trails_used = len(trails)
    summary.distinct_areas_used = len(areas)
    summary.shortfall_cascades = meta.shortfall_cascades
    summary.enrichment_conflict_patterns = list(meta.enrichment_conflict_patterns)
    # enrichmentSafeCount == emitted aligned alarms (all safe by construction) = correlatable count.
    summary.enrichment_safe_count = summary.aligned_alarms
    # alignedFraction is the post-enrichment expectation (== enrichmentSafeCount/T); the emitted
    # (over-provisioned) fraction is recorded separately (AC 51).
    summary.aligned_fraction_emitted = meta.target_emitted_fraction
    metrics.P3_DISTINCT_TRAILS_USED.set(summary.distinct_trails_used)
    metrics.P3_DISTINCT_AREAS_USED.set(summary.distinct_areas_used)


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
        pack=pack,
    )

    rng, _seed_value = _seed(settings)
    # Anchor cascade timing to REAL wall-clock now() for the live emission so raisedAt ~= arrival
    # time (nothing looks stale to downstream/UI). Reproducibility under P3_RNG_SEED is RELATIVE:
    # the seeded rng fixes ordering, stagger offsets, in-cascade gaps, noise placement and all
    # identities (alarmIds/moids), so two seeded runs reproduce identical RELATIVE timing/ordering/
    # identities — only the absolute base (now()) naturally differs (AC 41, relative form). CE
    # windows on wall-clock ARRIVAL, so the absolute base is irrelevant to auto-correlation.
    base_time = datetime.now(tz=UTC)

    labels = P3LabelStore()
    if settings.p3_network_wide_active:
        cascades, non_aligned_count, nw_meta = _plan_network_wide(
            settings, config, pack, rng, base_time
        )
    else:
        cascades, non_aligned_count, nw_meta = _plan_single_trail(
            settings, config, pack, rng, base_time
        )

    aligned_alarms: list[SynthAlarm] = []
    for cascade in cascades:
        aligned_alarms.extend(cascade.alarms)
        labels.record(cascade.label)
    metrics.P3_ALIGNED_ALARMS.inc(len(aligned_alarms))

    non_aligned = nonaligned_synth.build_nonaligned(
        pack,
        config.patterns,
        config.trails,
        non_aligned_count,
        rng,
        base_time,
        partial_fraction=settings.p3_partial_cascade_fraction,
        random_fraction=settings.p3_random_alarm_fraction,
        noise_fraction=settings.p3_noise_fraction,
        background_interval_ms=settings.background_interval_ms,
    )
    labels.record_all(non_aligned.labels)

    # Emit each aligned cascade as a CONTIGUOUS in-window burst (opener first), with non-aligned /
    # noise alarms sprinkled into the gaps BETWEEN cascades — never interleaved INTO a cascade. This
    # is the cascade-timing fix (M2): the prior global sort scattered a single cascade across the
    # timeline so opener+followers never arrived within windowMs at the CE -> ~0 auto-correlation.
    stream = p3_schedule.build_emission_stream(cascades, non_aligned.alarms, rng)

    strategy = replay.LiveReplay(producer, pacing_multiplier=settings.pacing_multiplier)
    emitted = strategy.replay_synth(stream)

    summary = labels.compute_summary()
    _apply_network_wide_summary(summary, cascades, nw_meta)
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
