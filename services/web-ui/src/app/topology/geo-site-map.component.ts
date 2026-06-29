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
  signal,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TopologyStore } from './topology.store';
import { NavigationService } from '../core/navigation.service';
import { ErrorBannerService } from '../core/error-banner.service';
import { ThemeService } from '../core/theme.service';
import { SiteDto } from '../api/models';

// Type-only import — the runtime module is lazy-loaded in ngAfterViewInit so the (large) MapLibre
// bundle is fetched only when this view is actually shown, and unit tests can mock it.
import type {
  Map as MlMap,
  GeoJSONSource,
  MapGeoJSONFeature,
  StyleSpecification,
  LngLatBoundsLike,
} from 'maplibre-gl';
import type { FeatureCollection, Point } from 'geojson';

/** Operator status of a site. Drives the status-dot colour, the legend and the status bar. */
export type SiteStatus = 'fault' | 'warning' | 'monitored';

/**
 * Geo-site map (spec task 6, AC 26; #276 clustering). The entry view of the topology module. Sites
 * returned by the Topology site query API are pushed into a MapLibre native GeoJSON CLUSTERING
 * source on a REAL UK/EU basemap (country outlines/coastlines from the committed offline
 * `geo/europe.json` asset), driven by an Angular effect() over TopologyStore.sites(). An accessible
 * site list is kept as the WCAG complement / drill-in source of truth (same data, same data-testid)
 * — clustering hides individual canvas pins at low zoom, so the accessible list is how SR users +
 * tests reach every site. Supports the `?trailId=` deep link (AC 24) carried into the site graph.
 *
 * Render strategy: the MapLibre Map uses a LOCAL GeoJSON basemap (sea/land/borders/coast layers)
 * from `geo/europe.json` — no remote tiles, glyphs or sprite, so the map renders fully offline. The
 * sites are a single GeoJSON source with `cluster: true`; a cluster-circle layer + an
 * unclustered-site (status-dot) layer render them, collapsing the dense UK/EU set into ONE count
 * badge at the tuned default zoom (#276 — no overlapping-pin clutter / click intercept) and
 * splitting into individual pins on zoom-in. Cluster counts are drawn as DOM badges synced to each
 * cluster's projected centre (offline-safe — a symbol text-field would need a glyph stack the
 * offline basemap omits). Click a cluster → ease to its expansion zoom; click a site pin → drill
 * in. WebGL is required; in jsdom (unit tests) there is no WebGL context, so map construction is
 * skipped while `mapInitAttempted` is still flipped true to prove the guarded real-render path ran.
 * A status bar + legend summarise the fleet (Fault/Warning/Monitored); status is text/aria-readable,
 * never colour-only.
 */
@Component({
  selector: 'app-geo-site-map',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>Topology &amp; trails — sites</h1>
    @if (errors.forService('Topology Service'); as err) {
      <div class="error-banner" role="alert">{{ err.message }}</div>
    }

    <!-- Operator status bar + legend (above the map). Summarises the fleet by status. -->
    <div class="status-bar" role="group" aria-label="Site status summary">
      <span class="status-item" data-testid="status-fault">
        <span class="dot dot-fault" aria-hidden="true"></span>Fault: {{ statusCounts().fault }}
      </span>
      <span class="status-item" data-testid="status-warning">
        <span class="dot dot-warning" aria-hidden="true"></span>Warning: {{ statusCounts().warning }}
      </span>
      <span class="status-item" data-testid="status-monitored">
        <span class="dot dot-monitored" aria-hidden="true"></span>Monitored: {{ statusCounts().monitored }}
      </span>
      <span class="status-item status-total" data-testid="status-total">{{ statusCounts().total }} sites</span>
    </div>

    <div class="map-wrap">
      <div
        #mapEl
        class="geo-map"
        role="application"
        aria-label="Geographic map of network sites over a UK and Europe basemap. Each site is selectable below."
      ></div>

      <!-- Offline-safe cluster-count badges: a DOM overlay synced to each native cluster's projected
           centre (MapLibre symbol text needs a glyph stack the offline basemap deliberately omits).
           aria-hidden — the accessible site list below is the SR-reachable source of every site. -->
      <div class="cluster-badges" aria-hidden="true"></div>

      <!-- Explicit zoom / fit / reset controls (operator-driven, keyboard-reachable). MapLibre's
           NavigationControl provides zoom-in/out too; these mirror the device-graph controls and add
           fit-to-sites + reset-to-default so both canvases offer the same affordances (AC 74/75). -->
      <div class="map-controls" role="group" aria-label="Map zoom controls">
        <button type="button" data-testid="map-zoom-in" aria-label="Zoom in" (click)="mapZoomIn()">+</button>
        <button type="button" data-testid="map-zoom-out" aria-label="Zoom out" (click)="mapZoomOut()">−</button>
        <button type="button" data-testid="map-zoom-fit" aria-label="Fit to all sites" (click)="mapFit()">Fit</button>
        <button type="button" data-testid="map-zoom-reset" aria-label="Reset map to default view" (click)="mapReset()">
          Reset
        </button>
      </div>
    </div>

    @if (store.sitesLoading()) {
      <p aria-busy="true">Loading sites…</p>
    } @else if (store.sites().length) {
      <ul class="site-markers" aria-label="Network sites">
        @for (site of store.sites(); track site.siteId) {
          <li>
            <button
              type="button"
              class="card site-marker"
              data-testid="site-marker"
              (click)="select(site.siteId)"
              [attr.aria-label]="ariaFor(site)"
            >
              <span class="dot" [class]="'dot-' + siteStatusFor(site)" aria-hidden="true"></span>
              <strong>{{ site.name }}</strong>
              <span class="muted">{{ site.region }} · {{ site.latitude }}, {{ site.longitude }}</span>
            </button>
          </li>
        }
      </ul>
    } @else {
      <p class="empty-state">No sites returned.</p>
    }
  `,
  styles: [
    `
      .status-bar {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 1rem;
        padding: 0.5rem 0.75rem;
        margin-bottom: 0.6rem;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: 8px;
        font-size: 0.9rem;
      }
      .status-item {
        display: inline-flex;
        align-items: center;
        gap: 0.4rem;
      }
      .status-total {
        margin-left: auto;
        color: var(--text-muted);
      }
      .dot {
        display: inline-block;
        width: 0.7rem;
        height: 0.7rem;
        border-radius: 50%;
        flex: 0 0 auto;
      }
      .dot-fault {
        background: var(--error);
      }
      .dot-warning {
        background: var(--warn);
      }
      .dot-monitored {
        background: var(--ok);
      }
      .map-wrap {
        position: relative;
        margin-bottom: 1rem;
      }
      .geo-map {
        height: 360px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: var(--canvas-bg);
        position: relative;
        overflow: hidden;
      }
      .cluster-badges {
        position: absolute;
        inset: 0;
        pointer-events: none;
        z-index: 2;
        overflow: hidden;
      }
      .cluster-count {
        position: absolute;
        transform: translate(-50%, -50%);
        font-size: 0.72rem;
        font-weight: 700;
        color: #f8fafc;
        text-shadow: 0 0 3px #0b1220, 0 0 3px #0b1220;
        pointer-events: none;
      }
      .map-controls {
        position: absolute;
        top: 8px;
        left: 8px;
        display: flex;
        flex-direction: column;
        gap: 4px;
        z-index: 2;
      }
      .map-controls button {
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
      .map-controls button[data-testid='map-zoom-fit'],
      .map-controls button[data-testid='map-zoom-reset'] {
        width: auto;
        padding: 0 0.4rem;
        font-size: 0.7rem;
      }
      .map-controls button:hover {
        border-color: var(--accent);
      }
      .site-markers {
        list-style: none;
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
        gap: 0.6rem;
        padding: 0;
        margin: 0;
      }
      .site-marker {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        text-align: left;
        color: var(--text);
        cursor: pointer;
      }
      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
    `,
  ],
})
export class GeoSiteMapComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly store = inject(TopologyStore);
  private readonly nav = inject(NavigationService);
  private readonly route = inject(ActivatedRoute);
  readonly errors = inject(ErrorBannerService);
  private readonly theme = inject(ThemeService);
  private readonly zone = inject(NgZone);

  @ViewChild('mapEl') private mapEl?: ElementRef<HTMLDivElement>;

  /** Proves the guarded real-render path ran (asserted by the unit test even when WebGL is absent). */
  mapInitAttempted = false;

  private map: MlMap | null = null;
  private readonly mapReady = signal(false);
  private siteEffect: EffectRef;
  private themeEffect: EffectRef;

  /** GeoJSON source id holding the site points (native MapLibre clustering source). */
  private static readonly SITES_SOURCE = 'sites';
  /** Cluster collapse radius (px) — sites within this screen distance collapse into one count badge. */
  private static readonly CLUSTER_RADIUS = 60;
  /** Above this zoom the cluster SPLITS into individual unclustered site points. */
  private static readonly CLUSTER_MAX_ZOOM = 6;

  /** Fallback viewport bbox (UK/EU) when there are no sites to fit to. lon[-12,32] lat[34,62]. */
  private static readonly FALLBACK_BOUNDS: LngLatBoundsLike = [-12, 34, 32, 62];
  /**
   * Default-view zoom CEILING. fitBounds tends to over-zoom a 10-site UK/EU extent so the dense
   * London/Frankfurt cluster paints as overlapping pins (the #276 clutter). Capping the load zoom
   * keeps the continental view so the dense cluster collapses into ONE count badge on first paint;
   * the operator zooms past CLUSTER_MAX_ZOOM to split it into individual pins.
   */
  private static readonly DEFAULT_MAX_ZOOM = 4.2;

  /**
   * Operator status counts for the status bar / legend, derived purely from the current sites.
   * `monitored` is the universe minus fault/warning (the P3 hook below makes all P1 sites
   * 'monitored' until SiteDto carries a severity).
   */
  readonly statusCounts = computed<{ fault: number; warning: number; monitored: number; total: number }>(() => {
    const sites = this.store.sites();
    const counts = { fault: 0, warning: 0, monitored: 0, total: sites.length };
    for (const site of sites) {
      counts[this.siteStatusFor(site)]++;
    }
    return counts;
  });

  constructor() {
    // Push the current sites into the clustering GeoJSON SOURCE whenever the sites signal changes,
    // once the map is built. Created in the injection context (constructor); gated on mapReady so it
    // no-ops until the real map + source exist, then runs reactively for every store.sites() update.
    this.siteEffect = effect(() => {
      const sites = this.store.sites();
      if (!this.mapReady() || !this.map) {
        return;
      }
      this.updateSitesSource(sites);
    });

    // THEME effect — when the theme flips, re-paint the imperatively-built basemap + cluster/pin
    // STROKE colours (CSS-var flips don't reach the WebGL paint properties). Status pin FILLS and
    // the cluster FILL stay identical in both themes (per product decision). No-ops until the real
    // map + style are ready (jsdom / pre-load).
    this.themeEffect = effect(() => {
      this.theme.theme(); // track
      if (this.map && this.mapReady()) {
        this.applyMapTheme();
      }
    });
  }

  /** Read a CSS custom property off the document root (theme-driven palette value). */
  private cssVar(name: string): string {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  }

  /**
   * Re-paint the basemap (sea/land/borders/coast) and the cluster/pin STROKE colours from the live
   * CSS palette so the map matches the active theme. Cluster FILL + status pin FILLS are deliberately
   * left untouched (identical in both themes). No-ops when the map or its layers are not present.
   */
  private applyMapTheme(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    const paint = this.basemapPaint();
    const setPaint = (layer: string, prop: string, value: unknown): void => {
      if (map.getLayer(layer)) {
        map.setPaintProperty(layer, prop, value as never);
      }
    };
    setPaint('sea', 'background-color', paint.sea);
    setPaint('land', 'fill-color', paint.land);
    setPaint('borders', 'line-color', paint.border);
    setPaint('coast', 'line-color', paint.coast);
    setPaint('site-clusters', 'circle-stroke-color', paint.clusterStroke);
    setPaint('site-unclustered', 'circle-stroke-color', paint.canvasBg);
  }

  /** Theme-dependent basemap + stroke colours read from the CSS palette (with dark fallbacks). */
  private basemapPaint(): {
    sea: string;
    land: string;
    border: string;
    coast: string;
    clusterStroke: string;
    canvasBg: string;
  } {
    const canvasBg = this.cssVar('--canvas-bg') || '#0b1220';
    const surface = this.cssVar('--surface') || '#1e293b';
    const border = this.cssVar('--border') || '#475569';
    const accent = this.cssVar('--accent') || '#60a5fa';
    return {
      // MapLibre paint properties require LITERAL colour strings (hex/rgb/rgba/hsl) — it does NOT
      // accept CSS color-mix(); passing one aborts the entire style load and the map renders blank.
      // The land sits over the opaque sea backdrop, so the 90% "lift" is applied via a separate
      // `fill-opacity` paint property (see `land` layer) rather than baked into the colour.
      sea: canvasBg,
      land: surface,
      border,
      coast: accent,
      clusterStroke: accent,
      canvasBg,
    };
  }

  ngOnInit(): void {
    this.store.loadSites();
    const trailId = this.route.snapshot.queryParamMap.get('trailId');
    if (trailId) {
      this.store.activateTrail(trailId);
    }
  }

  async ngAfterViewInit(): Promise<void> {
    this.mapInitAttempted = true;
    if (!this.mapEl || !this.webglAvailable()) {
      // jsdom / no-WebGL environment: keep the accessible list as the rendering surface.
      return;
    }
    const maplibregl = (await import('maplibre-gl')).default;

    // Local offline basemap — NO network: no remote tiles, glyphs or sprite. The country outlines
    // come from the committed `geo/europe.json` GeoJSON asset (Natural Earth 110m, Public Domain).
    const geoUrl = new URL('geo/europe.json', document.baseURI).href;
    // NOTE: do NOT include `glyphs`/`sprite` keys at all (not even as undefined) — MapLibre's
    // style validator rejects `undefined` for them ("string expected, undefined found") and the
    // style never finishes loading. Omitting the keys keeps the basemap fully offline.
    const p = this.basemapPaint();
    const style: StyleSpecification = {
      version: 8,
      sources: {
        countries: { type: 'geojson', data: geoUrl },
      },
      layers: [
        // Sea backdrop.
        { id: 'sea', type: 'background', paint: { 'background-color': p.sea } },
        // Land fill.
        { id: 'land', type: 'fill', source: 'countries', paint: { 'fill-color': p.land, 'fill-opacity': 0.9 } },
        // Country borders.
        { id: 'borders', type: 'line', source: 'countries', paint: { 'line-color': p.border, 'line-width': 1 } },
        // Coastline accent (the outer ring of land features reads as coast against the sea).
        { id: 'coast', type: 'line', source: 'countries', paint: { 'line-color': p.coast, 'line-width': 0.5, 'line-opacity': 0.6 } },
      ],
    };

    this.map = new maplibregl.Map({
      container: this.mapEl.nativeElement,
      style,
      attributionControl: false,
    });

    // Zoom / compass controls (operator-driven map zoom + pan; scrollZoom/dragPan are on by default).
    this.map.addControl(new maplibregl.NavigationControl(), 'top-right');

    // Default view: fit to the site extent but CAP the zoom (maxZoom) so the dense UK/EU set paints
    // as tidy cluster badges on load instead of a clutter of overlapping pins (#276).
    this.map.fitBounds(this.siteExtent(), {
      padding: 40,
      animate: false,
      maxZoom: GeoSiteMapComponent.DEFAULT_MAX_ZOOM,
    });

    // Expose for the Playwright basemap + cluster assertions (real chromium only).
    (window as unknown as { __geoMap?: MlMap }).__geoMap = this.map;

    // Build the clustering source + layers + interactions once the style is loaded, then flip
    // mapReady inside the Angular zone so the site effect pushes the current sites into the source;
    // subsequent sites() changes are picked up by the same effect.
    this.map.on('load', () =>
      this.zone.run(() => {
        this.installClusterLayers();
        this.mapReady.set(true);
      }),
    );
  }

  /**
   * Install MapLibre's BUILT-IN GeoJSON clustering for the site points (native — no extra dep,
   * fully offline). One source (`cluster: true`) feeds the rendering layers:
   *   - `site-clusters`    — a CIRCLE behind the count, sized/coloured by point count,
   *   - `site-unclustered` — individual site pins (status-coloured dot) once a cluster splits.
   * The cluster point COUNT is drawn as a SYMBOL badge: MapLibre's symbol text-field needs a glyph
   * stack, and the basemap is deliberately glyph-free for offline rendering, so rather than ship a
   * glyph PBF the count is rendered as a small DOM badge synced to each cluster's projected centre
   * (clusters are few — at the default continental zoom the dense set is ONE cluster, so there is no
   * pin clutter and exactly one count badge). Click a cluster → ease to its expansion zoom; click a
   * site → drill into its graph. Status colour is per-site siteStatusFor; status is NOT colour-only —
   * the accessible site list below carries the status WORD for SR users + tests.
   */
  private installClusterLayers(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    const src = GeoSiteMapComponent.SITES_SOURCE;
    const paint = this.basemapPaint();

    map.addSource(src, {
      type: 'geojson',
      data: this.sitesGeoJson(this.store.sites()),
      cluster: true,
      clusterRadius: GeoSiteMapComponent.CLUSTER_RADIUS,
      clusterMaxZoom: GeoSiteMapComponent.CLUSTER_MAX_ZOOM,
    });

    // Cluster bubble — grows with the number of grouped sites.
    map.addLayer({
      id: 'site-clusters',
      type: 'circle',
      source: src,
      filter: ['has', 'point_count'],
      paint: {
        // Cluster FILL identical in both themes (per decision); STROKE follows the theme accent.
        'circle-color': '#1d4ed8',
        'circle-opacity': 0.85,
        'circle-stroke-color': paint.clusterStroke,
        'circle-stroke-width': 1.5,
        'circle-radius': ['step', ['get', 'point_count'], 16, 5, 20, 10, 26],
      },
    });

    // Individual (unclustered) site pins — status-coloured dot.
    map.addLayer({
      id: 'site-unclustered',
      type: 'circle',
      source: src,
      filter: ['!', ['has', 'point_count']],
      paint: {
        // Status pin FILL identical in both themes (per decision); STROKE = canvas backdrop colour.
        'circle-color': ['get', 'statusColor'],
        'circle-radius': 7,
        'circle-stroke-color': paint.canvasBg,
        'circle-stroke-width': 2,
      },
    });

    // Click a CLUSTER → ease/zoom into it (expand to where it splits into its members).
    map.on('click', 'site-clusters', (e) => {
      const feature = e.features?.[0] as MapGeoJSONFeature | undefined;
      if (!feature) {
        return;
      }
      const clusterId = feature.properties?.['cluster_id'] as number | undefined;
      const source = map.getSource(src) as GeoJSONSource | undefined;
      if (clusterId == null || !source) {
        return;
      }
      void source.getClusterExpansionZoom(clusterId).then((zoom) => {
        const geom = feature.geometry as Point;
        this.zone.run(() =>
          map.easeTo({ center: geom.coordinates as [number, number], zoom: zoom + 0.2 }),
        );
      });
    });

    // Click an individual SITE point → drill into its device graph (the existing nav).
    map.on('click', 'site-unclustered', (e) => {
      const feature = e.features?.[0] as MapGeoJSONFeature | undefined;
      const siteId = feature?.properties?.['siteId'] as string | undefined;
      if (siteId) {
        this.zone.run(() => this.select(siteId));
      }
    });

    // Pointer affordance over interactive layers.
    for (const layer of ['site-clusters', 'site-unclustered']) {
      map.on('mouseenter', layer, () => {
        map.getCanvas().style.cursor = 'pointer';
      });
      map.on('mouseleave', layer, () => {
        map.getCanvas().style.cursor = '';
      });
    }

    // Sync the DOM cluster-count badges (offline-safe substitute for a glyph symbol layer) on
    // every paint so each cluster shows its point count at its projected centre.
    map.on('render', () => this.syncClusterCountBadges());
  }

  /**
   * Render a small DOM count badge over each rendered cluster (offline-safe — no glyph PBF needed).
   * Cluster features are queried from the rendered cluster layer and each is positioned by
   * projecting its lng/lat to the screen. At the default continental zoom the dense UK/EU set is a
   * single cluster, so this is one tidy badge, not a clutter of pins.
   */
  private syncClusterCountBadges(): void {
    const map = this.map;
    const host = this.clusterBadgeHost();
    if (!map || !host) {
      return;
    }
    const features = map.queryRenderedFeatures(undefined, { layers: ['site-clusters'] });
    host.replaceChildren();
    for (const f of features) {
      const count = (f.properties?.['point_count_abbreviated'] ?? f.properties?.['point_count']) as
        | string
        | number
        | undefined;
      const geom = f.geometry as Point;
      if (count == null || geom?.type !== 'Point') {
        continue;
      }
      const p = map.project(geom.coordinates as [number, number]);
      const badge = document.createElement('span');
      badge.className = 'cluster-count';
      badge.dataset['testid'] = 'cluster-count';
      badge.setAttribute('aria-hidden', 'true');
      badge.textContent = String(count);
      badge.style.left = `${p.x}px`;
      badge.style.top = `${p.y}px`;
      host.appendChild(badge);
    }
  }

  /** The absolutely-positioned overlay host for the DOM cluster-count badges (inside .map-wrap). */
  private clusterBadgeHost(): HTMLElement | null {
    return this.mapEl?.nativeElement?.parentElement?.querySelector<HTMLElement>('.cluster-badges') ?? null;
  }

  /** Build the site FeatureCollection (status colour + ids carried as feature properties). */
  private sitesGeoJson(sites: readonly SiteDto[]): FeatureCollection<Point> {
    return {
      type: 'FeatureCollection',
      features: sites.map((site) => ({
        type: 'Feature',
        geometry: { type: 'Point', coordinates: [site.longitude, site.latitude] },
        properties: {
          siteId: site.siteId,
          name: site.name,
          region: site.region,
          status: this.siteStatusFor(site),
          statusColor: this.statusColorFor(site),
        },
      })),
    };
  }

  /** Push the current sites into the clustering source (the reactive effect path). */
  private updateSitesSource(sites: readonly SiteDto[]): void {
    const source = this.map?.getSource(GeoSiteMapComponent.SITES_SOURCE) as GeoJSONSource | undefined;
    source?.setData(this.sitesGeoJson(sites));
  }

  /** Status → pin fill colour (mirrors the accessible-list dot colours). */
  private statusColorFor(site: SiteDto): string {
    switch (this.siteStatusFor(site)) {
      case 'fault':
        return '#ef4444';
      case 'warning':
        return '#f59e0b';
      default:
        return '#22c55e';
    }
  }

  /** Bounding box [W,S,E,N] of the current sites, or the UK/EU fallback when there are none. */
  private siteExtent(): LngLatBoundsLike {
    const sites = this.store.sites();
    if (!sites.length) {
      return GeoSiteMapComponent.FALLBACK_BOUNDS;
    }
    let minLon = Infinity;
    let minLat = Infinity;
    let maxLon = -Infinity;
    let maxLat = -Infinity;
    for (const s of sites) {
      minLon = Math.min(minLon, s.longitude);
      maxLon = Math.max(maxLon, s.longitude);
      minLat = Math.min(minLat, s.latitude);
      maxLat = Math.max(maxLat, s.latitude);
    }
    // Pad a degree so single-site / tightly-clustered extents are not a zero-area box.
    return [minLon - 1, minLat - 1, maxLon + 1, maxLat + 1];
  }

  /**
   * Operator status of a site (P3 hook). SiteDto carries no severity in the P1 contract, so every
   * P1 site is 'monitored' (green). When the Topology contract adds a per-site severity, branch
   * here to return 'fault'/'warning' — the status-bar counts, legend and pin colour all follow.
   */
  siteStatusFor(site: SiteDto): SiteStatus {
    void site;
    return 'monitored';
  }

  /** Accessible label including the operator status word (not colour-only — WCAG 1.4.1). */
  ariaFor(site: SiteDto): string {
    const status = this.siteStatusFor(site);
    const word = status.charAt(0).toUpperCase() + status.slice(1);
    return `Site ${site.name} in ${site.region}. Status: ${word}. Open device graph.`;
  }

  /** True when the browser can produce a WebGL(2) context (jsdom returns null → false). */
  private webglAvailable(): boolean {
    try {
      const canvas = document.createElement('canvas');
      const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
      return !!gl;
    } catch {
      return false;
    }
  }

  // ── Map zoom / fit / reset controls ───────────────────────────────────────────────────────────
  /** Zoom the map in one step (no-op until the real map exists, e.g. in jsdom unit tests). */
  mapZoomIn(): void {
    this.map?.zoomIn();
  }
  /** Zoom the map out one step. */
  mapZoomOut(): void {
    this.map?.zoomOut();
  }
  /** Fit the viewport to the extent of all current sites (the same capped bounds used on load). */
  mapFit(): void {
    this.map?.fitBounds(this.siteExtent(), {
      padding: 40,
      animate: false,
      maxZoom: GeoSiteMapComponent.DEFAULT_MAX_ZOOM,
    });
  }
  /** Reset zoom + centre to the initial default (re-fit to the site extent / UK-EU fallback). */
  mapReset(): void {
    this.map?.fitBounds(this.siteExtent(), {
      padding: 40,
      animate: false,
      maxZoom: GeoSiteMapComponent.DEFAULT_MAX_ZOOM,
    });
  }

  /**
   * TEST-ONLY hook: inject a stubbed MapLibre map so the zoom/fit/reset handlers (AC 74) can be
   * exercised under jsdom where the real WebGL map is never constructed. Production code never calls
   * this — the real map is built in ngAfterViewInit. Kept narrow so the approved render path is
   * unchanged.
   */
  setMapForTest(map: MlMap): void {
    this.map = map;
  }

  /**
   * TEST-ONLY hook: drive the native-clustering layer install against a stubbed MapLibre map under
   * jsdom (no WebGL). Asserts the source is created with `cluster: true` and the cluster +
   * unclustered layers + click handlers are wired. Production builds the real source on `map.load`.
   */
  installClusterLayersForTest(map: MlMap): void {
    this.map = map;
    this.installClusterLayers();
  }

  /** TEST-ONLY: the GeoJSON FeatureCollection pushed into the clustering source (status props). */
  sitesGeoJsonForTest(): FeatureCollection<Point> {
    return this.sitesGeoJson(this.store.sites());
  }

  select(siteId: string): void {
    this.nav.toSiteGraph(siteId);
  }

  ngOnDestroy(): void {
    this.siteEffect.destroy();
    this.themeEffect.destroy();
    this.map?.remove();
    this.map = null;
  }
}
