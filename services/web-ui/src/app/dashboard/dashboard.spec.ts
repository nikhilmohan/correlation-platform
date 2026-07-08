import { TestBed } from '@angular/core/testing';
import { Component } from '@angular/core';
import { Router, provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { describe, expect, it, vi } from 'vitest';
import { DashboardStore } from './dashboard.store';
import { DashboardComponent } from './dashboard.component';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { mockBackendInterceptor } from '../core/mock-backend.interceptor';
import { flush } from '../../test-utils';
import { GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

@Component({ template: '' })
class StubComponent {}

const ROUTES = [
  { path: '', pathMatch: 'full' as const, redirectTo: 'dashboard' },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'stats', component: StubComponent },
  { path: 'patterns', component: StubComponent },
];

function configure() {
  TestBed.configureTestingModule({
    providers: [DashboardStore, provideRouter(ROUTES), provideHttpClient(withInterceptors([mockBackendInterceptor]))],
  });
}

describe('Landing dashboard', () => {
  it('AC 2 — live incident count + active-pattern count match fixtures', async () => {
    configure();
    const s = TestBed.inject(DashboardStore);
    s.load();
    await flush();
    expect(s.incidentCount()).toBe(2);
    expect(s.activePatternCount()).toBe(1);
  });

  it('AC 3 — root path renders the dashboard as the default route', async () => {
    configure();
    const router = TestBed.inject(Router);
    await router.navigateByUrl('/');
    expect(router.url).toBe('/dashboard');
  });

  it('AC 4 — clicking the incident-count KPI navigates to the unified Alarms view', async () => {
    configure();
    const router = TestBed.inject(Router);
    const spy = vi.spyOn(router, 'navigate');
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const kpi = fixture.nativeElement.querySelector('[data-testid="kpi-incidents"]') as HTMLButtonElement;
    kpi.click();
    // Streaming + Stats merged into /alarms (Part 3); toStats() now targets it.
    expect(spy).toHaveBeenCalledWith(['/alarms']);
  });

  it('embeds the full topology & trails map below the KPIs (no recent-incidents / quick-links)', async () => {
    configure();
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    // The embedded topology view is the GeoSiteMapComponent, wrapped in the dashboard section.
    expect(el.querySelector('[data-testid="dashboard-topology"]')).toBeTruthy();
    expect(el.querySelector('app-geo-site-map')).toBeTruthy();
    // The removed sections must be gone.
    expect(el.querySelector('[data-testid="recent-incident"]')).toBeNull();
    expect(el.textContent).not.toMatch(/Quick links/i);
    // KPI testids are preserved.
    for (const id of ['kpi-incidents', 'kpi-patterns', 'kpi-processed', 'kpi-rca', 'kpi-autocorr']) {
      expect(el.querySelector(`[data-testid="${id}"]`)).toBeTruthy();
    }
  });

  it('swaps the map for the IN-PLACE site graph on site selection, and Close returns to the map', async () => {
    configure();
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const cmp = fixture.componentInstance;

    // Default: the geo-site MAP is shown, no site graph, no Close button.
    expect(el.querySelector('app-geo-site-map')).toBeTruthy();
    expect(el.querySelector('app-site-graph')).toBeNull();
    expect(el.querySelector('[data-testid="site-graph-close"]')).toBeNull();

    // Drill into a site (the map's (siteSelected) output) → the site graph fills the panel in-place.
    cmp.openSite('Site:LON');
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    expect(cmp.selectedSiteId()).toBe('Site:LON');
    expect(el.querySelector('app-site-graph')).toBeTruthy();
    expect(el.querySelector('app-geo-site-map')).toBeNull();
    const close = el.querySelector('[data-testid="site-graph-close"]') as HTMLButtonElement | null;
    expect(close).toBeTruthy();

    // Close → back to the map.
    close!.click();
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    expect(cmp.selectedSiteId()).toBeNull();
    expect(el.querySelector('app-geo-site-map')).toBeTruthy();
    expect(el.querySelector('app-site-graph')).toBeNull();
  });

  it('AC 57 — RCA accuracy: eval-mode value, else DIRECT rootCauseAlarmId exact join, else N/A', () => {
    const svc = new RcaAccuracyService();
    // Real P3 `/labels` shape (P3CascadeLabelModel): the ground-truth root-cause ALARM id is the join
    // key, matching the incident's rootCauseAlarmId directly — no alarm-by-id device resolution.
    // i1's rootCauseAlarmId (a1) IS a labelled root-cause alarm id → correct.
    // i2's rootCauseAlarmId (a2) IS labelled → correct.
    // i3's rootCauseAlarmId (a3) is NOT a labelled root-cause alarm id → miss (counts in denominator).
    const incidents: IncidentVM[] = [
      { incidentId: 'i1', rootCauseAlarmId: 'a1', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
      { incidentId: 'i2', rootCauseAlarmId: 'a2', rootCauseAlarmType: 'CardFail', childAlarmIds: [], confidence: 1, trailId: 't' },
      { incidentId: 'i3', rootCauseAlarmId: 'a3', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
    ];
    const labels: GroundTruthLabel[] = [
      { patternId: 'p1', trailId: 't', rootCauseAlarmId: 'a1', rootCauseAlarmType: 'LOS', childAlarmIds: [], scenarioType: 'f', instanceIndex: 0, igpArea: '0' },
      { patternId: 'p2', trailId: 't', rootCauseAlarmId: 'a2', rootCauseAlarmType: 'CardFail', childAlarmIds: [], scenarioType: 'c', instanceIndex: 1, igpArea: '0' },
    ];

    // eval path wins whenever stats.rcaAccuracy is a number.
    expect(svc.resolve({ rcaAccuracy: 0.86 } as StatsVM, incidents, labels)).toEqual({ value: 0.86, source: 'eval' });

    // client-side EXACT join on rootCauseAlarmId: denominator = total incidents (3);
    // numerator = incidents whose rootCauseAlarmId ∈ label set (i1, i2) = 2 → 2/3.
    const join = svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels);
    expect(join.value).toBeCloseTo(2 / 3);
    expect(join.source).toBe('client-side-join');

    // No labels → N/A.
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, null)).toEqual({ value: null, source: 'na' });

    // Empty label list → N/A.
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, [])).toEqual({ value: null, source: 'na' });
  });

  it('AC 57b — all incidents whose rootCauseAlarmId is labelled → 100%; a non-labelled id is a miss', () => {
    const svc = new RcaAccuracyService();
    const incidents: IncidentVM[] = [
      { incidentId: 'i1', rootCauseAlarmId: 'a1', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
      { incidentId: 'i2', rootCauseAlarmId: 'a2', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
    ];
    const labels: GroundTruthLabel[] = [
      { patternId: 'p1', trailId: 't', rootCauseAlarmId: 'a1', rootCauseAlarmType: 'LOS', childAlarmIds: [], scenarioType: 'f', instanceIndex: 0, igpArea: '0' },
      { patternId: 'p2', trailId: 't', rootCauseAlarmId: 'a2', rootCauseAlarmType: 'LOS', childAlarmIds: [], scenarioType: 'f', instanceIndex: 1, igpArea: '0' },
    ];
    // Every incident's rootCauseAlarmId is a labelled root-cause alarm id → 2/2 = 1.0 (the live 34/34 case).
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels).value).toBe(1);

    // Flip i2 to an UNLABELLED root-cause alarm id → it is a genuine miss → 1/2 = 0.5.
    const withMiss: IncidentVM[] = [incidents[0], { ...incidents[1], rootCauseAlarmId: 'unlabelled' }];
    const res = svc.resolve({ rcaAccuracy: null } as StatsVM, withMiss, labels);
    expect(res.value).toBeCloseTo(0.5);
    expect(res.source).toBe('client-side-join');
  });

  it('AC 58 — auto-correlation% = correlatedAlarmCount / totalAlarmsProcessed; N/A when zero processed', () => {
    configure();
    const s = TestBed.inject(DashboardStore);
    s.stats.set({ totalAlarmsProcessed: 1280, correlatedAlarmCount: 768, totalIncidentsCreated: 100 } as StatsVM);
    expect(s.autoCorrelationPct()).toBeCloseTo(0.6);
    s.stats.set({ totalAlarmsProcessed: 0, correlatedAlarmCount: 0, totalIncidentsCreated: 0 } as StatsVM);
    expect(s.autoCorrelationPct()).toBeNull();
  });
});
