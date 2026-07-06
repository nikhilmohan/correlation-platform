import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { StatsStore } from './stats.store';
import { StatsComponent } from './stats.component';
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
    expect(alarms.length).toBe(6);
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
    expect(s.visibleAlarms().length).toBe(6);
  });

  it('AC 47 — correlationGroups groups alarms by incident with the root cause first + children below', async () => {
    const s = store();
    s.loadIncidents();
    s.loadAlarms();
    await flush();

    const groups = s.correlationGroups();
    expect(groups.length).toBe(1);
    const g = groups[0];
    expect(g.incidentId).toBe('INC-12');

    // Root cause resolved and is the RCA-role alarm.
    expect(g.rootCause).not.toBeNull();
    expect(g.rootCause!.alarmId).toBe('a-3');
    expect(g.rootCause!.role).toBe('root-cause');

    // Children nested below, none of them the root cause.
    expect(g.children.map((c) => c.alarmId).sort()).toEqual(['a-7', 'a-8']);
    expect(g.children.every((c) => c.role === 'child')).toBe(true);
  });

  it('AC 47 — uncorrelated alarms (role=none / no incident) surface in a separate list, not in groups', async () => {
    const s = store();
    s.loadIncidents();
    s.loadAlarms();
    await flush();

    const uncorr = s.uncorrelatedAlarms();
    expect(uncorr.map((a) => a.alarmId).sort()).toEqual(['a-1', 'a-2', 'a-9']);
    expect(uncorr.every((a) => a.role === 'none')).toBe(true);
    // No uncorrelated alarm leaks into a correlation group.
    const grouped = s.correlationGroups().flatMap((g) => [g.rootCause, ...g.children]);
    expect(grouped.some((a) => a && a.role === 'none')).toBe(false);
  });

  it('AC 47 — a group with children but no live root-cause alarm degrades gracefully (placeholder RCA)', () => {
    const s = store();
    s.incidents.set([
      { incidentId: 'INC-99', rootCauseAlarmId: 'x', rootCauseAlarmType: 'LOS', childAlarmIds: ['c-1'] } as never,
    ]);
    s.alarms.set([
      { alarmId: 'c-1', managedObjectId: 'IPLink:z', eventType: 'LinkDown', lifecycleState: 'correlated', role: 'child', incidentId: 'INC-99' } as never,
    ]);
    const groups = s.correlationGroups();
    expect(groups.length).toBe(1);
    expect(groups[0].rootCause).toBeNull();
    // Falls back to the incident-declared root-cause alarm type.
    expect(groups[0].rootCauseAlarmType).toBe('LOS');
    expect(groups[0].children.map((c) => c.alarmId)).toEqual(['c-1']);
  });

  it('AC 48 — the lifecycle-state filter still applies within correlation groups', async () => {
    const s = store();
    s.loadIncidents();
    s.loadAlarms();
    await flush();

    // Filtering to a state with no correlated alarms empties the groups.
    s.setAlarmFilter('open');
    expect(s.correlationGroups().length).toBe(0);
    expect(s.uncorrelatedAlarms().every((a) => a.lifecycleState === 'open')).toBe(true);

    // Filtering to correlated keeps only the INC-12 group.
    s.setAlarmFilter('correlated');
    expect(s.correlationGroups().length).toBe(1);
    expect(s.correlationGroups()[0].incidentId).toBe('INC-12');
  });
});

describe('Stats timestamps + most-recent-first ordering', () => {
  it('sortedIncidents orders incidents by createdAt DESCENDING (fixture is stored out of order)', async () => {
    const s = store();
    s.loadIncidents();
    await flush();
    // Fixture array order is [INC-11 (11:50), INC-12 (12:00)]; sorted view must flip it.
    expect(s.incidents().map((i) => i.incidentId)).toEqual(['INC-11', 'INC-12']);
    expect(s.sortedIncidents().map((i) => i.incidentId)).toEqual(['INC-12', 'INC-11']);
  });

  it('sortedIncidents pushes incidents with no createdAt to the end', () => {
    const s = store();
    s.incidents.set([
      { incidentId: 'B', createdAt: undefined } as never,
      { incidentId: 'A', createdAt: '2026-06-01T12:00:00Z' } as never,
    ]);
    expect(s.sortedIncidents().map((i) => i.incidentId)).toEqual(['A', 'B']);
  });

  it('correlationGroups exposes a group timestamp (RCA raisedAt) and keeps children in raisedAt order', async () => {
    const s = store();
    s.loadIncidents();
    s.loadAlarms();
    await flush();
    const g = s.correlationGroups()[0];
    expect(g.groupRaisedAt).toBe('2026-06-01T12:00:00Z');
    // children a-7 (12:00:03) then a-8 (12:00:05) — ascending cascade order.
    expect(g.children.map((c) => c.alarmId)).toEqual(['a-7', 'a-8']);
  });

  it('uncorrelatedAlarms are ordered by raisedAt DESCENDING (most recent first)', async () => {
    const s = store();
    s.loadIncidents();
    s.loadAlarms();
    await flush();
    // Fixture raisedAt: a-2 (11:45) < a-1 (11:55) < a-9 (12:10); desc → a-9, a-1, a-2.
    expect(s.uncorrelatedAlarms().map((a) => a.alarmId)).toEqual(['a-9', 'a-1', 'a-2']);
  });

  it('renders incidents most-recent-first with a createdAt timestamp cell', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const cmp = TestBed.createComponent(StatsComponent);
    cmp.detectChanges();
    await flush();
    cmp.detectChanges();

    const rows = cmp.nativeElement.querySelectorAll('[data-testid="stats-incident"]');
    expect(rows.length).toBe(2);
    // Most-recent (INC-12) first despite the out-of-order fixture.
    expect(rows[0].textContent).toContain('INC-12');
    expect(rows[1].textContent).toContain('INC-11');
    // Each incident LEADS with a full absolute timestamp cell (dd MMM yy HH:mm:ss.SSS),
    // and the relative "… ago" form is kept only as the hover title.
    const ts = cmp.nativeElement.querySelectorAll('[data-testid="incident-created-at"]');
    expect(ts.length).toBe(2);
    expect((ts[0] as HTMLElement).textContent?.trim()).toMatch(
      /\d{2} \w{3} \d{2} \d{2}:\d{2}:\d{2}\.\d{3}/,
    );
    expect((ts[0] as HTMLElement).getAttribute('title')).toBeTruthy();
    // Timestamp is the FIRST field in the row (leads the incident id).
    const row0 = rows[0].textContent ?? '';
    expect(row0.search(/\d{2} \w{3} \d{2} \d{2}:\d{2}:\d{2}/)).toBeLessThan(row0.indexOf('INC-12'));
  });

  it('renders correlation groups most-recent-first with per-alarm raisedAt cells', async () => {
    TestBed.configureTestingModule({ providers: [...testProviders()] });
    const cmp = TestBed.createComponent(StatsComponent);
    cmp.detectChanges();
    await flush();
    // Switch to the alarm-lifecycle tab (loads alarms).
    (cmp.nativeElement.querySelector('[data-testid="tab-alarms"]') as HTMLButtonElement).click();
    cmp.detectChanges();
    await flush();
    cmp.detectChanges();

    // Group header LEADS with a full absolute timestamp (dd MMM yy HH:mm:ss.SSS).
    const groupTs = cmp.nativeElement.querySelector('[data-testid="group-raised-at"]');
    expect(groupTs).toBeTruthy();
    expect((groupTs as HTMLElement).textContent?.trim()).toMatch(
      /\d{2} \w{3} \d{2} \d{2}:\d{2}:\d{2}\.\d{3}/,
    );
    // Each alarm row shows a raisedAt cell (RCA + 2 children + 3 uncorrelated = 6),
    // each carrying the full absolute timestamp as its leading value.
    const alarmTs = cmp.nativeElement.querySelectorAll('[data-testid="alarm-raised-at"]');
    expect(alarmTs.length).toBe(6);
    expect((alarmTs[0] as HTMLElement).textContent?.trim()).toMatch(
      /\d{2} \w{3} \d{2} \d{2}:\d{2}:\d{2}\.\d{3}/,
    );
  });
});
