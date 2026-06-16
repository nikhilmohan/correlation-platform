# Network-element type icons

Bundled inline SVG glyphs, one per Core IP `objectType`, used as Cytoscape node
`background-image` in the site-graph view and in the type-icon legend (spec ACs 70-72,
design `## Icon asset design`).

## Source & license

These icons are **original works authored in-repo** for this project. They are released
under the **MIT License** (the same permissive license as the project), per the platform
golden rule "only permissive open source license components (MIT, Apache 2.0, BSD, CC0)".

No external icon pack, icon font, or CDN sprite is used. The icons are simple 24x24
two-tone glyphs designed to read at small node size on the dark graph canvas.

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
