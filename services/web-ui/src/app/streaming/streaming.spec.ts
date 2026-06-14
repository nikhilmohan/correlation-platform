import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { of } from 'rxjs';
import { LivePollingService } from './live-polling.service';
import { DeltaDiffService } from './delta-diff';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { ApiConfigService } from '../core/api-config.service';
import { AlarmPage, AlarmSummary, IncidentPage } from '../api/models';

function alarmPage(items: AlarmSummary[]): AlarmPage {
  return { items, total: items.length, limit: 50, offset: 0 };
}
const emptyIncidents: IncidentPage = { items: [], total: 0, limit: 50, offset: 0 };

function makeAlarm(alarmId: string, state: AlarmSummary['lifecycleState']): AlarmSummary {
  return { alarmId, managedObjectId: 'mo', eventType: 'E', lifecycleState: state, role: 'none', incidentId: null, trailIds: [] };
}

class FakeAm {
  listAlarmsCalls = 0;
  responses: AlarmSummary[][] = [];
  listAlarms() {
    const r = this.responses[Math.min(this.listAlarmsCalls, this.responses.length - 1)] ?? [];
    this.listAlarmsCalls += 1;
    return of(alarmPage(r));
  }
  getAlarm() {
    return of({ ...makeAlarm('a', 'open'), transitions: [] });
  }
}
class FakeCe {
  listIncidentsCalls = 0;
  listIncidents() {
    this.listIncidentsCalls += 1;
    return of(emptyIncidents);
  }
  getIncident() {
    return of(emptyIncidents.items[0]);
  }
  getStats() {
    return of({ totalAlarmsProcessed: 0, totalIncidentsCreated: 0 });
  }
}

function setup(intervalMs = 3000): { svc: LivePollingService; am: FakeAm; ce: FakeCe } {
  const am = new FakeAm();
  const ce = new FakeCe();
  TestBed.configureTestingModule({
    providers: [
      LivePollingService,
      DeltaDiffService,
      { provide: AlarmManagerClient, useValue: am },
      { provide: CorrelationEngineClient, useValue: ce },
      { provide: ApiConfigService, useValue: { streamingRefreshIntervalMs: intervalMs } },
    ],
  });
  return { svc: TestBed.inject(LivePollingService), am, ce };
}

describe('Real-time streaming view', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('AC 6 — polls GET /alarms every T ms; no extra call between ticks', () => {
    const { svc, am } = setup(3000);
    svc.start(); // immediate tick
    expect(am.listAlarmsCalls).toBe(1);
    vi.advanceTimersByTime(2999);
    expect(am.listAlarmsCalls).toBe(1); // no call before T
    vi.advanceTimersByTime(1);
    expect(am.listAlarmsCalls).toBe(2);
    vi.advanceTimersByTime(3000);
    expect(am.listAlarmsCalls).toBe(3);
  });

  it('AC 7 — a new alarm between polls gets a NEW indicator; unchanged gets none', () => {
    const { svc, am } = setup();
    am.responses = [[makeAlarm('a-1', 'open')], [makeAlarm('a-1', 'open'), makeAlarm('a-4', 'open')]];
    svc.start();
    vi.advanceTimersByTime(3000);
    const deltas = svc.alarmDeltas();
    expect(deltas.find((d) => d.alarmId === 'a-4')?.kind).toBe('NEW');
    expect(deltas.find((d) => d.alarmId === 'a-1')?.kind).toBe('UNCHANGED');
  });

  it('AC 8 — open → in-progress between polls updates the row + CHANGED indicator', () => {
    const { svc, am } = setup();
    am.responses = [[makeAlarm('a-2', 'open')], [makeAlarm('a-2', 'in-progress')]];
    svc.start();
    vi.advanceTimersByTime(3000);
    const d = svc.alarmDeltas().find((x) => x.alarmId === 'a-2');
    expect(d?.kind).toBe('CHANGED');
    expect(d?.currentState).toBe('in-progress');
  });

  it('AC 9 — in-progress → correlated and revert-back-to-open both reflected without reload', () => {
    const { svc, am } = setup();
    am.responses = [
      [makeAlarm('a-3', 'in-progress'), makeAlarm('a-5', 'in-progress')],
      [makeAlarm('a-3', 'correlated'), makeAlarm('a-5', 'open')],
    ];
    svc.start();
    vi.advanceTimersByTime(3000);
    const d = svc.alarmDeltas();
    expect(d.find((x) => x.alarmId === 'a-3')?.currentState).toBe('correlated');
    expect(d.find((x) => x.alarmId === 'a-3')?.kind).toBe('CHANGED');
    expect(d.find((x) => x.alarmId === 'a-5')?.currentState).toBe('open');
    expect(d.find((x) => x.alarmId === 'a-5')?.kind).toBe('CHANGED');
  });

  it('AC 10 — pause stops all polling to Alarm Manager and Correlation Engine', () => {
    const { svc, am, ce } = setup();
    svc.start();
    const a = am.listAlarmsCalls;
    const c = ce.listIncidentsCalls;
    svc.pause();
    vi.advanceTimersByTime(9000);
    expect(am.listAlarmsCalls).toBe(a);
    expect(ce.listIncidentsCalls).toBe(c);
  });

  it('AC 11 — resume restarts polling at the configured interval', () => {
    const { svc, am } = setup(3000);
    svc.start();
    svc.pause();
    const before = am.listAlarmsCalls;
    svc.resume(); // immediate tick on resume
    expect(am.listAlarmsCalls).toBe(before + 1);
    vi.advanceTimersByTime(3000);
    expect(am.listAlarmsCalls).toBe(before + 2);
  });

  it('AC 12 — env STREAMING_REFRESH_INTERVAL_MS=10000 polls at 10000 not 3000', () => {
    const { svc, am } = setup(10000);
    expect(svc.intervalMs()).toBe(10000);
    svc.start();
    expect(am.listAlarmsCalls).toBe(1);
    vi.advanceTimersByTime(3000);
    expect(am.listAlarmsCalls).toBe(1); // not yet
    vi.advanceTimersByTime(7000);
    expect(am.listAlarmsCalls).toBe(2);
  });
});

describe('DeltaDiffService', () => {
  it('incident GREW when childAlarmIds length increases', () => {
    const diff = new DeltaDiffService();
    const prev = [{ incidentId: 'INC-1', rootCauseAlarmId: 'r', childAlarmIds: ['c1'], confidence: 1, trailId: 't' }];
    const cur = [{ incidentId: 'INC-1', rootCauseAlarmId: 'r', childAlarmIds: ['c1', 'c2'], confidence: 1, trailId: 't' }];
    expect(diff.diffIncidents(prev, cur)[0].kind).toBe('GREW');
  });
});
