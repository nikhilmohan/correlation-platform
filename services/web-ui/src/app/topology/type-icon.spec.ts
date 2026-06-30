import { describe, expect, it } from 'vitest';
import {
  ALL_ICON_KEYS,
  iconDataUriFor,
  iconDataUriForKey,
  iconFileForObjectType,
  iconKeyForObjectType,
  iconUrlFor,
} from './type-icon-mapper';

/**
 * Network-element type-icon mapping (AC 70-72). Pure-function coverage of the objectType → icon
 * key/file/url contract: every one of the ten Core IP objectTypes resolves to its own icon, an
 * unknown type falls back to `generic`, and every URL is same-origin (offline bundle).
 *
 * These FAIL on the old build (no type-icon-mapper / no per-type icons): nodes were plain coloured
 * circles with no objectType→icon mapping at all.
 */
describe('type-icon-mapper — objectType → network-element icon (AC 70-72)', () => {
  // The ten Core IP objectTypes and their expected icon KEY (Node/Router share the router icon).
  const CASES: ReadonlyArray<[string, string]> = [
    ['Node', 'router'],
    ['Router', 'router'],
    ['LineCard', 'linecard'],
    ['Port', 'port'],
    ['Interface', 'interface'],
    ['FiberSpan', 'fiber-span'],
    ['IPLink', 'ip-link'],
    ['IGPAdjacency', 'igp-adjacency'],
    ['LSP', 'lsp'],
    ['VPNService', 'vpn-service'],
    ['SRLG', 'srlg'],
  ];

  it.each(CASES)('AC 70 — objectType %s → icon key %s', (objectType, expectedKey) => {
    expect(iconKeyForObjectType(objectType)).toBe(expectedKey);
    expect(iconFileForObjectType(objectType)).toBe(`${expectedKey}.svg`);
  });

  it('AC 70 — the ten objectTypes resolve to all 10 DISTINCT icon keys', () => {
    const tenTypes = ['Node', 'LineCard', 'Port', 'Interface', 'FiberSpan', 'IPLink', 'IGPAdjacency', 'LSP', 'VPNService', 'SRLG'];
    const keys = new Set(tenTypes.map((t) => iconKeyForObjectType(t)));
    expect(keys.size).toBe(10);
    // None of the ten resolves to the generic fallback.
    expect(keys.has('generic')).toBe(false);
  });

  it('AC 71 — an UNKNOWN objectType falls back to the generic icon (never icon-less)', () => {
    expect(iconKeyForObjectType('UnknownFutureThing')).toBe('generic');
    expect(iconFileForObjectType('UnknownFutureThing')).toBe('generic.svg');
    // Null/empty also degrade to generic, never throw.
    expect(iconKeyForObjectType(undefined)).toBe('generic');
    expect(iconKeyForObjectType(null)).toBe('generic');
    expect(iconKeyForObjectType('')).toBe('generic');
  });

  it('AC 72 — every icon URL is SAME-ORIGIN (bundle asset, no external host)', () => {
    const origin = new URL(document.baseURI).origin;
    for (const type of ['Node', 'FiberSpan', 'LSP', 'UnknownFutureThing']) {
      const url = new URL(iconUrlFor(type));
      expect(url.origin).toBe(origin);
      expect(url.pathname).toContain('/icons/');
      // Never points at a CDN / external hostname.
      expect(url.hostname).not.toMatch(/cdn|googleapis|jsdelivr|unpkg|cloudfront/i);
    }
  });

  it('ALL_ICON_KEYS covers the ten type keys plus the generic fallback', () => {
    expect(ALL_ICON_KEYS).toContain('generic');
    expect(ALL_ICON_KEYS.length).toBe(11);
  });
});

/**
 * THEME-AWARE glyph recolour (operator feedback: no plate → glyph sits on the canvas → must recolour
 * per theme). iconDataUriFor/ForKey return a `data:image/svg+xml` URI whose stroke is the passed
 * colour, so buildCyStyle can pass --icon-glyph (white in dark / dark-slate in light) and the
 * themeEffect re-render recolours every glyph.
 */
describe('type-icon-mapper — theme-aware glyph recolour (data-URI)', () => {
  it('iconDataUriFor returns a same-origin-free SVG data-URI (no external host, no /icons/ file)', () => {
    const uri = iconDataUriFor('Node', '#f8fafc');
    expect(uri.startsWith('data:image/svg+xml,')).toBe(true);
    expect(uri).not.toMatch(/cdn|googleapis|jsdelivr|unpkg|cloudfront/i);
  });

  it('the data-URI EMBEDS the passed glyph colour (URL-encoded so the # is safe)', () => {
    // Near-white DARK-theme glyph colour.
    const dark = iconDataUriFor('Router', '#f8fafc');
    expect(decodeURIComponent(dark)).toContain('stroke="#f8fafc"');
    // The # is percent-encoded in the raw URI (so the URI is valid).
    expect(dark).toContain('%23f8fafc');

    // DARK-slate LIGHT-theme glyph colour — same glyph, different stroke.
    const light = iconDataUriFor('Router', '#334155');
    expect(decodeURIComponent(light)).toContain('stroke="#334155"');
    expect(light).not.toBe(dark);
  });

  it('iconDataUriForKey recolours by KEY too (drives the theme-aware legend)', () => {
    const uri = iconDataUriForKey('lsp', '#f8fafc');
    expect(decodeURIComponent(uri)).toContain('stroke="#f8fafc"');
    expect(decodeURIComponent(uri)).toContain('<svg');
  });

  it('an unknown objectType still yields a (generic) recoloured glyph — never icon-less', () => {
    const uri = iconDataUriFor('UnknownFutureThing', '#abcdef');
    expect(decodeURIComponent(uri)).toContain('stroke="#abcdef"');
  });

  it('the file-URL helpers stay same-origin assets (used by the list-row <img>)', () => {
    // iconUrlFor unchanged: still a same-origin bundle URL, not a data-URI.
    expect(iconUrlFor('Node').startsWith('data:')).toBe(false);
  });
});
