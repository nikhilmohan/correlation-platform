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
            [attr.data-cy-zoom]="zoomLevel()"
            aria-label="Device-level topology graph for this site. Nodes and edges are listed below."
          ></div>

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
        </div>

        @if (store.graphLoading()) {
          <p data-testid="graph-loading" aria-busy="true">Loading site graph…</p>
        } @else if (store.hasGraph()) {
          <h2>Devices</h2>
          <ul class="obj-list" aria-label="Devices in this site">
            @for (node of store.derivedNodes(); track node.managedObjectId) {
              <li class="obj-row">
                <button
                  type="button"
                  class="obj"
                  data-testid="graph-node"
                  [class.selected]="store.selectedObjectId() === node.managedObjectId"
                  [class.trail-member]="isTrailMemberNode(node.managedObjectId)"
                  (click)="store.selectNode(node.managedObjectId)"
                  [attr.aria-pressed]="store.selectedObjectId() === node.managedObjectId"
                >
                  {{ node.name ?? node.managedObjectId }}
                  <span class="layer-tag">{{ node.derivedLayer }}</span>
                  @if (siteFor(node.managedObjectId); as sn) {
                    <span class="site-tag" data-testid="node-site-tag">site: {{ sn }}</span>
                  }
                </button>
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
        height: 360px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: #0b1220;
        margin-bottom: 0.8rem;
        position: relative;
        overflow: hidden;
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

  /** Small deterministic palette for site-boundary boxes (by site index). */
  static readonly SITE_COLORS = ['#22d3ee', '#f59e0b', '#a78bfa', '#34d399', '#f472b6', '#60a5fa', '#fb7185', '#facc15'];

  private cytoscape: typeof cytoscape | null = null;
  private cy: CyCore | null = null;
  private readonly cyReady = signal(false);
  private structureEffect!: EffectRef;
  private decorationEffect!: EffectRef;
  private resizeObserver: ResizeObserver | null = null;

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
              // Leaf node fill by derived logical layer (operator colour-coding) — unchanged.
              'background-color': (n) => colors[n.data('layer') as string] ?? colors['other'],
              label: 'data(label)',
              color: '#f1f5f9',
              'font-size': 9,
              'text-outline-width': 2,
              'text-outline-color': '#0b1220',
              'text-valign': 'bottom',
              'text-margin-y': 2,
              width: 22,
              height: 22,
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
      // Keep the zoom bridge live as the operator pans/zooms by mouse/trackpad.
      this.cy.on('zoom', () => this.zone.run(() => this.zoomLevel.set(this.roundZoom())));

      this.cy.on('layoutstop', () =>
        this.zone.run(() => {
          this.layoutDone.set(true);
          this.publishSpread();
          this.zoomLevel.set(this.roundZoom());
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

    // When the SITE SCOPE grows (a new site box) we run a FULL relayout (no locking) so the
    // compound-aware cose can place all boxes legibly and we re-fit to show the new box. For a
    // same-scope expand we lock already-placed nodes so only the NEW nodes are positioned and the
    // operator's view is preserved.
    const scopeGrew = distinctSites.length > this.lastFittedSiteCount;
    const locked: NodeSingular[] = [];
    if (!scopeGrew) {
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

  /** Run the size-appropriate deterministic layout. breadthfirst for a single site box; cose
   *  (compound-aware, deterministic) once ≥2 site boxes appear. animate:false so layoutstop fires
   *  and the layout-done bridge stays reliable. Fit only on the first layout (firstFitDone gate). */
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
            name: 'cose',
            animate: false,
            randomize: false,
            componentSpacing: 120,
            nodeRepulsion: () => 12000,
            idealEdgeLength: () => 80,
            nestingFactor: 1.2,
            gravity: 0.4,
            numIter: 1000,
            padding: 20,
          } as unknown as LayoutOptions)
        : cy.layout({ name: 'breadthfirst', animate: false, spacingFactor: 1.2, padding: 20 });
    layout.run();
    // Auto-fit on the FIRST layout, and once more whenever the graph gains a NEW site box (a major
    // scope change, e.g. a cross-site expand / trail explode) so the new box is visible. Same-site
    // expands do NOT re-fit, preserving the operator's manual zoom/pan.
    if (!this.firstFitDone || siteCount > this.lastFittedSiteCount) {
      cy.fit(undefined, 20);
      this.firstFitDone = true;
      this.lastFittedSiteCount = siteCount;
    }
    this.publishSpread();
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
  }
  fit(): void {
    const cy = this.cy;
    if (!cy) {
      return;
    }
    cy.fit(undefined, 20);
    this.zoomLevel.set(this.roundZoom());
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
