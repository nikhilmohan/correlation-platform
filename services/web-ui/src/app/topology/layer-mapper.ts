import { EdgeDto, LogicalLayer } from '../api/models';

/**
 * Pure objectType → logical layer mapping (design.md → Layer derivation, P1-G9). The frozen
 * NodeDto has no `layer` field; the layer is derived here. An unmapped objectType falls back to
 * `other` and is still rendered (never dropped).
 */
const MAP: ReadonlyArray<readonly [readonly string[], LogicalLayer]> = [
  [['FiberSpan', 'OpticalLine'], 'fiber'],
  [['Port', 'Interface', 'IPLink'], 'IP'],
  [['Node', 'Router', 'IGPAdjacency', 'LineCard'], 'IGP'],
  [['LSP'], 'LSP'],
  [['Service', 'ServiceEndpoint', 'VPNService'], 'service'],
];

/**
 * Pure edge-relation → logical layer mapping. The site graph paints typed edges whose `relation`
 * is one of the Topology §5 vocabulary (HOSTED_ON, RIDES_ON, ADJACENCY_OVER, TRAVERSES, SERVES,
 * MEMBER_OF — plus the structural HOSTS/TERMINATES/HAS_PORT/CONNECTS/CARRIES seen in the Core-IP
 * snapshot). EVERY one of those relations MUST resolve to one of the five toggleable logical
 * layers so that AC 28 holds: each layer toggle independently shows/hides its edges, and with all
 * five layers off the graph renders 0 edges (nodes remain). Relations that previously fell through
 * the endpoint-prefix derivation to `other` (e.g. SRLG `MEMBER_OF`, containment `HOSTED_ON`/`HOSTS`)
 * were never governed by any toggle and so survived all-off — issue #263. They are mapped here:
 *  - MEMBER_OF  → fiber   (SRLG = shared-risk *fiber* grouping; a physical-transport relation)
 *  - RIDES_ON   → fiber   (IPLink rides on the optical/fiber transport beneath it)
 *  - HOSTED_ON  → IGP     (LineCard/Port structural containment within the Node/Router chassis)
 *  - LOCATED_AT → IGP     (device→Site placement; structural, present in every domain — #263 review)
 *  - HOSTS      → IP      (Port HOSTS Interface — the port/interface layering)
 *  - TERMINATES → IP      (Interface TERMINATES an IPLink)
 *  - ADJACENCY_OVER → IGP (Interface runs an IGP adjacency)
 *  - TRAVERSES  → LSP     (LSP traverses its path)
 *  - SERVES     → service (VPNService serves an endpoint)
 *  - HAS_PORT   → IGP     (Core-IP snapshot: Router HAS_PORT Interface — chassis containment)
 *  - CONNECTS   → fiber   (Core-IP snapshot: Interface CONNECTS a FiberSpan)
 *  - CARRIES    → LSP     (Core-IP snapshot: Router CARRIES an LSP)
 * An unknown relation falls back to the endpoint-objectType derivation (see `layerForEdge`).
 */
const RELATION_MAP: Readonly<Record<string, LogicalLayer>> = {
  MEMBER_OF: 'fiber',
  RIDES_ON: 'fiber',
  CONNECTS: 'fiber',
  HOSTED_ON: 'IGP',
  HAS_PORT: 'IGP',
  ADJACENCY_OVER: 'IGP',
  LOCATED_AT: 'IGP',
  HOSTS: 'IP',
  TERMINATES: 'IP',
  TRAVERSES: 'LSP',
  CARRIES: 'LSP',
  SERVES: 'service',
};

export const ALL_LAYERS: readonly LogicalLayer[] = ['fiber', 'IP', 'IGP', 'LSP', 'service', 'other'];

/** The five operator-toggleable logical layers (AC 28). `other` is intentionally excluded: every
 *  rendered EDGE must resolve to one of these five so all-off → 0 edges. */
export const TOGGLEABLE_LAYERS: readonly LogicalLayer[] = ['fiber', 'IP', 'IGP', 'LSP', 'service'];

export function layerForObjectType(objectType: string | undefined | null): LogicalLayer {
  if (!objectType) {
    return 'other';
  }
  for (const [types, layer] of MAP) {
    if (types.includes(objectType)) {
      return layer;
    }
  }
  return 'other';
}

/** Logical layer for a typed edge `relation` (Topology §5 vocabulary); `null` when the relation is
 *  not in the known vocabulary, so the caller can fall back to the endpoint-objectType derivation. */
export function layerForRelation(relation: string | undefined | null): LogicalLayer | null {
  if (!relation) {
    return null;
  }
  return RELATION_MAP[relation] ?? null;
}

/**
 * Logical layer for an edge. Prefer the typed `relation` mapping (every §5 relation resolves to a
 * toggleable layer); only fall back to the endpoint-objectType prefix for an unknown relation. This
 * guarantees that no rendered edge is left in `other` with no governing toggle (#263).
 */
export function layerForEdge(edge: EdgeDto): LogicalLayer {
  const byRelation = layerForRelation(edge.relation);
  if (byRelation) {
    return byRelation;
  }
  const prefix = edge.from.split(':')[0];
  return layerForObjectType(prefix || edge.relation);
}
