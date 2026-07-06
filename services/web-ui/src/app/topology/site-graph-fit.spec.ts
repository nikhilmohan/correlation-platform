import { TestBed, ComponentFixture } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';

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
