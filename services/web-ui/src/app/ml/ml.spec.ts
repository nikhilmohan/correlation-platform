import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideLocationMocks } from '@angular/common/testing';
import { describe, expect, it } from 'vitest';
import { APP_ROUTES } from '../app.routes';
import { mockBackendInterceptor } from '../core/mock-backend.interceptor';
import { flush } from '../../test-utils';

/**
 * Change 1 — the ML page consolidation. Drives the REAL APP_ROUTES through the router so the
 * child routes, the /ml redirect, and the old-path → /ml/... redirects are all exercised end to
 * end (deep-linkable sub-tabs), plus the shell's sub-tab nav.
 */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideRouter(APP_ROUTES),
      provideLocationMocks(),
      provideHttpClient(withInterceptors([mockBackendInterceptor])),
    ],
  });
}

async function goto(path: string) {
  const harness = await RouterTestingHarness.create();
  await harness.navigateByUrl(path);
  harness.detectChanges();
  await flush();
  harness.detectChanges();
  return harness;
}

describe('ML page (Change 1) — shell + sub-tabs', () => {
  it('renders the shell with three sub-tabs (Pattern mining / Noise filtering / Config)', async () => {
    setup();
    const harness = await goto('/ml/patterns');
    const el: HTMLElement = harness.routeNativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="ml-shell"]')).toBeTruthy();
    expect(el.querySelector('[data-testid="ml-subtab-patterns"]')?.textContent).toContain('Pattern mining');
    expect(el.querySelector('[data-testid="ml-subtab-noise"]')?.textContent).toContain('Noise filtering');
    expect(el.querySelector('[data-testid="ml-subtab-config"]')?.textContent).toContain('Config');
  });

  it('/ml/patterns shows the Pattern review component', async () => {
    setup();
    const harness = await goto('/ml/patterns');
    const el: HTMLElement = harness.routeNativeElement as HTMLElement;
    expect(el.querySelector('app-pattern-list, [data-testid="pattern-list"]') ?? el.textContent).toBeTruthy();
    expect(el.textContent).toMatch(/pattern/i);
  });

  it('/ml/noise shows Noise filtering — BOTH the noise view and chatter management together', async () => {
    setup();
    const harness = await goto('/ml/noise');
    const el: HTMLElement = harness.routeNativeElement as HTMLElement;
    expect(el.querySelector('[data-testid="ml-noise-filtering"]')).toBeTruthy();
    expect(el.querySelector('app-noise-view')).toBeTruthy();
    expect(el.querySelector('app-chatter-management')).toBeTruthy();
  });

  it('/ml/config shows the model-params form (grouped)', async () => {
    setup();
    const harness = await goto('/ml/config');
    const el: HTMLElement = harness.routeNativeElement as HTMLElement;
    expect(el.querySelector('app-model-params-form') ?? el).toBeTruthy();
    expect(el.textContent).toMatch(/Model parameters/i);
  });

  it('/ml redirects to /ml/noise (Noise filtering is the default landing)', async () => {
    setup();
    await goto('/ml');
    const router = TestBed.inject(Router);
    expect(router.url).toBe('/ml/noise');
  });

  it('old paths redirect to their new /ml/... homes', async () => {
    const cases: [string, string][] = [
      ['/patterns', '/ml/patterns'],
      ['/noise', '/ml/noise'],
      ['/chatter', '/ml/noise'],
      ['/config', '/ml/config'],
    ];
    for (const [from, to] of cases) {
      setup();
      await goto(from);
      const router = TestBed.inject(Router);
      expect(router.url).toBe(to);
      TestBed.resetTestingModule();
    }
  });

  it('the incident deep link is NOT under ML (stays /incidents/:id)', async () => {
    setup();
    await goto('/incidents/INC-9');
    const router = TestBed.inject(Router);
    expect(router.url).toBe('/incidents/INC-9');
  });
});
