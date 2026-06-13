"""Compilation pipeline — orchestrates one compile cycle (spec tasks 2-8).

On a deduped ``trails.built``: resolve domain, fetch domain-scoped fault-origins + templates
+ alarm-type vocabulary (Knowledge), enumerate fault-origin instances (Topology), forward-
propagate each instance's closure to an ordered symptom signature, validate every token
against the fetched vocabulary, tag scenarios to trails (Trail Builder), persist the codebook
atomically (one active per ``(domain, snapshotId)``), and emit ``codebook.generated``.

A duplicate ``eventId`` is a no-op (the prior codebook is preserved and re-emitted).
"""

from __future__ import annotations

from dataclasses import dataclass

from .clients.knowledge import KnowledgeClient
from .clients.topology import SNAPSHOT_CURRENT, TopologyClient
from .clients.trail_builder import TrailBuilderClient
from .logging_config import get_logger
from .metrics import (
    compiled_total,
    events_consumed_total,
    scenarios_generated_total,
)
from .models import FaultOriginType, PropagationTemplate, Scenario
from .producer import CodebookEventProducer
from .propagation import build_closure_graph, propagate
from .store import CodebookStore
from .tagging import tag_scenario
from .vocabulary import validate_scenarios

logger = get_logger(__name__)


@dataclass(slots=True)
class CompileResult:
    """Outcome of a compile cycle."""

    codebook_id: str
    domain: str
    snapshot_id: str
    scenario_count: int
    deduped: bool = False


class CompilationPipeline:
    """Stateless orchestrator (all state lives in the store / collaborators)."""

    def __init__(
        self,
        *,
        knowledge: KnowledgeClient,
        topology: TopologyClient,
        trail_builder: TrailBuilderClient,
        store: CodebookStore,
        producer: CodebookEventProducer,
        traversal_max_depth: int,
    ) -> None:
        self._knowledge = knowledge
        self._topology = topology
        self._trail_builder = trail_builder
        self._store = store
        self._producer = producer
        self._max_depth = traversal_max_depth

    def compile(
        self,
        *,
        event_id: str,
        snapshot_id: str,
        domain: str,
        trace_id: str,
    ) -> CompileResult:
        """Run one compile cycle for ``(domain, snapshot_id)``; idempotent on ``event_id``."""
        log_ctx = {"eventId": event_id, "domain": domain, "snapshotId": snapshot_id}
        events_consumed_total.labels(domain=domain).inc()

        prior = self._store.already_processed(event_id)
        if prior is not None:
            logger.info("duplicate eventId — compile is a no-op", extra=log_ctx)
            if prior:
                meta = self._store.get_codebook_meta(prior)
                count = meta["scenario_count"] if meta else 0
                self._producer.emit(
                    snapshot_id=snapshot_id,
                    scenario_count=count,
                    codebook_id=prior,
                    domain=domain,
                    trace_id=trace_id,
                )
                return CompileResult(prior, domain, snapshot_id, count, deduped=True)
            return CompileResult("", domain, snapshot_id, 0, deduped=True)

        # Task 3 — fetch domain-scoped Knowledge inputs.
        fault_origin_types = self._knowledge.get_fault_origin_types(domain)
        templates = self._knowledge.get_propagation_templates(domain)
        vocabulary = self._knowledge.get_alarm_type_vocabulary(domain)

        # Tasks 4-6 — enumerate, propagate, tag.
        scenarios = self._build_scenarios(
            snapshot_id=snapshot_id,
            domain=domain,
            fault_origin_types=fault_origin_types,
            templates=templates,
        )

        # Task 5/Q7 — validate every token against the fetched vocabulary (raises on miss).
        validate_scenarios(scenarios, vocabulary)

        # Task 7 — persist atomically (one active per key).
        codebook_id = self._store.persist_codebook(
            event_id=event_id,
            snapshot_id=snapshot_id,
            domain=domain,
            scenarios=scenarios,
        )
        compiled_total.labels(domain=domain).inc()
        scenarios_generated_total.labels(domain=domain).inc(len(scenarios))
        logger.info(
            "codebook compiled",
            extra={**log_ctx, "codebookId": codebook_id},
        )

        # Task 8 — emit codebook.generated.
        self._producer.emit(
            snapshot_id=snapshot_id,
            scenario_count=len(scenarios),
            codebook_id=codebook_id,
            domain=domain,
            trace_id=trace_id,
        )
        return CompileResult(codebook_id, domain, snapshot_id, len(scenarios))

    def _build_scenarios(
        self,
        *,
        snapshot_id: str,
        domain: str,
        fault_origin_types: list[FaultOriginType],
        templates: list[PropagationTemplate],
    ) -> list[Scenario]:
        relations = sorted({t.edgeType for t in templates})
        scenarios: list[Scenario] = []

        for fot in fault_origin_types:
            node_list = self._topology.list_objects_by_type(
                fot.objectType, domain, SNAPSHOT_CURRENT
            )
            for node in node_list.nodes:
                traversal = self._topology.traverse(
                    start=node.managedObjectId,
                    relations=relations,
                    domain=domain,
                    max_depth=self._max_depth,
                    snapshot_id=SNAPSHOT_CURRENT,
                )
                closure = build_closure_graph(node, traversal)
                symptoms = propagate(node, closure, templates, fault_origin_types)
                scenario = Scenario(
                    scenarioId=node.managedObjectId,  # provisional; store rewrites to codebook-scoped id
                    faultOriginObjectId=node.managedObjectId,
                    faultOriginType=node.objectType,
                    predictedSymptoms=symptoms,
                )
                scenario.trailIds = tag_scenario(scenario, domain, self._trail_builder)
                scenarios.append(scenario)
        return scenarios
