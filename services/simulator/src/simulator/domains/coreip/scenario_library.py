"""Core-IP scenario library — 9 grounded fault scenarios + 4 noise classes (criteria 30, 6).

One scenario per Knowledge ``faultOriginType`` (7) plus SRLG co-failure and the line-card
fan-out. Each scenario declares its fault-origin object type, its root canonical ``alarmType``,
and the edge relations its cascade walks (so scenario 8 routing-adjacency-failure emphasizes the
multi-protocol routing relations, distinguishing it from interface-fault). The 9 root
``alarmType`` tokens are distinct per scenario.
"""

from __future__ import annotations

from simulator.engine.domain_pack import NoiseClass, ScenarioDef

# Relation walk orders (subsets of the pack's edge-relation vocabulary). Each cascade follows
# its relations forward over the cause→effect-directed graph (FiberSpan RIDES_ON IPLink; IPLink
# ADJACENCY_OVER IGPAdjacency and TRAVERSES LSP; LSP SERVES VPNService; Node/LineCard HOSTED_ON
# the layer below; Port HOSTS Interface; Interface TERMINATES IPLink; IPLink MEMBER_OF SRLG for
# fate-sharing). The cascade reaches the routing/LSP/service tail through the IP link.
_FULL = ("RIDES_ON", "TERMINATES", "ADJACENCY_OVER", "TRAVERSES", "SERVES")
_LINECARD = ("HOSTED_ON", "HOSTS", "TERMINATES", "ADJACENCY_OVER", "TRAVERSES", "SERVES")
_PORT = ("HOSTS", "TERMINATES", "ADJACENCY_OVER", "TRAVERSES", "SERVES")
_IFACE = ("TERMINATES", "ADJACENCY_OVER", "TRAVERSES", "SERVES")
_NODE = ("HOSTED_ON", "HOSTS", "TERMINATES", "ADJACENCY_OVER", "TRAVERSES", "SERVES")
_IPLINK = ("ADJACENCY_OVER", "MEMBER_OF", "TRAVERSES", "SERVES")
_LSP = ("TRAVERSES", "SERVES")
_ROUTING = ("TERMINATES", "ADJACENCY_OVER", "TRAVERSES", "SERVES")
_SRLG = ("RIDES_ON", "MEMBER_OF", "ADJACENCY_OVER", "TRAVERSES", "SERVES")

SCENARIO_LIBRARY: tuple[ScenarioDef, ...] = (
    ScenarioDef("fiber-cut", "FiberSpan", "FiberFault", _FULL),
    ScenarioDef("line-card-fault", "LineCard", "LineCardFault", _LINECARD),
    ScenarioDef("port-fault", "Port", "PortDown", _PORT),
    ScenarioDef("interface-fault", "Interface", "InterfaceDown", _IFACE),
    ScenarioDef("node-failure", "Node", "LOS", _NODE),
    ScenarioDef("ip-link-failure", "IPLink", "IPLinkDown", _IPLINK),
    ScenarioDef("lsp-te-failure", "LSP", "LSPDown", _LSP),
    ScenarioDef("routing-adjacency-failure", "Interface", "InterfaceDown", _ROUTING),
    ScenarioDef(
        "srlg-shared-risk-failure",
        "FiberSpan",
        "FiberFault",
        _SRLG,
    ),
)

SCENARIO_TYPES: tuple[str, ...] = tuple(s.scenario_type for s in SCENARIO_LIBRARY)

# ≥3 noise classes (design's Noise classes table). Each emits a valid vocabulary token; noise
# is identified by absence from every label's children, never by a distinct alarmType.
NOISE_CLASSES: tuple[NoiseClass, ...] = (
    NoiseClass("flapping", ("PortFlapping", "PortDown"), self_clearing=True),
    NoiseClass("transient", ("CRCErrors", "InterfaceErrors"), self_clearing=True),
    NoiseClass("chatty", ("Congestion", "QueueDrop", "HighLatency")),
    NoiseClass("coincidental", ("InterfaceDown", "LinkDown", "AdjDown")),
)
