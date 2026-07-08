import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  EffectRef,
  EventEmitter,
  HostBinding,
  Input,
  NgZone,
  Output,
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
    @if (showHeading) {
      <h1>Topology &amp; trails — sites</h1>
    }
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
        [class.geo-map-tall]="!showHeading && !embedded"
        [class.geo-map-fill]="embedded"
        role="application"
        aria-label="Geographic map of network sites over a UK and Europe basemap. Each site is selectable below."
      ></div>

      <!-- Offline-safe cluster-count badges: a DOM overlay synced to each native cluster's projected
           centre (MapLibre symbol text needs a glyph stack the offline basemap deliberately omits).
           aria-hidden — the accessible site list below is the SR-reachable source of every site. -->
      <div class="cluster-badges" aria-hidden="true"></div>

      <!-- Offline-safe CITY labels: same DOM-overlay pattern as the cluster badges (no glyph stack
           shipped). City name text is synced to each city point's projected screen position.
           aria-hidden — decorative basemap context; the accessible site list carries site names. -->
      <div class="city-labels" aria-hidden="true"></div>

      <!-- FIT / RESET controls only (bottom-right, clear of MapLibre's top-right NavigationControl).
           The redundant custom LEFT zoom-in/out buttons were removed — zoom lives on MapLibre's own
           NavigationControl (top-right). Fit-to-sites + reset-to-default are NOT pure zoom (they
           re-frame the whole fleet), so they are kept as a small right-aligned group. -->
      <div class="map-controls" role="group" aria-label="Map view controls">
        <button type="button" data-testid="map-zoom-fit" aria-label="Fit to all sites" (click)="mapFit()">Fit</button>
        <button type="button" data-testid="map-zoom-reset" aria-label="Reset map to default view" (click)="mapReset()">
          Reset
        </button>
      </div>
    </div>

    <!-- ACCESSIBLE, COMPACT site list (Parts 1-2). The old bottom CARD GRID was removed and its
         vertical space given to a taller map, so the visible UI is dominated by the map; this is a
         slim single-line chip row (not cards) that stays as the WCAG/keyboard drill-in path AND the
         selection source of truth for tests/e2e — every site is a selectable chip carrying
         data-testid=site-marker and its name/region (so keyboard users, screen readers, and hasText
         selectors reach every site at any map zoom). Clicking a green map pin is the primary visible
         drill-in; this compact row mirrors it. It is genuinely rendered (not 1px-hidden) so it is
         keyboard-focusable and reliably click-targetable. -->
    @if (store.sitesLoading()) {
      <p aria-busy="true">Loading sites…</p>
    } @else if (store.sites().length) {
      <nav class="site-chips" aria-label="Network sites — select a site to open its device graph">
        <span class="site-chips-lead" aria-hidden="true">Sites:</span>
        @for (site of store.sites(); track site.siteId) {
          <button
            type="button"
            class="site-chip"
            data-testid="site-marker"
            (click)="select(site.siteId)"
            [attr.aria-label]="ariaFor(site)"
          >
            <span class="dot dot-monitored" aria-hidden="true"></span>
            {{ site.name }} — {{ site.region }}
          </button>
        }
      </nav>
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
      /* The bottom site-card grid was removed (Parts 1-2); the map reclaims that vertical space and is
         now the sole visible surface, so give it a generous default height. */
      .geo-map {
        height: min(70vh, 720px);
        min-height: 480px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: var(--canvas-bg);
        position: relative;
        overflow: hidden;
      }
      /* Embedded on the dashboard (no heading) → give the map more vertical presence. */
      .geo-map-tall {
        height: min(68vh, 720px);
        min-height: 480px;
      }
      /* Embedded in the dashboard's SHARED topology panel: the map FILLS the space the panel gives it
         (the host is a flex column; the status bar sits on top, the accessible site list scrolls
         below), so the panel height is controlled by the host — identical to the site-graph panel and
         to itself before/after a drill-in, so swapping never shifts the page. */
      :host {
        display: block;
      }
      :host(.embedded-host) {
        height: 100%;
        display: flex;
        flex-direction: column;
      }
      :host(.embedded-host) .map-wrap {
        flex: 1 1 auto;
        display: flex;
        flex-direction: column;
        min-height: 0;
        margin-bottom: 0.6rem;
      }
      .geo-map-fill {
        flex: 1 1 auto;
        min-height: 240px;
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
      .city-labels {
        position: absolute;
        inset: 0;
        pointer-events: none;
        z-index: 1;
        overflow: hidden;
      }
      .city-label {
        position: absolute;
        transform: translate(6px, -50%);
        font-size: 0.68rem;
        font-weight: 600;
        letter-spacing: 0.02em;
        color: var(--text-muted);
        text-shadow:
          0 0 2px var(--canvas-bg),
          0 0 2px var(--canvas-bg),
          0 0 4px var(--canvas-bg);
        pointer-events: none;
        white-space: nowrap;
        opacity: 0.9;
      }
      /* FIT / RESET group — pinned bottom-right so it clears MapLibre's top-right NavigationControl
         (zoom) and no longer clutters the LEFT of the map (the redundant left zoom was removed). */
      .map-controls {
        position: absolute;
        bottom: 8px;
        right: 8px;
        display: flex;
        flex-direction: row;
        gap: 4px;
        z-index: 2;
      }
      .map-controls button {
        border: 1px solid var(--border);
        background: var(--surface);
        color: var(--text);
        border-radius: 6px;
        cursor: pointer;
        line-height: 1;
        padding: 0 0.5rem;
        height: 1.9rem;
        font-size: 0.72rem;
      }
      .map-controls button:hover {
        border-color: var(--accent);
      }
      .muted {
        color: var(--text-muted);
        font-size: 0.85rem;
      }
      /* Compact site chip row — replaces the removed bottom card grid. A single wrapping/scrolling
         line of selectable chips so the MAP stays the dominant surface while every site remains
         keyboard-reachable + click-targetable (a11y + tests). */
      .site-chips {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.4rem;
        margin-top: 0.5rem;
      }
      .site-chips-lead {
        color: var(--text-muted);
        font-size: 0.8rem;
        font-weight: 600;
        margin-right: 0.15rem;
      }
      .site-chip {
        display: inline-flex;
        align-items: center;
        gap: 0.35rem;
        padding: 0.2rem 0.6rem;
        border: 1px solid var(--border);
        border-radius: 999px;
        background: var(--surface-2);
        color: var(--text);
        font-size: 0.82rem;
        cursor: pointer;
        white-space: nowrap;
      }
      .site-chip:hover,
      .site-chip:focus-visible {
        border-color: var(--accent);
        color: var(--accent);
      }
      /* Embedded on the dashboard: bound the chip row height so it never crowds out the map/graph. */
      :host(.embedded-host) .site-chips {
        flex: 0 0 auto;
        max-height: 4.5rem;
        overflow-y: auto;
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

  /**
   * Whether to render the standalone `<h1>Topology &amp; trails — sites</h1>` heading. Default ON for
   * the `/topology` route; the dashboard sets it OFF (it supplies its own section header) and the
   * map is given more height (`.geo-map-tall`). A MapLibre `resize()` is issued after embed so the
   * canvas fills the taller container it becomes visible in.
   */
  @Input() showHeading = true;

  /**
   * True when embedded in the dashboard's SHARED topology panel. The host then becomes a flex column
   * filling 100% of the panel (map grows, status bar on top, site list scrolls below) so the map
   * panel has the EXACT SAME box as the in-place site graph — swapping between them causes zero
   * vertical shift. Standalone (`/topology`) it is false and the component keeps its own vh height.
   */
  @Input() embedded = false;

  /** Reflects `embedded` onto the host so the `:host(.embedded-host)` flex-fill rules apply. */
  @HostBinding('class.embedded-host') get embeddedHost(): boolean {
    return this.embedded;
  }

  /**
   * Emits the siteId when the operator drills into a site (pin click or accessible site-list click).
   * The dashboard listens and swaps the map panel for the in-place site graph (no separate
   * `/topology/:siteId` page). When NO listener is bound (`observed === false`) the component falls
   * back to legacy router navigation so it stays usable standalone.
   */
  @Output() siteSelected = new EventEmitter<string>();

  /** Proves the guarded real-render path ran (asserted by the unit test even when WebGL is absent). */
  mapInitAttempted = false;

  private map: MlMap | null = null;
  private readonly mapReady = signal(false);
  private siteEffect: EffectRef;
  private themeEffect: EffectRef;

  /**
   * Uniform site-pin GREEN (Part 1). Every independent site renders as this same green circle — the
   * status→green/amber/red variation was removed; a site is just a green dot. Matches the `--ok`
   * green family used elsewhere in the app.
   */
  private static readonly SITE_GREEN = '#22c55e';
  /** Cluster bubble GREEN — same family as the pin, a shade darker so the aggregate still reads. */
  private static readonly SITE_CLUSTER_GREEN = '#15803d';

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
    setPaint('graticule', 'line-color', paint.graticule);
    setPaint('land', 'fill-color', paint.land);
    setPaint('borders', 'line-color', paint.border);
    setPaint('coast', 'line-color', paint.coast);
    setPaint('cities', 'circle-color', paint.city);
    setPaint('cities', 'circle-stroke-color', paint.cityHalo);
    // Keep BOTH the cluster bubble and the site pins ringed with a fixed dark colour in both themes so
    // the uniform-green sites (Part 1) stay high-contrast on the natural green LAND / blue SEA basemap
    // (a canvas/accent-coloured ring would vanish on the green land).
    setPaint('site-clusters', 'circle-stroke-color', '#0b1220');
    setPaint('site-unclustered', 'circle-stroke-color', '#0b1220');
  }

  /** Theme-dependent basemap + stroke colours read from the CSS palette (with dark fallbacks). */
  private basemapPaint(): {
    sea: string;
    graticule: string;
    land: string;
    border: string;
    coast: string;
    city: string;
    cityHalo: string;
    clusterStroke: string;
    canvasBg: string;
  } {
    const canvasBg = this.cssVar('--canvas-bg') || '#0b1220';
    const accent = this.cssVar('--accent') || '#60a5fa';
    // NATURAL-COLOUR MAP TOKENS (dedicated --map-* palette, theme-aware). These give the offline
    // basemap real geographic colours (blue sea / green land / darker borders) instead of the pale
    // app surface/canvas tokens, so it reads as a genuine map in both light and dark themes. Solid
    // hex/rgb literals only — MapLibre paint rejects color-mix() and aborts the style load.
    const water = this.cssVar('--map-water') || '#a9d3ec';
    const land = this.cssVar('--map-land') || '#cfe3b0';
    const mapBorder = this.cssVar('--map-border') || '#6b8f4e';
    const coast = this.cssVar('--map-coast') || '#4a7fa5';
    const city = this.cssVar('--map-city') || '#334155';
    const graticule = this.cssVar('--map-graticule') || '#7fa8c4';
    return {
      graticule,
      // City marker fill from the dedicated token; halo = the sea backdrop so the dot stays legible.
      city,
      cityHalo: water,
      // Land sits over the opaque sea backdrop; the land `fill-opacity` (see `land` layer) keeps the
      // graticule faintly visible through it.
      sea: water,
      land,
      border: mapBorder,
      coast,
      // Cluster/pin STROKE follows the theme accent (kept separate from the natural land/sea fills).
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

    // Local offline basemap — NO network: no remote tiles, glyphs or sprite. All geodata is served
    // from committed static assets:
    //   - `geo/europe.json`    — Natural Earth 110m admin-0 country polygons (Public Domain) → land
    //                            fill + country borders + coastline.
    //   - `geo/graticule.json` — a lat/long grid (generated from the viewport bbox) for depth/scale.
    //   - `geo/cities.json`    — major EU/UK PoP-region cities (public-domain centroids) → city dots
    //                            (labels are DOM overlays, see syncCityLabels — offline-safe).
    const geoUrl = new URL('geo/europe.json', document.baseURI).href;
    const gratUrl = new URL('geo/graticule.json', document.baseURI).href;
    const citiesUrl = new URL('geo/cities.json', document.baseURI).href;
    // NOTE: do NOT include `glyphs`/`sprite` keys at all (not even as undefined) — MapLibre's
    // style validator rejects `undefined` for them ("string expected, undefined found") and the
    // style never finishes loading. Omitting the keys keeps the basemap fully offline.
    const p = this.basemapPaint();
    const style: StyleSpecification = {
      version: 8,
      sources: {
        countries: { type: 'geojson', data: geoUrl },
        graticule: { type: 'geojson', data: gratUrl },
        cities: { type: 'geojson', data: citiesUrl },
      },
      layers: [
        // Sea backdrop.
        { id: 'sea', type: 'background', paint: { 'background-color': p.sea } },
        // Graticule (lat/long grid) — drawn under the land so it reads only over water for depth.
        {
          id: 'graticule',
          type: 'line',
          source: 'graticule',
          paint: { 'line-color': p.graticule, 'line-width': 0.5, 'line-opacity': 0.35 },
        },
        // Land fill.
        { id: 'land', type: 'fill', source: 'countries', paint: { 'fill-color': p.land, 'fill-opacity': 0.9 } },
        // Country borders.
        { id: 'borders', type: 'line', source: 'countries', paint: { 'line-color': p.border, 'line-width': 1 } },
        // Coastline accent (the outer ring of land features reads as coast against the sea).
        { id: 'coast', type: 'line', source: 'countries', paint: { 'line-color': p.coast, 'line-width': 0.5, 'line-opacity': 0.6 } },
        // Major-city markers (dots). City NAMES are rendered as DOM overlays (offline-safe — no glyph
        // stack shipped); the dot itself is a small filled circle with a soft halo.
        {
          id: 'cities',
          type: 'circle',
          source: 'cities',
          paint: {
            'circle-color': p.city,
            'circle-radius': 2.6,
            'circle-stroke-color': p.cityHalo,
            'circle-stroke-width': 1.2,
            'circle-opacity': 0.9,
          },
        },
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
        // Keep the DOM city labels synced to the moving/zooming basemap (offline-safe — same overlay
        // pattern as the cluster-count badges).
        this.map?.on('render', () => this.syncCityLabels());
        this.mapReady.set(true);
        // The map may have been built into a container that only just became visible/sized (e.g.
        // embedded on the dashboard). Force a resize so the WebGL canvas fills its box, then re-fit
        // to the site extent in the NOW-correct container size so the fit + city labels are settled.
        this.map?.resize();
        this.mapFit();
        this.syncCityLabels();
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
        // Cluster FILL is the SAME GREEN family as the individual site pins (Part 1 — sites render
        // uniformly green; the cluster is just a grouped green bubble, not blue). A darker green than
        // the pin fill so the cluster still reads as an aggregate. Dark STROKE ring for contrast on
        // the natural land/sea basemap.
        'circle-color': GeoSiteMapComponent.SITE_CLUSTER_GREEN,
        'circle-opacity': 0.9,
        'circle-stroke-color': '#0b1220',
        'circle-stroke-width': 1.5,
        'circle-radius': ['step', ['get', 'point_count'], 16, 5, 20, 10, 26],
      },
    });

    // Individual (unclustered) site pins — UNIFORM GREEN dot (Part 1). Every independent site is the
    // same green circle regardless of status; a dark ring keeps it high-contrast on the green land.
    map.addLayer({
      id: 'site-unclustered',
      type: 'circle',
      source: src,
      filter: ['!', ['has', 'point_count']],
      paint: {
        'circle-color': GeoSiteMapComponent.SITE_GREEN,
        'circle-radius': 7,
        'circle-stroke-color': '#0b1220',
        'circle-stroke-width': 2.5,
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

    // Sync the DOM cluster-count badges ONLY on SETTLED events ('idle'/'moveend', plus once now for
    // the initial paint) — NEVER on the raw 'render' frame. A 'render'-time sync re-projects each
    // cluster centre mid-animation/pan/zoom, where `map.project()` can transiently return near-(0,0)
    // screen coordinates; because 'render' fires rapidly the badges would get positioned — and stuck
    // — at the canvas TOP-LEFT corner (the "222"/"43" corner-stack bug). Waiting for the map to come
    // to rest means every badge is placed at its FINAL settled projection, over its real cluster.
    const syncOverlays = (): void => {
      this.syncClusterCountBadges();
      this.syncCityLabels();
    };
    map.on('idle', syncOverlays);
    map.on('moveend', syncOverlays);
    // Initial placement for the first (already-settled) paint.
    this.syncClusterCountBadges();
  }

  /**
   * Render a small DOM count badge over each rendered cluster (offline-safe — no glyph PBF needed).
   * Cluster features are queried from the rendered cluster layer and each is positioned by
   * projecting its lng/lat to the screen. At the default continental zoom the dense UK/EU set is a
   * single cluster, so this is one tidy badge, not a clutter of pins.
   *
   * Called only on SETTLED map events (idle/moveend/initial paint), never per 'render' frame, so
   * `map.project()` returns the final resting screen position. As belt-and-braces defence against
   * a bad/transient projection, a badge whose projected point falls OUTSIDE the visible canvas
   * (negative or beyond width/height) is SKIPPED rather than clamped to the top-left corner — this
   * is exactly the guard that prevents the count badges stacking at (0,0) on the sea.
   */
  private syncClusterCountBadges(): void {
    const map = this.map;
    const host = this.clusterBadgeHost();
    if (!map || !host) {
      return;
    }
    const features = map.queryRenderedFeatures(undefined, { layers: ['site-clusters'] });
    host.replaceChildren();
    const canvas = map.getCanvas();
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
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
      // Skip a cluster whose projected centre is off-canvas (negative or past the edges). A valid
      // canvas may report width/height 0 in some layout/test states — only apply the upper-bound
      // check when the canvas has a real size so a real on-screen badge is never wrongly dropped.
      const offCanvas =
        p.x < 0 || p.y < 0 || (w > 0 && p.x > w) || (h > 0 && p.y > h) || !Number.isFinite(p.x) || !Number.isFinite(p.y);
      if (offCanvas) {
        continue;
      }
      const badge = document.createElement('span');
      badge.className = 'cluster-count';
      badge.dataset['testid'] = 'cluster-count';
      badge.setAttribute('aria-hidden', 'true');
      badge.textContent = String(count);
      // Positioning + centering are set INLINE (not via the component's scoped .cluster-count rule):
      // this span is created imperatively, so it lacks the component's style-encapsulation attribute
      // and the scoped `position:absolute`/`transform` would NOT apply — the badge would collapse to
      // `position:static` and ignore its left/top, stacking at the host's top-left corner. Mirrors the
      // city-label pattern below. Cosmetic props are inlined too so the imperative span stays legible.
      badge.style.position = 'absolute';
      badge.style.transform = 'translate(-50%, -50%)';
      badge.style.left = `${p.x}px`;
      badge.style.top = `${p.y}px`;
      badge.style.fontSize = '0.72rem';
      badge.style.fontWeight = '700';
      badge.style.color = '#f8fafc';
      badge.style.textShadow = '0 0 3px #0b1220, 0 0 3px #0b1220';
      badge.style.pointerEvents = 'none';
      host.appendChild(badge);
    }
  }

  /** The absolutely-positioned overlay host for the DOM cluster-count badges (inside .map-wrap). */
  private clusterBadgeHost(): HTMLElement | null {
    return this.mapEl?.nativeElement?.parentElement?.querySelector<HTMLElement>('.cluster-badges') ?? null;
  }

  /** The absolutely-positioned overlay host for the DOM city-name labels (inside .map-wrap). */
  private cityLabelHost(): HTMLElement | null {
    return this.mapEl?.nativeElement?.parentElement?.querySelector<HTMLElement>('.city-labels') ?? null;
  }

  /**
   * Render each major-city NAME as a DOM label positioned at its projected screen point (offline-safe
   * — MapLibre symbol text would need a bundled glyph stack the basemap omits, so we reuse the same
   * DOM-overlay technique as the cluster-count badges). Cities are queried from the rendered `cities`
   * circle layer so labels track the exact dot positions as the map pans/zooms.
   */
  private syncCityLabels(): void {
    const map = this.map;
    const host = this.cityLabelHost();
    if (!map || !host || !map.getLayer('cities')) {
      return;
    }
    const features = map.queryRenderedFeatures(undefined, { layers: ['cities'] });
    host.replaceChildren();
    const seen = new Set<string>();
    const canvas = map.getCanvas();
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    for (const f of features) {
      const name = f.properties?.['name'] as string | undefined;
      const geom = f.geometry as Point;
      if (!name || seen.has(name) || geom?.type !== 'Point') {
        continue;
      }
      seen.add(name);
      const pt = map.project(geom.coordinates as [number, number]);
      // Only label cities whose dot is actually inside the visible map viewport — otherwise
      // off-screen cities (above/beside the fitted extent) clamp to the edge and pile up.
      if (pt.x < 0 || pt.y < 0 || pt.x > w || pt.y > h) {
        continue;
      }
      const label = document.createElement('span');
      label.className = 'city-label';
      label.dataset['testid'] = 'city-label';
      label.setAttribute('aria-hidden', 'true');
      label.textContent = name;
      // Positioning is set INLINE (not via the component's scoped .city-label rule): these spans are
      // created imperatively, so they lack the component's style-encapsulation attribute and the
      // scoped `position:absolute` would not apply — the same reason the styling is kept minimal here.
      label.style.position = 'absolute';
      label.style.left = `${pt.x}px`;
      label.style.top = `${pt.y}px`;
      host.appendChild(label);
    }
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

  /**
   * Pin fill colour. Part 1: sites render UNIFORMLY GREEN — every independent site is the same green
   * circle regardless of status, so this always returns the site green (kept as a method so the
   * GeoJSON `statusColor` feature property stays populated for any downstream/theme use).
   */
  private statusColorFor(_site: SiteDto): string {
    return GeoSiteMapComponent.SITE_GREEN;
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

  // ── Map fit / reset controls ──────────────────────────────────────────────────────────────────
  // NOTE: the custom zoom-in/out handlers were removed with the redundant left zoom buttons — zoom
  // is provided by MapLibre's own NavigationControl (top-right). Fit/Reset re-frame the whole fleet.
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

  /**
   * TEST-ONLY: drive the cluster-count badge sync against a stubbed map under jsdom and return the
   * rendered `.cluster-count` badge spans. Lets the badge-positioning + off-canvas-guard behaviour
   * be asserted (badges land at their projected cluster centre, and a badge whose projection is
   * off-canvas/negative is skipped rather than stacked at the top-left corner).
   */
  syncClusterCountBadgesForTest(map: MlMap): readonly HTMLElement[] {
    this.map = map;
    this.syncClusterCountBadges();
    const host = this.clusterBadgeHost();
    return host ? Array.from(host.querySelectorAll<HTMLElement>('.cluster-count')) : [];
  }

  select(siteId: string): void {
    // Prefer the in-place dashboard swap: emit the siteId to the host (the dashboard renders the
    // site graph in the same panel with a Close button). If nothing is bound to the output (used
    // standalone), fall back to the legacy route navigation so the component still works alone.
    if (this.siteSelected.observed) {
      this.siteSelected.emit(siteId);
    } else {
      this.nav.toSiteGraph(siteId);
    }
  }

  ngOnDestroy(): void {
    this.siteEffect.destroy();
    this.themeEffect.destroy();
    this.map?.remove();
    this.map = null;
  }
}
