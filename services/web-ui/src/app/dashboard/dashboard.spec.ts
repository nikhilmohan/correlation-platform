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

  it('AC 4 — clicking the incident-count KPI navigates to stats', async () => {
    configure();
    const router = TestBed.inject(Router);
    const spy = vi.spyOn(router, 'navigate');
    const fixture = TestBed.createComponent(DashboardComponent);
    fixture.detectChanges();
    await flush();
    fixture.detectChanges();
    const kpi = fixture.nativeElement.querySelector('[data-testid="kpi-incidents"]') as HTMLButtonElement;
    kpi.click();
    expect(spy).toHaveBeenCalledWith(['/stats']);
  });

  it('AC 57 — RCA accuracy: eval-mode value, else client-side join, else N/A', () => {
    const svc = new RcaAccuracyService();
    const incidents: IncidentVM[] = [
      { incidentId: 'i1', rootCauseAlarmId: 'a', rootCauseAlarmType: 'LOS', childAlarmIds: [], confidence: 1, trailId: 't' },
      { incidentId: 'i2', rootCauseAlarmId: 'b', rootCauseAlarmType: 'X', childAlarmIds: [], confidence: 1, trailId: 't' },
    ];
    const labels: GroundTruthLabel[] = [
      { scenarioId: 's', scenarioType: 'f', rootCause: 'r', rootCauseManagedObjectId: 'm', rootCauseAlarmType: 'LOS', children: [] },
    ];
    expect(svc.resolve({ rcaAccuracy: 0.86 } as StatsVM, incidents, labels)).toEqual({ value: 0.86, source: 'eval' });
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels).value).toBeCloseTo(0.5);
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, labels).source).toBe('client-side-join');
    expect(svc.resolve({ rcaAccuracy: null } as StatsVM, incidents, null)).toEqual({ value: null, source: 'na' });
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
