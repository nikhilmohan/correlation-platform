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

  it('siteStatusFor() is wired to REAL alarm severity — LON has an active minor alarm → warning (amber)', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    expect(store.sites().length).toBeGreaterThanOrEqual(1);
    // The default mock alarm fixture carries an ACTIVE minor alarm on Router:lon-r1 (a LON device),
    // so the LON site (sites()[0]) buckets to 'warning' (amber). Sites with no active fault stay
    // 'monitored' (green). This replaces the old "everything monitored" P3-hook placeholder.
    expect(fixture.componentInstance.siteStatusFor(store.sites()[0])).toBe('warning');
    // Every site resolves to a valid bucket (no site is left undefined).
    for (const site of store.sites()) {
      expect(['fault', 'warning', 'monitored']).toContain(fixture.componentInstance.siteStatusFor(site));
    }
  });

  it('statusCounts() reflects the REAL alarm buckets (LON warning; the rest OK) and totals the fleet', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    const counts = fixture.componentInstance.statusCounts();
    // One warning site (LON, active minor), no fault (no active critical/major in the default mock),
    // and the counts sum to the fleet size.
    expect(counts.warning).toBe(1);
    expect(counts.fault).toBe(0);
    expect(counts.monitored).toBe(store.sites().length - 1);
    expect(counts.fault + counts.warning + counts.monitored).toBe(store.sites().length);
    expect(counts.total).toBe(store.sites().length);
  });

  it('status bar renders the severity WORD (not colour-only) in each item — WCAG 1.4.1', async () => {
    const fixture = await mountGeo();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-testid="status-fault"]')?.textContent).toMatch(/Critical\/Major:\s*0/);
    expect(el.querySelector('[data-testid="status-warning"]')?.textContent).toMatch(/Minor:\s*1/);
    expect(el.querySelector('[data-testid="status-monitored"]')?.textContent).toMatch(/OK:\s*\d+/);
    // Each accessible site marker's aria-label carries the status PHRASE (not colour-only).
    const marker = el.querySelector('[data-testid="site-marker"]');
    expect(marker?.getAttribute('aria-label')).toMatch(/Alarm status: (minor fault|no active fault|critical or major fault)/);
  });

  it('the map-refresh control re-pulls the alarm snapshot (aria-busy, accessible)', async () => {
    const fixture = await mountGeo();
    const store = TestBed.inject(TopologyStore);
    const btn = fixture.nativeElement.querySelector('[data-testid="map-refresh"]') as HTMLButtonElement;
    expect(btn).not.toBeNull();
    expect(btn.tagName).toBe('BUTTON');
    expect(btn.getAttribute('aria-label')).toBeTruthy();
    const spy = vi.spyOn(store, 'refreshAlarms');
    btn.click();
    fixture.detectChanges();
    expect(spy).toHaveBeenCalled();
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

  // ── Explorer affordances: per-node EXPAND controls + clickable trail BUTTONS ──────────────────
  // UX redesign: the PRIMARY expand control is an always-visible on-canvas "+" overlay (empty under
  // jsdom — no real Cytoscape render). The accessible List-view row control is the keyboard/SR + test
  // equivalent and carries the same data-testid="expand-node" + aria-label, so this count assertion
  // (which runs in jsdom) still resolves exactly one reachable expand control per device node.
  it('renders a keyboard-reachable expand-node control for every accessible graph-node (data-testid="expand-node")', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const expandBtns = fixture.nativeElement.querySelectorAll('[data-testid="expand-node"]');
    expect(expandBtns.length).toBe(store.derivedNodes().length);
    expect(store.derivedNodes().length).toBeGreaterThanOrEqual(1);
    // They are real <button>s (keyboard-reachable), not list items.
    expect((expandBtns[0] as HTMLElement).tagName).toBe('BUTTON');
    // Each carries the "Expand neighbours of <name>" aria-label the e2e + screen-readers rely on.
    expect((expandBtns[0] as HTMLElement).getAttribute('aria-label')).toMatch(/^Expand neighbours of /);
    // Activating it drives the store expandNode action.
    const spy = vi.spyOn(store, 'expandNode');
    (expandBtns[0] as HTMLButtonElement).click();
    expect(spy).toHaveBeenCalledWith(store.derivedNodes()[0].managedObjectId);
  });

  it('the expand-node controls are cap-disabled when the store reports capReached (no expansion past the cap)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    // Before the cap: enabled.
    let btn = fixture.nativeElement.querySelector('[data-testid="expand-node"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(false);
    // Force the cap signal; the control must disable.
    store.capReached.set(true);
    fixture.detectChanges();
    btn = fixture.nativeElement.querySelector('[data-testid="expand-node"]') as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });

  // ── UX redesign: Devices/Connections lists behind a default-collapsed "List view" disclosure ──
  it('the List-view toggle is a keyboard-operable disclosure (aria-expanded/aria-controls), collapsed by default', async () => {
    const fixture = await mountSiteGraph();
    const toggle = fixture.nativeElement.querySelector('[data-testid="list-view-toggle"]') as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    expect(toggle.tagName).toBe('BUTTON');
    // Collapsed by default — the canvas is the primary interface.
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    const controls = toggle.getAttribute('aria-controls');
    expect(controls).toBeTruthy();
    // The controlled region exists in the DOM and is hidden while collapsed.
    const region = fixture.nativeElement.querySelector(`#${controls}`) as HTMLElement;
    expect(region).not.toBeNull();
    expect(region.hasAttribute('hidden')).toBe(true);
  });

  it('the accessible Devices/Connections list stays IN THE DOM when collapsed (CSS-hidden, not *ngIf-removed) — a11y + test bridge', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    // Default-collapsed, yet the per-row controls + data-cy bridge are still queryable (the redesign
    // requires CSS-collapse, never structural removal, so SR + Vitest/Playwright still resolve them).
    const toggle = fixture.nativeElement.querySelector('[data-testid="list-view-toggle"]') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(fixture.nativeElement.querySelectorAll('[data-testid="graph-node"]').length).toBe(store.derivedNodes().length);
    expect(fixture.nativeElement.querySelectorAll('[data-testid="graph-edge"]').length).toBe(store.visibleEdges().length);
    expect(fixture.nativeElement.querySelectorAll('[data-testid="expand-node"]').length).toBeGreaterThanOrEqual(1);
  });

  it('clicking the List-view toggle reveals/hides the lists (aria-expanded flips, region hidden flips) — lists never removed', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const toggle = fixture.nativeElement.querySelector('[data-testid="list-view-toggle"]') as HTMLButtonElement;
    const controls = toggle.getAttribute('aria-controls')!;
    const region = () => fixture.nativeElement.querySelector(`#${controls}`) as HTMLElement;

    // Open.
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(region().hasAttribute('hidden')).toBe(false);
    // Rows still present (and now visible).
    expect(fixture.nativeElement.querySelectorAll('[data-testid="graph-node"]').length).toBe(store.derivedNodes().length);

    // Close again — rows REMAIN in the DOM (not removed), region hidden again.
    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(region().hasAttribute('hidden')).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('[data-testid="graph-node"]').length).toBe(store.derivedNodes().length);
  });

  it('trail-cluster items are BUTTONS that drive selectTrail (clickable to explode the trail)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    const clusters = fixture.nativeElement.querySelectorAll('[data-testid="trail-cluster"]');
    expect(clusters.length).toBeGreaterThanOrEqual(1);
    expect((clusters[0] as HTMLElement).tagName).toBe('BUTTON');
    const spy = vi.spyOn(store, 'selectTrail');
    (clusters[0] as HTMLButtonElement).click();
    expect(spy).toHaveBeenCalledWith(store.trails()[0].trailId);
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

    // Post-load truth: loading cleared and the graph populated from the store signal.
    expect(store.graphLoading()).toBe(false);
    expect(store.hasGraph()).toBe(true);

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
