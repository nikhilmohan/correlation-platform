"""Core-IP typed multi-layer topology builder (criteria 1, 22, 25, 28, 31).

Builds a ``networkx.DiGraph`` of the nine Core-IP layers plus the domain-agnostic ``Site`` and
the §5 #91 ``Interface`` layer. Edge relations: ``LOCATED_AT`` (device→Site), ``HOSTED_ON``
(LineCard→Port), ``HOSTS`` (Port→Interface), ``TERMINATES`` (Interface→IPLink), ``RIDES_ON``
(FiberSpan→IPLink), ``ADJACENCY_OVER`` (Interface→IGPAdjacency), ``TRAVERSES`` (IPLink→LSP),
``SERVES`` (LSP→VPNService), ``MEMBER_OF`` (SRLG→IPLink).

Every device ``Node`` (and the ``Interface``s it hosts) carries a grounded ``igpArea``;
backbone-role nodes land in ``area-0`` and edge nodes in numbered areas. All ``managedObjectId``s
follow the generic ``<objectType>:<id>`` scheme; the snapshot writer + ``acp_event_model`` enforce
that.
"""

from __future__ import annotations

import random

import networkx as nx

from simulator.domains.coreip import geo_catalogue
from simulator.engine.domain_pack import BuildResult, GeoSite, TopologyParams

_VENDORS = ("Acme", "Norvex", "Lumora")
_NODE_MODELS = ("XR-9000", "MX-7200", "PTX-3000")
_LC_MODELS = ("LC-48x100G", "LC-36x400G", "LC-16x100G")
_PORT_MODELS = ("QSFP28", "QSFP-DD", "CFP2")
_CAPACITIES = ("100G", "400G", "1.6Tbps")


def _area_for_role(role: str, area_count: int, edge_area_index: int) -> str:
    """Backbone roles → area-0; edge roles → a numbered edge area (round-robin)."""
    if role in ("P", "RR"):
        return "area-0"
    if area_count <= 1:
        return "area-0"
    return f"area-{1 + (edge_area_index % (area_count - 1))}"


def _device_attrs(role: str, igp_area: str, rng: random.Random) -> dict[str, object]:
    return {
        "vendor": rng.choice(_VENDORS),
        "model": rng.choice(_NODE_MODELS),
        "equipmentType": "router",
        "role": role,
        "capacity": rng.choice(_CAPACITIES),
        "igpArea": igp_area,
    }


def build_topology(  # noqa: C901 - layered construction, kept explicit for clarity
    params: TopologyParams,
    rng: random.Random,
    sites_catalogue: tuple[GeoSite, ...],
) -> BuildResult:
    """Construct the typed Core-IP graph for ``params`` using the seeded ``rng``."""
    g: nx.DiGraph = nx.DiGraph()
    sites = list(geo_catalogue.first_n_sites(params.site_count))
    igp_areas_used: set[str] = set()

    def add_node(moid: str, object_type: str, attrs: dict[str, object] | None = None) -> None:
        g.add_node(moid, objectType=object_type, attributes=attrs or {})

    def add_edge(src: str, dst: str, relation: str, attrs: dict[str, object] | None = None) -> None:
        g.add_edge(src, dst, relation=relation, attributes=attrs or {})

    for site in sites:
        add_node(
            f"Site:{site.site_id}",
            "Site",
            {
                "name": site.name,
                "latitude": site.latitude,
                "longitude": site.longitude,
                "region": site.region,
            },
        )

    # Distribute Node devices across sites and assign roles. Roughly 1 in 3 nodes is a
    # backbone P/RR (area-0); the rest are PE/peering edge nodes.
    node_count = params.node_count
    nodes: list[str] = []
    for i in range(node_count):
        # Backbone every 3rd node, RR every 7th, else PE/peering.
        if i % 7 == 6:
            role = "RR"
        elif i % 3 == 0:
            role = "P"
        elif i % 5 == 4:
            role = "peering"
        else:
            role = "PE"
        igp_area = _area_for_role(role, params.igp_area_count, i)
        igp_areas_used.add(igp_area)
        node_id = f"N{i}"
        moid = f"Node:{node_id}"
        add_node(moid, "Node", _device_attrs(role, igp_area, rng))
        nodes.append(moid)
        # place device in a site (round-robin even spread)
        site = sites[i % len(sites)]
        add_edge(moid, f"Site:{site.site_id}", "LOCATED_AT")

    # Line cards + ports + interfaces per node. One line card + one port per node keeps the
    # graph compact while every layer is present; the port hosts INTERFACES_PER_PORT interfaces.
    interfaces: list[tuple[str, str]] = []  # (interface_moid, node_moid)
    for node_moid in nodes:
        node_id = node_moid.split(":", 1)[1]
        node_area = g.nodes[node_moid]["attributes"]["igpArea"]
        lc_moid = f"LineCard:{node_id}-LC1"
        add_node(
            lc_moid,
            "LineCard",
            {
                "vendor": g.nodes[node_moid]["attributes"]["vendor"],
                "model": rng.choice(_LC_MODELS),
                "equipmentType": "lineCard",
                "role": "transport",
                "capacity": rng.choice(_CAPACITIES),
            },
        )
        # LineCard belongs to its node: model HOSTED_ON Port below; link node→linecard via
        # HOSTED_ON is represented by the port chain. We attach the line card under the node
        # implicitly through the port it hosts.
        port_moid = f"Port:{node_id}-LC1-P1"
        add_node(
            port_moid,
            "Port",
            {
                "vendor": g.nodes[node_moid]["attributes"]["vendor"],
                "model": rng.choice(_PORT_MODELS),
                "equipmentType": "port",
                "role": "core",
                "capacity": rng.choice(_CAPACITIES),
            },
        )
        # Node HOSTED_ON LineCard (cause→effect: a node failure cascades to its hosted cards)
        add_edge(node_moid, lc_moid, "HOSTED_ON")
        add_edge(lc_moid, port_moid, "HOSTED_ON")
        for k in range(params.interfaces_per_port):
            if_moid = f"Interface:{node_id}-LC1-P1-if{k}"
            add_node(
                if_moid,
                "Interface",
                {
                    "name": f"TenGigE0/1/0/{k}",
                    "addressFamily": "ipv4",
                    "role": "core",
                    "igpArea": node_area,
                },
            )
            add_edge(port_moid, if_moid, "HOSTS")
            interfaces.append((if_moid, node_moid))

    # IP links + IGP adjacencies between consecutive nodes' first interface; fiber spans ride
    # each link; SRLG groups bundle pairs of links; LSPs traverse links; VPN services served.
    def node_first_iface(node_moid: str) -> str | None:
        nid = node_moid.split(":", 1)[1]
        cand = f"Interface:{nid}-LC1-P1-if0"
        return cand if g.has_node(cand) else None

    iplinks: list[str] = []
    for i in range(len(nodes) - 1):
        a, b = nodes[i], nodes[i + 1]
        if_a, if_b = node_first_iface(a), node_first_iface(b)
        if if_a is None or if_b is None:
            continue
        link_moid = f"IPLink:{a.split(':')[1]}_{b.split(':')[1]}"
        add_node(link_moid, "IPLink", {})
        add_edge(if_a, link_moid, "TERMINATES")
        add_edge(if_b, link_moid, "TERMINATES")
        iplinks.append(link_moid)
        # fiber span rides this link
        fiber_moid = f"FiberSpan:F-{a.split(':')[1]}_{b.split(':')[1]}"
        add_node(fiber_moid, "FiberSpan", {})
        add_edge(
            fiber_moid,
            link_moid,
            "RIDES_ON",
            {"linkType": "fiber", "capacity": rng.choice(_CAPACITIES), "protectionRole": "working"},
        )
        # IGP adjacency over the interface AND over the IP link it runs across: an interface
        # fault or a link/fiber fault both bring the adjacency down, so the adjacency is
        # reachable (ADJACENCY_OVER) from both the interface and the link.
        adj_moid = f"IGPAdjacency:{a.split(':')[1]}_{b.split(':')[1]}"
        add_node(adj_moid, "IGPAdjacency", {})
        add_edge(if_a, adj_moid, "ADJACENCY_OVER")
        add_edge(link_moid, adj_moid, "ADJACENCY_OVER")
        # LSP traverses the link, serving a VPN
        lsp_moid = f"LSP:{a.split(':')[1]}-{b.split(':')[1]}-1"
        add_node(lsp_moid, "LSP", {})
        add_edge(link_moid, lsp_moid, "TRAVERSES")
        vpn_moid = f"VPNService:CUST-{i % 5}"
        if not g.has_node(vpn_moid):
            add_node(vpn_moid, "VPNService", {})
        add_edge(lsp_moid, vpn_moid, "SERVES")

    # SRLG groups: bundle each adjacent pair of IP links into a shared-risk group. Both the
    # group→link membership (SRLG MEMBER_OF IPLink) and the reverse link→group membership
    # (IPLink MEMBER_OF SRLG) are recorded so a cascade reaching one member link fate-shares
    # through the group to its co-member links (SRLG fate-sharing, criterion 4 / scenario 9).
    for j in range(0, len(iplinks) - 1, 2):
        srlg_moid = f"SRLG:SRLG-{j // 2}"
        add_node(srlg_moid, "SRLG", {})
        add_edge(srlg_moid, iplinks[j], "MEMBER_OF")
        add_edge(srlg_moid, iplinks[j + 1], "MEMBER_OF")
        add_edge(iplinks[j], srlg_moid, "MEMBER_OF")
        add_edge(iplinks[j + 1], srlg_moid, "MEMBER_OF")

    return BuildResult(graph=g, sites=sites, igp_areas=sorted(igp_areas_used))
