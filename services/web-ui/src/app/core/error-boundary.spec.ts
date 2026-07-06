import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { ApiConfigService, ServiceKey } from './api-config.service';
import { ErrorBannerService } from './error-banner.service';
import { mockBackendInterceptor } from './mock-backend.interceptor';
import { PatternStore } from '../patterns/pattern.store';
import { AlarmsStore } from '../alarms/alarms.store';
import { RcaAccuracyService } from './rca-accuracy.service';
import { flush } from '../../test-utils';

/** Real-mode config so calls reach the HttpTestingController (where we inject the 5xx). */
function realConfig(): Partial<ApiConfigService> & { isMock: boolean } {
  return {
    isMock: false,
    rcaLabelsEnabled: false,
    domain: 'core-ip',
    snapshotId: 'current',
    baseUrl: (k: ServiceKey) => `https://svc.example/${k}`,
    isConfigured: () => true,
  } as unknown as Partial<ApiConfigService> & { isMock: boolean };
}

describe('AC 53 — a 5xx in one integration point shows a structured service-named error and does not crash other modules', () => {
  function configure() {
    TestBed.configureTestingModule({
      providers: [
        PatternStore,
        AlarmsStore,
        RcaAccuracyService,
        ErrorBannerService,
        provideRouter([]),
        provideHttpClient(withInterceptors([mockBackendInterceptor])),
        provideHttpClientTesting(),
        { provide: ApiConfigService, useValue: realConfig() },
      ],
    });
  }

  it('Pattern Manager 500 → structured error naming the service; the store degrades to an empty list', async () => {
    configure();
    const httpMock = TestBed.inject(HttpTestingController);
    const errors = TestBed.inject(ErrorBannerService);
    const patterns = TestBed.inject(PatternStore);

    patterns.load('draft');
    const req = httpMock.expectOne((r) => r.url.includes('/patternManager/patterns'));
    req.flush('boom', { status: 500, statusText: 'Server Error' });
    await flush();

    const err = errors.forService('Pattern Manager');
    expect(err).toBeTruthy();
    expect(err?.status).toBe(500);
    expect(err?.message).toContain('Pattern Manager');
    // module did not crash — it degraded to empty + cleared loading
    expect(patterns.patterns()).toEqual([]);
    expect(patterns.loading()).toBe(false);
    httpMock.verify();
  });

  it('a 5xx in Correlation Engine does not affect the Pattern Manager error surface (per-service isolation)', async () => {
    configure();
    const httpMock = TestBed.inject(HttpTestingController);
    const errors = TestBed.inject(ErrorBannerService);
    const alarms = TestBed.inject(AlarmsStore);

    // The unified Alarms store loads alarms + incidents + stats. Fail the CE /stats call and let the
    // others resolve empty — the store must degrade gracefully with only a per-service error surfaced.
    alarms.loadAll();
    const statsReq = httpMock.expectOne((r) => r.url.includes('/correlationEngine/stats'));
    statsReq.flush('boom', { status: 503, statusText: 'Unavailable' });
    for (const r of httpMock.match(() => true)) {
      r.flush({ items: [], total: 0, limit: 50, offset: 0 });
    }
    await flush();

    expect(errors.forService('Correlation Engine')?.status).toBe(503);
    // a different module's surface is untouched
    expect(errors.forService('Pattern Manager')).toBeUndefined();
    // the alarms store degraded gracefully (no stats, no throw)
    expect(alarms.stats()).toBeNull();
    httpMock.verify();
  });
});
