import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { DashboardStore } from './dashboard.store';
import { mockBackendInterceptor } from '../core/mock-backend.interceptor';
import { SynthSummaryModel } from '../api/models';

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

/**
 * The dashboard's former "alarm reduction" card was REPURPOSED to the same "Dedup reduction" card the
 * Alarms header shows (emitted → kept, % deduped), backed by the shared `computeDedupReduction` helper.
 * These assert the dashboard store exposes that same computed with the same guards.
 */
describe('Dashboard dedup-reduction KPI (repurposed from alarm-reduction)', () => {
  const summary = (alarmsEmitted: number): SynthSummaryModel => ({ alarmsEmitted } as SynthSummaryModel);

  it('emitted → kept with a valid single-run basis yields the % deduped', () => {
    const store = setup();
    store.synthSummary.set(summary(200));
    store.alarmCount.set(187);
    const d = store.dedupReduction();
    expect(d.emitted).toBe(200);
    expect(d.kept).toBe(187);
    expect(d.deduped).toBe(13);
    expect(d.fraction).toBeCloseTo(13 / 200);
  });

  it('no run summary this session → emitted null, no fraction (card shows kept-only / —)', () => {
    const store = setup();
    store.alarmCount.set(187);
    const d = store.dedupReduction();
    expect(d.emitted).toBeNull();
    expect(d.fraction).toBeNull();
    expect(d.kept).toBe(187);
  });

  it('kept > emitted → NO negative %/deduped (guard: counts not on a single-run basis)', () => {
    const store = setup();
    store.synthSummary.set(summary(50)); // latest run only
    store.alarmCount.set(300); // spans prior runs → kept > emitted
    const d = store.dedupReduction();
    expect(d.fraction).toBeNull();
    expect(d.deduped).toBeNull();
    // kept is still surfaced so the card can show "300 kept" rather than a bogus ratio.
    expect(d.kept).toBe(300);
  });
});
