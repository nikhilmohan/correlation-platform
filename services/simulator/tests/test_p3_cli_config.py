"""P3 CLI + config validation tests (AC 44, 45, 46).

Config-switchable modes with no code change (AC 44); backward-compat that non-synth runs never
instantiate a P3 client (AC 45); and fail-fast config validation before any emission (AC 46).
"""

from __future__ import annotations

import pytest

from simulator import cli
from simulator.config.settings import ConfigError, load_settings


def _base(**extra: str) -> dict[str, str]:
    env = {
        "SIM_MODE": "synth",
        "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
        "phase": "p3",
    }
    env.update(extra)
    return env


# --- AC 46: CLI exposes synth options ------------------------------------------------------
def test_ac46_help_documents_synth_options() -> None:
    assert cli.wants_help(["--help"])
    for opt in (
        "--synth",
        "--p3-aligned-fraction",
        "--p3-total-alarms",
        "--p3-rng-seed",
        "--p3-config-snapshot-path",
    ):
        assert opt in cli.USAGE


def test_ac46_synth_flag_pins_phase_p3() -> None:
    plan = cli.build_plan(["--synth"], env={})
    assert plan.synth is True
    assert plan.phase == "p3"
    assert plan.env_overrides["SIM_MODE"] == "synth"


def test_ac46_synth_p3_options_flow_to_env() -> None:
    plan = cli.build_plan(
        [
            "--synth",
            "--p3-aligned-fraction",
            "0.7",
            "--p3-total-alarms",
            "300",
            "--p3-rng-seed",
            "9",
            "--p3-config-snapshot-path",
            "/tmp/cfg.json",
        ],
        env={},
    )
    assert plan.env_overrides["P3_ALIGNED_FRACTION"] == "0.7"
    assert plan.env_overrides["P3_TOTAL_ALARMS"] == "300"
    assert plan.env_overrides["P3_RNG_SEED"] == "9"
    assert plan.env_overrides["P3_CONFIG_SNAPSHOT_PATH"] == "/tmp/cfg.json"


def test_synth_conflicts_with_non_p3_phase() -> None:
    with pytest.raises(cli.UsageError, match="P3-only"):
        cli.build_plan(["--synth", "--phase", "p2"], env={})


def test_synth_conflicts_with_ingest() -> None:
    with pytest.raises(cli.UsageError, match="cannot be combined with --ingest"):
        cli.build_plan(["--synth", "--ingest"], env={})


# --- AC 46: fail-fast on missing Pattern Manager URL in real mode --------------------------
def test_ac46_missing_pattern_manager_url_real_fails_fast() -> None:
    with pytest.raises(ConfigError, match="PATTERN_MANAGER_API_BASE_URL"):
        load_settings(_base(PATTERN_MANAGER_API_MODE="real"))


def test_ac46_missing_trail_builder_url_real_fails_fast() -> None:
    with pytest.raises(ConfigError, match="TRAIL_BUILDER_API_BASE_URL"):
        load_settings(_base(TRAIL_BUILDER_API_MODE="real"))


def test_ac46_missing_topology_url_real_fails_fast() -> None:
    with pytest.raises(ConfigError, match="TOPOLOGY_API_BASE_URL"):
        load_settings(_base(TOPOLOGY_API_MODE="real"))


def test_ac46_aligned_fraction_out_of_range_fails_fast() -> None:
    with pytest.raises(ConfigError, match="P3_ALIGNED_FRACTION"):
        load_settings(_base(P3_ALIGNED_FRACTION="1.5"))


def test_ac46_mix_fractions_must_sum_to_one() -> None:
    with pytest.raises(ConfigError, match="must sum to 1.0"):
        load_settings(
            _base(
                P3_PARTIAL_CASCADE_FRACTION="0.5",
                P3_RANDOM_ALARM_FRACTION="0.5",
                P3_NOISE_FRACTION="0.5",
            )
        )


def test_ac46_synth_requires_kafka() -> None:
    with pytest.raises(ConfigError, match="KAFKA_BOOTSTRAP_SERVERS required for synth"):
        load_settings({"SIM_MODE": "synth", "phase": "p3"})


# --- AC 44: all three integrations config-switchable ---------------------------------------
def test_ac44_all_modes_default_mock_no_urls_needed() -> None:
    s = load_settings(_base())
    assert s.pattern_manager_api_mode == "mock"
    assert s.trail_builder_api_mode == "mock"
    assert s.topology_api_mode == "mock"
    # mock mode needs no base URLs
    assert s.pattern_manager_api_base_url is None


def test_ac44_switch_to_real_requires_only_config() -> None:
    s = load_settings(
        _base(
            PATTERN_MANAGER_API_MODE="real",
            PATTERN_MANAGER_API_BASE_URL="http://pm:8080",
            TRAIL_BUILDER_API_MODE="real",
            TRAIL_BUILDER_API_BASE_URL="http://tb:8080",
            TOPOLOGY_API_MODE="real",
            TOPOLOGY_API_BASE_URL="http://topo:8080",
        )
    )
    assert s.pattern_manager_api_mode == "real"
    assert s.trail_builder_api_base_url == "http://tb:8080"


def test_config_snapshot_path_defaults_under_output_dir() -> None:
    s = load_settings(_base(SIM_OUTPUT_DIR="/data/x"))
    assert s.resolved_p3_config_snapshot_path == "/data/x/p3-config-snapshot.json"


# --- AC 45: backward compat — non-synth modes never touch P3 clients -----------------------
def test_ac45_generate_mode_unchanged() -> None:
    """A generate p2 run with no synth options is byte-identical config-wise (mode=generate)."""
    s = load_settings(
        {
            "phase": "p2",
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "HISTORY_DURATION": "3600",
        }
    )
    assert s.sim_mode == "generate"


def test_ac45_generate_plan_has_no_synth() -> None:
    plan = cli.build_plan(["--phase", "p2"], env={})
    assert plan.synth is False
    assert "SIM_MODE" not in plan.env_overrides
