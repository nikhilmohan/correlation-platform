import { LogicalLayer } from '../api/models';

/**
 * Pure objectType → logical layer mapping (design.md → Layer derivation, P1-G9). The frozen
 * NodeDto has no `layer` field; the layer is derived here. An unmapped objectType falls back to
 * `other` and is still rendered (never dropped).
 */
const MAP: ReadonlyArray<readonly [readonly string[], LogicalLayer]> = [
  [['FiberSpan', 'OpticalLine'], 'fiber'],
  [['Port', 'Interface', 'IPLink'], 'IP'],
  [['Node', 'Router', 'IGPAdjacency'], 'IGP'],
  [['LSP'], 'LSP'],
  [['Service', 'ServiceEndpoint'], 'service'],
];

export const ALL_LAYERS: readonly LogicalLayer[] = ['fiber', 'IP', 'IGP', 'LSP', 'service', 'other'];

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
