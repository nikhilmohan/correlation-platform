# web-ui

**Cohort:** Angular 20 (standalone components, signals, strict TypeScript)
**Owned datastore:** — (stateless SPA; talks to collaborating service REST APIs only — never
Kafka or a datastore directly. No BFF.)

The operator/engineer single-pane-of-glass for the AI/ML Alarm Correlation Platform. One
Angular 20 application with: a landing dashboard, a real-time streaming view (client-side
polling), an incident-detail drill-down, a noise-filter run-stats view, and the four core
modules — **topology & trails**, **pattern review & XAI**, **config**, and **correlation
stats** — interconnected by deep-linkable cross-navigation.

See `spec.md` (contract) and `design.md` (how) for full detail, the navigation map, and the
acceptance criteria.

## Architecture at a glance

- Static Angular SPA served by nginx. No backend-for-frontend layer.
- Talks directly to each collaborator's published REST API via typed clients built against the
  producer's OpenAPI 3.1 spec.
- **Config-switchable backends.** Every outbound base URL and the mock/real toggle are resolved
  from configuration — no backend URL is hard-coded in application source. Unit/component tests
  run against in-memory mocks generated from the producers' shapes; integration runs against the
  real services on the Docker Compose network. The same bundle runs in both modes.

## Run (local dev)

```bash
npm ci
npm start          # ng serve, mock mode by default — no backend required
```

Open http://localhost:4200. With no runtime overlay the app uses compiled **mock-mode**
defaults, so every view renders from in-memory fixtures.

## Test

```bash
npm run lint                       # eslint, must be clean
npm test -- --run                  # Vitest + Angular TestBed (jsdom), unit/component
npm test -- --run --reporter=junit --outputFile=junit.xml   # CI form
npm run build                      # ng build (production)
npm run e2e                        # Playwright E2E — against the integration stack only
```

Vitest + Angular TestBed is the unit/component runner. **Playwright is E2E only** — never the
unit-test runner.

## E2E (Playwright) — P1 demonstrable journey + deferred E2E acceptance criteria

The Playwright suite lives in [`e2e/`](e2e/) (outside `src/`, files named `*.e2e.ts`). It is
**E2E-only and NOT part of the unit gate**: `npm test` (Vitest) globs `src/**/*.spec.ts` only,
and the CI `angular` job runs lint + Vitest + build — never Playwright. This suite runs in the
**integration stage**.

It covers the P1 demonstrable journey — *topology ingested → trails built → codebook compiled →
visualized in the UI* — plus the spec's deferred E2E acceptance criteria
(ACs **5, 13, 17, 20, 25, 33, 39, 43, 46, 49**), with each test named by its AC id. It also
asserts the [`docs/solution-goals.md`](../../docs/solution-goals.md) **P1 quantifiable outcomes**
(sites visualized, device topology per site, trails present).

### Two run modes (`E2E_MODE`, base URL via `E2E_BASE_URL` — no hard-coded hosts)

| Mode | How it runs | Backends |
|---|---|---|
| **`mock`** (default) | Playwright auto-starts `ng serve` (mock mode). Fully deterministic, **no live stack needed**. Used for local authoring + the spec-well-formedness gate. | Every API served by the app's in-process mock interceptor from OpenAPI-shaped fixtures (`src/app/core/mock-fixtures.ts`). |
| **`real`** | Targets the docker-compose SPA (`http://localhost:8086`). This is how `@integration-tester` runs it. | **REAL P1 stack**: Topology (8082), Trail Builder (8083), Codebook (8084), Knowledge (8081). **Contract-mocked** (Playwright route interception, shapes from the producers' OpenAPI / `libs/event-model`): Pattern Manager, Correlation Engine, Alarm Manager, Noise Filter (P2/P3) + Enrichment chatter — these services are not in the P1 compose yet. |

```bash
# (a) Local authoring / well-formedness gate — no backend needed:
npx playwright install --with-deps chromium   # one-time: browser binaries
npm run e2e:list                              # enumerate every spec (no run)
npm run e2e:mock                              # run all specs against ng serve + in-app mocks

# (b) Integration stack (how integration-tester runs it):
docker compose up -d topology trail-builder codebook-api knowledge web-ui
npm run e2e:real                              # P1 real; P2/P3 + chatter contract-mocked
# override the SPA endpoint if needed:
E2E_BASE_URL=http://host:port npm run e2e:real
```

### Real vs contract-mocked boundary

- **Real (P1):** the topology & trails journey and AC 33 hit the live Topology / Trail Builder /
  Codebook stack; AC 43 (config) hits the live **Knowledge Service** (a P1 service).
- **Contract-mocked (not yet built):** every assertion that depends on Pattern Manager,
  Correlation Engine, Alarm Manager, or Noise Filter is served from contract-shaped responses in
  [`e2e/support/contract-mocks.ts`](e2e/support/contract-mocks.ts), pinned 1:1 to the consumer
  view-models in `src/app/api/models.ts` (which track the producers' frozen OpenAPI). When a
  P2/P3 service is built and wired into compose, drop its mock + env override; the same spec then
  runs end-to-end against the real service with no assertion change.

### Spec ↔ test map

| File | ACs / journey |
|---|---|
| `e2e/p1-demonstrable-journey.e2e.ts` | **AC 33** + P1 journey + solution-goals P1-1/P1-2/P1-4/P1-6 |
| `e2e/dashboard-and-cross-nav.e2e.ts` | **AC 5**, **AC 25** |
| `e2e/streaming.e2e.ts` | **AC 13** |
| `e2e/incident-detail.e2e.ts` | **AC 17** |
| `e2e/noise-stats.e2e.ts` | **AC 20** |
| `e2e/patterns.e2e.ts` | **AC 39** |
| `e2e/config.e2e.ts` | **AC 43** (+ AC 42 browser cross-check) |
| `e2e/correlation-stats.e2e.ts` | **AC 46**, **AC 49** |

## Configuration (runtime, no rebuild)

The app reads `window.__ACP_ENV__` at boot from `env.js`. In the container `env.js` is
regenerated from environment variables at startup (`docker-entrypoint.sh`), so one immutable
image serves any backend wiring. Any unset variable falls back to the app's mock-mode default.

| Variable | Purpose |
|---|---|
| `INTEGRATION_MODE` | `mock` (in-memory fixtures) or `real` (call the services) |
| `TOPOLOGY_API_BASE_URL` | Topology Service site/graph query API |
| `TRAIL_BUILDER_API_BASE_URL` | Trail Builder trail-viz API |
| `PATTERN_MANAGER_API_BASE_URL` | Pattern Manager read / approval-intent / edit API |
| `KNOWLEDGE_API_BASE_URL` | Knowledge Service model-params API |
| `CORRELATION_ENGINE_API_BASE_URL` | Correlation Engine incidents / stats API |
| `ALARM_MANAGER_API_BASE_URL` | Alarm Manager alarm-lifecycle query API |
| `NOISE_FILTER_API_BASE_URL` | Noise Filter run-stats read API |
| `CODEBOOK_API_BASE_URL` | Codebook read API |
| `STREAMING_REFRESH_INTERVAL_MS` | streaming-view poll interval (default 3000) |
| `DOMAIN`, `SNAPSHOT_ID`, `LOG_LEVEL` | session context + client log level |

A backend whose base URL is left unset is not called; its view degrades gracefully (a
structured error/empty state) rather than crashing the rest of the app.

## Docker

```bash
docker build -t acp/web-ui:0.1.0 services/web-ui
```

Multi-stage: Node 24 build stage → nginx static serve. Runs **non-root** (`USER nginx`),
listens on **8080** inside the container, SPA deep-link fallback to `index.html`. The served
root returns HTTP 200 for liveness.

In the platform Compose, the service is published on host port **8086**:

```bash
docker compose up -d web-ui      # depends_on topology / trail-builder / codebook-api / knowledge
```

Open http://localhost:8086.

## Phase coverage

- **P1** — topology & trails (geo-site map, site-level device graph with logical-layer toggles,
  attribute panels, trail overlays) against Topology (8082), Trail Builder (8083), Codebook
  (8084) read APIs.
- **P2 / P3** — pattern review & XAI, config, correlation stats, streaming, incident-detail,
  noise-stats. These views render fully in mock mode; against the live stack they activate as
  their backend services come online, degrading gracefully until then.
