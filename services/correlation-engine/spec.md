# correlation-engine — Service Spec

## Purpose

The Correlation Engine is the real-time correlation core of the platform. In Phase 3, it
consumes live enriched alarms from `alarms.enriched.live`, matches them statefully against
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

## Scope

**In scope:**
- Consuming `patterns.approved` (`PatternApprovedEvent`) at startup and on every new approval event to maintain an in-memory (or local-state) approved-pattern set, scoped by `trailId`.
- Consuming `codebook.generated` (`CodebookGeneratedEvent`) to receive the codebook version reference; fetching the full scenario signatures from the Codebook Generator API (the event carries a summary only — `codebookId`, `snapshotId`, `scenarioCount`).
- Consuming `alarms.enriched.live` (`AlarmEvent` with `trailIds[]` populated by Enrichment) as the primary real-time input.
- Maintaining per-trail sliding-window state with timeouts; window duration is aligned to the Phase-2 session-gap parameter obtained from the Knowledge Service (configurable, not hard-coded) so that alarms arriving within one session gap are grouped for joint evaluation.
- For each active window, evaluating pattern matching: advancing per-pattern sequence state machines over the windowed alarm sequence; firing a match when the match condition is satisfied; partial match (tolerance for dropped alarms within a window) is allowed, with the tolerance bound sourced from the Knowledge Service.
- For each active window, evaluating codebook decoding: scoring the observed symptom set against all trail-scoped codebook scenarios by closest-match (minimum distance: tolerate missing alarms from the expected signature, penalize alarms present in the observation but absent from the scenario); selecting the best-scoring scenario as the codebook match candidate.
- Conflict resolution when multiple patterns or codebook scenarios claim ownership of the same alarm set: resolving deterministically by (1) specificity — the match covering more alarms wins — then (2) confidence — higher confidence wins; no random tie-breaking.
- On a winning match: tagging one `alarmId` as the root-cause alarm (derived from `rootCauseAlarmType` in the winning pattern or codebook scenario, resolved to the specific `alarmId` in the window) and all other correlated `alarmId`s as child alarms; assigning a stable `incidentId`; persisting the incident record to the Incident Store (PostgreSQL); emitting one `CorrelationResultEvent` on `correlation.results`.
- Populating all applicable fields on `CorrelationResultEvent`: `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId` (if pattern match), `matchedCodebookId` (if codebook match), `confidence`, `trailId`.
- Serving the Incident/Stats read API (OpenAPI 3.1) for the web-ui's Correlation Stats module: live incidents with root cause + children, and the data needed to compute alarm-reduction ratio, RCA accuracy, and match breakdown (pattern vs. codebook matches, confidence distribution).
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
- Feedback/closed-loop automated retraining — deferred from MVP.
- Redundancy/protection-aware propagation modelling (FRR, ECMP) — deferred from MVP.
- Multi-tenancy, production-grade HA/scale, real OSS connectors — deferred from MVP.
- Schema registry — replaced by the `libs/event-model` shared library.

## Tasks (high-level)

1. Load approved patterns: on startup, fetch all currently approved patterns from the Pattern Manager API; on each `patterns.approved` event, update the local approved-pattern set for the relevant `trailId`. The local pattern set is the reference for real-time pattern matching.

2. Load the codebook: on each `codebook.generated` event, record the new `codebookId` and fetch full per-trail scenario signatures (root-cause type, expected symptom set) from the Codebook Generator API. The fetched signatures are the reference for codebook decoding.

3. Consume and validate `alarms.enriched.live`: validate each `AlarmEvent` against the `libs/event-model` Java binding; deduplicate on `alarmId`; route poison/unparseable messages to `alarms.enriched.live.dlq`; dispatch valid alarms into per-trail window state.

4. Maintain per-trail sliding-window state: group incoming alarms by each `trailId` in their `trailIds[]` array into per-trail session windows; keep windows open while alarms continue arriving within the session-gap timeout (from Knowledge Service); expire windows after silence exceeds the timeout and trigger evaluation for the expired window.

5. Evaluate pattern matching per window: for each approved pattern scoped to the trail, advance the pattern's sequence state machine over the windowed alarm sequence; fire a match when the match condition is satisfied (partial match tolerance applied); record the set of matched `alarmId`s and the `matchedPatternId`.

6. Evaluate codebook decoding per window: score the windowed alarm set against each trail-scoped codebook scenario using closest-match (tolerate missing alarms, penalize spurious); select the best-scoring scenario as the codebook decode candidate; record the matched `alarmId` set and `matchedCodebookId`.

7. Resolve conflicts: when multiple patterns or codebook scenarios claim the same alarm, apply deterministic conflict resolution (specificity first, confidence second) to select exactly one winner per alarm set; record the final winning match and its `confidence` score.

8. Create and persist the incident: for the winning match, designate the root-cause `alarmId` (resolved from `rootCauseAlarmType` in the winning match to the specific alarm instance in the window), collect the remaining correlated `alarmId`s as children, assign a stable `incidentId`, and persist the incident record to the Incident Store (PostgreSQL).

9. Emit `correlation.results`: publish one `CorrelationResultEvent` per incident created, with `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, and `trailId` populated.

10. Serve the Incident/Stats read API (OpenAPI 3.1): expose endpoints for the web-ui's Correlation Stats module to retrieve live incidents (root cause + children), match breakdown, and the raw counts needed to compute alarm-reduction ratio and RCA accuracy against ground-truth labels from the Simulator oracle.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no live alarms and no approved patterns exist at this phase. | Idle | — |
| P2 — Pattern learning | Not involved; learning happens entirely upstream (Enrichment → Noise Filter → Pattern Miner → Pattern Manager). The Correlation Engine does not process `alarms.enriched` (history path) and does not participate in pattern discovery or approval. | Idle | — |
| P3 — Real-time correlation | Core work phase: loads approved patterns + codebook, consumes `alarms.enriched.live`, matches/scores/conflict-resolves, tags root cause + child alarms, persists incidents, emits `correlation.results`; serves the Incident/Stats read API to the web-ui. | Active | In (Kafka): `alarms.enriched.live`, `patterns.approved`, `codebook.generated`; Out (Kafka): `correlation.results`; Calls (API): Pattern Manager (approved patterns), Codebook Generator (scenario signatures), Knowledge Service (session-gap + thresholds); Serves (API): Incident/Stats read API (web-ui) |

## Contract

- **Consumes (Kafka):**
  - `alarms.enriched.live` — `AlarmEvent` (`alarmId`, `managedObjectId` in `<objectType>:<id>` scheme, `eventType`, `probableCause`, `perceivedSeverity`, `raisedAt`, `state`, `trailIds[]`; `trailIds[]` populated by Enrichment)
  - `patterns.approved` — `PatternApprovedEvent` (`patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `codebookMatchId?`, `lifecycle`)
  - `codebook.generated` — `CodebookGeneratedEvent` (`codebookId`, `snapshotId`, `scenarioCount`; full scenario signatures fetched via Codebook Generator API)

- **Produces (Kafka):**
  - `correlation.results` — `CorrelationResultEvent` (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`); one event per incident created

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; checked in to `services/correlation-engine/openapi.json`):
  - `GET /incidents` — list incidents; supports filter by `trailId`, time range, and match type (`pattern` | `codebook`); returns per incident: `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`. Pagination is a design-stage detail.
  - `GET /incidents/{incidentId}` — retrieve a single incident by `incidentId` with full detail.
  - `GET /stats` — return aggregate statistics for the web-ui Correlation Stats module: `totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution` (bucketed). Ground-truth comparison (RCA accuracy) is computed by the evaluation harness against these raw counts; the Correlation Engine exposes the data, not the accuracy score itself (see Open question 4).

- **APIs/data consumed from other services:**
  - **Pattern Manager** — `GET /patterns?lifecycle=approved` (list of all approved patterns with `patternId`, `sequence[]`, `rootCauseAlarmType`, `trailId`, `confidence`, `codebookMatchId?`); built against Pattern Manager's published OpenAPI.
  - **Codebook Generator** — fetch full scenario signatures for a given `codebookId`, indexed by `trailId` (exact endpoint TBD — see Open question 1); built against Codebook Generator's published OpenAPI.
  - **Knowledge Service** — fetch session-gap duration, partial-match tolerance, scoring threshold floors, and conflict-resolution parameters; built against Knowledge Service's published OpenAPI; no hard-coded values.

- **Integration points (mock vs. real):**
  - Each outbound dependency (Pattern Manager, Codebook Generator, Knowledge Service) is configured via environment variables (base URL + `INTEGRATION_MODE=mock|real`).
  - Unit tests: backed by mock/stub generated from collaborator's published OpenAPI spec (e.g. WireMock/MockWebServer); no live dependencies.
  - Integration tests: pointed at real collaborating services in Docker Compose.

- **Data owned:**
  - **PostgreSQL — Incident Store**: incident records (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`). No other service reads or writes the Incident Store directly; all external access is through the Correlation Engine's read API.

## Non-functional

- **Idempotency key:** `alarmId` for deduplication of `alarms.enriched.live` events; `eventId` for deduplication of `patterns.approved` and `codebook.generated` events; `incidentId` must be stable across reprocessing of the same alarm window (re-evaluating the same set of `alarmId`s within the same `trailId` and window must yield the same `incidentId`).
- **Config:** all thresholds and window parameters sourced from the Knowledge Service API or environment variables — no hard-coded values. Specifically: session-gap (window timeout), partial-match tolerance, scoring threshold floors, conflict-resolution weights, and all integration base URLs must be externally configurable.
- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus; expose at minimum: `incidents_created_total`, `alarms_processed_total`, `pattern_match_total`, `codebook_match_total`, `window_timeouts_total`, `dlq_routed_total`), structured JSON logs.
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; `openapi.json` checked in to `services/correlation-engine/openapi.json`; the published spec drives contract/unit tests; a surface change is a contract change requiring `architecture.md` update + human approval.
- **Error handling:** poison/unparseable messages on each consumed topic routed to `<topic>.dlq` (`alarms.enriched.live.dlq`, `patterns.approved.dlq`, `codebook.generated.dlq`); not dropped silently; processing of subsequent valid messages must continue uninterrupted.

## Acceptance criteria

Each criterion maps to a single JUnit 5 test.

1. **Fiber-cut storm — one incident, partial match tolerated:** given a replayed fiber-cut scenario (LOS as root cause + N downstream child alarms) with one alarm dropped from the stream, the service creates exactly one `CorrelationResultEvent` with `rootCauseAlarmId` matching the LOS alarm and `childAlarmIds[]` containing the surviving downstream alarms.

2. **Deterministic conflict resolution — specificity then confidence:** given two approved patterns that both claim the same alarm window, where pattern A covers more alarms (higher specificity) than pattern B, the service always selects pattern A as the winner across repeated replays. In a tie on specificity, the pattern with higher `confidence` wins.

3. **Codebook cold-start — closest-match decode without an approved pattern:** given a window of live alarms matching a codebook scenario but with no approved pattern covering those alarms, the service creates an incident with `matchedCodebookId` set to the matched scenario's `codebookId` reference, `matchedPatternId` absent (null), and `rootCauseAlarmId` correctly resolved from the codebook scenario's root-cause designation.

4. **Codebook tolerance — missing and extra alarms:** given a codebook scenario whose expected signature contains S alarms, when the observed alarm set is missing one alarm from S and contains one spurious alarm not in S, the service selects that scenario as the best closest-match and creates an incident (not a no-match result).

5. **`CorrelationResultEvent` schema compliance:** every `CorrelationResultEvent` emitted validates against the frozen `CorrelationResultEvent` schema in `libs/event-model`; all required fields (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `confidence`, `trailId`) are present and non-null.

6. **Required fields populated — pattern match:** for an incident created from a pattern match, `matchedPatternId` is non-null, `confidence` is in [0, 1], and `trailId` matches the `trailId` of the matched `PatternApprovedEvent`.

7. **Required fields populated — codebook match:** for an incident created from a codebook decode (no pattern), `matchedCodebookId` is non-null, `matchedPatternId` is null, `confidence` is in [0, 1], and `trailId` matches the codebook scenario's trail tag.

8. **Idempotency — duplicate alarm does not create a duplicate incident:** replaying the same `alarmId` twice within the same window results in exactly one incident, not two.

9. **Window alignment — configurable session gap:** given a Knowledge Service session-gap parameter of T, alarms arriving within T of each other are placed in the same window; alarms separated by a gap exceeding T are placed in separate windows and evaluated independently. Changing T to a different configured value changes the grouping outcome without a code change.

10. **Alarm-reduction ratio computable from stats API:** calling `GET /stats` after replaying a scenario with K raw alarms that collapse to I incidents returns `totalAlarmsProcessed >= K` and `totalIncidentsCreated = I`, making alarm-reduction ratio K/I derivable.

11. **Incident read API — root cause and children:** calling `GET /incidents/{incidentId}` returns `rootCauseAlarmId` and `childAlarmIds[]` that match the values emitted in the `CorrelationResultEvent` for the same `incidentId`.

12. **Poison message routing — processing continues:** an unparseable message on `alarms.enriched.live` is routed to `alarms.enriched.live.dlq` and the service continues processing the next valid message without halting.

## Open questions

1. **Codebook scenario signature fetch endpoint (contract gap):** `CodebookGeneratedEvent` carries only `codebookId`, `snapshotId`, and `scenarioCount` (summary). The Correlation Engine needs to fetch full per-trail scenario signatures (root-cause type, expected symptom set per scenario, trail tags) from the Codebook Generator API. The Codebook Generator's published OpenAPI must define this endpoint before the design phase can proceed. If the endpoint is absent from the Codebook Generator's current published spec, this is a contract change requiring `architecture.md` update + human approval. (Labels: `question`, `service:correlation-engine`)

2. **Codebook version alignment with approved patterns:** when multiple codebook versions exist (e.g. topology changed between learning runs), a pattern approved against codebook version V1 may coexist alongside a newer version V2. The Correlation Engine needs a defined rule for which codebook version to use when decoding a given window. `PatternApprovedEvent` carries `codebookMatchId?` (referencing a scenario) but does not carry the parent `codebookId` / version identifier explicitly. Either `PatternApprovedEvent` should be extended with a `codebookId` field, or the Correlation Engine should use the latest available codebook version unconditionally. This is a contract decision for a human to resolve; if `PatternApprovedEvent` needs a new field it is a contract change. (Labels: `question`, `service:correlation-engine`)

3. **Partial-match tolerance as a Knowledge Service parameter:** the spec permits partial match but defers the exact tolerance bound (e.g. "fire if ≥ N−1 of N sequence elements are observed") to the design stage. This threshold must not be hard-coded per the no-literals rule. Confirm whether partial-match tolerance is a Knowledge Service parameter (preferred, for consistency) or a service-internal configurable default. If it is a Knowledge Service parameter it must be added to the Knowledge Service API surface — which is a contract change if not already present. (Labels: `question`, `service:correlation-engine`)

4. **RCA accuracy computation ownership:** §10 defines RCA accuracy as a platform metric. The `GET /stats` endpoint exposes raw counts (`totalAlarmsProcessed`, `totalIncidentsCreated`). Accuracy computation requires the Simulator's ground-truth `{rootCause, children}` labels. Confirm whether: (a) accuracy is computed client-side in the web-ui from stats + a separate ground-truth feed, (b) a separate evaluation harness computes it, or (c) the Correlation Engine should accept a ground-truth label feed for server-side accuracy tracking. Option (c) introduces an additional API surface that is a contract change. (Labels: `question`, `service:correlation-engine`)
