"""Core-IP §5 propagation templates (the 28 Knowledge propagationTemplate records).

Each record maps an edge relation to an effect ``alarmType`` emitted when the cascade hops
that relation. Multiple records share one relation (so one hop adds several co-symptom types)
— this is what lets a single cascade span 10-20 distinct ``alarmType`` tokens. The traversal
itself lives in the domain-agnostic ``engine/cascade.py``; only this data is domain-specific.
"""

from __future__ import annotations

from simulator.engine.domain_pack import PropagationTemplate as PT

# 28 propagation records over the typed edge relations.
PROPAGATION_TEMPLATES: tuple[PT, ...] = (
    # FiberSpan RIDES_ON IPLink — optical + link symptoms (4)
    PT("RIDES_ON", "LOS"),
    PT("RIDES_ON", "LOF"),
    PT("RIDES_ON", "OpticalPowerLow"),
    PT("RIDES_ON", "LinkDown"),
    # LineCard HOSTED_ON Port — port symptoms (2)
    PT("HOSTED_ON", "PortDown"),
    PT("HOSTED_ON", "PortFlapping"),
    # Port HOSTS Interface — interface symptoms (3)
    PT("HOSTS", "InterfaceDown"),
    PT("HOSTS", "CRCErrors"),
    PT("HOSTS", "InterfaceErrors"),
    # Interface TERMINATES IPLink — link symptoms (3)
    PT("TERMINATES", "IPLinkDown"),
    PT("TERMINATES", "LinkDown"),
    PT("TERMINATES", "LinkBundleDegraded"),
    # Interface ADJACENCY_OVER IGPAdjacency — routing fan-out (6)
    PT("ADJACENCY_OVER", "ISISAdjacencyDown"),
    PT("ADJACENCY_OVER", "OSPFAdjacencyDown"),
    PT("ADJACENCY_OVER", "AdjDown"),
    PT("ADJACENCY_OVER", "BGPPeerDown"),
    PT("ADJACENCY_OVER", "RouteFlap"),
    PT("ADJACENCY_OVER", "LDPSessionDown"),
    # IPLink TRAVERSES LSP — LSP/TE symptoms (3)
    PT("TRAVERSES", "LSPDown"),
    PT("TRAVERSES", "FRRSwitchover"),
    PT("TRAVERSES", "TETunnelDown"),
    # LSP SERVES VPNService — service/QoS tail (6)
    PT("SERVES", "ReachabilityLoss"),
    PT("SERVES", "VPNReachabilityLoss"),
    PT("SERVES", "ServiceDegraded"),
    PT("SERVES", "Congestion"),
    PT("SERVES", "QueueDrop"),
    PT("SERVES", "HighLatency"),
    # SRLG MEMBER_OF IPLink — fate-sharing co-failure (1)
    PT("MEMBER_OF", "LinkBundleDegraded", fanout="co-failure-group"),
)
