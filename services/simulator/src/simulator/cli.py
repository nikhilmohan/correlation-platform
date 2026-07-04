"""CLI argument parsing + usage validation (criterion 41) — testable, no side effects.

Separated from ``main`` so the generate/ingest/export option surface and the exit-code rules
can be unit-tested without running a real phase. ``build_plan`` parses argv + env into a
:class:`CliPlan` or raises :class:`UsageError` (exit 2). ``main`` then loads + validates config
(exit 3 on bad config) and runs the phase.
"""

from __future__ import annotations

import argparse
import os
from dataclasses import dataclass

USAGE = """\
usage: python -m simulator.main --phase {p1,p2,p3}
                                [--ingest | SIM_MODE=ingest]
                                [--synth | SIM_MODE=synth]
                                [--mode {upload,history,live}]
                                [--config PATH] [--dry-run] [--help]
       generate-mode export (round-trip):
                                [--export-corpus PATH]
       ingest-mode inputs (skip generation):
                                [--topology-file PATH]
                                [--alarms-file PATH]
                                [--labels-file PATH]
       synth-mode (P3 topology-and-pattern-driven) options:
                                [--p3-aligned-fraction F]
                                [--p3-total-alarms N]
                                [--p3-rng-seed SEED]
                                [--p3-config-snapshot-path PATH]

One simulator, three phases x three data-source modes:

GENERATE (default — synthesize, optionally export):
  --phase p1   build topology, write the snapshot file, upload to the Topology ingestion API.
  --phase p2   synthesize the labeled corpus, BATCH-replay to alarms.history, write labels.
  --phase p3   synthesize the same stream, replay to alarms.live wall-clock paced.
  --export-corpus PATH   ALSO write the ordered emitted alarm stream to a corpus file.

INGEST (--ingest or SIM_MODE=ingest — skip generation, replay pre-created files verbatim):
  --phase p1 --topology-file PATH   load + validate + upload a pre-created snapshot.
  --phase p2 --alarms-file PATH --labels-file PATH   replay a corpus to alarms.history.
  --phase p3 --alarms-file PATH --labels-file PATH   replay a corpus to alarms.live (paced).

SYNTH (--synth or SIM_MODE=synth — P3 topology-and-pattern-driven live synthesis, pinned to P3):
  Reads the deployed topology + trails + approved patterns from the running services' published
  APIs (config-switchable mock/real via PATTERN_MANAGER_API_MODE / TRAIL_BUILDER_API_MODE /
  TOPOLOGY_API_MODE) and synthesizes a pattern-aligned alarms.live stream. Regenerates nothing.
    --p3-aligned-fraction F       target pattern-aligned fraction (P3_ALIGNED_FRACTION).
    --p3-total-alarms N           total alarm volume (P3_TOTAL_ALARMS).
    --p3-rng-seed SEED            reproducibility seed (P3_RNG_SEED; unset -> fresh, logged).
    --p3-config-snapshot-path P   load-from-persisted vs. re-fetch (P3_CONFIG_SNAPSHOT_PATH).
  Standalone: with a pre-populated --p3-config-snapshot-path the run makes zero API calls.
  Full-cycle: without it, the run fetches from the live/mock services and persists the snapshot.

Options:
  --mode {upload,history,live}   explicit alias for the phase action (must agree with --phase).
  --config PATH                  scenario/config file (KNOWLEDGE_MODE=local override).
  --dry-run                      build/load + validate + log the planned run WITHOUT emitting.
  --help                         print this usage and exit 0.

Exit codes:
  0  success / --help / --dry-run on valid config
  2  invalid CLI usage (bad/missing --phase, conflicting --phase/--mode,
     --ingest without the required file(s), --ingest with --export-corpus, or --synth with --ingest)
  3  invalid/missing required config or malformed ingest input (zero events emitted)
  4  dependency failure (Topology API, Pattern Manager, Trail Builder, or Kafka unreachable)
"""

_MODE_TO_PHASE = {"upload": "p1", "history": "p2", "live": "p3"}


class UsageError(Exception):
    """Raised on invalid CLI usage (exit 2)."""


@dataclass
class CliPlan:
    phase: str
    ingest: bool
    export_corpus: str | None
    topology_file: str | None
    alarms_file: str | None
    labels_file: str | None
    config: str | None
    dry_run: bool
    # env overrides to feed into Settings (only the CLI-provided file flags + mode)
    env_overrides: dict[str, str]
    synth: bool = False


def _build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="simulator", add_help=False)
    p.add_argument("--phase", choices=["p1", "p2", "p3"])
    p.add_argument("--mode", choices=["upload", "history", "live"])
    p.add_argument("--ingest", action="store_true")
    p.add_argument("--synth", action="store_true")
    p.add_argument("--export-corpus")
    p.add_argument("--topology-file")
    p.add_argument("--alarms-file")
    p.add_argument("--labels-file")
    p.add_argument("--p3-aligned-fraction")
    p.add_argument("--p3-total-alarms")
    p.add_argument("--p3-rng-seed")
    p.add_argument("--p3-config-snapshot-path")
    # network-wide emission + closed-loop auto-correlation target (additive)
    p.add_argument("--p3-network-wide", action="store_true")
    p.add_argument("--p3-auto-correlation-target")
    p.add_argument("--p3-target-tolerance")
    p.add_argument("--p3-max-cascades-per-trail")
    p.add_argument("--p3-enrichment-over-provision-margin")
    p.add_argument("--p3-enrichment-dedup-window-ms")
    p.add_argument("--p3-enrichment-transient-types")
    p.add_argument("--p3-dedup-spacing-margin")
    p.add_argument("--config")
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--help", action="store_true")
    return p


def build_plan(argv: list[str], env: dict[str, str] | None = None) -> CliPlan:
    """Parse argv + env into a validated :class:`CliPlan` (raises :class:`UsageError`)."""
    env = env if env is not None else dict(os.environ)
    parser = _build_parser()
    try:
        ns, unknown = parser.parse_known_args(argv)
    except SystemExit as exc:  # argparse error on bad choice
        raise UsageError("invalid CLI usage") from exc
    if unknown:
        raise UsageError(f"unknown arguments: {unknown}")

    synth = ns.synth or env.get("SIM_MODE") == "synth"

    # phase (CLI > --mode alias > synth-implied p3 > require explicit)
    phase = ns.phase
    if ns.mode:
        mode_phase = _MODE_TO_PHASE[ns.mode]
        if phase and phase != mode_phase:
            raise UsageError(f"--phase {phase} conflicts with --mode {ns.mode}")
        phase = phase or mode_phase
    if synth:
        # synth is pinned to P3 (emit target alarms.live); default/validate the phase.
        if phase and phase != "p3":
            raise UsageError(f"--synth is P3-only; --phase {phase} conflicts with synth")
        phase = "p3"
    if not phase:
        raise UsageError("a --phase {p1,p2,p3} (or --mode/--synth) is required")

    ingest = ns.ingest or env.get("SIM_MODE") == "ingest"
    if synth and ingest:
        raise UsageError("--synth cannot be combined with --ingest (choose one data-source mode)")
    export_corpus = ns.export_corpus or env.get("EXPORT_CORPUS_FILE")
    topology_file = ns.topology_file or env.get("INGEST_TOPOLOGY_FILE")
    alarms_file = ns.alarms_file or env.get("INGEST_ALARMS_FILE")
    labels_file = ns.labels_file or env.get("INGEST_LABELS_FILE")

    if ingest and export_corpus:
        raise UsageError("--ingest cannot be combined with --export-corpus (ingest OR export)")

    if ingest:
        if phase == "p1" and not topology_file:
            raise UsageError("ingest --phase p1 requires --topology-file/INGEST_TOPOLOGY_FILE")
        if phase in ("p2", "p3"):
            if not alarms_file:
                raise UsageError("ingest --phase p2/p3 requires --alarms-file/INGEST_ALARMS_FILE")
            if not labels_file:
                raise UsageError("ingest --phase p2/p3 requires --labels-file/INGEST_LABELS_FILE")

    env_overrides: dict[str, str] = {}
    if ns.ingest:
        env_overrides["SIM_MODE"] = "ingest"
    if ns.synth:
        env_overrides["SIM_MODE"] = "synth"
    if export_corpus:
        env_overrides["EXPORT_CORPUS_FILE"] = export_corpus
    if topology_file:
        env_overrides["INGEST_TOPOLOGY_FILE"] = topology_file
    if alarms_file:
        env_overrides["INGEST_ALARMS_FILE"] = alarms_file
    if labels_file:
        env_overrides["INGEST_LABELS_FILE"] = labels_file
    if ns.p3_aligned_fraction is not None:
        env_overrides["P3_ALIGNED_FRACTION"] = ns.p3_aligned_fraction
    if ns.p3_total_alarms is not None:
        env_overrides["P3_TOTAL_ALARMS"] = ns.p3_total_alarms
    if ns.p3_rng_seed is not None:
        env_overrides["P3_RNG_SEED"] = ns.p3_rng_seed
    if ns.p3_config_snapshot_path is not None:
        env_overrides["P3_CONFIG_SNAPSHOT_PATH"] = ns.p3_config_snapshot_path
    if ns.p3_network_wide:
        env_overrides["P3_NETWORK_WIDE"] = "true"
    if ns.p3_auto_correlation_target is not None:
        env_overrides["P3_AUTO_CORRELATION_TARGET"] = ns.p3_auto_correlation_target
    if ns.p3_target_tolerance is not None:
        env_overrides["P3_TARGET_TOLERANCE"] = ns.p3_target_tolerance
    if ns.p3_max_cascades_per_trail is not None:
        env_overrides["P3_MAX_CASCADES_PER_TRAIL"] = ns.p3_max_cascades_per_trail
    if ns.p3_enrichment_over_provision_margin is not None:
        env_overrides["P3_ENRICHMENT_OVER_PROVISION_MARGIN"] = (
            ns.p3_enrichment_over_provision_margin
        )
    if ns.p3_enrichment_dedup_window_ms is not None:
        env_overrides["P3_ENRICHMENT_DEDUP_WINDOW_MS"] = ns.p3_enrichment_dedup_window_ms
    if ns.p3_enrichment_transient_types is not None:
        env_overrides["P3_ENRICHMENT_TRANSIENT_TYPES"] = ns.p3_enrichment_transient_types
    if ns.p3_dedup_spacing_margin is not None:
        env_overrides["P3_DEDUP_SPACING_MARGIN"] = ns.p3_dedup_spacing_margin
    env_overrides["phase"] = phase  # settings field name

    return CliPlan(
        phase=phase,
        ingest=ingest,
        synth=synth,
        export_corpus=export_corpus,
        topology_file=topology_file,
        alarms_file=alarms_file,
        labels_file=labels_file,
        config=ns.config,
        dry_run=ns.dry_run,
        env_overrides=env_overrides,
    )


def wants_help(argv: list[str]) -> bool:
    return "--help" in argv or "-h" in argv
