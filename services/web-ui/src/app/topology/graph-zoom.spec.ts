import { TestBed, ComponentFixture } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { TopologyStore } from './topology.store';

/**
 * AC 73 — device-graph zoom controls drive the Cytoscape API. In jsdom the real Cytoscape canvas is
 * never built (no WebGL), so a stubbed `cy` core (vi.fn() spies) is injected via the component's
 * narrow test hook; each control handler is then exercised and the resulting cy call asserted:
 *   - zoom-in  → cy.zoom() raised above the current level,
 *   - zoom-out → cy.zoom() lowered below the current level,
 *   - Fit      → cy.fit() called,
 *   - Reset    → store.collapseToRoot() re-roots (firstFit reset) — the component's reset semantics.
 * Each assertion would FAIL if the corresponding handler stopped calling the cytoscape/store method.
 */

interface CyStub {
  level: number;
  zoom: ReturnType<typeof vi.fn>;
  fit: ReturnType<typeof vi.fn>;
  extent: ReturnType<typeof vi.fn>;
  destroy: ReturnType<typeof vi.fn>;
}

function makeCyStub(): CyStub {
  const stub = {
    level: 1,
    zoom: vi.fn((arg?: { level: number }) => {
      if (arg && typeof arg.level === 'number') {
        stub.level = arg.level;
        return undefined as unknown as number;
      }
      return stub.level;
    }),
    fit: vi.fn(),
    extent: vi.fn(() => ({ x1: 0, y1: 0, x2: 100, y2: 100, w: 100, h: 100 })),
    // ngOnDestroy calls cy.destroy() — provide it so test cleanup doesn't throw.
    destroy: vi.fn(),
  };
  return stub as unknown as CyStub;
}

async function mount(): Promise<{ fixture: ComponentFixture<SiteGraphComponent>; cy: CyStub }> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  const cy = makeCyStub();
  // Inject the stub AFTER the guarded jsdom init (which leaves cy null) so the handlers act on it.
  fixture.componentInstance.setCyForTest(cy as unknown as never);
  return { fixture, cy };
}

describe('AC 73 — device-graph zoom controls (cy spy)', () => {
  it('zoom-in raises the cytoscape zoom level above the current level', async () => {
    const { fixture, cy } = await mount();
    cy.level = 1;
    fixture.componentInstance.zoomIn();
    expect(cy.zoom).toHaveBeenCalled();
    // The handler reads cy.zoom() then sets a HIGHER level via cy.zoom({level}).
    expect(cy.level).toBeGreaterThan(1);
  });

  it('zoom-out lowers the cytoscape zoom level below the current level', async () => {
    const { fixture, cy } = await mount();
    cy.level = 1;
    fixture.componentInstance.zoomOut();
    expect(cy.zoom).toHaveBeenCalled();
    expect(cy.level).toBeLessThan(1);
  });

  it('Fit calls cy.fit() to fit all current content into the viewport', async () => {
    const { fixture, cy } = await mount();
    fixture.componentInstance.fit();
    expect(cy.fit).toHaveBeenCalled();
  });

  it('Reset re-roots the graph (collapseToRoot) and re-arms the first-fit', async () => {
    const { fixture } = await mount();
    const store = TestBed.inject(TopologyStore);
    const spy = vi.spyOn(store, 'collapseToRoot');
    fixture.componentInstance.reset();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it('all four controls are wired from the rendered buttons (in/out/fit/reset)', async () => {
    const { fixture, cy } = await mount();
    const store = TestBed.inject(TopologyStore);
    const collapseSpy = vi.spyOn(store, 'collapseToRoot');
    const el: HTMLElement = fixture.nativeElement;

    cy.level = 1;
    (el.querySelector('[data-testid="zoom-in"]') as HTMLButtonElement).click();
    expect(cy.level).toBeGreaterThan(1);

    cy.level = 1;
    (el.querySelector('[data-testid="zoom-out"]') as HTMLButtonElement).click();
    expect(cy.level).toBeLessThan(1);

    cy.fit.mockClear();
    (el.querySelector('[data-testid="zoom-fit"]') as HTMLButtonElement).click();
    expect(cy.fit).toHaveBeenCalled();

    (el.querySelector('[data-testid="zoom-reset"]') as HTMLButtonElement).click();
    expect(collapseSpy).toHaveBeenCalled();
  });
});
