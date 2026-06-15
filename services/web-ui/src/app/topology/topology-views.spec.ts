import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { GeoSiteMapComponent } from './geo-site-map.component';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';

/**
 * Real-UI render tests for the two graphical topology views (AC 26-33, AC 52). In jsdom there is
 * no WebGL/canvas, so the MapLibre map and Cytoscape graph are NOT actually painted; instead we
 * assert (a) the accessible list rendered from the store signals matches the data, (b) the canvas
 * div carries role="application" + an ARIA label (AC 52), and (c) the guarded real-render path
 * executed (mapInitAttempted / cyInitAttempted). The REAL painted-canvas + marker/edge counts are
 * asserted by the Playwright suite (real chromium with WebGL), which complements these.
 */
async function mountGeo(): Promise<ComponentFixture<GeoSiteMapComponent>> {
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
  fixture.detectChanges();
  return fixture;
}

async function mountSiteGraph(siteId = 'Site:LON'): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', siteId);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('GeoSiteMapComponent — real-UI render (AC 26, AC 52)', () => {
  it('AC 26 — renders one accessible site marker per Site from the store signal', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    const markers = fixture.nativeElement.querySelectorAll('[data-testid="site-marker"]');
    expect(markers.length).toBe(store.sites().length);
    expect(store.sites().length).toBeGreaterThanOrEqual(2);
    const texts = [...markers].map((m: Element) => m.textContent ?? '');
    expect(texts.some((t) => t.includes(store.sites()[0].name))).toBe(true);
  });

  it('AC 52 — the geo map canvas is role="application" with a truthy ARIA label', async () => {
    const fixture = await mountGeo();
    const canvas = fixture.nativeElement.querySelector('.geo-map');
    expect(canvas?.getAttribute('role')).toBe('application');
    expect(canvas?.getAttribute('aria-label')).toBeTruthy();
  });

  it('the guarded MapLibre real-render path executes (mapInitAttempted)', async () => {
    const fixture = await mountGeo();
    expect(fixture.componentInstance.mapInitAttempted).toBe(true);
  });
});

describe('SiteGraphComponent — real-UI render (AC 27/28/31/32, AC 52)', () => {
  it('AC 27 — renders one accessible graph-node per derivedNode from the store signal', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const nodes = fixture.nativeElement.querySelectorAll('[data-testid="graph-node"]');
    expect(nodes.length).toBe(store.derivedNodes().length);
    expect(store.derivedNodes().length).toBeGreaterThanOrEqual(1);
  });

  it('AC 28 — renders one accessible graph-edge per visibleEdge from the store signal', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const edges = fixture.nativeElement.querySelectorAll('[data-testid="graph-edge"]');
    expect(edges.length).toBe(store.visibleEdges().length);
    expect(store.visibleEdges().length).toBeGreaterThanOrEqual(1);
  });

  it('AC 31 — renders one trail-cluster overlay per trail from the store signal', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const clusters = fixture.nativeElement.querySelectorAll('[data-testid="trail-cluster"]');
    expect(clusters.length).toBe(store.trails().length);
  });

  it('AC 52 — the graph canvas is role="application" with a truthy ARIA label', async () => {
    const fixture = await mountSiteGraph();
    const canvas = fixture.nativeElement.querySelector('.cy-canvas');
    expect(canvas?.getAttribute('role')).toBe('application');
    expect(canvas?.getAttribute('aria-label')).toBeTruthy();
  });

  it('the guarded Cytoscape real-render path executes (cyInitAttempted)', async () => {
    const fixture = await mountSiteGraph();
    expect(fixture.componentInstance.cyInitAttempted).toBe(true);
  });
});
