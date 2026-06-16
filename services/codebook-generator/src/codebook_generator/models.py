"""Service-internal domain models (NOT contract events).

These Pydantic models type the data fetched from collaborators (Knowledge fault-origin
types, propagation templates, alarm-type vocabulary; Topology nodes/traversal; Trail Builder
trails) and the compiled codebook (scenarios, predicted-symptom signatures). The Kafka
envelope/payload contract types come from ``acp_event_model`` and are never redefined here.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field


# --- Knowledge: fault-origin types ---
class FaultOriginType(BaseModel):
    """A graph object type that can be a root cause for a domain."""

    objectType: str
    originAlarmType: str | None = None
    description: str | None = None


# --- Knowledge: propagation templates ---
class TemplateEndpoint(BaseModel):
    """The ``trigger`` / ``effect`` half of a propagation template."""

    objectType: str
    alarmType: str


class Traversal(BaseModel):
    """Edge traversal direction/cardinality for a template (informational)."""

    direction: str | None = None
    cardinality: str | None = None


class PropagationTemplate(BaseModel):
    """A per-edge-type fault cascade rule (read from Knowledge; never authored here)."""

    edgeType: str
    trigger: TemplateEndpoint
    effect: TemplateEndpoint
    traversal: Traversal | None = None
    ordering: int | None = None


# --- Topology: nodes / traversal ---
class NodeDto(BaseModel):
    """A topology graph node."""

    managedObjectId: str
    objectType: str
    domain: str
    snapshotId: str | None = None
    name: str | None = None
    attributes: dict = Field(default_factory=dict)


class NodeListDto(BaseModel):
    """Topology ``list by type`` response."""

    domain: str
    objectType: str | None = None
    snapshotId: str | None = None
    count: int = 0
    nodes: list[NodeDto] = Field(default_factory=list)


class TraversalEdge(BaseModel):
    """A directed, typed edge in a bounded traversal result.

    Conforms to Topology's published ``EdgeDto`` (``services/topology/openapi.json``):
    the directed endpoints are wired as ``from``/``to`` on the wire. ``from`` is a Python
    keyword, so it is bound via a Pydantic field alias to ``from_``; ``populate_by_name``
    keeps construction by field name (``from_=...``) working in tests/fixtures.
    """

    model_config = ConfigDict(populate_by_name=True)

    from_: str = Field(alias="from")
    to: str
    relation: str


class TraversalDto(BaseModel):
    """Topology ``bounded traverse`` response.

    ``reached`` are the nodes reached from ``start``; ``edges`` (when the producer
    supplies them) carry the typed connectivity used to build the propagation graph.
    """

    start: str
    domain: str
    relations: list[str] = Field(default_factory=list)
    maxDepth: int = 0
    crossDomain: bool = False
    reached: list[NodeDto] = Field(default_factory=list)
    edges: list[TraversalEdge] = Field(default_factory=list)


# --- Trail Builder ---
class TrailsForObjectResponse(BaseModel):
    """Trail Builder ``GET /trails/by-object`` response."""

    managedObjectId: str
    domain: str
    trailIds: list[str] = Field(default_factory=list)


# --- Compiled codebook (internal representation) ---
class PredictedSymptom(BaseModel):
    """One symptom in a scenario signature: an alarm-type token on an object."""

    alarmType: str
    managedObjectId: str


class Scenario(BaseModel):
    """A compiled codebook scenario (one candidate root-cause instance)."""

    scenarioId: str
    faultOriginObjectId: str
    faultOriginType: str
    predictedSymptoms: list[PredictedSymptom] = Field(default_factory=list)
    trailIds: list[str] = Field(default_factory=list)


class Codebook(BaseModel):
    """A compiled codebook: metadata + scenarios."""

    codebookId: str
    snapshotId: str
    domain: str
    scenarioCount: int
    knowledgeVersion: str | None = None
    scenarios: list[Scenario] = Field(default_factory=list)
