# correlation-engine — Service Spec

## Purpose

The Correlation Engine is the real-time correlation core of the platform. In Phase 3, it
consumes live persisted alarms from `alarms.persisted.live`, matches them statefully against
the two upstream knowledge sources — approved patterns (from the Pattern Manager via
`patterns.approved`) and the model-derived codebook (from the Codebook Generator via
`codebook.generated`) — and produces correlated incidents. For each winning match it tags
one alarm as the root cause and the rest as child alarms, persists the incident to the
Incident Store (PostgreSQL), and emits `correlation.results` (`CorrelationResultEvent`).
It provides a read API for the web-ui's Correlation Stats module so operators can view live
incidents and the effectiveness metrics (alarm-reduction ratio, RCA accuracy, match stats)
that demonstrate the platform's value. Confidence scoring and deterministic conflict
resolution ensure every incident is attributable to exactly one winning match. The service
is the sole owner of the Incident Store; it does not mine patterns, own pattern lifecycle,
compile the codebook, or own the topology graph.

> **Why `alarms.persisted.live` and not `alarms.enriched.live`:** the Alarm Manager sits
> in-line on the live path between Enrichment and the Correlation Engine. It consumes
> `alarms.enriched.live`, persists each live alarm (initial state `open`) to the live alarm
> store, and republishes it on `alarms.persisted.live`. The Correlation Engine consumes
> `alarms.persisted.live`, which guarantees every alarm entering correlation has already been
> persisted. The payload is the same frozen `AlarmEvent` — no new payload type is introduced
> (contract #73).

## Scope

**In scope:**
- Consuming `patterns.approved` (`PatternApprovedEvent`) at startup and on every new approval event to maintain an in-memory (or local-state) approved-pattern set, scoped by `trailId`.
- Consuming `codebook.generated` (`CodebookGeneratedEvent`) to receive the codebook version reference; fetching the full scenario signatures from the Codebook Generator API (the event carries a summary only — `codebookId`, `snapshotId`, `scenarioCount`). On each new `codebook.generated` event the engine replaces its in-scope codebook with the **latest** received for the relevant `snapshotId`/trail scope; version alignment is by `snapshotId` + trail scope, not by a `codebookId` field on `PatternApprovedEvent` (no such field is added — no contract change).
- Consuming `alarms.persisted.live` (`AlarmEvent` with `trailIds[]` populated by Enrichment and persisted by the Alarm Manager) as the primary real-time input.
- Maintaining per-trail sliding-window state with timeouts; window duration is aligned to the Phase-2 session-gap parameter obtained from the Knowledge Service (configurable, not hard-coded) so that alarms arriving within one session gap are grouped for joint evaluation.
- For each active window, evaluating pattern matching: advancing per-pattern sequence state machines over the windowed alarm sequence; firing a match when the match condition is satisfied; partial match (tolerance for dropped alarms within a window) is allowed, with the **partial-match tolerance bound sourced from the Knowledge Service** (no hard-coded thresholds).
- For each active window, evaluating codebook decoding: scoring the observed symptom set against all trail-scoped codebook scenarios by closest-match (minimum distance: tolerate missing alarms from the expected signature, penalize alarms present in the observation but absent from the scenario); selecting the best-scoring scenario as the codebook match candidate; scoring and conflict-resolution thresholds sourced from the Knowledge Service.
- Conflict resolution when multiple patterns or codebook scenarios claim ownership of the same alarm set: resolving deterministically by (1) specificity — the match covering more alarms wins — then (2) confidence — higher confidence wins; no random tie-breaking; conflict-resolution weights sourced from the Knowledge Service.
- On a winning match: tagging one `alarmId` as the root-cause alarm (derived from `rootCauseAlarmType` in the winning pattern or codebook scenario, resolved to the specific `alarmId` in the window) and all other correlated `alarmId`s as child alarms; assigning a stable `incidentId`; persisting the incident record to the Incident Store (PostgreSQL); emitting one `CorrelationResultEvent` on `correlation.results`.
- Populating all applicable fields on `CorrelationResultEvent`: `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId` (if pattern match), `matchedCodebookId` (if codebook match), `confidence`, `trailId`.
- Serving the Incident/Stats read API (OpenAPI 3.1) for the web-ui's Correlation Stats module: live incidents with root cause + children, and the **raw counts** (`totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution`) needed for downstream computation of alarm-reduction ratio. RCA accuracy is **not** computed inside this service — it is computed at evaluation time by comparing the emitted incidents' tagged root cause against the Simulator's ground-truth labels (the integration-test/evaluation oracle owns this comparison).
- Deduplicating consumed alarms and events on `eventId`/`alarmId` (idempotency under Kafka at-least-once delivery).
- Routing poison/unparseable messages to the appropriate `<topic>.dlq` topic.
- Making all outbound integration points (Pattern Manager API, Codebook Generator API, Knowledge Service API) config-switchable: mock (backed by collaborator's published OpenAPI) for unit tests, real for integration.
- Publishing `/health`, `/metrics` (Prometheus), and structured JSON logs.
- Publishing `openapi.json` (OpenAPI 3.1) at `/openapi.json`; checking the generated `openapi.json` in to `services/correlation-engine/openapi.json`.

## Out of scope

- Mining patterns from alarm history — owned by Pattern Miner.
- Owning pattern lifecycle (`draft → approved → deprecated`) — owned by Pattern Manager; the Correlation Engine is a downstream consumer of already-approved patterns only.
- Compiling or updating the codebook — owned by Codebook Generator; the Correlation Engine reads the codebook via the Codebook Generator API and never writes codebook state.
- Owning or querying the topology graph directly — owned by Topology Service; the Correlation Engine never touches Apache AGE.
- Deterministic pre-filtering (flap-damping, dedup, self-clear suppression, trail tagging) — owned by Enrichment Service upstream; alarms arriving on `alarms.enriched.live` are already enriched, deduplicated, and trail-tagged.
- DBSCAN statistical noise removal — owned by Noise Filter Service on the history path; the Correlation Engine operates only on the live enriched alarm stream and does not run DBSCAN.
- Processing `alarms.history`, `alarms.enriched`, `transactions.clean`, or any Phase-2-only topics — the Correlation Engine is live-path only.
- The human-approval workflow for patterns — owned by Pattern Manager + web-ui.
- Computing RCA accuracy inside the service — RCA accuracy is computed at evaluation time by the integration-test/evaluation oracle (comparing emitted incidents against Simulator ground-truth labels); the Correlation Engine exposes raw counts only via `GET /stats`.
- Accepting a ground-truth label feed or computing server-side accuracy scores — out of scope; no additional API surface for accuracy is introduced.
- Feedback/closed-loop automated retraining — deferred from MVP.
- Redundancy/protection-aware propagation modelling (FRR, ECMP) — deferred from MVP.
- Multi-tenancy, production-grade HA/scale, real OSS connectors — deferred from MVP.
- Schema registry — replaced by the `libs/event-model` shared library.

## Tasks (high-level)

1. Load approved patterns: on startup, fetch all currently approved patterns from the Pattern Manager API; on each `patterns.approved` event, update the local approved-pattern set for the relevant `trailId`. The local pattern set is the reference for real-time pattern matching.

2. Load the codebook: on each `codebook.generated` event, record the new `codebookId` and fetch full per-trail scenario signatures (root-cause type, expected symptom set) from the Codebook Generator API. Always maintain the **latest codebook in scope** for each `snapshotId`/trail; when a newer `codebook.generated` event arrives for the same scope, replace the prior codebook. The fetched signatures are the reference for codebook decoding; version alignment is by `snapshotId` + trail scope.

3. Consume and validate `alarms.persisted.live`: validate each `AlarmEvent` against the `libs/event-model` Java binding; deduplicate on `alarmId`; route poison/unparseable messages to `alarms.persisted.live.dlq`; dispatch valid alarms into per-trail window state.

4. Maintain per-trail sliding-window state: group incoming alarms by each `trailId` in their `trailIds[]` array into per-trail session windows; keep windows open while alarms continue arriving within the session-gap timeout (sourced from Knowledge Service — no hard-coded value); expire windows after silence exceeds the timeout and trigger evaluation for the expired window.

5. Evaluate pattern matching per window: for each approved pattern scoped to the trail, advance the pattern's sequence state machine over the windowed alarm sequence; fire a match when the match condition is satisfied, applying the **partial-match tolerance sourced from the Knowledge Service** (no hard-coded threshold); record the set of matched `alarmId`s and the `matchedPatternId`.

6. Evaluate codebook decoding per window: score the windowed alarm set against each trail-scoped codebook scenario using closest-match (tolerate missing alarms, penalize spurious); apply scoring threshold floors sourced from the Knowledge Service; select the best-scoring scenario as the codebook decode candidate; record the matched `alarmId` set and `matchedCodebookId`.

7. Resolve conflicts: when multiple patterns or codebook scenarios claim the same alarm, apply deterministic conflict resolution (specificity first, confidence second) using conflict-resolution weights sourced from the Knowledge Service; select exactly one winner per alarm set; record the final winning match and its `confidence` score.

8. Create and persist the incident: for the winning match, designate the root-cause `alarmId` (resolved from `rootCauseAlarmType` in the winning match to the specific alarm instance in the window), collect the remaining correlated `alarmId`s as children, assign a stable `incidentId`, and persist the incident record to the Incident Store (PostgreSQL).

9. Emit `correlation.results`: publish one `CorrelationResultEvent` per incident created, with `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, and `trailId` populated.

10. Serve the Incident/Stats read API (OpenAPI 3.1): expose endpoints for the web-ui's Correlation Stats module to retrieve live incidents (root cause + children), match breakdown, and **raw counts** (`totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution`) enabling alarm-reduction ratio derivation. RCA accuracy is computed externally at evaluation time by the integration-test oracle comparing these incidents against Simulator ground-truth labels — the service does not compute or store accuracy scores.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no live alarms and no approved patterns exist at this phase. | Idle | — |
| P2 — Pattern learning | Not involved; learning happens entirely upstream (Enrichment → Noise Filter → Pattern Miner → Pattern Manager). The Correlation Engine does not process `alarms.enriched` (history path) and does not participate in pattern discovery or approval. | Idle | — |
| P3 — Real-time correlation | Core work phase: loads approved patterns + latest-in-scope codebook, consumes `alarms.persisted.live`, matches/scores/conflict-resolves (all thresholds from Knowledge Service), tags root cause + child alarms, persists incidents, emits `correlation.results`; serves the Incident/Stats read API (raw counts) to the web-ui. | Active | In (Kafka): `alarms.persisted.live`, `patterns.approved`, `codebook.generated`; Out (Kafka): `correlation.results`; Calls (API): Pattern Manager (approved patterns), Codebook Generator (scenario signatures), Knowledge Service (session-gap + partial-match tolerance + scoring/conflict thresholds); Serves (API): Incident/Stats read API (web-ui) |

## Contract

- **Consumes (Kafka):**
  - `alarms.persisted.live` — `AlarmEvent` (`alarmId`, `managedObjectId` in `<objectType>:<id>` scheme, `eventType`, `probableCause`, `perceivedSeverity`, `raisedAt`, `state`, `trailIds[]`; `trailIds[]` populated by Enrichment, alarm persisted in-line by the Alarm Manager before reaching this topic)
  - `patterns.approved` — `PatternApprovedEvent` (`patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `codebookMatchId?`, `lifecycle`). No `codebookId` field is present or required on this event; the engine resolves codebook version by `snapshotId`/trail scope from the latest `codebook.generated` event.
  - `codebook.generated` — `CodebookGeneratedEvent` (`codebookId`, `snapshotId`, `scenarioCount`; full scenario signatures fetched via Codebook Generator API). The engine always uses the **latest** event received for a given `snapshotId`/trail scope as the active codebook.

- **Produces (Kafka):**
  - `correlation.results` — `CorrelationResultEvent` (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`); one event per incident created

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; checked in to `services/correlation-engine/openapi.json`):
  - `GET /incidents` — list incidents; supports filter by `trailId`, time range, and match type (`pattern` | `codebook`); returns per incident: `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`. Pagination is a design-stage detail.
  - `GET /incidents/{incidentId}` — retrieve a single incident by `incidentId` with full detail.
  - `GET /stats` — return aggregate raw counts for the web-ui Correlation Stats module: `totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution` (bucketed). These counts enable computation of alarm-reduction ratio (`totalAlarmsProcessed / totalIncidentsCreated`). RCA accuracy is **not** returned here; it is computed at evaluation time by the integration-test oracle comparing the engine's emitted `rootCauseAlarmId` values in `correlation.results` against the Simulator's ground-truth labels, per the thresholds defined in `services/simulator/spec.md`.

- **APIs/data consumed from other services:**
  - **Pattern Manager** — `GET /patterns?lifecycle=approved` (list of all approved patterns with `patternId`, `sequence[]`, `rootCauseAlarmType`, `trailId`, `confidence`, `codebookMatchId?`); built against Pattern Manager's published OpenAPI.
  - **Codebook Generator** — fetch full scenario signatures for a given `codebookId`, indexed by `trailId` (exact endpoint shape is published in the Codebook Generator's OpenAPI at design time — see Open questions); built against Codebook Generator's published OpenAPI.
  - **Knowledge Service** — fetch session-gap duration, **partial-match tolerance**, **scoring threshold floors**, and **conflict-resolution parameters**; all sourced from Knowledge Service — no hard-coded thresholds anywhere in the engine; built against Knowledge Service's published OpenAPI.

- **Integration points (mock vs. real):**
  - Each outbound dependency (Pattern Manager, Codebook Generator, Knowledge Service) is configured via environment variables (base URL + `INTEGRATION_MODE=mock|real`).
  - Unit tests: backed by mock/stub generated from collaborator's published OpenAPI spec (e.g. WireMock/MockWebServer); no live dependencies.
  - Integration tests: pointed at real collaborating services in Docker Compose.

- **Data owned:**
  - **PostgreSQL — Incident Store**: incident records (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`). No other service reads or writes the Incident Store directly; all external access is through the Correlation Engine's read API.

## Non-functional

- **Idempotency key:** `alarmId` for deduplication of `alarms.persisted.live` events; `eventId` for deduplication of `patterns.approved` and `codebook.generated` events; `incidentId` must be stable across reprocessing of the same alarm window (re-evaluating the same set of `alarmId`s within the same `trailId` and window must yield the same `incidentId`).
- **Config:** all thresholds and window parameters sourced from the Knowledge Service API — no hard-coded values. Specifically: session-gap (window timeout), partial-match tolerance, scoring threshold floors, conflict-resolution weights are Knowledge Service parameters; all integration base URLs and `INTEGRATION_MODE` are environment variables.
- **Codebook version alignment:** the engine uses the latest `codebook.generated` event received for the relevant `snapshotId`/trail scope. When a newer codebook arrives for the same scope, it replaces the prior one for all subsequent window evaluations. This requires no additional field on `PatternApprovedEvent` and is not a contract change.
- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus; expose at minimum: `incidents_created_total`, `alarms_processed_total`, `pattern_match_total`, `codebook_match_total`, `window_timeouts_total`, `dlq_routed_total`), structured JSON logs.
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; `openapi.json` checked in to `services/correlation-engine/openapi.json`; the published spec drives contract/unit tests; a surface change is a contract change requiring `architecture.md` update + human approval.
- **Error handling:** poison/unparseable messages on each consumed topic routed to `<topic>.dlq` (`alarms.persisted.live.dlq`, `patterns.approved.dlq`, `codebook.generated.dlq`); not dropped silently; processing of subsequent valid messages must continue uninterrupted.

## Acceptance criteria

Each criterion maps to a single JUnit 5 test.

1. **Fiber-cut storm — one incident, partial match tolerated:** given a replayed fiber-cut scenario (LOS as root cause + N downstream child alarms) with one alarm dropped from the stream, and a partial-match tolerance parameter from the Knowledge Service that permits N−1 of N matches, the service creates exactly one `CorrelationResultEvent` with `rootCauseAlarmId` matching the LOS alarm and `childAlarmIds[]` containing the surviving downstream alarms.

2. **Deterministic conflict resolution — specificity then confidence:** given two approved patterns that both claim the same alarm window, where pattern A covers more alarms (higher specificity) than pattern B, the service always selects pattern A as the winner across repeated replays. In a tie on specificity, the pattern with higher `confidence` wins. Conflict-resolution weights are sourced from a Knowledge Service mock (no hard-coded values in the assertion setup).

3. **Codebook cold-start — closest-match decode without an approved pattern:** given a window of live alarms matching a codebook scenario but with no approved pattern covering those alarms, the service creates an incident with `matchedCodebookId` set to the matched scenario's `codebookId` reference, `matchedPatternId` absent (null), and `rootCauseAlarmId` correctly resolved from the codebook scenario's root-cause designation.

4. **Codebook tolerance — missing and extra alarms:** given a codebook scenario whose expected signature contains S alarms, when the observed alarm set is missing one alarm from S and contains one spurious alarm not in S, the service selects that scenario as the best closest-match and creates an incident (not a no-match result). Scoring threshold floors are sourced from a Knowledge Service mock.

5. **`CorrelationResultEvent` schema compliance:** every `CorrelationResultEvent` emitted validates against the frozen `CorrelationResultEvent` schema in `libs/event-model`; all required fields (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `confidence`, `trailId`) are present and non-null.

6. **Required fields populated — pattern match:** for an incident created from a pattern match, `matchedPatternId` is non-null, `confidence` is in [0, 1], and `trailId` matches the `trailId` of the matched `PatternApprovedEvent`.

7. **Required fields populated — codebook match:** for an incident created from a codebook decode (no pattern), `matchedCodebookId` is non-null, `matchedPatternId` is null, `confidence` is in [0, 1], and `trailId` matches the codebook scenario's trail tag.

8. **Idempotency — duplicate alarm does not create a duplicate incident:** replaying the same `alarmId` twice within the same window results in exactly one incident, not two.

9. **Window alignment — configurable session gap:** given a Knowledge Service session-gap parameter of T (sourced from a Knowledge Service mock), alarms arriving within T of each other are placed in the same window; alarms separated by a gap exceeding T are placed in separate windows and evaluated independently. Changing T to a different configured value changes the grouping outcome without a code change.

10. **Alarm-reduction ratio computable from stats API:** calling `GET /stats` after replaying a scenario with K raw alarms that collapse to I incidents returns `totalAlarmsProcessed >= K` and `totalIncidentsCreated = I`, making alarm-reduction ratio K/I derivable from the response without any additional engine API.

11. **Incident read API — root cause and children:** calling `GET /incidents/{incidentId}` returns `rootCauseAlarmId` and `childAlarmIds[]` that match the values emitted in the `CorrelationResultEvent` for the same `incidentId`.

12. **Poison message routing — processing continues:** an unparseable message on `alarms.persisted.live` is routed to `alarms.persisted.live.dlq` and the service continues processing the next valid message without halting.

13. **Latest codebook used — newer codebook replaces prior:** given two sequential `codebook.generated` events for the same `snapshotId`/trail scope (V1 then V2), the service uses V2's scenario signatures for all window evaluations that begin after V2 is loaded; a window evaluated before V1 is received still uses the then-latest codebook (none / V1 as applicable). No `codebookId` field is required on `PatternApprovedEvent` to satisfy this criterion.

14. **All thresholds from Knowledge Service — no hard-coded values:** given a test that replaces every Knowledge Service parameter (session-gap, partial-match tolerance, scoring threshold floors, conflict-resolution weights) with values different from any default, the engine's matching and conflict-resolution outcomes change to reflect the new parameters with no code change.

## Open questions

1. **Codebook scenario signature fetch endpoint (design-stage):** `CodebookGeneratedEvent` carries only `codebookId`, `snapshotId`, and `scenarioCount` (summary). The engine fetches full per-trail scenario signatures (root-cause type, expected symptom set, trail tags) via the **Codebook Generator's read API**. The exact endpoint shape is published when the Codebook Generator is designed (contract-first); the engine builds its HTTP client and WireMock stub against that published OpenAPI. This is not a spec blocker — the engine's contract (what it consumes and produces) is fully defined; the Codebook Generator OpenAPI is a design-stage dependency. (Issue #55 — relabeled `design-stage`.)
