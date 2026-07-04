import type { Page, Route, Request } from '@playwright/test';

/**
 * CONTRACT BOUNDARY MOCKS for the not-yet-built P2/P3 collaborators + Enrichment chatter.
 *
 * ── Real-vs-mocked boundary (per the incremental-E2E gate decision) ──────────────────────────
 *  REAL in `E2E_MODE=real`:  Topology, Trail Builder, Codebook, Knowledge (P1) AND Pattern
 *                            Manager (P2) — the read/approve API stack in docker-compose. The
 *                            browser reaches them SAME-ORIGIN via the web-ui nginx reverse proxy:
 *                            the SPA base URLs are the path prefixes /api/topology,
 *                            /api/trail-builder, /api/codebook, /api/knowledge,
 *                            /api/pattern-manager (set in compose), which nginx forwards to the
 *                            real backends by docker service name. No cross-origin call, no CORS.
 *  CONTRACT-MOCKED:          Correlation Engine, Alarm Manager, Noise Filter (P3) and Enrichment
 *                            chatter — these services are not in the compose stack yet, so they
 *                            are stubbed here at the HTTP boundary, pointed at the sentinel origins
 *                            below (distinct from the /api/* same-origin proxy prefixes, so the
 *                            real backend paths are never intercepted).
 *
 * The response BODIES below are shaped 1:1 to the consumer view-models in
 * `src/app/api/models.ts`, which the app's typed clients build against each producer's PUBLISHED
 * OpenAPI 3.1 / libs/event-model (the model header pins them to the frozen producer specs). The
 * lifecycle-state vocabulary (open / in-progress / correlated / cleared / reverted-open) follows
 * the Alarm Manager spec + `libs/event-model/.../AlarmStatusChange.schema.json`. Keeping the
 * mock shapes identical to the typed-client view-models means this mock CANNOT drift from the
 * contract: if a producer changes its OpenAPI, `models.ts` is regenerated (contract-change
 * procedure) and these shapes must be updated in lockstep.
 *
 * HOW IT WIRES UP (real mode): the app reads its backend base URLs from `window.__ACP_ENV__`
 * (env.js). In `real` mode the compose SPA leaves the P2/P3 URLs UNSET (graceful degrade). To
 * exercise the P2/P3 deferred ACs against the real P1 stack BEFORE those services exist, we
 * (1) override `env.js` to point the P2/P3 base URLs at a sentinel origin, then (2) intercept
 * that origin and reply with the contract-shaped bodies. P1 URLs are left untouched so they
 * keep hitting the real services.
 *
 * Once a P2/P3 service is actually built + wired into compose, the integration-tester drops the
 * corresponding mock (and the env override for it); the same spec then runs end-to-end against
 * the real service with no assertion change.
 */

/**
 * Same-origin proxy prefixes for the REAL P1 read-API stack (must match docker-compose web-ui
 * env + nginx.conf). The env.js override below re-asserts these explicitly: Playwright FULFILLS
 * the /env.js route with our own script, so the container-generated `window.__ACP_ENV__` has NOT
 * executed at merge time (it is undefined inside `Object.assign({}, window.__ACP_ENV__, ...)`);
 * without re-asserting them the P1 base URLs would fall back to the app's compiled `/mock/...`
 * defaults,
 * which the SPA nginx serves as index.html (HTTP 200 HTML → "No sites returned"). Keeping them
 * here ties the real-mode wiring to the proxy prefixes in exactly one place.
 */
export const P1_SAME_ORIGIN_BASE_URLS = {
  TOPOLOGY_API_BASE_URL: '/api/topology',
  TRAIL_BUILDER_API_BASE_URL: '/api/trail-builder',
  CODEBOOK_API_BASE_URL: '/api/codebook',
  KNOWLEDGE_API_BASE_URL: '/api/knowledge',
} as const;

/** Sentinel origins the env-overlay points the mocked services at; intercepted below. */
export const MOCK_ORIGINS = {
  correlationEngine: 'http://e2e-mock.correlation-engine',
  alarmManager: 'http://e2e-mock.alarm-manager',
  noiseFilter: 'http://e2e-mock.noise-filter',
  enrichment: 'http://e2e-mock.enrichment',
} as const;

const json = (route: Route, body: unknown, status = 200) =>
  route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

const page = <T>(items: T[]) => ({ items, total: items.length, limit: 50, offset: 0 });

// ── Correlation Engine (frozen CE OpenAPI — IncidentVM / StatsVM) ────────────────────────────
// A replayed fiber-cut incident: root-cause LOS on the cut fiber, two downstream children.
const INCIDENT_FIBER_CUT = {
  incidentId: 'INC-12',
  rootCauseAlarmId: 'a-3',
  rootCauseAlarmType: 'LOS',
  childAlarmIds: ['a-7', 'a-8'],
  matchedPatternId: 'PAT-3',
  matchedCodebookId: null,
  confidence: 0.91,
  trailId: 'TR-7',
  createdAt: '2026-06-01T12:00:00Z',
};
const INCIDENTS = [
  INCIDENT_FIBER_CUT,
  {
    incidentId: 'INC-11',
    rootCauseAlarmId: 'a-20',
    rootCauseAlarmType: 'LinkDown',
    childAlarmIds: ['a-21'],
    matchedPatternId: null,
    matchedCodebookId: 'CB-2',
    confidence: 0.77,
    trailId: 'TR-8',
    createdAt: '2026-06-01T11:50:00Z',
  },
];
const STATS = {
  totalAlarmsProcessed: 1280,
  correlatedAlarmCount: 768,
  totalIncidentsCreated: 154,
  patternMatchCount: 42,
  codebookMatchCount: 17,
  rcaAccuracy: 0.86,
};

// ── Alarm Manager (frozen AM OpenAPI — AlarmSummary / AlarmDetail; states per AlarmStatusChange)
const ALARMS = [
  { alarmId: 'a-3', managedObjectId: 'FiberSpan:lon-fra-1', eventType: 'LOS', perceivedSeverity: 'critical', raisedAt: '2026-06-01T12:00:00Z', lifecycleState: 'correlated', role: 'root-cause', incidentId: 'INC-12', trailIds: ['TR-7'] },
  { alarmId: 'a-7', managedObjectId: 'Interface:lon-r1-e1', eventType: 'LinkDown', lifecycleState: 'correlated', role: 'child', incidentId: 'INC-12', trailIds: ['TR-7'] },
  { alarmId: 'a-8', managedObjectId: 'Router:lon-r1', eventType: 'AdjDown', lifecycleState: 'correlated', role: 'child', incidentId: 'INC-12', trailIds: ['TR-7'] },
  { alarmId: 'a-2', managedObjectId: 'Router:lon-r1', eventType: 'CpuHigh', lifecycleState: 'in-progress', role: 'none', incidentId: null, trailIds: [] },
  { alarmId: 'a-1', managedObjectId: 'Router:lon-r1', eventType: 'PortFlap', lifecycleState: 'open', role: 'none', incidentId: null, trailIds: [] },
];
const ALARM_DETAIL: Record<string, unknown> = Object.fromEntries(
  ALARMS.map((a) => [
    a.alarmId,
    { ...a, transitions: [{ toState: 'open', occurredAt: '2026-06-01T12:00:00Z' }, { toState: a.lifecycleState, occurredAt: '2026-06-01T12:00:05Z' }] },
  ]),
);

// ── Noise Filter (frozen NF OpenAPI — RunStatsRow page) ───────────────────────────────────────
const RUN_STATS = [
  { runId: 'RUN-9', runTimestamp: '2026-05-10T00:00:00Z', trailId: 'TR-7', snapshotId: 'current', domain: 'core-ip', windowStart: '2026-05-10T00:00:00Z', windowEnd: '2026-05-10T00:10:00Z', eps: 0.5, minSamples: 3, windowSize: 60, algorithm: 'dbscan', alarmsIn: 240, clustersFormed: 12, alarmsKept: 180, alarmsDropped: 60, noiseRatio: 0.25 },
  { runId: 'RUN-7', runTimestamp: '2026-05-08T00:00:00Z', trailId: 'TR-8', snapshotId: 'current', domain: 'core-ip', windowStart: '2026-05-08T00:00:00Z', windowEnd: '2026-05-08T00:10:00Z', eps: 0.5, minSamples: 3, windowSize: 60, algorithm: 'dbscan', alarmsIn: 90, clustersFormed: 5, alarmsKept: 70, alarmsDropped: 20, noiseRatio: 0.22 },
];

const ENRICHMENT_CHATTER = { source: 'nms-alpha', chatterList: [{ managedObjectId: 'Interface:e1-12', eventType: 'linkDown' }] };

/**
 * Install the contract mocks. No-op in `mock` mode (the in-app interceptor already serves these).
 * In `real` mode this:
 *   1. overrides env.js so the SPA points the REAL Pattern Manager at its same-origin nginx proxy
 *      (/api/pattern-manager) and the still-unbuilt P3 collaborators + chatter at sentinel origins;
 *   2. intercepts those sentinel origins with contract-shaped responses.
 * Pattern Manager is now a REAL P2 backend in compose, so its mock has been dropped: the browser
 * hits it end-to-end via the proxy. P1 base URLs (topology/trail-builder/codebook/knowledge) are
 * likewise NOT overridden — they keep resolving to the real compose stack.
 */
export async function installContractMocks(p: Page): Promise<void> {
  // (1) Re-assert the REAL P1 same-origin proxy base URLs and re-point ONLY the not-yet-built
  // collaborators at the sentinel mock origins. This route FULFILLS /env.js with our own script,
  // so the container-generated window.__ACP_ENV__ has not run yet — we therefore set the P1 URLs
  // explicitly (they flow through the web-ui nginx proxy → real backends) rather than relying on a
  // merge with an as-yet-undefined overlay.
  await p.route('**/env.js', async (route) => {
    const overlay = {
      ...P1_SAME_ORIGIN_BASE_URLS,
      // Pattern Manager is REAL in real mode — reach it same-origin via the nginx proxy.
      PATTERN_MANAGER_API_BASE_URL: '/api/pattern-manager',
      CORRELATION_ENGINE_API_BASE_URL: MOCK_ORIGINS.correlationEngine,
      ALARM_MANAGER_API_BASE_URL: MOCK_ORIGINS.alarmManager,
      NOISE_FILTER_API_BASE_URL: MOCK_ORIGINS.noiseFilter,
      ENRICHMENT_CHATTER_API_BASE_URL: MOCK_ORIGINS.enrichment,
      INTEGRATION_MODE: 'real',
      DOMAIN: 'core-ip',
      SNAPSHOT_ID: 'current',
    };
    const body =
      '// E2E real-mode overlay: P1 same-origin proxy (real backends); P2/P3 + chatter mocked.\n' +
      `window.__ACP_ENV__ = Object.assign({}, window.__ACP_ENV__, ${JSON.stringify(overlay)});`;
    await route.fulfill({ status: 200, contentType: 'application/javascript', body });
  });

  // (2) Intercept the still-mocked P3 origins with contract-shaped responses. Pattern Manager is
  // NOT intercepted — it is a real backend reached via the same-origin proxy.
  await mockCorrelationEngine(p);
  await mockAlarmManager(p);
  await mockNoiseFilter(p);
  await mockEnrichment(p);
}

async function mockCorrelationEngine(p: Page): Promise<void> {
  await p.route(`${MOCK_ORIGINS.correlationEngine}/**`, (route: Route, req: Request) => {
    const path = new URL(req.url()).pathname;
    if (/\/incidents\/[^/]+$/.test(path)) {
      const id = decodeURIComponent(path.split('/').pop()!);
      return json(route, INCIDENTS.find((i) => i.incidentId === id) ?? INCIDENT_FIBER_CUT);
    }
    if (path.endsWith('/incidents')) return json(route, page(INCIDENTS));
    if (path.endsWith('/stats')) return json(route, STATS);
    return json(route, {}, 404);
  });
}

async function mockAlarmManager(p: Page): Promise<void> {
  await p.route(`${MOCK_ORIGINS.alarmManager}/**`, (route: Route, req: Request) => {
    const url = new URL(req.url());
    const path = url.pathname;
    if (/\/alarms\/[^/]+$/.test(path)) {
      const id = decodeURIComponent(path.split('/').pop()!);
      return json(route, ALARM_DETAIL[id] ?? { ...ALARMS[0], transitions: [] });
    }
    if (path.endsWith('/alarms')) {
      const state = url.searchParams.get('state');
      const items = state ? ALARMS.filter((a) => a.lifecycleState === state) : ALARMS;
      return json(route, page(items));
    }
    return json(route, {}, 404);
  });
}

async function mockNoiseFilter(p: Page): Promise<void> {
  await p.route(`${MOCK_ORIGINS.noiseFilter}/**`, (route: Route, req: Request) => {
    const url = new URL(req.url());
    if (url.pathname.endsWith('/run-stats')) {
      const trailId = url.searchParams.get('trailId');
      const items = trailId ? RUN_STATS.filter((r) => r.trailId === trailId) : RUN_STATS;
      return json(route, page(items));
    }
    if (url.pathname.endsWith('/observed-chatter')) return json(route, page([]));
    return json(route, {}, 404);
  });
}

async function mockEnrichment(p: Page): Promise<void> {
  await p.route(`${MOCK_ORIGINS.enrichment}/**`, (route: Route) => json(route, ENRICHMENT_CHATTER));
}
