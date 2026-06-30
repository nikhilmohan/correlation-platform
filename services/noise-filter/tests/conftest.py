"""Shared pytest fixtures.

The run-stats + observed-chatter stores are exercised against the in-memory repository
stand-ins (the design's permitted unit-test backing). Outbound integration points are backed by
respx mocks generated from the collaborators' published OpenAPI shapes. ``TransactionEvent``s are
validated against the frozen ``libs/event-model`` JSON Schema.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from noise_filter.clients import TrailBuilderClient, TrailContext
from noise_filter.cluster import Clusterer
from noise_filter.config import (
    FeatureConfig,
    FeatureSettings,
    ModelParams,
    ParamStore,
)
from noise_filter.emit import TransactionEmitter
from noise_filter.features import FeatureVectorizer, HopDistanceResolver
from noise_filter.metrics import Metrics
from noise_filter.pipeline import Pipeline
from noise_filter.repository import (
    InMemoryObservedChatterRepository,
    InMemoryRunStatsRepository,
)
from noise_filter.stats import ObservedChatterRecorder, RunStatsRecorder

_SCHEMA_ROOT = Path(__file__).resolve().parents[3] / "libs" / "event-model" / "schema"


@pytest.fixture(scope="session")
def transaction_event_schema() -> dict[str, Any]:
    """Load the frozen TransactionEvent JSON Schema (with the managedObjectId $ref inlined)."""
    schema_path = _SCHEMA_ROOT / "payloads" / "TransactionEvent.schema.json"
    schema = json.loads(schema_path.read_text())
    mo_path = _SCHEMA_ROOT / "common" / "managedObjectId.schema.json"
    mo_schema = json.loads(mo_path.read_text())
    # Inline the managedObjectId $ref so jsonschema validates standalone.
    items = schema["properties"]["alarms"]["items"]
    items["properties"]["managedObjectId"] = {
        k: v for k, v in mo_schema.items() if k not in ("$schema", "$id")
    }
    return schema


class FakeTrailBuilderClient(TrailBuilderClient):
    """In-process TrailBuilder stub returning a fixed TrailContext (no HTTP)."""

    def __init__(self, ctx: TrailContext) -> None:  # noqa: D107
        self._ctx = ctx

    def get_trail(self, trail_id: str) -> TrailContext:  # type: ignore[override]
        return TrailContext(
            trail_id=trail_id,
            snapshot_id=self._ctx.snapshot_id,
            domain=self._ctx.domain,
            member_ids=self._ctx.member_ids,
            edges=self._ctx.edges,
            seed_id=self._ctx.seed_id,
        )


def make_trail_ctx(
    *,
    trail_id: str = "t1",
    snapshot_id: str = "snap-1",
    domain: str | None = "core-ip",
    member_ids: list[str] | None = None,
    edges: list[tuple[str, str]] | None = None,
    seed_id: str | None = None,
) -> TrailContext:
    return TrailContext(
        trail_id=trail_id,
        snapshot_id=snapshot_id,
        domain=domain,
        member_ids=member_ids or [],
        edges=edges or [],
        seed_id=seed_id,
    )


@pytest.fixture
def metrics() -> Metrics:
    return Metrics()


@pytest.fixture
def run_repo() -> InMemoryRunStatsRepository:
    return InMemoryRunStatsRepository()


@pytest.fixture
def chatter_repo() -> InMemoryObservedChatterRepository:
    return InMemoryObservedChatterRepository()


def build_pipeline(
    *,
    params: ModelParams,
    features: FeatureSettings | None = None,
    run_repo: InMemoryRunStatsRepository,
    chatter_repo: InMemoryObservedChatterRepository,
    metrics: Metrics,
    trail_ctx: TrailContext | None = None,
    topology_client=None,
    fault_origin_ids: frozenset[str] = frozenset(),
    oracle_valid_ids: dict[str, set[str]] | None = None,
    producer=None,
) -> Pipeline:
    """Assemble a Pipeline with in-memory repos + a fake TrailBuilder returning ``trail_ctx``."""
    features = features or FeatureSettings.fallback()
    param_store = ParamStore(params)
    feature_config = FeatureConfig(features)
    ctx = trail_ctx or make_trail_ctx()
    tb_client = FakeTrailBuilderClient(ctx)
    hop_resolver = (
        HopDistanceResolver(tb_client, fault_origin_ids=fault_origin_ids)
        if features.hop_distance_enabled
        else None
    )
    vectorizer = FeatureVectorizer(
        topology_client=topology_client, hop_resolver=hop_resolver, metrics=metrics
    )
    return Pipeline(
        param_store=param_store,
        feature_config=feature_config,
        vectorizer=vectorizer,
        clusterer=Clusterer(metrics=metrics),
        emitter=TransactionEmitter(metrics=metrics),
        run_stats_recorder=RunStatsRecorder(run_repo, metrics=metrics),
        chatter_recorder=ObservedChatterRecorder(chatter_repo, metrics=metrics),
        trail_builder_client=tb_client,
        producer=producer,
        metrics=metrics,
        oracle_valid_ids=oracle_valid_ids,
    )
