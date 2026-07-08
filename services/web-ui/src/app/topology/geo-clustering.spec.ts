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
    // Status is the site's REAL worst-active-alarm bucket. LON (sites()[0]) has an active minor
    // alarm on Router:lon-r1 in the default mock → 'warning' (amber pin). The colour is a hex string.
    expect(f0.properties?.['status']).toBe('warning');
    expect(f0.properties?.['statusColor']).toMatch(/^#/);
  });

  it('the accessible site list still renders one entry per site (drill-in source of truth at any zoom)', async () => {
    const fixture = await mount();
    const store = TestBed.inject(TopologyStore);
    const markers = fixture.nativeElement.querySelectorAll('[data-testid="site-marker"]');
    expect(markers.length).toBe(store.sites().length);
    expect(store.sites().length).toBeGreaterThanOrEqual(2);
  });
});
