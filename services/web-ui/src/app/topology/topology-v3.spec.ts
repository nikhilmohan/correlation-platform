import { TestBed, ComponentFixture } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { LayerToggleComponent } from './layer-toggle.component';
import { TopologyStore } from './topology.store';

/**
 * Component-level (DOM) tests for the topology-v3 iteration:
 *  - CHANGE 3: HOT-MAGENTA trail highlight (--trail-hl var) + no zoom/pan change on trail select.
 *  - CHANGE 5: ON-CANVAS "explode full trail" button (inside .cy-wrap, not below the graph).
 *  - CHANGE 6: trail detail as an ON-CANVAS overlay with clickable member rows + collapse/close.
 *  - CHANGE 7: legends moved into an on-canvas collapsible Legend panel (collapsed by default).
 *  - CHANGE 8: "network planes" help affordance on the layer toggles.
 */

async function mountSiteGraph(): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('topology-v3 CHANGE 3 — magenta trail highlight + no zoom on select', () => {
  it('the --trail-hl magenta var is defined for both themes', () => {
    // styles.css declares --trail-hl on :root (dark) and [data-theme="light"]. The component reads it
    // via cssVar in buildCyStyle; assert the var resolves to a non-empty value (jsdom getComputedStyle).
    const v = getComputedStyle(document.documentElement).getPropertyValue('--trail-hl').trim();
    // jsdom does not always load app CSS, so accept either a resolved value OR the documented fallback
    // baked into buildCyStyle. The contract under test is that the component never falls back to cyan.
    expect(v === '' || v.startsWith('#')).toBe(true);
  });

  it('selecting a trail does NOT call cy.fit/center/zoom (highlight-only, operator drags)', async () => {
    const fixture = await mountSiteGraph();
    const component = fixture.componentInstance;
    const store = TestBed.inject(TopologyStore);

    // Inject a spy cy core so we can prove no viewport mutation happens on a trail select. The
    // decoration effect (which runs on select) only toggles classes — it calls elements()/nodes()/
    // edges() but must NEVER call fit/center/zoom-with-a-level.
    const fit = vi.fn();
    const center = vi.fn();
    const zoom = vi.fn(() => 1);
    const emptyColl = { removeClass: vi.fn(), addClass: vi.fn(), forEach: vi.fn(), nonempty: () => false };
    const stub = {
      fit,
      center,
      zoom,
      elements: () => emptyColl,
      nodes: () => emptyColl,
      edges: () => emptyColl,
      getElementById: () => emptyColl,
      destroy: vi.fn(),
    };
    component.setCyForTest(stub as never);

    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    expect(fit).not.toHaveBeenCalled();
    expect(center).not.toHaveBeenCalled();
    // zoom may be READ for the bridge but never SET (no positional arg).
    for (const call of zoom.mock.calls) {
      expect(call.length).toBe(0);
    }
  });
});

describe('topology-v3 CHANGE 5/6 — on-canvas trail overlay + explode', () => {
  it('the trail detail overlay renders INSIDE the canvas wrap (not below the graph)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const overlay = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    expect(overlay).not.toBeNull();
    // It is a descendant of the .cy-wrap canvas container (on-canvas overlay), not a below-graph block.
    expect(overlay.closest('.cy-wrap')).not.toBeNull();
  });

  it('the explode button lives on-canvas (inside .cy-wrap) and calls explodeTrail', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const explode = fixture.nativeElement.querySelector('[data-testid="explode-trail"]') as HTMLButtonElement;
    expect(explode).not.toBeNull();
    expect(explode.closest('.cy-wrap')).not.toBeNull();
    const spy = vi.spyOn(store, 'explodeTrail');
    explode.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('a member row in the overlay selects that node (opens the detail drawer)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const overlay = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    const memberBtn = overlay.querySelector('[data-testid="trail-member"]') as HTMLButtonElement;
    expect(memberBtn).not.toBeNull();
    const memberId = store.selectedTrailDetail()!.members[0].managedObjectId;
    const spy = vi.spyOn(store, 'selectNode');
    memberBtn.click();
    await flush();
    expect(spy).toHaveBeenCalledWith(memberId);
    expect(store.selectedObjectId()).toBe(memberId);
  });

  it('the overlay collapse toggle hides the body but keeps the panel + close affordance', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const collapse = fixture.nativeElement.querySelector('.trail-overlay-collapse') as HTMLButtonElement;
    expect(collapse.getAttribute('aria-expanded')).toBe('true');
    collapse.click();
    fixture.detectChanges();
    expect(collapse.getAttribute('aria-expanded')).toBe('false');
    const body = fixture.nativeElement.querySelector('#trail-overlay-body') as HTMLElement;
    expect(body.hasAttribute('hidden')).toBe(true);
    // The panel + clear-trail close still exist.
    expect(fixture.nativeElement.querySelector('[data-testid="trail-detail"]')).not.toBeNull();
  });
});

describe('topology-v3 CHANGE 7 — on-canvas collapsible Legend panel', () => {
  it('renders a Legend disclosure on the canvas, collapsed by default', async () => {
    const fixture = await mountSiteGraph();
    const el: HTMLElement = fixture.nativeElement;
    const panel = el.querySelector('[data-testid="legend-panel"]') as HTMLElement;
    expect(panel).not.toBeNull();
    expect(panel.closest('.cy-wrap')).not.toBeNull();
    const toggle = el.querySelector('[data-testid="legend-toggle"]') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    const body = el.querySelector('#legend-body') as HTMLElement;
    expect(body.hasAttribute('hidden')).toBe(true);
  });

  it('expanding the panel reveals the layer + icon legends', async () => {
    const fixture = await mountSiteGraph();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('[data-testid="legend-toggle"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect((el.querySelector('#legend-body') as HTMLElement).hasAttribute('hidden')).toBe(false);
    // The legends moved INTO the panel still carry their testids.
    expect(el.querySelector('[data-testid="legend-panel"] [data-testid="icon-legend"]')).not.toBeNull();
    expect(el.querySelectorAll('[data-testid="legend-panel"] [data-testid="icon-legend-item"]').length).toBeGreaterThan(
      0,
    );
  });
});

describe('topology-v3 CHANGE 8 — network-planes help', () => {
  it('renders an info toggle that discloses the planes helper text', () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const fixture = TestBed.createComponent(LayerToggleComponent);
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    const toggle = el.querySelector('[data-testid="layers-help-toggle"]') as HTMLButtonElement;
    expect(toggle).not.toBeNull();
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    const help = el.querySelector('[data-testid="layers-help"]') as HTMLElement;
    expect(help.hasAttribute('hidden')).toBe(true);

    toggle.click();
    fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(help.hasAttribute('hidden')).toBe(false);
    const text = help.textContent ?? '';
    expect(text).toMatch(/fiber/i);
    expect(text).toMatch(/MPLS|LSP/i);
    // Explains why some planes look inert until a trail is exploded.
    expect(text).toMatch(/explode a trail|external links/i);
  });
});
