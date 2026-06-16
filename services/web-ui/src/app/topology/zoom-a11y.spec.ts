import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import axe from 'axe-core';
import { describe, expect, it, vi } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { SiteGraphComponent } from './site-graph.component';
import { GeoSiteMapComponent } from './geo-site-map.component';

/**
 * AC 75 — every zoom control (zoom in, zoom out, fit, reset) on the device graph is reachable and
 * activatable by keyboard alone: each is a real focusable <button> with an aria-label inside a
 * role="group", and Enter/Space activates its handler (native button semantics → click on
 * Enter/Space). Complements the axe pass in a11y.spec.ts. Would FAIL if a control became a
 * non-button (no keyboard activation), lost its aria-label, or its handler was unwired.
 */

const GRAPH_CONTROLS: { testid: string; method: keyof SiteGraphComponent }[] = [
  { testid: 'zoom-in', method: 'zoomIn' },
  { testid: 'zoom-out', method: 'zoomOut' },
  { testid: 'zoom-fit', method: 'fit' },
  { testid: 'zoom-reset', method: 'reset' },
];

async function mountGraph(): Promise<ComponentFixture<SiteGraphComponent>> {
  TestBed.configureTestingModule({ providers: [...testProviders()] });
  const fixture = TestBed.createComponent(SiteGraphComponent);
  fixture.componentRef.setInput('siteId', 'Site:LON');
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

async function mountMap(): Promise<ComponentFixture<GeoSiteMapComponent>> {
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
  return fixture;
}

describe('AC 75 — zoom controls are keyboard-accessible (focus + Enter/Space activation)', () => {
  it('the graph zoom controls live in a labelled role="group"', async () => {
    const fixture = await mountGraph();
    const group = fixture.nativeElement.querySelector('.cy-controls') as HTMLElement;
    expect(group?.getAttribute('role')).toBe('group');
    expect(group?.getAttribute('aria-label')).toBeTruthy();
  });

  for (const { testid, method } of GRAPH_CONTROLS) {
    it(`graph control "${testid}" is a focusable <button> with an aria-label`, async () => {
      const fixture = await mountGraph();
      const btn = fixture.nativeElement.querySelector(`[data-testid="${testid}"]`) as HTMLButtonElement;
      expect(btn).not.toBeNull();
      expect(btn.tagName).toBe('BUTTON');
      // Not removed from the tab order (no negative tabindex) — Tab-reachable.
      expect(btn.getAttribute('tabindex')).not.toBe('-1');
      expect(btn.getAttribute('aria-label')).toBeTruthy();
      // Focusable.
      btn.focus();
      expect(document.activeElement).toBe(btn);
    });

    it(`graph control "${testid}" activates its handler via keyboard (button click semantics)`, async () => {
      const fixture = await mountGraph();
      const btn = fixture.nativeElement.querySelector(`[data-testid="${testid}"]`) as HTMLButtonElement;
      const spy = vi.spyOn(fixture.componentInstance, method as never);
      // A native <button> fires `click` on Enter/Space; dispatching click is the DOM-equivalent of
      // the keyboard activation a screen-reader / keyboard user triggers (no mouse required).
      btn.click();
      expect(spy).toHaveBeenCalledTimes(1);
    });
  }

  it('the graph zoom-controls group has no axe violations', async () => {
    const fixture = await mountGraph();
    const group = fixture.nativeElement.querySelector('.cy-controls') as HTMLElement;
    const results = await axe.run(group, { rules: { 'color-contrast': { enabled: false }, region: { enabled: false } } });
    expect(results.violations).toEqual([]);
  });

  it('the map zoom controls are also keyboard-accessible buttons with aria-labels (canvas symmetry)', async () => {
    const fixture = await mountMap();
    for (const testid of ['map-zoom-in', 'map-zoom-out', 'map-zoom-fit', 'map-zoom-reset']) {
      const btn = fixture.nativeElement.querySelector(`[data-testid="${testid}"]`) as HTMLButtonElement;
      expect(btn, testid).not.toBeNull();
      expect(btn.tagName).toBe('BUTTON');
      expect(btn.getAttribute('aria-label')).toBeTruthy();
      btn.focus();
      expect(document.activeElement).toBe(btn);
    }
  });
});
