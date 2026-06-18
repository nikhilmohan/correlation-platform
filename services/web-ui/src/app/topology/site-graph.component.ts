import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EffectRef,
  NgZone,
  OnDestroy,
  OnInit,
  ViewChild,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { TopologyStore } from './topology.store';
import { ErrorBannerService } from '../core/error-banner.service';
import { NavigationService } from '../core/navigation.service';
import { AttributeDetailPanelComponent } from './attribute-detail-panel.component';
import { LayerToggleComponent } from './layer-toggle.component';
import { ICON_LEGEND, iconKeyForObjectType, iconUrlFor } from './type-icon-mapper';

// Type-only import — the runtime module is lazy-loaded in ngAfterViewInit so the Cytoscape bundle
// is fetched only when this view is shown, and unit tests can mock it.
import type cytoscape from 'cytoscape';
import type { Core as CyCore, ElementDefinition, LayoutOptions, NodeSingular } from 'cytoscape';

/**
 * Site-level EXPLORER graph (spec tasks 7-9, AC 27-32). The graph is an ACCUMULATING set held in
 * the store (nodeMap/edgeMap): rooting at a site seeds it; the operator then grows it explicitly
 * via per-node EXPAND controls (pull neighbours, cross-site/cross-domain on opt-in) or by selecting
 * a TRAIL (explode the topology to include the trail's full, possibly cross-site, member path).
 * Nothing auto-grows.
 *
 * Site boundaries render as Cytoscape COMPOUND PARENT nodes — one labelled box per distinct site in
 * nodeSiteMap; device nodes are parented into their site box. Layout is breadthfirst while a single
 * site is shown and switches to a deterministic `cose` (compound-aware) once ≥2 site boxes appear.
 *
 * Render strategy: Cytoscape draws into a single <canvas>; an effect mirrors the store into the
 * graph and reflects render counts onto the .cy-canvas element via data-cy-* attributes so the
 * Playwright suite can assert the REAL render. In jsdom (unit tests) init is guarded; the
 * accessible node/edge/trail lists are the WCAG complement / test source of truth.
 */
@Component({
  selector: 'app-site-graph',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AttributeDetailPanelComponent, LayerToggleComponent],
  template: `
    <nav class="breadcrumb" aria-label="Breadcrumb">
      <button
        type="button"
        class="crumb-link"
        data-testid="breadcrumb-topology"
        (click)="toTopology()"
      >
        ‹ Topology &amp; trails
      </button>
      <span class="crumb-sep" aria-hidden="true">/</span>
      <span class="crumb-current" aria-current="page">Site: {{ siteId() }}</span>
    </nav>

    <h1>Site graph — {{ siteId() }}</h1>
    @if (errors.forService('Topology Service'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    @if (store.activeTrailId(); as t) {
      <p class="active-trail" data-testid="active-trail">Active trail: {{ t }}</p>
    }
    @if (store.capReached()) {
      <p class="cap-note" role="status" data-testid="cap-reached">
        Node limit reached — collapse or reset to explore elsewhere.
      </p>
    }

    <div class="layout">
      <section class="graph-area">
        <app-layer-toggle />

        <div class="cy-wrap">
          <div
            #cyEl
            class="cy-canvas"
            role="application"
            [attr.data-cy-loading]="store.graphLoading()"
            [attr.data-cy-node-count]="bridgeNodeCount()"
            [attr.data-cy-edge-count]="bridgeEdgeCount()"
            [attr.data-cy-trail-count]="bridgeTrailCount()"
            [attr.data-cy-highlight-count]="highlightCount()"
            [attr.data-cy-layout-done]="layoutDone()"
            [attr.data-cy-node-spread]="nodeSpread()"
            [attr.data-cy-site-count]="bridgeSiteCount()"
            [attr.data-cy-expanded-node-count]="store.expandedNodeIds().size"
            [attr.data-cy-icon-types]="distinctIconKeyCount()"
            [attr.data-cy-zoom]="zoomLevel()"
            aria-label="Device-level topology graph for this site. Nodes and edges are listed below."
          ></div>

          <!-- ALWAYS-VISIBLE on-canvas EXPAND affordance per device node (UX redesign — the canvas
               IS the interface). Each "+" is an HTML overlay button positioned at the node's RENDERED
               position and re-positioned on every pan/zoom/layout so it tracks the node. It carries
               data-testid="expand-node" + the "Expand neighbours of <name>" aria-label so the existing
               e2e + a11y selectors still resolve, and is cap-disabled. In jsdom (no real cy render)
               overlayMarkers() is empty; the accessible List-view row control is the test/SR equivalent. -->
          <div class="cy-expand-layer" aria-hidden="false">
            @for (m of overlayMarkers(); track m.id) {
              <button
                type="button"
                class="cy-expand"
                data-testid="expand-node"
                [style.left.px]="m.x"
                [style.top.px]="m.y"
                [disabled]="store.capReached()"
                [attr.aria-label]="'Expand neighbours of ' + m.name"
                (click)="store.expandNode(m.id)"
              >
                +
              </button>
            }
          </div>

          <!-- Zoom / fit / reset controls overlaid on the canvas (operator-driven, keyboard-reachable). -->
          <div class="cy-controls" role="group" aria-label="Graph zoom controls">
            <button type="button" data-testid="zoom-in" aria-label="Zoom in" (click)="zoomIn()">+</button>
            <button type="button" data-testid="zoom-out" aria-label="Zoom out" (click)="zoomOut()">−</button>
            <button type="button" data-testid="zoom-fit" aria-label="Fit graph" (click)="fit()">Fit</button>
            <button type="button" data-testid="zoom-reset" aria-label="Reset graph to site root" (click)="reset()">
              Reset
            </button>
          </div>
        </div>

        <div class="legends">
          <ul class="layer-legend" aria-label="Graph layer legend">
            @for (item of LAYER_LEGEND; track item.layer) {
              <li>
                <span class="dot" [style.background]="item.color" aria-hidden="true"></span>{{ item.layer }}
              </li>
            }
          </ul>
          @if (siteLegend().length) {
            <ul class="site-legend" aria-label="Site boundary legend" data-testid="site-legend">
              @for (s of siteLegend(); track s.siteId) {
                <li data-testid="site-legend-item">
                  <span class="swatch" [style.border-color]="s.color" aria-hidden="true"></span>{{ s.name }}
                </li>
              }
            </ul>
          }
          <ul class="icon-legend" aria-label="Network element type icon legend" data-testid="icon-legend">
            @for (item of ICON_LEGEND; track item.key) {
              <li data-testid="icon-legend-item" [attr.data-icon]="item.key">
                <img class="icon-glyph" [src]="iconUrlForKey(item.key)" alt="" aria-hidden="true" width="16" height="16" />
                {{ item.label }}
              </li>
            }
          </ul>
        </div>

        @if (store.graphLoading()) {
          <p data-testid="graph-loading" aria-busy="true">Loading site graph…</p>
        } @else if (store.hasGraph()) {
          <!-- LIST VIEW (UX redesign): the Devices/Connections lists are COLLAPSED BY DEFAULT behind a
               keyboard-operable disclosure. The canvas is the primary interface; the lists are the
               WCAG non-visual equivalent of the <canvas> (AC 52) + the deterministic test source of
               truth + data-cy bridge, so they are CSS-HIDDEN (never *ngIf-removed) when collapsed —
               axe still finds them, screen-readers can disclose them, and Vitest/Playwright still
               resolve the per-row controls and data-* attributes regardless of visual state. -->
          <button
            type="button"
            class="list-view-toggle"
            data-testid="list-view-toggle"
            [attr.aria-expanded]="listViewOpen()"
            aria-controls="site-list-view"
            (click)="toggleListView()"
          >
            <span class="disclosure" aria-hidden="true">{{ listViewOpen() ? '▾' : '▸' }}</span>
            List view (devices &amp; connections)
          </button>

          <div id="site-list-view" class="list-view" [hidden]="!listViewOpen()">
            <h2>Devices</h2>
            <ul class="obj-list" aria-label="Devices in this site">
              @for (node of store.derivedNodes(); track node.managedObjectId) {
                <li class="obj-row">
                  <button
                    type="button"
                    class="obj"
                    data-testid="graph-node"
                    [attr.data-icon]="iconKeyFor(node.objectType)"
                    [attr.data-object-type]="node.objectType"
                    [class.selected]="store.selectedObjectId() === node.managedObjectId"
                    [class.trail-member]="isTrailMemberNode(node.managedObjectId)"
                    (click)="store.selectNode(node.managedObjectId)"
                    [attr.aria-pressed]="store.selectedObjectId() === node.managedObjectId"
                  >
                    <img
                      class="node-icon"
                      [src]="iconUrlFor(node.objectType)"
                      alt=""
                      aria-hidden="true"
                      width="16"
                      height="16"
                    />
                    {{ node.name ?? node.managedObjectId }}
                    <span class="layer-tag">{{ node.derivedLayer }}</span>
                    @if (siteFor(node.managedObjectId); as sn) {
                      <span class="site-tag" data-testid="node-site-tag">site: {{ sn }}</span>
                    }
                  </button>
                  <!-- Accessible/test equivalent of the on-canvas "+" — keyboard-reachable per row.
                       Carries the SAME data-testid + aria-label so the existing unit tests (which run
                       in jsdom where the canvas overlay is empty) and screen-reader users both reach
                       the SAME expandNode() behaviour. In a real browser the canvas "+" appears FIRST
                       in DOM order, so e2e .first() resolves the visible on-canvas control. -->
                  <button
                    type="button"
                    class="expand-btn"
                    data-testid="expand-node"
                    [disabled]="store.capReached()"
                    [attr.aria-label]="'Expand neighbours of ' + (node.name ?? node.managedObjectId)"
                    (click)="store.expandNode(node.managedObjectId)"
                  >
                    +expand
                  </button>
                </li>
              }
            </ul>

            <h2>Connections</h2>
            <ul class="obj-list" aria-label="Connections in this site">
              @for (edge of store.visibleEdges(); track edge.edgeId) {
                <li>
                  <button
                    type="button"
                    class="obj"
                    data-testid="graph-edge"
                    [attr.data-relation]="edge.relation"
                    [attr.data-layer]="edge.derivedLayer"
                    [class.selected]="store.selectedEdgeId() === edge.edgeId"
                    (click)="store.selectEdge(edge.edgeId)"
                    [attr.aria-pressed]="store.selectedEdgeId() === edge.edgeId"
                  >
                    {{ edge.from }} → {{ edge.to }}
                    <span class="layer-tag">{{ edge.relation }}</span>
                  </button>
                </li>
              }
            </ul>
          </div>

          <h2>Trail clusters</h2>
          @if (store.trails().length) {
            <ul class="trail-overlay" aria-label="Trail clusters overlaid on the graph">
              @for (trail of store.trails(); track trail.trailId) {
                <li>
                  <button
                    type="button"
                    class="trail-btn"
                    data-testid="trail-cluster"
                    [class.selected]="store.selectedTrailId() === trail.trailId"
                    [class.highlighted]="store.highlightedTrailIds().has(trail.trailId)"
                    [attr.aria-pressed]="store.selectedTrailId() === trail.trailId"
                    (click)="store.selectTrail(trail.trailId)"
                  >
                    {{ trail.trailId }} ({{ trail.memberCount }} members)
                    @if (store.highlightedTrailIds().has(trail.trailId)) {
                      <span class="badge badge-new">member</span>
                    }
                  </button>
                </li>
              }
            </ul>
          } @else {
            <p class="empty-state">No trails for this snapshot.</p>
          }

          <!-- Selected-trail detail: full member path (each a button → select that device), area/SRLG. -->
          @if (store.selectedTrailDetail(); as td) {
            <section class="trail-detail" data-testid="trail-detail" aria-label="Selected trail detail">
              <h2>Trail {{ td.trailId }}</h2>
              <p class="muted">
                {{ td.memberCount }} members
                @if (td.igpArea) {
                  · IGP area {{ td.igpArea }}
                }
                @if (td.srlgGroup) {
                  · SRLG {{ td.srlgGroup }}
                }
              </p>
              <ul class="trail-members" aria-label="Trail members">
                @for (m of td.members; track m.managedObjectId) {
                  <li>
                    <button
                      type="button"
                      class="obj"
                      data-testid="trail-member"
                      (click)="store.selectNode(m.managedObjectId)"
                    >
                      {{ m.managedObjectId }}
                      <span class="layer-tag">{{ m.objectType }}</span>
                    </button>
                  </li>
                }
              </ul>
              <button type="button" class="clear-trail" data-testid="clear-trail" (click)="store.clearTrail()">
                Clear trail
              </button>
            </section>
          }
        } @else {
          <p class="empty-state">No objects at this site.</p>
        }
      </section>

      <app-attribute-detail-panel />
    </div>
  `,
  styles: [
    `
      .breadcrumb {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-bottom: 0.4rem;
        font-size: 0.9rem;
      }
      .crumb-link {
        background: none;
        border: none;
        color: var(--accent);
        cursor: pointer;
        padding: 0;
        font: inherit;
      }
      .crumb-link:hover {
        text-decoration: underline;
      }
      .crumb-sep,
      .crumb-current {
        color: var(--text-muted);
      }
      .cap-note {
        color: var(--warn);
        font-size: 0.85rem;
      }
      .layout {
        display: grid;
        grid-template-columns: 2fr 1fr;
        gap: 1rem;
      }
      .cy-wrap {
        position: relative;
      }
      .cy-canvas {
        height: 640px;
        min-height: 640px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: #0b1220;
        margin-bottom: 0.8rem;
        position: relative;
        overflow: hidden;
      }
      /* On-canvas "+" expand affordances — an overlay above the canvas; each button is absolutely
         positioned at its node's rendered position and re-positioned on pan/zoom/layout so it tracks
         the node. pointer-events:none on the layer so panning the canvas still works; re-enabled on
         the buttons themselves. */
      .cy-expand-layer {
        position: absolute;
        inset: 0;
        pointer-events: none;
        z-index: 3;
      }
      /* ITEM 1: a SMALL, SUBTLE corner badge — sized RELATIVE to the (now bigger) node icon so it
         reads as a secondary affordance and does not compete with the type-icon glyph. Smaller box,
         lower-profile colour (muted border/translucent fill at rest), reduced opacity until hover/
         focus. Still a real focusable <button>: keyboard-reachable, visible, and clickable, and it
         keeps its data-testid/aria-label/click→expandNode/cap-disabled behaviour unchanged. */
      .cy-expand {
        position: absolute;
        transform: translate(-50%, -50%);
        width: 0.85rem;
        height: 0.85rem;
        padding: 0;
        line-height: 1;
        font-size: 0.62rem;
        font-weight: 600;
        border: 1px solid var(--border);
        border-radius: 50%;
        background: rgba(11, 18, 32, 0.85);
        color: var(--text-muted);
        cursor: pointer;
        pointer-events: auto;
        opacity: 0.7;
        display: flex;
        align-items: center;
        justify-content: center;
        box-shadow: 0 0 0 1px #0b1220;
        transition:
          opacity 0.1s ease,
          color 0.1s ease,
          border-color 0.1s ease;
      }
      .cy-expand:hover:not(:disabled),
      .cy-expand:focus-visible:not(:disabled) {
        opacity: 1;
        border-color: var(--accent);
        color: var(--accent);
      }
      .cy-expand:disabled {
        opacity: 0.3;
        cursor: not-allowed;
      }
      .list-view-toggle {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
        background: var(--surface);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.35rem 0.7rem;
        cursor: pointer;
        font: inherit;
        margin-bottom: 0.6rem;
      }
      .list-view-toggle:hover {
        border-color: var(--accent);
      }
      .list-view-toggle .disclosure {
        color: var(--accent);
      }
      .list-view[hidden] {
        display: none;
      }
      .cy-controls {
        position: absolute;
        top: 8px;
        right: 8px;
        display: flex;
        flex-direction: column;
        gap: 4px;
        z-index: 2;
      }
      .cy-controls button {
        width: 2rem;
        height: 2rem;
        border: 1px solid var(--border);
        background: var(--surface);
        color: var(--text);
        border-radius: 6px;
        cursor: pointer;
        font-size: 0.9rem;
        line-height: 1;
      }
      .cy-controls button[data-testid='zoom-fit'],
      .cy-controls button[data-testid='zoom-reset'] {
        width: auto;
        padding: 0 0.4rem;
        font-size: 0.7rem;
      }
      .cy-controls button:hover {
        border-color: var(--accent);
      }
      .legends {
        display: flex;
        flex-wrap: wrap;
        gap: 1.5rem;
        margin-bottom: 0.8rem;
      }
      .layer-legend,
      .site-legend {
        list-style: none;
        display: flex;
        flex-wrap: wrap;
        gap: 0.8rem;
        padding: 0;
        margin: 0;
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      .layer-legend li,
      .site-legend li {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
      }
      .layer-legend .dot {
        display: inline-block;
        width: 0.7rem;
        height: 0.7rem;
        border-radius: 50%;
      }
      .site-legend .swatch {
        display: inline-block;
        width: 0.8rem;
        height: 0.8rem;
        border-radius: 3px;
        border: 2px solid var(--border);
        background: rgba(255, 255, 255, 0.04);
      }
      .icon-legend {
        list-style: none;
        display: flex;
        flex-wrap: wrap;
        gap: 0.8rem;
        padding: 0;
        margin: 0;
        font-size: 0.8rem;
        color: var(--text-muted);
      }
      .icon-legend li {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
      }
      .icon-glyph {
        display: inline-block;
        width: 1rem;
        height: 1rem;
      }
      .node-icon {
        width: 0.95rem;
        height: 0.95rem;
        margin-right: 0.3rem;
        vertical-align: -2px;
      }
      .obj-list {
        list-style: none;
        padding: 0;
        margin: 0 0 1rem;
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
      }
      .obj-row {
        display: inline-flex;
        align-items: stretch;
        gap: 0;
      }
      .obj {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.3rem 0.6rem;
      }
      .obj-row .obj {
        border-top-right-radius: 0;
        border-bottom-right-radius: 0;
      }
      .expand-btn {
        background: var(--surface);
        color: var(--accent);
        border: 1px solid var(--border);
        border-left: none;
        border-radius: 0 6px 6px 0;
        padding: 0 0.4rem;
        font-size: 0.72rem;
        cursor: pointer;
      }
      .expand-btn:hover:not(:disabled) {
        border-color: var(--accent);
      }
      .expand-btn:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .obj.selected {
        border-color: var(--accent);
        outline: 2px solid var(--accent);
      }
      .obj.trail-member {
        border-color: var(--new);
      }
      .layer-tag {
        color: var(--text-muted);
        font-size: 0.78rem;
        margin-left: 0.3rem;
      }
      .site-tag {
        color: var(--accent);
        font-size: 0.72rem;
        margin-left: 0.3rem;
      }
      .trail-overlay {
        list-style: none;
        padding: 0;
        margin: 0;
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
      }
      .trail-btn {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.3rem 0.6rem;
        text-align: left;
        cursor: pointer;
        width: 100%;
      }
      .trail-btn.highlighted {
        color: var(--new);
        font-weight: 600;
      }
      .trail-btn.selected {
        border-color: var(--accent);
        outline: 2px solid var(--accent);
      }
      .trail-detail {
        margin-top: 1rem;
        padding: 0.6rem;
        border: 1px solid var(--border);
        border-radius: 8px;
        background: var(--surface);
      }
      .trail-members {
        list-style: none;
        padding: 0;
        margin: 0.4rem 0;
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
      }
      .clear-trail {
        background: var(--surface-2);
        color: var(--text);
        border: 1px solid var(--border);
        border-radius: 6px;
        padding: 0.3rem 0.6rem;
        cursor: pointer;
      }
      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      .active-trail {
        color: var(--accent);
      }
      @media (max-width: 800px) {
        .layout {
          grid-template-columns: 1fr;
        }
      }
    `,
  ],
})
export class SiteGraphComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly store = inject(TopologyStore);
  readonly errors = inject(ErrorBannerService);
  private readonly nav = inject(NavigationService);
  private readonly zone = inject(NgZone);

  /** Route param binding (withComponentInputBinding). */
  readonly siteId = input<string>('');

  @ViewChild('cyEl') private cyEl?: ElementRef<HTMLDivElement>;

  /** Proves the guarded Cytoscape init path ran (asserted by the unit test even in jsdom). */
  cyInitAttempted = false;

  /** Layer → colour map shared by the Cytoscape node styling and the on-screen legend. */
  static readonly LAYER_COLORS: Record<string, string> = {
    fiber: '#f59e0b',
    IP: '#60a5fa',
    IGP: '#a78bfa',
    LSP: '#34d399',
    service: '#f472b6',
    other: '#94a3b8',
  };
  readonly LAYER_LEGEND = Object.entries(SiteGraphComponent.LAYER_COLORS).map(([layer, color]) => ({ layer, color }));

  /** Network-element type-icon legend (objectType → glyph), rendered beneath the layer legend. */
  readonly ICON_LEGEND = ICON_LEGEND;

  /** Resolved icon KEY for an objectType (the `data-icon` bridge value, `generic` fallback — AC 71). */
  iconKeyFor(objectType: string | undefined | null): string {
    return iconKeyForObjectType(objectType);
  }
  /** Same-origin bundle URL for an objectType's icon (used by the accessible row `<img>`, AC 72). */
  iconUrlFor(objectType: string | undefined | null): string {
    return iconUrlFor(objectType);
  }
  /** Same-origin bundle URL for an icon KEY (used by the legend rows). */
  iconUrlForKey(key: string): string {
    const base = typeof document !== 'undefined' && document.baseURI ? document.baseURI : '/';
    return new URL(`icons/${key}.svg`, base).href;
  }
  /** Count of DISTINCT type-icon keys currently rendered (bridged as data-cy-icon-types — AC 70). */
  readonly distinctIconKeyCount = computed(
    () => new Set(this.store.derivedNodes().map((n) => iconKeyForObjectType(n.objectType))).size,
  );

  /** Small deterministic palette for site-boundary boxes (by site index). */
  static readonly SITE_COLORS = ['#22d3ee', '#f59e0b', '#a78bfa', '#34d399', '#f472b6', '#60a5fa', '#fb7185', '#facc15'];

  private cytoscape: typeof cytoscape | null = null;
  private cy: CyCore | null = null;
  private readonly cyReady = signal(false);
  private structureEffect!: EffectRef;
  private decorationEffect!: EffectRef;
  private resizeObserver: ResizeObserver | null = null;

  /** Disclosure state of the "List view" (Devices/Connections) region — collapsed by default so the
   *  canvas is the primary interface. The lists stay in the DOM (CSS `[hidden]`) for a11y + tests. */
  readonly listViewOpen = signal(false);
  toggleListView(): void {
    this.listViewOpen.update((v) => !v);
  }

  /** On-canvas "+" expand affordances: one per device node, positioned at the node's RENDERED
   *  position (canvas pixels) so each "+" tracks its node on pan/zoom/layout. Empty under jsdom
   *  (no real Cytoscape render) — there the accessible list-row control is the equivalent. */
  readonly overlayMarkers = signal<ReadonlyArray<{ id: string; name: string; x: number; y: number }>>([]);

  /** True once the deterministic layout has settled at least once (bridged to data-cy-layout-done). */
  readonly layoutDone = signal(false);
  /** Max(bbox width, bbox height) of all laid-out nodes — ~0 when collapsed to a blob. */
  readonly nodeSpread = signal(0);
  /** Current cytoscape zoom level (bridged to data-cy-zoom). */
  readonly zoomLevel = signal(1);

  /** Gate so cy.fit() runs only on the FIRST layout + explicit Fit/Reset — never on a same-scope
   *  expand/merge relayout, preserving the operator's manual zoom/pan. */
  private firstFitDone = false;
  /** Distinct-site count at the last auto-fit. When the graph gains a NEW site box (a major scope
   *  change — e.g. a cross-site expand or trail explode) we re-fit ONCE so the new box is on-screen;
   *  same-site expands do not re-fit (manual zoom/pan preserved). */
  private lastFittedSiteCount = 0;

  /** Stable ordering of site ids → index, so a site keeps the same colour as the graph grows. */
  private siteOrder = new Map<string, number>();

  /** Identity key for the STRUCTURE of the graph (node + edge id sets + site parenting). A change
   *  to this key means the graph must be rebuilt and re-laid-out; selection/highlight must NOT. */
  private readonly structureKey = computed(() => {
    const nodeIds = this.store.derivedNodes().map((n) => n.managedObjectId);
    const edgeIds = this.store.visibleEdges().map((e) => e.edgeId);
    // Include the site-parenting so a newly-arrived LOCATED_AT edge re-parents + relays out.
    const siteMap = this.store.nodeSiteMap();
    const parenting = nodeIds.map((id) => `${id}:${siteMap.get(id) ?? ''}`).join(',');
    return `${nodeIds.join(',')}|${edgeIds.join(',')}|${parenting}`;
  });

  /**
   * Deterministic test/render bridge — bound onto the .cy-canvas via [attr.data-cy-*] in the
   * template so the counts track the store signals through normal change detection, correct the
   * moment data resolves, independent of Cytoscape readiness. While loading the counts read 0.
   */
  readonly bridgeNodeCount = computed(() => (this.store.graphLoading() ? 0 : this.store.derivedNodes().length));
  readonly bridgeEdgeCount = computed(() => (this.store.graphLoading() ? 0 : this.store.visibleEdges().length));
  readonly bridgeTrailCount = computed(() => (this.store.graphLoading() ? 0 : this.store.trails().length));
  readonly bridgeSiteCount = computed(() => (this.store.graphLoading() ? 0 : this.store.distinctSiteIds().length));

  /** Painted-graph highlight count (AC 31/32), reflected onto the canvas by applyDecoration(). */
  readonly highlightCount = signal(0);

  /** Site legend rows (id + name + colour) for the distinct sites in the current graph. */
  readonly siteLegend = computed(() => {
    const order = this.assignSiteOrder(this.store.distinctSiteIds());
    return this.store.distinctSiteIds().map((siteId) => ({
      siteId,
      name: this.store.siteName(siteId),
      color: SiteGraphComponent.SITE_COLORS[(order.get(siteId) ?? 0) % SiteGraphComponent.SITE_COLORS.length],
    }));
  });

  constructor() {
    // STRUCTURE effect — rebuild the graph elements + run the deterministic layout ONLY when the
    // node/edge/parenting id-set key changes (a genuinely different graph).
    this.structureEffect = effect(() => {
      const key = this.structureKey(); // tracked: rebuild only when the id-sets change
      void key;
      const nodes = this.store.derivedNodes();
      const edges = this.store.visibleEdges();
      if (!this.cyReady() || !this.cy) {
        return;
      }
      this.rebuildAndLayout(nodes, edges);
      this.applyDecoration();
    });

    // DECORATION effect — selection / trail highlight only toggle classes; NO layout (no blob).
    this.decorationEffect = effect(() => {
      // Track the decoration inputs explicitly.
      this.store.selectedObjectId();
      this.store.selectedTrailId();
      this.store.trailMemberIds();
      this.store.highlightedTrailIds();
      if (!this.cyReady() || !this.cy) {
        return;
      }
      this.applyDecoration();
    });
  }

  ngOnInit(): void {
    const id = this.siteId();
    if (id) {
      this.store.selectSite(id);
    }
  }

  /** Breadcrumb: back to the geo-site map (topology entry view). */
  toTopology(): void {
    this.nav.toTopology();
  }

  /** Site of a device for the accessible row tag (friendly name), or null if unknown. */
  siteFor(managedObjectId: string): string | null {
    const siteId = this.store.nodeSiteMap().get(managedObjectId);
    return siteId ? this.store.siteName(siteId) : null;
  }

  async ngAfterViewInit(): Promise<void> {
    this.cyInitAttempted = true;
    const el = this.cyEl?.nativeElement;
    if (!el) {
      return;
    }
    try {
      const cy = (await import('cytoscape')).default;
      this.cytoscape = cy;
      const colors = SiteGraphComponent.LAYER_COLORS;
      this.cy = cy({
        container: el,
        elements: [],
        style: [
          {
            selector: 'node[?isSiteParent]',
            style: {
              // Labelled site-boundary box (compound parent): low-opacity bg, per-site border colour.
              'background-color': 'data(boxColor)',
              'background-opacity': 0.08,
              'border-color': 'data(boxColor)',
              'border-width': 2,
              shape: 'round-rectangle',
              label: 'data(label)',
              color: 'data(boxColor)',
              'font-size': 11,
              'font-weight': 'bold',
              'text-valign': 'top',
              'text-halign': 'center',
              'text-margin-y': -4,
              padding: '18px',
            },
          },
          {
            selector: 'node[!isSiteParent]',
            style: {
              // Network-element TYPE ICON as the node glyph (AC 70-72): a same-origin bundled SVG
              // resolved from the node's objectType, drawn contained over a dark chip. The DERIVED
              // LOGICAL LAYER stays readable as the node's coloured BORDER/ring (both type-icon AND
              // layer are encoded). A generic.svg fallback guarantees no node is ever icon-less.
              'background-image': (n) => iconUrlFor(n.data('objectType') as string),
              'background-fit': 'contain',
              'background-clip': 'none',
              'background-opacity': 1,
              // ITEM 2: larger glyph share of the (bigger) node so the type icon reads clearly.
              'background-width': '88%',
              'background-height': '88%',
              'background-color': '#0b1220',
              'border-color': (n) => colors[n.data('layer') as string] ?? colors['other'],
              'border-width': 4,
              shape: 'round-rectangle',
              label: 'data(label)',
              color: '#f1f5f9',
              'font-size': 13,
              'text-outline-width': 2,
              'text-outline-color': '#0b1220',
              'text-valign': 'bottom',
              'text-margin-y': 4,
              // ITEM 2: bumped node box (52 → 76) so the type-icon glyph is large and legible. The
              // multi-site preset spacing + breadthfirst spacingFactor below are tuned to match so
              // bigger nodes don't overlap (data-cy-node-spread stays > 40, deterministic).
              width: 76,
              height: 76,
            },
          },
          {
            selector: 'edge',
            style: {
              'line-color': (e) => colors[e.data('layer') as string] ?? '#475569',
              'curve-style': 'bezier',
              width: 2,
              opacity: 0.7,
            },
          },
          { selector: 'node.highlighted', style: { 'border-width': 3, 'border-color': '#22d3ee' } },
          { selector: 'node.selected', style: { 'border-width': 4, 'border-color': '#60a5fa' } },
          { selector: 'edge.trail-member', style: { 'line-color': '#22d3ee', width: 4, opacity: 1 } },
        ],
      });

      this.cy.on('tap', 'node[!isSiteParent]', (evt) => {
        const id = evt.target.id();
        this.zone.run(() => this.store.selectNode(id));
      });
      this.cy.on('tap', 'edge', (evt) => {
        const id = evt.target.id();
        this.zone.run(() => this.store.selectEdge(id));
      });
      // Keep the zoom bridge live as the operator pans/zooms by mouse/trackpad, and re-position the
      // on-canvas "+" affordances so they track their nodes through pan/zoom/render.
      this.cy.on('zoom', () =>
        this.zone.run(() => {
          this.zoomLevel.set(this.roundZoom());
          this.refreshOverlayMarkers();
        }),
      );
      this.cy.on('pan render', () => this.zone.run(() => this.refreshOverlayMarkers()));

      this.cy.on('layoutstop', () =>
        this.zone.run(() => {
          this.layoutDone.set(true);
          this.publishSpread();
          this.zoomLevel.set(this.roundZoom());
          this.refreshOverlayMarkers();
        }),
      );

      // SIZE GUARD: don't flip cyReady (first layout) until the canvas has a real, non-zero box —
      // laying out into a 0x0 container collapses every node onto the origin (the blob).
      this.resizeObserver = new ResizeObserver(() => {
        if (!this.cy) {
          return;
        }
        if (el.clientWidth > 0 && el.clientHeight > 0) {
          if (!this.cyReady()) {
            this.zone.run(() => this.cyReady.set(true));
          } else {
            this.cy.resize();
          }
        }
      });
      this.resizeObserver.observe(el);

      if (el.clientWidth > 0 && el.clientHeight > 0) {
        this.zone.run(() => this.cyReady.set(true));
      }
    } catch {
      // jsdom / no-canvas environment: keep the accessible lists as the rendering surface.
    }
  }

  /** STRUCTURE: rebuild the graph elements (compound site boxes + leaf nodes + edges) and run a
   *  deterministic layout. Existing node positions are locked across an expand relayout so already-
   *  placed nodes stay put and the operator's view is preserved. */
  private rebuildAndLayout(
    nodes: ReturnType<TopologyStore['derivedNodes']>,
    edges: ReturnType<TopologyStore['visibleEdges']>,
  ): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    const nodeIds = new Set(nodes.map((n) => n.managedObjectId));
    const siteMap = this.store.nodeSiteMap();
    const distinctSites = this.store.distinctSiteIds();
    const order = this.assignSiteOrder(distinctSites);

    // Preserve positions of nodes already laid out so an expand doesn't reshuffle the whole graph.
    const prevPos = new Map<string, { x: number; y: number }>();
    cy.nodes('[!isSiteParent]').forEach((n) => {
      prevPos.set(n.id(), { ...n.position() });
    });

    const parentDefs: ElementDefinition[] = distinctSites.map((siteId) => ({
      data: {
        id: this.siteParentId(siteId),
        label: this.store.siteName(siteId),
        isSiteParent: true,
        boxColor: SiteGraphComponent.SITE_COLORS[(order.get(siteId) ?? 0) % SiteGraphComponent.SITE_COLORS.length],
      },
    }));

    const leafDefs: ElementDefinition[] = nodes.map((n) => {
      const siteId = siteMap.get(n.managedObjectId);
      return {
        data: {
          id: n.managedObjectId,
          label: n.name ?? n.managedObjectId,
          layer: n.derivedLayer,
          // objectType drives the type-icon background-image; `icon` is the resolved key (AC 70-72).
          objectType: n.objectType,
          icon: iconKeyForObjectType(n.objectType),
          isSiteParent: false,
          ...(siteId ? { parent: this.siteParentId(siteId) } : {}),
        },
      };
    });

    const edgeDefs: ElementDefinition[] = edges
      .filter((e) => nodeIds.has(e.from) && nodeIds.has(e.to))
      .map((e) => ({
        data: { id: e.edgeId, source: e.from, target: e.to, relation: e.relation, layer: e.derivedLayer },
      }));

    cy.elements().remove();
    cy.add([...parentDefs, ...leafDefs, ...edgeDefs]);

    // For the MULTI-SITE preset layout every position is recomputed deterministically, so locking is
    // neither needed nor wanted (the preset is authoritative + stable). For a SINGLE-SITE same-scope
    // expand we lock already-placed nodes so only the NEW nodes are positioned and the operator's
    // view is preserved. (Scope-grow always re-lays-out + re-fits to reveal the new box.)
    const scopeGrew = distinctSites.length > this.lastFittedSiteCount;
    const multiSite = distinctSites.length > 1;
    const locked: NodeSingular[] = [];
    if (!scopeGrew && !multiSite) {
      cy.nodes('[!isSiteParent]').forEach((n) => {
        const p = prevPos.get(n.id());
        if (p) {
          n.position(p);
          n.lock();
          locked.push(n);
        }
      });
    }

    this.runLayout(distinctSites.length);
    for (const n of locked) {
      n.unlock();
    }
  }

  /** Run the size-appropriate deterministic layout. A single site uses breadthfirst (circle-packed,
   *  fans devices across the canvas). With ≥2 site boxes the built-in compound cose is unreliable
   *  (it collapses few-node compound graphs into a vertical strip), so we use a DETERMINISTIC PRESET:
   *  site boxes are placed left-to-right and each box's devices are arranged in an internal grid, so
   *  two sites always read as two clearly-separated clusters that fill the canvas. animate:false so
   *  layoutstop fires and the layout-done bridge stays reliable. Fit on first layout / scope-grow. */
  private runLayout(siteCount: number): void {
    const cy = this.cy;
    if (!cy || cy.nodes('[!isSiteParent]').length === 0) {
      this.publishSpread();
      return;
    }
    cy.resize();
    const layout =
      siteCount > 1
        ? cy.layout({
            name: 'preset',
            positions: this.computeMultiSitePositions(),
            fit: false,
            animate: false,
          } as unknown as LayoutOptions)
        : cy.layout({
            // Single site: breadthfirst, but circle-packed and well-spaced so devices fan out across
            // the canvas instead of stacking near the top edge.
            name: 'breadthfirst',
            animate: false,
            circle: true,
            // Wider spread to accommodate the bigger (76px) nodes without overlap.
            spacingFactor: 2.9,
            padding: 70,
            nodeDimensionsIncludeLabels: true,
            avoidOverlap: true,
          } as unknown as LayoutOptions);
    layout.run();
    // Auto-fit on the FIRST layout, and once more whenever the graph gains a NEW site box (a major
    // scope change, e.g. a cross-site expand / trail explode) so the new box is visible. Same-site
    // expands do NOT re-fit, preserving the operator's manual zoom/pan. Pad generously so the laid-out
    // graph fills the canvas centred rather than hugging the top edge.
    if (!this.firstFitDone || siteCount > this.lastFittedSiteCount) {
      cy.fit(undefined, 60);
      this.firstFitDone = true;
      this.lastFittedSiteCount = siteCount;
    }
    this.publishSpread();
    this.refreshOverlayMarkers();
  }

  /**
   * Deterministic preset positions for the MULTI-SITE case: each distinct site box is a column in a
   * left-to-right row (well-separated, so the boxes never overlap), and the devices inside each box
   * are arranged in an internal grid. Fully deterministic (no force simulation), so the same graph
   * always lays out identically and two sites always render as two distinct clusters that fill the
   * canvas — fixes the cose "vertical strip / blob" failure.
   */
  private computeMultiSitePositions(): Record<string, { x: number; y: number }> {
    const cy = this.cy;
    const positions: Record<string, { x: number; y: number }> = {};
    if (!cy) {
      return positions;
    }
    const siteMap = this.store.nodeSiteMap();
    const sites = this.store.distinctSiteIds();
    // Devices grouped by their site; devices with no known site go in a trailing "unsited" column.
    const bySite = new Map<string, string[]>();
    for (const id of sites) {
      bySite.set(id, []);
    }
    const unsited: string[] = [];
    cy.nodes('[!isSiteParent]').forEach((n) => {
      const site = siteMap.get(n.id());
      if (site && bySite.has(site)) {
        bySite.get(site)!.push(n.id());
      } else {
        unsited.push(n.id());
      }
    });

    const NODE_GAP = 170; // spacing between devices within a site box (bigger 76px nodes → wider spread)
    const COL_GAP = 240; // horizontal gap between site columns
    const columns: Array<{ ids: string[] }> = [];
    for (const id of sites) {
      columns.push({ ids: bySite.get(id) ?? [] });
    }
    if (unsited.length) {
      columns.push({ ids: unsited });
    }

    let cursorX = 0;
    for (const col of columns) {
      const count = Math.max(col.ids.length, 1);
      // A near-square internal grid per site box.
      const gridCols = Math.max(1, Math.ceil(Math.sqrt(count)));
      const colWidth = gridCols * NODE_GAP;
      col.ids.forEach((id, i) => {
        const gx = i % gridCols;
        const gy = Math.floor(i / gridCols);
        positions[id] = { x: cursorX + gx * NODE_GAP, y: gy * NODE_GAP };
      });
      cursorX += colWidth + COL_GAP;
    }
    return positions;
  }

  /** DECORATION: toggle selected/highlighted/trail-member classes only — NEVER runs a layout. The
   *  highlight set is the FULL trail member set when a trail is selected (fixes the hollow 1-node
   *  highlight), else the device-anchored set. */
  private applyDecoration(): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    cy.elements().removeClass('highlighted selected trail-member');
    const memberIds = this.trailMemberObjectIds();
    const selectedId = this.store.selectedObjectId();
    let highlightCount = 0;
    if (selectedId) {
      const sel = cy.getElementById(selectedId);
      if (sel.nonempty()) {
        sel.addClass('selected');
      }
    }
    cy.nodes('[!isSiteParent]').forEach((n) => {
      if (memberIds.has(n.id())) {
        n.addClass('highlighted');
        highlightCount++;
      }
    });
    cy.edges().forEach((e) => {
      if (memberIds.has(e.source().id()) && memberIds.has(e.target().id())) {
        e.addClass('trail-member');
        highlightCount++;
      }
    });
    this.highlightCount.set(highlightCount);
  }

  /**
   * Recompute the on-canvas "+" overlay positions from each leaf node's RENDERED position (canvas
   * pixel coordinates relative to the container), so every "+" tracks its node as the operator pans
   * and zooms. Called on layoutstop / pan / zoom / render. A small upward+rightward offset places the
   * "+" at the node's top-right corner, scaled with the node so it stays attached at any zoom.
   */
  private refreshOverlayMarkers(): void {
    const cy = this.cy;
    // Guard: no cy, or a narrow test stub without the graph API (graph-zoom.spec injects a zoom/fit
    // -only stub). The overlay only renders against a real Cytoscape core; otherwise stay empty.
    if (!cy || typeof cy.nodes !== 'function') {
      this.overlayMarkers.set([]);
      return;
    }
    const leaves = cy.nodes('[!isSiteParent]');
    if (leaves.length === 0) {
      this.overlayMarkers.set([]);
      return;
    }
    const zoom = cy.zoom();
    const markers: Array<{ id: string; name: string; x: number; y: number }> = [];
    leaves.forEach((n) => {
      const rp = n.renderedPosition();
      // Offset to the node's top-right corner; the rendered half-width scales with zoom.
      const halfW = (n.width() / 2) * zoom;
      const halfH = (n.height() / 2) * zoom;
      markers.push({
        id: n.id(),
        name: (n.data('label') as string) ?? n.id(),
        x: rp.x + halfW * 0.7,
        y: rp.y - halfH * 0.7,
      });
    });
    this.overlayMarkers.set(markers);
  }

  /** Publish the laid-out leaf-node spread (max bbox dimension) — ~0 when collapsed to a blob. */
  private publishSpread(): void {
    const cy = this.cy;
    const leaves = cy?.nodes('[!isSiteParent]');
    if (!cy || !leaves || leaves.length === 0) {
      this.nodeSpread.set(0);
      return;
    }
    const bb = leaves.boundingBox();
    this.nodeSpread.set(Math.round(Math.max(bb.w, bb.h)));
  }

  /**
   * The managedObjectIds to highlight on the painted graph. When a trail is explicitly selected this
   * is its FULL member set (the cross-site path lights up — fixes the previous hollow 1-node
   * behaviour). Otherwise it falls back to the device-anchored highlight (selected node when its
   * trails are in the highlighted set).
   */
  private trailMemberObjectIds(): Set<string> {
    if (this.store.selectedTrailId() && this.store.trailMemberIds().size > 0) {
      return new Set(this.store.trailMemberIds());
    }
    const ids = new Set<string>();
    if (this.store.highlightedTrailIds().size > 0) {
      const sel = this.store.selectedObjectId();
      if (sel) {
        ids.add(sel);
      }
    }
    return ids;
  }

  isTrailMemberNode(managedObjectId: string): boolean {
    if (this.store.selectedTrailId() && this.store.trailMemberIds().size > 0) {
      return this.store.trailMemberIds().has(managedObjectId);
    }
    return this.store.selectedObjectId() === managedObjectId && this.store.highlightedTrailIds().size > 0;
  }

  // ── Zoom / fit / reset controls ──────────────────────────────────────────────────────────────
  zoomIn(): void {
    this.zoomAbout(1.2);
  }
  zoomOut(): void {
    this.zoomAbout(1 / 1.2);
  }
  private zoomAbout(factor: number): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    const ext = cy.extent();
    cy.zoom({ level: cy.zoom() * factor, position: { x: (ext.x1 + ext.x2) / 2, y: (ext.y1 + ext.y2) / 2 } });
    this.zoomLevel.set(this.roundZoom());
    this.refreshOverlayMarkers();
  }
  fit(): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    cy.fit(undefined, 60);
    this.zoomLevel.set(this.roundZoom());
    this.refreshOverlayMarkers();
  }
  reset(): void {
    // Reset re-roots the graph at the site (discards expansions) and re-fits on the next layout.
    this.firstFitDone = false;
    this.lastFittedSiteCount = 0;
    this.store.collapseToRoot();
  }

  private roundZoom(): number {
    return this.cy ? Math.round(this.cy.zoom() * 1000) / 1000 : 1;
  }

  /**
   * TEST-ONLY hook: inject a stubbed Cytoscape core so the zoom/fit/reset handlers (AC 73) can be
   * exercised under jsdom where the real Cytoscape canvas is never constructed (no WebGL). Production
   * code never calls this — the real `cy` is built in ngAfterViewInit. Kept narrow and explicit so
   * the approved render path is unchanged.
   */
  setCyForTest(cy: CyCore): void {
    this.cy = cy;
  }

  // ── Site colour ordering ─────────────────────────────────────────────────────────────────────
  /** Assign / reuse a stable index for each site id so colours don't shuffle as the graph grows. */
  private assignSiteOrder(siteIds: readonly string[]): Map<string, number> {
    for (const id of siteIds) {
      if (!this.siteOrder.has(id)) {
        this.siteOrder.set(id, this.siteOrder.size);
      }
    }
    return this.siteOrder;
  }

  private siteParentId(siteId: string): string {
    return `site::${siteId}`;
  }

  ngOnDestroy(): void {
    this.structureEffect.destroy();
    this.decorationEffect.destroy();
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.cy?.destroy();
    this.cy = null;
  }
}
