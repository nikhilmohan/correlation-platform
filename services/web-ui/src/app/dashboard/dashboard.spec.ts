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
import { AlarmSummary, GroundTruthLabel, IncidentVM, StatsVM } from '../api/models';

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
    for (const id of ['kpi-incidents', 'kpi-patterns', 'kpi-dedup', 'kpi-processed', 'kpi-rca', 'kpi-autocorr']) {
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

  it('AC 57 — RCA accuracy: eval-mode value, else PER-INCIDENT EXACT device+type join, else N/A', () => {
    const svc = new RcaAccuracyService();
    const alarm = (id: string, mo: string, type: string): AlarmSummary => ({
      alarmId: id,
      managedObjectId: mo,
      eventType: type,
      alarmType: type,
      lifecycleState: 'correlated',
      role: 'root-cause',
    });
    // i1's RCA alarm is device m1 / LOS — EXACTLY matches label sc-1 → counts.
    // i2's RCA alarm is device m2 / LinkDown — device m2 IS labelled (sc-2) but the TYPE is wrong
    //   (label sc-2 is CardFail) → covered by a label but NOT correct.
    // i3's RCA alarm is device m9 — NO label covers it → excluded from BOTH num and denom.
    const incidents: IncidentVM[] = [
      { incidentId: 'i1', rootCauseAlarmId: 'a1', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
      { incidentId: 'i2', rootCauseAlarmId: 'a2', rootCauseAlarmType: 'LinkDown', childAlarmIds: [], confidence: 1, trailId: 't' },
      { incidentId: 'i3', rootCauseAlarmId: 'a3', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
    ];
    const rcaAlarms = new Map<string, AlarmSummary>([
      ['a1', alarm('a1', 'm1', 'LOS')],
      ['a2', alarm('a2', 'm2', 'LinkDown')],
      ['a3', alarm('a3', 'm9', 'LOS')],
    ]);
    const labels: GroundTruthLabel[] = [
      { scenarioId: 'sc-1', scenarioType: 'f', rootCause: 'm1', rootCauseManagedObjectId: 'm1', rootCauseAlarmType: 'LOS', children: [] },
      { scenarioId: 'sc-2', scenarioType: 'c', rootCause: 'm2', rootCauseManagedObjectId: 'm2', rootCauseAlarmType: 'CardFail', children: [] },
    ];

    // eval path wins whenever stats.rcaAccuracy is a number.
    expect(svc.resolve({ rcaAccuracy: 0.86 } as StatsVM, incidents, labels, rcaAlarms)).toEqual({ value: 0.86, source: 'eval' });

    // client-side EXACT join: denominator = incidents whose device IS labelled (i1, i2) = 2;
    // numerator = incidents whose device AND type match a label (only i1) = 1 → 1/2 = 0.5. i3 excluded.
    const join = svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels, rcaAlarms);
    expect(join.value).toBeCloseTo(0.5);
    expect(join.source).toBe('client-side-join');

    // No labels → N/A.
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, null, rcaAlarms)).toEqual({ value: null, source: 'na' });

    // Labels present but NONE cover any incident's device (empty rca-alarm map → no device resolvable) → N/A.
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels, new Map())).toEqual({ value: null, source: 'na' });
  });

  it('AC 57b — an incident with a NON-matching device id does NOT count (exact join, not type-membership)', () => {
    const svc = new RcaAccuracyService();
    const rcaAlarm: AlarmSummary = {
      alarmId: 'a1',
      managedObjectId: 'wrong-device',
      eventType: 'LOS',
      alarmType: 'LOS',
      lifecycleState: 'correlated',
      role: 'root-cause',
    };
    const incidents: IncidentVM[] = [
      { incidentId: 'i1', rootCauseAlarmId: 'a1', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
    ];
    // The label's TYPE (LOS) is in the incident's type set — the OLD loose check would count this — but
    // its DEVICE differs, so the EXACT join excludes it from the denominator entirely → N/A (0 covered).
    const labels: GroundTruthLabel[] = [
      { scenarioId: 'sc-1', scenarioType: 'f', rootCause: 'right-device', rootCauseManagedObjectId: 'right-device', rootCauseAlarmType: 'LOS', children: [] },
    ];
    const res = svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels, new Map([['a1', rcaAlarm]]));
    expect(res).toEqual({ value: null, source: 'na' });
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
