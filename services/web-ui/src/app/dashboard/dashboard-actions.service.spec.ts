import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardActionsService } from './dashboard-actions.service';
import { ApiConfigService, ServiceKey } from '../core/api-config.service';

const SIM_BASE = 'https://sim.example/api/simulator';

function realConfig(): Partial<ApiConfigService> & { isMock: boolean } {
  return {
    isMock: false,
    streamingRefreshIntervalMs: 1500,
    baseUrl: (k: ServiceKey) => (k === 'simulator' ? SIM_BASE : `https://real.example/${k}`),
    isConfigured: () => true,
  } as unknown as Partial<ApiConfigService> & { isMock: boolean };
}

describe('DashboardActionsService — mine safety timeout', () => {
  let http: HttpTestingController;
  let svc: DashboardActionsService;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ApiConfigService, useValue: realConfig() },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    svc = TestBed.inject(DashboardActionsService);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('stops polling gracefully after the safety window with a "still running" note (not an error)', () => {
    svc.startMine();
    http.expectOne(`${SIM_BASE}/mine/run`).flush({ runId: 'm1', status: 'running' }, { status: 202, statusText: 'Accepted' });

    // First poll fires immediately; keep answering "running" while we advance the clock past the deadline.
    http.expectOne(`${SIM_BASE}/mine/status`).flush({
      status: 'running',
      runId: 'm1',
      progress: { alarmsEmitted: 1, alarmsTotal: 500, alignedEmitted: 0, nonAlignedEmitted: 0 },
      summary: null,
    });

    // Advance well beyond the 10-minute safety window; answer any polls that fire on the way.
    for (let i = 0; i < 320; i++) {
      vi.advanceTimersByTime(2000);
      for (const req of http.match(`${SIM_BASE}/mine/status`)) {
        req.flush({
          status: 'running',
          runId: 'm1',
          progress: { alarmsEmitted: 1, alarmsTotal: 500, alignedEmitted: 0, nonAlignedEmitted: 0 },
          summary: null,
        });
      }
    }

    // The coordinator has released the busy flag and left a non-error "still running" note.
    expect(svc.busy()).toBe(false);
    expect(svc.mine().status).toBe('done');
    expect(svc.mine().message).toMatch(/still running/i);
    http.verify();
  });
});
