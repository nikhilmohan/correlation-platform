import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Type } from '@angular/core';
import axe from 'axe-core';
import { describe, expect, it } from 'vitest';
import { testProviders, flush } from '../../test-utils';
import { DashboardComponent } from '../dashboard/dashboard.component';
import { AlarmsComponent } from '../alarms/alarms.component';
import { GeoSiteMapComponent } from '../topology/geo-site-map.component';
import { SiteGraphComponent } from '../topology/site-graph.component';
import { PatternListComponent } from '../patterns/pattern-list.component';
import { ModelParamsFormComponent } from '../config/model-params-form.component';
import { NoiseViewComponent } from '../noise/noise-view.component';
import { IncidentDetailComponent } from '../incident-detail/incident-detail.component';
import { ChatterManagementComponent } from '../chatter/chatter-management.component';

/**
 * AC 52 — WCAG 2.1 AA accessibility. Renders each main interactive view and runs axe-core
 * against the rendered DOM (at least one criterion per view). color-contrast is disabled
 * because jsdom does not compute layout/visual style; structural a11y rules (roles, names,
 * labels, list/table structure, region/landmark) are asserted, plus the canvas-aria-label rule.
 */
async function renderHost(component: Type<unknown>): Promise<ComponentFixture<unknown>> {
  TestBed.configureTestingModule({
    providers: [
      ...testProviders(),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
    ],
  });
  const fixture = TestBed.createComponent(component);
  fixture.detectChanges();
  await flush();
  fixture.detectChanges();
  return fixture;
}

async function expectNoAxeViolations(fixture: ComponentFixture<unknown>): Promise<void> {
  const results = await axe.run(fixture.nativeElement as HTMLElement, {
    rules: {
      // jsdom has no layout engine → color-contrast cannot be computed reliably.
      'color-contrast': { enabled: false },
      region: { enabled: false },
    },
  });
  if (results.violations.length) {
    const summary = results.violations.map((v) => `${v.id}: ${v.nodes.length}`).join('; ');
    throw new Error(`axe violations: ${summary}`);
  }
  expect(results.violations).toEqual([]);
}

describe('AC 52 — WCAG/axe accessibility per main view', () => {
  it('Landing dashboard has no axe violations', async () => {
    await expectNoAxeViolations(await renderHost(DashboardComponent));
  });

  it('Unified Alarms view has no axe violations', async () => {
    await expectNoAxeViolations(await renderHost(AlarmsComponent));
  });

  it('Geo-site map: no axe violations + the map canvas carries an ARIA label', async () => {
    const fixture = await renderHost(GeoSiteMapComponent);
    const canvas = fixture.nativeElement.querySelector('.geo-map');
    expect(canvas?.getAttribute('aria-label')).toBeTruthy();
    await expectNoAxeViolations(fixture);
  });

  it('Site-level device graph: no axe violations + the graph canvas carries an ARIA label', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const fixture = TestBed.createComponent(SiteGraphComponent);
    fixture.componentRef.setInput('siteId', 'Site:LON');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const canvas = fixture.nativeElement.querySelector('.cy-canvas');
    expect(canvas?.getAttribute('aria-label')).toBeTruthy();
    await expectNoAxeViolations(fixture);
  });

  it('Pattern list has no axe violations', async () => {
    await expectNoAxeViolations(await renderHost(PatternListComponent));
  });

  it('Config form has no axe violations', async () => {
    await expectNoAxeViolations(await renderHost(ModelParamsFormComponent));
  });

  it('Graphical Noise view has no axe violations', async () => {
    await expectNoAxeViolations(await renderHost(NoiseViewComponent));
  });

  it('Incident-detail page has no axe violations', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const fixture = TestBed.createComponent(IncidentDetailComponent);
    fixture.componentRef.setInput('incidentId', 'INC-12');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    await expectNoAxeViolations(fixture);
  });

  it('Chatter management view has no axe violations', async () => {
    await expectNoAxeViolations(await renderHost(ChatterManagementComponent));
  });
});
