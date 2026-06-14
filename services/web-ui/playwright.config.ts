import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E configuration for web-ui — P1 demonstrable-journey + deferred E2E acceptance
 * criteria (spec.md ACs 5, 13, 17, 20, 25, 33, 39, 43, 46, 49) + the solution-goals.md P1
 * quantifiable-outcome assertions.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * PLAYWRIGHT IS E2E-ONLY. It is NEVER the unit-test runner. The unit/component gate is Vitest +
 * Angular TestBed (`npm test`, which globs `src/**\/*.spec.ts`). These E2E specs live OUTSIDE
 * `src/` (in `e2e/`) and are named `*.e2e.ts`, so Vitest never picks them up and the CI `angular`
 * job (lint + vitest + build) never runs them. This suite runs in the INTEGRATION stage only.
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * TWO RUN MODES (selected by the `E2E_MODE` env var):
 *
 *   1. `E2E_MODE=mock` (DEFAULT — local authoring / well-formedness gate)
 *      Playwright starts the Angular dev server (`ng serve`) which boots in the app's compiled
 *      MOCK mode (no `window.__ACP_ENV__` overlay → `INTEGRATION_MODE=mock`). Every backend call
 *      is served by the app's in-process mock interceptor from fixtures shaped 1:1 to each
 *      producer's frozen OpenAPI (src/app/core/mock-fixtures.ts). NO live stack is required;
 *      every test runs and is deterministic. This is how a reviewer runs the suite locally and
 *      how `npx playwright test --list` enumerates it.
 *
 *   2. `E2E_MODE=real` (INTEGRATION stage — how @integration-tester runs it)
 *      Playwright targets the docker-compose-served SPA (default http://localhost:8086), which is
 *      wired to the REAL P1 read-API stack (Topology 8082 / Trail Builder 8083 / Codebook 8084 /
 *      Knowledge 8081). P1-backed flows hit the real services. Collaborators that are NOT YET
 *      BUILT (Pattern Manager / Correlation Engine / Alarm Manager / Noise Filter — P2/P3 — and
 *      Enrichment chatter) are stubbed AT THE CONTRACT BOUNDARY via Playwright route interception
 *      using responses shaped from the producers' published OpenAPI / libs/event-model
 *      (see e2e/support/contract-mocks.ts).
 *
 * NO HARD-CODED HOSTS: the base URL is resolved from `E2E_BASE_URL` (falling back to a per-mode
 * default), mirroring the app's config-switchable-backends rule (spec AC 51).
 */

const MODE = (process.env['E2E_MODE'] ?? 'mock') as 'mock' | 'real';

// Local authoring serves on 4200 (ng serve); integration targets the compose SPA on 8086.
const DEFAULT_BASE_URL = MODE === 'real' ? 'http://localhost:8086' : 'http://localhost:4200';
const BASE_URL = process.env['E2E_BASE_URL'] ?? DEFAULT_BASE_URL;

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.e2e.ts',
  // Keep all E2E artefacts out of the Vitest `reports/junit` path and out of `src/`.
  outputDir: './e2e/.artifacts',
  fullyParallel: true,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  workers: process.env['CI'] ? 1 : undefined,
  reporter: process.env['CI']
    ? [
        ['list'],
        ['junit', { outputFile: 'reports/e2e/web-ui-e2e.xml' }],
        ['html', { open: 'never', outputFolder: 'e2e/.report' }],
      ]
    : [['list'], ['html', { open: 'never', outputFolder: 'e2e/.report' }]],

  use: {
    baseURL: BASE_URL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  // Expose the resolved mode/base to specs and the run summary.
  metadata: { e2eMode: MODE, baseURL: BASE_URL },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  /*
   * Only auto-start a dev server in `mock` mode for local authoring. In `real` (integration)
   * mode the SPA is already served by docker-compose (`docker compose up -d web-ui`), so no
   * webServer is launched — Playwright just targets the running compose endpoint.
   */
  webServer:
    MODE === 'mock'
      ? {
          command: 'npm start -- --port 4200',
          url: 'http://localhost:4200',
          reuseExistingServer: !process.env['CI'],
          timeout: 180_000,
        }
      : undefined,
});
