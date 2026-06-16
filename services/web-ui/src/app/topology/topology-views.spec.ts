import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { GeoSiteMapComponent } from './geo-site-map.component';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';
import { NavigationService } from '../core/navigation.service';

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

  it('siteStatusFor() is the P3 hook — every P1 site (no severity in SiteDto) is "monitored"', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    expect(store.sites().length).toBeGreaterThanOrEqual(1);
    for (const site of store.sites()) {
      expect(fixture.componentInstance.siteStatusFor(site)).toBe('monitored');
    }
  });

  it('statusCounts() — Monitored == site count, Fault/Warning == 0, total == site count', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    const counts = fixture.componentInstance.statusCounts();
    expect(counts.monitored).toBe(store.sites().length);
    expect(counts.fault).toBe(0);
    expect(counts.warning).toBe(0);
    expect(counts.total).toBe(store.sites().length);
  });

  it('status bar renders the status WORD (not colour-only) in each item — WCAG 1.4.1', async () => {
    const fixture = await mountGeo();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="status-fault"]')?.textContent).toMatch(/Fault:\s*0/);
    expect(el.querySelector('[data-testid="status-warning"]')?.textContent).toMatch(/Warning:\s*0/);
    expect(el.querySelector('[data-testid="status-monitored"]')?.textContent).toMatch(/Monitored:\s*\d+/);
    // Each accessible site marker's aria-label carries the status word (not colour-only).
    const marker = el.querySelector('[data-testid="site-marker"]');
    expect(marker?.getAttribute('aria-label')).toMatch(/Status: Monitored/);
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

  it('renders a geo→topology breadcrumb that navigates back via NavigationService', async () => {
    const fixture = await mountSiteGraph('Site:LON');
    const el: HTMLElement = fixture.nativeElement;
    const crumb = el.querySelector('[data-testid="breadcrumb-topology"]') as HTMLButtonElement | null;
    expect(crumb).not.toBeNull();
    expect(crumb?.textContent).toMatch(/Topology/);
    expect(el.querySelector('.crumb-current')?.textContent).toMatch(/Site: Site:LON/);

    const nav = TestBed.inject(NavigationService);
    const spy = vi.spyOn(nav, 'toTopology').mockResolvedValue(true);
    crumb?.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('renders a layer legend with the five toggleable layers as text (not colour-only)', async () => {
    const fixture = await mountSiteGraph();
    const legend = fixture.nativeElement.querySelector('.layer-legend') as HTMLElement | null;
    expect(legend).not.toBeNull();
    const text = legend?.textContent ?? '';
    for (const layer of ['fiber', 'IP', 'IGP', 'LSP', 'service']) {
      expect(text).toContain(layer);
    }
  });

  // ── #253 load/render lifecycle — the device graph reliably reaches a rendered state, no race ──
  it('once objects resolve, graphLoading() is false, the Loading… placeholder is gone, and graph rows render', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);

    // Post-load truth: loading cleared and objects populated from the store signal.
    expect(store.graphLoading()).toBe(false);
    expect(store.objects()).not.toBeNull();

    // The "Loading site graph…" placeholder is removed once loading clears (no permanent Loading state).
    expect(fixture.nativeElement.querySelector('[data-testid="graph-loading"]')).toBeNull();

    // The accessible node/edge lists render from the store signals (the same source the canvas bridge uses).
    const nodes = fixture.nativeElement.querySelectorAll('[data-testid="graph-node"]');
    const edges = fixture.nativeElement.querySelectorAll('[data-testid="graph-edge"]');
    expect(nodes.length).toBe(store.derivedNodes().length);
    expect(nodes.length).toBeGreaterThanOrEqual(1);
    expect(edges.length).toBe(store.visibleEdges().length);
    expect(edges.length).toBeGreaterThanOrEqual(1);

    // Test/render bridge: the canvas reflects the cleared loading state and the real store counts,
    // so a Playwright reader that waits for data-cy-loading="false" sees the post-render counts.
    const canvas: HTMLElement = fixture.nativeElement.querySelector('.cy-canvas');
    expect(canvas.dataset['cyLoading']).toBe('false');
    expect(Number(canvas.dataset['cyEdgeCount'])).toBe(store.visibleEdges().length);
    expect(Number(canvas.dataset['cyNodeCount'])).toBe(store.derivedNodes().length);
  });

  it('AC 28 (#263) — toggling ONLY the five logical layers off drives the bridge edge-count to 0 with nodes remaining', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const canvas: HTMLElement = fixture.nativeElement.querySelector('.cy-canvas');

    // Pre-condition: loaded, non-zero rendered edges, none stranded in the un-toggleable `other` layer.
    expect(store.graphLoading()).toBe(false);
    expect(Number(canvas.dataset['cyEdgeCount'])).toBeGreaterThanOrEqual(1);
    expect(store.derivedEdges().map((e) => e.derivedLayer)).not.toContain('other');

    // Toggle only the FIVE operator-facing layers (exactly what the LayerToggleComponent / Playwright
    // does — `other` has no checkbox). Pre-#263 this left the SRLG/containment edges rendered.
    for (const layer of ['fiber', 'IP', 'IGP', 'LSP', 'service'] as const) {
      store.setLayerVisible(layer, false);
    }
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();

    // All five layers off → 0 visible edges (list + bridge), nodes remain.
    expect(fixture.nativeElement.querySelectorAll('[data-testid="graph-edge"]').length).toBe(0);
    expect(Number(canvas.dataset['cyEdgeCount'])).toBe(0);
    expect(Number(canvas.dataset['cyNodeCount'])).toBeGreaterThanOrEqual(1);
  });
});
