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
