import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { GeoSiteMapComponent } from './geo-site-map.component';
import { TopologyStore } from './topology.store';
import { NavigationService } from '../core/navigation.service';

/**
 * #276 — geo-map native GeoJSON clustering. The per-site DOM `maplibregl.Marker` approach (one
 * overlapping pin per site, which intercepted clicks on the dense UK/EU set) is replaced by
 * MapLibre's BUILT-IN clustering: a single GeoJSON source with `cluster: true` feeds a cluster
 * circle layer + an unclustered-site layer. In jsdom there is no WebGL, so a stubbed MapLibre map
 * (vi.fn() spies) is injected via a narrow test hook and the install path exercised. Site selection
 * is driven via the ACCESSIBLE site list (robust, not subject to pin overlap) — the WCAG complement
 * + test source of truth that reaches every site at any zoom.
 *
 * These assertions FAIL on regression: if the source loses `cluster: true`, a cluster/unclustered
 * layer is dropped, or the click→drill handler is unwired.
 */

function makeMapStub() {
  const handlers: Record<string, ((e?: unknown) => void)[]> = {};
  return {
    addSource: vi.fn(),
    addLayer: vi.fn(),
    getSource: vi.fn(),
    getCanvas: vi.fn(() => ({ style: {} })),
    queryRenderedFeatures: vi.fn(() => []),
    project: vi.fn(() => ({ x: 0, y: 0 })),
    easeTo: vi.fn(),
    on: vi.fn((ev: string, a: unknown, b?: unknown) => {
      // Support both map.on(ev, cb) and map.on(ev, layer, cb).
      const cb = (typeof a === 'function' ? a : b) as (e?: unknown) => void;
      const key = typeof a === 'function' ? ev : `${ev}:${a as string}`;
      (handlers[key] ??= []).push(cb);
    }),
    remove: vi.fn(),
    __fire: (key: string, e?: unknown) => (handlers[key] ?? []).forEach((cb) => cb(e)),
  };
}

async function mount(): Promise<ComponentFixture<GeoSiteMapComponent>> {
  TestBed.configureTestingModule({
    providers: [
      ...testProviders(),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
    ],
  });
  const fixture = TestBed.createComponent(GeoSiteMapComponent);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  return fixture;
}

describe('#276 — geo-map native GeoJSON clustering', () => {
  it('adds ONE clustering GeoJSON source with cluster:true + tuned radius/maxZoom', async () => {
    const fixture = await mount();
    const map = makeMapStub();
    fixture.componentInstance.installClusterLayersForTest(map as unknown as never);

    expect(map.addSource).toHaveBeenCalledTimes(1);
    const [srcId, srcDef] = map.addSource.mock.calls[0] as [string, Record<string, unknown>];
    expect(srcId).toBe('sites');
    expect(srcDef['type']).toBe('geojson');
    expect(srcDef['cluster']).toBe(true);
    expect(srcDef['clusterRadius']).toBeGreaterThan(0);
    expect(srcDef['clusterMaxZoom']).toBeGreaterThan(0);
  });

  it('adds a CLUSTER layer (point_count filter) and an UNCLUSTERED-site layer', async () => {
    const fixture = await mount();
    const map = makeMapStub();
    fixture.componentInstance.installClusterLayersForTest(map as unknown as never);

    const layers = map.addLayer.mock.calls.map((c) => c[0] as Record<string, unknown>);
    const cluster = layers.find((l) => l['id'] === 'site-clusters');
    const unclustered = layers.find((l) => l['id'] === 'site-unclustered');
    expect(cluster).toBeTruthy();
    expect(unclustered).toBeTruthy();
    // Cluster layer is filtered to features that HAVE a point_count; unclustered to those that don't.
    expect(JSON.stringify(cluster!['filter'])).toContain('point_count');
    expect(JSON.stringify(unclustered!['filter'])).toContain('point_count');
  });

  it('clicking an unclustered SITE point drills into its device graph (nav.toSiteGraph)', async () => {
    const fixture = await mount();
    const store = TestBed.inject(TopologyStore);
    const nav = TestBed.inject(NavigationService);
    const spy = vi.spyOn(nav, 'toSiteGraph').mockResolvedValue(true);

    const map = makeMapStub();
    fixture.componentInstance.installClusterLayersForTest(map as unknown as never);

    const siteId = store.sites()[0].siteId;
    map.__fire('click:site-unclustered', {
      features: [{ properties: { siteId } }],
    });
    expect(spy).toHaveBeenCalledWith(siteId);
  });

  it('EMITS the siteId via (siteSelected) when a host listener is bound (in-place dashboard drill)', async () => {
    const fixture = await mount();
    const nav = TestBed.inject(NavigationService);
    const navSpy = vi.spyOn(nav, 'toSiteGraph').mockResolvedValue(true);
    // Bind a listener to the output so `observed` is true (mirrors the dashboard host binding).
    const emitted: string[] = [];
    fixture.componentInstance.siteSelected.subscribe((id) => emitted.push(id));

    fixture.componentInstance.select('Site:LON');
    // With a listener bound, the component emits and does NOT fall back to router navigation.
    expect(emitted).toEqual(['Site:LON']);
    expect(navSpy).not.toHaveBeenCalled();
  });

  it('clicking a CLUSTER eases into its expansion zoom (getClusterExpansionZoom → easeTo)', async () => {
    const fixture = await mount();
    const map = makeMapStub();
    map.getSource.mockReturnValue({
      getClusterExpansionZoom: vi.fn(() => Promise.resolve(7)),
    } as unknown as never);
    fixture.componentInstance.installClusterLayersForTest(map as unknown as never);

    map.__fire('click:site-clusters', {
      features: [
        { properties: { cluster_id: 1 }, geometry: { type: 'Point', coordinates: [0, 50] } },
      ],
    });
    await flush();
    expect(map.easeTo).toHaveBeenCalledTimes(1);
    const arg = map.easeTo.mock.calls[0][0] as { zoom: number };
    expect(arg.zoom).toBeGreaterThan(7); // expansion zoom + nudge
  });

  it('the site FeatureCollection carries one point per site with siteId + status props', async () => {
    const fixture = await mount();
    const store = TestBed.inject(TopologyStore);
    const fc = fixture.componentInstance.sitesGeoJsonForTest();
    expect(fc.type).toBe('FeatureCollection');
    expect(fc.features.length).toBe(store.sites().length);
    const f0 = fc.features[0];
    expect(f0.geometry.type).toBe('Point');
    expect(f0.properties?.['siteId']).toBe(store.sites()[0].siteId);
    expect(typeof f0.properties?.['statusColor']).toBe('string');
    expect(f0.properties?.['status']).toBe('monitored');
  });

  it('the accessible site list still renders one entry per site (drill-in source of truth at any zoom)', async () => {
    const fixture = await mount();
    const store = TestBed.inject(TopologyStore);
    const markers = fixture.nativeElement.querySelectorAll('[data-testid="site-marker"]');
    expect(markers.length).toBe(store.sites().length);
    expect(store.sites().length).toBeGreaterThanOrEqual(2);
  });
});

/**
 * Cluster-count badge POSITIONING regression (corner-stack bug). The badges were re-synced on every
 * raw MapLibre 'render' frame, so `map.project()` returned near-(0,0) transient coordinates
 * mid-animation and the count badges stacked at the map's TOP-LEFT corner on the sea (seen live as
 * "222"/"43"). Fix: sync only on settled events (idle/moveend/initial), and skip any badge whose
 * projection lands off-canvas rather than clamping it to the corner. These tests fail on regression.
 */
function makeBadgeMapStub(opts: {
  features: { properties: Record<string, unknown>; geometry: { type: string; coordinates: [number, number] } }[];
  project: (c: [number, number]) => { x: number; y: number };
  canvasSize?: { w: number; h: number };
}) {
  const handlers: Record<string, ((e?: unknown) => void)[]> = {};
  const size = opts.canvasSize ?? { w: 800, h: 600 };
  return {
    addSource: vi.fn(),
    addLayer: vi.fn(),
    getSource: vi.fn(),
    getLayer: vi.fn(() => ({})),
    getCanvas: vi.fn(() => ({ style: {}, clientWidth: size.w, clientHeight: size.h })),
    queryRenderedFeatures: vi.fn(() => opts.features),
    project: vi.fn((c: [number, number]) => opts.project(c)),
    easeTo: vi.fn(),
    on: vi.fn((ev: string, a: unknown, b?: unknown) => {
      const cb = (typeof a === 'function' ? a : b) as (e?: unknown) => void;
      const key = typeof a === 'function' ? ev : `${ev}:${a as string}`;
      (handlers[key] ??= []).push(cb);
    }),
    remove: vi.fn(),
    __has: (key: string) => (handlers[key]?.length ?? 0) > 0,
    __fire: (key: string, e?: unknown) => (handlers[key] ?? []).forEach((cb) => cb(e)),
  };
}

describe('geo-map cluster-count badge positioning (corner-stack fix)', () => {
  it('positions each badge at its projected cluster centre, NOT at (0,0)', async () => {
    const fixture = await mount();
    const map = makeBadgeMapStub({
      features: [
        { properties: { point_count: 6 }, geometry: { type: 'Point', coordinates: [-0.1, 51.5] } },
      ],
      // Real settled projection — well inside the 800x600 canvas.
      project: () => ({ x: 530, y: 206 }),
    });

    const badges = fixture.componentInstance.syncClusterCountBadgesForTest(map as unknown as never);

    expect(badges.length).toBe(1);
    expect(badges[0].textContent).toBe('6');
    expect(badges[0].style.left).toBe('530px');
    expect(badges[0].style.top).toBe('206px');
    // The whole point of the fix: the badge is NOT stacked at the top-left corner.
    expect(badges[0].style.left).not.toBe('0px');
    expect(badges[0].style.top).not.toBe('0px');
    // Root-cause lock: the badge is created imperatively, so it lacks the component's
    // style-encapsulation attribute and the scoped `.cluster-count { position:absolute }` rule does
    // NOT apply — the span would collapse to `position:static` and IGNORE its left/top, stacking at
    // the host corner. Positioning + centering MUST be set INLINE (as the city labels are) so the
    // badge honours its left/top and centres over the cluster. A badge with left/top but
    // position:static is the "222" corner-stack bug.
    expect(badges[0].style.position).toBe('absolute');
    expect(badges[0].style.transform).toBe('translate(-50%, -50%)');
  });

  it('SKIPS a badge whose projection is off-canvas/negative instead of corner-stacking it', async () => {
    const fixture = await mount();
    const map = makeBadgeMapStub({
      features: [
        // Valid, on-canvas cluster.
        { properties: { point_count: 4 }, geometry: { type: 'Point', coordinates: [-0.1, 51.5] } },
        // Transient bad projection (mid-animation) — negative screen coords near the corner.
        { properties: { point_count: 3 }, geometry: { type: 'Point', coordinates: [10, 55] } },
      ],
      project: (c) => (c[0] === 10 ? { x: -4, y: -2 } : { x: 400, y: 300 }),
    });

    const badges = fixture.componentInstance.syncClusterCountBadgesForTest(map as unknown as never);

    // Only the on-canvas cluster gets a badge; the off-canvas one is dropped (never at the corner).
    expect(badges.length).toBe(1);
    expect(badges[0].textContent).toBe('4');
    for (const b of badges) {
      expect(b.style.left).not.toBe('0px');
      expect(b.style.top).not.toBe('0px');
    }
  });

  it('does NOT wire cluster-badge sync to the raw "render" frame (only settled idle/moveend)', async () => {
    const fixture = await mount();
    const map = makeBadgeMapStub({
      features: [],
      project: () => ({ x: 0, y: 0 }),
    });
    fixture.componentInstance.installClusterLayersForTest(map as unknown as never);

    // The cluster-count badges must settle on idle/moveend, never re-project per render frame
    // (that mid-animation re-projection is what stacked them at (0,0)).
    expect(map.__has('idle')).toBe(true);
    expect(map.__has('moveend')).toBe(true);
    expect(map.__has('render')).toBe(false);
  });
});
