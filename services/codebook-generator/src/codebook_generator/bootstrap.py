"""Composition root — wire clients, store, producer, pipeline from :class:`Settings`.

The same code path serves MOCK and REAL modes; only config (URLs + ``MODE``) differs. Unit
tests back the ``httpx`` clients with a mocked transport (respx); integration points the same
clients at live services. Required integration-point URLs are validated fast at startup.
"""

from __future__ import annotations

from dataclasses import dataclass

import httpx

from .clients.knowledge import KnowledgeClient
from .clients.topology import TopologyClient
from .clients.trail_builder import TrailBuilderClient
from .config import Settings
from .consumer import DlqRouter, TrailsBuiltHandler
from .pipeline import CompilationPipeline
from .producer import CodebookEventProducer, MessageProducer
from .store import CodebookStore


@dataclass(slots=True)
class Components:
    """Assembled service components."""

    store: CodebookStore
    pipeline: CompilationPipeline
    handler: TrailsBuiltHandler
    http_client: httpx.Client


def build_components(
    settings: Settings,
    *,
    message_producer: MessageProducer,
    store: CodebookStore | None = None,
    http_client: httpx.Client | None = None,
) -> Components:
    """Assemble the runtime components (validates required URLs first)."""
    settings.require_integration_urls()
    settings.require_database_url()

    client = http_client or httpx.Client(timeout=10.0)
    the_store = store or CodebookStore.from_url(settings.database_url)

    knowledge = KnowledgeClient(
        fault_origins_base_url=settings.knowledge_fault_origins_url,
        propagation_templates_base_url=settings.knowledge_propagation_templates_url,
        alarm_type_vocabulary_base_url=settings.knowledge_alarm_type_vocabulary_url,
        client=client,
        max_retries=settings.integration_max_retries,
        backoff_ms=settings.integration_backoff_ms,
    )
    topology = TopologyClient(
        base_url=settings.topology_query_url,
        client=client,
        max_retries=settings.integration_max_retries,
        backoff_ms=settings.integration_backoff_ms,
    )
    trail_builder = TrailBuilderClient(
        base_url=settings.trail_builder_url,
        client=client,
        max_retries=settings.integration_max_retries,
        backoff_ms=settings.integration_backoff_ms,
    )

    event_producer = CodebookEventProducer(
        message_producer,
        topic=settings.codebook_generated_topic,
        dlq_topic=settings.codebook_generated_dlq_topic,
    )
    pipeline = CompilationPipeline(
        knowledge=knowledge,
        topology=topology,
        trail_builder=trail_builder,
        store=the_store,
        producer=event_producer,
        traversal_max_depth=settings.traversal_max_depth,
    )
    dlq = DlqRouter(message_producer, settings.trails_built_dlq_topic)
    handler = TrailsBuiltHandler(
        pipeline=pipeline,
        dlq=dlq,
        default_domain=settings.default_domain,
    )
    return Components(
        store=the_store, pipeline=pipeline, handler=handler, http_client=client
    )
