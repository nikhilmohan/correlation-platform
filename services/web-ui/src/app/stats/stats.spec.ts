import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { StatsStore } from './stats.store';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { testProviders, flush } from '../../test-utils';
import { AlarmSummary, RunStatsRow, StatsVM } from '../api/models';

function store(): StatsStore {
  TestBed.configureTestingModule({
    providers: [StatsStore, RcaAccuracyService, ...testProviders()],
  });
  return TestBed.inject(StatsStore);
}

describe('Noise-filter run-stats view (correlation-stats learning sub-view)', () => {
  it('AC 18 — renders each run row + derived storm-reduction ratio (alarmsIn / clustersFormed)', async () => {
    const s = store();
    s.loadRunStats();
    await flush();
    const rows = s.runStats();
    // mock fixture ships >= 2 run rows
    expect(rows.length).toBeGreaterThanOrEqual(2);
    const run9 = rows.find((r) => r.runId === 'RUN-9')!;
    expect(run9.trailId).toBe('TR-7');
    expect(run9.alarmsIn).toBe(240);
    expect(run9.clustersFormed).toBe(12);
    expect(run9.alarmsKept).toBe(180);
    expect(run9.alarmsDropped).toBe(60);
    expect(run9.noiseRatio).toBeCloseTo(0.25);
    // storm-reduction = alarmsIn / clustersFormed = 240 / 12 = 20
    expect(s.stormReduction(run9)).toBeCloseTo(20);
  });

  it('AC 18 — storm-reduction guards divide-by-zero (clustersFormed = 0 → null)', () => {
    const s = store();
    const row = { runId: 'R', alarmsIn: 10, clustersFormed: 0 } as RunStatsRow;
    expect(s.stormReduction(row)).toBeNull();
  });

  it('AC 19 — applying a trailId filter shows only matching rows; other trailIds are not rendered', async () => {
    const s = store();
    s.loadRunStats('TR-8');
    await flush();
    const rows = s.runStats();
    expect(rows.length).toBeGreaterThanOrEqual(1);
    expect(rows.every((r) => r.trailId === 'TR-8')).toBe(true);
    expect(rows.some((r) => r.trailId === 'TR-7')).toBe(false);
    expect(s.runStatsTrailFilter()).toBe('TR-8');
  });
});

describe('Correlation stats module + alarm lifecycle', () => {
  it('AC 44 — renders each incident with root-cause + child alarms', async () => {
    const s = store();
    s.loadIncidents();
    await flush();
    const incidents = s.incidents();
    expect(incidents.length).toBe(2);
    const inc = incidents.find((i) => i.incidentId === 'INC-12')!;
    expect(inc.rootCauseAlarmId).toBe('a-3');
    expect(inc.childAlarmIds).toEqual(['a-7', 'a-8']);
  });

  it('AC 45 — alarm-reduction ratio = totalAlarmsProcessed / totalIncidentsCreated as a numeric value', async () => {
    const s = store();
    s.loadStats();
    await flush();
    // fixture: 1280 / 154
    expect(s.alarmReductionRatio()).toBeCloseTo(1280 / 154);
    expect(typeof s.alarmReductionRatio()).toBe('number');
  });

  it('AC 45 — ratio is N/A (null) when totalIncidentsCreated is zero', () => {
    const s = store();
    s.stats.set({ totalAlarmsProcessed: 100, totalIncidentsCreated: 0 } as StatsVM);
    expect(s.alarmReductionRatio()).toBeNull();
  });

  it('AC 47 — alarm-lifecycle list shows each alarm state, role, and incident association', async () => {
    const s = store();
    s.loadAlarms();
    await flush();
    const alarms = s.alarms();
    expect(alarms.length).toBe(5);
    const states = new Set(alarms.map((a) => a.lifecycleState));
    // fixture covers open / in-progress / correlated / cleared
    expect(states.has('open')).toBe(true);
    expect(states.has('in-progress')).toBe(true);
    expect(states.has('correlated')).toBe(true);
    expect(states.has('cleared')).toBe(true);
    const rc = alarms.find((a) => a.alarmId === 'a-3')!;
    expect(rc.role).toBe('root-cause');
    expect(rc.incidentId).toBe('INC-12');
  });

  it('AC 48 — selecting a lifecycle state filters the list to only that state', async () => {
    const s = store();
    s.loadAlarms();
    await flush();
    s.setAlarmFilter('in-progress');
    const visible = s.visibleAlarms();
    expect(visible.length).toBeGreaterThanOrEqual(1);
    expect(visible.every((a: AlarmSummary) => a.lifecycleState === 'in-progress')).toBe(true);

    s.setAlarmFilter('correlated');
    expect(s.visibleAlarms().every((a) => a.lifecycleState === 'correlated')).toBe(true);

    s.setAlarmFilter('all');
    expect(s.visibleAlarms().length).toBe(5);
  });
});
