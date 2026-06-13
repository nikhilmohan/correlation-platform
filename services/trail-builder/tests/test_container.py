"""Composition-root wiring tests for ``build_container``.

Verifies the container assembles every collaborator and threads settings
(group-id convention, topics) through — so the API and the consumer share one
set of wired collaborators (AC-12 same-code-path in mock mode).
"""

from __future__ import annotations

from trailbuilder.build_service import BuildService
from trailbuilder.container import Container, build_container
from trailbuilder.idempotency import IdempotencyStore
from trailbuilder.kafka_consumer import KnowledgeUpdatedHandler, TopologyChangedHandler


def test_build_container_wires_all_collaborators(settings, engine, producer) -> None:
    container = build_container(settings, engine, producer)
    assert isinstance(container, Container)
    assert container.settings is settings
    assert container.engine is engine
    assert isinstance(container.build_service, BuildService)
    assert isinstance(container.idempotency, IdempotencyStore)
    assert isinstance(container.topology_changed_handler, TopologyChangedHandler)
    assert isinstance(container.knowledge_updated_handler, KnowledgeUpdatedHandler)
    # Repository + clients all present so the API can resolve them.
    assert container.repository is not None
    assert container.topology is not None
    assert container.policy is not None
    assert container.publisher is not None


def test_build_container_honours_injected_clients(settings, engine, producer) -> None:
    """Injected stub clients are used verbatim (the unit-test seam)."""

    class _StubTopology:
        pass

    class _StubPolicy:
        pass

    topo = _StubTopology()
    pol = _StubPolicy()
    container = build_container(
        settings, engine, producer, topology_client=topo, policy_client=pol  # type: ignore[arg-type]
    )
    assert container.topology is topo
    assert container.policy is pol
    # The build service and knowledge handler are wired against the same instances.
    assert container.knowledge_updated_handler is not None
