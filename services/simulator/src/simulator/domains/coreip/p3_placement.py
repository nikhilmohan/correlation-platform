"""Core-IP P3 alarmType -> objectType placement affinity table (OQ-P3-1, spec Task 15).

Pack-authored (generation-side) mapping from each canonical ``alarmType`` token to the object
type it is *naturally raised on*. P3 synthesis uses this to place a pattern sequence element onto
a real trail member of the affine ``objectType``. This lives in the Core-IP **domain pack** (not
the engine, not Knowledge) so the engine/``synth`` stay domain-generic (spec criterion 19); the
engine reads it only via the ``DomainPack.placement_affinity()`` Protocol method.

When a trail has no member of the affine ``objectType`` the caller falls back to *any* member of
the same trail (see :mod:`simulator.synth.aligned_synth`) — this table only declares the affinity.
"""

from __future__ import annotations

from collections.abc import Mapping
from types import MappingProxyType

# alarmType -> affine objectType. Every value is one of the nine known typed graph layers
# (Node, LineCard, Port, IPLink, IGPAdjacency, LSP, VPNService, FiberSpan, SRLG) plus Interface.
# Keys are canonical alarmType tokens from the Core-IP alarmTypeVocabulary.
_AFFINITY: dict[str, str] = {
    # optical / fiber -> FiberSpan
    "FiberCut": "FiberSpan",
    "FiberFault": "FiberSpan",
    "LOS": "FiberSpan",
    "LOF": "FiberSpan",
    "OpticalPowerLow": "FiberSpan",
    # line card -> LineCard
    "LineCardFault": "LineCard",
    # ports -> Port
    "PortDown": "Port",
    "PortFlapping": "Port",
    "CRCErrors": "Port",
    # interfaces -> Interface
    "InterfaceDown": "Interface",
    "InterfaceErrors": "Interface",
    "LinkBundleDegraded": "Interface",
    # IP links -> IPLink
    "IPLinkDown": "IPLink",
    "LinkDown": "IPLink",
    # routing adjacencies -> IGPAdjacency
    "ISISAdjacencyDown": "IGPAdjacency",
    "AdjDown": "IGPAdjacency",
    "OSPFAdjacencyDown": "IGPAdjacency",
    "BGPPeerDown": "IGPAdjacency",
    "RouteFlap": "IGPAdjacency",
    "LDPSessionDown": "IGPAdjacency",
    # LSP / TE -> LSP
    "LSPDown": "LSP",
    "TETunnelDown": "LSP",
    "FRRSwitchover": "LSP",
    # service / QoS -> VPNService
    "VPNReachabilityLoss": "VPNService",
    "ReachabilityLoss": "VPNService",
    "ServiceDegraded": "VPNService",
    "Congestion": "VPNService",
    "QueueDrop": "VPNService",
    "HighLatency": "VPNService",
}

# Immutable view exported to the engine/synth (pure pack data — no mutation).
PLACEMENT_AFFINITY: Mapping[str, str] = MappingProxyType(_AFFINITY)


def affine_object_type(alarm_type: str) -> str | None:
    """Return the objectType an ``alarmType`` is naturally raised on, or ``None`` if unmapped."""
    return _AFFINITY.get(alarm_type)
