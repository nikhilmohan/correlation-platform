import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ResetButtonComponent } from './reset-button.component';
import { TopologyStore } from '../topology/topology.store';
import { DashboardStore } from './dashboard.store';
import { DashboardActionsService } from './dashboard-actions.service';
import { ApiConfigService, ServiceKey } from '../core/api-config.service';
import { AlarmSummary } from '../api/models';
import { flush } from '../../test-utils';

const AM_BASE = 'https://am.example/api/alarm-manager';
const CE_BASE = 'https://ce.example/api/correlation-engine';

function realConfig(): Partial<ApiConfigService> & { isMock: boolean } {
  return {
    isMock: false,
    streamingRefreshIntervalMs: 1500,
    baseUrl: (k: ServiceKey) =>
      k === 'alarmManager' ? AM_BASE : k === 'correlationEngine' ? CE_BASE : `https://real.example/${k}`,
    isConfigured: () => true,
  } as unknown as Partial<ApiConfigService> & { isMock: boolean };
}

/** A minimal stub of the shared TopologyStore: a writable alarm snapshot + a spy refreshAlarms. */
class TopologyStoreStub {
  readonly alarms = signal<readonly AlarmSummary[]>([]);
  readonly alarmsLoading = signal<boolean>(false);
  readonly alarmsTruncated = signal<boolean>(false);
  readonly refreshAlarms = vi.fn();
}

/** A minimal DashboardStore stub with a spy `load` (the KPI refresh). */
class DashboardStoreStub {
  readonly load = vi.fn();
}

/** N fake active alarms — only the length matters to the reset poll. */
function activeAlarms(n: number): AlarmSummary[] {
  return Array.from({ length: n }, (_, i) => ({ alarmId: `a${i}` }) as unknown as AlarmSummary);
}

describe('ResetButtonComponent', () => {
  let http: HttpTestingController;
  let store: TopologyStoreStub;
  let dash: DashboardStoreStub;
  let actions: DashboardActionsService;

  function setup() {
    store = new TopologyStoreStub();
    dash = new DashboardStoreStub();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfigService, useValue: realConfig() },
        { provide: TopologyStore, useValue: store },
        { provide: DashboardStore, useValue: dash },
        DashboardActionsService,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    actions = TestBed.inject(DashboardActionsService);
  }

  beforeEach(() => setup());

  afterEach(() => {
    // If a test switched to fake timers, restore real ones for the next test.
    vi.useRealTimers();
  });

  function btn(fixture: ReturnType<typeof TestBed.createComponent<ResetButtonComponent>>): HTMLButtonElement {
    return fixture.nativeElement.querySelector('[data-testid="reset-btn"]') as HTMLButtonElement;
  }

  it('renders "Reset" idle with no spinner', () => {
    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();
    const b = btn(fixture);
    expect(b.textContent).toContain('Reset');
    expect(b.disabled).toBe(false);
    expect(b.getAttribute('aria-busy')).toBe('false');
    expect(fixture.nativeElement.querySelector('.spinner')).toBeNull();
  });

  it('click → fires BOTH purges (forkJoin), enters resetting (aria-busy, spinner) + coordinates busy', async () => {
    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();

    btn(fixture).click();
    fixture.detectChanges();

    // Both POSTs are in flight concurrently (forkJoin) with an empty body.
    const purge = http.expectOne(`${AM_BASE}/admin/purge-live-alarms`);
    const reset = http.expectOne(`${CE_BASE}/admin/reset-correlation`);
    expect(purge.request.method).toBe('POST');
    expect(purge.request.body).toEqual({});
    expect(reset.request.method).toBe('POST');
    expect(reset.request.body).toEqual({});

    // While resetting: aria-busy true, spinner shown, label "Resetting…", and the shared coordinator
    // marks reset busy (so Start ingestion disables).
    const b = btn(fixture);
    expect(b.getAttribute('aria-busy')).toBe('true');
    expect(b.disabled).toBe(true);
    expect(b.textContent).toContain('Resetting');
    expect(fixture.nativeElement.querySelector('.spinner')).toBeTruthy();
    expect(actions.resetting()).toBe(true);

    // Settle the requests so the harness verify is clean.
    purge.flush({ purgedAlarms: 5, purgedTransitions: 3, purgedPendingStatus: 0, purgedProcessedEvents: 2 });
    reset.flush({ purgedIncidents: 2, purgedIncidentAlarms: 4, resetInMemory: true });
    await flush();
    fixture.destroy();
  });

  it('poll: refreshAlarms is called repeatedly; when active count reaches 0 → back to idle + KPI refresh', async () => {
    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();

    // Seed a still-faulted snapshot so the first poll keeps spinning.
    store.alarms.set(activeAlarms(4));

    btn(fixture).click();
    fixture.detectChanges();
    http.expectOne(`${AM_BASE}/admin/purge-live-alarms`).flush({
      purgedAlarms: 4,
      purgedTransitions: 0,
      purgedPendingStatus: 0,
      purgedProcessedEvents: 0,
    });
    http.expectOne(`${CE_BASE}/admin/reset-correlation`).flush({
      purgedIncidents: 1,
      purgedIncidentAlarms: 4,
      resetInMemory: true,
    });
    await flush();

    // First poll tick: refreshAlarms called, snapshot still non-zero → keep spinning.
    await flush(1300);
    expect(store.refreshAlarms).toHaveBeenCalled();
    expect(actions.resetting()).toBe(true);
    expect(btn(fixture).getAttribute('aria-busy')).toBe('true');

    // Snapshot drops to 0 (the purge took effect) → next poll tick finishes.
    store.alarms.set([]);
    await flush(1300);
    fixture.detectChanges();

    const b = btn(fixture);
    expect(b.getAttribute('aria-busy')).toBe('false');
    expect(b.textContent).toContain('Reset');
    expect(actions.resetting()).toBe(false);
    // KPI header refreshed on completion so stats reset to 0 / N/A.
    expect(dash.load).toHaveBeenCalled();
    // At least two refreshAlarms calls (one per poll tick).
    expect(store.refreshAlarms.mock.calls.length).toBeGreaterThanOrEqual(2);
    fixture.destroy();
  });

  it('safety timeout: alarms never reach 0 → stops spinning at the timeout, shows the non-success notice (not "complete"), re-enables buttons', async () => {
    // Fake timers so we can drive the ~30s safety bound deterministically. flushPromises settles the
    // forkJoin microtask that fires after the HTTP responses resolve.
    vi.useFakeTimers();
    const flushMicro = () => Promise.resolve().then(() => Promise.resolve());

    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();

    // A snapshot that stays faulted forever (a fresh ingestion keeps producing) — count never hits 0.
    store.alarms.set(activeAlarms(3));

    btn(fixture).click();
    fixture.detectChanges();
    http.expectOne(`${AM_BASE}/admin/purge-live-alarms`).flush({
      purgedAlarms: 3,
      purgedTransitions: 0,
      purgedPendingStatus: 0,
      purgedProcessedEvents: 0,
    });
    http.expectOne(`${CE_BASE}/admin/reset-correlation`).flush({
      purgedIncidents: 0,
      purgedIncidentAlarms: 0,
      resetInMemory: true,
    });
    await flushMicro();

    // Drive well past the 30s safety bound; the poll keeps rescheduling but never sees 0.
    await vi.advanceTimersByTimeAsync(31_000);
    fixture.detectChanges();

    // Spinner stopped, buttons re-enabled — not stuck.
    const b = btn(fixture);
    expect(b.getAttribute('aria-busy')).toBe('false');
    expect(b.disabled).toBe(false);
    expect(actions.resetting()).toBe(false);
    expect(fixture.nativeElement.querySelector('.spinner')).toBeNull();

    // Distinct NON-success message — NOT "Reset complete".
    const notice = fixture.nativeElement.querySelector('[data-testid="reset-notice"]') as HTMLElement;
    expect(notice).toBeTruthy();
    expect(notice.textContent).toMatch(/some alarms may remain/i);
    expect(fixture.nativeElement.querySelector('[data-testid="reset-done"]')).toBeNull();
    // SR-only live region does not claim completion.
    const live = fixture.nativeElement.querySelector('[data-testid="reset-status-live"]') as HTMLElement;
    expect(live.textContent).not.toMatch(/Reset complete/i);

    fixture.destroy();
  });

  it('truncated snapshot with count 0 does NOT declare done → keeps polling', async () => {
    vi.useFakeTimers();
    const flushMicro = () => Promise.resolve().then(() => Promise.resolve());

    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();

    // Count reads 0 but the fetch was TRUNCATED (hit its safety cap) — an incomplete read that can't
    // prove zero. The poll must NOT treat this as green.
    store.alarms.set([]);
    store.alarmsTruncated.set(true);

    btn(fixture).click();
    fixture.detectChanges();
    http.expectOne(`${AM_BASE}/admin/purge-live-alarms`).flush({
      purgedAlarms: 0,
      purgedTransitions: 0,
      purgedPendingStatus: 0,
      purgedProcessedEvents: 0,
    });
    http.expectOne(`${CE_BASE}/admin/reset-correlation`).flush({
      purgedIncidents: 0,
      purgedIncidentAlarms: 0,
      resetInMemory: true,
    });
    await flushMicro();

    // Advance a few poll cadences — count==0 but truncated → keep spinning, do NOT finish.
    await vi.advanceTimersByTimeAsync(5_000);
    fixture.detectChanges();
    expect(actions.resetting()).toBe(true);
    expect(btn(fixture).getAttribute('aria-busy')).toBe('true');
    expect(dash.load).not.toHaveBeenCalled();
    expect(store.refreshAlarms.mock.calls.length).toBeGreaterThanOrEqual(2);

    // Truncation clears + count still 0 (a real purge settled) → next poll finishes green.
    store.alarmsTruncated.set(false);
    await vi.advanceTimersByTimeAsync(1_300);
    fixture.detectChanges();
    const b = btn(fixture);
    expect(b.getAttribute('aria-busy')).toBe('false');
    expect(actions.resetting()).toBe(false);
    expect(fixture.nativeElement.querySelector('[data-testid="reset-done"]')).toBeTruthy();
    expect(dash.load).toHaveBeenCalled();

    fixture.destroy();
  });

  it('error: a failing purge → shows reset-error, re-enables buttons, not stuck spinning', async () => {
    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();

    btn(fixture).click();
    fixture.detectChanges();

    const purge = http.expectOne(`${AM_BASE}/admin/purge-live-alarms`);
    // The sibling CE request is issued too; forkJoin CANCELS it the moment the purge errors, so it is
    // never flushed (flushing a cancelled request throws). We just capture it, then error the purge.
    http.expectOne(`${CE_BASE}/admin/reset-correlation`);
    purge.flush({ detail: 'boom' }, { status: 500, statusText: 'Internal Server Error' });
    await flush();
    fixture.detectChanges();

    const err = fixture.nativeElement.querySelector('[data-testid="reset-error"]') as HTMLElement;
    expect(err).toBeTruthy();
    expect(err.textContent).toMatch(/failed/i);

    const b = btn(fixture);
    expect(b.disabled).toBe(false);
    expect(b.getAttribute('aria-busy')).toBe('false');
    expect(actions.resetting()).toBe(false);
    fixture.destroy();
  });

  it('disables while an ingestion run is active (mutual exclusion via the shared coordinator)', () => {
    const fixture = TestBed.createComponent(ResetButtonComponent);
    fixture.detectChanges();
    expect(btn(fixture).disabled).toBe(false);

    actions.ingesting.set(true);
    fixture.detectChanges();
    expect(btn(fixture).disabled).toBe(true);
  });
});
