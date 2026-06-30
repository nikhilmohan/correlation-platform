# Network-element type icons

Bundled inline SVG glyphs, one per Core IP `objectType`, used as Cytoscape node
`background-image` in the site-graph view and in the type-icon legend (spec ACs 70-72,
design `## Icon asset design`).

## Source & license

These glyphs are **[Lucide](https://lucide.dev) icons**, released under the
**ISC License** — a permissive OSI-approved license (functionally equivalent to MIT/BSD),
which satisfies the platform golden rule "only permissive open source license components
(MIT, Apache 2.0, BSD, ISC, CC0)". The Lucide SVG path data is copied verbatim into the
per-file assets below and attributed in each file's comment header.

> Lucide — ISC License — https://lucide.dev — Copyright (c) the Lucide contributors.

No icon font, no CDN sprite, and no runtime network request to lucide.dev is used: the
path data is vendored as static 24×24 SVGs under `public/icons/` and served same-origin
(see "Offline guarantee" below).

### Lucide redesign (build/web-ui-lucide-and-collapse)

Operator feedback was that the prior hand-drawn glyphs were not intuitive, so each device
file now maps to a recognizable Lucide glyph:

| file | Lucide glyph |
|---|---|
| `router` | `router` |
| `linecard` | `cpu` |
| `port` | `ethernet-port` |
| `interface` | `cable` |
| `ip-link` | `spline` |
| `fiber-span` | `cable` (tinted amber — distinct from `ip-link`) |
| `igp-adjacency` | `waypoints` |
| `lsp` | `route` |
| `vpn-service` | `shield-check` |
| `srlg` | `shield-alert` |
| `generic` | `box` |

`lsp` (route) and `igp-adjacency` (waypoints) are deliberately different glyphs so the two
read as distinct element types.

**Dual-theme legibility.** The node chip background is theme-driven — dark navy
(`#0f172a`) in dark mode, white (`#ffffff`) in light mode — and the icon renders as a
Cytoscape `background-image`, so it does NOT inherit any CSS `currentColor`. A single
stroke colour cannot read on both a near-black and a white chip. The earlier design solved
this with a full-viewport opaque **slate-200 (`#e2e8f0`) backing rect**, but that filled
the 24×24 viewBox so the "icon" read as a big grey square bursting the node box. That
opaque rect has been **removed**.

Instead each SVG now carries a **small, inset, translucent** backing —
`<rect x="3" y="3" width="18" height="18" rx="4" fill="#94a3b8" fill-opacity="0.18"/>` — a
faint slate tile that is theme-neutral (reads on BOTH white and dark-navy) yet clearly
smaller than the node, so it never reads as a box again. The Lucide glyph is stroked in
**mid-slate (`#475569`)** on top (amber `#b45309` for `fiber-span`), which contrasts both
the faint tile and either chip background. Everything outside the inset tile is transparent,
so the node's own layer-coloured border/chip frames the glyph directly.

**Fit in the box.** The viewBox stays `0 0 24 24`; the node style draws the icon at
`background-width/height: 60%` (down from the prior 74%) with `background-fit: contain` and
the default centered `background-position`, so the glyph occupies roughly the middle ~60% of
the 100px node box — centered, with comfortable padding all round inside the
coloured-border chip, like a normal app icon in a button rather than edge-to-edge. The
2px-stroke Lucide glyph is never clipped.

## Offline guarantee (AC 72)

All icons are static assets under `public/icons/` and are copied verbatim into the build
output by the Angular `public/` asset pipeline, served from the app's own origin. The
runtime resolves each icon URL via `new URL('icons/<file>.svg', document.baseURI).href`
(see `src/app/topology/type-icon-mapper.ts`), which is always a **same-origin** URL — never
an external host. The app makes **no network request to any CDN or external host** for an
icon asset.

## objectType -> file map

| objectType (Core IP) | file |
|---|---|
| `Node` / `Router` | `router.svg` |
| `LineCard` | `linecard.svg` |
| `Port` | `port.svg` |
| `Interface` | `interface.svg` |
| `FiberSpan` | `fiber-span.svg` |
| `IPLink` | `ip-link.svg` |
| `IGPAdjacency` | `igp-adjacency.svg` |
| `LSP` | `lsp.svg` |
| `VPNService` | `vpn-service.svg` |
| `SRLG` | `srlg.svg` |
| *(any unknown type)* | `generic.svg` (fallback — AC 71; no node is ever icon-less) |

The map lives in `src/app/topology/type-icon-mapper.ts` (pure function, mirrors
`layer-mapper.ts`).
