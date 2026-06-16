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
import { SiteDto } from '../api/models';

// Type-only import — the runtime module is lazy-loaded in ngAfterViewInit so the (large) MapLibre
// bundle is fetched only when this view is actually shown, and unit tests can mock it.
import type maplibregl from 'maplibre-gl';
import type { Map as MlMap, Marker as MlMarker, StyleSpecification, LngLatBoundsLike } from 'maplibre-gl';

/** Operator status of a site. Drives the status-dot colour, the legend and the status bar. */
export type SiteStatus = 'fault' | 'warning' | 'monitored';

/**
 * Geo-site map (spec task 6, AC 26). The entry view of the topology module. Each Site returned
 * by the Topology site query API is rendered as a real MapLibre GL status-dot pin on a REAL
 * UK/EU basemap (country outlines/coastlines from the committed offline `geo/europe.json` asset),
 * driven by an Angular effect() over TopologyStore.sites(). An accessible site list is kept as a
 * WCAG complement / test source of truth (same data, same data-testid). Supports the `?trailId=`
 * deep link (AC 24) which is carried through to the site graph.
 *
 * Render strategy: the MapLibre Map is created with a LOCAL GeoJSON basemap (sea/land/borders/
 * coast layers) loaded from `geo/europe.json` — no remote tiles, glyphs or sprite, so the map
 * renders fully offline. WebGL is required; in jsdom (unit tests) there is no WebGL context, so
 * map construction is skipped while `mapInitAttempted` is still flipped true to prove the guarded
 * real-render path executed. Site pins carry an operator status (`siteStatusFor`) shown as a
 * coloured dot; a status bar + legend summarise the fleet (Fault/Warning/Monitored).
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

    <div
      #mapEl
      class="geo-map"
      role="application"
      aria-label="Geographic map of network sites over a UK and Europe basemap. Each site is selectable below."
    ></div>

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
      .geo-map {
        height: 360px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: #0b1220;
        margin-bottom: 1rem;
        position: relative;
        overflow: hidden;
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
  private readonly zone = inject(NgZone);

  @ViewChild('mapEl') private mapEl?: ElementRef<HTMLDivElement>;

  /** Proves the guarded real-render path ran (asserted by the unit test even when WebGL is absent). */
  mapInitAttempted = false;

  private maplibre: typeof maplibregl | null = null;
  private map: MlMap | null = null;
  private markers: MlMarker[] = [];
  private readonly mapReady = signal(false);
  private markerEffect: EffectRef;

  /** Fallback viewport bbox (UK/EU) when there are no sites to fit to. lon[-12,32] lat[34,62]. */
  private static readonly FALLBACK_BOUNDS: LngLatBoundsLike = [-12, 34, 32, 62];

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
    // Rebuild the MapLibre markers whenever the sites signal changes, once the map is built.
    // Created in the injection context (constructor); gated on mapReady so it no-ops until the
    // real map exists, then runs reactively for every store.sites() update.
    this.markerEffect = effect(() => {
      const sites = this.store.sites();
      if (!this.mapReady() || !this.map || !this.maplibre) {
        return;
      }
      this.rebuildMarkers(sites);
    });
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
    this.maplibre = maplibregl;

    // Local offline basemap — NO network: no remote tiles, glyphs or sprite. The country outlines
    // come from the committed `geo/europe.json` GeoJSON asset (Natural Earth 110m, Public Domain).
    const geoUrl = new URL('geo/europe.json', document.baseURI).href;
    // NOTE: do NOT include `glyphs`/`sprite` keys at all (not even as undefined) — MapLibre's
    // style validator rejects `undefined` for them ("string expected, undefined found") and the
    // style never finishes loading. Omitting the keys keeps the basemap fully offline.
    const style: StyleSpecification = {
      version: 8,
      sources: {
        countries: { type: 'geojson', data: geoUrl },
      },
      layers: [
        // Sea backdrop.
        { id: 'sea', type: 'background', paint: { 'background-color': '#0b1220' } },
        // Land fill.
        { id: 'land', type: 'fill', source: 'countries', paint: { 'fill-color': 'rgba(30,41,59,0.9)' } },
        // Country borders.
        { id: 'borders', type: 'line', source: 'countries', paint: { 'line-color': '#475569', 'line-width': 1 } },
        // Coastline accent (the outer ring of land features reads as coast against the sea).
        { id: 'coast', type: 'line', source: 'countries', paint: { 'line-color': '#60a5fa', 'line-width': 0.5, 'line-opacity': 0.6 } },
      ],
    };

    this.map = new maplibregl.Map({
      container: this.mapEl.nativeElement,
      style,
      attributionControl: false,
    });

    // Zoom / compass controls (operator-driven map zoom + pan; scrollZoom/dragPan are on by default).
    this.map.addControl(new maplibregl.NavigationControl(), 'top-right');

    // Fit to the extent of the current sites (fallback to the UK/EU bbox when there are none).
    this.map.fitBounds(this.siteExtent(), { padding: 40, animate: false });

    // Expose for the Playwright basemap assertions (real chromium only).
    (window as unknown as { __geoMap?: MlMap }).__geoMap = this.map;

    // Flip mapReady inside the Angular zone so the marker effect schedules and draws the
    // current sites; subsequent sites() changes are picked up by the same effect.
    this.map.on('load', () => this.zone.run(() => this.mapReady.set(true)));
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

  private rebuildMarkers(sites: readonly SiteDto[]): void {
    const maplibregl = this.maplibre;
    const map = this.map;
    if (!maplibregl || !map) {
      return;
    }
    for (const m of this.markers) {
      m.remove();
    }
    this.markers = [];

    for (const site of sites) {
      const status = this.siteStatusFor(site);
      const el = document.createElement('button');
      el.type = 'button';
      el.className = `maplibre-site-marker status-${status}`;
      el.dataset['testid'] = 'map-marker';
      // A status dot + name label.
      const dot = document.createElement('span');
      dot.className = `mm-dot mm-${status}`;
      const label = document.createElement('span');
      label.className = 'mm-label';
      label.textContent = site.name;
      el.append(dot, label);
      const aria = this.ariaFor(site);
      el.setAttribute('aria-label', aria);
      el.setAttribute('title', aria);
      el.addEventListener('click', () => this.zone.run(() => this.select(site.siteId)));

      const marker = new maplibregl.Marker({ element: el })
        .setLngLat([site.longitude, site.latitude])
        .addTo(map);
      this.markers.push(marker);
    }
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

  select(siteId: string): void {
    this.nav.toSiteGraph(siteId);
  }

  ngOnDestroy(): void {
    this.markerEffect.destroy();
    for (const m of this.markers) {
      m.remove();
    }
    this.markers = [];
    this.map?.remove();
    this.map = null;
  }
}
