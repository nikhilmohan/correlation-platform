"""Shared pytest fixtures for the simulator pure-logic unit suite.

These fixtures build the Core-IP pack and a deterministic (seeded) topology so the
domain-logic tests are reproducible. Kafka / HTTP / process boundaries are NOT touched here —
those are covered by the next agent's IO/CLI/API suite.
"""

from __future__ import annotations

import random
from datetime import UTC, datetime

import networkx as nx
import pytest

from simulator.domains.coreip.pack import CoreIPPack
from simulator.engine import topology_builder
from simulator.engine.domain_pack import DomainPack, TopologyParams


@pytest.fixture
def pack() -> DomainPack:
    """The single MVP domain pack."""
    return CoreIPPack()


@pytest.fixture
def rng() -> random.Random:
    """A seeded RNG so each test is deterministic."""
    return random.Random(1234)


@pytest.fixture
def params() -> TopologyParams:
    """A modest topology (N=20) sufficient to exercise every layer + 10 sites."""
    return TopologyParams(
        node_count=20,
        site_count=10,
        interfaces_per_port=2,
        igp_area_count=3,
    )


@pytest.fixture
def graph(pack: DomainPack, params: TopologyParams, rng: random.Random) -> nx.DiGraph:
    """A built Core-IP topology graph for the standard params."""
    return topology_builder.build_topology(pack, params, rng).graph


@pytest.fixture
def window() -> tuple[datetime, datetime]:
    """A fixed 1-hour synthesis window."""
    start = datetime(2026, 1, 1, 0, 0, 0, tzinfo=UTC)
    end = datetime(2026, 1, 1, 1, 0, 0, tzinfo=UTC)
    return start, end
