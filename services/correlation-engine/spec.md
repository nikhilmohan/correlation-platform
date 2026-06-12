# correlation-engine — Service Spec

## Purpose

The Correlation Engine is the real-time correlation core of the platform. In Phase 3, it
consumes live persisted alarms from `alarms.persisted.live` and correlates them by creating,
advancing, and concluding **correlation instances** — one per (trailId, patternId) pair —
against two upstream knowledge sources: approved patterns (from the Pattern Manager via
`patterns.approved`) and the model-derived codebook (from the Codebook Generator via
`codebook.generated`). A correlation instance is born lazily when the first alarm matching a
pattern's opening condition arrives on a trail where that pattern is active; it accumulates
alarms and re-evaluates its match incrementally; it either fully matches and fires immediately
(creating an incident, assigning root cause, emitting `CorrelationResultEvent`, and firing
`AlarmStatusChange` events on `alarms.status.changed`), or its per-pattern session window
expires without a satisfying match, in which case it is destroyed and its alarms revert to
`open`. The same alarm, if it belongs to multiple trails, fans out independently to each
trail's active instances without cross-contamination. Multiple instances may match concurrently
and in isolation. Confidence scoring and deterministic conflict resolution ensure every incident
is attributable to exactly one winning match. The service is the sole owner of the Incident
Store (PostgreSQL), provides a read API for the web-ui's Correlation Stats module, and does not
mine patterns, own pattern lifecycle, compile the codebook, or own the topology graph.

> **Why `alarms.persisted.live` and not `alarms.enriched.live`:** the Alarm Manager sits
> in-line on the live path between Enrichment and the Correlation Engine. It consumes
> `alarms.enriched.live`, persists each live alarm (initial state `open`) to the live alarm
> store, and republishes it on `alarms.persisted.live`. The Correlation Engine consumes
> `alarms.persisted.live`, which guarantees every alarm entering correlation has already been
> persisted. The payload is the same frozen `AlarmEvent` — no new payload type is introduced.

## Scope

**In scope:**
- Consuming `patterns.approved` (`PatternApprovedEvent`) at startup and on every new approval event to maintain an in-memory (or local-state) approved-pattern set. Each approved pattern is scoped to one or more `trailId`s and carries its own per-pattern timing/session-window rules (the `timing` field on `PatternApprovedEvent`). The local pattern set is the reference for deciding which patterns are active on a given trail and what timing rules govern each pattern's instances.
- Consuming `codebook.generated` (`CodebookGeneratedEvent`) to receive the codebook version reference; fetching the full scenario signatures from the Codebook Generator API (the event carries a summary only — `codebookId`, `snapshotId`, `scenarioCount`). On each new `codebook.generated` event the engine replaces its in-scope codebook with the **latest** received for the relevant `snapshotId`/trail scope; version alignment is by `snapshotId` + trail scope, not by a `codebookId` field on `PatternApprovedEvent` (no such field is added — no contract change).
- Consuming `alarms.persisted.live` (`AlarmEvent` with `trailIds[]` populated by Enrichment and persisted by the Alarm Manager) as the primary real-time input.
- **Multi-trail fan-out:** when an alarm arrives whose `trailIds[]` contains multiple trails (e.g. `[T1, T2]`), dispatching the alarm independently to each trail's set of active correlation instances. Each trail resolves its own active patterns; the engine must not assume the same patterns are active on different trails, and instances on different trails evaluate in complete isolation.
- **Lazy initialization of correlation instances:** for each (trailId, patternId) pair where the pattern is active on the trail, a correlation instance is created only when the first alarm matching the pattern's opening condition arrives on that trail. No instance exists before a relevant alarm appears.
- **Incremental / decisive matching:** as each alarm is added to an active instance, the match is re-evaluated immediately (not buffered for a later bulk pass). When the instance's match condition is fully satisfied, the instance fires immediately — it does not wait for any timer or window expiry.
- **Per-pattern session windows:** each correlation instance's session window is governed by the timing/session-window rules carried in the pattern's own `PatternApprovedEvent` (`timing` field). Different patterns may carry different session window durations/rules; the engine applies each pattern's own window to its instances. A single global session-gap is not used. Other match rules (sequence, partial-match tolerance, etc.) likewise come from the pattern or from Knowledge-sourced parameters — no hard-coded thresholds anywhere.
- **Instance lifecycle management (automatic):** each correlation instance follows a managed lifecycle:
  1. **Born** (lazy init): instance is created on the first alarm that matches the pattern's opening condition on the trail.
  2. **In-progress**: the instance accumulates alarms and re-evaluates its match incrementally; the match has not yet been fully satisfied.
  3. **Fully matched -> fires immediately**: RCA is determined and assigned; the incident is created/persisted/emitted; `CorrelationResultEvent` and `AlarmStatusChange(correlated)` are emitted; the instance is **destroyed**.
  4. **Session expires without a match**: if the instance's per-pattern session window elapses before a satisfying match is achieved, the instance is **destroyed**, no incident is created, and `AlarmStatusChange(reverted-open)` is fired for each alarm that had been added to the instance.
- **Status propagation to the Alarm Manager via `alarms.status.changed`:** the engine fires `AlarmStatusChange` events on `alarms.status.changed` (`source = correlation-engine`) on these transitions:
  - When an alarm is added to an active correlation instance (correlation in progress): `newStatus = in-progress`.
  - When an instance fully matches and the alarm becomes correlated: `newStatus = correlated` (both root-cause and child alarms in the instance).
  - When an instance's session window expires without a satisfying match: `newStatus = reverted-open` for each alarm in the expired instance.
  - `AlarmStatusChange` carries `{alarmId, newStatus, source, changedAt}` — the minimal generic signal. The richer correlation context (incidentId, root-cause/child role, trailId) continues to travel on `CorrelationResultEvent`, which is unchanged.
- **Isolation and concurrency:** alarms can arrive from different parts of the topology simultaneously, causing multiple correlation instances to match and assign RCA concurrently. One instance's state must never bleed into another's. The existing per-trail topic partitioning supports this, but the isolation requirement is a core correctness constraint stated explicitly.
- Evaluating codebook decoding as a fallback for alarm sets with no active pattern instance: scoring the observed alarm set against all trail-scoped codebook scenarios by closest-match (minimum distance: tolerate missing alarms from the expected signature, penalize alarms present in the observation but absent from the scenario); selecting the best-scoring scenario as the codebook match candidate; scoring and conflict-resolution thresholds sourced from the Knowledge Service. (The coexistence model — how codebook decode interacts with active pattern instances — is a design-stage decision; see Open questions.)
- Conflict resolution when multiple patterns or codebook scenarios claim ownership of the same alarm set: resolving deterministically by (1) specificity — the match covering more alarms wins — then (2) confidence — higher confidence wins; no random tie-breaking; conflict-resolution weights sourced from the Knowledge Service.
- On a winning match (pattern instance or codebook decode): tagging one `alarmId` as the root-cause alarm (derived from `rootCauseAlarmType` in the winning pattern or codebook scenario, resolved to the specific `alarmId` in the matched set) and all other correlated `alarmId`s as child alarms; assigning a stable `incidentId`; persisting the incident record to the Incident Store (PostgreSQL); emitting one `CorrelationResultEvent` on `correlation.results`.
- Populating all applicable fields on `CorrelationResultEvent`: `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId` (if pattern match), `matchedCodebookId` (if codebook match), `confidence`, `trailId`.
- Serving the Incident/Stats read API (OpenAPI 3.1) for the web-ui's Correlation Stats module: live incidents with root cause + children, and the **raw counts** (`totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution`) needed for downstream computation of alarm-reduction ratio. RCA accuracy is **not** computed inside this service — it is computed at evaluation time by comparing the emitted incidents' tagged root cause against the Simulator's ground-truth labels (the integration-test/evaluation oracle owns this comparison).
- Deduplicating consumed alarms and events on `eventId`/`alarmId` (idempotency under Kafka at-least-once delivery).
- Routing poison/unparseable messages to the appropriate `<topic>.dlq` topic.
- Making all outbound integration points (Pattern Manager API, Codebook Generator API, Knowledge Service API) config-switchable: mock (backed by collaborator's published OpenAPI) for unit tests, real for integration.
- Publishing `/health`, `/metrics` (Prometheus), and structured JSON logs.
- Publishing `openapi.json` (OpenAPI 3.1) at `/openapi.json`; checking the generated `openapi.json` in to `services/correlation-engine/openapi.json`.

## Out of scope

- Mining patterns from alarm history — owned by Pattern Miner.
- Owning pattern lifecycle (`draft -> approved -> deprecated`) — owned by Pattern Manager; the Correlation Engine is a downstream consumer of already-approved patterns only.
- Compiling or updating the codebook — owned by Codebook Generator; the Correlation Engine reads the codebook via the Codebook Generator API and never writes codebook state.
- Owning or querying the topology graph directly — owned by Topology Service; the Correlation Engine never touches the graph DB (NebulaGraph).
- Deterministic pre-filtering (flap-damping, dedup, self-clear suppression, trail tagging) — owned by Enrichment Service upstream; alarms arriving on `alarms.persisted.live` are already enriched, deduplicated, and trail-tagged.
- DBSCAN statistical noise removal — owned by Noise Filter Service on the history path; the Correlation Engine operates only on the live enriched alarm stream and does not run DBSCAN.
- Processing `alarms.history`, `alarms.enriched`, `transactions.clean`, or any Phase-2-only topics — the Correlation Engine is live-path only.
- The human-approval workflow for patterns — owned by Pattern Manager + web-ui.
- Computing RCA accuracy inside the service — RCA accuracy is computed at evaluation time by the integration-test/evaluation oracle (comparing emitted incidents against Simulator ground-truth labels); the Correlation Engine exposes raw counts only via `GET /stats`.
- Accepting a ground-truth label feed or computing server-side accuracy scores — out of scope; no additional API surface for accuracy is introduced.
- Maintaining live alarm state or lifecycle beyond correlation status notifications — the Alarm Manager is the sole owner of live alarm state; the engine fires `AlarmStatusChange` events as a notification mechanism only and does not duplicate alarm state storage.
- Feedback/closed-loop automated retraining — deferred from MVP.
- Redundancy/protection-aware propagation modelling (FRR, ECMP) — deferred from MVP.
- Multi-tenancy, production-grade HA/scale, real OSS connectors — deferred from MVP.
- Schema registry — replaced by the `libs/event-model` shared library.

## Tasks (high-level)

1. **Load approved patterns:** on startup, fetch all currently approved patterns from the Pattern Manager API; on each `patterns.approved` event, add or refresh the pattern in the local approved-pattern set. Record which trails each pattern is active on and the pattern's per-pattern timing/session-window rules (from the `timing` field). This local set drives which (trailId, patternId) instances may be created and what timing governs each one.

2. **Load the codebook:** on each `codebook.generated` event, record the new `codebookId` and fetch full per-trail scenario signatures (root-cause type, expected symptom set) from the Codebook Generator API. Always maintain the **latest codebook in scope** for each `snapshotId`/trail; when a newer `codebook.generated` event arrives for the same scope, replace the prior codebook. The fetched signatures are the reference for codebook decoding; version alignment is by `snapshotId` + trail scope.

3. **Consume, validate, and fan out `alarms.persisted.live`:** validate each `AlarmEvent` against the `libs/event-model` Java binding; deduplicate on `alarmId`; route poison/unparseable messages to `alarms.persisted.live.dlq`; for each valid alarm, fan out to every trail in its `trailIds[]` array — dispatching it independently to that trail's active correlation instances (or initiating new instances where the opening condition is met).

4. **Manage correlation-instance lifecycle (lazy init, in-progress, full-match, session-expiry):** for each (trailId, patternId) pair where the pattern is active on the trail:
   - On the first alarm that matches the pattern's opening condition on the trail, create a new instance (lazy initialization) for that (trailId, patternId) pair.
   - For each subsequent alarm relevant to an existing instance, add the alarm to the instance and re-evaluate the match immediately (incremental, not batched).
   - Fire `AlarmStatusChange(in-progress)` on `alarms.status.changed` for each alarm added to an active instance.
   - If the full-match condition is satisfied: designate the winner (resolving any conflict per Task 6), create and persist the incident (Task 7), emit `CorrelationResultEvent` (Task 8), fire `AlarmStatusChange(correlated)` for all correlated alarms (Task 9), and destroy the instance.
   - If the instance's per-pattern session window (from the pattern's `timing` field) expires before a satisfying match: destroy the instance, emit no incident, and fire `AlarmStatusChange(reverted-open)` for each alarm that had been accumulated in the instance.

5. **Evaluate codebook decoding (fallback for unmatched alarm sets):** for an alarm set on a trail that has no active pattern instance capable of covering it, score the alarm set against all trail-scoped codebook scenarios using closest-match; apply scoring threshold floors sourced from the Knowledge Service; select the best-scoring scenario as the codebook decode candidate if one meets the threshold; pass this candidate to conflict resolution (Task 6). (The exact interaction between concurrent pattern instances and codebook decode for the same alarm set is a design-stage decision — see Open questions.)

6. **Resolve conflicts:** when multiple pattern instances and/or codebook decode candidates claim ownership of the same alarm set, apply deterministic conflict resolution (specificity first, then confidence); select exactly one winner; conflict-resolution weights are sourced from the Knowledge Service — no hard-coded values.

7. **Create and persist the incident:** for the winning match, designate the root-cause `alarmId` (resolved from `rootCauseAlarmType` in the winning match to the specific alarm instance in the matched set), collect the remaining correlated `alarmId`s as children, assign a stable `incidentId`, and persist the incident record to the Incident Store (PostgreSQL).

8. **Emit `correlation.results`:** publish one `CorrelationResultEvent` per incident created, with `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, and `trailId` populated.

9. **Fire `AlarmStatusChange` on `alarms.status.changed`:** emit one `AlarmStatusChange` event per alarm per status transition on the three lifecycle transitions described in Scope (in-progress, correlated, reverted-open), with `source = correlation-engine` and `changedAt` set to the time of the transition. The richer incident context travels on `CorrelationResultEvent`, not here.

10. **Serve the Incident/Stats read API (OpenAPI 3.1):** expose endpoints for the web-ui's Correlation Stats module to retrieve live incidents (root cause + children), match breakdown, and **raw counts** (`totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution`) enabling alarm-reduction ratio derivation. RCA accuracy is computed externally at evaluation time by the integration-test oracle — the service does not compute or store accuracy scores.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no live alarms and no approved patterns exist at this phase. | Idle | — |
| P2 — Pattern learning | Not involved; learning happens entirely upstream (Enrichment -> Noise Filter -> Pattern Miner -> Pattern Manager). The Correlation Engine does not process `alarms.enriched` (history path) and does not participate in pattern discovery or approval. | Idle | — |
| P3 — Real-time correlation | Core work phase: loads approved patterns (with per-pattern timing) + latest-in-scope codebook; consumes `alarms.persisted.live`; fans out to (trailId, patternId) instances; manages instance lifecycle (lazy init, incremental match, session-expiry); fires `AlarmStatusChange` on `alarms.status.changed` on in-progress/correlated/reverted-open transitions; resolves conflicts; tags root cause + child alarms; persists incidents; emits `correlation.results`; serves the Incident/Stats read API (raw counts) to the web-ui. | Active | In (Kafka): `alarms.persisted.live`, `patterns.approved`, `codebook.generated`; Out (Kafka): `correlation.results`, `alarms.status.changed`; Calls (API): Pattern Manager (approved patterns + per-pattern timing), Codebook Generator (scenario signatures), Knowledge Service (partial-match tolerance + scoring/conflict thresholds); Serves (API): Incident/Stats read API (web-ui) |

## Contract

- **Consumes (Kafka):**
  - `alarms.persisted.live` — `AlarmEvent` (`alarmId`, `managedObjectId` in `<objectType>:<id>` scheme, `eventType`, `probableCause`, `perceivedSeverity`, `raisedAt`, `state`, `trailIds[]`; `trailIds[]` populated by Enrichment, alarm persisted in-line by the Alarm Manager before reaching this topic)
  - `patterns.approved` — `PatternApprovedEvent` (`patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `codebookMatchId?`, `lifecycle`). The `timing` field carries the pattern's own session-window and timing rules that govern each correlation instance for this pattern. No `codebookId` field is present or required on this event; the engine resolves codebook version by `snapshotId`/trail scope from the latest `codebook.generated` event.
  - `codebook.generated` — `CodebookGeneratedEvent` (`codebookId`, `snapshotId`, `scenarioCount`; full scenario signatures fetched via Codebook Generator API). The engine always uses the **latest** event received for a given `snapshotId`/trail scope as the active codebook.

- **Produces (Kafka):**
  - `correlation.results` — `CorrelationResultEvent` (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`); one event per incident created; unchanged from prior contract.
  - `alarms.status.changed` — `AlarmStatusChange` (`alarmId`, `newStatus` in {`in-progress`, `correlated`, `reverted-open`}, `source = correlation-engine`, `changedAt`); one event per alarm per lifecycle transition (in-progress on instance admission; correlated on full match; reverted-open on session expiry). This is the already-merged contract (`AlarmStatusChange.schema.json` in `libs/event-model`). The engine is **one of potentially many producers** of this generic signal; the Alarm Manager consumes it to keep live alarm status in sync.

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; checked in to `services/correlation-engine/openapi.json`):
  - `GET /incidents` — list incidents; supports filter by `trailId`, time range, and match type (`pattern` | `codebook`); returns per incident: `incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`. Pagination is a design-stage detail.
  - `GET /incidents/{incidentId}` — retrieve a single incident by `incidentId` with full detail.
  - `GET /stats` — return aggregate raw counts for the web-ui Correlation Stats module: `totalAlarmsProcessed`, `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution` (bucketed). These counts enable computation of alarm-reduction ratio (`totalAlarmsProcessed / totalIncidentsCreated`). RCA accuracy is **not** returned here; it is computed at evaluation time by the integration-test oracle comparing the engine's emitted `rootCauseAlarmId` values in `correlation.results` against the Simulator's ground-truth labels, per the thresholds defined in `services/simulator/spec.md`.

- **APIs/data consumed from other services:**
  - **Pattern Manager** — `GET /patterns?lifecycle=approved` (list of all approved patterns with `patternId`, `sequence[]`, `rootCauseAlarmType`, `trailId`, `confidence`, `timing`, `codebookMatchId?`); the `timing` field is required to obtain per-pattern session-window rules; built against Pattern Manager's published OpenAPI.
  - **Codebook Generator** — fetch full scenario signatures for a given `codebookId`, indexed by `trailId` (exact endpoint shape is published in the Codebook Generator's OpenAPI at design time — see Open questions); built against Codebook Generator's published OpenAPI.
  - **Knowledge Service** — fetch partial-match tolerance, scoring threshold floors, and conflict-resolution parameters; all sourced from Knowledge Service — no hard-coded thresholds anywhere in the engine; built against Knowledge Service's published OpenAPI. Note: the per-pattern session-window duration/rules are sourced from each pattern's `timing` field (via Pattern Manager), not from the Knowledge Service; the Knowledge Service supplies match-quality and conflict-resolution parameters.

- **Integration points (mock vs. real):**
  - Each outbound dependency (Pattern Manager, Codebook Generator, Knowledge Service) is configured via environment variables (base URL + `INTEGRATION_MODE=mock|real`).
  - Unit tests: backed by mock/stub generated from collaborator's published OpenAPI spec (e.g. WireMock/MockWebServer); no live dependencies.
  - Integration tests: pointed at real collaborating services in Docker Compose.

- **Data owned:**
  - **PostgreSQL — Incident Store**: incident records (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `matchedPatternId?`, `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`). No other service reads or writes the Incident Store directly; all external access is through the Correlation Engine's read API. **The Correlation Engine is the system of record for incidents** (the incident-centric view). The Alarm Manager separately consumes `correlation.results` and **denormalizes** each alarm's role (root-cause / child) + `incidentId` onto its live alarm record (the alarm-centric view) — it does not re-own or duplicate the incident itself.

## Non-functional

- **Idempotency key:** `alarmId` for deduplication of `alarms.persisted.live` events; `eventId` for deduplication of `patterns.approved` and `codebook.generated` events; `incidentId` must be stable across reprocessing of the same matched alarm set for the same (trailId, patternId) instance (re-evaluating the same set of `alarmId`s within the same instance must yield the same `incidentId`). Within one trail, at most one live instance per (trailId, patternId) pair exists at any time — enforced by the lazy-init and instance-destruction rules.
- **Config:** all match-quality and conflict-resolution thresholds sourced from the Knowledge Service API; per-pattern session-window and timing rules sourced from the pattern's `timing` field (Pattern Manager); no hard-coded values. Specifically: partial-match tolerance, scoring threshold floors, conflict-resolution weights are Knowledge Service parameters. All integration base URLs and `INTEGRATION_MODE` are environment variables.
- **Codebook version alignment:** the engine uses the latest `codebook.generated` event received for the relevant `snapshotId`/trail scope. When a newer codebook arrives for the same scope, it replaces the prior one for all subsequent instance evaluations. This requires no additional field on `PatternApprovedEvent` and is not a contract change.
- **Isolation invariant:** the state of one (trailId, patternId) correlation instance must never affect another instance's state, alarm set, or outcome — even when multiple instances evaluate concurrently.
- **AlarmStatusChange firing:** every admission of an alarm to an instance, every full-match completion, and every session expiry must each produce the corresponding `AlarmStatusChange` event on `alarms.status.changed` with the correct `newStatus`, `source = correlation-engine`, and `changedAt`. No status transition is silently omitted.
- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus; expose at minimum: `incidents_created_total`, `alarms_processed_total`, `pattern_match_total`, `codebook_match_total`, `instance_session_expirations_total`, `alarms_status_changed_total`, `dlq_routed_total`), structured JSON logs.
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; `openapi.json` checked in to `services/correlation-engine/openapi.json`; the published spec drives contract/unit tests; a surface change is a contract change requiring `architecture.md` update + human approval.
- **Error handling:** poison/unparseable messages on each consumed topic routed to `<topic>.dlq` (`alarms.persisted.live.dlq`, `patterns.approved.dlq`, `codebook.generated.dlq`); not dropped silently; processing of subsequent valid messages must continue uninterrupted.

## Acceptance criteria

Each criterion maps to a single JUnit 5 test.

1. **Lazy-init — first matching alarm creates exactly one instance:** given a trail T1 with pattern P1 active and no prior alarms, when the first alarm matching P1's opening condition arrives on T1, exactly one correlation instance for (T1, P1) is created; no instance for (T1, P1) exists before that alarm arrives.

2. **Multi-trail fan-out — two independent instances from one alarm:** given an alarm whose `trailIds[]` contains two trails T1 and T2, where T1 has pattern P_a active and T2 has pattern P_b active (P_a and P_b may differ), processing that alarm initiates instance(T1, P_a) and instance(T2, P_b) as two independent instances; the state of instance(T1, P_a) is not visible to instance(T2, P_b) and vice versa.

3. **Add-to-existing — second alarm is added to the existing instance, not a new one:** given a trail T with pattern P active and an already-open instance for (T, P), when a second relevant alarm arrives on T, the alarm is added to the existing (T, P) instance and the match is re-evaluated; exactly one instance for (T, P) exists after both alarms have been processed.

4. **Full-match fires and destroys immediately:** given a correlation instance for (T, P) that reaches its full-match condition, the service immediately (without waiting for any timer): creates and persists exactly one incident, emits exactly one `CorrelationResultEvent`, fires `AlarmStatusChange(correlated)` for every alarm in the instance, and destroys the instance (no live instance for (T, P) remains after the match).

5. **Session-expiry destroys the instance and reverts alarms:** given a correlation instance for (T, P) whose pattern's session window (from `timing`) elapses before the full-match condition is met, the service destroys the instance, creates no incident, emits no `CorrelationResultEvent` for that instance's alarm set, and fires `AlarmStatusChange(reverted-open)` for each alarm that had been accumulated in the instance.

6. **In-progress status on alarm admission:** when an alarm is added to an active correlation instance, the service fires exactly one `AlarmStatusChange` with `newStatus = in-progress`, `alarmId` matching the admitted alarm's `alarmId`, and `source = correlation-engine`.

7. **Per-pattern session windows are independent:** given two patterns P1 and P2 active on the same trail, where P1's `timing` carries a session window of duration W1 and P2's `timing` carries a duration W2 (W1 not equal to W2), instance(T, P1) expires at W1 and instance(T, P2) expires at W2; neither instance's expiry is governed by the other's window duration.

8. **Isolation — concurrent instances produce independent incidents:** given two alarm sequences arriving simultaneously from different topology parts, each initiating and completing a separate correlation instance, the service produces two independent incidents with disjoint `childAlarmIds[]` sets and no alarm appears in both incidents.

9. **Codebook cold-start — closest-match decode without an active pattern instance:** given a set of live alarms on a trail that matches a codebook scenario but for which no active pattern instance exists, the service creates an incident with `matchedCodebookId` set to the matched scenario's identifier, `matchedPatternId` absent (null), and `rootCauseAlarmId` correctly resolved from the codebook scenario's root-cause designation.

10. **Fiber-cut storm — one incident, partial match tolerated:** given a replayed fiber-cut scenario (LOS as root cause + N downstream child alarms) with one alarm dropped from the stream, and a partial-match tolerance parameter from the Knowledge Service that permits N-1 of N matches, the service creates exactly one `CorrelationResultEvent` with `rootCauseAlarmId` matching the LOS alarm and `childAlarmIds[]` containing the surviving downstream alarms.

11. **Deterministic conflict resolution — specificity then confidence:** given two approved patterns that both claim the same alarm set within an instance, where pattern A covers more alarms (higher specificity) than pattern B, the service always selects pattern A as the winner across repeated replays. In a tie on specificity, the pattern with higher `confidence` wins. Conflict-resolution weights are sourced from a Knowledge Service mock (no hard-coded values in the assertion setup).

12. **Codebook tolerance — missing and extra alarms:** given a codebook scenario whose expected signature contains S alarms, when the observed alarm set is missing one alarm from S and contains one spurious alarm not in S, the service selects that scenario as the best closest-match and creates an incident (not a no-match result). Scoring threshold floors are sourced from a Knowledge Service mock.

13. **`CorrelationResultEvent` schema compliance:** every `CorrelationResultEvent` emitted validates against the frozen `CorrelationResultEvent` schema in `libs/event-model`; all required fields (`incidentId`, `rootCauseAlarmId`, `childAlarmIds[]`, `confidence`, `trailId`) are present and non-null.

14. **Required fields populated — pattern match:** for an incident created from a pattern-instance match, `matchedPatternId` is non-null, `confidence` is in [0, 1], and `trailId` matches the `trailId` of the matched `PatternApprovedEvent`.

15. **Required fields populated — codebook match:** for an incident created from a codebook decode (no pattern instance), `matchedCodebookId` is non-null, `matchedPatternId` is null, `confidence` is in [0, 1], and `trailId` matches the codebook scenario's trail tag.

16. **Idempotency — duplicate alarm does not create a duplicate instance or incident:** replaying the same `alarmId` twice while an instance for (T, P) is active results in the alarm being processed exactly once; the instance's alarm set contains the alarm exactly once; exactly one incident is created if the full-match condition is satisfied.

17. **Alarm-reduction ratio computable from stats API:** calling `GET /stats` after replaying a scenario with K raw alarms that collapse to I incidents returns `totalAlarmsProcessed >= K` and `totalIncidentsCreated = I`, making alarm-reduction ratio K/I derivable from the response without any additional engine API.

18. **Incident read API — root cause and children:** calling `GET /incidents/{incidentId}` returns `rootCauseAlarmId` and `childAlarmIds[]` that match the values emitted in the `CorrelationResultEvent` for the same `incidentId`.

19. **Poison message routing — processing continues:** an unparseable message on `alarms.persisted.live` is routed to `alarms.persisted.live.dlq` and the service continues processing the next valid message without halting.

20. **Latest codebook used — newer codebook replaces prior:** given two sequential `codebook.generated` events for the same `snapshotId`/trail scope (V1 then V2), the service uses V2's scenario signatures for all instance evaluations that begin after V2 is loaded. No `codebookId` field is required on `PatternApprovedEvent` to satisfy this criterion.

21. **All match-quality thresholds from Knowledge Service — no hard-coded values:** given a test that replaces every Knowledge Service parameter (partial-match tolerance, scoring threshold floors, conflict-resolution weights) with values different from any default, the engine's matching and conflict-resolution outcomes change to reflect the new parameters with no code change. (Note: session-window duration is per-pattern from `timing`, not a Knowledge Service parameter — this criterion covers the Knowledge-sourced parameters only.)

22. **`AlarmStatusChange` schema compliance:** every `AlarmStatusChange` event emitted on `alarms.status.changed` by the engine validates against the frozen `AlarmStatusChange` schema in `libs/event-model`; all required fields (`alarmId`, `newStatus`, `source`, `changedAt`) are present; `source` equals `correlation-engine`.

## Open questions

1. **Codebook-decode-vs-instance coexistence model (design-stage):** the spec preserves codebook decode as a fallback capability, but the precise interaction between active pattern instances and codebook decode for the same trail and alarm set is underspecified. For example: does codebook decode run only when no active pattern instance exists for an alarm? Can they run concurrently and enter conflict resolution together? Does session expiry of a pattern instance trigger a codebook decode pass on the accumulated alarms? The designer must define this coexistence model in `design.md`; if the chosen model requires a contract change (a new field, topic, or payload), that must be escalated to a human before design proceeds. Tracked as `design-stage` — not a spec blocker.

2. **Correlation-instance data structure (design-stage):** the spec defines the observable behaviour of correlation instances (lifecycle, isolation, per-pattern windows, incremental matching) but does not prescribe the in-memory or persistent data structure that represents an instance. The designer decides whether instances are purely in-memory (with restart-recovery implications), checkpointed to a state store (e.g. RocksDB via Kafka Streams), or held in PostgreSQL. This is a design decision — the spec's correctness requirements (isolation, idempotency, per-pattern timing) constrain but do not dictate the structure. Tracked as `design-stage`.

3. **How the pattern's session-window is expressed in `timing` (design-stage / potential contract change):** `PatternApprovedEvent` carries a `timing` object (`additionalProperties: true` in the current schema). The correlation engine must interpret the per-pattern session-window duration/rules from this field. The precise key names and structure within `timing` that express the session window (e.g. a `sessionWindowMs` key, a gap-based vs. fixed-length window, what "session expires" means for a partially matched sequence) must be agreed between the Pattern Manager and Correlation Engine designers. If the required timing structure is absent from or insufficiently specified in the current `PatternApprovedEvent` schema, that is a contract change requiring `architecture.md` update and human approval before either service proceeds. **Flag to human if `timing` is insufficient.** Tracked as `design-stage` / potential contract change.

4. **Codebook scenario signature fetch endpoint (design-stage):** `CodebookGeneratedEvent` carries only `codebookId`, `snapshotId`, and `scenarioCount` (summary). The engine fetches full per-trail scenario signatures (root-cause type, expected symptom set, trail tags) via the **Codebook Generator's read API**. The exact endpoint shape is published when the Codebook Generator is designed (contract-first); the engine builds its HTTP client and WireMock stub against that published OpenAPI. This is not a spec blocker — the engine's contract (what it consumes and produces) is fully defined; the Codebook Generator OpenAPI is a design-stage dependency. (Carries over from prior OQ-1.)
