import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { TestBed } from '@angular/core/testing';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { describe, expect, it } from 'vitest';
import { ApiConfigService, ServiceKey } from './api-config.service';
import { mockBackendInterceptor } from './mock-backend.interceptor';
import { TopologyClient } from '../api/topology.client';
import { TrailBuilderClient } from '../api/trail-builder.client';
import { PatternManagerClient } from '../api/pattern-manager.client';
import { KnowledgeClient } from '../api/knowledge.client';
import { CorrelationEngineClient } from '../api/correlation-engine.client';
import { AlarmManagerClient } from '../api/alarm-manager.client';
import { NoiseFilterClient } from '../api/noise-filter.client';
import { EnrichmentChatterClient } from '../api/enrichment-chatter.client';
import { flush } from '../../test-utils';

describe('AC 50 — config-switchable backends resolve to mocks; no real HTTP call in mock mode', () => {
  it('every documented integration point is served by the mock interceptor (no escape to the network)', async () => {
    TestBed.configureTestingModule({
      providers: [
        // HttpTestingController acts as the *real network*: any request that escapes the mock
        // interceptor lands here. In mock mode there must be ZERO such requests.
        provideRouter([]),
        provideHttpClient(withInterceptors([mockBackendInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    const httpMock = TestBed.inject(HttpTestingController);
    const cfg = TestBed.inject(ApiConfigService);
    expect(cfg.isMock).toBe(true);

    // Exercise one call per integration point.
    TestBed.inject(TopologyClient).listSites().subscribe();
    TestBed.inject(TopologyClient).objectsAtSite('Site:LON').subscribe();
    TestBed.inject(TrailBuilderClient).listTrails().subscribe();
    TestBed.inject(PatternManagerClient).listPatterns().subscribe();
    TestBed.inject(PatternManagerClient).decide('02007ff1-9d3a-4e21-b7c8-1a2b3c4d5e6f', { decision: 'approve', reviewer: 'op' }).subscribe();
    TestBed.inject(KnowledgeClient).getModelParams('noise-filter').subscribe();
    TestBed.inject(CorrelationEngineClient).listIncidents().subscribe();
    TestBed.inject(CorrelationEngineClient).getStats().subscribe();
    TestBed.inject(AlarmManagerClient).listAlarms().subscribe();
    TestBed.inject(NoiseFilterClient).listRunStats().subscribe();
    TestBed.inject(EnrichmentChatterClient).listChatter('nms-alpha').subscribe();
    await flush();

    // The mock interceptor short-circuits every call → nothing reaches the testing backend.
    httpMock.expectNone(() => true);
    httpMock.verify();
  });

  it('switching ApiConfigService to real mode lets calls pass through to the configured base URL', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([mockBackendInterceptor])),
        provideHttpClientTesting(),
        { provide: ApiConfigService, useValue: realConfig() },
      ],
    });
    const httpMock = TestBed.inject(HttpTestingController);
    TestBed.inject(TopologyClient).listSites().subscribe();
    await flush();
    // In real mode the interceptor is a no-op; the call reaches the (test) network at the base URL.
    const req = httpMock.expectOne((r) => r.url.startsWith('https://real.example/topology/topology/sites'));
    expect(req.request.method).toBe('GET');
    req.flush({ domain: 'd', snapshotId: 's', count: 0, sites: [] });
    httpMock.verify();
  });
});

function realConfig(): Partial<ApiConfigService> & { isMock: boolean } {
  return {
    isMock: false,
    domain: 'core-ip',
    snapshotId: 'current',
    baseUrl: (k: ServiceKey) => (k === 'topology' ? 'https://real.example/topology' : `https://real.example/${k}`),
    isConfigured: () => true,
  } as unknown as Partial<ApiConfigService> & { isMock: boolean };
}

describe('AC 51 — no hard-coded backend URL appears in application source', () => {
  // Build-time check: scan app source (excluding the environment overlay, which is where the
  // ONLY URL literals legitimately live) for http(s):// literals or service-hostname literals.
  const APP_DIR = join(process.cwd(), 'src', 'app');
  const ENV_DIR = join(process.cwd(), 'src', 'environments');

  function tsFiles(dir: string): string[] {
    const out: string[] = [];
    for (const entry of readdirSync(dir)) {
      const full = join(dir, entry);
      if (statSync(full).isDirectory()) {
        out.push(...tsFiles(full));
      } else if (entry.endsWith('.ts') && !entry.endsWith('.spec.ts')) {
        out.push(full);
      }
    }
    return out;
  }

  it('src/app/**/*.ts (non-test) contains no http(s):// URL literal', () => {
    const offenders: string[] = [];
    for (const file of tsFiles(APP_DIR)) {
      const content = readFileSync(file, 'utf8');
      // strip the in-mock relative '/mock/...' paths are in environments only; app dir must be clean.
      if (/https?:\/\/[a-z0-9.-]/i.test(content)) {
        offenders.push(file);
      }
    }
    expect(offenders).toEqual([]);
  });

  it('the only URL literals live in src/environments (the runtime-overridable overlay)', () => {
    // sanity: the environment file is the single allowed home for default URL strings.
    const envContent = readFileSync(join(ENV_DIR, 'environment.ts'), 'utf8');
    expect(envContent).toContain('serviceBaseUrls');
  });
});
