# web-ui — Service Spec

## Purpose
The web-ui is the operator and engineer interface for the Alarm Correlation Platform. It is a
single Angular 20 application with four modules — topology & trails, pattern review & XAI,
config, and correlation stats — that give NOC operators and engineers a single pane of glass:
visualize the onboarded topology and trail clusters, review and approve/reject discovered
patterns with full explainability, tune ML model parameters, and monitor live incident
effectiveness metrics. The application talks exclusively to the published REST APIs of its
collaborating services; it never touches Kafka topics or any datastore directly.

## Scope
**In scope:**
- Topology & trails module: geo-site map view (sites); zoom into a site to view site-level
  topology with togglable logical layers (fiber / IP / IGP / LSP / service); overlay and
  highlight trail clusters; highlight all trails a selected device belongs to. Reads the
  Topology Service graph/geometry API and the Trail Builder trail-viz API
  (`listTrails(snapshotId)`, `getTrail(trailId)`, `getTrailsForObject(managedObjectId)`).
- Pattern review & XAI module: list discovered patterns from the Pattern Manager (pattern
  read API) with support, confidence, lift, RCA, timing, codebook overlap, and supporting
  instances; present the explainability data to support the operator decision; approve or
  reject a pattern by posting a lightweight approval-intent to the Pattern Manager API; list
  active/approved patterns with their details. The Pattern Manager, upon receiving the
  approval-intent, is responsible for transitioning lifecycle and emitting `patterns.approved`
  downstream — the web-ui does not emit Kafka events.
- Config module: read and edit Knowledge Service model parameters (DBSCAN params,
  session-window gap, min-support, etc.) via the Knowledge Service read and edit API; persist
  changes via Knowledge (Knowledge Service is the single owner of these params).
- Correlation stats module: display live incidents (root-cause alarm + child alarms), noise-
  filter stats, alarm-reduction ratio, RCA accuracy, and real-time pattern-match stats. Reads
  the Correlation Engine incident and stats API and the Pattern Manager active-patterns API.
- Config-switchable backends: all outbound HTTP calls are routed through Angular environment
  configuration (base URL + mock/real toggle); unit/component tests use mocks generated from
  the producer's published OpenAPI spec; integration tests point at the live stack. No backend
  URL is hard-coded.
- Test suite: Vitest + Angular TestBed for unit/component tests (mock backends); Playwright for
  E2E tests against the integration stack (browser-level user-flow coverage). Playwright is E2E
  only and is not the unit-test runner.
- WCAG 2.1 AA accessibility: keyboard navigation, ARIA labeling, and sufficient colour contrast
  on the four key flows (topology view, pattern review, config edit, stats view).
- Permissive-license dependencies only (Angular MIT, Cytoscape.js MIT, MapLibre GL BSD,
  deck.gl MIT).
- Dockerfile + Docker Compose entry, README with run instructions.
- Served-app health indicator (HTTP 200 on the app root for liveness checks).

## Out of scope
- Direct Kafka consumption or production: the web-ui never subscribes to or publishes Kafka
  topics directly. The architecture row "produces `patterns.approved` (via API)" means the UI
  posts an approval-intent to the Pattern Manager API; the Pattern Manager emits the
  `patterns.approved` event.
- Owning or computing domain data: topology graph, trail definitions, patterns, incidents, and
  ML params all live in their owning services; the web-ui is a stateless client.
- A Backend-for-Frontend (BFF) server layer: the application is a static Angular SPA that
  calls service APIs directly. If a BFF is introduced it is a design decision to be made at
  the design stage and would constitute a new API surface requiring a contract-change review.
- Correlation/incident computation: the web-ui displays results from the Correlation Engine; it
  does not implement matching, scoring, or RCA logic.
- Pattern lifecycle management beyond surfacing the UI action: lifecycle transitions
  (draft → approved → deprecated) are owned by the Pattern Manager.
- Automated retraining or closed-loop feedback execution (deferred MVP item per §2 non-goals).
- Multi-tenancy, production-grade HA, real OSS north-bound connectors (deferred non-goals).
- Other domains beyond Core IP (domain pack extensibility is by design in the backend;
  web-ui renders whatever the APIs return).
- Apache AGE or PostgreSQL direct access.

## Tasks (high-level)

1. **Render the geo-site topology view.** Fetch topology geometry and site groupings from the
   Topology Service graph/geometry API and display sites on a geo map. Allow the operator to
   zoom into a site and view its node/link topology with layer toggles (fiber, IP, IGP, LSP,
   service layers individually shown or hidden).

2. **Visualize trail clusters and per-device trail membership.** Fetch trail data from the
   Trail Builder trail-viz API (`listTrails`, `getTrail`, `getTrailsForObject`) and overlay
   trail cluster boundaries on the topology graph. When the operator selects a device, highlight
   all trails that device belongs to (a device may appear in multiple overlapping trails).

3. **List and present discovered patterns with full XAI.** Fetch discovered patterns from the
   Pattern Manager read API. For each pattern, surface the sequence, support/confidence/lift,
   RCA, timing stats, codebook overlap, and supporting instance count in a form that lets the
   operator understand the evidence behind the pattern before acting on it.

4. **Accept approve/reject decisions and post to the Pattern Manager.** Capture the operator's
   approve or reject decision and post a lightweight approval-intent request to the Pattern
   Manager API. Reflect the resulting lifecycle state back in the UI.

5. **List active/approved patterns.** Fetch and display patterns whose lifecycle state is
   `approved` from the Pattern Manager read API, with their details.

6. **Read and edit Knowledge Service model parameters.** Fetch current ML config params
   (DBSCAN params, session-window gap, min-support, etc.) from the Knowledge Service API and
   present them for editing. Submit validated edits to the Knowledge Service API and confirm
   persistence to the operator.

7. **Display live correlation stats and incidents.** Fetch live incidents (root-cause alarm +
   child alarms), noise-filter stats, alarm-reduction ratio, RCA accuracy, and pattern-match
   stats from the Correlation Engine incident/stats API. Present them as the platform's
   effectiveness dashboard for a replayed or live scenario.

8. **Provide config-switchable backend integration.** All outbound API calls are resolved from
   Angular environment configuration. Each integration point is independently switchable
   between a mock (generated from the collaborator's published OpenAPI spec) for unit/component
   tests and the real service for integration — with no code changes between modes.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Topology & trails visualization: operators view the onboarded topology, toggle layers, and explore trail clusters as they are built | Active | Reads: Topology Service graph/geometry API; Trail Builder `listTrails` / `getTrail` / `getTrailsForObject` API. Writes: — |
| P2 — Pattern learning | Pattern review/approve (XAI-driven approve/reject) and config edits (Knowledge params): operators review discovered patterns and tune ML parameters | Active | Reads: Pattern Manager pattern read API (discovered + active/approved patterns). Writes: Pattern Manager approval-intent API (approve/reject); Knowledge Service model-params edit API |
| P3 — Real-time correlation | Live incidents and correlation stats (effectiveness dashboard): operators monitor running correlation, view incidents, and assess platform effectiveness | Active | Reads: Correlation Engine incident/stats API; Pattern Manager active-patterns API. Writes: — |

## Contract

- **Consumes (Kafka):** — (none; the web-ui consumes service REST APIs only, never Kafka
  topics directly)
- **Produces (Kafka):** — (note: `patterns.approved` is emitted by the Pattern Manager upon
  receiving the UI's approval-intent via the Pattern Manager API; the web-ui does not publish
  to Kafka)
- **APIs exposed:** — (the web-ui is a static SPA; it exposes no HTTP API that other services
  consume. The served application responds HTTP 200 on its root path for liveness purposes. No
  OpenAPI document is published.)
- **APIs consumed (integration points — each config-switchable mock/real, built against the
  producer's published OpenAPI; no hard-coded backend URLs):**
  - **Topology Service — graph/geometry API:** query nodes and edges with geo coordinates,
    list objects by type, retrieve neighbours, resolve `managedObjectId` → object + layer,
    list objects by site. Used by the topology & trails module (P1).
  - **Trail Builder — trail-viz API:** `listTrails(snapshotId)`, `getTrail(trailId)`,
    `getTrailsForObject(managedObjectId)`. Used by the topology & trails module to overlay
    clusters and highlight per-device trail membership (P1, P2 passive reference).
  - **Pattern Manager — pattern read API:** list discovered patterns with full explainability
    metadata (sequence, support, confidence, lift, RCA, timing, codebook overlap, supporting
    instances, lifecycle). List active/approved patterns. Used by the pattern review & XAI
    module (P2) and correlation stats module (P3 active-patterns list).
  - **Pattern Manager — approval-intent API:** POST approve or reject decision for a given
    `patternId`. Used by the pattern review & XAI module (P2). The Pattern Manager transitions
    the lifecycle and emits `patterns.approved`.
  - **Knowledge Service — model-params API:** read current ML configuration parameters; submit
    validated edits. Used by the config module (P2).
  - **Correlation Engine — incident/stats API:** list live incidents (root-cause alarm +
    child alarms), retrieve noise-filter stats, alarm-reduction ratio, RCA accuracy, and
    pattern-match stats. Used by the correlation stats module (P3).
- **Integration points (mock vs. real):** each of the six integration points above is
  independently configured via Angular environments (base URL per service + mock/real toggle).
  Unit/component tests use mocks or stubs generated from the collaborator's published OpenAPI
  3.1 spec (no live dependency). Integration tests point at the real service on the Docker
  Compose network. The same application code runs in both modes without modification.
- **Data owned:** — (none; the web-ui is a stateless frontend; it holds no persistent store
  and caches no domain data beyond what is in-memory for the current session)

## Non-functional

- **Idempotency key:** not applicable — the web-ui is a stateless client. The only write
  operations (approval-intent POST, Knowledge params edit) are idempotent at the API level
  (the owning service enforces idempotency); the UI need only avoid double-submit on the same
  user action (standard Angular reactive-form guard).
- **Config:** all backend base URLs and mock/real toggles are supplied via Angular environment
  files (`environment.ts` / `environment.integration.ts`); no URL or threshold is hard-coded
  in application source. Environment files are populated from Docker Compose environment
  variables at build or serve time.
- **Observability:** the served application root path returns HTTP 200 (liveness). Client-side
  structured logging (JSON, configurable log level from environment) for API errors and
  navigation events. No Prometheus `/metrics` endpoint is required for a static SPA; if a BFF
  layer is introduced at the design stage, that layer must expose `/health` and `/metrics`.
- **API contract:** the web-ui has no published OpenAPI surface. It builds all outbound clients
  against its collaborators' published OpenAPI 3.1 specs; a change to a collaborator's API
  surface is a contract change requiring architecture.md update and human approval before the
  web-ui client is updated.
- **Error handling:** API errors are surfaced to the operator with a structured error message
  (HTTP status + service name); the application does not crash on a single failing backend
  call. A failed liveness probe (unresponsive collaborator) is reported in the relevant module
  rather than blocking the entire application.
- **Accessibility:** WCAG 2.1 AA — keyboard navigability for all interactive controls, ARIA
  roles and labels on graph/map canvases and data tables, colour contrast >= 4.5:1 for normal
  text and >= 3:1 for large text and UI components.
- **Performance:** Angular OnPush change detection and signals throughout; lazy-loaded routes
  per module; virtual scrolling for long pattern/incident lists; graph rendering (Cytoscape.js)
  must remain responsive for topologies up to the maximum configured size.
- **Licenses:** all runtime dependencies must carry permissive licenses (MIT, Apache-2.0, BSD,
  PostgreSQL). No GPL, AGPL, BSL, or source-available components.
- **Test frameworks (do not substitute):** unit/component tests use **Vitest + Angular TestBed**
  (jsdom, mock backends). E2E tests use **Playwright** (real browser, against the integration
  stack). Playwright is E2E only — never the unit-test runner.

## Acceptance criteria

### Topology & trails module (P1)

1. Given a topology snapshot loaded into the Topology Service, the geo-site view renders a map
   marker or region for each site; selecting a site zooms into and displays the site's nodes
   and links. (Vitest/TestBed — mock Topology API returning a fixture with >= 2 sites)

2. Given a site-level topology view, toggling each logical layer (fiber, IP, IGP, LSP, service)
   independently shows or hides the corresponding edges; toggling all off shows only nodes.
   (Vitest/TestBed — mock API; layer-toggle component test)

3. Given trail data returned by the Trail Builder `listTrails(snapshotId)` API, trail cluster
   boundaries are rendered as visual overlays on the topology graph. (Vitest/TestBed — mock
   Trail Builder API fixture)

4. Given a device that belongs to multiple trails (per `getTrailsForObject`), selecting that
   device in the topology view highlights all trails it belongs to, visually distinct from
   non-member trails. (Vitest/TestBed — fixture with a device in >= 2 trails)

5. Given the Topology and Trail Builder mocks are replaced with real services in the
   integration stack, the geo-site and trail views render without errors for the synthetic
   topology. (Playwright E2E)

### Pattern review & XAI module (P2)

6. Given discovered patterns returned by the Pattern Manager read API, the pattern list
   renders each pattern's sequence, support, confidence, lift, RCA, timing, codebook overlap,
   and supporting instance count. (Vitest/TestBed — mock Pattern Manager API fixture)

7. Given a pattern in the list, the operator can expand it to view the full explainability
   detail (all XAI fields) before acting. (Vitest/TestBed — component expansion interaction
   test)

8. Given the operator clicks "Approve" on a pattern, the application posts an approval-intent
   to the Pattern Manager approval-intent API endpoint with the correct `patternId`; the
   pattern's displayed lifecycle state updates to `approved` after a successful response.
   (Vitest/TestBed — mock Pattern Manager approval-intent endpoint)

9. Given the operator clicks "Reject" on a pattern, the application posts a reject-intent to
   the Pattern Manager API with the correct `patternId`; the pattern is removed from the
   discovered list or marked rejected in the UI. (Vitest/TestBed — mock Pattern Manager
   reject endpoint)

10. Given a filter or tab for active/approved patterns, the list displays patterns whose
    lifecycle state is `approved` as returned by the Pattern Manager read API.
    (Vitest/TestBed — mock API fixture with mixed lifecycle states)

11. Given the Playwright E2E suite runs against the integration stack with a replayed scenario,
    the operator can approve a pattern, and the Pattern Manager reflects the `approved` lifecycle
    state on a subsequent read. (Playwright E2E)

### Config module (P2)

12. Given the config module is loaded, it displays the current model parameters (DBSCAN params,
    session-window gap, min-support) fetched from the Knowledge Service API. (Vitest/TestBed —
    mock Knowledge API fixture)

13. Given the operator edits a model parameter and submits, the application sends an edit
    request to the Knowledge Service API with the updated values; a success response is
    confirmed to the operator in the UI. (Vitest/TestBed — mock Knowledge edit endpoint)

14. Given the operator submits an invalid parameter value (e.g. a negative session-window gap),
    the form displays a validation error and does not call the Knowledge Service API.
    (Vitest/TestBed — form validation unit test)

15. Given the Playwright E2E suite runs against the integration stack, a config edit submitted
    through the UI is retrievable via the Knowledge Service API on a subsequent read.
    (Playwright E2E)

### Correlation stats module (P3)

16. Given incidents returned by the Correlation Engine incident/stats API, the stats view
    renders each incident with its root-cause alarm and the list of child alarms.
    (Vitest/TestBed — mock Correlation Engine fixture)

17. Given stats metrics returned by the Correlation Engine API, the view displays the
    alarm-reduction ratio and RCA accuracy as numeric values. (Vitest/TestBed — mock API
    fixture with known ratio/accuracy values)

18. Given noise-filter stats returned by the Correlation Engine API, the view displays the
    noise-filter effectiveness metric. (Vitest/TestBed — mock API fixture)

19. Given the Playwright E2E suite runs a replayed fiber-cut scenario against the integration
    stack, the stats module shows at least one incident with a tagged root-cause alarm and one
    or more child alarms. (Playwright E2E)

### Cross-cutting

20. Given the application is built with mock environment configuration, all six integration
    points (Topology, Trail Builder, Pattern Manager read, Pattern Manager approval-intent,
    Knowledge, Correlation Engine) resolve to mock/stub handlers and no real HTTP call is made.
    (Vitest/TestBed — environment-switch test per integration point)

21. Given the application is built with integration environment configuration, all six
    integration point base URLs are resolved from environment variables with no URL literal in
    application source. (Build-time check: no hard-coded http://localhost or service hostname
    appears in non-environment source files)

22. Given the main interactive views (geo-site topology, pattern list, config form, stats
    dashboard), keyboard navigation cycles through all interactive elements without a mouse,
    and all graph/map canvas elements carry an ARIA label. (Vitest/TestBed accessibility test
    using axe-core or equivalent; at least one criterion per view)

23. Given any single backend integration point returns a 5xx error, the affected module
    displays a structured error message identifying the service and does not crash other
    modules. (Vitest/TestBed — error-boundary component test per integration point)

## Open questions

1. **Topology Service graph/geometry API shape (P1 blocker for design).** The Topology Service
   spec is a scaffold stub (STATUS: TBD). The web-ui needs the published OpenAPI for the
   graph/geometry query API — specifically: the endpoint(s) for listing nodes and edges with
   geo coordinates, listing objects by site/geo-region, and listing objects by type. The
   site-grouping field or endpoint is not defined in any merged spec. Cannot finalise the
   client shape or the mock fixture until the Topology spec and OpenAPI are authored and
   approved.
   — Issue to open: `[web-ui] Topology Service graph/geometry OpenAPI needed for web-ui client
   design` (labels: `question`, `service:web-ui`).

2. **Trail Builder trail-viz API shape (P1 blocker for design).** The Trail Builder spec is a
   scaffold stub. The web-ui needs the published OpenAPI confirming the exact request/response
   shapes for `listTrails(snapshotId)`, `getTrail(trailId)`, and
   `getTrailsForObject(managedObjectId)` including pagination, field names, and the trail
   geometry/member format. Cannot finalise the client contract or mock until the Trail Builder
   spec and OpenAPI are authored and approved.
   — Issue to open: `[web-ui] Trail Builder trail-viz API OpenAPI needed for web-ui client
   design` (labels: `question`, `service:web-ui`).

3. **Pattern Manager read API and approval-intent API shapes (P2 blocker for design).** The
   Pattern Manager spec is a scaffold stub. The web-ui needs: (a) the exact response shape for
   listing discovered and approved patterns (field names, pagination, XAI fields); (b) the
   exact endpoint and request body for approve/reject (whether a single endpoint with a
   decision field, two separate endpoints, or another shape). The architecture row states
   "patterns.approved (via API)" but the API contract is not yet published.
   — Issue to open: `[web-ui] Pattern Manager read + approval-intent API OpenAPI needed for
   web-ui client design` (labels: `question`, `service:web-ui`).

4. **Knowledge Service model-params API shape (P2 blocker for design).** The Knowledge Service
   spec is a scaffold stub. The web-ui needs the published OpenAPI confirming the endpoint(s)
   for reading and editing model parameters (DBSCAN params, session-window gap, min-support),
   including which params are editable, their types, and validation rules.
   — Issue to open: `[web-ui] Knowledge Service model-params API OpenAPI needed for web-ui
   client design` (labels: `question`, `service:web-ui`).

5. **Correlation Engine incident/stats API shape (P3 blocker for design).** The Correlation
   Engine spec is a scaffold stub. The web-ui needs the published OpenAPI for the incident
   list endpoint (root-cause alarm + child alarms, pagination) and the stats endpoint
   (alarm-reduction ratio, RCA accuracy, noise-filter stats, pattern-match stats). Field names
   must be confirmed before mock fixtures can be written.
   — Issue to open: `[web-ui] Correlation Engine incident/stats API OpenAPI needed for web-ui
   client design` (labels: `question`, `service:web-ui`).

6. **BFF vs. direct-to-service architecture decision.** Solution Design §6.11 says "talks to a
   thin BFF or directly to service APIs." This spec assumes direct API calls (no BFF) because
   the architecture row lists no BFF service and the web-ui is marked as having no APIs
   exposed. If a BFF is introduced it would be a new runtime service requiring its own spec,
   published OpenAPI, and contract-change approval. A human must decide whether to introduce a
   BFF before the design stage proceeds. If the BFF decision is deferred past design, it
   becomes a contract change.
   — Issue to open: `[web-ui] Confirm BFF vs. direct-to-service architecture before design`
   (labels: `question`, `service:web-ui`).

7. **Geo-coordinate data availability in the Topology Service.** The geo-site view (MapLibre GL
   / deck.gl) requires lat/long or site coordinates for each topology site. It is not confirmed
   whether the Topology Service graph API exposes geo-coordinates or whether the Simulator
   generates them in the topology snapshot file. If coordinates are absent from the API, a
   fallback layout strategy (force-directed or fixed) must be defined at design stage. The
   Topology Service spec team should be made aware so coordinates are included in the ingestion
   API and query API if needed.

8. **Pattern Manager active-patterns API overlap with P3.** The correlation stats module
   references a Pattern Manager active-patterns list (P3). It is not confirmed whether the
   same Pattern Manager read API (open question 3) covers an approved/active filter, or whether
   a separate endpoint is required. To be confirmed when the Pattern Manager spec is authored.
