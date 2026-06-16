/**
 * Pure `objectType -> network-element type icon` mapping (design.md -> Icon asset design, AC 70-72).
 * Mirrors `layer-mapper.ts`'s pure-function shape. Every Core IP objectType resolves to a bundled
 * inline SVG under `public/icons/`; any unknown type falls back to `generic.svg` so a node is NEVER
 * icon-less (AC 71). All icon URLs are same-origin bundle assets — no CDN, no external host (AC 72).
 */

/** Stable icon KEY per objectType (also the value bridged onto each node row as `data-icon`). The
 *  key is the file stem; `generic` is the fallback. */
export type IconKey =
  | 'router'
  | 'linecard'
  | 'port'
  | 'interface'
  | 'fiber-span'
  | 'ip-link'
  | 'igp-adjacency'
  | 'lsp'
  | 'vpn-service'
  | 'srlg'
  | 'generic';

/** objectType -> icon key. Several objectTypes share an icon (Node and Router are both routers). */
const ICON_MAP: Readonly<Record<string, IconKey>> = {
  Node: 'router',
  Router: 'router',
  LineCard: 'linecard',
  Port: 'port',
  Interface: 'interface',
  FiberSpan: 'fiber-span',
  OpticalLine: 'fiber-span',
  IPLink: 'ip-link',
  IGPAdjacency: 'igp-adjacency',
  LSP: 'lsp',
  VPNService: 'vpn-service',
  Service: 'vpn-service',
  ServiceEndpoint: 'vpn-service',
  SRLG: 'srlg',
};

/** The icon KEY for an objectType (the `data-icon` bridge value); `generic` for any unknown type. */
export function iconKeyForObjectType(objectType: string | undefined | null): IconKey {
  if (!objectType) {
    return 'generic';
  }
  return ICON_MAP[objectType] ?? 'generic';
}

/** The icon FILENAME (`<key>.svg`) for an objectType; `generic.svg` fallback (AC 71). */
export function iconFileForObjectType(objectType: string | undefined | null): string {
  return `${iconKeyForObjectType(objectType)}.svg`;
}

/**
 * Absolute, SAME-ORIGIN URL to the icon asset for an objectType, resolved against the app's served
 * base path via `document.baseURI` (AC 72 — never an off-origin URL). Used as the Cytoscape leaf-node
 * `background-image`.
 */
export function iconUrlFor(objectType: string | undefined | null): string {
  const file = iconFileForObjectType(objectType);
  const base = typeof document !== 'undefined' && document.baseURI ? document.baseURI : '/';
  return new URL(`icons/${file}`, base).href;
}

/** All distinct icon keys this app ships (drives the type-icon legend), in display order. */
export const ALL_ICON_KEYS: readonly IconKey[] = [
  'router',
  'linecard',
  'port',
  'interface',
  'fiber-span',
  'ip-link',
  'igp-adjacency',
  'lsp',
  'vpn-service',
  'srlg',
  'generic',
];

/** A representative objectType per icon key (for the legend label). */
export const ICON_LEGEND: ReadonlyArray<{ key: IconKey; label: string }> = [
  { key: 'router', label: 'Router / Node' },
  { key: 'linecard', label: 'Line card' },
  { key: 'port', label: 'Port' },
  { key: 'interface', label: 'Interface' },
  { key: 'fiber-span', label: 'Fiber span' },
  { key: 'ip-link', label: 'IP link' },
  { key: 'igp-adjacency', label: 'IGP adjacency' },
  { key: 'lsp', label: 'LSP' },
  { key: 'vpn-service', label: 'VPN service' },
  { key: 'srlg', label: 'SRLG' },
  { key: 'generic', label: 'Other / unknown' },
];
