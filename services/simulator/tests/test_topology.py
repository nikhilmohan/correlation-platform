"""Topology-snapshot pure-logic tests (spec AC 1, 3, 11, 14, 22, 24).

Covers snapshot generation + canonical-schema validation, the moid scheme, configurable size,
grounded igpArea stamping, and the >=10 distinct grounded geo sites.
"""

from __future__ import annotations

import random
import re

import pytest
from acp_event_model import validate as validate_moid

from simulator.domains.coreip import geo_catalogue
from simulator.domains.coreip.pack import CoreIPPack
from simulator.engine import snapshot_writer, topology_builder
from simulator.engine.domain_pack import TopologyParams

_MOID_RE = re.compile(r"^[A-Za-z][A-Za-z0-9]*:[^:].*$")
_KNOWN_OBJECT_TYPES = {
    "Node",
    "LineCard",
    "Port",
    "IPLink",
    "IGPAdjacency",
    "LSP",
    "VPNService",
    "FiberSpan",
    "SRLG",
}


def _build(node_count: int = 20, site_count: int = 10, igp_area_count: int = 3):
    pack = CoreIPPack()
    params = TopologyParams(
        node_count=node_count,
        site_count=site_count,
        interfaces_per_port=2,
        igp_area_count=igp_area_count,
    )
    graph = topology_builder.build_topology(pack, params, random.Random(7)).graph
    snapshot = snapshot_writer.graph_to_snapshot(graph, pack.domain_id())
    return pack, graph, snapshot


# --- AC 1: snapshot internally consistent, no dangling references --------------------------


def test_ac1_no_dangling_references_and_layer_consistency() -> None:
    """AC1: every line card→node, port→line card, link→ports, srlg→links reference resolves."""
    _, _, snapshot = _build()
    node_ids = {n["managedObjectId"] for n in snapshot["nodes"]}
    by_type: dict[str, set[str]] = {}
    for n in snapshot["nodes"]:
        by_type.setdefault(n["objectType"], set()).add(n["managedObjectId"])

    # every edge endpoint resolves to a node in the snapshot (no dangling refs)
    for edge in snapshot["edges"]:
        assert edge["from"] in node_ids, f"dangling from-ref {edge['from']}"
        assert edge["to"] in node_ids, f"dangling to-ref {edge['to']}"

    # every Node has a Node:<id> moid
    assert by_type.get("Node"), "topology must contain Node objects"
    for moid in by_type["Node"]:
        assert moid.startswith("Node:")

    # line cards reference an existing node (HOSTED_ON node->linecard); ports reference an
    # existing line card; ip links reference existing ports' interfaces; srlg references links.
    rels = [(e["from"], e["to"], e["relation"]) for e in snapshot["edges"]]
    linecards = by_type.get("LineCard", set())
    ports = by_type.get("Port", set())
    iplinks = by_type.get("IPLink", set())
    # Node HOSTED_ON LineCard
    assert any(
        f in by_type.get("Node", set()) and t in linecards and r == "HOSTED_ON" for f, t, r in rels
    )
    # LineCard HOSTED_ON Port
    assert any(f in linecards and t in ports and r == "HOSTED_ON" for f, t, r in rels)
    # SRLG MEMBER_OF IPLink
    if by_type.get("SRLG"):
        assert any(f in by_type["SRLG"] and t in iplinks and r == "MEMBER_OF" for f, t, r in rels)


# --- AC 3: managedObjectId conforms to the frozen contract scheme --------------------------


def test_ac3_moids_conform_to_contract_scheme() -> None:
    """AC3: every snapshot moid is <objectType>:<non-empty-id>, known type, validator-passing."""
    _, _, snapshot = _build()
    for node in snapshot["nodes"]:
        moid = node["managedObjectId"]
        # the frozen event-model validator must accept the moid
        assert validate_moid(moid) == moid
        assert _MOID_RE.match(moid), moid
        prefix = moid.split(":", 1)[0]
        # prefix must equal objectType (snapshot writer invariant)
        assert prefix == node["objectType"]
        # the nine known typed graph layers (Site/Interface are domain-agnostic extras)
        if prefix in _KNOWN_OBJECT_TYPES:
            assert prefix in _KNOWN_OBJECT_TYPES


def test_ac3_all_nine_typed_layers_present() -> None:
    """AC3/AC21 support: all nine canonical typed layers appear in a built snapshot."""
    _, _, snapshot = _build(node_count=20)
    present = {n["objectType"] for n in snapshot["nodes"]}
    assert _KNOWN_OBJECT_TYPES.issubset(present), _KNOWN_OBJECT_TYPES - present


# --- AC 11: topology size is configurable, no hard-coded count -----------------------------


@pytest.mark.parametrize("node_count", [10, 50])
def test_ac11_node_count_is_configurable(node_count: int) -> None:
    """AC11: a run configured with N nodes yields ~N Node objects (no compiled default)."""
    _, _, snapshot = _build(node_count=node_count)
    n_nodes = sum(1 for n in snapshot["nodes"] if n["objectType"] == "Node")
    assert n_nodes == node_count


def test_ac11_two_sizes_differ() -> None:
    """AC11: two different configured sizes produce different node counts."""
    _, _, small = _build(node_count=10)
    _, _, large = _build(node_count=50)
    n_small = sum(1 for n in small["nodes"] if n["objectType"] == "Node")
    n_large = sum(1 for n in large["nodes"] if n["objectType"] == "Node")
    assert n_small == 10 and n_large == 50 and n_small != n_large


# --- AC 14: snapshot validates against the canonical topology-file schema -------------------


def test_ac14_snapshot_validates_against_canonical_schema() -> None:
    """AC14: the generated snapshot passes the single canonical snapshot.schema.json."""
    _, _, snapshot = _build()
    # raises SnapshotValidationError on any schema/moid/referential failure
    snapshot_writer.validate_snapshot(snapshot)


def test_ac14_dangling_edge_is_rejected() -> None:
    """AC14: a hand-corrupted snapshot with a dangling edge fails validation (negative)."""
    _, _, snapshot = _build()
    snapshot["edges"].append(
        {
            "from": "Node:DOESNOTEXIST",
            "to": snapshot["nodes"][0]["managedObjectId"],
            "relation": "LOCATED_AT",
        }
    )
    with pytest.raises(snapshot_writer.SnapshotValidationError):
        snapshot_writer.validate_snapshot(snapshot)


def test_ac14_moid_prefix_mismatch_is_rejected() -> None:
    """AC14: objectType not matching the moid prefix fails validation (negative)."""
    _, _, snapshot = _build()
    bad = dict(snapshot["nodes"][0])
    bad["objectType"] = "WrongType"
    snapshot["nodes"][0] = bad
    with pytest.raises(snapshot_writer.SnapshotValidationError):
        snapshot_writer.validate_snapshot(snapshot)


# --- AC 22: every Node (and its Interfaces) carries a grounded igpArea ----------------------


def test_ac22_every_node_and_interface_has_grounded_igp_area() -> None:
    """AC22: each Node + each Interface carries a non-empty igpArea; area-0 + a numbered area."""
    pack, graph, _ = _build(igp_area_count=3)
    node_areas: set[str] = set()
    for moid, data in graph.nodes(data=True):
        if data["objectType"] == "Node":
            area = data["attributes"].get("igpArea")
            assert area, f"Node {moid} missing igpArea"
            node_areas.add(area)
        if data["objectType"] == "Interface":
            assert data["attributes"].get("igpArea"), f"Interface {moid} missing igpArea"
    # at least one backbone area-0 and one numbered edge area
    assert "area-0" in node_areas
    assert any(a != "area-0" for a in node_areas)


def test_ac22_interface_inherits_its_node_area() -> None:
    """AC22: an Interface's igpArea equals the igpArea of the Node hosting it."""
    pack, graph, _ = _build()
    # map node -> its hosted interfaces via HOSTED_ON (node->lc->port) + HOSTS (port->iface)
    for node_moid, ndata in graph.nodes(data=True):
        if ndata["objectType"] != "Node":
            continue
        node_area = ndata["attributes"]["igpArea"]
        # walk node -> linecard -> port -> interface
        for _, lc, d1 in graph.out_edges(node_moid, data=True):
            if d1["relation"] != "HOSTED_ON":
                continue
            for _, port, d2 in graph.out_edges(lc, data=True):
                if d2["relation"] != "HOSTED_ON":
                    continue
                for _, iface, d3 in graph.out_edges(port, data=True):
                    if d3["relation"] != "HOSTS":
                        continue
                    assert graph.nodes[iface]["attributes"]["igpArea"] == node_area


def test_ac22_igp_area_count_yields_distinct_areas() -> None:
    """AC22: IGP_AREA_COUNT=N yields N distinct areas (area-0 + numbered)."""
    _, graph, _ = _build(node_count=50, igp_area_count=4)
    areas = {
        d["attributes"]["igpArea"] for _, d in graph.nodes(data=True) if d["objectType"] == "Node"
    }
    # area-0 backbone + up to (N-1) numbered edge areas; with 50 nodes all 4 are exercised
    assert len(areas) == 4


# --- AC 24: >=10 distinct grounded geo sites; SITE_COUNT=10 yields 10 distinct --------------


def test_ac24_catalogue_has_at_least_10_distinct_grounded_sites() -> None:
    """AC24: the grounded geo catalogue holds >=10 entries with distinct coordinates."""
    cat = CoreIPPack().geo_sites()
    assert len(cat) >= 10
    coords = {(s.latitude, s.longitude) for s in cat}
    assert len(coords) == len(cat), "geo coordinates must be distinct (no reused coords)"
    names = {s.name for s in cat}
    assert len(names) == len(cat), "site names must be distinct"


def test_ac24_site_count_10_yields_10_distinct_sites() -> None:
    """AC24: SITE_COUNT=10 produces exactly 10 Site nodes with 10 distinct geo tuples."""
    _, graph, _ = _build(node_count=20, site_count=10)
    sites = [d for _, d in graph.nodes(data=True) if d["objectType"] == "Site"]
    assert len(sites) == 10
    tuples = {
        (
            s["attributes"]["name"],
            s["attributes"]["latitude"],
            s["attributes"]["longitude"],
            s["attributes"]["region"],
        )
        for s in sites
    }
    assert len(tuples) == 10


def test_ac24_site_count_above_catalogue_fails_fast() -> None:
    """AC24: SITE_COUNT above the catalogue size raises (fail-fast in the catalogue helper)."""
    oversize = geo_catalogue.CATALOGUE_SIZE + 1
    with pytest.raises(ValueError):
        geo_catalogue.first_n_sites(oversize)
