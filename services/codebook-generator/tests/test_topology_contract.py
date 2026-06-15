"""Consumer-side contract/conformance test for Topology's published ``EdgeDto`` (#257).

The codebook-generator builds its Topology traversal client against Topology's **published
OpenAPI** (``services/topology/openapi.json``), never an assumed shape. Topology's frozen
``EdgeDto`` wires the directed closure endpoints as ``from``/``to`` (see #252). A prior bug
shipped paper-green because the unit cascade tests used a *synthetic* closure keyed by
``source``/``target`` — the live generator crashed with a Pydantic ``ValidationError`` on the
real ``from``/``to`` wire shape and exited (0 codebooks).

This test binds the codebook-generator's ``TraversalEdge`` / ``TraversalDto`` models directly
to Topology's checked-in ``EdgeDto`` schema, so the client can no longer drift: it MUST fail
if anyone reverts the endpoint fields to ``source``/``target``.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from codebook_generator.models import TraversalDto, TraversalEdge

# repo-root/services/topology/openapi.json  (test is services/codebook-generator/tests/<file>)
_TOPOLOGY_OPENAPI = Path(__file__).resolve().parents[3] / "services" / "topology" / "openapi.json"


def _edge_dto_schema() -> dict:
    """Load Topology's published ``EdgeDto`` schema (skip if topology not vendored)."""
    if not _TOPOLOGY_OPENAPI.exists():
        pytest.skip(f"topology openapi.json not present at {_TOPOLOGY_OPENAPI}")
    doc = json.loads(_TOPOLOGY_OPENAPI.read_text())
    schemas = doc.get("components", {}).get("schemas", {})
    edge = schemas.get("EdgeDto")
    assert edge is not None, "topology openapi.json has no EdgeDto schema"
    return edge


def _sample_edge_from_schema(schema: dict) -> dict:
    """Build a wire-shaped edge dict using Topology's exact EdgeDto property names."""
    props = set(schema.get("properties", {}))
    # The endpoint fields are the load-bearing part of the contract.
    sample: dict[str, object] = {}
    if "from" in props:
        sample["from"] = "FiberSpan:f1"
    if "to" in props:
        sample["to"] = "IPLink:l1"
    if "relation" in props:
        sample["relation"] = "RIDES_ON"
    if "edgeId" in props:
        sample["edgeId"] = "E-1"
    if "domain" in props:
        sample["domain"] = "core-ip"
    if "attributes" in props:
        sample["attributes"] = {}
    if "snapshotId" in props:
        sample["snapshotId"] = "SNAP-1"
    return sample


def test_edge_dto_uses_from_to_endpoints() -> None:
    """Guard the assumption under test: Topology's EdgeDto wires endpoints as from/to."""
    props = set(_edge_dto_schema().get("properties", {}))
    assert (
        "from" in props and "to" in props
    ), "topology EdgeDto no longer exposes from/to; codebook client mapping must be revisited"
    # And it is NOT source/target — the shape the broken client assumed.
    assert "source" not in props and "target" not in props


def test_traversal_edge_parses_topology_edge_dto() -> None:
    """The codebook ``TraversalEdge`` parses an edge shaped EXACTLY like Topology's EdgeDto.

    Fails (Field required: from) if the model is reverted to ``source``/``target``.
    """
    schema = _edge_dto_schema()
    wire = _sample_edge_from_schema(schema)

    edge = TraversalEdge.model_validate(wire)

    assert edge.from_ == "FiberSpan:f1"
    assert edge.to == "IPLink:l1"
    assert edge.relation == "RIDES_ON"


def test_traversal_dto_parses_response_with_topology_edges() -> None:
    """A full ``/topology/traversal`` response (edges in EdgeDto shape) validates end-to-end."""
    schema = _edge_dto_schema()
    wire_edge = _sample_edge_from_schema(schema)
    response = {
        "start": "FiberSpan:f1",
        "domain": "core-ip",
        "relations": ["RIDES_ON"],
        "maxDepth": 8,
        "crossDomain": False,
        "reached": [{"managedObjectId": "IPLink:l1", "objectType": "IPLink", "domain": "core-ip"}],
        "edges": [wire_edge],
    }

    dto = TraversalDto.model_validate(response)

    assert len(dto.edges) == 1
    assert dto.edges[0].from_ == "FiberSpan:f1"
    assert dto.edges[0].to == "IPLink:l1"


def test_source_target_edge_is_rejected() -> None:
    """A regression to the old source/target wire shape MUST be rejected (no from/to)."""
    with pytest.raises(ValidationError):
        TraversalEdge.model_validate(
            {"source": "FiberSpan:f1", "target": "IPLink:l1", "relation": "RIDES_ON"}
        )
