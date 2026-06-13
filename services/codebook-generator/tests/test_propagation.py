"""Forward-propagation signature tests — spec acceptance criteria 1, 2, 3.

These are PURE-LOGIC tests: they exercise the networkx propagation engine
(:func:`codebook_generator.propagation.propagate`) directly against the frozen
collaborator shapes defined in ``conftest`` (Knowledge templates + fault-origin types,
Topology closures), with no store / consumer / HTTP wiring. The asserted ordered
signatures are exactly the cascade signatures the compile pipeline persists.
"""

from __future__ import annotations

from codebook_generator.models import (
    FaultOriginType,
    NodeDto,
    PropagationTemplate,
    TraversalDto,
)
from codebook_generator.propagation import build_closure_graph, propagate

from .conftest import (
    CORE_IP_FAULT_ORIGINS,
    CORE_IP_NODES,
    CORE_IP_TEMPLATES,
    CORE_IP_VOCABULARY,
)

# --------------------------------------------------------------------------- #
# Helpers: build typed inputs from the frozen conftest collaborator shapes.   #
# --------------------------------------------------------------------------- #
_FAULT_ORIGINS = [FaultOriginType(**fo) for fo in CORE_IP_FAULT_ORIGINS]
_TEMPLATES = [PropagationTemplate(**t) for t in CORE_IP_TEMPLATES]


def _origin(object_type: str) -> NodeDto:
    """The single Core IP fault-origin instance of the given object type."""
    return NodeDto(**CORE_IP_NODES[object_type][0])


def _signature(object_type: str) -> list[tuple[str, str]]:
    """Run propagation for a Core IP origin type and return [(alarmType, objectId)]."""
    from .conftest import CORE_IP_CLOSURES

    origin = _origin(object_type)
    raw = CORE_IP_CLOSURES[origin.managedObjectId]
    traversal = TraversalDto(
        start=origin.managedObjectId,
        domain="core-ip",
        reached=[NodeDto(**n) for n in raw["reached"]],
        edges=raw["edges"],
    )
    closure = build_closure_graph(origin, traversal)
    symptoms = propagate(origin, closure, _TEMPLATES, _FAULT_ORIGINS)
    return [(s.alarmType, s.managedObjectId) for s in symptoms]


def test_fiber_cut_signature_matches_expected_cascade() -> None:
    """AC-1: FiberSpan origin -> [FiberFault, LinkDown, LSPDown, ReachabilityLoss]."""
    fiber = _signature("FiberSpan")
    assert fiber == [
        ("FiberFault", "FiberSpan:f1"),
        ("LinkDown", "IPLink:l1"),
        ("LSPDown", "LSP:s1"),
        ("ReachabilityLoss", "VPNService:v1"),
    ]
    # No InterfaceDown distinguishes the fiber-cut signature.
    assert all(a != "InterfaceDown" for (a, _o) in fiber)
    # Every token is an alarmTypeVocabulary member.
    assert all(a in CORE_IP_VOCABULARY for (a, _o) in fiber)


def test_linecard_and_port_signatures_distinguishable() -> None:
    """AC-2: LineCard has multiple PortDown; Port starts at LOS, no top-level PortDown."""
    linecard = _signature("LineCard")
    port = _signature("Port")

    linecard_alarms = [a for (a, _o) in linecard]
    port_alarms = [a for (a, _o) in port]

    # LineCard cascades HOSTED_ON to multiple Ports -> multiple PortDown.
    assert linecard_alarms.count("PortDown") >= 2
    # The Port scenario does not carry PortDown above its own origin (it starts at LOS).
    assert "PortDown" not in port_alarms
    # The port-layer discriminator (LOS) is the Port origin alarm, absent from LineCard.
    assert port_alarms[0] == "LOS"
    assert "LOS" not in linecard_alarms
    # Both signatures stay within the domain vocabulary.
    assert all(a in CORE_IP_VOCABULARY for a in linecard_alarms + port_alarms)


def test_interface_fault_signature_matches_expected_cascade() -> None:
    """AC-3: Interface origin -> [InterfaceDown, LinkDown, AdjDown, LSPDown, ReachabilityLoss]."""
    iface = _signature("Interface")
    assert iface == [
        ("InterfaceDown", "Interface:i1"),
        ("LinkDown", "IPLink:l1"),
        ("AdjDown", "IGPAdjacency:a1"),
        ("LSPDown", "LSP:s1"),
        ("ReachabilityLoss", "VPNService:v1"),
    ]
    # Distinct from fiber-cut: starts at InterfaceDown (the origin's own alarm).
    assert iface[0][0] == "InterfaceDown"
    # Distinct from port-fault: no PortDown precedes InterfaceDown.
    assert "PortDown" not in [a for (a, _o) in iface]
