import { TestBed, ComponentFixture } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { DashboardComponent } from '../dashboard/dashboard.component';
import { TopologyStore } from './topology.store';

/** Read a standalone component's compiled inline `styles[]` (all entries joined) for CSS-contract
 *  assertions. The regression these guard is a DOM-layout one that jsdom cannot measure (it does no
 *  layout, so every getBoundingClientRect() is 0); asserting the CSS rules that resolve the flex
 *  chain is the deterministic proxy that fails the moment the collapse is reintroduced. */
function componentStyles(cmp: unknown): string {
  const meta = (cmp as { ɵcmp?: { styles?: readonly string[] } }).ɵcmp;
  return (meta?.styles ?? []).join('\n');
}
/** Collapse all whitespace so brittle formatting differences don't affect the substring assertions. */
function squish(css: string): string {
  return css.replace(/\s+/g, ' ');
}

/**
 * Dashboard site-graph fit-in-bounds + shared-panel + Back-to-map behaviour (PR #401 fix):
 *   - the in-place site graph carries a PROMINENT "← Back to map" button (data-testid="site-graph-close")
 *     at the TOP of the view that emits (closed) so the dashboard swaps back to the map;
 *   - the `embedded` input reflects onto the host (.embedded-host) so the panel fills the shared box;
 *   - AUTO-FIT: the graph fits ALL elements in-bounds — the Fit button and the fitAll() path call
 *     cy.fit(cy.elements(), padding), resize the canvas first, and never zoom above the maxZoom cap.
 * Each assertion FAILS if the corresponding wiring regresses.
 */

interface FitCyStub {
  level: number;
  zoom: ReturnType<typeof vi.fn>;
  fit: ReturnType<typeof vi.fn>;
  resize: ReturnType<typeof vi.fn>;
  center: ReturnType<typeof vi.fn>;
  extent: ReturnType<typeof vi.fn>;
  elements: ReturnType<typeof vi.fn>;
  destroy: ReturnType<typeof vi.fn>;
}

/** A cy stub whose zoom() reports a HIGH level after fit, so the maxZoom cap path is exercised. */
function makeFitCyStub(zoomAfterFit: number): FitCyStub {
  const stub = {
    level: 1,
    zoom: vi.fn((arg?: { level: number }) => {
      if (arg && typeof arg.level === 'number') {
        stub.level = arg.level;
        return undefined as unknown as number;
      }
      return stub.level;
    }),
    fit: vi.fn(() => {
      // Emulate cy.fit() settling the viewport at a (possibly too-high) zoom for a tiny graph.
      stub.level = zoomAfterFit;
    }),
    resize: vi.fn(),
    center: vi.fn(),
    extent: vi.fn(() => ({ x1: 0, y1: 0, x2: 100, y2: 100, w: 100, h: 100 })),
    elements: vi.fn(() => ({ length: 3 })),
    destroy: vi.fn(),
  };
  return stub as unknown as FitCyStub;
}

async function mount(embedded: boolean): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.componentInstance.embedded = embedded;
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

describe('site-graph — Back to map + embedded host', () => {
  it('renders a prominent "← Back to map" button (site-graph-close) that emits (closed)', async () => {
    const fixture = await mount(true);
    const el: HTMLElement = fixture.nativeElement;
    const back = el.querySelector('[data-testid="site-graph-close"]') as HTMLButtonElement;
    expect(back).not.toBeNull();
    expect(back.tagName).toBe('BUTTON');
    // A REAL labelled button (← glyph + "Back to map" text), not a bare breadcrumb crumb.
    expect(back.textContent).toMatch(/Back to map/i);
    expect(back.getAttribute('aria-label')).toBe('Back to map');

    const emitted = vi.fn();
    fixture.componentInstance.closed.subscribe(emitted);
    back.click();
    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('embedded reflects onto the host (.embedded-host) and suppresses the breadcrumb', async () => {
    const fixture = await mount(true);
    expect((fixture.nativeElement as HTMLElement).classList.contains('embedded-host')).toBe(true);
    // Embedded suppresses the verbose breadcrumb (the Back button is the return path).
    expect(fixture.nativeElement.querySelector('[data-testid="breadcrumb-topology"]')).toBeNull();
  });

  it('standalone (not embedded) keeps the breadcrumb and no embedded-host class', async () => {
    const fixture = await mount(false);
    expect((fixture.nativeElement as HTMLElement).classList.contains('embedded-host')).toBe(false);
    expect(fixture.nativeElement.querySelector('[data-testid="breadcrumb-topology"]')).not.toBeNull();
  });
});

describe('site-graph — auto-fit all nodes in-bounds', () => {
  it('Fit resizes the canvas then cy.fit()s ALL elements (fit-all, not viewport-only)', async () => {
    const fixture = await mount(true);
    const cy = makeFitCyStub(0.8);
    fixture.componentInstance.setCyForTest(cy as unknown as never);
    fixture.componentInstance.fit();
    // fitAll(): resize BEFORE fit (so it never fits to a stale/zero size), then fit ALL elements.
    expect(cy.resize).toHaveBeenCalled();
    expect(cy.elements).toHaveBeenCalled();
    expect(cy.fit).toHaveBeenCalled();
  });

  it('caps the fit zoom at the maxZoom (a tiny graph is not hugely magnified)', async () => {
    const fixture = await mount(true);
    // fit() settles the stub at zoom 3.0 (> the 1.5 cap) → fitAll must clamp back down to the cap.
    const cy = makeFitCyStub(3.0);
    fixture.componentInstance.setCyForTest(cy as unknown as never);
    fixture.componentInstance.fit();
    expect(cy.level).toBe(SiteGraphComponent.FIT_MAX_ZOOM);
  });

  it('does NOT over-zoom when fit settles below the cap (keeps cy.fit scale)', async () => {
    const fixture = await mount(true);
    const cy = makeFitCyStub(0.6);
    fixture.componentInstance.setCyForTest(cy as unknown as never);
    fixture.componentInstance.fit();
    // Below the cap → the fit scale is preserved (no clamp-up, no clamp-down).
    expect(cy.level).toBe(0.6);
  });

  it('Reset re-arms the first-fit so the next layout re-fits all nodes', async () => {
    const fixture = await mount(true);
    const store = TestBed.inject(TopologyStore);
    const spy = vi.spyOn(store, 'collapseToRoot');
    fixture.componentInstance.reset();
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

/**
 * REGRESSION GUARD — embedded site-graph collapsed to zero height (empty graph in the dashboard panel).
 * The live cause was a specificity fight: the dashboard's `.topology-panel > app-site-graph` rule set
 * `display: block`, which BEAT the component's `:host(.embedded-host){ display:flex }` and reverted the
 * host to a block box, so the flex chain never resolved and the Cytoscape `.cy-canvas` got clientHeight
 * 0 (nodes never drew). jsdom does no layout, so we assert the CSS CONTRACT that resolves the chain:
 *   - the component's :host(.embedded-host) makes the host a flex column;
 *   - the embedded .cy-wrap/.cy-canvas carry a NON-ZERO min-height fallback (never fully collapse);
 *   - the DASHBOARD parent selector for the embedded child must NOT set `display` (which would win on
 *     specificity and re-collapse the child) and must stretch the child (flex + height:100%).
 * Any of these regressing re-introduces the empty-graph bug and fails here.
 */
describe('site-graph — embedded panel must NOT collapse to zero height (regression #401)', () => {
  // Angular ViewEncapsulation.Emulated compiles `:host(.embedded-host)` to
  // `.embedded-host[_nghost-%COMP%]` and appends `[_ngcontent-%COMP%]` to descendant selectors, so
  // the assertions match those compiled forms (attribute suffixes tolerated).
  it('the embedded host (:host(.embedded-host)) is a flex column that fills its parent', () => {
    const css = squish(componentStyles(SiteGraphComponent));
    const host = /\.embedded-host\[[^\]]*\]\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(host.length).toBeGreaterThan(0);
    expect(host).toMatch(/display:\s*flex/);
    expect(host).toMatch(/flex-direction:\s*column/);
    expect(host).toMatch(/height:\s*100%/);
  });

  it('the embedded graph container carries a NON-ZERO min-height fallback (never fully collapses)', () => {
    const css = squish(componentStyles(SiteGraphComponent));
    // .cy-wrap and .cy-canvas in embedded mode must have a non-zero px min-height fallback so the
    // Cytoscape mount + fit always get a real box even if the flex chain briefly fails to resolve.
    const wrap = /\.embedded-host\[[^\]]*\]\s*\.cy-wrap\[[^\]]*\]\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    const canvas = /\.embedded-host\[[^\]]*\]\s*\.cy-canvas\[[^\]]*\]\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    const minPx = (block: string): number => {
      const m = /min-height:\s*(\d+)px/.exec(block);
      return m ? Number(m[1]) : 0;
    };
    expect(minPx(wrap)).toBeGreaterThan(0);
    expect(minPx(canvas)).toBeGreaterThan(0);
    // And the canvas fills the wrap's resolved height.
    expect(canvas).toMatch(/height:\s*100%/);
  });

  it('DASHBOARD panel does NOT force display:block on the embedded child (specificity trap)', () => {
    const css = squish(componentStyles(DashboardComponent));
    // The parent > child rule that sizes the embedded map/graph host (attribute suffixes tolerated).
    const rule =
      /\.topology-panel\[[^\]]*\]\s*>\s*app-geo-site-map\[[^\]]*\]\s*,\s*\.topology-panel\[[^\]]*\]\s*>\s*app-site-graph\[[^\]]*\]\s*\{([^}]*)\}/.exec(
        css,
      )?.[1] ?? '';
    expect(rule.length).toBeGreaterThan(0);
    // The regression: a `display: block` here overrides the child's :host(.embedded-host){display:flex}.
    expect(rule).not.toMatch(/display:\s*block/);
    // It must instead STRETCH the child to the panel height so the child's flex chain has a real box.
    expect(rule).toMatch(/flex:\s*1 1 auto/);
    expect(rule).toMatch(/height:\s*100%/);
    // And the panel itself is a flex column so the single child fills the fixed-height box.
    const panel = /\.topology-panel\[[^\]]*\]\s*\{([^}]*)\}/.exec(css)?.[1] ?? '';
    expect(panel).toMatch(/display:\s*flex/);
  });
});
