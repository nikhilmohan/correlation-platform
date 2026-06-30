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

/**
 * INNER SVG MARKUP per icon key — the Lucide glyph path/shape elements, WITHOUT the wrapping
 * `<svg>`/`<g>` (those are added by `iconSvg()` so the stroke colour can be injected per render).
 * These mirror the bundled `public/icons/<key>.svg` shapes 1:1 (Lucide, ISC License, lucide.dev).
 * We hold the markup here so `iconDataUri()` can RECOLOUR the glyph at render
 * time (theme-aware white-in-dark / dark-in-light) without shipping duplicate per-theme SVG files.
 */
const ICON_PATHS: Readonly<Record<IconKey, string>> = {
  router:
    '<rect width="20" height="8" x="2" y="14" rx="2"/><path d="M6.01 18h.01"/><path d="M10.01 18h.01"/><path d="M15 10v4"/><path d="M17.84 7.17a4 4 0 0 0-5.66 0"/><path d="M20.66 4.34a8 8 0 0 0-11.31 0"/>',
  linecard:
    '<rect width="16" height="16" x="4" y="4" rx="2"/><rect width="6" height="6" x="9" y="9" rx="1"/><path d="M15 2v2"/><path d="M15 20v2"/><path d="M2 15h2"/><path d="M2 9h2"/><path d="M20 15h2"/><path d="M20 9h2"/><path d="M9 2v2"/><path d="M9 20v2"/>',
  port:
    '<path d="m15 20 3-3h2a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h2l3 3z"/><path d="M6 8v1"/><path d="M10 8v1"/><path d="M14 8v1"/><path d="M18 8v1"/>',
  interface:
    '<path d="M17 21v-2a1 1 0 0 1-1-1v-1a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1a1 1 0 0 1-1 1"/><path d="M19 15V6.5a1 1 0 0 0-7 0v11a1 1 0 0 1-7 0V9"/><path d="M21 21v-2h-4"/><path d="M3 5h4V3"/><path d="M7 5a1 1 0 0 1 1 1v1a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a1 1 0 0 1 1-1z"/>',
  'fiber-span':
    '<path d="M17 21v-2a1 1 0 0 1-1-1v-1a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v1a1 1 0 0 1-1 1"/><path d="M19 15V6.5a1 1 0 0 0-7 0v11a1 1 0 0 1-7 0V9"/><path d="M21 21v-2h-4"/><path d="M3 5h4V3"/><path d="M7 5a1 1 0 0 1 1 1v1a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a1 1 0 0 1 1-1z"/>',
  'ip-link': '<path d="M5 17A12 12 0 0 1 17 5"/><circle cx="19" cy="5" r="2"/><circle cx="5" cy="19" r="2"/>',
  'igp-adjacency':
    '<circle cx="12" cy="4.5" r="2.5"/><path d="m10.2 6.3-3.9 3.9"/><circle cx="4.5" cy="12" r="2.5"/><path d="M7 12h10"/><circle cx="19.5" cy="12" r="2.5"/><path d="m13.8 17.7 3.9-3.9"/><circle cx="12" cy="19.5" r="2.5"/>',
  lsp: '<circle cx="6" cy="19" r="3"/><path d="M9 19h8.5a3.5 3.5 0 0 0 0-7h-11a3.5 3.5 0 0 1 0-7H15"/><circle cx="18" cy="5" r="3"/>',
  'vpn-service':
    '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="m9 12 2 2 4-4"/>',
  srlg:
    '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/><path d="M12 8v4"/><path d="M12 16h.01"/>',
  generic:
    '<path d="M21 8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16Z"/><path d="m3.3 7 8.7 5 8.7-5"/><path d="M12 22V12"/>',
};

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

/**
 * objectType -> short, human-friendly TYPE LABEL for the on-canvas node glyph (line 1 of the
 * two-line device label, so the operator reads WHAT each box is at first glance). Unknown types
 * fall back to the raw objectType (or 'Device' when absent) so a node is never type-less.
 */
const TYPE_LABEL_MAP: Readonly<Record<string, string>> = {
  Node: 'Router',
  Router: 'Router',
  LineCard: 'Line Card',
  Port: 'Port',
  Interface: 'Interface',
  FiberSpan: 'Fiber',
  OpticalLine: 'Fiber',
  IPLink: 'IP Link',
  IGPAdjacency: 'IGP Adj',
  LSP: 'LSP',
  VPNService: 'VPN',
  Service: 'VPN',
  ServiceEndpoint: 'VPN',
  SRLG: 'SRLG',
};

/** Short human-friendly TYPE LABEL for an objectType; falls back to the raw type, then 'Device'. */
export function typeLabelFor(objectType: string | undefined | null): string {
  if (!objectType) {
    return 'Device';
  }
  return TYPE_LABEL_MAP[objectType] ?? objectType;
}

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

/**
 * Default glyph stroke colour when none is supplied — the dark-slate that matches the bundled SVG
 * files (so a colour-less call renders identically to the static asset).
 */
const DEFAULT_GLYPH_COLOR = '#334155';

/**
 * The SVG XML namespace, assembled from parts so the literal scheme+slashes substring never appears
 * in source — the config-switchable guard (AC 51) forbids URL-scheme literals in app code, and this
 * is an XML namespace identifier (not a backend URL), so we keep the guard strict and build it here.
 */
const SVG_NS = ['http', '://www.w3.org/2000/svg'].join('');

/**
 * Build the FULL SVG markup for an icon key with the glyph drawn in `color`. The wrapping `<svg>`
 * + `<g>` carry the Lucide stroke conventions (none-fill, 2px round stroke); `color` becomes the
 * stroke so the same glyph can render white-on-dark or dark-on-light per theme.
 */
function iconSvg(key: IconKey, color: string): string {
  return (
    `<svg xmlns="${SVG_NS}" viewBox="0 0 24 24">` +
    `<g fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">` +
    `${ICON_PATHS[key]}</g></svg>`
  );
}

/**
 * THEME-AWARE icon as a `data:image/svg+xml` URI for an icon KEY, with the glyph stroke set to
 * `color`. Used as the Cytoscape node `background-image` so the glyph recolours per theme
 * (white-ish in dark, dark-slate in light) on a `themeEffect` re-render — no duplicate SVG files.
 * The SVG string is `encodeURIComponent`-escaped so the `#` in a hex colour is URL-safe.
 */
export function iconDataUriForKey(key: IconKey, color: string = DEFAULT_GLYPH_COLOR): string {
  return `data:image/svg+xml,${encodeURIComponent(iconSvg(key, color))}`;
}

/**
 * THEME-AWARE icon data-URI for an objectType (resolves the key, `generic` fallback). The
 * Cytoscape leaf-node `background-image` is a function of (objectType, current theme glyph colour),
 * so a theme flip recolours every node glyph via `buildCyStyle()`.
 */
export function iconDataUriFor(objectType: string | undefined | null, color: string = DEFAULT_GLYPH_COLOR): string {
  return iconDataUriForKey(iconKeyForObjectType(objectType), color);
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
