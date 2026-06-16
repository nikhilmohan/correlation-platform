"""Core-IP canonical ``alarmType`` vocabulary + X.733 alarm shapes (criteria 7, 7a).

The 29-token expanded Core-IP ``alarmTypeVocabulary`` authored in Knowledge. Each token is
paired with its X.733 ``eventType``/``probableCause``/``perceivedSeverity`` so every emitted
``AlarmEvent`` carries the required canonical ``alarmType`` (the join key) alongside the
X.733 fields. ``alarmType`` is distinct from ``eventType`` and ``probableCause``.
"""

from __future__ import annotations

from simulator.engine.domain_pack import AlarmShape

# X.733 event-type categories (free-string on the wire).
_COMM = "communicationsAlarm"
_QOS = "qualityOfServiceAlarm"
_EQUIP = "equipmentAlarm"

# The 29-token canonical vocabulary, each with its grounded X.733 shape.
_SHAPES: dict[str, AlarmShape] = {
    # optical / fiber
    "LOS": AlarmShape("LOS", _COMM, "lossOfSignal", "critical"),
    "LOF": AlarmShape("LOF", _COMM, "lossOfFrame", "critical"),
    "OpticalPowerLow": AlarmShape("OpticalPowerLow", _COMM, "lowOpticalPower", "major"),
    "FiberCut": AlarmShape("FiberCut", _COMM, "lossOfSignal", "critical"),
    "FiberFault": AlarmShape("FiberFault", _COMM, "lossOfSignal", "critical"),
    # ports / line cards
    "PortDown": AlarmShape("PortDown", _COMM, "portDown", "major"),
    "LineCardFault": AlarmShape("LineCardFault", _EQUIP, "equipmentMalfunction", "critical"),
    "CRCErrors": AlarmShape("CRCErrors", _COMM, "thresholdCrossed", "minor"),
    "PortFlapping": AlarmShape("PortFlapping", _COMM, "portFlapping", "warning"),
    "LinkBundleDegraded": AlarmShape("LinkBundleDegraded", _QOS, "degradedSignal", "major"),
    # interfaces / links
    "InterfaceDown": AlarmShape("InterfaceDown", _COMM, "interfaceDown", "major"),
    "InterfaceErrors": AlarmShape("InterfaceErrors", _COMM, "thresholdCrossed", "minor"),
    "IPLinkDown": AlarmShape("IPLinkDown", _COMM, "linkDown", "critical"),
    "LinkDown": AlarmShape("LinkDown", _COMM, "linkDown", "critical"),
    # routing
    "ISISAdjacencyDown": AlarmShape("ISISAdjacencyDown", _COMM, "adjacencyDown", "major"),
    "AdjDown": AlarmShape("AdjDown", _COMM, "adjacencyDown", "major"),
    "OSPFAdjacencyDown": AlarmShape("OSPFAdjacencyDown", _COMM, "adjacencyDown", "major"),
    "BGPPeerDown": AlarmShape("BGPPeerDown", _COMM, "bgpPeerDown", "major"),
    "RouteFlap": AlarmShape("RouteFlap", _COMM, "routeFlap", "minor"),
    "LDPSessionDown": AlarmShape("LDPSessionDown", _COMM, "ldpSessionDown", "major"),
    # LSP / TE
    "LSPDown": AlarmShape("LSPDown", _COMM, "lspDown", "major"),
    "FRRSwitchover": AlarmShape("FRRSwitchover", _COMM, "protectionSwitch", "minor"),
    "TETunnelDown": AlarmShape("TETunnelDown", _COMM, "tunnelDown", "major"),
    # service / QoS
    "VPNReachabilityLoss": AlarmShape("VPNReachabilityLoss", _QOS, "reachabilityLoss", "critical"),
    "ReachabilityLoss": AlarmShape("ReachabilityLoss", _QOS, "reachabilityLoss", "critical"),
    "ServiceDegraded": AlarmShape("ServiceDegraded", _QOS, "serviceDegraded", "major"),
    "Congestion": AlarmShape("Congestion", _QOS, "congestion", "minor"),
    "QueueDrop": AlarmShape("QueueDrop", _QOS, "thresholdCrossed", "minor"),
    "HighLatency": AlarmShape("HighLatency", _QOS, "highLatency", "minor"),
}

ALARM_TYPE_VOCABULARY: tuple[str, ...] = tuple(_SHAPES.keys())


def alarm_shape(alarm_type: str) -> AlarmShape:
    """Return the X.733 shape for one canonical ``alarmType`` token."""
    try:
        return _SHAPES[alarm_type]
    except KeyError as exc:  # pragma: no cover - guards pack-internal misuse
        raise KeyError(f"unknown Core-IP alarmType token: {alarm_type!r}") from exc
