"""AC9 (static half): no mining-threshold or windowing-gap literal in the service source.

Scans the mining/windowing/config source for numeric literals that would represent a hard-coded
``minSupport`` / ``maxPatternLength`` / session-gap / percentile / multiplier. All such values MUST
come from the Knowledge-sourced ``MiningParams`` / ``WindowingParams`` at runtime, never a code
default. Structural constants (indices, ms/s conversions, the length-1 confidence 1.0, the
percentile 50/100 math, the minBurstSamples=2 definitional minimum) are allow-listed with a reason.
"""

from __future__ import annotations

import ast
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "src" / "pattern_miner"

# Files whose numeric literals are *threshold* candidates (must be Knowledge-sourced, not literals).
# NOTE: config.py is deliberately NOT scanned for literals — it holds OPERATIONAL knobs (retry
# counts, backoff ms, HTTP port, batch-flush latency) which are legitimately env-defaulted, not
# correlation thresholds. The mining/windowing THRESHOLDS live only in windowing.py + miner.py,
# and config.py's freedom-from-mining-defaults is asserted separately below.
THRESHOLD_FILES = [
    SRC / "windowing.py",
    SRC / "mining" / "miner.py",
    SRC / "mining" / "grouped_miner.py",
    SRC / "anchoring.py",
]

# Structural constants that are NOT correlation thresholds (indices, unit conversions, math,
# log-rounding precision).
ALLOWED = {
    0,  # index / neutral / lift-baseline start
    1,  # index / length-1 confidence / neutral
    2,  # definitional minBurstSamples minimum
    -1,  # reverse index
    50.0,  # median percentile
    100.0,  # percent scale
    6,  # round(gap, 6) log precision (not a threshold)
    12,  # sourceWindowId hash truncation length (identifier, not a threshold)
}


def _numeric_literals(path: Path) -> list[tuple[int, float]]:
    tree = ast.parse(path.read_text())
    found: list[tuple[int, float]] = []
    for node in ast.walk(tree):
        if (
            isinstance(node, ast.Constant)
            and isinstance(node.value, int | float)
            and not isinstance(node.value, bool)
        ):
            found.append((node.lineno, float(node.value)))
    return found


def test_no_threshold_literals_in_threshold_files():
    offenders: list[str] = []
    for path in THRESHOLD_FILES:
        for lineno, value in _numeric_literals(path):
            if value not in ALLOWED:
                offenders.append(f"{path.name}:{lineno} -> {value}")
    assert not offenders, (
        "hard-coded threshold/windowing-gap literals found (must be Knowledge-sourced):\n"
        + "\n".join(offenders)
    )


def test_config_declares_no_default_mining_thresholds():
    """Settings must not carry a minSupport/maxPatternLength/session-gap/anchoring env default."""
    src = (SRC / "config.py").read_text()
    for banned in (
        "min_support: float = 0",
        "max_pattern_length: int = ",
        "base_gap_seconds: float = 0",
        "match_confidence_threshold: float = ",
        "w_order: float = ",
        "w_jaccard: float = ",
    ):
        # MiningParams/WindowingParams/AnchoringParams are dataclasses WITHOUT defaults; Settings
        # has no such field.
        assert banned not in src, f"config declares a default mining/anchoring threshold: {banned}"
    # No mining/anchoring-threshold env var exists on Settings (only wiring + operational knobs).
    assert "MIN_SUPPORT" not in src
    assert "SESSION_GAP" not in src
    assert "MATCH_CONFIDENCE" not in src
    assert "MATCHCONFIDENCE" not in src


# Domain-specific tokens that MUST NOT appear as literals in source/default config (they belong to
# the FIXTURES only). If the reusable template ever inlines a domain alarm type / fault-origin name,
# this scan catches it. (Illustrative fixture tokens used across tests — never in src.)
_DOMAIN_LITERALS = [
    "FiberFault",
    "LinkDown",
    "AdjDown",
    "PortDown",
    "InterfaceDown",
    "FiberCut",
    "CardFail",
    "SC-FIBER",
    "SC-CARD",
    "communicationsAlarm",
]


def test_no_domain_literals_in_source():
    """No domain-specific alarm type / fault-origin name / scenarioId appears anywhere in src.

    The whole pipeline is domain-agnostic: alarm vocabulary comes from alarms[].alarmType, scenario
    chains from the Codebook, thresholds/weights/grouping from Knowledge. A source scan must find no
    domain literal (spec Non-functional / AC-17; CLAUDE.md shared-code rule).
    """
    offenders: list[str] = []
    for path in SRC.rglob("*.py"):
        text = path.read_text()
        for literal in _DOMAIN_LITERALS:
            if literal in text:
                offenders.append(f"{path.relative_to(SRC)} -> {literal!r}")
    assert not offenders, "domain-specific literal(s) found in service source:\n" + "\n".join(
        offenders
    )
