# codebook-generator — Service Spec

## Purpose

The Codebook Generator Service compiles the **codebook** — the model-derived matrix of
candidate root-cause instances to predicted symptom signatures. On receiving a `trails.built`
event (carrying a `snapshotId`), it resolves the **domain** for that snapshot (see OQ-4),
fetches the **domain-scoped** fault-origin list and propagation templates from the Knowledge
Service, enumerates every graph instance whose object type appears in that domain's fault-origin
list (querying the Topology Service with a domain-scoped request), then runs each instance's
propagation templates forward over the topology graph closure to collect the predicted symptom
set for that root-cause scenario, including the origin's own alarm. Each scenario is tagged with
the trail(s) its symptoms occupy (resolved via the Trail Builder API). The resulting codebook —
scenarios, signatures, trail tags, `snapshotId`, and the `domain` it was compiled for — is
persisted in PostgreSQL under a freshly minted `codebookId`, and a `codebook.generated` event
(a summary-only `CodebookGeneratedEvent`) is emitted on the `codebook.generated` topic. Full
signatures are served to downstream consumers (Pattern Manager, Correlation Engine) via the
service's own query API. The service is domain-agnostic: it does not hard-code Core IP
specifics; it applies whatever fault-origin types and propagation templates the Knowledge Service
provides for the snapshot's domain — a non-Core-IP domain's codebook is compiled with its own
fault-origins and templates without code change.

---

## Scope

**In scope:**

- Consume `trails.built` (a `TrailsBuiltEvent` carrying `snapshotId` + `trailIds[]`) and
  use it as the trigger to compile a new codebook for that snapshot.
- Resolve the **domain** associated with the triggering snapshot (see OQ-4 for resolution
  mechanism: carried in the event or looked up from Topology via `snapshotId`).
- Fetch the **domain-scoped fault-origin list** (the set of graph object types that can be root
  causes for that domain) from the Knowledge Service, passing the resolved `domain` as a
  parameter. Different domains have different fault-origin type sets, authored in Knowledge.
- Fetch the **domain-scoped propagation templates** (per-edge-type fault cascade rules for that
  domain) from the Knowledge Service, passing the resolved `domain` as a parameter.
- Fetch the **graph closure** for each fault-origin instance from the Topology Service query
  API (bounded traversal by edge type), scoped to the snapshot's domain.
- **Enumerate** every graph instance whose type is in the domain's fault-origin list, using a
  domain-scoped query to the Topology Service.
- For each enumerated instance, **run the propagation templates forward** over the graph
  closure to produce that instance's predicted symptom set (the codebook row), including the
  origin's own alarm type.
- **Tag each scenario** with the trail(s) whose membership includes the scenario's symptoms,
  using the Trail Builder API (`getTrailsForObject` / `getTrail`), scoped to the snapshot's
  domain-scoped trails.
- **Persist** the compiled codebook (scenarios, alarm-type signatures, trail tags, `snapshotId`,
  `domain`) in PostgreSQL, keyed by a freshly minted `codebookId`. The `domain` column is a
  first-class attribute on every persisted codebook record.
- **Emit `codebook.generated`** (`CodebookGeneratedEvent`: `snapshotId`, `scenarioCount`,
  `codebookId`) on the `codebook.generated` topic after successful compilation.
- Expose a **query API** (OpenAPI 3.1) that lets Pattern Manager and Correlation Engine read
  codebook scenarios, signatures, and trail tags by `codebookId`, `snapshotId`, or `domain`.
  The query API returns the `domain` on every codebook response; queries can be scoped by
  `domain` (e.g. `GET /codebooks?domain={domain}`).
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
- **Does not author propagation templates, fault-origin types, trail policy, or domain
  vocabulary.** Those are the sole responsibility of the Knowledge Service. This service only
  reads and applies them, domain-scoped.
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
- **Cross-domain codebook scenarios are out of MVP scope.** A cross-domain scenario — one whose
  predicted symptom signature spans objects from more than one domain via cross-domain edges —
  is structurally supported (the codebook schema carries `domain` and cross-domain edges may
  exist in the graph), but is not compiled, served, or tested in the MVP. The MVP compiles
  and serves single-domain codebooks only (Core IP domain). Cross-domain expansion is deferred
  and does not require a code change to enable — only Knowledge data (cross-domain relation
  vocabulary) and a future spec update.

---

## Tasks (high-level)

1. **Consume `trails.built` and trigger codebook compilation.** On receiving a
   `TrailsBuiltEvent`, extract the `snapshotId` and `trailIds[]`, deduplicate on the
   envelope `eventId`, and initiate a new codebook compilation cycle for that snapshot.
   Route unprocessable events to `trails.built.dlq`.

2. **Resolve the domain for the snapshot.** Determine the `domain` value associated with the
   triggering `snapshotId` — either from the event payload (if available; see OQ-4) or by
   querying the Topology Service's snapshot metadata API. The resolved `domain` is the
   parameter passed to all downstream Knowledge and Topology calls for this compilation cycle.

3. **Fetch domain-scoped fault-origin types and propagation templates from Knowledge Service.**
   Retrieve the domain's fault-origin object type list and propagation templates (per-edge-type
   cascade rules) from the Knowledge Service API, passing the resolved `domain` as a required
   parameter. These are read inputs — this service never authors them. A non-Core-IP domain's
   fault-origin list and templates are fetched identically; no code change is needed per domain.

4. **Enumerate domain-scoped fault-origin instances from the Topology Service.** Query the
   Topology Service's query API to list every graph object whose type appears in the domain's
   fault-origin list, scoped to the `snapshotId` and `domain` from the trigger event. The
   Topology Service is domain-isolated; the enumeration query is domain-scoped.

5. **Propagate templates forward and collect predicted symptom sets.** For each enumerated
   fault-origin instance, fetch its graph closure (bounded traversal by relevant edge types)
   from the Topology Service (domain-scoped), then apply the domain's propagation templates
   forward — traversing each template edge in cascade — to accumulate the full set of predicted
   alarm types (symptoms), including the origin instance's own alarm type. Each result is one
   codebook scenario row.

6. **Tag each scenario with its trail(s).** For each scenario, resolve which trail(s) the
   scenario's symptoms occupy by querying the Trail Builder API
   (`getTrailsForObject` / `getTrail`) — the trails for this snapshot are domain-scoped — and
   attach the resulting `trailIds[]` to the scenario.

7. **Persist the codebook with domain.** Store all scenarios (fault-origin instance, predicted
   symptom signature, trail tags, `snapshotId`, `domain`) in PostgreSQL under a freshly minted
   `codebookId`. The `domain` is a non-nullable column on the codebook record. A new `snapshotId`
   always produces a new codebook and a new `codebookId`; regeneration does not overwrite a
   prior codebook for a different snapshot.

8. **Emit `codebook.generated`.** Publish a `CodebookGeneratedEvent` (`snapshotId`,
   `scenarioCount`, `codebookId`) on the `codebook.generated` topic using the frozen
   `libs/event-model` Python/Pydantic binding. Failed-delivery fallback: `codebook.generated.dlq`.
   See OQ-5 regarding whether `domain` should be added to `CodebookGeneratedEvent`.

9. **Serve the domain-scoped codebook query API.** Answer requests from Pattern Manager and
   Correlation Engine: retrieve a codebook's full scenario list and signatures by `codebookId`;
   retrieve scenarios by `snapshotId`; retrieve a single scenario's predicted symptom signature
   and trail tags by scenario identifier; list codebooks filtered by `domain`. Every response
   includes the codebook's `domain`. The published OpenAPI 3.1 spec is the surface contract.

---

## Phase applicability

The codebook-generator's primary work is in **P1** (topology onboarding), where it compiles the
domain-scoped codebook from the newly built trails. In **P2** and **P3** it drives no work of
its own; it serves its query API as a dependency for the Pattern Manager (reconciliation) and
the Correlation Engine (matching) respectively.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Compile domain-scoped codebook: on `trails.built`, resolve domain, fetch domain-scoped fault-origin list + propagation templates (Knowledge), enumerate domain-scoped fault-origin instances (Topology), run templates forward over graph closure, tag scenarios to trails, persist codebook (with `domain`), mint `codebookId`, emit `codebook.generated` | **Active** | In: `trails.built` (+ Topology query API domain-scoped, Knowledge fault-origin list API with domain param, Knowledge propagation templates API with domain param, Trail Builder API reads); Out: `codebook.generated` |
| P2 — Pattern learning | Serves the domain-scoped codebook (scenario signatures + trail tags) to the Pattern Manager, which uses it to: (a) **reconcile** mined patterns — confirm those matching a scenario and flag patterns with no model explanation; (b) **supply authoritative RCA** — where a mined pattern overlaps a scenario, the scenario's root cause overrides the Manager's ordering-based RCA; (c) feed **explainability** (codebook-overlap metadata). The codebook is the model-based check on data-mined patterns; it drives no work of its own. | **Passive** | In: codebook query API requests from Pattern Manager (may include `domain` filter); Out: codebook query API responses (scenario signatures, trail tags, RCA per scenario, `domain`). No topic output. |
| P3 — Real-time correlation | Serves the domain-scoped codebook to the Correlation Engine as the **model-based correlation/RCA source**, co-equal with approved patterns. The engine runs a **closest-match decode** of the live symptom set against trail-scoped scenarios to pick a root-cause scenario — enabling RCA even with no learned pattern. The winning scenario's id is recorded as `matchedCodebookId` on the incident. The codebook drives no work of its own. | **Passive** | In: codebook query API requests from Correlation Engine (may include `domain` filter; codebook also delivered via `codebook.generated`); Out: codebook query API responses (scenario signatures + RCA, `domain`, scoped by trail/snapshot). No topic output. |

---

## Contract

- **Consumes (Kafka):** `trails.built` _(primary trigger)_
  - Payload type: `TrailsBuiltEvent` (frozen binding from `libs/event-model`)
  - Fields: `snapshotId` (string), `trailIds` (array of strings), `trailCount` (integer)
  - Envelope: standard envelope — `eventId` (UUID, idempotency / dedup key), `type`,
    `schemaVersion`, `occurredAt`, `source`, `traceId`, `payload`
  - Note: `TrailsBuiltEvent` does not currently carry a `domain` field. Domain resolution uses
    the `snapshotId` to query Topology snapshot metadata, unless the event payload is extended
    (see OQ-4).
  - Unprocessable-message fallback: `trails.built.dlq`

- **Optional Kafka input (design-stage decision):** `knowledge.updated`
  - Payload type: `KnowledgeUpdatedEvent` (frozen binding from `libs/event-model`). Fields:
    `recordType` (string), `recordId` (string, optional), `version` (string), `domain` (string).
  - The `domain` field on `KnowledgeUpdatedEvent` (already present in the frozen binding) enables
    domain-scoped cache invalidation: when a `knowledge.updated` event arrives for a specific
    `domain`, the service can selectively invalidate or refresh cached fault-origin types and
    propagation templates for that domain only.
  - Whether the service subscribes to `knowledge.updated` as a cache-invalidation or eager
    refresh trigger is a **design decision**. The topic and payload are available and typed;
    subscribing is not required by this spec. If the designer subscribes, dedup on envelope
    `eventId` applies and unprocessable messages route to `knowledge.updated.dlq`. No new
    contract change is needed to use this topic.

- **Produces (Kafka):** `codebook.generated`
  - Payload type: `CodebookGeneratedEvent` (frozen binding from `libs/event-model`)
  - Fields: `snapshotId` (string), `scenarioCount` (integer), `codebookId` (string)
  - The `codebookId` minted here is the identity referenced as `matchedCodebookId` in
    `CorrelationResultEvent` and as `codebookMatchId` in `PatternDiscoveredEvent` /
    `PatternApprovedEvent` downstream.
  - Note: `CodebookGeneratedEvent` does not currently carry a `domain` field. Downstream
    consumers that need the codebook's domain can derive it from `snapshotId` via the
    codebook query API or Topology snapshot metadata. If a `domain` field is required on the
    event itself, that is a contract change — see OQ-5.
  - Note: `CodebookGeneratedEvent` carries a summary only. Full signatures are served via this
    service's query API — not embedded in the event.
  - Failed-delivery fallback: `codebook.generated.dlq`

- **APIs exposed** (publish OpenAPI 3.1 at `/openapi.json` + checked-in `openapi.json`; a
  surface change is a contract change):
  - `GET /codebooks/{codebookId}` — return codebook metadata (snapshotId, scenarioCount,
    codebookId, compiledAt, **domain**).
  - `GET /codebooks/{codebookId}/scenarios` — return all scenarios in the codebook (fault-origin
    instance identifier, predicted symptom signature as an ordered list of alarm types,
    trail tags).
  - `GET /codebooks/{codebookId}/scenarios/{scenarioId}` — return a single scenario's predicted
    symptom signature and trail tags.
  - `GET /codebooks?snapshotId={snapshotId}` — return the codebook(s) compiled for a given
    `snapshotId` (typically one; a list for extensibility).
  - `GET /codebooks?domain={domain}` — return all codebooks compiled for the given domain,
    ordered by `compiledAt` descending. Supports domain-scoped lookup by Pattern Manager and
    Correlation Engine.
  - `/health` and `/metrics` (Prometheus-compatible).

- **APIs consumed from other services** (integration points — built against each producer's
  published OpenAPI, never against source code):
  - **Topology Service — graph query API:** list objects by type scoped to `snapshotId` and
    `domain` (to enumerate domain-scoped fault-origin instances); bounded traversal by edge
    type (to fetch graph closures for propagation); snapshot metadata lookup (to resolve
    `domain` from `snapshotId` — see OQ-4).
    Integration point name: `topology-query`.
  - **Knowledge Service — domain-scoped fault-origin list:** retrieve the versioned list of
    fault-origin object types for the specified `domain`. Integration point name:
    `knowledge-fault-origins`.
  - **Knowledge Service — domain-scoped propagation templates:** retrieve the versioned set of
    propagation templates (per-edge-type cascade rules) for the specified `domain`. Integration
    point name: `knowledge-propagation-templates`.
  - **Trail Builder Service — trail membership:** `getTrailsForObject(managedObjectId)` and
    `getTrail(trailId)` to resolve trail tags for each scenario (trails are domain-scoped by
    the Trail Builder).
    Integration point name: `trail-builder-trails`.

- **Integration points (mock vs. real):**
  - Each of the four outbound integration points (`topology-query`, `knowledge-fault-origins`,
    `knowledge-propagation-templates`, `trail-builder-trails`) is configured by environment
    variable: a base URL and a `MOCK|REAL` toggle.
  - Unit tests use mocks/stubs generated from the respective producer's published OpenAPI spec
    (e.g. `respx` or `httpx` mock transport for Python) so tests run without live dependencies.
    Domain-parameterized mock responses (different fault-origin lists / templates per domain)
    are used to verify domain-scoped behaviour without live services.
  - Integration tests point each integration point at the real service in Docker Compose. The
    same code runs in both modes — no code change, only config.
  - No integration point URL or mock toggle is hard-coded.

- **Data owned:** PostgreSQL — Codebook Store (schema: `codebook`). Owns: codebooks table
  (codebookId, snapshotId, scenarioCount, compiledAt, **domain** [non-nullable]), scenarios
  table (scenarioId, codebookId, faultOriginObjectId, faultOriginType, predictedSymptoms as
  ordered alarm-type list, trailIds). No other service writes to this schema.

---

## Non-functional

- **Idempotency key:** envelope `eventId` (dedup consumed `trails.built` events); regenerating
  for a new `snapshotId` always mints a fresh `codebookId` and a new codebook; re-processing
  the same `eventId` is a no-op (the existing codebook is preserved and re-emitted if already
  compiled).
- **Config:** all integration-point base URLs, `MOCK|REAL` toggles, database connection
  parameters, Kafka bootstrap servers, consumer group ID, and log level are provided via
  environment variables. No thresholds, no URLs, no domain names, and no topology-domain
  specifics are hard-coded. Fault-origin types and propagation templates are read from the
  Knowledge Service at runtime, parameterized by domain. A new domain requires no code change —
  only Knowledge data.
- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus-compatible
  counters and gauges — at minimum: events consumed, codebooks compiled, scenarios generated,
  errors, and integration-point call latencies — labelled by `domain` where applicable),
  structured JSON logs (level, timestamp, traceId, service name, message, domain).
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
  always retrieve the codebook's `snapshotId` and `domain` to verify alignment.
- **Permissive licenses only:** all runtime dependencies must be Apache-2.0, BSD, MIT, or
  PostgreSQL-licensed. No GPL/AGPL/BSL/source-available components.

---

## Acceptance criteria

Each criterion maps to a single pytest test.

1. **Fiber-cut signature matches expected cascade.**
   Given a mock Topology Service returning a synthetic FiberSpan instance with RIDES_ON edges to
   an IPLink, ADJACENCY_OVER edges to an IGPAdjacency, TRAVERSES edges to an LSP, and SERVES
   edges to a VPNService, and a mock Knowledge Service returning the standard Core IP propagation
   templates (RIDES_ON, ADJACENCY_OVER, TRAVERSES, SERVES) for domain `core-ip`, the compiled
   codebook scenario for that FiberSpan instance contains the expected ordered symptom set:
   `[FiberSpan-alarm, LinkDown(IPLink), AdjDown(IGPAdjacency), LSPDown(LSP), ReachabilityLoss(VPNService)]`.
   _(Note: alarm-type identifier strings are illustrative placeholders; replaced with the shared
   alarm-type vocabulary confirmed at design — see OQ-2.)_

2. **Line-card fault and port fault produce distinguishable signatures.**
   Given mock graph instances for a LineCard (HOSTED_ON edges to two Ports, each with an IPLink)
   and a Port (with one IPLink), the compiled scenarios for each instance have distinct
   signatures: the LineCard scenario contains PortDown alarm types absent from the Port scenario,
   and the Port scenario's LOS / port-layer discriminator alarm is absent from the LineCard
   scenario's top-level signature.
   _(Note: alarm-type identifier strings are illustrative placeholders; replaced with the shared
   alarm-type vocabulary confirmed at design — see OQ-2.)_

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

11. **Compiled codebook record carries the snapshot's domain.**
    After processing a `trails.built` event for snapshot `snap-X` whose resolved domain is
    `core-ip`, the persisted codebook record has `domain = "core-ip"` (non-null). The
    `GET /codebooks/{codebookId}` response includes `"domain": "core-ip"` and validates against
    the published `openapi.json` schema.

12. **Fault-origin list and propagation templates are fetched with the snapshot's domain parameter.**
    When compiling a codebook for domain `core-ip`, the Knowledge Service integration point is
    called with `domain=core-ip` on each request (verified via mock assertion that the outbound
    request carries the domain parameter). No domain-specific data is hard-coded in the service.

13. **Domain-scoped enumeration query is passed to the Topology Service.**
    When enumerating fault-origin instances for domain `core-ip` and snapshot `snap-X`, the
    Topology Service integration point is called with both `snapshotId=snap-X` and
    `domain=core-ip` as query parameters (verified via mock assertion).

14. **Domain-scoped codebook query API filters by domain.**
    Given two persisted codebooks — one for domain `core-ip` (codebookId `CB-1`) and one for
    domain `transport` (codebookId `CB-2`) — `GET /codebooks?domain=core-ip` returns exactly
    the `core-ip` codebook and not the `transport` codebook; the response validates against the
    published `openapi.json` schema.

15. **A different domain's codebook compiles using that domain's fault-origins and templates without code change.**
    Given mock Knowledge Service responses returning a distinct fault-origin list and propagation
    templates for domain `transport` (different object types and cascade rules from `core-ip`),
    and mock Topology Service responses for domain `transport`, the service compiles a complete
    codebook for `transport` using those domain-specific inputs — without any code change or
    additional configuration beyond pointing to the same Knowledge and Topology service endpoints.
    The persisted record carries `domain = "transport"`.

---

## Open questions

All remaining open questions are **design-stage items** unless marked as spec blockers. They
are resolved when the relevant collaborating service is designed and publishes its OpenAPI spec;
codebook-generator's designer builds their mock/client against that published spec.

- **OQ-1 [DESIGN-STAGE]: Trail Builder API surface for trail tagging.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/28)
  The spec requires calling `getTrailsForObject(managedObjectId)` on the Trail Builder Service.
  The Trail Builder's published OpenAPI is not yet frozen (its spec PR is open). This resolves
  when the Trail Builder spec is merged and `services/trail-builder/openapi.json` is checked in;
  at that point, codebook-generator's designer builds the `trail-builder-trails` integration
  point mock and client against that spec. The requirement — every scenario is tagged to at
  least one trail via a Trail Builder API call — is firm; the endpoint path and response schema
  are confirmed at design. Not a spec blocker.

- **OQ-2 [DESIGN-STAGE]: Shared alarm-type vocabulary for codebook signatures.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/30)
  The codebook signature is built from **alarm-type identifiers drawn from a shared alarm-type
  vocabulary defined at design**, coordinated with the Knowledge Service (which authors the
  propagation templates that name these effects). The propagation-template effects referenced in
  §5 (e.g. `LinkDown`, `AdjDown`, `LSPDown`, `PortDown`, `LOS`, `ReachabilityLoss`) are
  illustrative; the canonical identifier strings — and whether they map to `eventType`,
  `probableCause`, or a separate field in `AlarmEvent` — are a **shared design-time decision**
  between codebook-generator and knowledge (the template author). Acceptance criteria 1 and 2
  use these strings as illustrative placeholders; the designer replaces them with the vocabulary
  confirmed at design. This does not require a new topic, payload, or field in `libs/event-model`
  now; if a new field is ultimately needed that is a contract change at design requiring human
  approval. Not a spec blocker.

- **OQ-3 [DESIGN-STAGE]: Topology Service query API support for snapshotId-scoped and domain-scoped object enumeration.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/31)
  Task 4 requires enumerating all graph instances of each fault-origin type for a specific
  `snapshotId` and `domain`. The codebook-generator's requirement — enumerate fault-origin
  instances scoped to both the snapshot and the domain — must be accounted for in the Topology
  Service's design. This resolves when the Topology Service is designed and its `list objects
  by type` endpoint (with `snapshotId` and `domain` filter parameters) is confirmed in the
  Topology Service's published OpenAPI; codebook-generator's designer then builds its
  `topology-query` client and mock against that spec. If the Topology API does not support
  domain scoping, the Topology designer must note the constraint and codebook-generator's
  designer adapts accordingly. Not a spec blocker.

- **OQ-4 [SPEC-STAGE, NEEDS HUMAN RESOLUTION]: Domain resolution mechanism for TrailsBuiltEvent.**
  The current `TrailsBuiltEvent` payload (frozen in `libs/event-model`) carries `snapshotId`,
  `trailIds`, and `trailCount` — it does **not** carry a `domain` field. This service needs the
  `domain` to parameterize Knowledge and Topology calls. Two options exist:
  (a) Look up the domain by querying the Topology Service's snapshot metadata API using the
  `snapshotId` — no contract change required, but adds a synchronous dependency per compile
  cycle; or
  (b) Add `domain` to `TrailsBuiltEvent` — this is a **contract change** requiring an
  `architecture.md`/`libs/event-model` update and human approval before this spec/design
  proceeds.
  Option (a) is preferred to avoid a contract change, but requires the Topology snapshot
  metadata API to expose the domain (see OQ-3). A human must confirm which option to take.
  **This is a spec-stage question; the domain resolution mechanism must be decided before
  design begins.** Linked issue to be filed labeled `question` + `service:codebook-generator`.

- **OQ-5 [DESIGN-STAGE]: Whether `domain` should be added to `CodebookGeneratedEvent`.**
  The current `CodebookGeneratedEvent` (frozen in `libs/event-model`) carries `snapshotId`,
  `scenarioCount`, and `codebookId` — no `domain` field. Downstream consumers (Pattern Manager,
  Correlation Engine) that need the domain can derive it from `snapshotId` via the codebook
  query API or Topology metadata. If a `domain` field on the event is required for
  consumer-side routing or filtering without an API lookup, that is a **contract change**
  requiring an `architecture.md`/`libs/event-model` update and human approval. The preferred
  stance is that `domain` is derivable from `snapshotId` (via the codebook query API or
  Topology) and that no contract change is needed; however, if a downstream consumer's designer
  identifies a blocking need, this becomes a contract change request. Not a spec blocker unless
  a downstream consumer designer raises the need.
