# pattern-manager — Service Spec

## Purpose

The Pattern Manager is the single owner of the full pattern domain. It consumes raw mined
sequences from the Pattern Miner, enriches them with root-cause analysis (RCA), codebook
reconciliation, and explainability metadata, and persists them in the Pattern Store
(PostgreSQL) with initial lifecycle state `draft`. It drives the human-approval workflow: a
web-ui operator reviews discovered patterns and signals a lightweight approval intent (patternId
+ decision + reviewer/notes) to the Pattern Manager via its API; the Pattern Manager owns the
lifecycle transition and is the sole emitter of the frozen `PatternApprovedEvent` downstream for
the Correlation Engine. It exposes a read API for the web-ui's pattern-review/XAI module and
serves approved patterns to the Correlation Engine. It contains no ML — mining is wholly
owned by the Pattern Miner; the Pattern Manager's job is to turn raw Miner output into
governed, reviewable, downstream-ready patterns.

## Scope

**In scope:**
- Consuming `patterns.mined` events (`PatternMinedEvent`: ordered sequence, support/confidence/lift, `trailId`, timing, provenance) from the Pattern Miner.
- RCA: for each mined pattern, map alarm types in the sequence to their graph objects via the Topology Service API; designate the alarm type whose object has no upstream dependency within the group (lowest in the dependency graph), corroborated by earliest timestamp in supporting instances, as `rootCauseAlarmType`.
- Codebook RCA override: where a mined pattern's sequence overlaps a codebook scenario (via Codebook Generator API), replace the graph-ordering-derived RCA with the scenario's designated root cause and record the `codebookMatchId`.
- Codebook reconciliation: confirm matches between mined patterns and codebook scenarios, merge complementary appendages, and flag patterns with no codebook match (no model explanation).
- Explainability metadata assembly: compile instance count, support/confidence/lift, timing (median inter-arrival, timeframe), codebook overlap reference, and supporting example instances (mined sequence occurrences and their provenance from the Pattern Miner) per pattern.
- Persisting enriched patterns to the Pattern Store (PostgreSQL) with lifecycle `draft`; assigning a stable `patternId`.
- Emitting `patterns.discovered` (one `PatternDiscoveredEvent` per newly persisted draft pattern).
- Exposing a pattern read API (OpenAPI 3.1) for the web-ui's pattern-review/XAI module. The API serves the data the UI needs to **visualize discovered patterns and support the operator's approve/reject decision**:
  - **Discovered (draft) patterns for review:** list draft patterns with the full explainability/XAI metadata (support/confidence/lift, `rootCauseAlarmType` (RCA), timing stats, codebook overlap / `codebookMatchId`, supporting example instances, lifecycle) — i.e. everything needed to render an intuitive review-and-decide view — and retrieve a single pattern by `patternId`.
  - **Active (approved) patterns for operator visibility:** list the currently-active (approved) patterns with their details (sequence, RCA, metrics, lifecycle), filterable by lifecycle, so the UI can show which patterns are live in correlation.
  - Note: the UI *renders* these (Cytoscape/charts, per §6.11); the Pattern Manager only *serves* the structured data. Real-time pattern-match counts / live correlation stats are produced by the Correlation Engine (`correlation.results`), not here.
- Accepting a lightweight approval-intent request (patternId + decision approve/reject + reviewer + notes) via the Pattern Manager API; owning the lifecycle transition from `draft` to `approved` in the Pattern Store; recording the transition with a timestamp.
- Accepting **operator edits of a draft pattern** (placeholder, to be enhanced) via the pattern-edit API — e.g. marking sequence alarms `optional` — and persisting them onto the draft pattern in the Pattern Store before approval. Edit metadata stays internal (Pattern Store + read API); it is not added to the frozen `PatternApprovedEvent`.
- Emitting `patterns.approved` downstream (one `PatternApprovedEvent`) after each approval transition, for the Correlation Engine to consume. The Pattern Manager is the sole producer of `PatternApprovedEvent`.
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

## Tasks (high-level)

1. Consume `patterns.mined`: validate each `PatternMinedEvent` against the `libs/event-model` Java binding, deduplicate on `eventId`, and extract the raw sequence, support/confidence/lift, `trailId`, timing, and provenance for downstream enrichment steps.

2. Perform RCA: call the Topology Service API to resolve each alarm type in the sequence to its graph object and dependency position; designate the alarm type whose object has no upstream dependency within the group (lowest in the dependency graph), corroborated by earliest timestamp in supporting instances, as `rootCauseAlarmType`.

3. Apply codebook RCA override: call the Codebook Generator API to test whether the mined sequence overlaps a known codebook scenario; if it does, replace the graph-derived `rootCauseAlarmType` with the scenario's designated root cause and record the `codebookMatchId`.

4. Reconcile against the codebook: confirm match quality, merge complementary appendages where applicable, and flag patterns with no codebook match (no model explanation available).

5. Assemble explainability metadata: compile instance count, support/confidence/lift, timing statistics (median inter-arrival, timeframe), codebook overlap reference (`codebookMatchId` if present), and a set of supporting example instances (mined sequence occurrences / example transactions from the Pattern Miner's provenance); attach to the pattern record.

6. Persist to the Pattern Store: write the enriched pattern (sequence, `rootCauseAlarmType`, metrics, explainability metadata, provenance, `codebookMatchId`) to PostgreSQL with lifecycle `draft`; assign a stable `patternId`.

7. Emit `patterns.discovered`: publish one `PatternDiscoveredEvent` per newly persisted draft pattern, carrying `patternId`, sequence, `rootCauseAlarmType`, support/confidence/lift, timing, `codebookMatchId` (if any), and `lifecycle = draft`.

8. Serve the pattern read API (OpenAPI 3.1): expose endpoints for the web-ui to (a) list **discovered (draft) patterns** with full explainability/XAI metadata for the review-and-approve/reject view, (b) retrieve a single pattern by `patternId`, (c) list **active (approved) patterns** with their details for operator visibility — all filterable by lifecycle — and serve approved patterns to the Correlation Engine.

9. Process human approval intent: receive a lightweight approval-intent request (patternId + decision approve/reject + reviewer + notes) via the Pattern Manager API; validate that the named pattern exists in `draft` state; transition lifecycle to `approved` in the Pattern Store; record the transition with a timestamp.

10. Process operator edits (placeholder, to be enhanced): receive a pattern-edit request for a `draft` pattern via the pattern-edit API — for the MVP placeholder, per-alarm `optional` flags on the sequence (plus reviewer/notes) — validate the pattern is in `draft`, persist the edits onto the draft pattern record in the Pattern Store, and return the updated record. The edit metadata is internal (Pattern Store + read API); it is not added to `PatternApprovedEvent`, and its effect on Correlation matching is a post-MVP/design-stage enhancement.

11. Emit `patterns.approved` downstream: publish one `PatternApprovedEvent` per approval transition, carrying `patternId`, sequence, `rootCauseAlarmType`, support/confidence/lift, timing, `codebookMatchId` (if any), and `lifecycle = approved`, for the Correlation Engine. The Pattern Manager is the sole producer of this event.

12. Support deprecation: accept a deprecation action (via the pattern management API) for a pattern in `draft` or `approved` state; transition lifecycle to `deprecated` in the Pattern Store; record the transition with a timestamp.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no patterns exist at this phase. | Idle | — |
| P2 — Pattern learning | Core work phase: consumes mined patterns, performs RCA + codebook reconciliation + XAI, persists draft patterns, drives the human-approval workflow via API; concurrently serves the pattern-review API to the web-ui (passive serving runs alongside active enrichment). | Active | In: `patterns.mined` (Kafka, from Pattern Miner); approval-intent received via API (from web-ui); Out: `patterns.discovered` (Kafka), `patterns.approved` (Kafka, to Correlation Engine); Serves: pattern read API (web-ui, Correlation Engine); Calls: Topology Service API (RCA), Codebook Generator API (reconcile + RCA override), Knowledge Service API (params) |
| P3 — Real-time correlation | Serves the approved Pattern Store to the Correlation Engine and the web-ui on demand; drives no work of its own — all patterns were approved in P2. | Passive | Serves: pattern read API (`GET /patterns?lifecycle=approved` to Correlation Engine; web-ui reads patterns); no Kafka topics produced or consumed in P3 |

## Contract

- **Consumes (Kafka):**
  - `patterns.mined` — `PatternMinedEvent` (Pattern Miner output: `sequence[]`, `support`, `confidence`, `lift`, `trailId`, `timing`, `provenance{sourceWindowId, snapshotId, codebookVersion}`)

- **Produces (Kafka):**
  - `patterns.discovered` — `PatternDiscoveredEvent` (one per draft pattern persisted: `patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `codebookMatchId?`, `lifecycle = draft`)
  - `patterns.approved` — `PatternApprovedEvent` (one per approval transition: `patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `codebookMatchId?`, `lifecycle = approved`). The Pattern Manager is the sole producer; the web-ui signals approval only via the Pattern Manager API (see APIs exposed below), never by publishing to this topic directly. Architecture.md's "web-ui produces patterns.approved (via API)" means the web-ui drives approval through the Pattern Manager API, and the Pattern Manager emits the event.

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; `openapi.json` checked in to `services/pattern-manager/openapi.json`):
  - `GET /patterns` — list all patterns; supports filter by `lifecycle` (`draft`, `approved`, `deprecated`); returns per pattern: `patternId`, `sequence[]`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `codebookMatchId`, `instanceCount`, `supportingInstances[]`, `lifecycle`. Pagination and sort parameters are a design-stage detail (see Open questions).
  - `GET /patterns/{patternId}` — retrieve a single pattern by `patternId` with full explainability metadata including `supportingInstances[]`.
  - `POST /patterns/{patternId}/approve` — accept a lightweight approval-intent body (`decision: approve|reject`, `reviewer`, `notes`); transition the pattern lifecycle to `approved` (or record the rejection); emit `PatternApprovedEvent`; return the updated pattern record.
  - `PATCH /patterns/{patternId}` — **operator edit of a draft pattern (placeholder; to be enhanced).** Accept an edit body that adjusts the pattern before approval — for the MVP placeholder, **per-alarm flags on the sequence** (e.g. marking a sequence element `optional`), plus reviewer/notes. Allowed only while the pattern is in `draft` (reject otherwise). Persist the edits onto the draft pattern record in the Pattern Store and return the updated record. The edit metadata (e.g. `optional` markers) is **internal to the Pattern Store + read API for now — NOT added to the frozen `PatternApprovedEvent`**; how an edited/optional-alarm pattern is represented to and matched by the Correlation Engine is a **documented post-MVP / design-stage enhancement** (the Correlation Engine already tolerates missing alarms via its partial-match tolerance). Editable fields beyond the optional-alarm placeholder are a design-stage detail.
  - `POST /patterns/{patternId}/deprecate` — transition a pattern to `deprecated` lifecycle state; records the transition timestamp; returns the updated pattern record.

- **APIs/data consumed from other services** (each built against the producer's published OpenAPI spec — never against source code):
  - **Topology Service API** — resolve alarm type to graph object and retrieve dependency position within a trail for RCA (object lookup + bounded dependency traversal).
  - **Codebook Generator API** — retrieve codebook scenarios for reconciliation and RCA override; look up a scenario by sequence overlap; retrieve `codebookMatchId` for matched scenarios.
  - **Knowledge Service API** — retrieve RCA/policy parameters (e.g., dependency-ordering weights, reconciliation thresholds) that govern enrichment behaviour; no hard-coded values.

- **Integration points (mock vs. real):**
  - **Topology Service API** — config-switchable; mock (generated from Topology Service's published OpenAPI, e.g. WireMock/MockWebServer) for unit tests; real Topology Service for integration.
  - **Codebook Generator API** — config-switchable; mock (generated from Codebook Generator's published OpenAPI) for unit tests; real Codebook Generator for integration.
  - **Knowledge Service API** — config-switchable; mock (generated from Knowledge Service's published OpenAPI) for unit tests; real Knowledge Service for integration.
  - All base URLs and mock/real toggle resolved from environment configuration — not hard-coded.

- **Data owned:** PostgreSQL Pattern Store — the sole writer of all pattern records, lifecycle state, explainability metadata, and lifecycle-transition audit log. No other service writes to this store.

## Non-functional

- **Idempotency key:** `eventId` (UUID from the event envelope) for deduplication of consumed `patterns.mined` events; `patternId` for Pattern Store upsert idempotency.
- **Config:** all outbound integration base URLs (Topology, Codebook Generator, Knowledge), mock/real environment toggle, Kafka bootstrap servers, consumer group IDs, and enrichment/RCA policy parameters sourced from environment variables or the Knowledge Service — no hard-coded URLs, thresholds, or credentials.
- **Observability:** `/health` (liveness and readiness), `/metrics` (Prometheus), structured JSON logs (include `traceId`, `patternId`, lifecycle-transition events at INFO; errors at ERROR).
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; the checked-in `services/pattern-manager/openapi.json` is the authoritative HTTP surface; the service's own OpenAPI spec drives contract/unit tests; a surface change is a contract change requiring `docs/architecture.md` update and human approval.
- **Error handling:** messages failing validation or deserialization on `patterns.mined` are routed to `patterns.mined.dlq`; errors are never dropped silently.
- **Lifecycle auditability:** each transition (draft → approved, draft → deprecated, approved → deprecated) is recorded in the Pattern Store with a timestamp.
- **Test framework:** JUnit 5 (unit and contract tests); Testcontainers for integration tests — per CLAUDE.md Java cohort standard; do not substitute.

## Acceptance criteria

1. Given a `PatternMinedEvent` carrying a fiber-cut alarm sequence (e.g., `[LOS, LinkDown, AdjDown, LSPDown]`) where the Topology API stub returns that `LOS` maps to a `FiberSpan` object with no upstream dependency in the group and the earliest timestamp among all alarms in the sequence, the service assigns `rootCauseAlarmType = LOS` to the persisted pattern record.

2. Given a `PatternMinedEvent` whose sequence overlaps a codebook scenario that designates `LineCardFault` as the root cause (Codebook Generator API stub returns this scenario with a non-null scenario ID), the service overrides the graph-ordering-derived RCA, persists `rootCauseAlarmType = LineCardFault`, and sets `codebookMatchId` to the matched scenario's identifier.

3. Given a `PatternMinedEvent` with high `support` and low `lift` (a spurious co-occurrence), the service persists the pattern with `codebookMatchId` absent (flagged as no model explanation), and the persisted `lift` value in the explainability metadata is the low value from the event — enabling the UI to surface the lift signal during review.

4. Given any consumed and successfully processed `PatternMinedEvent`, the persisted pattern record carries all required explainability fields: `instanceCount` (integer > 0), `support`, `confidence`, `lift`, `timing` (object with at minimum median inter-arrival and timeframe), `codebookMatchId` (null if no codebook match), and `supportingInstances` (a list of example occurrence references sourced from the Pattern Miner's provenance; may be empty if provenance carries no occurrences).

5. Given a `PatternMinedEvent` processed without an approval action, the persisted pattern has `lifecycle = draft` and is returned by `GET /patterns?lifecycle=draft`.

6. Given a `PatternDiscoveredEvent` emitted by the service, deserializing it with the `libs/event-model` Java binding succeeds and all required fields — `patternId`, `sequence`, `rootCauseAlarmType`, `support`, `confidence`, `lift`, `timing`, `lifecycle` — are present and non-null; `lifecycle` equals `draft`.

7. Given a `POST /patterns/{patternId}/approve` request with `decision = approve` for a pattern currently in `lifecycle = draft`, the service transitions that pattern's lifecycle to `approved` in the Pattern Store and emits exactly one `PatternApprovedEvent` on the `patterns.approved` Kafka topic within the same processing action.

8. Given a `PatternApprovedEvent` emitted by the service, deserializing it with the `libs/event-model` Java binding succeeds, `lifecycle = approved`, and all required fields are present and non-null.

9. Given a `POST /patterns/{patternId}/deprecate` request for a pattern currently in `approved` state, the service transitions lifecycle to `deprecated`, records a non-null transition timestamp, and a subsequent `GET /patterns?lifecycle=approved` does not include that `patternId`.

10. Given two identical `PatternMinedEvent` messages with the same `eventId` delivered to the consumer (simulating Kafka at-least-once redelivery), the Pattern Store contains exactly one pattern record for that event after both messages are processed.

11. Given a malformed `PatternMinedEvent` message (e.g., `sequence` field absent), the service routes that message to `patterns.mined.dlq` and continues processing the next message without restarting or dropping it silently.

12. A `GET /patterns` response validates against the published OpenAPI 3.1 schema; a `GET /patterns/{patternId}` response for an existing pattern validates against the same schema; a `GET /patterns/{patternId}` for a non-existent `patternId` returns HTTP 404.

13. A `GET /patterns?lifecycle=approved` response contains only patterns with `lifecycle = approved`; no `draft` or `deprecated` entries appear in the result set.

14. Given a `PATCH /patterns/{patternId}` edit request marking a sequence alarm `optional` for a pattern in `lifecycle = draft`, the service persists the edit onto the draft pattern (a subsequent `GET /patterns/{patternId}` reflects the `optional` marker) without changing lifecycle; the same edit request for a pattern not in `draft` is rejected (HTTP 409/422).

## Open questions

- OQ-2 (`service:pattern-manager`, `design-stage`): Pagination and sort parameters for `GET /patterns` are a design-stage API detail, not a spec blocker. The designer finalises pagination/sort (cursor- or offset-based, parameter names, response envelope fields) in the service's OpenAPI 3.1 spec. Tracked as issue #46 (relabeled `design-stage`).
