# web-ui — Design

> Authored by `@designer` using the `design` skill, from the approved spec on `web-ui`
> (the streaming/dashboard spec rework: landing dashboard, real-time streaming view,
> incident-detail drill-down, noise-filter run-stats view, cross-navigation/deep-linking,
> plus the original four modules).
> Branch: `design/web-ui-streaming-dashboard`. **Supersedes the earlier design PR #112**
> (`design/web-ui-rework`): this document carries every part of that design forward and adds
> the five new views. Merges into `web-ui` — human gate, do not self-merge.
>
> The streaming view, dashboard, incident-detail page, and noise-stats view all read
> **already-published REST APIs only** (Alarm Manager `GET /alarms` + `GET /alarms/{id}`,
> Correlation Engine `GET /incidents` + `GET /incidents/{id}` + `GET /stats`, Pattern Manager
> `GET /patterns`, Noise Filter run-stats read API). **No new backend API surface, no Kafka
> consumer, no WebSocket/SSE, no contract change** is introduced by this design — real-time is
> delivered by **client-side polling** on a configurable interval.

## Stack

- **Framework:** Angular 20, **standalone components only** (no NgModules). Bootstrapped via
  `bootstrapApplication` with `provideRouter`, `provideHttpClient(withFetch())`, and
  application-level providers.
- **Reactivity:** Angular **signals** (`signal`, `computed`, `effect`, `resource` /
  `httpResource` where it fits) as the primary state mechanism; `OnPush` change detection on
  every component. No NgRx — signal stores (plain injectable services exposing signals) are
  sufficient for a stateless client. The streaming view's poll loop is a signal-driven timer
  (see State management).
- **Typing:** strict TypeScript (`strict: true`, `noImplicitAny`, `strictNullChecks`). No `any`
  in application source. **Typed reactive forms** (`FormGroup` with typed controls) for the
  config-edit and pattern-edit forms, and for the streaming interval control.
- **HTTP clients:** one typed client service per collaborator, generated from each producer's
  **published OpenAPI 3.1** spec (`openapi-typescript` for models + a thin hand-written
  Angular `HttpClient` wrapper; no codegen against producer source). Base URL + mock/real toggle
  resolved from Angular environment config.
- **Graph & map rendering (permissive licenses only):**
  - **MapLibre GL JS** (BSD-3-Clause) for the geo-site map; **deck.gl** (MIT) optional overlay
    layer for site markers/clusters.
  - **Cytoscape.js** (MIT) for the site-level device graph and trail overlays.
  - Charts: a lightweight MIT chart lib (e.g. `ng2-charts`/Chart.js — MIT) for stats and the
    dashboard KPI sparklines.
- **Accessibility tooling:** `@angular/cdk/a11y` (FocusTrap, LiveAnnouncer), `@angular/cdk`
  virtual scroll for long lists (streaming, alarm-lifecycle, incidents), `axe-core` (dev-only
  test dependency — not shipped) for the a11y unit tests.
- **Test stack:** **Vitest + Angular TestBed** (jsdom) for unit/component tests; mock backends
  generated from each producer's OpenAPI spec (MSW — MIT — as the in-jsdom mock transport).
  Mock timers (Vitest `vi.useFakeTimers`) drive the streaming poll-cadence tests.
  **Playwright** (Apache-2.0) for E2E only, against the integration stack. Playwright is never
  the unit-test runner.
- **Build/serve:** Angular CLI (esbuild). Production build is a **static SPA**; no BFF. Served
  by nginx (BSD-2-Clause) in the container with a health route returning HTTP 200 on `/`.
- **Runtime:** Node 24 build stage (`node:24`), nginx serve stage.

License posture: Angular (MIT), MapLibre GL (BSD-3), deck.gl (MIT), Cytoscape.js (MIT), Chart.js
(MIT), MSW (MIT), axe-core (dev-only). No GPL/AGPL/BSL/source-available runtime dependency.

## Task breakdown (from the spec)

| Spec task | Realized by (modules / flow) |
|---|---|
| 1. Render the landing dashboard (home/default route) | `DashboardModule` to `DashboardComponent` + `KpiCardComponent` + `RecentIncidentsComponent` + `QuickLinksComponent`; `DashboardStore` (signals) fans out parallel reads to `CorrelationEngineClient.getStats()` + `listIncidents()`, `PatternManagerClient.listPatterns({lifecycle:'approved'})`, and `AlarmManagerClient.listAlarms()` (count). Default route `''` redirects to `/dashboard`. |
| 2. Real-time streaming view via configurable client polling | `StreamingModule` to `StreamingViewComponent` + `LivePollingService`; `LivePollingService` runs a signal-driven timer at `STREAMING_REFRESH_INTERVAL_MS`, polling `AlarmManagerClient.listAlarms()` + `CorrelationEngineClient.listIncidents()`; `DeltaDiffService` diffs previous-vs-new sets keyed by `alarmId`/`incidentId`; new/changed rows get transient highlight classes; pause/resume toggle + interval control + live/last-updated indicator. |
| 3. Render the incident-detail drill-down page | `IncidentDetailModule` to `IncidentDetailComponent`; route `/incidents/:incidentId`; reads `CorrelationEngineClient.getIncident(incidentId)` then `AlarmManagerClient.getAlarm(alarmId)` per member (root-cause + children) via parallel `forkJoin`/`Promise.all`; renders root cause, children, matched pattern/codebook + confidence, trail, per-member links into the streaming/alarm view. |
| 4. Render the noise-filter run-stats view | `CorrelationStatsModule` to `NoiseStatsComponent` (learning sub-view); reads `NoiseFilterClient.listRunStats({trailId, from, to, limit, offset})` (`GET /api/v1/run-stats`); renders one row per run with derived storm-reduction ratio; filterable by `trailId` and time range. |
| 5. Provide logical cross-navigation with deep-linkable routes | `NavigationService` builds `RouterLink`/`navigate` targets for every cross-link (pattern→`/topology?trailId=`, incident→`/incidents/:id`, member alarm→`/streaming?alarmId=`, site→`/streaming?siteId=` filtered, KPI→underlying view). All entity pages carry the ID in the URL (route param or query param) so links are shareable/bookmarkable. The **navigation map** below is the required deliverable. |
| 6. Render the geo-site topology view | `TopologyTrailsModule` to `GeoSiteMapComponent` (MapLibre GL) reads `TopologyClient.listSites()` (`GET /topology/sites`); renders one marker per `Site`; `SiteStore` signal holds the list. |
| 7. Expand a site into its device-level graph | `SiteGraphComponent` (Cytoscape.js); on marker select, `TopologyTrailsStore.selectSite(siteId)` calls `TopologyClient.objectsAtSite(siteId)`; view transitions map→graph (route `/topology/:siteId`); selected `siteId` passed in the request. |
| 8. Display device/connection attributes | `AttributeDetailPanelComponent` renders the `attributes` map from the selected node/edge; well-known keys labelled, unknown keys as generic key/value rows; `managedObjectId` always shown. |
| 9. Visualize trail clusters & per-device membership | `TrailOverlayService` reads `TrailBuilderClient.listTrails(snapshotId)` for cluster overlays and `getTrailsForObject(moid)` on device select; Cytoscape style highlights member trails. |
| 10. List & present discovered patterns with XAI | `PatternReviewModule` to `PatternListComponent` + `PatternXaiDetailComponent` read `PatternManagerClient.listPatterns()`; show sequence, support, confidence, lift, RCA, timing, codebook overlap, supporting instances. |
| 11. Accept approve/reject decisions | `PatternDecisionService.approve/reject(patternId)` posts `POST /patterns/{id}/approve` with `{decision}`; `PatternStore` updates the pattern lifecycle signal from the response. |
| 12. List active/approved patterns | `ActivePatternsComponent` reads `listPatterns({lifecycle:'approved'})`; tab/filter in `PatternReviewModule` (reused by the dashboard active-pattern count and the stats module). |
| 13. Read & edit Knowledge model params | `ConfigModule` to `ModelParamsFormComponent` (typed reactive form) reads `KnowledgeClient.getModelParams()`, submits `KnowledgeClient.updateModelParams()`; confirmation toast. |
| 14. Display live correlation stats & incidents | `CorrelationStatsModule` to `IncidentListComponent` + `StatsDashboardComponent` read `CorrelationEngineClient.listIncidents()` and `getStats()`; ratio derived client-side. |
| 15. Display live alarm lifecycle | `AlarmLifecycleComponent` reads `AlarmManagerClient.listAlarms({state})`; shows state (incl. `in-progress`/`reverted-open`), role, incidentId; filter by lifecycle state. |
| 16. Config-switchable backend integration | `ApiConfigService` resolves each base URL + `mock|real` toggle from `environment.ts`; `MockBackendProvider` (MSW) wired only when toggle is `mock`. Nine integration points (now +Noise Filter). |

Every spec task above maps to a named module/component; none dropped or re-scoped.

## Phase applicability (design view)

The web-ui is **Active in all three runtime phases** (a different set of modules is the focus of
each phase), matching the spec Phase applicability table and the canonical phase map. The app
shell is always loaded; the landing dashboard is reachable in every phase (it degrades each KPI
to "N/A"/empty when its source has no data yet). Phase-specific behaviour is which lazy module
the operator works in.

| Phase | Active/Passive/Idle | Modules/handlers exercised | Inputs/Outputs |
|---|---|---|---|
| P1 — Topology onboarding | Active | `DashboardModule` (topology KPIs available at this phase; incident/pattern KPIs show empty/N-A). `TopologyTrailsModule`: `GeoSiteMapComponent`, `SiteGraphComponent`, `AttributeDetailPanelComponent`, `LayerToggleComponent`, `TrailOverlayService`. Streaming/stats modules dormant (lazy, not loaded). | Reads: Topology site query API (`listSites`, `objectsAtSite`, neighbours, resolve); Trail Builder (`listTrails`, `getTrail`, `getTrailsForObject`). Writes: none |
| P2 — Pattern learning | Active | `DashboardModule` (active-pattern count, learning KPIs). `PatternReviewModule`: `PatternListComponent`, `PatternXaiDetailComponent`, `PatternEditDialogComponent`, `PatternDecisionService`. `ConfigModule`: `ModelParamsFormComponent`. `CorrelationStatsModule` → `NoiseStatsComponent` (noise-filter run-stats / learning sub-view). | Reads: Pattern Manager read API (`GET /patterns`, `GET /patterns/{id}`); Knowledge model-params read API; Noise Filter run-stats read API (`GET /api/v1/run-stats`). Writes: Pattern Manager approval-intent (`POST /patterns/{id}/approve`), pattern-edit (`PATCH /patterns/{id}`); Knowledge model-params edit API. |
| P3 — Real-time correlation | Active | `DashboardModule` (live KPIs). `StreamingModule`: `StreamingViewComponent`, `LivePollingService`, `DeltaDiffService`. `IncidentDetailModule`: `IncidentDetailComponent`. `CorrelationStatsModule`: `IncidentListComponent`, `StatsDashboardComponent`, `AlarmLifecycleComponent`; `ActivePatternsComponent` (reused). | Reads: Correlation Engine (`GET /incidents`, `GET /incidents/{id}`, `GET /stats`); Pattern Manager active-patterns (`GET /patterns?lifecycle=approved`); Alarm Manager (`GET /alarms`, `GET /alarms/{id}`). Writes: none |

## Module breakdown

Lazy-loaded feature modules behind a shared app shell. Each feature route is loaded with
`loadComponent` / `loadChildren` so a phase the operator is not using carries no bundle cost.

- **App shell (eager):** `AppShellComponent` (top nav with links to every page, module
  router-outlet, global error toast, `LiveAnnouncer` host), `ApiConfigService`, the nine typed
  API clients, `MockBackendProvider` (active only under the mock toggle), `ErrorBannerService`,
  `NavigationService` (builds all cross-navigation targets).
- **`DashboardModule` (lazy, default route `/dashboard`):** `DashboardComponent`,
  `KpiCardComponent`, `RecentIncidentsComponent`, `QuickLinksComponent`, `DashboardStore`
  (signals: `stats`, `incidents`, `activePatternCount`, `alarmCount`, `loading`, `error`).
- **`StreamingModule` (lazy, route `/streaming`):** `StreamingViewComponent`,
  `StreamingAlarmRowComponent`, `StreamingIncidentRowComponent`, `IntervalControlComponent`,
  `LivePollingService`, `DeltaDiffService`, `StreamingStore` (signals: `alarms`, `incidents`,
  `alarmDeltas`, `incidentDeltas`, `autoRefresh`, `intervalMs`, `lastUpdated`, `pollError`).
- **`IncidentDetailModule` (lazy, route `/incidents/:incidentId`):** `IncidentDetailComponent`,
  `MemberAlarmRowComponent`, `IncidentDetailStore` (signals: `incident`, `memberAlarms`,
  `loading`, `error`).
- **`TopologyTrailsModule` (lazy, routes `/topology`, `/topology/:siteId`):** `GeoSiteMapComponent`
  (MapLibre), `SiteGraphComponent` (Cytoscape), `LayerToggleComponent`,
  `AttributeDetailPanelComponent`, `TrailOverlayService`, `TopologyTrailsStore` (signals: `sites`,
  `selectedSite`, `graph`, `visibleLayers`, `selectedObject`, `trails`, `highlightedTrailIds`,
  `activeTrailId`).
- **`PatternReviewModule` (lazy, route `/patterns`):** `PatternListComponent`,
  `PatternXaiDetailComponent`, `PatternEditDialogComponent`, `ActivePatternsComponent`,
  `PatternDecisionService`, `PatternStore` (signals: `patterns`, `lifecycleFilter`,
  `selectedPattern`, `pendingDecision`).
- **`ConfigModule` (lazy, route `/config`):** `ModelParamsFormComponent`, `ConfigStore`
  (signals: `params`, `saveStatus`).
- **`CorrelationStatsModule` (lazy, route `/stats`):** `StatsDashboardComponent`,
  `IncidentListComponent`, `AlarmLifecycleComponent`, `NoiseStatsComponent` (learning sub-view),
  `ActivePatternsComponent` (reused), `StatsStore` (signals: `incidents`, `stats`, `alarms`,
  `alarmStateFilter`, `runStats`, `runStatsTrailFilter`).

```mermaid
flowchart TD
  Shell[AppShellComponent plus ApiConfigService plus NavigationService]
  Shell --> DB[DashboardModule]
  Shell --> ST[StreamingModule]
  Shell --> ID[IncidentDetailModule]
  Shell --> TT[TopologyTrailsModule]
  Shell --> PR[PatternReviewModule]
  Shell --> CFG[ConfigModule]
  Shell --> CS[CorrelationStatsModule]
  DB --> CEC[CorrelationEngineClient]
  DB --> PMC[PatternManagerClient]
  DB --> AMC[AlarmManagerClient]
  ST --> LPS[LivePollingService plus DeltaDiffService]
  LPS --> AMC
  LPS --> CEC
  ID --> CEC
  ID --> AMC
  TT --> TC[TopologyClient]
  TT --> TBC[TrailBuilderClient]
  PR --> PMC
  CFG --> KC[KnowledgeClient]
  CS --> CEC
  CS --> AMC
  CS --> PMC
  CS --> NFC[NoiseFilterClient]
  TC --> Resolve[ApiConfigService resolves baseUrl plus mock or real]
  TBC --> Resolve
  PMC --> Resolve
  KC --> Resolve
  CEC --> Resolve
  AMC --> Resolve
  NFC --> Resolve
```

## Data model / DB schema

**N/A — no owned store.** The web-ui is a stateless SPA per spec (Data owned: none). It holds
no persistent store and caches no domain data beyond in-memory session state (the streaming
view's last-poll snapshot for delta computation is in-memory only and is discarded on navigate-
away). Below are the **client-side view-models** (TypeScript types) the UI projects from
collaborator API responses. Field names are confirmed against each producer OpenAPI at design
time (open questions #1-#7); the types below reflect the spec contracts.

```mermaid
classDiagram
  class SiteVM {
    string siteId
    string name
    number latitude
    number longitude
    string region
  }
  class GraphObjectVM {
    string managedObjectId
    string objectType
    string name
    AttributeMap attributes
  }
  class GraphEdgeVM {
    string edgeId
    string from
    string to
    string relation
    string layer
    AttributeMap attributes
  }
  class TrailVM {
    string trailId
    string snapshotId
    string domain
    StringArray memberManagedObjectIds
  }
  class PatternVM {
    string patternId
    StringArray sequence
    number support
    number confidence
    number lift
    string rootCauseAlarmType
    TimingVM timing
    string codebookMatchId
    number instanceCount
    InstanceArray supportingInstances
    string lifecycle
    string trailId
  }
  class IncidentVM {
    string incidentId
    string rootCauseAlarmId
    StringArray childAlarmIds
    string matchedPatternId
    string matchedCodebookId
    number confidence
    string trailId
    string createdAt
  }
  class StatsVM {
    number totalAlarmsProcessed
    number totalIncidentsCreated
    number patternMatchCount
    number codebookMatchCount
    number alarmReductionRatio
  }
  class AlarmVM {
    string alarmId
    string managedObjectId
    string state
    string role
    string incidentId
    StringArray trailIds
    TransitionArray transitions
  }
  class RunStatsVM {
    string runId
    string runTimestamp
    string trailId
    string snapshotId
    string domain
    string windowStart
    string windowEnd
    DbscanParams params
    number alarmsIn
    number clustersFormed
    number alarmsKept
    number alarmsDropped
    number noiseRatio
    number stormReductionRatio
  }
  SiteVM "1" --> "many" GraphObjectVM : objects-at-site
  GraphObjectVM "many" --> "many" TrailVM : membership
  IncidentVM "1" --> "many" AlarmVM : groups
  PatternVM "1" --> "many" InstanceArray : evidence
  RunStatsVM "many" --> "1" TrailVM : per-trail runs
```

### Streaming delta / diff view-model

The streaming view keeps the **previous poll snapshot** and the **current poll snapshot** and
projects a per-row delta view-model. The diff is computed by `DeltaDiffService` (see Algorithm
logical flow + State management).

```mermaid
classDiagram
  class AlarmDeltaVM {
    string alarmId
    AlarmVM current
    string previousState
    string currentState
    DeltaKind kind
    number highlightUntilEpochMs
  }
  class IncidentDeltaVM {
    string incidentId
    IncidentVM current
    number previousChildCount
    number currentChildCount
    DeltaKind kind
    number highlightUntilEpochMs
  }
  class DeltaKind {
    enum NEW
    enum CHANGED
    enum UNCHANGED
    enum GREW
  }
  AlarmDeltaVM --> DeltaKind : kind
  IncidentDeltaVM --> DeltaKind : kind
```

Notes:
- `DeltaKind` for alarms: `NEW` (alarmId absent from previous snapshot), `CHANGED` (alarmId
  present in both but `state` differs — covers `open` to `in-progress`, `in-progress` to
  `correlated`, any to `reverted-open` i.e. back to `open`, and to `cleared`), `UNCHANGED`
  (present in both, state equal).
- `DeltaKind` for incidents: `NEW` (incidentId absent from previous), `GREW` (`childAlarmIds`
  length increased), `UNCHANGED` otherwise.
- `highlightUntilEpochMs` drives the transient highlight CSS class; once `Date.now()` passes it,
  the row drops back to its resting style (see Accessibility — prefers-reduced-motion).
- `attributes` is rendered as an open key/value map; well-known keys (`vendor`, `model`,
  `equipmentType`, `role`, `capacity` on nodes; `linkType`, `capacity`, `protectionRole` on
  edges) get friendly labels, all other keys render as generic rows. The UI never validates the
  attribute schema (Knowledge Service owns the catalogue).
- `alarmReductionRatio` is **computed client-side** as `totalAlarmsProcessed` divided by
  `totalIncidentsCreated` from the Correlation Engine `GET /stats` raw counts. RCA accuracy is
  not returned by the stats API (evaluated offline per the Correlation Engine spec); the
  dashboard/stats view show "evaluated offline" rather than fabricating a value.
- `stormReductionRatio` on `RunStatsVM` is computed client-side as `alarmsIn / clustersFormed`
  when the Noise Filter API does not return it directly (guarded for `clustersFormed` equal to 0).
- `domain` on `RunStatsVM` may be absent/null in some rows; the UI handles it gracefully.

## Event handling

- **Consumers:** **none.** The web-ui never subscribes to Kafka topics directly (spec: Consumes
  Kafka — none). All data arrives over collaborator REST APIs. The "real-time" streaming view is
  **client-side polling of existing REST endpoints**, not a Kafka consumer and not a WebSocket
  or SSE connection.
- **Producers:** **none.** The web-ui never publishes to Kafka. The architecture row "produces
  `patterns.approved` (via API)" is realized by `POST /patterns/{id}/approve` to the Pattern
  Manager, which owns the lifecycle transition and is the sole emitter of `PatternApprovedEvent`.

## API contracts / API schema

**Exposed:** N/A — no HTTP surface other than the static-asset server returning HTTP 200 on `/`
for liveness. The web-ui publishes **no** OpenAPI document (spec: APIs exposed — none).

**Consumed** (nine integration points; each typed client built from the producer published
OpenAPI 3.1; no hard-coded URLs). Request/response shapes below reflect the collaborator specs;
exact field names/pagination are reconciled against each producer `openapi.json` at design time
(open questions #1-#7).

### Topology Service (P1)
- `GET /topology/sites` returns `200 [{ siteId, name, latitude, longitude, region }]` — list sites.
- `GET /topology/sites/{siteId}/objects` (objects-at-site) returns `200 { nodes, edges }` where
  each node/edge carries `managedObjectId` and `attributes`.
- `GET /topology/nodes/{managedObjectId}` returns `200 { managedObjectId, objectType, layer,
  attributes }` — resolve object plus layer.
- `GET /topology/nodes/{managedObjectId}/neighbors` returns `200 { neighbors }`.
- Errors: `404` (unknown object) shows empty-state in panel; `5xx` shows error banner.

### Trail Builder (P1)
- `GET /trails?snapshotId=X&domain=core-ip` (`listTrails`) returns `200 [{ trailId, memberCount,
  domain, snapshotId }]`.
- `GET /trails/{trailId}` (`getTrail`) returns `200 { trailId, snapshotId, domain, members }`.
- `GET /trails/by-object?managedObjectId=X&domain=core-ip` (`getTrailsForObject`) returns
  `200 [{ trailId }]`.

### Pattern Manager (P2 + P3 active-patterns + dashboard active-pattern count)
- `GET /patterns?lifecycle=draft|approved|deprecated` returns `200 [PatternVM]` (full XAI
  metadata: `sequence`, `support`, `confidence`, `lift`, `rootCauseAlarmType`, `timing`,
  `codebookMatchId`, `instanceCount`, `supportingInstances`, `lifecycle`, `trailId`).
- `GET /patterns/{patternId}` returns `200 PatternVM` (full detail) or `404`.
- `POST /patterns/{patternId}/approve` body `{ decision, reviewer, notes }` (decision =
  approve or reject) returns `200 PatternVM` (updated lifecycle).
- `PATCH /patterns/{patternId}` body `{ sequenceFlags, reviewer, notes }` (each flag
  `{ index, optional }`) returns `200 PatternVM` (edited). Allowed only when `lifecycle` is
  `draft`; otherwise `409/422`, surfaced.

### Knowledge Service (P2 — config)
- `GET /knowledge/model-params` returns `200 { dbscanEps, dbscanMinSamples,
  sessionWindowGapSeconds, minSupport }`.
- `PUT or PATCH /knowledge/model-params` body `{ editedParams }` returns `200 { persisted }` or
  `422` validation error.

### Correlation Engine (P3 + dashboard + streaming + incident-detail)
- `GET /incidents?trailId=X&from=Y&to=Z&matchType=W` returns `200 [IncidentVM]` (`incidentId`,
  `rootCauseAlarmId`, `childAlarmIds`, `matchedPatternId`, `matchedCodebookId`, `confidence`,
  `trailId`, `createdAt`). Used by dashboard, streaming view, and stats module.
- `GET /incidents/{incidentId}` returns `200 IncidentVM` or `404`. **Used by the incident-detail
  page**; carries `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId`/`matchedCodebookId`,
  `confidence`, `trailId` — all fields the incident-detail page needs (per Correlation Engine
  spec, no contract change).
- `GET /stats` returns `200 { totalAlarmsProcessed, totalIncidentsCreated, patternMatchCount,
  codebookMatchCount, confidenceDistribution }`. UI computes `alarmReductionRatio`. RCA accuracy
  is not returned (evaluated offline) — UI surfaces a note.

### Alarm Manager (P3 + streaming + incident-detail)
- `GET /alarms?state=open|in-progress|correlated|cleared&trailId=X&incidentId=Y&from=A&to=B`
  returns `200 [AlarmVM]` (paginated) with `state` (incl. `in-progress`; `reverted-open` is
  modelled as a transition back to `open` with an audit reason), `role`
  (root-cause/child/none), `incidentId`. Used by the streaming view and the alarm-lifecycle view.
- `GET /alarms/{alarmId}` returns `200 AlarmVM` with ordered `transitions` (UTC timestamps).
  Used by the incident-detail page per member alarm.

### Noise Filter (P2 — run-stats / learning sub-view) **[NEW client]**
- `GET /api/v1/run-stats?trailId=X&from=Y&to=Z&limit=L&offset=O` returns `200 RunStatsPage`
  `{ items: [RunStatsRow], total, limit, offset }`, newest first. Each `RunStatsRow`:
  `runId`, `runTimestamp`, `trailId`, `snapshotId`, `domain` (optional/null), `windowStart`,
  `windowEnd`, `eps`, `minSamples`, `windowSize`, `algorithm`, `alarmsIn`, `clustersFormed`,
  `alarmsKept`, `alarmsDropped`, `noiseRatio`. The UI computes the storm-reduction ratio
  (`alarmsIn / clustersFormed`) when not directly returned.
- `GET /api/v1/run-stats/{runId}` returns `200 RunStatsRow` or `404`.
- Validation errors (bad `limit`, malformed `from`/`to`) return `422`; surfaced as an error
  banner in the noise-stats sub-view. Exact paths/params confirmed against the Noise Filter
  `openapi.json` (open question #7).

**OpenAPI usage:** the web-ui generates TypeScript models from each producer checked-in
`openapi.json` (`openapi-typescript`) at design time and regenerates on a collaborator contract
change (a collaborator API change is a contract change — architecture.md update plus human
approval before the client is updated, per the spec Non-functional "API contract").

## Integration points (mock vs. real)

Resolution is by Angular environment config — never a hard-coded URL. `ApiConfigService` reads a
per-service base URL and a `mock|real` toggle. Under `mock`, `MockBackendProvider` registers MSW
handlers generated from the producer OpenAPI; under `real`, calls go to the Compose address.

| Integration point | Client | Config key (base URL) | Mock (unit) | Real (integration) |
|---|---|---|---|---|
| Topology site query + objects-at-site + graph | `TopologyClient` | `TOPOLOGY_API_BASE_URL` | MSW from Topology `openapi.json` | Topology Service (Compose) |
| Trail Builder trail-viz | `TrailBuilderClient` | `TRAIL_BUILDER_API_BASE_URL` | MSW from Trail Builder `openapi.json` | Trail Builder (Compose) |
| Pattern Manager read | `PatternManagerClient` | `PATTERN_MANAGER_API_BASE_URL` | MSW from Pattern Manager `openapi.json` | Pattern Manager (Compose) |
| Pattern Manager approval-intent | `PatternManagerClient` | `PATTERN_MANAGER_API_BASE_URL` | MSW handler | Pattern Manager (Compose) |
| Pattern Manager pattern-edit (PATCH) | `PatternManagerClient` | `PATTERN_MANAGER_API_BASE_URL` | MSW handler | Pattern Manager (Compose) |
| Knowledge model-params | `KnowledgeClient` | `KNOWLEDGE_API_BASE_URL` | MSW from Knowledge `openapi.json` | Knowledge Service (Compose) |
| Correlation Engine incident/stats (+single incident) | `CorrelationEngineClient` | `CORRELATION_ENGINE_API_BASE_URL` | MSW from Correlation `openapi.json` | Correlation Engine (Compose) |
| Alarm Manager lifecycle query (+single alarm) | `AlarmManagerClient` | `ALARM_MANAGER_API_BASE_URL` | MSW from Alarm Manager `openapi.json` | Alarm Manager (Compose) |
| Noise Filter run-stats read | `NoiseFilterClient` | `NOISE_FILTER_API_BASE_URL` | MSW from Noise Filter `openapi.json` | Noise Filter (Compose) |

Pattern Manager read/approval/edit share one base URL but are three logical integration points;
nine total integration points per spec AC 50/51 (Topology, Trail Builder, Pattern read, Pattern
approval, Knowledge, Correlation Engine, Alarm Manager, Noise Filter — and the Pattern edit
shares the Pattern Manager URL). `INTEGRATION_MODE=mock|real`, the per-service base URLs, and
`STREAMING_REFRESH_INTERVAL_MS` are injected into `environment.ts` from Docker Compose
environment variables at build/serve time.

## State management

Signal-based injectable stores (no NgRx). Each feature has a store exposing `signal`s and
`computed`s; components are `OnPush` and read signals directly.

### LivePollingService (streaming)

The streaming poll loop is the only timer in the app. Design:

- **Interval source:** `intervalMs` signal, initialized from `STREAMING_REFRESH_INTERVAL_MS`
  (environment; default `3000`). The operator can change it at runtime via the
  `IntervalControlComponent`; writing the signal restarts the timer with the new period.
- **Timer:** rxjs `timer(0, intervalMs())` bridged to the poll, **or** a self-rescheduling
  `setTimeout`/`effect` loop keyed off `intervalMs` and `autoRefresh`. The first tick fires
  immediately, then every `intervalMs`. A `computed`/`effect` reacts to `autoRefresh` and
  `intervalMs` changes: when `autoRefresh()` is `false` (paused) the loop is torn down; when it
  flips back to `true` (resume) the loop restarts at the configured interval.
- **Per tick:** call `AlarmManagerClient.listAlarms()` and `CorrelationEngineClient.listIncidents()`
  in parallel. On success: pass `(previousSnapshot, newSnapshot)` to `DeltaDiffService`, write
  the resulting `alarmDeltas`/`incidentDeltas` signals and the new snapshots, and set
  `lastUpdated = now`. On failure: set `pollError` (stale-data indicator), keep the previous
  snapshot, do not crash; the next tick retries.
- **No overlap:** a tick that is still in flight when the next would fire is skipped (a
  `pollInFlight` guard) so slow responses do not stack.
- **Pause/resume:** `autoRefresh` signal toggled by the pause/resume button. While paused, **no**
  HTTP call is made to Alarm Manager or Correlation Engine.
- **Cleanup:** the loop is torn down in the component's `ngOnDestroy`/`DestroyRef` so navigating
  away stops polling. The last snapshot is discarded (not persisted across sessions).

### DeltaDiffService

Pure function, no I/O. Keys the previous and new arrays by `alarmId`/`incidentId` into `Map`s,
then for each current item computes a `DeltaKind` (see Algorithm logical flow). It produces a new
array of delta view-models; only the changed/new rows carry a non-expired
`highlightUntilEpochMs`. This keeps re-render scoped: the template `@for` tracks by `alarmId`/
`incidentId`, so Angular updates only changed rows (spec performance: no full-list re-render per
poll).

### Other stores

- `DashboardStore`: parallel reads on load; each KPI is a `computed` over its source signal,
  degrading to "N/A"/empty when the source is zero/absent. No timer (one-shot load + manual
  refresh button).
- `IncidentDetailStore`: load incident by route param, then fan out member-alarm reads.
- `StatsStore`, `TopologyTrailsStore`, `PatternStore`, `ConfigStore`: one-shot reads + filter
  signals (carried forward from the prior design).

## Key flows (sequence / data-flow diagrams)

### Flow 1 — Real-time streaming (P3): poll, diff, animate deltas

```mermaid
sequenceDiagram
  actor Operator
  participant View as StreamingViewComponent
  participant LPS as LivePollingService
  participant AMC as AlarmManagerClient
  participant CEC as CorrelationEngineClient
  participant Diff as DeltaDiffService
  Operator->>View: open streaming view
  View->>LPS: start with intervalMs from env default 3000
  loop every intervalMs while autoRefresh is on
    LPS->>AMC: listAlarms
    LPS->>CEC: listIncidents
    AMC-->>LPS: current alarm set
    CEC-->>LPS: current incident set
    LPS->>Diff: diff previous and current keyed by id
    Diff-->>LPS: alarmDeltas and incidentDeltas with kind new or changed or grew
    LPS->>View: write delta signals and lastUpdated
    View->>View: apply transient highlight on new or changed rows
    View->>Operator: announce new alarms via LiveAnnouncer
  end
  Operator->>View: click pause
  View->>LPS: set autoRefresh false
  Note over LPS: no further polling calls until resume
  Operator->>View: change interval to 10000
  View->>LPS: set intervalMs 10000 then restart timer
```

### Flow 2 — Landing dashboard (default route): parallel KPI load

```mermaid
sequenceDiagram
  actor Operator
  participant Dash as DashboardComponent
  participant Store as DashboardStore
  participant CEC as CorrelationEngineClient
  participant PMC as PatternManagerClient
  participant AMC as AlarmManagerClient
  Operator->>Dash: navigate to root then redirect to dashboard
  Dash->>Store: load
  par parallel KPI reads
    Store->>CEC: getStats
    Store->>CEC: listIncidents
    Store->>PMC: listPatterns lifecycle approved
    Store->>AMC: listAlarms count
  end
  CEC-->>Store: raw counts
  CEC-->>Store: incidents
  PMC-->>Store: approved patterns
  AMC-->>Store: alarms
  Store->>Store: compute alarm reduction ratio and N-A when zero incidents
  Store->>Dash: render KPI cards plus recent incidents plus quick links
  Operator->>Dash: click live incident count KPI
  Dash->>Dash: navigate to stats incidents view
```

### Flow 3 — Incident-detail drill-down: incident plus member alarms

```mermaid
sequenceDiagram
  actor Operator
  participant Detail as IncidentDetailComponent
  participant Store as IncidentDetailStore
  participant CEC as CorrelationEngineClient
  participant AMC as AlarmManagerClient
  Operator->>Detail: open incidents incidentId deep link
  Detail->>Store: load incidentId from route param
  Store->>CEC: getIncident incidentId
  CEC-->>Store: incident with rootCause and children and pattern and confidence and trail
  par fetch each member alarm
    Store->>AMC: getAlarm rootCauseAlarmId
    Store->>AMC: getAlarm each childAlarmId
  end
  AMC-->>Store: alarm records with state and role and transitions
  Store->>Detail: render root cause then children then matched pattern then trail
  Operator->>Detail: click a member alarm link
  Detail->>Detail: navigate to streaming with alarmId query param
```

### Flow 4 — Cross-navigation deep-link: pattern to trail, incident to detail

```mermaid
sequenceDiagram
  actor Operator
  participant Pat as PatternListComponent
  participant Nav as NavigationService
  participant Topo as TopologyTrailsModule
  participant Stats as CorrelationStatsModule
  Operator->>Pat: click view trail on a pattern
  Pat->>Nav: navigate to topology with trailId query param
  Nav->>Topo: activate route topology trailId equals id
  Topo->>Topo: load topology then activate trail by id
  Operator->>Stats: click an incident row
  Stats->>Nav: navigate to incidents incidentId
  Nav->>Stats: route to incident-detail page
```

### Flow 5 — Topology & trails (P1): geo map to site graph to trail highlight

```mermaid
sequenceDiagram
  actor Operator
  participant Map as GeoSiteMapComponent
  participant Store as TopologyTrailsStore
  participant TC as TopologyClient
  participant Graph as SiteGraphComponent
  participant TBC as TrailBuilderClient
  Operator->>Map: open topology module
  Map->>TC: listSites
  TC-->>Map: sites with geo coords
  Map->>Map: render one marker per site
  Operator->>Map: select a site
  Map->>Store: selectSite siteId
  Store->>TC: objectsAtSite siteId
  TC-->>Store: nodes and edges with attributes
  Store->>Graph: render device graph plus layer toggles
  Graph->>TBC: listTrails snapshotId
  TBC-->>Graph: trail clusters
  Graph->>Graph: overlay trail boundaries
  Operator->>Graph: select a device
  Graph->>TBC: getTrailsForObject managedObjectId
  TBC-->>Graph: trails for object
  Graph->>Graph: highlight member trails distinctly
  Graph->>Operator: detail panel shows attributes
```

### Flow 6 — Pattern review & XAI (P2): review, edit optional alarm, approve

```mermaid
sequenceDiagram
  actor Operator
  participant List as PatternListComponent
  participant PMC as PatternManagerClient
  participant Edit as PatternEditDialogComponent
  participant Dec as PatternDecisionService
  Operator->>List: open pattern review
  List->>PMC: listPatterns lifecycle draft
  PMC-->>List: patterns with XAI metadata
  Operator->>List: expand a pattern for full XAI
  Operator->>Edit: open edit on a draft pattern
  Edit->>PMC: PATCH patterns id mark alarm optional
  PMC-->>Edit: updated pattern with optional marker
  Operator->>Dec: click Approve
  Dec->>PMC: POST patterns id approve decision approve
  PMC-->>Dec: pattern lifecycle approved
  Dec->>List: update lifecycle signal to approved
```

### Flow 7 — Config (P2): read and edit Knowledge model params

```mermaid
sequenceDiagram
  actor Operator
  participant Form as ModelParamsFormComponent
  participant KC as KnowledgeClient
  Operator->>Form: open config module
  Form->>KC: getModelParams
  KC-->>Form: current params
  Form->>Form: populate typed reactive form
  Operator->>Form: edit a value then submit
  alt invalid value
    Form->>Form: show validation error, no API call
  else valid value
    Form->>KC: updateModelParams edited values
    KC-->>Form: persisted params
    Form->>Operator: confirm success
  end
```

### Flow 8 — Correlation stats (P3): incidents, stats, alarm lifecycle, noise run-stats

```mermaid
sequenceDiagram
  actor Operator
  participant Dash as StatsDashboardComponent
  participant CEC as CorrelationEngineClient
  participant Inc as IncidentListComponent
  participant ALC as AlarmLifecycleComponent
  participant AMC as AlarmManagerClient
  participant NS as NoiseStatsComponent
  participant NFC as NoiseFilterClient
  Operator->>Dash: open stats module
  Dash->>CEC: getStats
  CEC-->>Dash: raw counts
  Dash->>Dash: compute alarm reduction ratio
  Dash->>Inc: render incidents
  Inc->>CEC: listIncidents
  CEC-->>Inc: incidents with root cause and children
  Operator->>ALC: view alarm lifecycle
  ALC->>AMC: listAlarms state filter
  AMC-->>ALC: alarms with state role incidentId
  Operator->>NS: open noise run-stats sub-view
  NS->>NFC: listRunStats trailId filter
  NFC-->>NS: run-stats rows
  NS->>NS: compute storm reduction ratio per row
```

## Algorithm logical flow

The web-ui implements **no domain algorithm** (no matching, scoring, RCA, mining — all owned by
backend services). The only non-trivial client-side logic is presentation/diff logic:

1. **Streaming delta diff (AC 7, 8, 9):** key the previous and current alarm arrays by `alarmId`
   into `Map`s. For each current alarm: if absent from previous then `NEW`; else if `state`
   differs then `CHANGED`; else `UNCHANGED`. The same keyed-diff over incidents by `incidentId`:
   absent then `NEW`; `childAlarmIds.length` increased then `GREW`; else `UNCHANGED`. The
   `NEW`/`CHANGED`/`GREW` rows get `highlightUntilEpochMs = now + HIGHLIGHT_MS`. The template
   tracks by id so only the affected rows re-render.
2. **Layer-toggle filtering (AC 28):** each edge carries a `layer` (fiber/IP/IGP/LSP/service).
   `visibleLayers` is a signal set; the Cytoscape graph applies a style filter showing only edges
   whose `layer` is in the set. All layers off then only nodes render. Pure derived view.
3. **Trail highlight (AC 32):** on device select, `getTrailsForObject` returns the member
   `trailId` set; `highlightedTrailIds` signal drives a Cytoscape class that styles member trails
   distinctly. A device may be in many overlapping trails.
4. **Alarm-reduction ratio (AC 1, 45):** ratio is `totalAlarmsProcessed / totalIncidentsCreated`;
   guard divide-by-zero then display "N/A" when `totalIncidentsCreated` is 0.
5. **Storm-reduction ratio (AC 18):** `alarmsIn / clustersFormed` per run; guard
   `clustersFormed` equal to 0.
6. **Lifecycle filter (AC 48):** `alarmStateFilter` signal; `computed` filters the `alarms`
   signal by `state` (incl. `in-progress`, `reverted-open`).

```mermaid
flowchart TD
  Start[new poll alarm set arrives] --> Key[index previous set by alarmId]
  Key --> Loop{for each current alarm}
  Loop --> Present{alarmId in previous set}
  Present -->|no| New[kind NEW then set highlight]
  Present -->|yes| Same{state equals previous state}
  Same -->|no| Changed[kind CHANGED then set highlight]
  Same -->|yes| Unchanged[kind UNCHANGED no highlight]
  New --> Emit[emit delta view-model]
  Changed --> Emit
  Unchanged --> Emit
  Emit --> Render[template tracks by alarmId then updates changed rows only]
```

## Seed data & examples

The web-ui ships **test fixtures** (mock-backend responses) generated from each producer
published OpenAPI, used by Vitest/TestBed. Representative fixtures:

- `fixtures/topology/sites.json` — two sites e.g. London PoP (lat 51.5, lon -0.12, region
  EU-West) and Frankfurt PoP (at least 2 sites, AC 26).
- `fixtures/topology/objects-at-site-1.json` — nodes incl. a device with attributes
  `vendor=Acme, model=R8000, equipmentType=router, slotCount=16` (three well-known keys plus one
  extra, AC 29) and an edge with attributes `linkType=fiber, capacity=100G,
  protectionRole=primary` (AC 30); edges tagged with `layer` across fiber/IP/IGP/LSP/service
  (AC 28).
- `fixtures/trails/list.json` + `fixtures/trails/by-object.json` — a device present in at least 2
  trails (AC 31, 32).
- `fixtures/patterns/discovered.json` — patterns with full XAI; mixed `lifecycle`
  (draft/approved) for AC 34, 35, 38; one `draft` pattern editable for AC 54; each pattern
  carries a `trailId` for the pattern→topology cross-link (AC 21).
- `fixtures/knowledge/model-params.json` — `dbscanEps, dbscanMinSamples,
  sessionWindowGapSeconds, minSupport` (AC 40).
- `fixtures/correlation/stats.json` — known `totalAlarmsProcessed`/`totalIncidentsCreated` so the
  reduction ratio is a known value (AC 1, 45); a variant with `totalIncidentsCreated=0` for the
  N/A case (AC 1).
- `fixtures/correlation/incidents.json` + `incident-detail.json` — incidents with root-cause plus
  children, `matchedPatternId`/`matchedCodebookId`, `confidence`, `trailId` (AC 14, 44).
- `fixtures/alarms/lifecycle.json` — alarms in all five states (open/in-progress/correlated/
  cleared/reverted-open) with role plus `incidentId` (AC 47, 48).
- `fixtures/streaming/poll-a.json` + `poll-b.json` — two successive poll snapshots: `poll-b` adds
  one new alarm (AC 7), changes one alarm `open` to `in-progress` (AC 8), changes one
  `in-progress` to `correlated` and one to `reverted-open` (AC 9), and grows one incident.
- `fixtures/alarms/alarm-{id}.json` — per-member single-alarm records for the incident-detail
  page (AC 15).
- `fixtures/noise/run-stats.json` — at least 2 run rows across two distinct `trailId` values, with
  `alarmsIn`, `clustersFormed`, `alarmsKept`, `alarmsDropped`, `noiseRatio` (AC 18, 19).

Example streaming delta (poll-a to poll-b):

```
poll-a alarms: a-1 open,  a-2 open,        a-3 in-progress
poll-b alarms: a-1 open,  a-2 in-progress, a-3 correlated, a-4 open, a-5 reverted-open
delta: a-2 CHANGED (open to in-progress), a-3 CHANGED (in-progress to correlated),
       a-4 NEW, a-5 CHANGED (to reverted-open i.e. open), a-1 UNCHANGED
```

## UI wireframes

ASCII layouts (non-mermaid fenced blocks so they are not parsed as diagrams).

### New page — Landing dashboard (default route `/dashboard`)

```
+-------------------------------------------------------------------------+
| [Dashboard] [Streaming] [Topology] [Patterns] [Config] [Stats]  shell   |
+-------------------------------------------------------------------------+
| Platform overview                                       [Refresh]       |
| +------------------+ +------------------+ +------------------+           |
| | Live incidents   | | Active patterns  | | Alarm reduction  |           |
| |       12         | |        5         | |      8.3 : 1     |           |
| | (click to stats) | | (click patterns) | | (N/A if 0 inc.)  |           |
| +------------------+ +------------------+ +------------------+           |
| +------------------+ +------------------+                                |
| | Alarms processed | | RCA accuracy     |                                |
| |      1280        | | evaluated offline|                                |
| +------------------+ +------------------+                                |
+-------------------------------------------------------------------------+
| Recent incidents                          | Quick links                 |
|  INC-12 root LOS at FiberSpan  -> detail  |  -> Streaming (live)        |
|  INC-11 root LinkDown          -> detail  |  -> Topology + trails       |
|  INC-10 root AdjDown           -> detail  |  -> Pattern review          |
|                                           |  -> Config (Knowledge)      |
|                                           |  -> Correlation stats       |
+-------------------------------------------------------------------------+
```
Reads: Correlation Engine `getStats` + `listIncidents`; Pattern Manager `listPatterns?lifecycle=approved`;
Alarm Manager `listAlarms` (count). Each KPI card + recent-incident row is a deep link.

### New page — Real-time streaming view (`/streaming`)

```
+-------------------------------------------------------------------------+
| Streaming (live)   * LIVE   last updated 12:04:07                        |
|   interval [ 3000 ] ms   [Pause]   (env default; operator-adjustable)   |
+-------------------------------------------------------------------------+
| Alarms (newest highlighted)            | Incidents (forming)            |
|  alarmId state        role   inc       |  incidentId  root      childs  |
|  a-4*  open       (NEW)   --   --       |  INC-12* (GREW)  LOS    3 -> 4  |
|  a-2~  in-progress(CHG)   --   --       |  INC-11   LinkDown      2       |
|  a-3~  correlated (CHG)   child INC-12  |  INC-10   AdjDown       2       |
|  a-5~  open (reverted-open)(CHG) -- --  |                                |
|  a-1   open               --   --       |  -> click incident for detail  |
|  (* = new this poll, ~ = changed; transient highlight then fades;       |
|   respects prefers-reduced-motion: no animation, static badge instead)  |
+-------------------------------------------------------------------------+
```
Reads (polled every interval): Alarm Manager `GET /alarms`, Correlation Engine `GET /incidents`.
No backend streaming. Pause stops all polling. Clicking an alarm/incident deep-links out.

### New page — Incident-detail drill-down (`/incidents/:incidentId`)

```
+-------------------------------------------------------------------------+
| Incident INC-12          trail TR-7      confidence 0.91                 |
| Matched pattern PAT-3 (LOS, LinkDown, AdjDown)   [view pattern]         |
|   (or matched codebook CB-2 when matchedCodebookId is set)              |
+-------------------------------------------------------------------------+
| Root-cause alarm                                                        |
|  a-3  LOS at FiberSpan-9   state correlated   role root-cause           |
|       -> view in streaming/alarm view                                   |
+-------------------------------------------------------------------------+
| Child alarms (N-1)                                                       |
|  a-7  LinkDown   state correlated  role child   -> view alarm           |
|  a-8  AdjDown    state correlated  role child   -> view alarm           |
+-------------------------------------------------------------------------+
| trail TR-7  -> view trail in topology                                   |
+-------------------------------------------------------------------------+
```
Reads: Correlation Engine `GET /incidents/{incidentId}`; Alarm Manager `GET /alarms/{alarmId}` per member.
Deep-linkable (loads directly from URL). Every member alarm + the trail is a cross-link.

### New view — Noise-filter run-stats (learning sub-view in `/stats`)

```
+-------------------------------------------------------------------------+
| Stats  tabs: [Incidents] [Alarm lifecycle] [Noise run-stats]            |
+-------------------------------------------------------------------------+
| Noise-filter run-stats           filter trailId [ TR-7 ] [from][to]     |
|  run     trail  in   clusters kept drop  noiseRatio  stormReduction     |
|  RUN-9   TR-7   240   12       180  60    0.25        20.0 : 1           |
|  RUN-8   TR-7   180   10       150  30    0.17        18.0 : 1           |
|  (stormReduction = alarmsIn / clustersFormed, computed if not returned; |
|   filtering by trailId hides rows for other trails; domain may be null) |
+-------------------------------------------------------------------------+
```
Reads: Noise Filter `GET /api/v1/run-stats?trailId=&from=&to=`.

### Existing — Topology & trails (`/topology`, `/topology/:siteId`)

```
+-------------------------------------------------------------+
| [Dashboard][Streaming][topology][patterns][config][stats]   |
+-------------------------------------------------------------+
| Geo-site map (MapLibre)                                     |
|   . London PoP        . Frankfurt PoP    . Madrid PoP       |
|   (markers, one per Site; click to expand)                  |
+-------------------------------------------------------------+
  (after selecting a site, transitions to /topology/:siteId)
+----------------------------------+--------------------------+
| Site graph (Cytoscape)           | Detail panel             |
|   o--o  o   trail cluster overlay | managedObjectId: ...     |
|   |    \                          | vendor: Acme             |
|   o     o                         | model: R8000             |
| Layers: [x]fiber [x]IP [ ]IGP     | equipmentType: router    |
|         [ ]LSP [ ]service         | slotCount: 16 (generic)  |
| (selected device highlights its   | --- trails ---           |
|  member trails distinctly)        | trail-A, trail-C         |
+----------------------------------+--------------------------+
| (deep link in: /topology?trailId=<id> activates that trail) |
+-------------------------------------------------------------+
```
Reads: Topology `listSites`, `objectsAtSite`; Trail Builder `listTrails`, `getTrailsForObject`.

### Existing — Pattern review & XAI (`/patterns`)

```
+-------------------------------------------------------------+
| Patterns   tabs: [Discovered (draft)] [Active (approved)]   |
+-------------------------------------------------------------+
| seq            sup  conf lift RCA        codebook  lifecycle |
| LOS,LinkDown.. 0.12 0.90 4.2 LOS         match-7   draft  v  |
|   v expanded XAI: timing (median IAT, timeframe),           |
|     instanceCount, supportingInstances list                 |
|     [Approve] [Reject] [Edit] [View trail -> topology]      |
|     (Edit only for draft; View trail uses pattern.trailId)  |
+-------------------------------------------------------------+
  Edit dialog (placeholder):
  [ ] LOS  [x] LinkDown optional  [ ] AdjDown   reviewer:____
  notes:__________________________  [Submit PATCH] [Cancel]
```
Reads: Pattern Manager `GET /patterns`. Writes: `POST /patterns/{id}/approve`, `PATCH /patterns/{id}`.

### Existing — Config (`/config`)

```
+-------------------------------------------------------------+
| Model parameters (Knowledge Service)                        |
|   DBSCAN eps          [ 0.5  ]                               |
|   DBSCAN minSamples   [ 5    ]                               |
|   session-window gap  [ 300  ] seconds  (must be 0 or more) |
|   min-support         [ 0.05 ]                              |
|                                   [Save]  status: saved OK  |
|   (negative gap shows inline validation error, no API call) |
+-------------------------------------------------------------+
```
Reads: Knowledge `getModelParams`. Writes: `updateModelParams`.

### Existing — Correlation stats (`/stats`, Incidents + Alarm-lifecycle tabs)

```
+-------------------------------------------------------------+
| Stats dashboard                                             |
|  alarm-reduction ratio: 8.3   RCA accuracy: evaluated offl. |
|  pattern matches: 42   codebook matches: 17                 |
+------------------------------+------------------------------+
| Incidents                    | Alarm lifecycle              |
|  INC-1 root: LOS  -> detail  | filter: all open inprog corr |
|    children: LinkDown, AdjDn |        cleared reverted-open  |
|  INC-2 root: ...  -> detail  | alarmId  state       role inc|
|                              | a-1   correlated     root INC1
|                              | a-2   in-progress    --   -- |
|                              | a-9   reverted-open  --   -- |
+------------------------------+------------------------------+
```
Reads: Correlation Engine `listIncidents`, `getStats`; Alarm Manager `listAlarms`. Each incident
row deep-links to `/incidents/:incidentId`.

## Navigation map (required deliverable)

The page graph below documents every page, its route, and the logical cross-navigation flows
between them. All entity pages carry the ID in the URL (route param or query param) so links are
shareable and bookmarkable.

### Route table

| Route | Page / component | Deep-link param | Reached from |
|---|---|---|---|
| `/` | redirect to `/dashboard` | — | app entry |
| `/dashboard` | `DashboardComponent` (default) | — | nav bar, any page |
| `/streaming` | `StreamingViewComponent` | `?alarmId=` (optional focus), `?siteId=` (optional filter) | nav bar, dashboard quick link, incident-detail member alarm, topology site |
| `/topology` | `GeoSiteMapComponent` | `?trailId=` (activate trail) | nav bar, pattern view trail, incident-detail trail |
| `/topology/:siteId` | `SiteGraphComponent` | `:siteId` | geo map site select |
| `/patterns` | `PatternReviewModule` | — | nav bar, dashboard active-pattern KPI, incident-detail matched pattern |
| `/incidents/:incidentId` | `IncidentDetailComponent` | `:incidentId` | dashboard recent incidents + incident KPI, stats incident row, streaming incident row, direct deep link |
| `/config` | `ModelParamsFormComponent` | — | nav bar, dashboard quick link |
| `/stats` | `CorrelationStatsModule` (Incidents / Alarm-lifecycle / Noise run-stats tabs) | — | nav bar, dashboard incident-count KPI, dashboard quick link |

### Page graph + logical flows

```mermaid
flowchart TD
  Dash[Dashboard slash dashboard default]
  Stream[Streaming slash streaming]
  Topo[Topology slash topology]
  Site[Site graph slash topology siteId]
  Pat[Patterns slash patterns]
  Inc[Incident detail slash incidents incidentId]
  Cfg[Config slash config]
  Stats[Stats slash stats incidents alarms noise]
  Dash -->|incident count KPI| Stats
  Dash -->|active pattern KPI| Pat
  Dash -->|recent incident| Inc
  Dash -->|quick link| Stream
  Dash -->|quick link| Topo
  Dash -->|quick link| Cfg
  Stats -->|incident row| Inc
  Stream -->|incident row| Inc
  Stream -->|alarm row| Inc
  Inc -->|member alarm| Stream
  Inc -->|matched pattern| Pat
  Inc -->|trail link| Topo
  Pat -->|view trail trailId| Topo
  Topo -->|select site| Site
  Site -->|live alarms on site| Stream
  Stats -->|noise run-stats tab| Stats
```

## Error handling

First-class. The SPA must never crash whole-app on one backend failure; each module degrades
independently.

- **No Kafka / no schemaVersion handling:** N/A — the web-ui consumes no Kafka topics, so there
  is no DLQ routing and no `schemaVersion` rejection in this service.
- **Backend 5xx / unreachable (AC 53):** each module wraps its client calls; on `5xx` or network
  error the `ErrorBannerService` renders a **structured error message identifying the service**
  (service name + HTTP status) inside that module; other modules are unaffected. An effect logs a
  structured JSON error (level from env) with service name, endpoint, status, and `traceId` if
  present. Nothing is silently dropped.
- **Streaming poll failure:** a failed poll (5xx/network) sets a **stale-data indicator** ("last
  updated" goes stale + a warning badge); the previous snapshot is retained and the next tick
  retries. The view does not crash and does not lose the last good data (spec Non-functional).
- **404 / empty result:** rendered as an explicit **empty state** (e.g. "No sites returned", "No
  discovered patterns", "No run-stats yet", "No incidents"). A `404` on `GET /incidents/{id}`,
  `GET /alarms/{id}`, or `GET /run-stats/{id}` shows a "not found" panel; the incident-detail
  page shows a not-found state when the `incidentId` deep link does not resolve.
- **Dashboard partial failure:** the dashboard reads are independent; if one source fails, that
  KPI card shows an inline error/N-A while the others still render (no whole-page failure).
- **Validation failure (config edit, AC 42):** the typed reactive form blocks submit and shows an
  inline field error; **no API call** is made for an invalid value (e.g. negative session-window
  gap). The streaming interval control likewise rejects non-positive intervals client-side.
- **Edit on non-draft pattern (AC 54):** the Edit action is offered only for `draft` patterns; a
  `409/422` from the Pattern Manager (race) surfaces the structured error and does not mutate
  local state.
- **Noise-stats query error (422):** a bad `limit`/`from`/`to` returns `422`; the noise-stats
  sub-view shows the structured validation error and keeps prior rows.
- **Double-submit guard (idempotency):** approval-intent and config-save buttons are disabled
  while a request is in flight (`pendingDecision`/`saveStatus` signals). Server-side idempotency
  is the owning service concern.
- **Loading state:** every async view shows a skeleton/spinner with an ARIA `aria-busy` region
  while the request is pending; `LiveAnnouncer` announces load completion.
- **RCA accuracy display:** the Correlation Engine `GET /stats` does **not** return RCA accuracy
  (evaluated by the offline oracle per its spec); the dashboard/stats show "evaluated offline"
  rather than fabricating a value. **Flagged design note** — not a contract change; consistent
  with the Correlation Engine spec.

## Design alternatives

| Consideration | Alternatives considered | Chosen plus rationale |
|---|---|---|
| Real-time delivery | WebSocket/SSE push vs. client-side polling vs. backend stream API | **Client-side polling** — fixed by the spec (no backend streaming, no new API surface). Polls existing `GET /alarms` + `GET /incidents` at a configurable interval. No contract change; works against the already-published REST APIs and the same mock/real toggle. |
| Streaming timer | rxjs `interval`/`timer` bridged to signals vs. self-rescheduling `setTimeout` in an `effect` vs. Angular `resource` polling | **Signal-driven timer (rxjs `timer` bridged, or `setTimeout` loop) keyed off `intervalMs`/`autoRefresh` signals.** Restartable on interval change, tearable on pause, testable with `vi.useFakeTimers`. `resource` re-fetch is less ergonomic for pause/resume + delta-diff. |
| Delta diffing | Full-list re-render each poll vs. keyed Map diff with per-row tracking vs. server-provided diffs | **Keyed Map diff** keyed by `alarmId`/`incidentId`, template `@for` tracked by id — only changed rows re-render (spec performance). Server diffs are unavailable (no new API). Full re-render violates the perf requirement and loses highlight state. |
| New-row animation | CSS keyframe animation vs. CSS class toggle with timed expiry vs. no animation | **Timed CSS class toggle** (`highlightUntilEpochMs`) — cheap, OnPush-friendly, and trivially disabled under `prefers-reduced-motion` (static badge instead of motion). Keyframe-only would not respect reduced motion without extra work. |
| Incident-detail member fetch | Sequential per-alarm vs. parallel fan-out vs. a single batch endpoint | **Parallel fan-out** (`forkJoin`/`Promise.all`) over `GET /alarms/{id}` — there is no batch endpoint (no new API), and parallel keeps the page responsive. Sequential is slow for large incidents. |
| Cross-navigation | Hard-coded `routerLink`s in each component vs. a central `NavigationService` | **Central `NavigationService`** builds every cross-link target from entity IDs — single place to keep deep-link URL shapes correct and shareable, and to satisfy the navigation-map contract. |
| State management | NgRx vs. signal-based injectable stores vs. component-local signals | **Signal-based injectable stores.** Stateless client, no complex shared-state graph; signals + `computed` give OnPush reactivity with far less boilerplate than NgRx. |
| API client generation | Full OpenAPI codegen client vs. models-only codegen + thin wrapper vs. hand-written | **Models-only codegen (`openapi-typescript`) + thin Angular `HttpClient` wrappers.** Contract-true types while keeping Angular DI, interceptors, and the env-driven base URL/toggle. |
| Mock backend transport | MSW vs. `HttpTestingController` vs. Prism mock server | **MSW for unit/component** (fetch-layer intercept in jsdom, same handlers from OpenAPI keep mock/real honest). Mock timers drive cadence tests. |
| Geo map / device graph | MapLibre + deck.gl vs. Leaflet; Cytoscape vs. d3-force | **MapLibre GL (BSD) + optional deck.gl (MIT); Cytoscape.js (MIT).** Permissive; purpose-built for vector basemap + typed multi-layer graphs with per-edge style classes. |
| Noise run-stats placement | Separate top-level route vs. sub-view/tab in `/stats` | **Tab within `/stats`** — the spec places noise run-stats in the correlation/learning stats area; a sub-view keeps the navigation map simple and matches the noise-filter spec's "presented in the existing correlation-stats module". |
| Alarm-reduction ratio source | Server-computed vs. client-computed from raw counts | **Client-computed** — `GET /stats` exposes raw counts only; UI divides `totalAlarmsProcessed` by `totalIncidentsCreated`, N/A when zero. No contract change. |
| BFF vs. direct-to-service | BFF proxy vs. direct SPA-to-service | **Direct-to-service (no BFF)** — fixed by the spec for MVP. |

## Test plan

### Acceptance criterion to test (unit/contract)

Unit/component tests use **Vitest + Angular TestBed** with **mock backends** (MSW from producer
OpenAPI) and **mock timers** for the streaming cadence. E2E tests use **Playwright** against the
integration stack (E2E only). All 54 acceptance criteria are mapped 1:1 to a named test.

| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| 1 | Dashboard alarm-reduction ratio = processed/incidents; N/A when zero incidents | `dashboard-ratio.spec.ts` | ratio matches `totalAlarmsProcessed/totalIncidentsCreated`; N/A shown when `totalIncidentsCreated=0` |
| 2 | Dashboard live incident count + active-pattern count match fixtures | `dashboard-counts.spec.ts` | incident count = incidents fixture size; pattern count = approved patterns size |
| 3 | Root path renders dashboard as default route | `dashboard-route.spec.ts` (router) | navigating `/` (and `/dashboard`) renders `DashboardComponent` |
| 4 | Clicking incident-count KPI navigates to stats/incidents | `dashboard-kpi-nav.spec.ts` (router) | activating the KPI navigates to the stats/incidents view |
| 5 | E2E dashboard non-zero count + ratio after fiber-cut replay | `dashboard.e2e.ts` (Playwright) | integration stack: dashboard shows non-zero incident count and non-zero ratio from stats API |
| 6 | Streaming polls `GET /alarms` every T ms; no extra calls between | `streaming-cadence.spec.ts` (fake timers) | one call per interval at configured T; no call between ticks |
| 7 | New alarm between polls gets "new" indicator; unchanged none | `streaming-new-alarm.spec.ts` | fixture A then B (one added alarm) then added row has NEW indicator; unchanged row none |
| 8 | open to in-progress between polls updates row + "changed" indicator | `streaming-state-change.spec.ts` | second poll `state=in-progress` then row updates + CHANGED indicator |
| 9 | in-progress to correlated and to reverted-open reflected without reload | `streaming-transitions.spec.ts` | all four transition cases reflected; no page reload |
| 10 | Pause stops all polling to Alarm Manager + Correlation Engine | `streaming-pause.spec.ts` (fake timers) | after pause, no further calls to either client |
| 11 | Resume restarts polling at configured interval | `streaming-resume.spec.ts` (fake timers) | after resume, polling resumes at interval T |
| 12 | Env `STREAMING_REFRESH_INTERVAL_MS=10000` then polls at 10000 | `streaming-interval-config.spec.ts` | interval read from env config; cadence is 10000 not 3000 |
| 13 | E2E streaming shows updated state within two poll cycles | `streaming.e2e.ts` (Playwright) | integration stack: transition visible within two polls |
| 14 | Incident-detail renders root cause/children/pattern/confidence/trail | `incident-detail-render.spec.ts` | `getIncident(id)` called with route param; all fields rendered |
| 15 | Incident-detail fetches each member alarm + renders state/role | `incident-detail-members.spec.ts` | `getAlarm` called per member; each alarm state + role tag rendered |
| 16 | Clicking a member alarm navigates to streaming/alarm view | `incident-detail-nav.spec.ts` (router) | activating an alarm link navigates to streaming with alarm focus/filter |
| 17 | E2E direct nav to `/incidents/<id>` renders root + at least one child | `incident-detail.e2e.ts` (Playwright) | integration stack: deep-link renders root cause + at least one child |
| 18 | Noise-stats renders each run row + derived storm-reduction ratio | `noise-stats-render.spec.ts` | rows show trailId/alarmsIn/clustersFormed/kept/dropped/noiseRatio + storm ratio = alarmsIn/clustersFormed |
| 19 | Noise-stats trailId filter shows only matching rows | `noise-stats-filter.spec.ts` | filter for one trailId then only its rows render; other-trail rows absent |
| 20 | E2E noise-stats shows at least one run with non-zero alarmsIn | `noise-stats.e2e.ts` (Playwright) | integration stack: at least one run-stats row with non-zero `alarmsIn` |
| 21 | Pattern "view trail" navigates to `/topology?trailId=<id>` | `xnav-pattern-trail.spec.ts` (router) | navigation carries pattern `trailId` as query param |
| 22 | Incident entry navigates to `/incidents/:incidentId` | `xnav-incident-detail.spec.ts` (router) | navigation to incident-detail with correct `incidentId` |
| 23 | Direct deep link `/incidents/:id` loads without prior nav | `xnav-deeplink-incident.spec.ts` (router) | direct route activation renders the incident |
| 24 | Direct deep link `/topology?trailId=<id>` activates the trail | `xnav-deeplink-trail.spec.ts` (router) | direct route activation with query param activates trail |
| 25 | E2E dashboard KPI to incident-detail path completes | `xnav.e2e.ts` (Playwright) | integration stack: dashboard KPI to incident-detail renders without error |
| 26 | Geo map renders a marker per Site, none for absent sites | `geo-site-map.spec.ts` | `listSites` at least 2 sites, one marker each, no extra markers |
| 27 | Selecting a site calls objects-at-site + renders device graph | `site-selection.spec.ts` | request carries correct `siteId`; graph replaces map |
| 28 | Layer toggles independently show/hide edges; all off = nodes only | `layer-toggle.spec.ts` | each toggle filters its layer; all-off shows only nodes |
| 29 | Node detail panel shows vendor/model/equipmentType + unknown keys | `attribute-panel-node.spec.ts` | three well-known keys labelled; extra key generic; `managedObjectId` shown |
| 30 | Edge detail panel shows linkType/capacity + unknown keys | `attribute-panel-edge.spec.ts` | well-known connection keys labelled; unknown generic |
| 31 | Trail cluster boundaries overlaid from `listTrails` | `trail-overlay.spec.ts` | overlay rendered per trail in fixture |
| 32 | Multi-trail device highlights all its trails distinctly | `trail-highlight.spec.ts` | device in at least 2 trails, member trails highlighted, non-members not |
| 33 | E2E real Topology + Trail Builder render sites/graph/attrs/overlays | `topology.e2e.ts` (Playwright) | integration stack: sites, site graph, attributes, trail overlays render without error |
| 34 | Pattern list renders all XAI fields | `pattern-list.spec.ts` | sequence/support/confidence/lift/RCA/timing/codebook/instances per pattern |
| 35 | Operator can expand a pattern to full XAI | `pattern-expand.spec.ts` | expansion reveals all XAI fields |
| 36 | Approve posts approval-intent with correct id; lifecycle approved | `pattern-approve.spec.ts` | `POST /patterns/{id}/approve` decision approve, correct id; UI shows approved |
| 37 | Reject posts reject-intent; pattern removed/marked rejected | `pattern-reject.spec.ts` | reject POST correct id; pattern removed/marked rejected |
| 38 | Active/approved filter shows only approved | `active-patterns.spec.ts` | mixed-lifecycle fixture, only approved under filter |
| 39 | E2E approve; Pattern Manager reflects approved on re-read | `pattern-approve.e2e.ts` (Playwright) | integration stack: approve then re-read returns approved |
| 40 | Config shows current model params from Knowledge | `config-load.spec.ts` | DBSCAN params, session-window gap, min-support displayed |
| 41 | Valid edit submits to Knowledge; success confirmed | `config-save.spec.ts` | edit request sent with updated values; success toast |
| 42 | Invalid value shows validation error, no API call | `config-validation.spec.ts` | negative gap inline error; Knowledge client not called |
| 43 | E2E config edit retrievable via Knowledge on re-read | `config-edit.e2e.ts` (Playwright) | integration stack: submitted edit returned on re-read |
| 44 | Incidents render with root-cause + child alarms | `incident-list.spec.ts` | each incident shows `rootCauseAlarmId` + `childAlarmIds` |
| 45 | Alarm-reduction ratio from stats shown as numeric | `stats-metrics.spec.ts` | computed ratio = `totalAlarmsProcessed/totalIncidentsCreated`, numeric |
| 46 | E2E fiber-cut: stats shows incident with root cause + children | `stats.e2e.ts` (Playwright) | integration stack: incident with tagged root cause + at least 1 child |
| 47 | Alarm-lifecycle lists state (5 states) + role + incidentId | `alarm-lifecycle.spec.ts` | alarms in open/in-progress/correlated/cleared/reverted-open with role + incidentId |
| 48 | Lifecycle filter filters by selected state incl. in-progress/reverted-open | `alarm-filter.spec.ts` | selecting a state shows only that state, incl. in-progress + reverted-open |
| 49 | E2E fiber-cut: correlated alarm with incident association | `alarm-lifecycle.e2e.ts` (Playwright) | integration stack: correlated alarm with non-empty incidentId from Alarm Manager |
| 50 | Mock config: all nine integration points resolve to mocks, no real HTTP | `env-mock-switch.spec.ts` | each of 9 clients hits MSW; no outbound real request |
| 51 | Integration config: 9 base URLs from env, no URL literal in source | `no-hardcoded-url.spec.ts` (build-time grep) | no localhost/service-hostname URL in non-environment source |
| 52 | Keyboard nav cycles all controls; canvases ARIA-labelled (10 views) | `a11y.spec.ts` (axe-core per view) | dashboard/streaming/topology/site-graph/patterns/config/stats/alarm-lifecycle/incident-detail/noise-stats: keyboard reachable; canvas ARIA labels; no axe violations |
| 53 | Any backend 5xx: module shows service-named error, others unaffected | `error-boundary.spec.ts` (per integration point) | 5xx then error banner naming the service; other modules still render |
| 54 | Edit draft pattern: mark optional, PATCH sent, edit reflected; draft-only | `pattern-edit.spec.ts` | `PATCH /patterns/{id}` optional marker; UI reflects edit; edit action absent for non-draft |

All 54 acceptance criteria map 1:1 to a named test (45 Vitest/TestBed + 9 Playwright E2E:
AC 5, 13, 17, 20, 25, 33, 39, 43, 46, 49 are E2E — note AC 51 is a build-time grep check run
under the unit harness). The five new-view AC groups are covered: dashboard (AC 1-5), streaming
(AC 6-13), incident-detail (AC 14-17), noise-stats (AC 18-20), cross-nav/deep-link (AC 21-25).

### E2E scenarios (from this design unit's point of view — Playwright)

| # | Scenario | Trigger to path | Expected outcome |
|---|---|---|---|
| 1 | Dashboard after fiber-cut (AC 5) | Replay fiber-cut, open `/dashboard` against the real stack | Non-zero live incident count and non-zero alarm-reduction ratio from stats API |
| 2 | Streaming live transition (AC 13) | Open `/streaming` against the real stack while a replay drives an alarm transition | Updated lifecycle state visible within two poll cycles; new alarms highlighted |
| 3 | Incident-detail deep link (AC 17) | Navigate directly to `/incidents/<id>` after a fiber-cut replay | Root-cause alarm + at least one child rendered matching the Correlation Engine record |
| 4 | Noise run-stats after P2 (AC 20) | Replay P2 learning scenario, open `/stats` noise run-stats tab | At least one run-stats row with non-zero `alarmsIn` from the Noise Filter API |
| 5 | Cross-nav dashboard to detail (AC 25) | From dashboard incident-count KPI, navigate through to an incident-detail page | Full path completes without error; incident detail rendered |
| 6 | Topology browse (AC 33) | Open `/topology` against real Topology + Trail Builder, list sites, select a site, toggle layers, select a device | Sites listed, site graph + attributes + trail overlays render, no console/network error |
| 7 | Pattern approve round-trip (AC 39) | Open `/patterns`, approve a draft pattern, re-read | Pattern Manager returns approved on subsequent read; UI reflects it |
| 8 | Config edit round-trip (AC 43) | Open `/config`, edit a param, save, re-read | Edited value persisted and returned on re-read |
| 9 | Fiber-cut stats (AC 46) | Replay fiber-cut, open `/stats` | At least 1 incident with tagged root-cause alarm + at least 1 child |
| 10 | Fiber-cut alarm lifecycle (AC 49) | Same replay, open alarm-lifecycle view | At least 1 alarm in correlated state with non-empty incident association from Alarm Manager |
| 11 (failure path) | Backend-down degradation | Point one client (e.g. Knowledge) at an unavailable/5xx endpoint | Affected module shows a structured service-named error; other modules still function (no whole-app crash) |
| 12 (failure path) | Streaming poll failure | Make the Alarm Manager poll endpoint return 5xx mid-stream | Streaming view shows a stale-data indicator, retains last good data, retries next tick, does not crash |
| 13 (empty path) | No discovered patterns | Pattern Manager returns empty draft list | Pattern review shows an explicit empty state, not an error |

## Config & observability

- **Config:** `environment.ts` / `environment.integration.ts` carry the nine per-service base
  URLs, `INTEGRATION_MODE=mock|real`, `STREAMING_REFRESH_INTERVAL_MS` (default `3000`), and the
  client log level. Values are injected from Docker Compose environment variables at build/serve
  time. No URL, threshold, interval literal, or credential is hard-coded in application source
  (AC 51). The streaming refresh interval is operator-adjustable at runtime via the UI; the env
  value is its default. The 3 s default is the spec default (open question #10) and may be
  adjusted in `environment.ts` if the integration stack reveals a better value — not a contract
  change.
- **Observability:** served app root `/` returns HTTP 200 for liveness (nginx). Client-side
  **structured JSON logging** (configurable level from env) for API errors, navigation events,
  and poll failures. **No `/metrics`** endpoint — a static SPA has no BFF; per spec this is
  intentional.
- **Accessibility:** WCAG 2.1 AA — semantic landmarks, ARIA roles/labels on map and graph
  canvases and data tables, `@angular/cdk/a11y` focus management + `LiveAnnouncer`, keyboard
  navigation for all controls, contrast at least 4.5 to 1 (text) and at least 3 to 1 (large
  text + UI components). **Streaming-specific a11y:** the new/changed highlight respects
  `prefers-reduced-motion` (no animation — a static text/badge indicator instead); `LiveAnnouncer`
  announces newly-arrived alarms and lifecycle changes to screen readers via an ARIA live region
  (polite), so non-visual users are not excluded from the live view; the pause control is
  keyboard-operable and the interval control is a labelled numeric input. Verified by
  `a11y.spec.ts` (axe-core) per view across all ten views (AC 52).
- **Performance:** OnPush + signals throughout; lazy-loaded routes per module; CDK virtual scroll
  for long streaming/alarm/incident/pattern lists; streaming delta-render updates only changed
  rows (tracked by id) — never a full-list re-render per poll; Cytoscape kept responsive up to
  the configured max graph size.

## Build & run

- **Dev (mock):** `npm ci && npm start` — serves the SPA with `INTEGRATION_MODE=mock`; MSW
  handlers (from producer OpenAPI) back all calls; no live dependency. Streaming polls the mock
  endpoints at `STREAMING_REFRESH_INTERVAL_MS`.
- **Unit/component tests:** `npm test` (Vitest + Angular TestBed, jsdom, MSW mocks, fake timers
  for cadence). Lint: `npm run lint`. Build: `npm run build` (static bundle).
- **E2E:** `npm run e2e` (Playwright) against the integration stack (`INTEGRATION_MODE=real`,
  base URLs are Compose addresses).
- **Container:** multi-stage Dockerfile — build stage `node:24` (`npm ci && npm run build`),
  serve stage nginx serving `dist/` with `/` returning HTTP 200 liveness. Docker Compose entry
  sets the nine base-URL env vars, `INTEGRATION_MODE`, and `STREAMING_REFRESH_INTERVAL_MS`.
- **Client regeneration:** on a collaborator OpenAPI contract change (architecture.md update +
  human approval first), run `npm run generate:clients` to regenerate TypeScript models from the
  producers' checked-in `openapi.json` (now including the Noise Filter `openapi.json`).
