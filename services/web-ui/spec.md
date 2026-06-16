# web-ui — Service Spec

## Purpose
The web-ui is the operator and engineer interface for the Alarm Correlation Platform. It is a
single Angular 20 application that provides NOC operators and engineers with an **intuitive
single-pane-of-glass experience** for understanding topology correlation, pattern discovery,
and real-time alarm correlation. The application now encompasses: a **landing dashboard** that
orients operators at a glance with key KPIs; a **real-time streaming view** that shows alarms
being ingested and correlations forming dynamically (via configurable client-side polling — no
backend streaming); an **incident-detail drill-down** for understanding individual correlation
groups; a **noise-filter run-stats view** showing storm-reduction effectiveness; and the four
original modules (topology & trails, pattern review & XAI, config, and correlation stats), all
**interconnected by logical cross-navigation** and deep-linkable routes. The application talks
exclusively to the published REST APIs of its collaborating services; it never touches Kafka
topics or any datastore directly. There is no backend-for-frontend layer — the SPA calls
service APIs directly.

## Scope

**In scope:**

- **Landing dashboard (home view — default route):** a single-pane-of-glass home view that
  serves as the entry point and orients the operator at a glance. Displays key KPIs: live
  incident count, active-pattern count, alarm-reduction ratio (derived from Correlation Engine
  `GET /stats` raw counts: `totalAlarmsProcessed / totalIncidentsCreated`), alarms-processed
  throughput, and an RCA accuracy note (evaluated offline per Correlation Engine spec — the
  spec note is surfaced in the UI; the engine does not return a live accuracy score). Also
  shows a recent-incidents summary and quick links/drill-ins to the topology view, streaming
  view, pattern review, config, and correlation stats. Reads Correlation Engine
  `/incidents` + `/stats`, Pattern Manager active-patterns API, and Alarm Manager alarm
  counts. The dashboard is the default route (`/` or `/dashboard`).

- **Real-time streaming view:** a live view that shows alarms being ingested and correlations
  forming dynamically. Delivery mechanism is **client-side polling with auto-refresh** on a
  **configurable interval** (an Angular environment/config value; default 3 s; operator-
  adjustable via UI toggle). The view animates **deltas between polls**: newly-ingested alarms
  appear with a visual highlight; each alarm's lifecycle-state transitions are shown live
  (`open` → `in-progress` → `correlated`, or `→ reverted-open`) using the Alarm Manager live
  alarm query API (`GET /alarms` — which now carries `in-progress` and `reverted-open` states
  driven by `AlarmStatusChange`); incidents and correlation groups appear and grow dynamically
  using the Correlation Engine `GET /incidents`. The operator can watch ingestion and
  correlation churn live. State managed in this view: configurable refresh interval, auto-
  refresh toggle (pause/resume), visual indication of new/changed items (animation/highlight).
  This view reads only existing REST APIs (Alarm Manager `GET /alarms`, Correlation Engine
  `GET /incidents`); **no new backend API surface is added**.

- **Incident-detail drill-down page:** a dedicated page for a single incident, deep-linked by
  `incidentId` (route `/incidents/:incidentId`). Shows the full correlation group: the
  root-cause alarm, all child alarms, the matched pattern (or codebook match) with confidence
  score, the trail, and a link to each member alarm in the streaming/alarm-lifecycle view.
  Reads Correlation Engine `GET /incidents/{incidentId}` for the incident record, and Alarm
  Manager `GET /alarms/{alarmId}` for each member alarm's full detail. This page is central to
  operator understanding of why a correlation happened.

- **Noise-filter run-stats view:** presented within the correlation/learning stats area (the
  correlation stats module's learning sub-view). Displays per-run aggregate stats from the
  Noise Filter run-stats read API: `trailId`, `snapshotId`, window parameters, DBSCAN params
  (`eps`, `minSamples`, `windowSize`, `algorithm`), `alarmsIn`, `clustersFormed`, `alarmsKept`,
  `alarmsDropped`, `noiseRatio`, and storm-reduction ratio (`alarmsIn / clustersFormed`) per
  run. Allows filtering by `trailId` and time range (as supported by the Noise Filter read
  API). This closes the loop from the noise-filter spec, which explicitly states the web-ui
  correlation-stats module presents these run stats. Reads the Noise Filter run-stats read API
  (built against the Noise Filter's published OpenAPI 3.1).

- **Logical cross-navigation and deep-linking:** the modules are interconnected with logical
  navigation links, not four isolated silos. Deep-linkable routes (shareable URLs with IDs)
  are required throughout:
  - From a discovered or active pattern → the trail(s) it applies to in the topology view
    (route `/topology?trailId=<id>`).
  - From an incident (in the dashboard, stats view, or streaming view) → the incident-detail
    page (`/incidents/:incidentId`).
  - From an incident-detail page → each member alarm in the streaming/alarm-lifecycle view.
  - From a site in the topology view → live incidents/alarms on that site.
  - From a dashboard KPI → the underlying detail view (e.g. incident count → incidents list;
    active patterns → pattern review).
  - All navigable pages carry the relevant ID in the URL so the link is shareable and bookmarkable.
  - A **navigation map** (a page-graph diagram describing all pages and the logical flows
    between them) must be included in the `design.md` for this service — it is a required
    design-stage deliverable.

- Topology & trails module: **explorable, site-bounded topology visualization backed by the
  `Site` entity.**
  The entry view is a **geo map** (MapLibre GL / deck.gl) showing each `Site` returned by the
  Topology Service **site query API** (`GET /topology/sites`). Expanding a site fetches the
  objects located at that site via the **objects-at-site query** and renders the site's
  device-level graph (Cytoscape.js) with togglable logical layers (fiber / IP / IGP / LSP /
  service). `Site` nodes and `LOCATED_AT` relations are owned by the Topology Service and the
  graph; the web-ui fetches them from the Topology query API and does not model them
  independently. (The Topology Service site query API — exact endpoint paths, request/response
  shapes — is a design-stage dependency; the web-ui builds its client and mock against the
  Topology Service's published OpenAPI 3.1 spec. See Open question 1.)
  - **Device/connection attribute display:** when the operator selects a device or connection
    in the site-level graph, the detail panel displays the node/edge `attributes` returned by
    the Topology query API. Well-known keys include `vendor`, `model`, `equipmentType`, `role`,
    `capacity` for devices and `linkType`, `capacity`, `protectionRole` for connections. The
    attribute set is open (domain-specific keys are rendered as generic key/value pairs); the
    web-ui does not hard-code or validate the attribute schema — attribute catalogue ownership
    belongs to the Knowledge Service.
  - Trail overlays and per-device trail membership are available in the site-level graph view:
    trail cluster boundaries are overlaid from the Trail Builder trail-viz API
    (`listTrails(snapshotId)`, `getTrail(trailId)`, `getTrailsForObject(managedObjectId)`);
    selecting a device highlights all trails it belongs to.
  - **Explorable / dynamically-extendable topology:** from any device already rendered in the
    graph, the operator can **expand** that device to pull its neighbours into the graph using
    the Topology Service neighbours query (`GET /topology/nodes/{managedObjectId}/neighbors`),
    accumulating nodes and edges **additively** (existing nodes/edges are never removed). A
    single expansion may reach devices in other sites; cross-domain expansion is available as
    an explicit opt-in (using the `crossDomain=true` query parameter). A configurable node-cap
    prevents runaway expansion: when the total rendered node count would exceed the cap, the
    expansion is rejected and the operator receives visible feedback. Re-expanding a node whose
    neighbours are already present in the graph produces no duplicates.
  - **Site boundaries:** each rendered device node is visually attributed to its `Site`
    (via the `LOCATED_AT` relation returned by the Topology query API). When the device graph
    spans multiple sites — after a cross-site expand or after a trail highlights devices across
    sites — each site's devices are enclosed in a distinct, labelled visual boundary group.
    At least two distinct site groups are visible when the graph contains devices from two or
    more sites.
  - **Trail navigation and explode:** a trail can be **selected** (not merely displayed as a
    boundary overlay). Selecting a trail fetches its full detail from the Trail Builder
    `getTrail(trailId)` API (`GET /trails/{trailId}`) and highlights every member in
    `TrailDetail.members` — not just the device the operator last clicked. If the trail's
    members include devices at sites not currently loaded in the device graph, those
    sites/devices are automatically fetched and added to the graph so the complete trail span
    is visible. A trail-detail panel shows `trailId`, `igpArea`, `srlgGroup`, and the list of
    `members` (each `managedObjectId` is interactive — activating it selects that device in
    the graph). The operator can clear the trail selection; clearing restores the graph to its
    unselected state without removing nodes or edges that were added to show the trail. The
    existing behaviour — selecting a **device** highlights all trails it belongs to (via
    `getTrailsForObject`) — is preserved and reconciled with trail-select: both can be active
    simultaneously (trail-select highlights the trail's members; device-select shows that
    device's trail memberships).
  - **Network-element type icons:** every device node in the graph is rendered with an icon
    that represents its `objectType` as returned in the `NodeDto.objectType` field from the
    Topology query API. The Core IP domain `objectType` set is: Node (Router), LineCard, Port,
    Interface, FiberSpan, IPLink, IGPAdjacency, LSP, VPNService, SRLG. An `objectType` value
    not in this set falls back to a **generic icon** — the node is always rendered, never
    invisible. All icons are **bundled offline** in the application (no CDN/external URL);
    consistent with the offline-only licensing constraint.
  - **Zoom and pan:** both the geo map and the device graph support **zoom** and **pan**. Each
    canvas provides explicit zoom controls: zoom in, zoom out, fit-to-viewport (fit all current
    content), and reset to default zoom. The operator can also pan freely. Zoom-control
    interactions must be keyboard-accessible (WCAG 2.1 AA).

- Pattern review & XAI module: list discovered patterns from the Pattern Manager (pattern
  read API) with support, confidence, lift, RCA, timing, codebook overlap, and supporting
  instances; present the explainability data to support the operator decision; approve or
  reject a pattern by posting a lightweight approval-intent to the Pattern Manager API; list
  active/approved patterns with their details. The Pattern Manager, upon receiving the
  approval-intent, is responsible for transitioning lifecycle and emitting `patterns.approved`
  downstream — the web-ui does not emit Kafka events.
  - **Edit a draft pattern (placeholder, to be enhanced):** alongside approve/reject, provide an
    **edit** action letting the operator adjust a draft pattern before approving — for the MVP a
    **placeholder UI** to mark sequence alarms as **optional** (plus reviewer/notes). Edits are
    submitted to the Pattern Manager's pattern-edit API (`PATCH /patterns/{patternId}`), which
    persists them on the draft pattern; the edited pattern can then be approved. The web-ui only
    surfaces the action and renders the edit form — the Pattern Manager owns the edit semantics,
    validation, and persistence. The richer edit model is a future enhancement; the MVP delivers
    the action + the optional-alarm placeholder.

- Config module: read and edit Knowledge Service model parameters (DBSCAN params,
  session-window gap, min-support, etc.) via the Knowledge Service read and edit API; persist
  changes via Knowledge (Knowledge Service is the single owner of these params).

- Correlation stats module: display live incidents (root-cause alarm + child alarms), alarm-
  reduction ratio, RCA accuracy (note: evaluated offline per Correlation Engine spec), and
  real-time pattern-match stats, sourced from the Correlation Engine incident/stats API and the
  Pattern Manager active-patterns API; display the live alarm-lifecycle view — a list of
  enriched alarms with their current lifecycle state (open / in-progress / correlated / cleared
  / reverted-open), root-cause/child membership, and incident association — sourced from the
  Alarm Manager alarm-lifecycle query API; **and display the noise-filter run-stats view** (the
  learning sub-view, sourced from the Noise Filter run-stats API). The split of
  responsibilities: the Correlation Engine provides incident groupings and effectiveness
  metrics; the Alarm Manager provides the per-alarm lifecycle list and state; the Noise Filter
  provides per-run aggregate storm-reduction stats.

- Config-switchable backends: all outbound HTTP calls are routed through Angular environment
  configuration (base URL + mock/real toggle); unit/component tests use mocks generated from
  the producer's published OpenAPI spec; integration tests point at the live stack. No backend
  URL is hard-coded.

- Test suite: Vitest + Angular TestBed for unit/component tests (mock backends); Playwright for
  E2E tests against the integration stack (browser-level user-flow coverage). Playwright is E2E
  only and is not the unit-test runner.

- WCAG 2.1 AA accessibility: keyboard navigation, ARIA labeling, and sufficient colour contrast
  on all key views (topology view, pattern review, config edit, stats view, streaming view,
  dashboard, incident-detail, noise-stats view).

- Permissive-license dependencies only (Angular MIT, Cytoscape.js MIT, MapLibre GL BSD,
  deck.gl MIT).

- Dockerfile + Docker Compose entry, README with run instructions.

- Served-app health indicator (HTTP 200 on the app root for liveness checks).

## Out of scope

- Direct Kafka consumption or production: the web-ui never subscribes to or publishes Kafka
  topics directly. The architecture row "produces `patterns.approved` (via API)" means the UI
  posts an approval-intent to the Pattern Manager API; the Pattern Manager emits the
  `patterns.approved` event.

- Backend streaming APIs or WebSockets for the streaming view: the streaming view uses
  **client-side polling only** against existing REST APIs. No backend streaming endpoint, no
  WebSocket, no Server-Sent Events connection, and no new Kafka consumer are introduced. The
  polling interval is a web-ui config/env value; it does not require any backend change.

- Any new backend API surface: the streaming view, dashboard, incident-detail page, and noise-
  stats view all read from **already-published REST APIs** (Alarm Manager `GET /alarms` +
  `GET /alarms/{alarmId}`, Correlation Engine `GET /incidents` + `GET /incidents/{id}` +
  `GET /stats`, Pattern Manager `GET /patterns`, Noise Filter run-stats read API). No new
  backend endpoint, topic, payload, or field is introduced by this spec.

- Owning or computing domain data: topology graph, `Site` nodes, `LOCATED_AT` relations, trail
  definitions, patterns, incidents, and ML params all live in their owning services; the
  web-ui is a stateless client.

- A Backend-for-Frontend (BFF) server layer: **decided — no BFF for MVP.** The application
  is a static Angular SPA that calls the collaborating service APIs directly via
  config-switchable Angular environment configuration (per-service base URL, no hard-coded
  URLs). CORS is a design-stage concern for each producer service. A BFF introduced post-MVP
  would be a separate new service requiring its own spec, published OpenAPI, contract-change
  approval (architecture.md update), Dockerfile, /health, and /metrics. It is not in scope
  for this service or this spec.

- Correlation/incident computation: the web-ui displays results from the Correlation Engine; it
  does not implement matching, scoring, or RCA logic.

- Pattern lifecycle management beyond surfacing the UI action: lifecycle transitions
  (draft → approved → deprecated) are owned by the Pattern Manager.

- Automated retraining or closed-loop feedback execution (deferred MVP item per §2 non-goals).

- Multi-tenancy, production-grade HA, real OSS north-bound connectors (deferred non-goals).

- Other domains beyond Core IP (domain pack extensibility is by design in the backend;
  web-ui renders whatever the APIs return). Multi-domain scoping (e.g. showing sites/topology
  per domain filter) is a post-MVP concern; the MVP serves Core IP only.

- Site hierarchy (nested sites) — out of MVP scope per architecture.md.

- Device/connection attribute validation or catalogue editing — the Knowledge Service owns
  the attribute catalogue; the web-ui renders attributes as returned and does not author them.

- NebulaGraph or PostgreSQL direct access.

- **[POST-MVP — DEFERRED, DO NOT DESIGN NOW]**
  The following capabilities are tracked for post-MVP planning but are explicitly out of scope
  for this spec and design:
  - (a) **Live incident overlay on the topology/trail graph view** — overlaying active
    incidents directly on the site-level or trail graph (e.g. highlighting graph nodes that
    are currently involved in an incident) is deferred. The streaming view and incident-detail
    page provide the operator with correlation context; graph overlay is a post-MVP UX
    enhancement.
  - (b) **Alarm/incident timeline/history view** — a chronological timeline or history view
    for alarms and incidents is deferred. The MVP serves the live/current state; a persistent
    historical view depends on a durable historical-alarm corpus, which is itself deferred per
    architecture.md.

## Tasks (high-level)

1. **Render the landing dashboard (home/default route).** Fetch KPIs (live incident count,
   active-pattern count, alarm-reduction ratio derived from Correlation Engine `GET /stats`,
   alarms-processed throughput) from the Correlation Engine `/incidents` + `/stats`, Pattern
   Manager active-patterns API, and Alarm Manager alarm counts. Render a recent-incidents
   summary and quick-link navigation to all other views. This view is the application's default
   route.

2. **Deliver the real-time streaming view via configurable client polling.** On a configurable
   interval (web-ui env value; default 3 s), poll Alarm Manager `GET /alarms` and Correlation
   Engine `GET /incidents`. Animate deltas between polls: newly-ingested alarms appear with a
   visual highlight; alarms that changed lifecycle state (including transitions involving
   `in-progress` and `reverted-open`) are highlighted. Incidents that are new or have grown
   are highlighted. Provide an auto-refresh toggle (pause/resume) and an interval control.
   No backend change; polling interval is a UI config value only.

3. **Render the incident-detail drill-down page.** For a given `incidentId` (from the URL
   route `/incidents/:incidentId`), fetch the incident from Correlation Engine
   `GET /incidents/{incidentId}` and fetch each member alarm's detail from Alarm Manager
   `GET /alarms/{alarmId}`. Display the root-cause alarm, child alarms, matched pattern or
   codebook match with confidence, trail identifier, and links to each alarm in the streaming/
   alarm-lifecycle view.

4. **Render the noise-filter run-stats view.** Within the correlation stats module, fetch
   per-run aggregate stats from the Noise Filter run-stats read API. Display `trailId`,
   window parameters, DBSCAN params, `alarmsIn`, `clustersFormed`, `alarmsKept`, `alarmsDropped`,
   `noiseRatio`, and storm-reduction ratio per run. Support filtering by `trailId` and time
   range. Build client against the Noise Filter's published OpenAPI 3.1.

5. **Provide logical cross-navigation with deep-linkable routes.** Implement navigation links
   between views: pattern → topology trail, incident → incident-detail, incident-detail → alarm
   in streaming view, site → live alarms/incidents, dashboard KPI → underlying detail. Ensure
   all navigable entity pages carry the entity ID in the URL so links are shareable and
   bookmarkable. The design.md for this service must include a navigation map (page-graph
   diagram) documenting all pages and logical flows.

6. **Render the geo-site topology view from the Topology site query API.** Call the Topology
   Service site query API (`GET /topology/sites`) to list all `Site` objects with their geo
   attributes (name, latitude, longitude, region) and display each site as a marker or region
   on a geo map (MapLibre GL / deck.gl). Sites are the top-level navigation unit for topology;
   the geo map is the entry view of the topology & trails module.

7. **Expand a site into its device-level graph.** When the operator selects a site, call the
   Topology Service objects-at-site query to retrieve the nodes and edges located at that site
   (backed by `LOCATED_AT` relations in the graph) and render them as a device-level topology
   graph (Cytoscape.js) with logical-layer toggles (fiber, IP, IGP, LSP, service layers
   individually shown or hidden). The site-level graph replaces the flat topology list view
   from the prior spec iteration.

8. **Display device and connection attributes in the topology detail panel.** When the operator
   selects a node or edge in the site-level graph, fetch or use the `attributes` returned in
   the Topology query API node/edge response and display them in a detail panel alongside the
   `managedObjectId`. Well-known keys (`vendor`, `model`, `equipmentType`, `role`, `capacity`,
   `linkType`, `protectionRole`) are labelled clearly; additional domain-specific keys are
   shown as generic key/value pairs.

9. **Visualize trail clusters and per-device trail membership.** Fetch trail data from the
   Trail Builder trail-viz API (`listTrails`, `getTrail`, `getTrailsForObject`) and overlay
   trail cluster boundaries on the site-level topology graph. When the operator selects a
   device, highlight all trails that device belongs to (a device may appear in multiple
   overlapping trails).

10. **Expand a device node to pull its neighbours into the graph (explorable topology).** When
    the operator triggers an expand action on a device already rendered in the graph, call the
    Topology Service neighbours query (`GET /topology/nodes/{managedObjectId}/neighbors`) and
    **additively** merge the returned `NeighborsDto.neighbors` (nodes + connecting edges) into
    the currently rendered graph. Existing nodes and edges are never removed or replaced.
    If the `crossDomain` opt-in is active, pass `crossDomain=true` to the neighbours query.
    If adding the new nodes would cause the total rendered node count to exceed the configured
    node cap, reject the expansion and display an operator-visible message; no partial add is
    applied. Re-expanding a node whose neighbours are already present produces no duplicates
    (deduplicate on `managedObjectId`).

11. **Render site-boundary groupings on the device graph.** For each device node in the
    rendered graph, use the `siteId` association returned by the Topology query API (via the
    `LOCATED_AT` relation / `SiteObjectsDto`) to attribute the node to its site. Enclose all
    nodes of the same site within a distinct, labelled visual boundary group. When the graph
    spans multiple sites (after cross-site expansion or a cross-site trail explode), each
    site's boundary group is rendered and labelled independently.

12. **Navigate trails by selection and explode cross-site spans.** Allow the operator to
    **select a trail** directly (from the trail-list overlay or a trail detail link). On trail
    select: call the Trail Builder `getTrail(trailId)` API (`GET /trails/{trailId}`), fetch
    the full `TrailDetail`, and highlight every device in `TrailDetail.members` in the graph.
    If any member device belongs to a site not yet loaded in the graph, fetch and add those
    sites' device subgraphs so the full trail span is visible. Display a trail-detail panel
    showing `trailId`, `igpArea`, `srlgGroup`, and the `members` list; each `managedObjectId`
    in the members list is interactive (activating it selects that device in the graph).
    Provide a clear-trail-selection action that removes the trail highlight without removing
    nodes/edges from the graph. The existing device-select-to-trail-membership flow
    (`getTrailsForObject`) is preserved alongside trail-select.

13. **Render network-element type icons on device nodes.** For every device node in the graph,
    read `objectType` from the `NodeDto` returned by the Topology query API and render the
    node with the corresponding bundled icon. The supported `objectType` set for Core IP is:
    Node (Router), LineCard, Port, Interface, FiberSpan, IPLink, IGPAdjacency, LSP,
    VPNService, SRLG. An `objectType` value not in this set falls back to a generic bundled
    icon; no device node is ever rendered without an icon. All icon assets are bundled
    offline within the application build — no CDN or external URL is used.

14. **Provide zoom and pan controls on both the geo map and the device graph.** The geo map
    (MapLibre GL) and the device graph (Cytoscape.js) each expose explicit zoom controls: zoom
    in, zoom out, fit-to-viewport (fit all current graph/map content), and reset to default
    zoom/pan. The operator can pan freely on both canvases. Zoom controls are keyboard-
    accessible. All zoom-control state is local to the view (not persisted).

15. **List and present discovered patterns with full XAI.** Fetch discovered patterns from the
    Pattern Manager read API. For each pattern, surface the sequence, support/confidence/lift,
    RCA, timing stats, codebook overlap, and supporting instance count in a form that lets the
    operator understand the evidence behind the pattern before acting on it.

16. **Accept approve/reject decisions and post to the Pattern Manager.** Capture the operator's
    approve or reject decision and post a lightweight approval-intent request to the Pattern
    Manager API. Reflect the resulting lifecycle state back in the UI.

17. **List active/approved patterns.** Fetch and display patterns whose lifecycle state is
    `approved` from the Pattern Manager read API, with their details.

18. **Read and edit Knowledge Service model parameters.** Fetch current ML config params
    (DBSCAN params, session-window gap, min-support, etc.) from the Knowledge Service API and
    present them for editing. Submit validated edits to the Knowledge Service API and confirm
    persistence to the operator.

19. **Display live correlation stats and incidents.** Fetch live incidents (root-cause alarm +
    child alarms), alarm-reduction ratio, and pattern-match stats from the Correlation Engine
    incident/stats API. Present them as the platform's effectiveness dashboard. The Correlation
    Engine provides incident groupings and raw counts only; it does not provide per-alarm
    lifecycle state or a live accuracy score.

20. **Display live alarm lifecycle from the Alarm Manager.** Within the correlation stats
    module, fetch the list of live alarms and their lifecycle state (open / in-progress /
    correlated / cleared / reverted-open) from the Alarm Manager alarm-lifecycle query API.
    Present each alarm's state, root-cause/child membership, and incident association.

21. **Provide config-switchable backend integration.** All outbound API calls are resolved from
    Angular environment configuration. Each integration point is independently switchable
    between a mock (generated from the collaborator's published OpenAPI spec) for unit/component
    tests and the real service for integration — with no code changes between modes.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Topology & trails visualization: operators view the onboarded topology organized by Site, toggle device-level layers, expand device nodes to explore neighbours across sites, inspect site-boundary groupings and network-element type icons, select trails to highlight their full member span and see detail (igpArea, srlgGroup), zoom/pan both map and graph, inspect device/connection attributes; the landing dashboard orients to topology KPIs available at this phase | Active | Reads: Topology Service site query API (`GET /topology/sites`); objects-at-site (`GET /topology/sites/{siteId}/objects`); neighbours query (`GET /topology/nodes/{managedObjectId}/neighbors`, with optional `crossDomain=true`); Trail Builder `listTrails(snapshotId)` / `getTrail(trailId)` / `getTrailsForObject(managedObjectId)` API. Writes: — |
| P2 — Pattern learning | Pattern review/approve (XAI-driven approve/reject), config edits (Knowledge params), and correlation/learning stats including noise-filter run-stats: operators review discovered patterns, tune ML parameters, and observe noise-filtering effectiveness; the dashboard shows active patterns and learning-phase KPIs | Active | Reads: Pattern Manager pattern read API (discovered + active/approved patterns); Noise Filter run-stats read API (storm-reduction stats). Writes: Pattern Manager approval-intent API (approve/reject); Knowledge Service model-params edit API |
| P3 — Real-time correlation | Full platform: live incidents, correlation stats (effectiveness dashboard), per-alarm lifecycle view including in-progress/reverted-open states, real-time streaming view, incident-detail drill-downs, and dashboard with live KPIs — operators monitor running correlation and understand correlation groups | Active | Reads: Correlation Engine `GET /incidents` + `GET /incidents/{id}` + `GET /stats`; Alarm Manager `GET /alarms` + `GET /alarms/{alarmId}`; Pattern Manager active-patterns API. Writes: — |

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
  - **Topology Service — site query API, objects-at-site API, neighbours query API, and
    traversal API:** list all `Site` objects with geo attributes (`GET /topology/sites`);
    retrieve nodes and edges located at a given site (objects-at-site query
    `GET /topology/sites/{siteId}/objects`, backed by `LOCATED_AT` relations); retrieve the
    direct neighbours of a device node (`GET /topology/nodes/{managedObjectId}/neighbors`,
    with optional `relation[]` filter, optional `domain`, optional `snapshotId`, and optional
    `crossDomain=true`), returning a `NeighborsDto` whose `neighbors[]` each carry a `NodeDto`
    (including `objectType`) and the connecting `EdgeDto`; retrieve a bounded traversal closure
    (`GET /topology/traversal`, returning `TraversalDto` with `reached[]` nodes and `edges[]`).
    Node responses include `objectType` and an `attributes` map (well-known keys: `vendor`,
    `model`, `equipmentType`, `role`, `capacity`); edge responses include `relation` and
    `attributes` (well-known keys: `linkType`, `capacity`, `protectionRole`). Used by the
    topology & trails module — site listing and device graph (P1), neighbour expansion (P1),
    site-boundary attribution (P1), type-icon rendering (P1). The exact endpoint paths,
    response shapes, and pagination are confirmed against the Topology Service's published
    OpenAPI 3.1 spec (already published: `services/topology/openapi.json`); the web-ui builds
    its typed client and mock against that spec. (See Open question 1 for any shape gaps.)
  - **Trail Builder — trail-viz API:** `listTrails(snapshotId)`, `getTrail(trailId)`,
    `getTrailsForObject(managedObjectId)`. Used by the topology & trails module to overlay
    clusters and highlight per-device trail membership (P1, P2 passive reference).
  - **Pattern Manager — pattern read API:** list discovered patterns with full explainability
    metadata (sequence, support, confidence, lift, RCA, timing, codebook overlap, supporting
    instances, lifecycle). List active/approved patterns. Used by the pattern review & XAI
    module (P2), dashboard (active-pattern count, P2/P3), and correlation stats module (P3
    active-patterns list).
  - **Pattern Manager — approval-intent API:** POST approve or reject decision for a given
    `patternId`. Used by the pattern review & XAI module (P2). The Pattern Manager transitions
    the lifecycle and emits `patterns.approved`.
  - **Pattern Manager — pattern-edit API (placeholder):** `PATCH /patterns/{patternId}` to
    submit operator edits of a draft pattern (MVP placeholder: mark sequence alarms
    `optional`). Used by the pattern review & XAI module (P2). The Pattern Manager owns edit
    validation/persistence.
  - **Knowledge Service — model-params API:** read current ML configuration parameters; submit
    validated edits. Used by the config module (P2).
  - **Correlation Engine — incident/stats API:** `GET /incidents` (list incidents, used by
    dashboard, streaming view, and correlation stats module); `GET /incidents/{incidentId}`
    (single incident detail, used by incident-detail page); `GET /stats` (raw counts
    `totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`,
    `confidenceDistribution` — used by dashboard for alarm-reduction ratio derivation and stats
    module). Provides incident groupings and raw counts; does not provide per-alarm lifecycle
    state. RCA accuracy is evaluated offline (not returned by this API) — the UI notes this.
  - **Alarm Manager — alarm-lifecycle query API:** `GET /alarms` (list/filter alarms by
    lifecycle state including `in-progress`, by trail, by incident, by time window — used by
    streaming view and alarm-lifecycle view); `GET /alarms/{alarmId}` (single alarm full
    record including lifecycle state, role, `incidentId`, and state-transition history — used
    by incident-detail page). Lifecycle states include `open`, `in-progress`, `correlated`,
    `cleared`, and `reverted-open` (per the Alarm Manager spec, driven by `AlarmStatusChange`
    from the Correlation Engine). Config-switchable (mock from Alarm Manager's published
    OpenAPI / real); no hard-coded URL.
  - **Noise Filter — run-stats read API:** query per-run aggregate stats records, filterable
    by `trailId` and time range. Returns per row: `runId`, `runTimestamp`, `trailId`,
    `snapshotId`, `domain`, `windowStart`, `windowEnd`, DBSCAN params (`eps`, `minSamples`,
    `windowSize`, `algorithm`), `alarmsIn`, `clustersFormed`, `alarmsKept`, `alarmsDropped`,
    `noiseRatio`. Used by the noise-filter run-stats view within the correlation stats module
    (P2). Built against the Noise Filter's published OpenAPI 3.1. Exact endpoint path,
    query-parameter names, and pagination are design-stage on the Noise Filter side (see Open
    question 7).
- **Integration points (mock vs. real):** each of the ten integration points above is
  independently configured via Angular environments (base URL per service + mock/real toggle).
  Unit/component tests use mocks or stubs generated from the collaborator's published OpenAPI
  3.1 spec (no live dependency). Integration tests point at the real service on the Docker
  Compose network. The same application code runs in both modes without modification.
- **Streaming polling:** the streaming view polls existing REST APIs (Alarm Manager
  `GET /alarms`, Correlation Engine `GET /incidents`) at the configurable refresh interval.
  The interval is a web-ui Angular environment value. No new backend endpoint is introduced
  and no contract change is required.
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
  variables at build or serve time. The **streaming-view refresh interval** is an Angular
  environment/config value (e.g. `STREAMING_REFRESH_INTERVAL_MS`; default 3000 ms); it is
  operator-adjustable in the UI at runtime. The **topology node cap** (maximum number of
  device nodes in the rendered graph before expansion is refused) is an Angular environment/
  config value (e.g. `TOPOLOGY_NODE_CAP`; no hard-coded threshold in source); the designer
  chooses a sensible default at design time.
- **Deep-linkable routes:** all entity-specific views (incident-detail, topology-site,
  pattern-detail) carry the entity ID in the Angular route URL. Deep links are shareable and
  survive browser refresh. Routes include at minimum: `/` or `/dashboard` (landing),
  `/streaming` (real-time view), `/topology` (geo-site map), `/topology/:siteId` (site device
  graph), `/patterns` (pattern review), `/incidents/:incidentId` (incident-detail), `/config`
  (Knowledge params), `/stats` (correlation stats including noise-stats sub-view).
- **Auto-refresh / streaming state:** the streaming view maintains auto-refresh state (enabled/
  paused), the current interval, and the last-fetched result for delta computation. State is
  local to the view (in-memory Angular signals/store); it is not persisted across sessions.
- **Integration points — direct-to-service (no BFF, MVP decision):** the web-ui calls the
  collaborating service APIs (Topology Service, Trail Builder, Pattern Manager, Knowledge
  Service, Correlation Engine, Alarm Manager, Noise Filter) **directly** from the SPA. There
  is no Backend-for-Frontend proxy layer. Each integration point is independently configured
  via Angular environment files (base URL per service + mock/real toggle). The UI builds typed
  clients against each producer's published OpenAPI 3.1 spec. CORS headers on the
  collaborating services are a design-stage concern for each producer. A BFF is explicitly out
  of MVP scope; if introduced post-MVP it would require its own spec and a contract-change
  review.
- **Observability:** the served application root path returns HTTP 200 (liveness). Client-side
  structured logging (JSON, configurable log level from environment) for API errors and
  navigation events. No Prometheus `/metrics` endpoint is required for a static SPA (there is
  no BFF layer for MVP).
- **API contract:** the web-ui has no published OpenAPI surface. It builds all outbound clients
  against its collaborators' published OpenAPI 3.1 specs; a change to a collaborator's API
  surface is a contract change requiring architecture.md update and human approval before the
  web-ui client is updated.
- **Error handling:** API errors are surfaced to the operator with a structured error message
  (HTTP status + service name); the application does not crash on a single failing backend
  call. A failed liveness probe (unresponsive collaborator) is reported in the relevant module
  rather than blocking the entire application. A polling failure in the streaming view displays
  a stale-data indicator rather than crashing the view.
- **Accessibility:** WCAG 2.1 AA — keyboard navigability for all interactive controls, ARIA
  roles and labels on graph/map canvases and data tables, colour contrast >= 4.5:1 for normal
  text and >= 3:1 for large text and UI components. Applies to all views including the
  streaming view, dashboard, incident-detail, and noise-stats view.
- **Performance:** Angular OnPush change detection and signals throughout; lazy-loaded routes
  per module; virtual scrolling for long pattern/incident/alarm lists; graph rendering
  (Cytoscape.js) must remain responsive for topologies up to the maximum configured size. The
  streaming view's delta-render must not re-render the full list on each poll — only changed
  items are updated.
- **Licenses and offline requirement:** all runtime dependencies must carry permissive licenses
  (MIT, Apache-2.0, BSD, PostgreSQL). No GPL, AGPL, BSL, or source-available components. The
  application is **offline-only**: every asset — including the geo basemap tiles, network-
  element type icons, and any graph/map rendering library — must be **bundled within the
  application build** and must not fetch from any CDN or external URL at runtime. This mirrors
  the existing basemap-offline constraint and extends it to type icons and any new visual
  assets introduced by the topology enhancements.
- **Test frameworks (do not substitute):** unit/component tests use **Vitest + Angular TestBed**
  (jsdom, mock backends). E2E tests use **Playwright** (real browser, against the integration
  stack). Playwright is E2E only — never the unit-test runner.

## Acceptance criteria

### Landing dashboard (default route)

1. Given the Correlation Engine `GET /stats` returns `totalAlarmsProcessed` and
   `totalIncidentsCreated`, the dashboard renders the alarm-reduction ratio as
   `totalAlarmsProcessed / totalIncidentsCreated`; when `totalIncidentsCreated` is zero the
   ratio is shown as "N/A" or equivalent. (Vitest/TestBed — mock Correlation Engine stats
   fixture with known counts)

2. Given the Correlation Engine `GET /incidents` returns a list of incidents and the Pattern
   Manager active-patterns API returns a list of approved patterns, the dashboard renders the
   live incident count and active-pattern count matching the fixture sizes. (Vitest/TestBed —
   mock fixtures with >= 2 incidents and >= 1 pattern)

3. Given the application is navigated to the root path (`/` or `/dashboard`), the landing
   dashboard view is rendered as the default route. (Vitest/TestBed — Angular router test)

4. Given the dashboard renders a KPI card for live incident count, clicking or activating it
   navigates the operator to the correlation stats / incidents list view. (Vitest/TestBed —
   router navigation interaction test)

5. Given the Playwright E2E suite runs against the integration stack after a replayed fiber-cut
   scenario, the landing dashboard renders a non-zero incident count and a non-zero alarm-
   reduction ratio sourced from the Correlation Engine stats API. (Playwright E2E)

### Real-time streaming view

6. Given the streaming view is active with auto-refresh enabled and a configured refresh
   interval of T ms (from Angular environment), the Alarm Manager `GET /alarms` endpoint is
   polled at approximately every T ms; between polls, no additional HTTP call is made to that
   endpoint. (Vitest/TestBed — mock timer + mock Alarm Manager; verify call cadence)

7. Given the streaming view receives a second poll response that contains a new alarm ID absent
   from the first poll response, that alarm is rendered with a visual "new" indicator (e.g.
   CSS highlight class or animation marker); an alarm present in both polls with an unchanged
   lifecycle state receives no "new" indicator. (Vitest/TestBed — mock Alarm Manager returning
   fixture A then fixture B with one added alarm)

8. Given an alarm present in the streaming view transitions from `open` to `in-progress`
   between two polls (the second Alarm Manager response carries `state: "in-progress"` for
   that `alarmId`), the alarm row in the streaming view updates to reflect `in-progress` and
   receives a visual "changed" indicator. (Vitest/TestBed — mock returning lifecycle state
   change between polls)

9. Given an alarm transitions from `in-progress` to `correlated` between polls, and separately
   an alarm transitions to `reverted-open` between polls, both transitions are reflected in the
   streaming view without requiring a page reload. (Vitest/TestBed — mock lifecycle state
   fixtures covering all four transition cases)

10. Given the operator clicks the "pause" auto-refresh toggle on the streaming view, no further
    polling calls are made to the Alarm Manager or Correlation Engine until "resume" is
    clicked. (Vitest/TestBed — mock timer; verify no calls after pause)

11. Given the operator resumes auto-refresh after pausing, polling resumes at the configured
    interval. (Vitest/TestBed — mock timer; verify resumption)

12. Given the Angular environment sets `STREAMING_REFRESH_INTERVAL_MS` to a non-default value
    (e.g. 10000), the streaming view polls at that interval rather than the default 3000 ms.
    (Vitest/TestBed — verify interval read from environment config)

13. Given the Playwright E2E suite runs against the integration stack with a replayed scenario
    that produces at least one alarm transition, the streaming view shows the updated lifecycle
    state within two poll cycles of the transition occurring on the backend. (Playwright E2E)

### Incident-detail drill-down page

14. Given a route navigation to `/incidents/:incidentId`, the application calls Correlation
    Engine `GET /incidents/{incidentId}` with the `incidentId` from the route parameter and
    renders the returned incident's root-cause alarm ID, child alarm IDs, `matchedPatternId`
    (or `matchedCodebookId`), `confidence`, and `trailId`. (Vitest/TestBed — mock Correlation
    Engine `GET /incidents/{id}` fixture)

15. Given the incident-detail page for an incident with N member alarms (1 root-cause + N-1
    children), the application calls Alarm Manager `GET /alarms/{alarmId}` for each member
    alarm and renders each alarm's lifecycle state and role tag (`root-cause` or `child`).
    (Vitest/TestBed — mock Alarm Manager fixture for each member `alarmId`)

16. Given the incident-detail page renders a member alarm, clicking the alarm's link navigates
    to the streaming/alarm-lifecycle view with that alarm highlighted or pre-filtered.
    (Vitest/TestBed — router navigation interaction test)

17. Given the Playwright E2E suite navigates directly to an incident-detail URL
    (`/incidents/<id>`) after a replayed fiber-cut scenario, the page renders the root-cause
    alarm and at least one child alarm matching the Correlation Engine's incident record.
    (Playwright E2E)

### Noise-filter run-stats view

18. Given the Noise Filter run-stats API returns a list of run-stats rows, the noise-stats view
    renders each row with `trailId`, `alarmsIn`, `clustersFormed`, `alarmsKept`, `alarmsDropped`,
    `noiseRatio`, and the derived storm-reduction ratio (`alarmsIn / clustersFormed`). All values
    match the fixture. (Vitest/TestBed — mock Noise Filter run-stats API with >= 2 run rows)

19. Given the noise-stats view with a `trailId` filter applied, only run-stats rows matching
    that `trailId` are displayed; rows for other trail IDs are not rendered. (Vitest/TestBed —
    mock fixture with rows for two distinct `trailId` values; apply filter for one)

20. Given the Playwright E2E suite runs against the integration stack after a replayed P2
    learning scenario, the noise-stats view renders at least one run-stats row with a non-zero
    `alarmsIn` count sourced from the Noise Filter run-stats API. (Playwright E2E)

### Cross-navigation and deep-linking

21. Given a pattern in the pattern review module, activating its "view trail" navigation link
    navigates to the topology view with the pattern's `trailId` as a route parameter or query
    parameter (e.g. `/topology?trailId=<id>`). (Vitest/TestBed — router navigation interaction
    test; mock Pattern Manager fixture with a known `trailId`)

22. Given an incident entry in the dashboard or correlation stats module, activating its
    navigation link navigates to the incident-detail page at route `/incidents/:incidentId`
    with the correct `incidentId`. (Vitest/TestBed — router navigation test)

23. Given a browser is navigated directly to `/incidents/:incidentId` (a deep link), the
    incident-detail page loads and renders the incident without requiring prior navigation
    through the dashboard or stats module. (Vitest/TestBed — direct route activation test)

24. Given a browser is navigated directly to `/topology?trailId=<id>`, the topology view
    loads and activates the trail with that ID. (Vitest/TestBed — direct route activation
    test with query parameter)

25. Given the Playwright E2E suite navigates from the dashboard incident-count KPI through to
    an incident-detail page, the full navigation path completes without error and the incident
    detail is rendered. (Playwright E2E)

### Topology & trails module (P1)

26. Given the Topology Service site query API returns a list of `Site` objects (each with a
    name and geo coordinates), the geo map renders a marker or region for each site; no marker
    is rendered for a site absent from the API response. (Vitest/TestBed — mock Topology site
    query API returning a fixture with >= 2 sites)

27. Given the operator selects a site on the geo map, the application calls the Topology
    objects-at-site query for that site's identifier and renders the returned nodes and edges
    as a device-level graph; the geo map view is replaced by (or transitions to) the
    site-level graph view. (Vitest/TestBed — mock objects-at-site response; verify correct
    site identifier is passed in the request)

28. Given a site-level topology view, toggling each logical layer (fiber, IP, IGP, LSP, service)
    independently shows or hides the corresponding edges; toggling all off shows only nodes.
    (Vitest/TestBed — mock API; layer-toggle component test)

29. Given the operator selects a node (device) in the site-level graph, the detail panel
    displays the node's `attributes` as returned by the Topology query API — including at least
    the `vendor`, `model`, and `equipmentType` keys when present in the fixture; unknown keys
    are rendered as generic key/value pairs. (Vitest/TestBed — mock Topology API node response
    with a fixture containing all three well-known device keys plus one extra key)

30. Given the operator selects an edge (connection) in the site-level graph, the detail panel
    displays the edge's `attributes` — including at least `linkType` and `capacity` when present
    in the fixture; unknown keys are rendered as generic key/value pairs. (Vitest/TestBed —
    mock Topology API edge response with well-known connection keys)

31. Given trail data returned by the Trail Builder `listTrails(snapshotId)` API, trail cluster
    boundaries are rendered as visual overlays on the site-level topology graph.
    (Vitest/TestBed — mock Trail Builder API fixture)

32. Given a device that belongs to multiple trails (per `getTrailsForObject`), selecting that
    device in the topology view highlights all trails it belongs to, visually distinct from
    non-member trails. (Vitest/TestBed — fixture with a device in >= 2 trails)

33. Given the Topology and Trail Builder mocks are replaced with real services in the
    integration stack, the geo-site view lists sites, selecting a site renders its device-level
    graph with attributes, and trail overlays render without errors for the synthetic topology.
    (Playwright E2E)

### Pattern review & XAI module (P2)

34. Given discovered patterns returned by the Pattern Manager read API, the pattern list
    renders each pattern's sequence, support, confidence, lift, RCA, timing, codebook overlap,
    and supporting instance count. (Vitest/TestBed — mock Pattern Manager API fixture)

35. Given a pattern in the list, the operator can expand it to view the full explainability
    detail (all XAI fields) before acting. (Vitest/TestBed — component expansion interaction
    test)

36. Given the operator clicks "Approve" on a pattern, the application posts an approval-intent
    to the Pattern Manager approval-intent API endpoint with the correct `patternId`; the
    pattern's displayed lifecycle state updates to `approved` after a successful response.
    (Vitest/TestBed — mock Pattern Manager approval-intent endpoint)

37. Given the operator clicks "Reject" on a pattern, the application posts a reject-intent to
    the Pattern Manager API with the correct `patternId`; the pattern is removed from the
    discovered list or marked rejected in the UI. (Vitest/TestBed — mock Pattern Manager
    reject endpoint)

38. Given a filter or tab for active/approved patterns, the list displays patterns whose
    lifecycle state is `approved` as returned by the Pattern Manager read API.
    (Vitest/TestBed — mock API fixture with mixed lifecycle states)

39. Given the Playwright E2E suite runs against the integration stack with a replayed scenario,
    the operator can approve a pattern, and the Pattern Manager reflects the `approved` lifecycle
    state on a subsequent read. (Playwright E2E)

### Config module (P2)

40. Given the config module is loaded, it displays the current model parameters (DBSCAN params,
    session-window gap, min-support) fetched from the Knowledge Service API. (Vitest/TestBed —
    mock Knowledge API fixture)

41. Given the operator edits a model parameter and submits, the application sends an edit
    request to the Knowledge Service API with the updated values; a success response is
    confirmed to the operator in the UI. (Vitest/TestBed — mock Knowledge edit endpoint)

42. Given the operator submits an invalid parameter value (e.g. a negative session-window gap),
    the form displays a validation error and does not call the Knowledge Service API.
    (Vitest/TestBed — form validation unit test)

43. Given the Playwright E2E suite runs against the integration stack, a config edit submitted
    through the UI is retrievable via the Knowledge Service API on a subsequent read.
    (Playwright E2E)

### Correlation stats module (P3)

44. Given incidents returned by the Correlation Engine incident/stats API, the stats view
    renders each incident with its root-cause alarm and the list of child alarms.
    (Vitest/TestBed — mock Correlation Engine fixture)

45. Given stats metrics returned by the Correlation Engine `GET /stats` API, the view displays
    the alarm-reduction ratio derived from `totalAlarmsProcessed / totalIncidentsCreated` as a
    numeric value. (Vitest/TestBed — mock API fixture with known ratio values)

46. Given the Playwright E2E suite runs a replayed fiber-cut scenario against the integration
    stack, the stats module shows at least one incident with a tagged root-cause alarm and one
    or more child alarms. (Playwright E2E)

47. Given alarms returned by the Alarm Manager alarm-lifecycle query API, the alarm-lifecycle
    view in the correlation stats module lists each alarm with its lifecycle state
    (open / in-progress / correlated / cleared / reverted-open), its root-cause or child
    designation, and its associated incident identifier (where applicable). (Vitest/TestBed —
    mock Alarm Manager API fixture containing alarms in all five lifecycle states)

48. Given the Alarm Manager mock returns a mix of alarms in different lifecycle states, the
    alarm-lifecycle view filters correctly when the operator selects a specific lifecycle state
    (including `in-progress` and `reverted-open`). (Vitest/TestBed — filter interaction
    component test against mock fixture)

49. Given the Playwright E2E suite runs a replayed fiber-cut scenario against the integration
    stack, the alarm-lifecycle view shows at least one alarm in `correlated` state with a
    non-empty incident association, sourced from the Alarm Manager API. (Playwright E2E)

### Cross-cutting

50. Given the application is built with mock environment configuration, all ten integration
    points (Topology site query + objects-at-site + neighbours + traversal, Trail Builder,
    Pattern Manager read, Pattern Manager approval-intent, Knowledge, Correlation Engine, Alarm
    Manager, Noise Filter) resolve to mock/stub handlers and no real HTTP call is made.
    (Vitest/TestBed — environment-switch test per integration point)

51. Given the application is built with integration environment configuration, all ten
    integration point base URLs are resolved from environment variables with no URL literal in
    application source. (Build-time check: no hard-coded http://localhost or service hostname
    appears in non-environment source files)

52. Given the main interactive views (landing dashboard, streaming view, geo-site topology,
    site-level device graph including zoom controls and expand actions, trail-detail panel,
    pattern list, config form, stats dashboard, alarm-lifecycle view, incident-detail page,
    noise-stats view), keyboard navigation cycles through all interactive elements without a
    mouse, and all graph/map canvas elements carry an ARIA label. (Vitest/TestBed
    accessibility test using axe-core or equivalent; at least one criterion per view)

53. Given any single backend integration point returns a 5xx error, the affected module
    displays a structured error message identifying the service and does not crash other
    modules. (Vitest/TestBed — error-boundary component test per integration point)

54. Given a draft pattern in the review module, the operator can open the **edit** placeholder,
    mark a sequence alarm as `optional`, and submit; the application sends a
    `PATCH /patterns/{id}` edit request to the Pattern Manager (verified against the mock) and
    reflects the returned edited pattern. The edit action is offered only for `draft` patterns.
    (Vitest/TestBed — mock Pattern Manager pattern-edit API)

### Explorable topology — neighbour expansion (P1)

55. Given a device node is rendered in the site-level device graph, the operator triggers
    expand on that node; the application calls
    `GET /topology/nodes/{managedObjectId}/neighbors` with that node's `managedObjectId`;
    each `NodeDto` in the returned `NeighborsDto.neighbors` that is not already in the graph
    is added as a new node, and each corresponding `EdgeDto` connecting existing or newly
    added nodes is added as a new edge. (Vitest/TestBed — mock neighbours API returning a
    fixture with two neighbours; verify both nodes and the connecting edges appear in the
    rendered graph)

56. Given a device node is expanded and all of its neighbours are already present in the
    graph, re-triggering the expand action on that node does not add any duplicate nodes or
    edges; the rendered node count remains unchanged. (Vitest/TestBed — mock neighbours
    response returning the same `managedObjectId` values as nodes already in the graph;
    verify node count before and after re-expand)

57. Given the current rendered graph contains a number of nodes equal to the configured node
    cap (`TOPOLOGY_NODE_CAP`), attempting to expand a device whose neighbours would add at
    least one new node causes the expansion to be rejected; no new nodes are added to the
    graph and a visible error or feedback message is displayed to the operator.
    (Vitest/TestBed — mock neighbours API returning one unseen node; set node count fixture
    to cap value; verify node count unchanged and feedback message present)

58. Given the `crossDomain` opt-in is active (e.g. a UI toggle for cross-domain expand),
    expanding a device calls `GET /topology/nodes/{managedObjectId}/neighbors` with query
    parameter `crossDomain=true`; when the opt-in is inactive the parameter is omitted (or
    `false`). (Vitest/TestBed — mock neighbours API; verify `crossDomain` query param value
    in the captured request under each opt-in state)

59. Given the Playwright E2E suite runs against the integration stack with the synthetic Core
    IP topology loaded, the operator expands a device node and at least one neighbour node
    with its connecting edge appears in the rendered graph without a page reload.
    (Playwright E2E)

### Explorable topology — site boundaries (P1)

60. Given a device graph rendered from the objects-at-site response for a single site, every
    rendered device node is enclosed within a visual boundary group labelled with that site's
    name. (Vitest/TestBed — mock objects-at-site fixture returning nodes all attributed to
    one `siteId`; verify a single site-boundary group element exists with the site name label)

61. Given a device graph that contains devices from two distinct sites (e.g. after a
    cross-site neighbour expansion), the rendered graph shows exactly two distinct site-
    boundary groups each labelled with its respective site name; no device is ungrouped.
    (Vitest/TestBed — mock returning nodes with two different `siteId` values; verify two
    site-boundary group elements)

62. Given the Playwright E2E suite triggers a cross-site neighbour expansion against the
    integration stack and the expanded subgraph contains devices from a second site, the
    rendered graph shows at least two distinct, labelled site-boundary groups.
    (Playwright E2E)

### Trail navigation and explode (P1)

63. Given a trail is selected (e.g. the operator clicks a trail overlay or a trail list
    entry), the application calls `GET /trails/{trailId}` on the Trail Builder API; every
    `managedObjectId` in the returned `TrailDetail.members` that is present in the rendered
    graph receives a distinct visual highlight that is absent from non-member nodes.
    (Vitest/TestBed — mock `GET /trails/{trailId}` returning a `TrailDetail` with three
    members; render a graph containing all three plus at least one non-member; verify
    highlight applied to all three and not to the non-member)

64. Given a selected trail whose `TrailDetail.members` includes a device belonging to a site
    not currently rendered in the graph, the application fetches and adds that site's device
    subgraph so the member device becomes visible; after the fetch the device is present in
    the rendered graph and receives the trail-member highlight. (Vitest/TestBed — mock
    `GET /trails/{trailId}` with a member whose `managedObjectId` is absent from the current
    graph; mock the objects-at-site response for the member's site; verify the member node
    appears and is highlighted)

65. Given a trail is selected, the trail-detail panel is rendered and displays `trailId`,
    `igpArea` (or a null/absent indicator if `null`), `srlgGroup` (or a null/absent
    indicator if `null`), and the list of `members` each showing its `managedObjectId`.
    (Vitest/TestBed — mock `TrailDetail` fixture with both `igpArea` and `srlgGroup`
    populated; verify each field is rendered in the panel)

66. Given a trail-detail panel is rendered, activating (clicking/pressing Enter on) a member
    `managedObjectId` in the members list selects that device node in the device graph (e.g.
    the node receives the selected-device visual state). (Vitest/TestBed — panel member
    interaction test; verify the graph's selected-node state changes to the activated member)

67. Given a trail is selected and the operator activates the clear-trail-selection action,
    the trail-member highlights are removed from all nodes; nodes and edges that were added
    to the graph to show the trail span remain in the graph (they are not removed on clear).
    (Vitest/TestBed — verify zero nodes carry the trail-highlight class after clear; verify
    node count is not reduced to pre-explode count)

68. Given a device is selected and then a trail is also selected, both states are active
    simultaneously: the device's trail-membership highlights (from `getTrailsForObject`)
    remain visible alongside the trail-selection highlights from `getTrail`. (Vitest/TestBed
    — mock both `getTrailsForObject` and `getTrail`; verify both sets of highlights are
    present when both selections are active)

69. Given the Playwright E2E suite selects a trail from the trail list in the topology view
    against the integration stack, all member devices visible in the graph receive a trail-
    highlight and the trail-detail panel renders `trailId`, `igpArea`, and `srlgGroup`.
    (Playwright E2E)

### Network-element type icons (P1)

70. Given a device node rendered in the site-level graph whose `NodeDto.objectType` is one of
    the Core IP types (`Node`, `LineCard`, `Port`, `Interface`, `FiberSpan`, `IPLink`,
    `IGPAdjacency`, `LSP`, `VPNService`, `SRLG`), the rendered node carries the icon
    corresponding to that `objectType`. (Vitest/TestBed — render a fixture node for each
    objectType; verify the correct icon class or `data-icon` attribute for each)

71. Given a device node whose `NodeDto.objectType` is a value not in the known Core IP set
    (e.g. `"UnknownFutureThing"`), the node is rendered with the generic fallback icon and
    is not hidden or unrendered. (Vitest/TestBed — render a fixture node with an unrecognised
    `objectType`; verify the node element exists in the DOM and carries the generic icon
    indicator)

72. Given the application is served from an offline environment with no internet access, all
    device-type icon assets are loaded from the application bundle (no network request to an
    external domain for any icon). (Vitest/TestBed — intercept network requests during
    component render; verify zero requests to external hostnames for icon assets)

### Zoom and pan controls (P1)

73. Given the device graph is rendered and the operator activates the zoom-in control, the
    graph's zoom level increases; activating zoom-out decreases it; activating fit-to-viewport
    sets the zoom so all current nodes are visible; activating reset returns zoom and pan to
    the initial default. (Vitest/TestBed — Cytoscape.js mock or spy; verify
    `zoom()`/`fit()`/`reset()` or equivalent method is called in response to each control
    interaction)

74. Given the geo map is rendered and the operator activates the map zoom-in control, the
    map's zoom level increases; activating zoom-out decreases it; activating fit-to-viewport
    adjusts the map bounds to contain all site markers; activating reset returns zoom and
    centre to the initial default. (Vitest/TestBed — MapLibre GL mock or spy; verify the
    appropriate zoom/fit method is called for each control)

75. Given the zoom controls on the device graph, each control (zoom in, zoom out, fit,
    reset) is reachable and activatable via keyboard alone (Tab focus + Enter/Space). No
    mouse interaction is required. (Vitest/TestBed — axe-core ARIA test + keyboard-navigation
    component test verifying focusability and activation of each button)

76. Given the Playwright E2E suite navigates to the site-level device graph and activates the
    zoom-in control, the rendered graph's visual zoom level is demonstrably increased (e.g.
    the graph viewport scale is larger). (Playwright E2E)

## Open questions

The items below are **design-stage integration dependencies** — inherent to contract-first
development. The web-ui integration points are defined and config-switchable; the exact
OpenAPI shapes for each producer arrive when that producer's spec and design are authored and
approved. These are tracked dependencies, not spec blockers. Mock clients are generated at
design time once the producer publishes their OpenAPI 3.1.

1. **[DESIGN-STAGE] Topology Service site query API and objects-at-site API shape** (see also
   issue #60 for the graph/geometry API). The Topology Service's published OpenAPI
   (`services/topology/openapi.json`) is now available and confirms: `GET /topology/sites`
   (returning `SiteListDto` with `SiteDto` items carrying `siteId`, `name`, `latitude`,
   `longitude`, `region`); `GET /topology/sites/{siteId}/objects` (returning `SiteObjectsDto`
   with node and edge arrays); `GET /topology/nodes/{managedObjectId}/neighbors` (returning
   `NeighborsDto`); `GET /topology/traversal` (returning `TraversalDto`). The web-ui builds
   its typed client and mock against this published OpenAPI.
   **Remaining design-stage item:** the `NodeDto` schema does not carry an explicit `siteId`
   field — `SiteObjectsDto` returns nodes for a given site, so `siteId` is known from the
   fetch context. For nodes added by neighbour expansion, the site association must be
   inferred from the expansion context (the starting node's `siteId`) or retrieved by a
   follow-up objects-at-site query. The designer must specify how `siteId` is propagated to
   neighbour-expanded nodes so site-boundary grouping remains correct. **If a new field or
   endpoint is required to carry `siteId` on expanded nodes, that is a contract change
   requiring architecture.md update and human approval — it must not be silently added.**
   (See also Open question 11 below.)

2. **[DESIGN-STAGE] Trail Builder trail-viz API shape** (issue #61). The Trail Builder's
   published OpenAPI (`services/trail-builder/openapi.json`) is now available and confirms:
   `GET /trails` (`listTrails`, requiring `snapshotId` + `domain`); `GET /trails/{trailId}`
   (`getTrail`, returning `TrailDetail` with `trailId`, `domain`, `snapshotId`, `members[]`
   of `TrailMember` (`managedObjectId`, `objectType`), `memberCount`, `igpArea`?, `srlgGroup`?);
   `GET /trails/by-object` (`getTrailsForObject`, returning `TrailsForObjectResponse` with
   `trailIds[]`). The web-ui builds its typed client and mock against this published OpenAPI.
   No shape gaps remain for the trail-navigation/explode feature. (The `domain` parameter
   required by `listTrails` and `getTrailsForObject` is a design-stage wiring concern — the
   designer must specify how the active domain is resolved in the UI, e.g. from a config
   value or from the loaded snapshot.)

3. **[DESIGN-STAGE] Pattern Manager read API and approval-intent API shapes** (issue #62).
   The web-ui builds its typed clients for pattern listing (with XAI fields, lifecycle state,
   pagination) and the approve/reject approval-intent endpoint against the Pattern Manager's
   published OpenAPI. Whether approve and reject share one endpoint or use two is a Pattern
   Manager design decision. Also covers the active-patterns filter used by the P3 stats module
   and the dashboard active-pattern count (previously open question 8 — to be resolved by the
   Pattern Manager's design, not this spec).

4. **[DESIGN-STAGE] Knowledge Service model-params API shape** (issue #63).
   The web-ui builds its typed client for reading and editing model parameters (DBSCAN params,
   session-window gap, min-support) against the Knowledge Service's published OpenAPI. Which
   params are editable, their types, and validation rules are determined by the Knowledge
   Service design.

5. **[DESIGN-STAGE] Correlation Engine incident/stats API shape** (issue #64).
   The web-ui builds its typed client for the incident list (root-cause alarm + child alarms,
   pagination), single-incident endpoint (`GET /incidents/{id}` — required for the incident-
   detail page), and stats endpoint (raw counts for alarm-reduction ratio) against the
   Correlation Engine's published OpenAPI. The `GET /incidents/{id}` response must carry the
   fields required by the incident-detail page: `rootCauseAlarmId`, `childAlarmIds[]`,
   `matchedPatternId`, `matchedCodebookId`, `confidence`, `trailId`. Field names and pagination
   are confirmed at the Correlation Engine design stage. The Correlation Engine spec already
   documents these fields; this is a design-stage shape-confirmation dependency only.

6. **[DESIGN-STAGE] Alarm Manager alarm-lifecycle query API shape.**
   The web-ui builds its typed client and mock fixture for the Alarm Manager alarm-lifecycle
   integration point against the Alarm Manager's published OpenAPI 3.1 spec. The exact
   request/response shapes — how alarms are queried by state/trail/time/incident, field names
   for lifecycle state (including `in-progress` and `reverted-open`) and root-cause/child tags,
   pagination, and the incident-membership field — are determined when the Alarm Manager spec
   and design are authored. The Alarm Manager spec now documents these states; the designer
   generates the typed client and mock from the Alarm Manager's published OpenAPI at design
   time.

7. **[DESIGN-STAGE] Noise Filter run-stats read API endpoint shape.**
   The Noise Filter spec defines the required run-stats fields and the read-only query
   capability (filter by `trailId` / time range) but states the exact endpoint path(s), query-
   parameter names, pagination strategy, and sort order are a design-stage decision. The web-ui
   builds its typed client and mock against the Noise Filter's published OpenAPI 3.1 once
   available. If the `domain` field is optional/null in some rows, the UI must handle absent
   values gracefully. If any required field (e.g. storm-reduction ratio derivable as
   `alarmsIn / clustersFormed`) is not directly returned by the API, the UI computes it from
   the returned counts.

8. **[DESIGN-STAGE] Streaming view — delta-diffing approach and visual design.**
   The spec requires that newly-ingested alarms and changed-lifecycle alarms receive visual
   indicators between polls, and that the streaming view does not re-render the full list on
   each poll. The exact mechanism for computing deltas (e.g. comparing `alarmId` sets and
   `state` fields between successive poll responses), the animation/highlight approach, and the
   UI presentation of the auto-refresh toggle and interval control are **design-stage decisions**
   for the designer to specify in `design.md`. No backend change is required for any choice.

9. **[DESIGN-STAGE] Navigation map.**
   The spec requires a navigation map (page-graph diagram) documenting all pages, routes, and
   logical flows between them. This is a required deliverable in `design.md` for this service.
   The exact presentation format (Mermaid diagram, table, or other) is a design-stage decision.
   The spec defines the required navigation links (see Scope — Logical cross-navigation); the
   designer produces the map. If any required navigation link cannot be satisfied by existing
   API responses (e.g. a trail link from an incident requires `trailId` to be present in the
   `GET /incidents/{id}` response — which it is, per the Correlation Engine spec), no contract
   change is needed; otherwise the designer must flag the gap as a human-resolvable contract
   question.

10. **[DESIGN-STAGE] Default refresh interval value.**
    The spec sets the default streaming refresh interval at 3 s. The designer should confirm
    this default is acceptable for the integration stack's expected API response latency and
    that it does not create excessive load on the Alarm Manager and Correlation Engine under
    normal operation. If the integration test environment reveals a different appropriate
    default, the designer may adjust the env-default value in `environment.ts`; this is not a
    contract change. If adjusting the default requires changes to Alarm Manager or Correlation
    Engine query-rate handling, that is a contract gap requiring human resolution.

11. **[HUMAN DECISION REQUIRED] Site attribution for neighbour-expanded nodes.**
    The Topology Service `NodeDto` schema (published `services/topology/openapi.json`) does not
    carry a `siteId` field directly. Nodes fetched by `GET /topology/sites/{siteId}/objects`
    are attributed to their site by fetch context. Nodes fetched by the neighbours query
    (`GET /topology/nodes/{managedObjectId}/neighbors`) may belong to a different site — but
    `NeighborsDto` and `NodeDto` carry no `siteId`. The site-boundary grouping AC (AC 61)
    requires that nodes added by expansion are attributed to their correct site. Three
    resolution options exist: (a) rely on a follow-up `GET /topology/nodes/{id}` or
    objects-at-site call to determine the site of each expanded node — no contract change;
    (b) add `siteId` to `NodeDto` in the Topology Service — a contract change to
    `architecture.md` + `services/topology/openapi.json` requiring human approval; (c) the
    designer specifies an alternative attribution strategy using the existing API surface (e.g.
    inferring from the `SiteObjectsDto` cache). **This spec does not resolve the choice — it
    is a human decision.** The designer must not silently add a new field; if option (b) is
    chosen a contract-change PR must be opened before the design proceeds.

12. **[DESIGN-STAGE] Node-cap default value and expansion UX.**
    The spec requires a configurable `TOPOLOGY_NODE_CAP` and specifies that expansion is
    rejected when the cap would be exceeded, with operator-visible feedback. The default cap
    value and the exact UX for the feedback (toast, inline message, disabled expand button
    with tooltip, etc.) are design-stage decisions. The designer chooses a default that
    balances usability and browser rendering performance for the expected synthetic topology
    size; this is not a contract change. If the integration tests reveal performance issues at
    the chosen default, the designer adjusts the env default without a spec change.
