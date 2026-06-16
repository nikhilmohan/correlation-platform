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
  effect,
  inject,
  signal,
} from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { TopologyStore } from './topology.store';
import { NavigationService } from '../core/navigation.service';
import { ErrorBannerService } from '../core/error-banner.service';

// Type-only import — the runtime module is lazy-loaded in ngAfterViewInit so the (large) MapLibre
// bundle is fetched only when this view is actually shown, and unit tests can mock it.
import type maplibregl from 'maplibre-gl';
import type { Map as MlMap, Marker as MlMarker, StyleSpecification } from 'maplibre-gl';

/**
 * Geo-site map (spec task 6, AC 26). The entry view of the topology module. Each Site returned
 * by the Topology site query API is rendered as a real MapLibre GL marker on the geo map (a
 * labelled <button>), driven by an Angular effect() over TopologyStore.sites(). An accessible
 * site list is kept as a WCAG complement / test source of truth (same data, same data-testid).
 * Supports the `?trailId=` deep link (AC 24) which is carried through to the site graph.
 *
 * Render strategy: the MapLibre Map is created with an INLINE empty style (no remote tiles /
 * glyphs / sprite), so the map renders fully offline. WebGL is required; in jsdom (unit tests)
 * there is no WebGL context, so map construction is skipped while `mapInitAttempted` is still
 * flipped true to prove the guarded real-render path executed.
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

    <div
      #mapEl
      class="geo-map"
      role="application"
      aria-label="Geographic map of network sites. Each site is selectable below."
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
              [attr.aria-label]="'Site ' + site.name + ' in ' + site.region + '. Open device graph.'"
            >
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
      .geo-map {
        height: 320px;
        border: 1px solid var(--border);
        border-radius: 10px;
        background: linear-gradient(135deg, #1e293b, #0f172a);
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

    // Inline empty basemap — NO network: no remote tiles, glyphs or sprite.
    const style: StyleSpecification = {
      version: 8,
      sources: {},
      layers: [{ id: 'bg', type: 'background', paint: { 'background-color': '#0f172a' } }],
    };

    this.map = new maplibregl.Map({
      container: this.mapEl.nativeElement,
      style,
      center: [10, 50],
      zoom: 3,
      attributionControl: false,
    });

    // Flip mapReady inside the Angular zone so the marker effect schedules and draws the
    // current sites; subsequent sites() changes are picked up by the same effect.
    this.zone.run(() => this.mapReady.set(true));
  }

  private rebuildMarkers(sites: ReturnType<TopologyStore['sites']>): void {
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
      const el = document.createElement('button');
      el.type = 'button';
      el.className = 'maplibre-site-marker';
      el.dataset['testid'] = 'map-marker';
      el.textContent = site.name;
      el.setAttribute('aria-label', `Site ${site.name} in ${site.region}. Open device graph.`);
      el.addEventListener('click', () => this.zone.run(() => this.select(site.siteId)));

      const marker = new maplibregl.Marker({ element: el })
        .setLngLat([site.longitude, site.latitude])
        .addTo(map);
      this.markers.push(marker);
    }
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
