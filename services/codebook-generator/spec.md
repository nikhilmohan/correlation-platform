# codebook-generator — Service Spec

## Purpose

The Codebook Generator Service compiles the **codebook** — the model-derived matrix of
candidate root-cause instances to predicted symptom signatures. On receiving a `trails.built`
event (carrying a `snapshotId`), it enumerates every graph instance whose object type appears
in the fault-origin list (sourced exclusively from the Knowledge Service), then runs each
instance's propagation templates forward over the topology graph closure (fetched from the
Topology Service query API) to collect the predicted symptom set for that root-cause scenario,
including the origin's own alarm. Each scenario is tagged with the trail(s) its symptoms
occupy (resolved via the Trail Builder API). The resulting codebook — scenarios, signatures,
trail tags, and the `snapshotId` it was compiled from — is persisted in PostgreSQL under a
freshly minted `codebookId`, and a `codebook.generated` event (a summary-only
`CodebookGeneratedEvent`) is emitted on the `codebook.generated` topic. Full signatures are
served to downstream consumers (Pattern Manager, Correlation Engine) via the service's own
query API. The service operates domain-agnostically: it does not hard-code Core IP specifics;
it applies whatever fault-origin types and propagation templates the Knowledge Service provides.

---

## Scope

**In scope:**

- Consume `trails.built` (a `TrailsBuiltEvent` carrying `snapshotId` + `trailIds[]`) and
  use it as the trigger to compile a new codebook for that snapshot.
- Fetch the **fault-origin list** (the set of graph object types that can be root causes) from
  the Knowledge Service.
- Fetch the **propagation templates** (per-edge-type fault cascade rules) from the Knowledge
  Service.
- Fetch the **graph closure** for each fault-origin instance from the Topology Service query
  API (bounded traversal by edge type).
- **Enumerate** every graph instance whose type is in the fault-origin list.
- For each enumerated instance, **run the propagation templates forward** over the graph
  closure to produce that instance's predicted symptom set (the codebook row), including the
  origin's own alarm type.
- **Tag each scenario** with the trail(s) whose membership includes the scenario's symptoms,
  using the Trail Builder API (`getTrailsForObject` / `getTrail`).
- **Persist** the compiled codebook (scenarios, alarm-type signatures, trail tags, `snapshotId`)
  in PostgreSQL, keyed by a freshly minted `codebookId`.
- **Emit `codebook.generated`** (`CodebookGeneratedEvent`: `snapshotId`, `scenarioCount`,
  `codebookId`) on the `codebook.generated` topic after successful compilation.
- Expose a **query API** (OpenAPI 3.1) that lets Pattern Manager and Correlation Engine read
  codebook scenarios, signatures, and trail tags by `codebookId` or `snapshotId`.
- Publish **OpenAPI 3.1** at `/openapi.json` (plus a human-readable docs UI) and check the
  generated `openapi.json` into `services/codebook-generator/`; this spec is the single source
  of truth for the HTTP surface.
- Maintain **`snapshotId` alignment**: every codebook is tied to the `snapshotId` from which
  it was compiled; a new `snapshotId` always produces a new codebook and a new `codebookId`.
- Deduplicate consumed `trails.built` events on the envelope `eventId`.
- Expose `/health`, `/metrics` (Prometheus), structured JSON logs; config from env.
- Dockerfile and Docker Compose entry.

---

## Out of scope

- **Does not own or query the topology graph directly.** Graph data is obtained exclusively
  through the Topology Service query API. This service never holds AGE credentials or issues
  openCypher queries; all graph access goes through the Topology Service's published API.
- **Does not author propagation templates, fault-origin types, or trail policy.** Those are
  the sole responsibility of the Knowledge Service. This service only reads and applies them.
- **Does not build trails.** Trail construction belongs to the Trail Builder Service; this
  service only reads trail membership for tagging.
- **Does not perform Root Cause Analysis (RCA), pattern lifecycle management, or pattern
  reconciliation.** Those belong to the Pattern Manager Service.
- **Does not perform real-time alarm matching or incident creation.** Real-time correlation
  is the Correlation Engine Service's responsibility.
- **Does not author or own the `managedObjectId` scheme.** The scheme is defined in
  `libs/event-model` and is consumed, not authored, here.
- **Does not subscribe to `topology.changed`.** The trigger to compile a codebook is the
  `trails.built` event (not the raw topology change); `snapshotId` alignment is achieved
  through the `snapshotId` carried in `trails.built`.
- **Does not produce, consume, or own any alarm-domain topics.** This service has no knowledge
  of `alarms.*`, `transactions.clean`, or `patterns.*` topics.
- **Redundancy/protection-aware propagation (FRR, ECMP) is out of MVP scope.** Templates
  assume straight-up propagation; protection-aware extensions are deferred.
- **Does not own the Pattern Store or incident store.** Those are owned by Pattern Manager and
  Correlation Engine respectively.

---

## Tasks (high-level)

1. **Consume `trails.built` and trigger codebook compilation.** On receiving a
   `TrailsBuiltEvent`, extract the `snapshotId` and `trailIds[]`, deduplicate on the
   envelope `eventId`, and initiate a new codebook compilation cycle for that snapshot.
   Route unprocessable events to `trails.built.dlq`.

2. **Fetch fault-origin types and propagation templates from Knowledge Service.** Retrieve the
   current list of fault-origin object types (e.g. Fiber, LineCard, Port, Node) and the set of
   propagation templates (per-edge-type cascade rules) from the Knowledge Service API. These are
   read inputs — this service never authors them.

3. **Enumerate fault-origin instances from the Topology Service.** Query the Topology Service's
   query API to list every graph object whose type appears in the fault-origin list, scoped to
   the `snapshotId` received in the trigger event.

4. **Propagate templates forward and collect predicted symptom sets.** For each enumerated
   fault-origin instance, fetch its graph closure (bounded traversal by relevant edge types)
   from the Topology Service, then apply the propagation templates forward — traversing each
   template edge in cascade — to accumulate the full set of predicted alarm types (symptoms),
   including the origin instance's own alarm type. Each result is one codebook scenario row.

5. **Tag each scenario with its trail(s).** For each scenario, resolve which trail(s) the
   scenario's symptoms occupy by querying the Trail Builder API
   (`getTrailsForObject` / `getTrail`), and attach the resulting `trailIds[]` to the scenario.

6. **Persist the codebook.** Store all scenarios (fault-origin instance, predicted symptom
   signature, trail tags, `snapshotId`) in PostgreSQL under a freshly minted `codebookId`.
   A new `snapshotId` always produces a new codebook and a new `codebookId`; regeneration does
   not overwrite a prior codebook for a different snapshot.

7. **Emit `codebook.generated`.** Publish a `CodebookGeneratedEvent` (`snapshotId`,
   `scenarioCount`, `codebookId`) on the `codebook.generated` topic using the frozen
   `libs/event-model` Python/Pydantic binding. Failed-delivery fallback: `codebook.generated.dlq`.

8. **Serve the codebook query API.** Answer requests from Pattern Manager and Correlation
   Engine: retrieve a codebook's full scenario list and signatures by `codebookId`; retrieve
   scenarios by `snapshotId`; retrieve a single scenario's predicted symptom signature and trail
   tags by scenario identifier. The published OpenAPI 3.1 spec is the surface contract.

---

## Contract

- **Consumes (Kafka):** `trails.built`
  - Payload type: `TrailsBuiltEvent` (frozen binding from `libs/event-model`)
  - Fields: `snapshotId` (string), `trailIds` (array of strings), `trailCount` (integer)
  - Envelope: standard envelope — `eventId` (UUID, idempotency / dedup key), `type`,
    `schemaVersion`, `occurredAt`, `source`, `traceId`, `payload`
  - Unprocessable-message fallback: `trails.built.dlq`

- **Produces (Kafka):** `codebook.generated`
  - Payload type: `CodebookGeneratedEvent` (frozen binding from `libs/event-model`)
  - Fields: `snapshotId` (string), `scenarioCount` (integer), `codebookId` (string)
  - The `codebookId` minted here is the identity referenced as `matchedCodebookId` in
    `CorrelationResultEvent` and as `codebookMatchId` in `PatternDiscoveredEvent` /
    `PatternApprovedEvent` downstream.
  - Note: `CodebookGeneratedEvent` carries a summary only. Full signatures are served via this
    service's query API — not embedded in the event.
  - Failed-delivery fallback: `codebook.generated.dlq`

- **APIs exposed** (publish OpenAPI 3.1 at `/openapi.json` + checked-in `openapi.json`; a
  surface change is a contract change):
  - `GET /codebooks/{codebookId}` — return codebook metadata (snapshotId, scenarioCount,
    codebookId, compiledAt).
  - `GET /codebooks/{codebookId}/scenarios` — return all scenarios in the codebook (fault-origin
    instance identifier, predicted symptom signature as an ordered list of alarm types,
    trail tags).
  - `GET /codebooks/{codebookId}/scenarios/{scenarioId}` — return a single scenario's predicted
    symptom signature and trail tags.
  - `GET /codebooks?snapshotId={snapshotId}` — return the codebook(s) compiled for a given
    `snapshotId` (typically one; a list for extensibility).
  - `/health` and `/metrics` (Prometheus-compatible).

- **APIs consumed from other services** (integration points — built against each producer's
  published OpenAPI, never against source code):
  - **Topology Service — graph query API:** list objects by type (to enumerate fault-origin
    instances); bounded traversal by edge type (to fetch graph closures for propagation).
    Integration point name: `topology-query`.
  - **Knowledge Service — fault-origin list:** retrieve the current versioned list of fault-origin
    object types. Integration point name: `knowledge-fault-origins`.
  - **Knowledge Service — propagation templates:** retrieve the current versioned set of
    propagation templates (per-edge-type cascade rules). Integration point name:
    `knowledge-propagation-templates`.
  - **Trail Builder Service — trail membership:** `getTrailsForObject(managedObjectId)` and
    `getTrail(trailId)` to resolve trail tags for each scenario.
    Integration point name: `trail-builder-trails`.

- **Integration points (mock vs. real):**
  - Each of the four outbound integration points (`topology-query`, `knowledge-fault-origins`,
    `knowledge-propagation-templates`, `trail-builder-trails`) is configured by environment
    variable: a base URL and a `MOCK|REAL` toggle.
  - Unit tests use mocks/stubs generated from the respective producer's published OpenAPI spec
    (e.g. `respx` or `httpx` mock transport for Python) so tests run without live dependencies.
  - Integration tests point each integration point at the real service in Docker Compose. The
    same code runs in both modes — no code change, only config.
  - No integration point URL or mock toggle is hard-coded.

- **Data owned:** PostgreSQL — Codebook Store (schema: `codebook`). Owns: codebooks table
  (codebookId, snapshotId, scenarioCount, compiledAt), scenarios table (scenarioId,
  codebookId, faultOriginObjectId, faultOriginType, predictedSymptoms as ordered alarm-type
  list, trailIds). No other service writes to this schema.

---

## Non-functional

- **Idempotency key:** envelope `eventId` (dedup consumed `trails.built` events); regenerating
  for a new `snapshotId` always mints a fresh `codebookId` and a new codebook; re-processing
  the same `eventId` is a no-op (the existing codebook is preserved and re-emitted if already
  compiled).
- **Config:** all integration-point base URLs, `MOCK|REAL` toggles, database connection
  parameters, Kafka bootstrap servers, consumer group ID, and log level are provided via
  environment variables. No thresholds, no URLs, and no topology-domain specifics are
  hard-coded. Fault-origin types and propagation templates are read from the Knowledge Service
  at runtime.
- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus-compatible
  counters and gauges — at minimum: events consumed, codebooks compiled, scenarios generated,
  errors, and integration-point call latencies), structured JSON logs (level, timestamp, traceId,
  service name, message).
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json` plus a human-readable docs UI;
  the generated `openapi.json` is checked into `services/codebook-generator/openapi.json`.
  Own spec drives contract and unit tests; a change to the HTTP surface is a contract change
  requiring `architecture.md`/spec update and human approval.
- **Error handling:** unprocessable `trails.built` messages → `trails.built.dlq`. Failed
  `codebook.generated` publish → `codebook.generated.dlq`. Integration-point failures
  (Topology, Knowledge, Trail Builder) are retried with backoff; unrecoverable failures are
  logged as structured errors and the event is routed to `trails.built.dlq`.
- **Snapshot alignment:** every codebook is associated with exactly the `snapshotId` carried
  in the triggering `trails.built` event. Consumers (Pattern Manager, Correlation Engine) can
  always retrieve the codebook's `snapshotId` to verify alignment.
- **Permissive licenses only:** all runtime dependencies must be Apache-2.0, BSD, MIT, or
  PostgreSQL-licensed. No GPL/AGPL/BSL/source-available components.

---

## Acceptance criteria

Each criterion maps to a single pytest test.

1. **Fiber-cut signature matches expected cascade.**
   Given a mock Topology Service returning a synthetic FiberSpan instance with RIDES_ON edges to
   an IPLink, ADJACENCY_OVER edges to an IGPAdjacency, TRAVERSES edges to an LSP, and SERVES
   edges to a VPNService, and a mock Knowledge Service returning the standard propagation
   templates (RIDES_ON, ADJACENCY_OVER, TRAVERSES, SERVES), the compiled codebook scenario for
   that FiberSpan instance contains the expected ordered symptom set:
   `[FiberSpan-alarm, LinkDown(IPLink), AdjDown(IGPAdjacency), LSPDown(LSP), ReachabilityLoss(VPNService)]`.

2. **Line-card fault and port fault produce distinguishable signatures.**
   Given mock graph instances for a LineCard (HOSTED_ON edges to two Ports, each with an IPLink)
   and a Port (with one IPLink), the compiled scenarios for each instance have distinct
   signatures: the LineCard scenario contains PortDown alarm types absent from the Port scenario,
   and the Port scenario's LOS / port-layer discriminator alarm is absent from the LineCard
   scenario's top-level signature.

3. **Every scenario is tagged to at least one trail.**
   Given a mock Trail Builder returning at least one `trailId` for any `managedObjectId` queried,
   every scenario in the compiled codebook has a non-empty `trailIds[]`.

4. **Regenerating after a topology change produces a new codebook tied to the new snapshotId.**
   Given two sequential `trails.built` events with distinct `snapshotId` values (`snap-A` and
   `snap-B`), processing both events produces two separate codebook records with distinct
   `codebookId` values; the codebook for `snap-B` carries `snapshotId = snap-B`; and two
   `codebook.generated` events are emitted, each carrying the correct `snapshotId` and
   `codebookId`, validating against the `CodebookGeneratedEvent` Pydantic binding from
   `libs/event-model`.

5. **Duplicate `trails.built` events (same `eventId`) are deduplicated.**
   Given the same `trails.built` event delivered twice (identical `eventId`), the service
   compiles the codebook exactly once and emits `codebook.generated` exactly once.

6. **All outbound integration calls go through config-switchable integration points.**
   When `TOPOLOGY_QUERY_MODE=MOCK`, `KNOWLEDGE_FAULT_ORIGINS_MODE=MOCK`,
   `KNOWLEDGE_PROPAGATION_TEMPLATES_MODE=MOCK`, and `TRAIL_BUILDER_MODE=MOCK` are set, the
   service completes a full compilation cycle using mock responses derived from the respective
   producers' published OpenAPI specs, without making any real HTTP calls. When any integration
   point URL env var is absent or unset, the service refuses to start and logs a structured
   configuration error.

7. **Codebook query API returns scenario signature and trail tags by `codebookId`.**
   After compiling a codebook, `GET /codebooks/{codebookId}/scenarios/{scenarioId}` returns the
   correct predicted symptom list and `trailIds[]` for that scenario, with a `200` response that
   validates against the published `openapi.json` schema.

8. **`codebook.generated` event validates against the frozen event-model binding.**
   The `CodebookGeneratedEvent` payload emitted after compilation is deserializable by the
   `CodebookGeneratedEvent` Pydantic class from `libs/event-model` without validation errors,
   and carries `snapshotId`, `scenarioCount` (matching the number of persisted scenarios), and a
   non-empty `codebookId`.

9. **Unknown `schemaVersion` in a consumed event is rejected.**
   A `trails.built` envelope carrying `schemaVersion >= 2` is rejected (not processed) and
   routed to `trails.built.dlq`; the service does not crash.

10. **Unprocessable `trails.built` messages route to the DLQ.**
    A malformed `trails.built` message (missing required `snapshotId` field) is routed to
    `trails.built.dlq` and not retried indefinitely; the consumer continues processing
    subsequent valid messages.

---

## Open questions

- **OQ-1: Trail Builder API surface for trail tagging.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/28)
  The spec requires calling `getTrailsForObject(managedObjectId)` on the Trail Builder Service.
  The Trail Builder's `spec.md` (on the `spec/trail-builder` branch, not yet merged) describes
  this operation but its OpenAPI surface is not yet frozen. If the Trail Builder API surface
  changes (different endpoint path, response schema, or `managedObjectId` lookup semantics),
  this service's integration point contract and acceptance criterion 3 may need revision.
  Blocked on: Trail Builder spec PR merge and `services/trail-builder/openapi.json` being
  checked in. No contract change is made here — this is flagged for human resolution.

- **OQ-2: Exact alarm-type string identifiers for predicted symptoms.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/30)
  The propagation templates in §5 describe symptom types (e.g. `LinkDown`, `AdjDown`,
  `LSPDown`, `ReachabilityLoss`, `PortDown`) at a conceptual level. The exact string values
  that will appear as `probableCause` or `eventType` in an `AlarmEvent` (and therefore in a
  codebook signature) are not defined in `libs/event-model` or `architecture.md` — they are
  domain knowledge authored in the Knowledge Service. Acceptance criteria 1 and 2 use
  illustrative placeholders. The designer must confirm: are alarm-type strings in codebook
  signatures drawn from the `probableCause` field, `eventType` field, or a composite? Are the
  canonical values for Core IP defined somewhere in the Knowledge Service's seed data or in a
  separate contract? Resolution needed before the designer finalizes the signature schema.

- **OQ-3: Topology Service query API support for "list objects by type" scoped to a snapshotId.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/31)
  Task 3 requires enumerating all graph instances of each fault-origin type for a specific
  `snapshotId`. The Topology Service spec (on `spec/topology`) describes `list objects by type`
  but it is not clear whether the query API accepts `snapshotId` as a filter parameter or
  always operates on the current snapshot. If the Topology Service only serves the latest
  snapshot, codebook compilation for a previous `snapshotId` may not be reproducible. No
  contract change is made here — flagged for human resolution against the Topology Service spec.
