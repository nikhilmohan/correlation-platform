"""Forward-propagation signature tests (spec acceptance criteria 1, 2, 3).

Drive the networkx propagation engine via the full compile pipeline (mocked collaborators)
so the asserted signatures are exactly what gets compiled and persisted.
"""

from __future__ import annotations

from codebook_generator.store import CodebookStore

from .conftest import CORE_IP_VOCABULARY, trails_built_bytes


def _scenarios_by_origin(store: CodebookStore, codebook_id: str) -> dict[str, list[tuple[str, str]]]:
    cb = store.get_full_codebook(codebook_id)
    assert cb is not None
    return {
        s.faultOriginObjectId: [(x.alarmType, x.managedObjectId) for x in s.predictedSymptoms]
        for s in cb.scenarios
    }


def test_fiber_cut_signature_matches_expected_cascade(components, store: CodebookStore) -> None:
    """AC-1: FiberSpan origin -> [FiberFault, LinkDown, LSPDown, ReachabilityLoss], no InterfaceDown."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-A", domain="core-ip"))
    assert result is not None
    by_origin = _scenarios_by_origin(store, result.codebook_id)

    fiber = by_origin["FiberSpan:f1"]
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


def test_linecard_and_port_signatures_distinguishable(components, store: CodebookStore) -> None:
    """AC-2: LineCard has multiple PortDown; Port has its LOS discriminator, no top-level PortDown."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-A", domain="core-ip"))
    assert result is not None
    by_origin = _scenarios_by_origin(store, result.codebook_id)

    linecard_alarms = [a for (a, _o) in by_origin["LineCard:c1"]]
    port_alarms = [a for (a, _o) in by_origin["Port:p1"]]

    # LineCard cascades HOSTED_ON to multiple Ports -> multiple PortDown.
    assert linecard_alarms.count("PortDown") >= 2
    # The Port scenario does not carry PortDown above its own origin (it starts at LOS).
    assert "PortDown" not in port_alarms
    # The port-layer discriminator (LOS) is the Port origin alarm, absent from LineCard's top-level.
    assert port_alarms[0] == "LOS"
    assert "LOS" not in linecard_alarms


def test_interface_fault_signature_matches_expected_cascade(
    components, store: CodebookStore
) -> None:
    """AC-3: Interface origin -> [InterfaceDown, LinkDown, AdjDown, LSPDown, ReachabilityLoss]."""
    result = components.handler.handle(trails_built_bytes(snapshot_id="snap-A", domain="core-ip"))
    assert result is not None
    cb = store.get_full_codebook(result.codebook_id)
    assert cb is not None
    iface = next(s for s in cb.scenarios if s.faultOriginObjectId == "Interface:i1")

    assert iface.faultOriginType == "Interface"
    assert [(x.alarmType, x.managedObjectId) for x in iface.predictedSymptoms] == [
        ("InterfaceDown", "Interface:i1"),
        ("LinkDown", "IPLink:l1"),
        ("AdjDown", "IGPAdjacency:a1"),
        ("LSPDown", "LSP:s1"),
        ("ReachabilityLoss", "VPNService:v1"),
    ]
    # Distinct from fiber-cut: starts at InterfaceDown (the origin's own alarm).
    assert iface.predictedSymptoms[0].alarmType == "InterfaceDown"
    # Distinct from port-fault: no PortDown precedes InterfaceDown.
    assert "PortDown" not in [x.alarmType for x in iface.predictedSymptoms]
