import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { describe, expect, it } from 'vitest';
import { ActionsMenuComponent } from './actions-menu.component';
import { ApiConfigService, ServiceKey } from '../core/api-config.service';
import { flush } from '../../test-utils';
import { MineStatusResponse, MineProgress, MineSummaryModel } from '../api/models';

const SIM_BASE = 'https://sim.example/api/simulator';
const AM_BASE = 'https://am.example/api/alarm-manager';
const CE_BASE = 'https://ce.example/api/correlation-engine';

function realConfig(): Partial<ApiConfigService> & { isMock: boolean } {
  return {
    isMock: false,
    streamingRefreshIntervalMs: 1500,
    baseUrl: (k: ServiceKey) => {
      if (k === 'simulator') return SIM_BASE;
      if (k === 'alarmManager') return AM_BASE;
      if (k === 'correlationEngine') return CE_BASE;
      return `https://real.example/${k}`;
    },
    isConfigured: () => true,
  } as unknown as Partial<ApiConfigService> & { isMock: boolean };
}

const MP0: MineProgress = { alarmsEmitted: 0, alarmsTotal: 0, alignedEmitted: 0, nonAlignedEmitted: 0 };

function mineStatus(over: Partial<MineStatusResponse> = {}): MineStatusResponse {
  return { status: 'idle', runId: null, progress: MP0, summary: null, ...over };
}

function mineSummary(over: Partial<MineSummaryModel> = {}): MineSummaryModel {
  return {
    runId: 'm1',
    status: 'completed',
    alarmsEmitted: 500,
    failureReason: null,
    startedAt: '2026-01-01T00:00:00Z',
    completedAt: '2026-01-01T00:05:00Z',
    ...over,
  };
}

describe('ActionsMenuComponent', () => {
  let http: HttpTestingController;

  function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfigService, useValue: realConfig() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ActionsMenuComponent);
    fixture.detectChanges();
    return fixture;
  }

  function q<T extends HTMLElement>(fixture: ReturnType<typeof setup>, sel: string): T | null {
    return fixture.nativeElement.querySelector(sel) as T | null;
  }

  it('trigger has menu-button ARIA and is collapsed initially', () => {
    const fixture = setup();
    const trigger = q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!;
    expect(trigger.getAttribute('aria-haspopup')).toBe('menu');
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
    expect(q(fixture, '#actions-menu-list')).toBeNull();
  });

  it('opens on click and shows the three items with testids + role=menu/menuitem', () => {
    const fixture = setup();
    const trigger = q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!;
    trigger.click();
    fixture.detectChanges();

    expect(trigger.getAttribute('aria-expanded')).toBe('true');
    const list = q<HTMLElement>(fixture, '#actions-menu-list')!;
    expect(list.getAttribute('role')).toBe('menu');
    const mine = q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!;
    const ingest = q<HTMLButtonElement>(fixture, '[data-testid="action-ingest"]')!;
    const purge = q<HTMLButtonElement>(fixture, '[data-testid="action-purge"]')!;
    expect(mine.getAttribute('role')).toBe('menuitem');
    expect(ingest.getAttribute('role')).toBe('menuitem');
    expect(purge.getAttribute('role')).toBe('menuitem');
    expect(mine.textContent).toContain('Mine patterns');
    expect(ingest.textContent).toContain('Ingest alarms');
    expect(purge.textContent).toContain('Purge alarms');
  });

  it('shows the resource-intensive helper text', () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('resource-intensive');
  });

  it('Escape closes the menu and returns focus to the trigger', () => {
    const fixture = setup();
    const trigger = q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!;
    trigger.click();
    fixture.detectChanges();
    expect(q(fixture, '#actions-menu-list')).toBeTruthy();

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(q(fixture, '#actions-menu-list')).toBeNull();
    expect(trigger.getAttribute('aria-expanded')).toBe('false');
  });

  it('click outside closes the menu', () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    expect(q(fixture, '#actions-menu-list')).toBeTruthy();

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    expect(q(fixture, '#actions-menu-list')).toBeNull();
  });

  it('ArrowDown on trigger opens the menu (roving focus entry)', () => {
    const fixture = setup();
    const trigger = q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!;
    trigger.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }));
    fixture.detectChanges();
    expect(q(fixture, '#actions-menu-list')).toBeTruthy();
    // menuitems are removed from the tab order (roving tabindex).
    expect(q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.getAttribute('tabindex')).toBe('-1');
  });

  // --- Mine flow ------------------------------------------------------------

  it('Mine: click → POST /mine/run, running shows progress, completed → mine-result + review hint', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();

    q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.click();
    fixture.detectChanges();

    const run = http.expectOne(`${SIM_BASE}/mine/run`);
    expect(run.request.method).toBe('POST');
    expect(run.request.body).toEqual({});
    run.flush({ runId: 'm1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();

    // Immediate poll after acceptance — report running with progress.
    http.expectOne(`${SIM_BASE}/mine/status`).flush(
      mineStatus({ status: 'running', runId: 'm1', progress: { ...MP0, alarmsEmitted: 42, alarmsTotal: 500 } }),
    );
    await flush();
    fixture.detectChanges();

    // Menu closed on click, but re-open to see the busy label.
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    expect(q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.textContent).toContain('Mining');

    // Next scheduled poll reports idle + a completed summary.
    await flush(1600);
    http.expectOne(`${SIM_BASE}/mine/status`).flush(
      mineStatus({ status: 'idle', runId: 'm1', summary: mineSummary({ alarmsEmitted: 500 }) }),
    );
    await flush();
    fixture.detectChanges();

    const result = q<HTMLElement>(fixture, '[data-testid="mine-result"]')!;
    expect(result.textContent).toContain('500');
    expect(result.textContent).toMatch(/Pattern mining/i);
    fixture.destroy();
  });

  it('Mine: 409 on start → enters running (not an error) and polls', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.click();
    fixture.detectChanges();

    http.expectOne(`${SIM_BASE}/mine/run`).flush(
      { detail: 'a mine run is already active', runId: 'm-existing' },
      { status: 409, statusText: 'Conflict' },
    );
    await flush();

    const poll = http.expectOne(`${SIM_BASE}/mine/status`);
    poll.flush(mineStatus({ status: 'running', runId: 'm-existing', progress: { ...MP0, alarmsEmitted: 5 } }));
    await flush();
    fixture.detectChanges();

    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    expect(q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.textContent).toContain('Mining');
    fixture.destroy();
  });

  it('Mine: failure → shows failureReason', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.click();
    fixture.detectChanges();

    http.expectOne(`${SIM_BASE}/mine/run`).flush({ runId: 'm1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();
    http.expectOne(`${SIM_BASE}/mine/status`).flush(
      mineStatus({ status: 'idle', runId: 'm1', summary: mineSummary({ status: 'failed', failureReason: 'spark oom' }) }),
    );
    await flush();
    fixture.detectChanges();

    expect(q<HTMLElement>(fixture, '[data-testid="mine-result"]')!.textContent).toContain('spark oom');
    fixture.destroy();
  });

  it('Mine: 422 on start → inline error', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.click();
    fixture.detectChanges();

    http.expectOne(`${SIM_BASE}/mine/run`).flush({ detail: [] }, { status: 422, statusText: 'Unprocessable Entity' });
    await flush();
    fixture.detectChanges();

    expect(q<HTMLElement>(fixture, '[data-testid="mine-result"]')!.textContent).toMatch(/invalid/i);
    fixture.destroy();
  });

  // --- Ingest / Purge share the same underlying client calls -----------------

  it('Ingest item → POST /synth/run (same flow as the dashboard button)', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    q<HTMLButtonElement>(fixture, '[data-testid="action-ingest"]')!.click();
    fixture.detectChanges();

    const run = http.expectOne(`${SIM_BASE}/synth/run`);
    expect(run.request.method).toBe('POST');
    run.flush({ runId: 'r1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();
    http.expectOne(`${SIM_BASE}/synth/status`).flush({ status: 'running', runId: 'r1', progress: MP0, summary: null });
    await flush();
    fixture.destroy();
  });

  it('Purge item → forkJoin(purge-live-alarms, reset-correlation)', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    q<HTMLButtonElement>(fixture, '[data-testid="action-purge"]')!.click();
    fixture.detectChanges();

    const purge = http.expectOne(`${AM_BASE}/admin/purge-live-alarms`);
    const reset = http.expectOne(`${CE_BASE}/admin/reset-correlation`);
    expect(purge.request.method).toBe('POST');
    expect(reset.request.method).toBe('POST');
    purge.flush(null);
    reset.flush(null);
    await flush();
    fixture.destroy();
  });

  it('mutual exclusion: while mining, the other items are disabled', async () => {
    const fixture = setup();
    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    q<HTMLButtonElement>(fixture, '[data-testid="action-mine"]')!.click();
    fixture.detectChanges();

    http.expectOne(`${SIM_BASE}/mine/run`).flush({ runId: 'm1', status: 'running' }, { status: 202, statusText: 'Accepted' });
    await flush();
    http.expectOne(`${SIM_BASE}/mine/status`).flush(mineStatus({ status: 'running', runId: 'm1' }));
    await flush();
    fixture.detectChanges();

    q<HTMLButtonElement>(fixture, '[data-testid="actions-menu"]')!.click();
    fixture.detectChanges();
    expect(q<HTMLButtonElement>(fixture, '[data-testid="action-ingest"]')!.disabled).toBe(true);
    expect(q<HTMLButtonElement>(fixture, '[data-testid="action-purge"]')!.disabled).toBe(true);
    fixture.destroy();
  });
});
