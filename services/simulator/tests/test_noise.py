"""Noise + synthesis pure-logic tests (spec AC 6, 13, 25).

Exercises the pack's >=3 noise classes, that noise is identifiable solely by absence from any
label's children, that the noise rate is configurable (rate=0 -> zero noise), and the
synthesizer's separation of signal / background / noise.
"""

from __future__ import annotations

import random
from datetime import UTC, datetime

import networkx as nx
import pytest

from simulator.config.settings import load_settings
from simulator.domains.coreip.scenario_library import NOISE_CLASSES
from simulator.engine import noise, scenario_runner
from simulator.engine.domain_pack import DomainPack

_START = datetime(2026, 1, 1, tzinfo=UTC)
_END = datetime(2026, 1, 1, 1, 0, 0, tzinfo=UTC)


def _ids():
    import itertools

    return (f"ALM-{i:07d}" for i in itertools.count())


# --- AC 6: at least 3 noise classes --------------------------------------------------------


def test_ac6_pack_ships_at_least_three_noise_classes(pack: DomainPack) -> None:
    """AC6: the pack declares >=3 distinct noise classes."""
    classes = pack.noise_classes()
    assert len(classes) >= 3
    names = {c.name for c in classes}
    assert len(names) == len(classes)


def test_ac6_generate_noise_covers_at_least_three_classes(
    pack: DomainPack, graph: nx.DiGraph
) -> None:
    """AC6: a noise run emits alarms across >=3 distinct configured noise classes."""
    mix = {c.name: 1.0 for c in NOISE_CLASSES}
    out = noise.generate_noise(
        pack,
        graph,
        count=200,
        noise_mix=mix,
        hard_noise_fraction=0.4,
        window_start=_START,
        window_end=_END,
        cascade_times=[_START],
        rng=random.Random(3),
        alarm_id_seq=_ids(),
    )
    emitted_classes = {a.noise_class for a in out}
    assert len(emitted_classes) >= 3
    # every noise alarm is flagged as noise and carries a valid vocabulary token
    vocab = set(pack.alarm_type_vocabulary())
    assert all(a.is_noise for a in out)
    assert all(a.alarm_type in vocab for a in out)


def test_ac6_noise_absent_from_any_label_children(pack: DomainPack, graph: nx.DiGraph) -> None:
    """AC6/AC25: noise + background alarms appear in no ground-truth label's children."""
    settings = load_settings(
        {
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "TOPOLOGY_NODE_COUNT": "20",
            "SITE_COUNT": "10",
            "INTERFACES_PER_PORT": "2",
            "SCENARIO_INSTANCES": "2",
            "NOISE_RATE": "0.2",
            "BACKGROUND_FRACTION": "0.2",
        }
    )
    result = scenario_runner.synthesize(pack, graph, settings, random.Random(5), _START, _END)
    label_children: set[str] = set()
    for label in result.labels.all():
        label_children.update(label.children)
        label_children.add(label.root_cause)
    for a in result.alarms:
        if a.is_noise or a.is_background:
            assert a.alarm_id not in label_children


# --- AC 13: noise mix is configurable — no hard-coded rate ---------------------------------


def test_ac13_noise_rate_zero_emits_no_noise(pack: DomainPack, graph: nx.DiGraph) -> None:
    """AC13: a configured noise rate of 0 emits zero noise alarms."""
    settings = load_settings(
        {
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "TOPOLOGY_NODE_COUNT": "20",
            "SITE_COUNT": "10",
            "INTERFACES_PER_PORT": "2",
            "SCENARIO_INSTANCES": "2",
            "NOISE_RATE": "0",
            "BACKGROUND_FRACTION": "0.2",
        }
    )
    result = scenario_runner.synthesize(pack, graph, settings, random.Random(5), _START, _END)
    assert result.noise_count == 0
    assert not any(a.is_noise for a in result.alarms)


def test_ac13_nonzero_noise_rate_emits_noise(pack: DomainPack, graph: nx.DiGraph) -> None:
    """AC13: a non-zero noise rate emits noise alarms."""
    settings = load_settings(
        {
            "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
            "TOPOLOGY_NODE_COUNT": "20",
            "SITE_COUNT": "10",
            "INTERFACES_PER_PORT": "2",
            "SCENARIO_INSTANCES": "2",
            "NOISE_RATE": "0.3",
            "BACKGROUND_FRACTION": "0.2",
        }
    )
    result = scenario_runner.synthesize(pack, graph, settings, random.Random(5), _START, _END)
    assert result.noise_count > 0
    assert any(a.is_noise for a in result.alarms)


def test_ac13_different_noise_rates_change_ratio(pack: DomainPack, graph: nx.DiGraph) -> None:
    """AC13: two runs with different noise rates produce different noise-to-signal ratios."""

    def run(noise_rate: str) -> float:
        settings = load_settings(
            {
                "KAFKA_BOOTSTRAP_SERVERS": "localhost:9092",
                "TOPOLOGY_NODE_COUNT": "20",
                "SITE_COUNT": "10",
                "INTERFACES_PER_PORT": "2",
                "SCENARIO_INSTANCES": "2",
                "NOISE_RATE": noise_rate,
                "BACKGROUND_FRACTION": "0.2",
            }
        )
        res = scenario_runner.synthesize(pack, graph, settings, random.Random(5), _START, _END)
        return res.noise_count / max(1, len(res.alarms))

    low = run("0.1")
    high = run("0.4")
    assert high > low


# --- AC 25 support: background alarms also outside labels ----------------------------------


@pytest.mark.parametrize("klass", NOISE_CLASSES)
def test_noise_class_tokens_in_vocabulary(pack: DomainPack, klass) -> None:
    """Each noise class only emits canonical vocabulary tokens (no off-contract alarmType)."""
    vocab = set(pack.alarm_type_vocabulary())
    assert set(klass.alarm_types).issubset(vocab), set(klass.alarm_types) - vocab
