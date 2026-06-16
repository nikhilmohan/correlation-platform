"""CLI parsing + usage-validation tests (criterion 41 and the ingest/export option surface).

``build_plan`` parses argv + env into a validated ``CliPlan`` or raises ``UsageError`` (exit 2):
a phase is required; --phase and --mode must agree; ingest requires its input file(s); ingest
and --export-corpus are mutually exclusive (ingest OR export). No side effects, no real run.
"""

from __future__ import annotations

import pytest

from simulator import cli
from simulator.cli import UsageError, build_plan, wants_help


def test_phase_is_required() -> None:
    with pytest.raises(UsageError, match="--phase"):
        build_plan([], env={})


def test_explicit_phase_parsed() -> None:
    plan = build_plan(["--phase", "p2"], env={})
    assert plan.phase == "p2"
    assert plan.env_overrides["phase"] == "p2"
    assert not plan.ingest


def test_mode_alias_maps_to_phase() -> None:
    assert build_plan(["--mode", "upload"], env={}).phase == "p1"
    assert build_plan(["--mode", "history"], env={}).phase == "p2"
    assert build_plan(["--mode", "live"], env={}).phase == "p3"


def test_phase_and_mode_conflict_rejected() -> None:
    with pytest.raises(UsageError, match="conflicts"):
        build_plan(["--phase", "p1", "--mode", "live"], env={})


def test_phase_and_agreeing_mode_accepted() -> None:
    assert build_plan(["--phase", "p2", "--mode", "history"], env={}).phase == "p2"


def test_unknown_argument_rejected() -> None:
    with pytest.raises(UsageError, match="unknown arguments"):
        build_plan(["--phase", "p2", "--bogus"], env={})


def test_bad_choice_rejected() -> None:
    with pytest.raises(UsageError):
        build_plan(["--phase", "p9"], env={})


def test_ingest_flag_sets_mode_override() -> None:
    plan = build_plan(["--phase", "p1", "--ingest", "--topology-file", "/t/snap.json"], env={})
    assert plan.ingest
    assert plan.env_overrides["SIM_MODE"] == "ingest"
    assert plan.env_overrides["INGEST_TOPOLOGY_FILE"] == "/t/snap.json"


def test_ingest_via_env_sim_mode() -> None:
    plan = build_plan(["--phase", "p1", "--topology-file", "/t/s.json"], env={"SIM_MODE": "ingest"})
    assert plan.ingest


def test_ingest_p1_requires_topology_file() -> None:
    with pytest.raises(UsageError, match="topology-file"):
        build_plan(["--phase", "p1", "--ingest"], env={})


def test_ingest_p2_requires_alarms_and_labels() -> None:
    with pytest.raises(UsageError, match="alarms-file"):
        build_plan(["--phase", "p2", "--ingest"], env={})
    with pytest.raises(UsageError, match="labels-file"):
        build_plan(["--phase", "p2", "--ingest", "--alarms-file", "/c.jsonl"], env={})


def test_ingest_p2_with_all_files_ok() -> None:
    plan = build_plan(
        ["--phase", "p2", "--ingest", "--alarms-file", "/c.jsonl", "--labels-file", "/l.jsonl"],
        env={},
    )
    assert plan.alarms_file == "/c.jsonl"
    assert plan.labels_file == "/l.jsonl"


def test_ingest_and_export_are_mutually_exclusive() -> None:
    with pytest.raises(UsageError, match="cannot be combined"):
        build_plan(
            [
                "--phase",
                "p2",
                "--ingest",
                "--alarms-file",
                "/c",
                "--labels-file",
                "/l",
                "--export-corpus",
                "/e.jsonl",
            ],
            env={},
        )


def test_export_corpus_generate_mode() -> None:
    plan = build_plan(["--phase", "p2", "--export-corpus", "/e.jsonl"], env={})
    assert plan.export_corpus == "/e.jsonl"
    assert plan.env_overrides["EXPORT_CORPUS_FILE"] == "/e.jsonl"
    assert not plan.ingest


def test_env_file_overrides_are_picked_up() -> None:
    plan = build_plan(
        ["--phase", "p3"],
        env={"SIM_MODE": "ingest", "INGEST_ALARMS_FILE": "/a", "INGEST_LABELS_FILE": "/l"},
    )
    assert plan.ingest
    assert plan.alarms_file == "/a"
    assert plan.labels_file == "/l"


def test_dry_run_and_config_flags() -> None:
    plan = build_plan(["--phase", "p2", "--dry-run", "--config", "/cfg.json"], env={})
    assert plan.dry_run
    assert plan.config == "/cfg.json"


def test_wants_help_detects_help_flags() -> None:
    assert wants_help(["--help"])
    assert wants_help(["-h"])
    assert not wants_help(["--phase", "p2"])


def test_usage_text_documents_phases_and_modes() -> None:
    assert "--phase" in cli.USAGE
    assert "INGEST" in cli.USAGE
    assert "export" in cli.USAGE.lower()
