import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { AlarmsStore } from './alarms.store';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { SimulatorLabelsClient } from '../api/simulator-labels.client';
import { SimulatorClient } from '../api/simulator.client';
import { RcaAccuracyService } from '../core/rca-accuracy.service';
import { flush } from '../../test-utils';
import {
  AlarmDetail,
  AlarmPage,
  AlarmSummary,
  GroundTruthLabel,
  IncidentPage,
  IncidentVM,
  StatsVM,
  SynthStatusResponse,
} from '../api/models';

/**
 * Incident-FIRST load-path tests (the bug fix). The store must NOT rely on the flat `/alarms`
 * window (which returns only the freshest uncorrelated tail). It fetches incidents, resolves each
 * incident's RCA + child alarms by id, and merges them with the recent open tail. These tests drive
 * the store with hand-built stub clients so the incident list, the by-id map (incl. a 404) and the
 * open tail are all under direct control.
 */

function alarm(partial: Partial<AlarmSummary> & { alarmId: string }): AlarmSummary {
  return {
    managedObjectId: 'mo-x',
    eventType: 'LinkDown',
    perceivedSeverity: 'major',
    raisedAt: '2026-06-01T12:00:00Z',
    lifecycleState: 'correlated',
    role: 'child',
    incidentId: null,
    trailIds: [],
    ...partial,
  };
}

function detail(a: AlarmSummary): AlarmDetail {
  return { ...a, transitions: [] };
}

const INCIDENTS: IncidentVM[] = [
  {
    incidentId: 'INC-A',
    rootCauseAlarmId: 'rc-a',
    rootCauseAlarmType: 'LOS',
    childAlarmIds: ['ch-a1', 'ch-a2'],
    matchedPatternId: 'PAT-1',
    confidence: 0.9,
    trailId: 'TR-1',
    createdAt: '2026-06-01T12:00:00Z',
  },
  {
    incidentId: 'INC-B',
    rootCauseAlarmId: 'rc-b',
    rootCauseAlarmType: 'CardFail',
    childAlarmIds: ['ch-b1'],
    matchedPatternId: null,
    confidence: 0.7,
    trailId: 'TR-2',
    createdAt: '2026-06-01T11:00:00Z',
  },
];

const RCA_A = alarm({ alarmId: 'rc-a', role: 'root-cause', incidentId: 'INC-A', perceivedSeverity: 'critical', alarmType: 'LOS', raisedAt: '2026-06-01T12:00:00Z' });
const CH_A1 = alarm({ alarmId: 'ch-a1', role: 'child', incidentId: 'INC-A', raisedAt: '2026-06-01T12:00:05Z' });
const CH_A2 = alarm({ alarmId: 'ch-a2', role: 'child', incidentId: 'INC-A', raisedAt: '2026-06-01T12:00:02Z' });
const RCA_B = alarm({ alarmId: 'rc-b', role: 'root-cause', incidentId: 'INC-B', alarmType: 'CardFail', raisedAt: '2026-06-01T11:00:00Z' });
const CH_B1 = alarm({ alarmId: 'ch-b1', role: 'child', incidentId: 'INC-B', raisedAt: '2026-06-01T11:00:03Z' });

const OPEN_TAIL: AlarmSummary[] = [
  alarm({ alarmId: 'open-1', role: 'none', incidentId: null, lifecycleState: 'open', perceivedSeverity: 'minor', raisedAt: '2026-06-01T13:00:00Z' }),
  alarm({ alarmId: 'open-2', role: 'none', incidentId: null, lifecycleState: 'in-progress', perceivedSeverity: 'warning', raisedAt: '2026-06-01T10:00:00Z' }),
];

/**
 * Build stub AM/CE clients over MUTABLE backing state so a test can simulate a poll tick that
 * changes the backend (e.g. an alarm becoming newly correlated). `byId` maps id → alarm; ids in
 * `notFound` (or absent from `byId`) throw a 404 from `getAlarm`, mirroring the real backend.
 */
function stubs(opts: {
  incidents?: IncidentVM[];
  byId?: Record<string, AlarmSummary>;
  notFound?: Set<string>;
  openTail?: AlarmSummary[];
}) {
  const state = {
    incidents: opts.incidents ?? INCIDENTS,
    byId: opts.byId ?? { 'rc-a': RCA_A, 'ch-a1': CH_A1, 'ch-a2': CH_A2, 'rc-b': RCA_B, 'ch-b1': CH_B1 },
    notFound: opts.notFound ?? new Set<string>(),
    openTail: opts.openTail ?? OPEN_TAIL,
  };

  const am: Partial<AlarmManagerClient> = {
    listAlarms: (): Observable<AlarmPage> =>
      of({ items: state.openTail, total: state.openTail.length, limit: 50, offset: 0 }),
    getAlarm: (id: string): Observable<AlarmDetail> => {
      if (state.notFound.has(id) || !state.byId[id]) {
        return throwError(() => new HttpErrorResponse({ status: 404, url: `/alarms/${id}` }));
      }
      return of(detail(state.byId[id]));
    },
    // Re-use the REAL fan-out semantics (concurrent + 404-resilient) over the stub getAlarm.
    getAlarms: AlarmManagerClient.prototype.getAlarms,
  };
  const ce: Partial<CorrelationEngineClient> = {
    listIncidents: (): Observable<IncidentPage> =>
      of({ items: state.incidents, total: state.incidents.length, limit: 200, offset: 0 }),
    getStats: (): Observable<StatsVM> =>
      of({ totalAlarmsProcessed: 1000, correlatedAlarmCount: 600, totalIncidentsCreated: 47, rcaAccuracy: 0.85 }),
  };
  const labelsSvc: Partial<SimulatorLabelsClient> = {
    listLabels: (): Observable<GroundTruthLabel[]> => of<GroundTruthLabel[]>([]),
  };
  const simSvc: Partial<SimulatorClient> = {
    getStatus: (): Observable<SynthStatusResponse> =>
      of({ status: 'idle', runId: null, progress: { alarmsEmitted: 0, alarmsTotal: 0, alignedEmitted: 0, nonAlignedEmitted: 0 }, summary: null }),
  };
  return { am, ce, labelsSvc, simSvc, state };
}

let currentStubs: ReturnType<typeof stubs>;

function store(opts: Parameters<typeof stubs>[0] = {}): AlarmsStore {
  currentStubs = stubs(opts);
  TestBed.configureTestingModule({
    providers: [
      AlarmsStore,
      RcaAccuracyService,
      { provide: AlarmManagerClient, useValue: currentStubs.am },
      { provide: CorrelationEngineClient, useValue: currentStubs.ce },
      { provide: SimulatorLabelsClient, useValue: currentStubs.labelsSvc },
      { provide: SimulatorClient, useValue: currentStubs.simSvc },
    ],
  });
  return TestBed.inject(AlarmsStore);
}

describe('AlarmsStore — INCIDENT-FIRST assembly (bug fix)', () => {
  it('produces one collapsible group per incident (RCA + nested children, state correlated) PLUS the open rows', async () => {
    const s = store();
    s.loadAll();
    await flush();

    const rows = s.rows();
    const groups = rows.filter((r) => r.kind === 'rca');
    // Two incidents -> two groups.
    expect(groups.map((g) => g.incidentId).sort()).toEqual(['INC-A', 'INC-B']);

    const gA = groups.find((g) => g.incidentId === 'INC-A')!;
    expect(gA.alarm.alarmId).toBe('rc-a'); // RCA highlighted as the group header row
    expect(gA.alarm.role).toBe('root-cause');
    expect(gA.alarm.lifecycleState).toBe('correlated');
    // Children nested under the RCA, ordered by their own raisedAt asc (ch-a2 12:00:02 before ch-a1 12:00:05).
    expect(gA.children.map((c) => c.alarmId)).toEqual(['ch-a2', 'ch-a1']);

    const gB = groups.find((g) => g.incidentId === 'INC-B')!;
    expect(gB.children.map((c) => c.alarmId)).toEqual(['ch-b1']);

    // The open tail renders as plain rows.
    const plain = rows.filter((r) => r.kind === 'plain').map((r) => r.alarm.alarmId).sort();
    expect(plain).toEqual(['open-1', 'open-2']);
  });

  it('top-level rows are timestamp-DESC and no alarm is duplicated across a group and the plain list', async () => {
    const s = store();
    s.loadAll();
    await flush();
    const rows = s.rows();

    const ts = rows.map((r) => (r.alarm.raisedAt ? Date.parse(r.alarm.raisedAt) : 0));
    for (let i = 1; i < ts.length; i++) {
      expect(ts[i - 1]).toBeGreaterThanOrEqual(ts[i]);
    }

    // No id appears both as a top-level row/child and elsewhere.
    const seen = new Set<string>();
    for (const r of rows) {
      expect(seen.has(r.alarm.alarmId)).toBe(false);
      seen.add(r.alarm.alarmId);
      for (const c of r.children) {
        expect(seen.has(c.alarmId)).toBe(false);
        seen.add(c.alarmId);
      }
    }
    // Every group + child + plain alarm accounted for exactly once.
    expect([...seen].sort()).toEqual(['ch-a1', 'ch-a2', 'ch-b1', 'open-1', 'open-2', 'rc-a', 'rc-b']);
  });

  it('an open-tail alarm that is ALSO a resolved group member is de-duped (shown only in the group)', async () => {
    // ch-a1 leaks into the open tail (a stale flat-window read) — it must NOT also be a plain row.
    const tailWithDupe = [...OPEN_TAIL, { ...CH_A1 }];
    const s = store({ openTail: tailWithDupe });
    s.loadAll();
    await flush();
    const rows = s.rows();
    const plainIds = rows.filter((r) => r.kind === 'plain').map((r) => r.alarm.alarmId);
    expect(plainIds).not.toContain('ch-a1');
    // ch-a1 still present as a child of INC-A.
    const gA = rows.find((r) => r.incidentId === 'INC-A')!;
    expect(gA.children.map((c) => c.alarmId)).toContain('ch-a1');
  });

  it('a by-id 404 for one child still renders the group (minus that child); load does not fail', async () => {
    const s = store({ notFound: new Set(['ch-a1']) });
    s.loadAll();
    await flush();
    const rows = s.rows();
    const gA = rows.find((r) => r.incidentId === 'INC-A')!;
    expect(gA).toBeTruthy();
    expect(gA.alarm.alarmId).toBe('rc-a'); // RCA intact
    // The missing child is skipped; the surviving child still renders.
    expect(gA.children.map((c) => c.alarmId)).toEqual(['ch-a2']);
    // The rest of the view is unaffected.
    expect(rows.some((r) => r.incidentId === 'INC-B')).toBe(true);
    expect(rows.filter((r) => r.kind === 'plain').length).toBe(2);
  });

  it('an incident whose RCA + all children 404 contributes no group but does not break the load', async () => {
    const s = store({ notFound: new Set(['rc-b', 'ch-b1']) });
    s.loadAll();
    await flush();
    const rows = s.rows();
    expect(rows.some((r) => r.incidentId === 'INC-B')).toBe(false);
    // INC-A + the open tail still render.
    expect(rows.some((r) => r.incidentId === 'INC-A')).toBe(true);
    expect(rows.filter((r) => r.kind === 'plain').length).toBe(2);
  });

  it('the lifecycle-state filter still works over the assembled incident-first rows', async () => {
    const s = store();
    s.loadAll();
    await flush();

    s.setStateFilter('correlated');
    let rows = s.rows();
    expect(rows.length).toBeGreaterThan(0);
    // Only correlated members survive -> the two groups, no open rows.
    expect(rows.every((r) => r.alarm.lifecycleState === 'correlated')).toBe(true);
    expect(rows.filter((r) => r.kind === 'rca').map((r) => r.incidentId).sort()).toEqual(['INC-A', 'INC-B']);

    s.setStateFilter('open');
    rows = s.rows();
    // Only the open plain alarm survives.
    expect(rows.map((r) => r.alarm.alarmId)).toEqual(['open-1']);
  });

  it('with zero incidents the view still shows the open tail (no groups)', async () => {
    const s = store({ incidents: [] });
    s.loadAll();
    await flush();
    const rows = s.rows();
    expect(rows.every((r) => r.kind === 'plain')).toBe(true);
    expect(rows.map((r) => r.alarm.alarmId).sort()).toEqual(['open-1', 'open-2']);
  });
});

describe('AlarmsStore — REAL-TIME incident-first (poll tick)', () => {
  it('a poll tick that reports an alarm as newly correlated moves it from plain -> its incident group', async () => {
    const s = store();
    s.loadAll();
    await flush();

    // Baseline: open-1 is a plain row, INC-A has 2 children.
    expect(s.rows().find((r) => r.incidentId === 'INC-A')!.children.length).toBe(2);
    expect(s.rows().some((r) => r.kind === 'plain' && r.alarm.alarmId === 'open-1')).toBe(true);

    // The backend correlates open-1 into INC-A: by-id now resolves it as a child, and INC-A lists it.
    currentStubs.state.byId['open-1'] = { ...OPEN_TAIL[0], role: 'child', incidentId: 'INC-A', lifecycleState: 'correlated' };
    const incA2: IncidentVM = { ...INCIDENTS[0], childAlarmIds: ['ch-a1', 'ch-a2', 'open-1'] };
    const nextIncidents = [incA2, INCIDENTS[1]];

    // Simulate a LivePollingService tick: fresh incident list + the (now shorter) raw open tail.
    s.applyLiveSnapshot([OPEN_TAIL[1]], nextIncidents);
    await flush();

    const rows = s.rows();
    const gA = rows.find((r) => r.incidentId === 'INC-A')!;
    // open-1 is now inside INC-A (resolved by id as a child), no longer a plain row.
    expect(gA.children.map((c) => c.alarmId)).toContain('open-1');
    expect(rows.some((r) => r.kind === 'plain' && r.alarm.alarmId === 'open-1')).toBe(false);
    // open-2 remains the sole plain row.
    expect(rows.filter((r) => r.kind === 'plain').map((r) => r.alarm.alarmId)).toEqual(['open-2']);
  });
});
