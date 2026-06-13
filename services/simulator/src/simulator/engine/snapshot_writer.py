"""Serialize the topology graph to the versioned snapshot file + validate (criteria 1, 14, 27).

Validation is three-layered:
  1. JSON-Schema validation against the **single canonical** topology snapshot schema
     (``services/topology/schema/snapshot.schema.json``, owned by Topology — synced into the
     Simulator's build-time vendor cache, never re-authored: see ``scripts/sync_schema.py``).
  2. Every ``managedObjectId`` validates via ``acp_event_model.validate`` (the same generic
     ``<objectType>:<id>`` scheme the event-model enforces — they never drift).
  3. Referential integrity (every edge endpoint resolves to a node; no dangling refs).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import networkx as nx
from acp_event_model import validate as validate_moid
from jsonschema import Draft202012Validator

SNAPSHOT_SCHEMA_VERSION = 1

_SVC_DIR = Path(__file__).resolve().parents[2]
# Canonical, Topology-owned source (preferred when present in the checkout).
_CANONICAL = _SVC_DIR.parents[1] / "services" / "topology" / "schema" / "snapshot.schema.json"
# Build-time synced cache (verbatim copy of the canonical schema — no re-authoring).
_VENDOR = _SVC_DIR / "_vendor" / "snapshot.schema.json"


class SnapshotValidationError(ValueError):
    """Raised when the snapshot fails schema, moid, or referential validation."""


def canonical_schema_path() -> Path:
    """Return the canonical schema path, preferring the Topology source over the synced cache."""
    if _CANONICAL.exists():
        return _CANONICAL
    if _VENDOR.exists():
        return _VENDOR
    raise SnapshotValidationError(
        "canonical snapshot schema not found; run scripts/sync_schema.py to sync it from "
        "services/topology/schema/snapshot.schema.json"
    )


def load_schema() -> dict[str, Any]:
    """Load the canonical snapshot JSON Schema."""
    return json.loads(canonical_schema_path().read_text())


def graph_to_snapshot(graph: nx.DiGraph, domain: str) -> dict[str, Any]:
    """Serialize a typed ``networkx`` graph into the snapshot-file structure."""
    nodes: list[dict[str, Any]] = []
    for moid, data in graph.nodes(data=True):
        node: dict[str, Any] = {"managedObjectId": moid, "objectType": data["objectType"]}
        attrs = data.get("attributes") or {}
        if "name" in attrs:
            # surface a name when present (schema permits a top-level name)
            node["name"] = str(attrs["name"])
        if attrs:
            node["attributes"] = attrs
        nodes.append(node)
    edges: list[dict[str, Any]] = []
    for src, dst, data in graph.edges(data=True):
        edge: dict[str, Any] = {"from": src, "to": dst, "relation": data["relation"]}
        attrs = data.get("attributes") or {}
        if attrs:
            edge["attributes"] = attrs
        edges.append(edge)
    return {
        "schemaVersion": SNAPSHOT_SCHEMA_VERSION,
        "domain": domain,
        "nodes": nodes,
        "edges": edges,
    }


def validate_snapshot(snapshot: dict[str, Any]) -> None:
    """Run the three validation layers; raise :class:`SnapshotValidationError` on any failure."""
    validator = Draft202012Validator(load_schema())
    errors = sorted(validator.iter_errors(snapshot), key=lambda e: list(e.path))
    if errors:
        first = errors[0]
        raise SnapshotValidationError(
            f"snapshot fails canonical schema: {first.message} at {list(first.path)}"
        )

    node_ids: set[str] = set()
    for node in snapshot["nodes"]:
        moid = node["managedObjectId"]
        validate_moid(moid)  # generic <objectType>:<id> scheme
        prefix = moid.split(":", 1)[0]
        if prefix != node["objectType"]:
            raise SnapshotValidationError(
                f"managedObjectId prefix {prefix!r} != objectType {node['objectType']!r}"
            )
        node_ids.add(moid)

    for edge in snapshot["edges"]:
        for endpoint in ("from", "to"):
            ref = edge[endpoint]
            validate_moid(ref)
            if ref not in node_ids:
                raise SnapshotValidationError(
                    f"edge {endpoint} {ref!r} ({edge['relation']}) is a dangling reference"
                )


def write_snapshot(graph: nx.DiGraph, domain: str, out_path: Path) -> dict[str, Any]:
    """Build, validate and write the snapshot file; return the snapshot dict."""
    snapshot = graph_to_snapshot(graph, domain)
    validate_snapshot(snapshot)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(snapshot, indent=2))
    return snapshot
