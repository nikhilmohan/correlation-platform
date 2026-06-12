# pattern-manager — Service Spec

## Purpose

The Pattern Manager is the single owner of the full pattern domain. It consumes raw mined
sequences from the Pattern Miner, enriches them with root-cause analysis (RCA), structural
validation, codebook reconciliation, explainability metadata, and **derives the per-pattern
session-window rule** (`sessionWindow`), then persists everything in the Pattern Store
(PostgreSQL) with initial lifecycle state `draft`. It drives the human-approval workflow: a
web-ui operator reviews discovered patterns and signals a lightweight approval intent
(patternId + decision + reviewer/notes) to the Pattern Manager via its API; the Pattern
Manager owns the lifecycle transition and is the sole emitter of the frozen
`PatternApprovedEvent` downstream for the Correlation Engine. It exposes a read API for the
web-ui's pattern-review/XAI module and serves approved patterns to the Correlation Engine.
It contains no ML — mining is wholly owned by the Pattern Miner; the Pattern Manager's job
is to turn raw Miner output into governed, reviewable, downstream-ready patterns that include
an explicit session-window directive the Correlation Engine uses to govern each correlation
instance's lifetime.

## Scope

**In scope:**
- Consuming `patterns.mined` events (`PatternMinedEvent`: ordered sequence, support/confidence/lift, `trailId`, timing, provenance) from the Pattern Miner.
- RCA: for each mined pattern, map alarm types in the sequence to their graph objects via the Topology Service API; designate the alarm type whose object has no upstream dependency within the group (lowest in the dependency graph), corroborated by earliest timestamp in supporting instances, as `rootCauseAlarmType`.
- **Structural validation:** for each mined pattern (after RCA, before persistence), verify that the alarm-type objects resolved during RCA form a connected dependency path in the topology — i.e. the objects are related through dependency edges (a connected sub-path in the dependency graph), not topologically disjoint. Uses the Topology Service API (the same integration point as RCA: object resolution + bounded dependency traversal). Patterns whose objects are NOT dependency-connected are persisted but FLAGGED (`structurallyValidated: false` with a reason string); patterns whose objects ARE connected are marked `structurallyValidated: true`. The structural-validation status and reason are internal to the Pattern Store and the read API (not added to the frozen `PatternDiscoveredEvent` or `PatternApprovedEvent`). The flag is surfaced in the web-ui pattern-review/XAI metadata so an operator reviewing a flagged pattern sees "this pattern's objects are not dependency-connected (possible statistical artifact)". Validation parameters (e.g. connectivity strictness, maximum traversal hops to consider two objects connected) are sourced from the Knowledge Service — no hard-coded thresholds.
- Codebook RCA override: where a mined pattern's sequence overlaps a codebook scenario (via Codebook Generator API), replace the graph-ordering-derived RCA with the scenario's designated root cause and record the `codebookMatchId`.
- Codebook reconciliation: confirm matches between mined patterns and codebook scenarios, merge complementary appendages, and flag patterns with no codebook match (no model explanation).
- Explainability metadata assembly: compile instance count, support/confidence/lift, timing (median inter-arrival, timeframe), codebook overlap reference, structural-validation status (`structurallyValidated` flag + reason), and supporting example instances (mined sequence occurrences and their provenance from the Pattern Miner) per pattern.
- **Session-window derivation:** for each mined pattern, derive the per-pattern session-window rule (`sessionWindow`) from the mined `timing` statistics carried on `PatternMinedEvent` (the inter-arrival / timeframe statistics). The derived `sessionWindow` — an object with `windowMs` (integer, milliseconds) and `type` (`gap-based` or `fixed`) — is an explicit operational directive, distinct from the descriptive `timing` statistics. The exact derivation formula (how `windowMs` is computed from the mined timing) and the rule for choosing `type` are design-stage decisions (see Open questions). The derivation must be deterministic: the same timing statistics must always produce the same `sessionWindow`. No Knowledge-policy input for derivation — kept data-driven. No hard-coded magic numbers without justification.
- Persisting enriched patterns to the Pattern Store (PostgreSQL) with lifecycle `draft`; assigning a stable `patternId`. The Pattern Store record persists the derived `sessionWindow` alongside all other pattern fields.
- Emitting `patterns.discovered` (one `PatternDiscoveredEvent` per newly persisted draft pattern), including the derived `sessionWindow` field as required by the merged contract.
- Exposing a pattern read API (OpenAPI 3.1) for the web-ui's pattern-review/XAI module. The API serves the data the UI needs to **visualize discovered patterns and support the operator's approve/reject decision**:
  - **Discovered (draft) patterns for review:** list draft patterns with the full explainability/XAI metadata (support/confidence/lift, `rootCauseAlarmType` (RCA), timing stats, codebook overlap / `codebookMatchId`, structural-validation status (`structurallyValidated` flag + reason), `sessionWindow`, supporting example instances, lifecycle) — i.e. everything needed to render an intuitive review-and-decide view — and retrieve a single pattern by `patternId`.
  - **Active (approved) patterns for operator visibility:** list the currently-active (approved) patterns with their details (sequence, RCA, metrics, `sessionWindow`, lifecycle), filterable by lifecycle, so the UI can show which patterns are live in correlation.
  - Note: the UI *renders* these (Cytoscape/charts, per §6.11); the Pattern Manager only *serves* the structured data. Real-time pattern-match counts / live correlation stats are produced by the Correlation Engine (`correlation.results`), not here.
- Accepting a lightweight approval-intent request (patternId + decision approve/reject + reviewer + notes) via the Pattern Manager API; owning the lifecycle transition from `draft` to `approved` in the Pattern Store; recording the transition with a timestamp.
- Accepting **operator edits of a draft pattern** (placeholder, to be enhanced) via the pattern-edit API — e.g. marking sequence alarms `optional` — and persisting them onto the draft pattern in the Pattern Store before approval. Edit metadata stays internal (Pattern Store + read API); it is not added to the frozen `PatternApprovedEvent`. The derived `sessionWindow` is read-only in MVP (see Out of scope); operator adjustment of the session window is a design-stage possibility only (see Open questions).
- Emitting `patterns.approved` downstream (one `PatternApprovedEvent`) after each approval transition, carrying the `sessionWindow` as required by the merged contract, for the Correlation Engine to consume. The Pattern Manager is the sole producer of `PatternApprovedEvent`.
- Supporting deprecation: transitioning a pattern in `draft` or `approved` state to `deprecated` via the pattern management API; recording the transition with a timestamp.
- Serving approved patterns via the read API to the Correlation Engine (Pattern Store read at startup and on refresh).
- Deduplicating consumed events on `eventId` (idempotency under Kafka at-least-once delivery).
- Routing poison/unparseable messages to `patterns.mined.dlq`.
- Making all outbound integration points (Topology API, Codebook Generator API, Knowledge API) config-switchable: mock (from collaborator's published OpenAPI) for unit tests, real for integration.
- Publishing `/health`, `/metrics` (Prometheus), and structured JSON logs.
- Publishing `openapi.json` (OpenAPI 3.1) checked in to `services/pattern-manager/openapi.json`.
- Auditable lifecycle transitions: each state change is recorded with a timestamp.

## Out of scope

- Mining: discovering patterns from alarm transactions — owned by Pattern Miner (single ML owner).
- Compiling or owning the codebook — owned by Codebook Generator Service; Pattern Manager reads the codebook via its published API only and never writes codebook state.
- Owning or querying the topology graph directly — owned by Topology Service; Pattern Manager reads via Topology Service API only and never touches Apache AGE.
- Real-time alarm correlation — owned by Correlation Engine; Pattern Manager produces approved patterns for the Correlation Engine, it does not perform correlation.
- Enriching or filtering alarms — owned by Enrichment and Noise Filter services.
- Incident / correlation-group storage — owned by Correlation Engine.
- Multi-tenancy, automated retraining, closed-loop feedback execution — deferred from MVP.
- Redundancy/protection-aware propagation (FRR, ECMP) — deferred from MVP.
- Schema registry — replaced by the `libs/event-model` shared library.
- Emitting `patterns.approved` from the web-ui — the web-ui posts only a lightweight approval-intent to the Pattern Manager API; it does not publish `PatternApprovedEvent` directly.
- Automatically rejecting patterns that fail structural validation — the MVP behaviour is to flag-and-persist (human-in-the-loop approval already exists); hard auto-reject is a post-MVP option (see Open questions).
- Producing the descriptive `timing` statistics on `PatternMinedEvent` — those remain the Pattern Miner's output and pass through unchanged. The Pattern Manager derives `sessionWindow` from `timing`; it does not modify or re-emit `timing`.
- Operator-authored override of the derived `sessionWindow` in MVP — the session window is derived and read-only for MVP. Whether an operator should be able to adjust it via the edit API is a design-stage possibility noted in Open questions; it is not an MVP capability.

## Tasks (high-level)

1. Consume `patterns.mined`: validate each `PatternMinedEvent` against the `libs/event-model` Java binding, deduplicate on `eventId`, and extract the raw sequence, support/confidence/lift, `trailId`, timing, and provenance for downstream enrichment steps.

2. Perform RCA: call the Topology Service API to resolve each alarm type in the sequence to its graph object and dependency position; designate the alarm type whose object has no upstream dependency within the group (lowest in the dependency graph), corroborated by earliest timestamp in supporting instances, as `rootCauseAlarmType`.

3. Perform structural validation: using the graph objects already resolved during RCA (reusing the same Topology Service API call results), verify that those objects form a connected dependency path — i.e. the objects are reachable from one another through dependency edges (a connected sub-path in the dependency graph). Retrieve validation parameters (e.g. connectivity strictness, max traversal hops) from the Knowledge Service. Record `structurallyValidated: true` if the objects are connected; record `structurallyValidated: false` with a reason string if they are not. This step is distinct from RCA: RCA designates the root cause; structural validation checks the topological coherence of the entire pattern. A failed structural validation does not stop persistence — the pattern is persisted with the flag set accordingly (see Scope). The structural-validation status is part of the explainability metadata (Task 6) served by the read API; it is NOT added to the frozen Kafka events.

4. Apply codebook RCA override: call the Codebook Generator API to test whether the mined sequence overlaps a known codebook scenario; if it does, replace the graph-derived `rootCauseAlarmType` with the scenario's designated root cause and record the `codebookMatchId`.

5. Reconcile against the codebook: confirm match quality, merge complementary appendages where applicable, and flag patterns with no codebook match (no model explanation available).

6. Assemble explainability metadata: compile instance count, support/confidence/lift, timing statistics (median inter-arrival, timeframe), codebook overlap reference (`codebookMatchId` if present), structural-validation status (`structurallyValidated` boolean + `structuralValidationReason` string), and a set of supporting example instances (mined sequence occurrences / example transactions from the Pattern Miner's provenance); attach to the pattern record.

7. Derive session window: from the mined `timing` statistics carried on `PatternMinedEvent` (the inter-arrival and timeframe statistics), compute a `sessionWindow` — `windowMs` (integer, > 0, milliseconds) and `type` (`gap-based` or `fixed`) — that serves as the per-pattern session-window rule for the Correlation Engine. The derivation is deterministic and data-driven (no Knowledge-policy input; no hard-coded magic numbers beyond what is documented and justified). The exact formula and type-selection rule are a design-stage decision (see Open questions). The derived value is attached to the pattern record alongside the original timing statistics, which remain unchanged.

8. Persist to the Pattern Store: write the enriched pattern (sequence, `rootCauseAlarmType`, metrics, explainability metadata including structural-validation status, `sessionWindow`, provenance, `codebookMatchId`) to PostgreSQL with lifecycle `draft`; assign a stable `patternId`.

9. Emit `patterns.discovered`: publish one `PatternDiscoveredEvent` per newly persisted draft pattern, carrying `patternId`, sequence, `rootCauseAlarmType`, support/confidence/lift, timing, `sessionWindow` ({windowMs, type}), `codebookMatchId` (if any), and `lifecycle = draft`. The `sessionWindow` field is required by the merged contract and must be present and valid on every emitted event.

10. Serve the pattern read API (OpenAPI 3.1): expose endpoints for the web-ui to (a) list **discovered (draft) patterns** with full explainability/XAI metadata for the review-and-approve/reject view (including the structural-validation flag and reason, and `sessionWindow`), (b) retrieve a single pattern by `patternId` including `sessionWindow` in the response and XAI metadata, (c) list **active (approved) patterns** with their details (including `sessionWindow`) for operator visibility — all filterable by lifecycle — and serve approved patterns to the Correlation Engine.

11. Process human approval intent: receive a lightweight approval-intent request (patternId + decision approve/reject + reviewer + notes) via the Pattern Manager API; validate that the named pattern exists in `draft` state; transition lifecycle to `approved` in the Pattern Store; record the transition with a timestamp.

12. Process operator edits (placeholder, to be enhanced): receive a pattern-edit request for a `draft` pattern via the pattern-edit API — for the MVP placeholder, per-alarm `optional` flags on the sequence (plus reviewer/notes) — validate the pattern is in `draft`, persist the edits onto the draft pattern record in the Pattern Store, and return the updated record. The edit metadata is internal (Pattern Store + read API); it is not added to `PatternApprovedEvent`, and its effect on Correlation matching is a post-MVP/design-stage enhancement.

13. Emit `patterns.approved` downstream: publish one `PatternApprovedEvent` per approval transition, carrying `patternId`, sequence, `rootCauseAlarmType`, support/confidence/lift, timing, `sessionWindow` ({windowMs, type}), `codebookMatchId` (if any), and `lifecycle = approved`, for the Correlation Engine. The `sessionWindow` field is required by the merged contract and must be present and valid on every emitted event. The Pattern Manager is the sole producer of this event.

14. Support deprecation: accept a deprecation action (via the pattern management API) for a pattern in `draft` or `approved` state; transition lifecycle to `deprecated` in the Pattern Store; record the transition with a timestamp.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no patterns exist at this phase. | Idle | — |
| P2 — Pattern learning | Core work phase: consumes mined patterns, performs RCA + structural validation + codebook reconciliation + session-window derivation + XAI, persists draft patterns, drives the human-approval workflow via API; concurrently serves the pattern-review API to the web-ui (passive serving runs alongside active enrichment). | Active | In: `patterns.mined` (Kafka, from Pattern Miner); approval-intent received via API (from web-ui); Out: `patterns.discovered` (Kafka, with sessionWindow), `patterns.approved` (Kafka, with sessionWindow, to Correlation Engine); Serves: pattern read API (web-ui, Correlation Engine); Calls: Topology Service API (RCA + structural validation, same client), Codebook Generator API (reconcile + RCA override), Knowledge Service API (params including structural-validation params) |
| P3 — Real-time correlation | Serves the approved Pattern Store to the Correlation Engine and the web-ui on demand; drives no work of its own — all patterns were approved in P2. | Passive | Serves: pattern read API (`GET /patterns?lifecycle=approved` to Correlation Engine; web-ui reads patterns); no Kafka topics produced or consumed in P3 |

## Contract

- **Consumes (Kafka):**
  - `patterns.mined` — `PatternMinedEvent` (Pattern Miner output: `sequence[]`, `support`, `confidence`, `lift`, `trailId`, `timing`, `provenance{sourceWindowId, snapshotId, codebookVersion}`). The `timing` field carries the inter-arrival / timeframe statistics the Pattern Manager uses as the sole input for session-window derivation.

- **Produces (Kafka):**
  - `patterns.discovered` — `PatternDiscoveredEvent` (one per draft pattern persisted: `patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `sessionWindow` ({`windowMs`: integer ms, `type`: `gap-based`|`fixed`}), `codebookMatchId?`, `lifecycle = draft`). `sessionWindow` is **required** on every emitted event per the merged `libs/event-model` contract — the Pattern Manager derives and populates it from the mined timing.
  - `patterns.approved` — `PatternApprovedEvent` (one per approval transition: `patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `sessionWindow` ({`windowMs`: integer ms, `type`: `gap-based`|`fixed`}), `codebookMatchId?`, `lifecycle = approved`). `sessionWindow` is **required** on every emitted event per the merged `libs/event-model` contract — carried from the Pattern Store's persisted value for the pattern. The Pattern Manager is the sole producer; the web-ui signals approval only via the Pattern Manager API (see APIs exposed below), never by publishing to this topic directly. Architecture.md's "web-ui produces patterns.approved (via API)" means the web-ui drives approval through the Pattern Manager API, and the Pattern Manager emits the event.
  - Note: the structural-validation flag (`structurallyValidated` + reason) is internal to the Pattern Store and the read API. It is NOT carried on `PatternDiscoveredEvent` or `PatternApprovedEvent` — these frozen event schemas are unchanged beyond the addition of `sessionWindow` from the merged contract. If a future requirement demands the flag on these events, that is a contract change requiring `docs/architecture.md` update and human approval.

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; `openapi.json` checked in to `services/pattern-manager/openapi.json`):
  - `GET /patterns` — list all patterns; supports filter by `lifecycle` (`draft`, `approved`, `deprecated`); returns per pattern: `patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `sessionWindow` ({`windowMs`, `type`}), `codebookMatchId`, `instanceCount`, `supportingInstances[]`, `structurallyValidated`, `structuralValidationReason`, `lifecycle`. Pagination and sort parameters are a design-stage detail (see Open questions).
  - `GET /patterns/{patternId}` — retrieve a single pattern by `patternId` with full explainability metadata including `supportingInstances[]`, `structurallyValidated`, `structuralValidationReason`, and `sessionWindow` ({`windowMs`, `type`}) in the response and XAI metadata.
  - `POST /patterns/{patternId}/approve` — accept a lightweight approval-intent body (`decision: approve|reject`, `reviewer`, `notes`); transition the pattern lifecycle to `approved` (or record the rejection); emit `PatternApprovedEvent` (with `sessionWindow`); return the updated pattern record.
  - `PATCH /patterns/{patternId}` — **operator edit of a draft pattern (placeholder; to be enhanced).** Accept an edit body that adjusts the pattern before approval — for the MVP placeholder, **per-alarm flags on the sequence** (e.g. marking a sequence element `optional`), plus reviewer/notes. Allowed only while the pattern is in `draft` (reject otherwise). Persist the edits onto the draft pattern record in the Pattern Store and return the updated record. The edit metadata (e.g. `optional` markers) is **internal to the Pattern Store + read API for now — NOT added to the frozen `PatternApprovedEvent`**; how an edited/optional-alarm pattern is represented to and matched by the Correlation Engine is a **documented post-MVP / design-stage enhancement** (the Correlation Engine already tolerates missing alarms via its partial-match tolerance). The `sessionWindow` is derived and read-only in MVP — it is not an editable field via this endpoint. Editable fields beyond the optional-alarm placeholder are a design-stage detail.
  - `POST /patterns/{patternId}/deprecate` — transition a pattern to `deprecated` lifecycle state; records the transition timestamp; returns the updated pattern record.

- **APIs/data consumed from other services** (each built against the producer's published OpenAPI spec — never against source code):
  - **Topology Service API** — resolve alarm type to graph object and retrieve dependency position within a trail for RCA (object lookup + bounded dependency traversal); also used for structural validation — verifying that the resolved objects form a connected dependency path — reusing the same client and integration point as RCA. No direct access to Apache AGE.
  - **Codebook Generator API** — retrieve codebook scenarios for reconciliation and RCA override; look up a scenario by sequence overlap; retrieve `codebookMatchId` for matched scenarios.
  - **Knowledge Service API** — retrieve RCA/policy parameters (e.g., dependency-ordering weights, reconciliation thresholds) and structural-validation parameters (e.g. connectivity strictness, maximum traversal hops) that govern enrichment behaviour; no hard-coded values. Note: session-window derivation does NOT call the Knowledge Service — it is derived purely from mined timing.

- **Integration points (mock vs. real):**
  - **Topology Service API** — config-switchable; mock (generated from Topology Service's published OpenAPI, e.g. WireMock/MockWebServer) for unit tests; real Topology Service for integration.
  - **Codebook Generator API** — config-switchable; mock (generated from Codebook Generator's published OpenAPI) for unit tests; real Codebook Generator for integration.
  - **Knowledge Service API** — config-switchable; mock (generated from Knowledge Service's published OpenAPI) for unit tests; real Knowledge Service for integration.
  - All base URLs and mock/real toggle resolved from environment configuration — not hard-coded.

- **Data owned:** PostgreSQL Pattern Store — the sole writer of all pattern records, lifecycle state, explainability metadata (including structural-validation status and derived `sessionWindow`), and lifecycle-transition audit log. No other service writes to this store. `sessionWindow` is persisted on the pattern record and returned by the read API; it is derived at ingest time and is read-only in MVP.

## Non-functional

- **Idempotency key:** `eventId` (UUID from the event envelope) for deduplication of consumed `patterns.mined` events; `patternId` for Pattern Store upsert idempotency.
- **Config:** all outbound integration base URLs (Topology, Codebook Generator, Knowledge), mock/real environment toggle, Kafka bootstrap servers, consumer group IDs, enrichment/RCA policy parameters, and structural-validation parameters (connectivity strictness, max traversal hops, flag-vs-reject policy) sourced from environment variables or the Knowledge Service — no hard-coded URLs, thresholds, or credentials. Session-window derivation must not embed undocumented magic numbers.
- **Observability:** `/health` (liveness and readiness), `/metrics` (Prometheus), structured JSON logs (include `traceId`, `patternId`, lifecycle-transition events at INFO; structural-validation outcomes at INFO; session-window derivation result at DEBUG; errors at ERROR).
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; the checked-in `services/pattern-manager/openapi.json` is the authoritative HTTP surface; the service's own OpenAPI spec drives contract/unit tests; a surface change is a contract change requiring `docs/architecture.md` update and human approval.
- **Error handling:** messages failing validation or deserialization on `patterns.mined` are routed to `patterns.mined.dlq`; errors are never dropped silently.
- **Lifecycle auditability:** each transition (draft → approved, draft → deprecated, approved → deprecated) is recorded in the Pattern Store with a timestamp.
- **Test framework:** JUnit 5 (unit and contract tests); Testcontainers for integration tests — per CLAUDE.md Java cohort standard; do not substitute.

## Acceptance criteria

1. Given a `PatternMinedEvent` carrying a fiber-cut alarm sequence (e.g., `[LOS, LinkDown, AdjDown, LSPDown]`) where the Topology API stub returns that `LOS` maps to a `FiberSpan` object with no upstream dependency in the group and the earliest timestamp among all alarms in the sequence, the service assigns `rootCauseAlarmType = LOS` to the persisted pattern record.

2. Given a `PatternMinedEvent` whose sequence overlaps a codebook scenario that designates `LineCardFault` as the root cause (Codebook Generator API stub returns this scenario with a non-null scenario ID), the service overrides the graph-ordering-derived RCA, persists `rootCauseAlarmType = LineCardFault`, and sets `codebookMatchId` to the matched scenario's identifier.

3. Given a `PatternMinedEvent` with high `support` and low `lift` (a spurious co-occurrence), the service persists the pattern with `codebookMatchId` absent (flagged as no model explanation), and the persisted `lift` value in the explainability metadata is the low value from the event — enabling the UI to surface the lift signal during review.

4. Given any consumed and successfully processed `PatternMinedEvent`, the persisted pattern record carries all required explainability fields: `instanceCount` (integer > 0), `support`, `confidence`, `lift`, `timing` (object with at minimum median inter-arrival and timeframe), `codebookMatchId` (null if no codebook match), `structurallyValidated` (boolean), `structuralValidationReason` (string, non-null when `structurallyValidated` is false), and `supportingInstances` (a list of example occurrence references sourced from the Pattern Miner's provenance; may be empty if provenance carries no occurrences).

5. Given a `PatternMinedEvent` processed without an approval action, the persisted pattern has `lifecycle = draft` and is returned by `GET /patterns?lifecycle=draft`.

6. Given a `PatternDiscoveredEvent` emitted by the service, deserializing it with the `libs/event-model` Java binding succeeds and all required fields — `patternId`, `sequence`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `sessionWindow`, `lifecycle` — are present and non-null; `lifecycle` equals `draft`.

7. Given a `POST /patterns/{patternId}/approve` request with `decision = approve` for a pattern currently in `lifecycle = draft`, the service transitions that pattern's lifecycle to `approved` in the Pattern Store and emits exactly one `PatternApprovedEvent` on the `patterns.approved` Kafka topic within the same processing action.

8. Given a `PatternApprovedEvent` emitted by the service, deserializing it with the `libs/event-model` Java binding succeeds, `lifecycle = approved`, and all required fields are present and non-null.

9. Given a `POST /patterns/{patternId}/deprecate` request for a pattern currently in `approved` state, the service transitions lifecycle to `deprecated`, records a non-null transition timestamp, and a subsequent `GET /patterns?lifecycle=approved` does not include that `patternId`.

10. Given two identical `PatternMinedEvent` messages with the same `eventId` delivered to the consumer (simulating Kafka at-least-once redelivery), the Pattern Store contains exactly one pattern record for that event after both messages are processed.

11. Given a malformed `PatternMinedEvent` message (e.g., `sequence` field absent), the service routes that message to `patterns.mined.dlq` and continues processing the next message without restarting or dropping it silently.

12. A `GET /patterns` response validates against the published OpenAPI 3.1 schema; a `GET /patterns/{patternId}` response for an existing pattern validates against the same schema; a `GET /patterns/{patternId}` for a non-existent `patternId` returns HTTP 404.

13. A `GET /patterns?lifecycle=approved` response contains only patterns with `lifecycle = approved`; no `draft` or `deprecated` entries appear in the result set.

14. Given a `PATCH /patterns/{patternId}` edit request marking a sequence alarm `optional` for a pattern in `lifecycle = draft`, the service persists the edit onto the draft pattern (a subsequent `GET /patterns/{patternId}` reflects the `optional` marker) without changing lifecycle; the same edit request for a pattern not in `draft` is rejected (HTTP 409/422).

15. Given a `PatternMinedEvent` whose alarm-type objects, when resolved via the Topology Service API mock, form a connected dependency path (each object reachable from another through dependency edges within the configured max-hops), the service sets `structurallyValidated = true` on the persisted pattern record and persists the pattern normally with lifecycle `draft`.

16. Given a `PatternMinedEvent` whose alarm-type objects, when resolved via the Topology Service API mock, are topologically disjoint (no dependency path connects them within the configured max-hops), the service persists the pattern with `structurallyValidated = false` and a non-null `structuralValidationReason`, the pattern's lifecycle is `draft`, and a subsequent `GET /patterns/{patternId}` returns the explainability metadata with `structurallyValidated = false` and the reason string present.

17. Given a fixed mined pattern and a fixed Topology mock response, changing the structural-validation parameters retrieved from the Knowledge Service mock (e.g. reducing max-hops so that the previously-connected objects are now considered unreachable) changes the validation outcome from `structurallyValidated = true` to `structurallyValidated = false` — confirming no validation threshold is hard-coded in the service.

18. Given a `PatternMinedEvent` with known timing statistics, the Pattern Manager derives a `sessionWindow` where `windowMs` is a positive integer (> 0) and `type` is one of `gap-based` or `fixed`; processing the identical event a second time (same timing statistics) produces the identical `sessionWindow` — confirming the derivation is deterministic.

19. Given any successfully processed `PatternMinedEvent`, the emitted `PatternDiscoveredEvent` carries a `sessionWindow` field with `windowMs` (integer > 0) and `type` (`gap-based` or `fixed`), and the event validates without error against the frozen `PatternDiscoveredEvent` JSON Schema from `libs/event-model`.

20. Given an approved pattern, the emitted `PatternApprovedEvent` carries a `sessionWindow` field that is equal to the `sessionWindow` persisted on the Pattern Store record for that `patternId`, with `windowMs` (integer > 0) and `type` (`gap-based` or `fixed`), and the event validates without error against the frozen `PatternApprovedEvent` JSON Schema from `libs/event-model`.

21. Given a `GET /patterns/{patternId}` request for an existing pattern, the response includes the `sessionWindow` field ({`windowMs`, `type`}) in the pattern record and in the XAI metadata, and the response validates against the published OpenAPI 3.1 schema.

## Open questions

- OQ-2 (`service:pattern-manager`, `design-stage`): Pagination and sort parameters for `GET /patterns` are a design-stage API detail, not a spec blocker. The designer finalises pagination/sort (cursor- or offset-based, parameter names, response envelope fields) in the service's OpenAPI 3.1 spec. Tracked as issue #46 (relabeled `design-stage`).

- OQ-3 (`service:pattern-manager`, `design-stage`): Exact connectivity criterion and traversal parameters for structural validation. The spec requires that alarm-type objects form a "connected dependency path" and that max-hops/strictness come from the Knowledge Service, but the precise graph query (e.g. whether the path must be strictly directed, whether shared intermediate nodes count, the exact Topology API call shape for bounded dependency traversal) is a design-stage detail. The designer must define this in `design.md` and confirm the Topology Service's published OpenAPI supports the required traversal operation; if the Topology API does not currently expose a sufficient endpoint, that is a contract change requiring human approval before design proceeds.

- OQ-4 (`service:pattern-manager`, `design-stage`): Reject-vs-flag policy finalization. The MVP default is flag-and-persist (`structurallyValidated: false`) to preserve operator oversight and avoid discarding possibly-real patterns. A hard auto-reject (discarding a pattern that fails structural validation, without persisting it) is a more aggressive alternative deferred to post-MVP. If the product owner decides to change the MVP policy to auto-reject before the design is complete, that decision must be made by a human and recorded here before the designer encodes it — it changes the observable behaviour of the service and the acceptance criteria.

- OQ-5 (`service:pattern-manager`, `design-stage`): Session-window derivation formula and type-selection rule. The spec requires that `windowMs` and `type` be derived deterministically from the mined `timing` statistics (inter-arrival / timeframe data on `PatternMinedEvent`) with no Knowledge-policy input. The designer must specify: (a) the exact formula for computing `windowMs` from the timing statistics (e.g. a multiple of the observed median inter-arrival span, or a function of the observed timeframe, with a documented margin); (b) the rule for choosing `type` (`gap-based` vs `fixed`) from the timing statistics (e.g. based on inter-arrival variance, pattern duration, or a fixed default); (c) which specific sub-fields of `timing` are used and their expected units (milliseconds assumed — confirm with the Pattern Miner's `timing` schema); (d) whether any configurable scaling factor is needed (must come from environment config, not hard-coded). If the mined `timing` statistics are insufficient to derive a meaningful `windowMs` (e.g. a pattern with zero observed timeframe), the designer must specify a safe fallback. This is entirely a design-stage decision. Tracked as a `design-stage` + `service:pattern-manager` issue.

- OQ-6 (`service:pattern-manager`, `design-stage`): Operator editability of `sessionWindow` in a future release. In MVP, `sessionWindow` is derived and read-only. If a post-MVP requirement arises to let an operator override the derived session window via `PATCH /patterns/{patternId}`, the designer should assess whether this is a spec-level change (new editable field, new contract on `PatternApprovedEvent`, new API surface) requiring human approval, or purely an internal enhancement. No action required before MVP design.
