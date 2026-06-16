"""Forward-propagation engine (networkx). Pure — no I/O.

Forward propagation is a typed-edge BFS over an origin instance's bounded graph closure,
driven by the Knowledge propagation templates (never hard-coded). Output: an ordered
predicted-symptom signature (origin's own alarm first/seed, then effects in cascade order,
deduplicated preserving first-seen order).

A template names an ``edgeType``, a ``trigger {objectType, alarmType}`` (the source state),
and an ``effect {objectType, alarmType}`` (the alarm raised on the target). When a node N
holds alarm state S, every out-edge of N whose ``edgeType`` matches a template with
``trigger.objectType == type(N)`` and ``trigger.alarmType == S`` and ``effect.objectType ==
type(target)`` raises ``effect.alarmType`` on the target, which is then pushed with that new
state.
"""

from __future__ import annotations

from collections import deque

import networkx as nx

from .models import (
    FaultOriginType,
    NodeDto,
    PredictedSymptom,
    PropagationTemplate,
    TraversalDto,
)


def build_closure_graph(origin: NodeDto, traversal: TraversalDto) -> nx.MultiDiGraph:
    """Build a typed directed multigraph from a Topology traversal closure.

    Nodes carry ``objectType``; edges carry the ``relation`` (edge type). The origin is
    always present even when the traversal omits it from ``reached``.
    """
    graph: nx.MultiDiGraph = nx.MultiDiGraph()
    graph.add_node(origin.managedObjectId, objectType=origin.objectType)
    for node in traversal.reached:
        graph.add_node(node.managedObjectId, objectType=node.objectType)
    for edge in traversal.edges:
        # Tolerate traversal nodes that only appear as edge endpoints.
        if edge.from_ not in graph:
            graph.add_node(edge.from_, objectType=_infer_type(edge.from_))
        if edge.to not in graph:
            graph.add_node(edge.to, objectType=_infer_type(edge.to))
        graph.add_edge(edge.from_, edge.to, relation=edge.relation)
    return graph


def _infer_type(managed_object_id: str) -> str:
    """Best-effort object type from a ``Type:id`` managedObjectId (closure fallback)."""
    return managed_object_id.split(":", 1)[0] if ":" in managed_object_id else managed_object_id


def origin_alarm_type(fault_origin_type: str, fault_origin_types: list[FaultOriginType]) -> str:
    """Return the self-emitted origin alarm token for ``fault_origin_type``.

    Read from the Knowledge fault-origin type record's ``originAlarmType`` (vocabulary
    token, e.g. ``FiberFault`` for ``FiberSpan``, ``InterfaceDown`` for ``Interface``).
    """
    for fot in fault_origin_types:
        if fot.objectType == fault_origin_type and fot.originAlarmType:
            return fot.originAlarmType
    raise PropagationError(
        f"no originAlarmType authored for fault-origin type {fault_origin_type!r}"
    )


class PropagationError(ValueError):
    """Raised when an origin instance cannot be propagated (missing origin alarm token)."""


def propagate(
    origin: NodeDto,
    closure: nx.MultiDiGraph,
    templates: list[PropagationTemplate],
    fault_origin_types: list[FaultOriginType],
) -> list[PredictedSymptom]:
    """Run templates forward from ``origin`` over ``closure`` to an ordered signature.

    The first/seed symptom is the origin's own alarm (``originAlarmType`` for its type).
    Effects are appended in cascade (BFS) order; duplicates (same alarmType + object) are
    dropped preserving first-seen order.
    """
    seed_alarm = origin_alarm_type(origin.objectType, fault_origin_types)

    signature: list[PredictedSymptom] = []
    seen: set[tuple[str, str]] = set()

    def emit(alarm_type: str, object_id: str) -> bool:
        key = (alarm_type, object_id)
        if key in seen:
            return False
        seen.add(key)
        signature.append(PredictedSymptom(alarmType=alarm_type, managedObjectId=object_id))
        return True

    emit(seed_alarm, origin.managedObjectId)

    # Frontier carries (node_id, alarm_state). The state drives which templates fire next.
    frontier: deque[tuple[str, str]] = deque([(origin.managedObjectId, seed_alarm)])
    # Guard against cycles: a (node, state) is processed at most once.
    processed: set[tuple[str, str]] = set()

    while frontier:
        node_id, state = frontier.popleft()
        if (node_id, state) in processed:
            continue
        processed.add((node_id, state))
        node_type = closure.nodes[node_id].get("objectType")

        for _src, target_id, data in closure.out_edges(node_id, data=True):
            relation = data.get("relation")
            target_type = closure.nodes[target_id].get("objectType")
            for tmpl in templates:
                if (
                    tmpl.edgeType == relation
                    and tmpl.trigger.objectType == node_type
                    and tmpl.trigger.alarmType == state
                    and tmpl.effect.objectType == target_type
                ):
                    emit(tmpl.effect.alarmType, target_id)
                    frontier.append((target_id, tmpl.effect.alarmType))

    return signature
