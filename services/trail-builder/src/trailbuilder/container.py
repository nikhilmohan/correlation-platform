"""Composition root — wires settings, clients, repository, and services.

Lets the API and the consumer share one set of collaborators, and lets tests
construct a container with stubbed clients / an in-memory DB.
"""

from __future__ import annotations

from dataclasses import dataclass

import httpx
from sqlalchemy import Engine

from .build_service import BuildService
from .clients.policy_client import KnowledgePolicyClient
from .clients.topology_client import TopologyClient
from .closure import TrailClosure
from .config import Settings
from .event_publisher import Producer, TrailsBuiltPublisher
from .idempotency import IdempotencyStore
from .kafka_consumer import KnowledgeUpdatedHandler, TopologyChangedHandler
from .repository import TrailRepository


@dataclass
class Container:
    """Holds the constructed collaborators for one running service instance."""

    settings: Settings
    engine: Engine
    repository: TrailRepository
    topology: TopologyClient
    policy: KnowledgePolicyClient
    closure: TrailClosure
    publisher: TrailsBuiltPublisher
    build_service: BuildService
    idempotency: IdempotencyStore
    topology_changed_handler: TopologyChangedHandler
    knowledge_updated_handler: KnowledgeUpdatedHandler


def build_container(
    settings: Settings,
    engine: Engine,
    producer: Producer,
    topology_client: TopologyClient | None = None,
    policy_client: KnowledgePolicyClient | None = None,
    httpx_client: httpx.Client | None = None,
) -> Container:
    """Construct a :class:`Container` from settings + an engine + a producer."""
    topology = topology_client or TopologyClient(settings, client=httpx_client)
    policy = policy_client or KnowledgePolicyClient(settings, client=httpx_client)
    repository = TrailRepository(engine, settings.trail_retention_snapshots)
    closure = TrailClosure()
    publisher = TrailsBuiltPublisher(producer, settings.trails_built_topic, settings.service_name)
    build_service = BuildService(settings, topology, policy, repository, closure, publisher)
    idempotency = IdempotencyStore(engine)
    return Container(
        settings=settings,
        engine=engine,
        repository=repository,
        topology=topology,
        policy=policy,
        closure=closure,
        publisher=publisher,
        build_service=build_service,
        idempotency=idempotency,
        topology_changed_handler=TopologyChangedHandler(
            settings, idempotency, build_service, producer
        ),
        knowledge_updated_handler=KnowledgeUpdatedHandler(policy),
    )
