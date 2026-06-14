import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { DashboardStore } from './dashboard.store';
import { mockBackendInterceptor } from '../core/mock-backend.interceptor';
import { StatsVM } from '../api/models';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      DashboardStore,
      provideRouter([]),
      provideHttpClient(withInterceptors([mockBackendInterceptor])),
    ],
  });
  return TestBed.inject(DashboardStore);
}

describe('AC 1 — dashboard alarm-reduction ratio', () => {
  it('renders totalAlarmsProcessed / totalIncidentsCreated', () => {
    const store = setup();
    store.stats.set({ totalAlarmsProcessed: 1280, totalIncidentsCreated: 154 } as StatsVM);
    expect(store.alarmReductionRatio()).toBeCloseTo(1280 / 154);
  });

  it('shows N/A (null) when totalIncidentsCreated is zero', () => {
    const store = setup();
    store.stats.set({ totalAlarmsProcessed: 100, totalIncidentsCreated: 0 } as StatsVM);
    expect(store.alarmReductionRatio()).toBeNull();
  });
});
