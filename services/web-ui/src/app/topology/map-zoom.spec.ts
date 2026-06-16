import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { GeoSiteMapComponent } from './geo-site-map.component';

/**
 * AC 74 — geo-map zoom controls drive the MapLibre API. In jsdom there is no WebGL, so the real
 * map is never constructed; a stubbed MapLibre map (vi.fn() spies) is injected via the component's
 * narrow test hook and each control handler exercised:
 *   - zoom-in  → map.zoomIn(),
 *   - zoom-out → map.zoomOut(),
 *   - Fit      → map.fitBounds(siteExtent) — bounds containing all site markers,
 *   - Reset    → map.fitBounds(siteExtent) (zoom + centre back to the initial default view).
 * Each assertion would FAIL if the corresponding handler stopped calling the MapLibre method.
 */

function makeMapStub() {
  return {
    zoomIn: vi.fn(),
    zoomOut: vi.fn(),
    fitBounds: vi.fn(),
    // ngOnDestroy calls map.remove() — provide it so test cleanup doesn't throw.
    remove: vi.fn(),
  };
}

async function mount(): Promise<{ fixture: ComponentFixture<GeoSiteMapComponent>; map: ReturnType<typeof makeMapStub> }> {
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
  const map = makeMapStub();
  fixture.componentInstance.setMapForTest(map as unknown as never);
  return { fixture, map };
}

describe('AC 74 — geo-map zoom controls (MapLibre spy)', () => {
  it('zoom-in calls map.zoomIn()', async () => {
    const { fixture, map } = await mount();
    fixture.componentInstance.mapZoomIn();
    expect(map.zoomIn).toHaveBeenCalledTimes(1);
  });

  it('zoom-out calls map.zoomOut()', async () => {
    const { fixture, map } = await mount();
    fixture.componentInstance.mapZoomOut();
    expect(map.zoomOut).toHaveBeenCalledTimes(1);
  });

  it('Fit calls map.fitBounds with the extent that contains all site markers', async () => {
    const { fixture, map } = await mount();
    fixture.componentInstance.mapFit();
    expect(map.fitBounds).toHaveBeenCalledTimes(1);
    const bounds = map.fitBounds.mock.calls[0][0] as [number, number, number, number];
    // [W,S,E,N] — a finite, non-degenerate box (the seeded sites' padded extent), not the empty default.
    expect(bounds.length).toBe(4);
    expect(bounds.every((n) => Number.isFinite(n))).toBe(true);
    expect(bounds[2]).toBeGreaterThan(bounds[0]); // E > W
    expect(bounds[3]).toBeGreaterThan(bounds[1]); // N > S
  });

  it('Reset calls map.fitBounds (restores zoom + centre to the initial default view)', async () => {
    const { fixture, map } = await mount();
    fixture.componentInstance.mapReset();
    expect(map.fitBounds).toHaveBeenCalledTimes(1);
  });

  it('all four controls are wired from the rendered buttons (in/out/fit/reset)', async () => {
    const { fixture, map } = await mount();
    const el: HTMLElement = fixture.nativeElement;
    (el.querySelector('[data-testid="map-zoom-in"]') as HTMLButtonElement).click();
    (el.querySelector('[data-testid="map-zoom-out"]') as HTMLButtonElement).click();
    (el.querySelector('[data-testid="map-zoom-fit"]') as HTMLButtonElement).click();
    (el.querySelector('[data-testid="map-zoom-reset"]') as HTMLButtonElement).click();
    expect(map.zoomIn).toHaveBeenCalledTimes(1);
    expect(map.zoomOut).toHaveBeenCalledTimes(1);
    expect(map.fitBounds).toHaveBeenCalledTimes(2); // fit + reset
  });
});
