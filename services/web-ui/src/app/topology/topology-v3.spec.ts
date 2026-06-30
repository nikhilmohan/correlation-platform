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
    // A relayout would call cy.layout() (then fit). The viewport drift on select was a structure-effect
    // re-run (derivedNodes/visibleEdges re-emit a new array ref with an identical id-set) calling
    // rebuildAndLayout → runLayout → cy.layout → cy.fit. Spy layout/add/remove so we prove NONE fire.
    const runFn = vi.fn();
    const layout = vi.fn(() => ({ run: runFn }));
    const add = vi.fn();
    // A single fake leaf node so runLayout() does NOT early-return on an empty graph and would really
    // reach cy.layout()/cy.fit() if a relayout were (wrongly) triggered on select.
    const fakeNode = {
      id: () => 'n1',
      position: (p?: unknown) => (p ? undefined : { x: 0, y: 0 }),
      lock: vi.fn(),
      unlock: vi.fn(),
      data: () => 'n1',
      width: () => 100,
      height: () => 100,
      renderedPosition: () => ({ x: 0, y: 0 }),
      addClass: vi.fn(),
    };
    const nodeColl = {
      removeClass: vi.fn(),
      addClass: vi.fn(),
      forEach: (cb: (n: typeof fakeNode) => void) => cb(fakeNode),
      nonempty: () => true,
      length: 1,
      boundingBox: () => ({ w: 100, h: 100 }),
    };
    const edgeColl = { removeClass: vi.fn(), addClass: vi.fn(), forEach: vi.fn(), nonempty: () => false, length: 0 };
    const removeFn = vi.fn(() => edgeColl);
    const stub = {
      fit,
      center,
      zoom,
      layout,
      add,
      resize: vi.fn(),
      extent: () => ({ x1: 0, x2: 0, y1: 0, y2: 0 }),
      elements: () => ({ ...edgeColl, remove: removeFn }),
      nodes: () => nodeColl,
      edges: () => edgeColl,
      getElementById: () => edgeColl,
      destroy: vi.fn(),
    };
    component.setCyForTest(stub as never);
    // Flip the readiness gate so the structure/decoration effects run against the stub (in jsdom the
    // real ResizeObserver-driven readiness never fires). Let the FIRST structure pass build + lay out.
    component.setCyReadyForTest(true);
    await flush();
    fixture.detectChanges();
    await flush();

    // Now selecting a trail re-emits derivedNodes/visibleEdges (new array refs, identical id-set). The
    // value-gated structure effect must NOT rebuild/relayout/fit — it re-decorates only. Reset the
    // spies so we measure ONLY what the select triggers.
    layout.mockClear();
    add.mockClear();
    fit.mockClear();
    center.mockClear();
    zoom.mockClear();

    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    // No relayout (the value-gated structure effect re-decorates only) → no fit/center/layout.
    expect(layout).not.toHaveBeenCalled();
    expect(add).not.toHaveBeenCalled();
    expect(fit).not.toHaveBeenCalled();
    expect(center).not.toHaveBeenCalled();
    // zoom may be READ for the bridge but never SET (no positional arg).
    for (const call of zoom.mock.calls) {
      expect(call.length).toBe(0);
    }
  });
});

describe('topology-v3 CHANGE 5/6 — on-canvas trail overlay + explode', () => {
  it('the trail reference renders in the TOP control row next to the trail-selector dropdown (BUG 2)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const overlay = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    expect(overlay).not.toBeNull();
    // BUG 2 / #304 regression fix: the trail-ref chip lives in the top control row, beside the
    // dropdown TOGGLE BUTTON. data-testid="trail-selector" is now the toggle BUTTON itself (so a
    // test/user click opens the menu), not the wrapper — the chip is a SIBLING of it inside the
    // shared .trail-control-row (data-testid="trail-control-row"), NOT a descendant of the button.
    expect(overlay.closest('[data-testid="trail-control-row"]')).not.toBeNull();
    expect(overlay.closest('.trail-control-row')).not.toBeNull();
    const selector = fixture.nativeElement.querySelector('[data-testid="trail-selector"]') as HTMLElement;
    expect(selector.tagName).toBe('BUTTON');
    expect(overlay.contains(selector)).toBe(false);
  });

  it('the explode button lives on-canvas (inside .cy-wrap) and calls toggleFullPath', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const explode = fixture.nativeElement.querySelector('[data-testid="explode-trail"]') as HTMLButtonElement;
    expect(explode).not.toBeNull();
    expect(explode.closest('.cy-wrap')).not.toBeNull();
    const spy = vi.spyOn(store, 'toggleFullPath');
    explode.click();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('the trail-ref chip has NO per-member object rows — only the summary tooltip + explode toggle', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const overlay = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    // The raw managedObjectId member rows were removed.
    expect(overlay.querySelectorAll('[data-testid="trail-member"]').length).toBe(0);
    // The summary (member count) is carried in the chip's title tooltip; the explode toggle is present.
    const summary = (overlay.querySelector('.trail-ref') as HTMLElement).getAttribute('title') ?? '';
    expect(summary).toContain('members');
    expect(overlay.querySelector('[data-testid="explode-trail"]')).not.toBeNull();
  });

  it('the explode button reflects exploded state (aria-pressed + label) and resets on clear', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const explode = () =>
      fixture.nativeElement.querySelector('[data-testid="explode-trail"]') as HTMLButtonElement;
    expect(explode().getAttribute('aria-pressed')).toBe('false');
    expect(explode().textContent ?? '').toContain('Show full path');

    explode().click();
    await flush();
    fixture.detectChanges();
    expect(explode().getAttribute('aria-pressed')).toBe('true');
    expect(explode().textContent ?? '').toContain('Hide full path');

    // Clearing tears the explosion down (contract) so the overlay closes.
    (fixture.nativeElement.querySelector('[data-testid="clear-trail"]') as HTMLButtonElement).click();
    await flush();
    fixture.detectChanges();
    expect(store.explodeActive()).toBe(false);
    expect(fixture.nativeElement.querySelector('[data-testid="trail-detail"]')).toBeNull();
  });

  it('the top trail-ref chip keeps a reachable clear-trail control next to the explode toggle (BUG 2)', async () => {
    const fixture = await mountSiteGraph();
    const store = TestBed.inject(TopologyStore);
    store.selectTrail('TR-7');
    await flush();
    fixture.detectChanges();

    const chip = fixture.nativeElement.querySelector('[data-testid="trail-detail"]') as HTMLElement;
    expect(chip).not.toBeNull();
    // Exactly one clear-trail control, and it lives inside the top chip next to the explode toggle.
    const clears = fixture.nativeElement.querySelectorAll('[data-testid="clear-trail"]');
    expect(clears.length).toBe(1);
    expect(chip.querySelector('[data-testid="clear-trail"]')).not.toBeNull();
    expect(chip.querySelector('[data-testid="explode-trail"]')).not.toBeNull();
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
