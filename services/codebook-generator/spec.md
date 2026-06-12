# codebook-generator — Service Spec

## Purpose

The Codebook Generator Service compiles the **codebook** — the model-derived matrix of
candidate root-cause instances to predicted symptom signatures. On receiving a `trails.built`
event (carrying a `snapshotId` and `domain`), it fetches the **domain-scoped** fault-origin
list and propagation templates from the Knowledge Service, enumerates every graph instance
whose object type appears in that domain's fault-origin list (querying the Topology Service
with a domain-scoped request), then runs each instance's propagation templates forward over the
topology graph closure to collect the predicted symptom set for that root-cause scenario,
including the origin's own alarm. Each scenario is tagged with the trail(s) its symptoms occupy
(resolved via the Trail Builder API). The resulting codebook — scenarios, signatures, trail
tags, `snapshotId`, `domain`, and a freshly minted `codebookId` — is persisted in PostgreSQL,
and a `codebook.generated` event (a summary-only `CodebookGeneratedEvent` carrying `snapshotId`,
`scenarioCount`, `codebookId`, and `domain`) is emitted on the `codebook.generated` topic. Full
signatures are served to downstream consumers (Pattern Manager, Correlation Engine) via the
service's own query API. The service is domain-agnostic: it does not hard-code Core IP
specifics; it applies whatever fault-origin types and propagation templates the Knowledge Service
provides for the snapshot's domain — a non-Core-IP domain's codebook is compiled with its own
fault-origins and templates without code change.

The Core IP fault-origin type set (authored in Knowledge) is: **Fiber, LineCard, Port,
Interface, Node**. Interface is a first-class, fault-capable fault-origin: an `InterfaceDown`
event directly originates the interface-fault cascade (`InterfaceDown => LinkDown => AdjDown =>
LSPDown => ReachabilityLoss`), and is also a cascade target when a Port fault cascades down
through the `HOSTS` edge into its interfaces. The codebook enumerates Interface instances as
fault origins and produces a distinct scenario signature for each.

---

## Scope

**In scope:**

- Consume `trails.built` (a `TrailsBuiltEvent` carrying `snapshotId`, `trailIds[]`,
  `trailCount`, and optional `domain`) and use it as the trigger to compile a new codebook for
  that snapshot.
- Read the **`domain`** directly from the `TrailsBuiltEvent` payload when present. When the
  field is absent (backward-compatibility case), default to the single MVP domain. No Topology
  lookup for domain resolution is required when the event carries the field.
- Fetch the **domain-scoped fault-origin list** (the set of graph object types that can be root
  causes for that domain) from the Knowledge Service, passing the resolved `domain` as a
  parameter. For the Core IP domain this list is: **Fiber, LineCard, Port, Interface, Node**.
  Different domains have different fault-origin type sets, authored in Knowledge.
- Fetch the **domain-scoped propagation templates** (per-edge-type fault cascade rules for that
  domain) from the Knowledge Service, passing the resolved `domain` as a parameter. For the Core
  IP domain these include:
  - `HOSTED_ON: fault(LineCard) => PortDown(each Port)`
  - `HOSTS: PortDown(Port) => InterfaceDown(each Interface on the port)`
  - `TERMINATES: InterfaceDown(Iface) => LinkDown(its IPLink)`
  - `ADJACENCY_OVER: InterfaceDown(Iface) => AdjDown(IGPAdjacency on that interface)`
  - `RIDES_ON: fault(Fiber) => LinkDown(IPLink)`
  - `TRAVERSES: LinkDown(IPLink) => LSPDown(LSP head-end)`
  - `SERVES: LSPDown(LSP) => ReachabilityLoss(VPN)`
  - `MEMBER_OF: co-failure grouping (fate sharing)`
- Fetch the **graph closure** for each fault-origin instance from the Topology Service query
  API (bounded traversal by edge type), scoped to the snapshot's domain.
- **Enumerate** every graph instance whose type is in the domain's fault-origin list — including
  Interface instances — using a domain-scoped query to the Topology Service.
- For each enumerated instance, **run the propagation templates forward** over the graph
  closure to produce that instance's predicted symptom set (the codebook row), including the
  origin's own alarm type. For an Interface fault-origin instance, this means: start with
  `InterfaceDown`, traverse `TERMINATES` to collect `LinkDown`, traverse `ADJACENCY_OVER` to
  collect `AdjDown`, then traverse `TRAVERSES` and `SERVES` from the resulting link/adjacency
  state to collect `LSPDown` and `ReachabilityLoss`.
- **Tag each scenario** with the trail(s) whose membership includes the scenario's symptoms,
  using the Trail Builder API (`getTrailsForObject` / `getTrail`), scoped to the snapshot's
  domain-scoped trails.
- **Persist** the compiled codebook (scenarios, alarm-type signatures, trail tags, `snapshotId`,
  `domain`) in PostgreSQL, keyed by a freshly minted `codebookId`. The `domain` column is a
  first-class attribute on every persisted codebook record.
- **Emit `codebook.generated`** (`CodebookGeneratedEvent`: `snapshotId`, `scenarioCount`,
  `codebookId`, `domain`) on the `codebook.generated` topic after successful compilation. The
  `domain` field is set from the resolved `domain` for the snapshot, so downstream consumers
  (Pattern Manager, Correlation Engine) receive it directly without an API lookup.
- Expose a **query API** (OpenAPI 3.1) that lets Pattern Manager and Correlation Engine read
  codebook scenarios, signatures, and trail tags by `codebookId`, `snapshotId`, or `domain`.
  The query API returns the `domain` on every codebook response; queries can be scoped by
  `domain` (e.g. `GET /codebooks?domain={domain}`).
- Publish **OpenAPI 3.1** at `/openapi.json` (plus a human-readable docs UI) and check the
  generated `openapi.json` into `services/codebook-generator/`; this spec is the single source
  of truth for the HTTP surface.
- Maintain **`snapshotId` alignment**: every codebook is tied to the `snapshotId` from which
  it was compiled; a new `snapshotId` always produces a new codebook and a new `codebookId`.
- Enforce the **one-active-codebook invariant**: at any point in time there is exactly **one
  active codebook per `(domain, snapshotId)`** key. When a codebook is successfully compiled
  for a `(domain, snapshotId)` that already has an active codebook (e.g. a re-trigger of the
  same snapshot), the newly compiled codebook becomes THE active one for that key and the prior
  codebook is superseded (no longer active). A codebookId's content never mutates — supersede
  creates/activates a new codebook record; it does not edit the prior one. The invariant
  guarantees that Pattern Manager (P2) and Correlation Engine (P3) always retrieve the same
  codebook for a given `(domain, snapshotId)`.
- Expose a **deterministic active-codebook retrieval endpoint** that returns the single active
  codebook for a specified `(domain, snapshotId)`. This is the canonical retrieval path for
  downstream consumers that need "the codebook for this domain+snapshot." Retrieval by
  `codebookId` also remains supported.
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
   `TrailsBuiltEvent`, extract the `snapshotId`, `trailIds[]`, and `domain` fields, deduplicate
   on the envelope `eventId`, and initiate a new codebook compilation cycle for that snapshot.
   Route unprocessable events to `trails.built.dlq`.

2. **Resolve the domain from the event payload.** Read the `domain` field directly from the
   `TrailsBuiltEvent` payload. When `domain` is absent (backward-compatibility case), default
   to the single MVP domain `core-ip`. No synchronous Topology lookup is required for domain
   resolution. The resolved `domain` is the parameter passed to all downstream Knowledge and
   Topology calls for this compilation cycle. _(Resolves OQ-4: domain is carried on the event
   per contract #90; the Topology snapshot metadata lookup fallback is no longer the primary
   path.)_

3. **Fetch domain-scoped fault-origin types and propagation templates from Knowledge Service.**
   Retrieve the domain's fault-origin object type list (which for Core IP includes Fiber,
   LineCard, Port, **Interface**, and Node) and propagation templates (per-edge-type cascade
   rules, including the HOSTS and ADJACENCY_OVER templates that drive interface cascades) from
   the Knowledge Service API, passing the resolved `domain` as a required parameter. These are
   read inputs — this service never authors them. A non-Core-IP domain's fault-origin list and
   templates are fetched identically; no code change is needed per domain.

4. **Enumerate domain-scoped fault-origin instances from the Topology Service.** Query the
   Topology Service's query API to list every graph object whose type appears in the domain's
   fault-origin list — including all Interface instances — scoped to the `snapshotId` and
   `domain` from the trigger event. The Topology Service is domain-isolated; the enumeration
   query is domain-scoped.

5. **Propagate templates forward and collect predicted symptom sets.** For each enumerated
   fault-origin instance, fetch its graph closure (bounded traversal by relevant edge types)
   from the Topology Service (domain-scoped), then apply the domain's propagation templates
   forward — traversing each template edge in cascade — to accumulate the full set of predicted
   alarm types (symptoms), including the origin instance's own alarm type. Each result is one
   codebook scenario row. For Interface instances specifically: start with `InterfaceDown`,
   apply `TERMINATES` to get `LinkDown` on the interface's IP link, apply `ADJACENCY_OVER` to
   get `AdjDown` on adjacencies on that interface, then apply `TRAVERSES` and `SERVES`
   transitively to collect `LSPDown` and `ReachabilityLoss`. This produces an interface-fault
   scenario signature distinguishable from a fiber-cut scenario (no `InterfaceDown` origin in
   the fiber-cut case) and from a port-fault scenario (port fault cascades through `HOSTS` to
   reach `InterfaceDown`, whereas an interface fault starts there directly).

6. **Tag each scenario with its trail(s).** For each scenario, resolve which trail(s) the
   scenario's symptoms occupy by querying the Trail Builder API
   (`getTrailsForObject` / `getTrail`) — the trails for this snapshot are domain-scoped — and
   attach the resulting `trailIds[]` to the scenario.

7. **Persist the codebook and set it active for its `(domain, snapshotId)`.**  Store all
   scenarios (fault-origin instance, predicted symptom signature, trail tags, `snapshotId`,
   `domain`) in PostgreSQL under a freshly minted `codebookId`, and atomically set this new
   codebook as the single active codebook for the `(domain, snapshotId)` key, superseding any
   prior active codebook for that key. The `domain` is a non-nullable column on the codebook
   record. A new `snapshotId` always produces a new codebook and a new `codebookId`.
   Superseding does not mutate the prior codebook's content — the prior codebook record is
   preserved (as superseded); only its active status changes. The mechanism (hard-delete vs.
   inactive-flag) is a design decision; the invariant — exactly one active per
   `(domain, snapshotId)` — is the contract.

8. **Emit `codebook.generated` with domain.** Publish a `CodebookGeneratedEvent` (`snapshotId`,
   `scenarioCount`, `codebookId`, `domain`) on the `codebook.generated` topic using the frozen
   `libs/event-model` Python/Pydantic binding. The `domain` field is set to the resolved domain
   for the snapshot so downstream consumers receive it directly. Failed-delivery fallback:
   `codebook.generated.dlq`. _(Resolves OQ-5: `domain` is now on `CodebookGeneratedEvent` per
   contract #90; downstream consumers do not need a separate API lookup to obtain the domain.)_

9. **Serve the domain-scoped codebook query API.** Answer requests from Pattern Manager and
   Correlation Engine: retrieve a codebook's full scenario list and signatures by `codebookId`;
   retrieve scenarios by `snapshotId`; retrieve a single scenario's predicted symptom signature
   and trail tags by scenario identifier; list codebooks filtered by `domain`; and return the
   **single active codebook** for a `(domain, snapshotId)` via the deterministic active-codebook
   retrieval endpoint. Every response includes the codebook's `domain`. The published OpenAPI
   3.1 spec is the surface contract.

---

## Phase applicability

The codebook-generator's primary work is in **P1** (topology onboarding), where it compiles the
domain-scoped codebook from the newly built trails. In **P2** and **P3** it drives no work of
its own; it serves its query API as a dependency for the Pattern Manager (reconciliation) and
the Correlation Engine (matching) respectively.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Compile domain-scoped codebook: on `trails.built`, read `domain` directly from the event payload, fetch domain-scoped fault-origin list (including Interface) + propagation templates (including HOSTS/TERMINATES/ADJACENCY_OVER interface templates) from Knowledge, enumerate domain-scoped fault-origin instances (including Interface instances) from Topology, run templates forward over graph closure, tag scenarios to trails, persist codebook (with `domain`), set it as the single active codebook for its `(domain, snapshotId)`, mint `codebookId`, emit `codebook.generated` (with `domain`) | **Active** | In: `trails.built` (carrying `snapshotId`, `trailIds[]`, `domain`) + Topology query API (domain-scoped), Knowledge fault-origin list API (with `domain` param), Knowledge propagation templates API (with `domain` param), Trail Builder API reads; Out: `codebook.generated` (carrying `snapshotId`, `scenarioCount`, `codebookId`, `domain`) |
| P2 — Pattern learning | Serves the domain-scoped codebook (scenario signatures + trail tags) to the Pattern Manager, which uses it to: (a) **reconcile** mined patterns — confirm those matching a scenario and flag patterns with no model explanation; (b) **supply authoritative RCA** — where a mined pattern overlaps a scenario, the scenario's root cause overrides the Manager's ordering-based RCA; (c) feed **explainability** (codebook-overlap metadata). The codebook is the model-based check on data-mined patterns; it drives no work of its own. | **Passive** | In: codebook query API requests from Pattern Manager (may include `domain` filter or active-codebook retrieval by `(domain, snapshotId)`); Out: codebook query API responses (scenario signatures, trail tags, RCA per scenario, `domain`). No topic output. |
| P3 — Real-time correlation | Serves the domain-scoped codebook to the Correlation Engine as the **model-based correlation/RCA source**, co-equal with approved patterns. The engine runs a **closest-match decode** of the live symptom set against trail-scoped scenarios to pick a root-cause scenario — enabling RCA even with no learned pattern. The winning scenario's id is recorded as `matchedCodebookId` on the incident. The codebook drives no work of its own. | **Passive** | In: codebook query API requests from Correlation Engine (may include `domain` filter or active-codebook retrieval by `(domain, snapshotId)`; codebook also delivered via `codebook.generated`); Out: codebook query API responses (scenario signatures + RCA, `domain`, scoped by trail/snapshot). No topic output. |

---

## Contract

- **Consumes (Kafka):** `trails.built` _(primary trigger)_
  - Payload type: `TrailsBuiltEvent` (frozen binding from `libs/event-model`)
  - Fields: `snapshotId` (string, required), `trailIds` (array of strings, required),
    `trailCount` (integer, required), `domain` (string, optional — present in all events
    produced after contract #90; absent in pre-#90 events, in which case the service defaults
    to the single MVP domain)
  - Envelope: standard envelope — `eventId` (UUID, idempotency / dedup key), `type`,
    `schemaVersion`, `occurredAt`, `source`, `traceId`, `payload`
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
  - Fields: `snapshotId` (string, required), `scenarioCount` (integer, required),
    `codebookId` (string, required), `domain` (string, optional — set by this service for all
    events produced after contract #90; absent for pre-#90 backward-compat events)
  - The `codebookId` minted here is the identity referenced as `matchedCodebookId` in
    `CorrelationResultEvent` and as `codebookMatchId` in `PatternDiscoveredEvent` /
    `PatternApprovedEvent` downstream.
  - The `domain` field is populated from the resolved domain, enabling downstream consumers
    (Pattern Manager, Correlation Engine) to obtain the codebook's domain directly from the
    event without a secondary API lookup. _(OQ-5 resolved: `domain` is now on the event per
    contract #90.)_
  - Note: `CodebookGeneratedEvent` carries a summary only. Full signatures are served via this
    service's query API — not embedded in the event.
  - Failed-delivery fallback: `codebook.generated.dlq`

- **APIs exposed** (publish OpenAPI 3.1 at `/openapi.json` + checked-in `openapi.json`; a
  surface change is a contract change):
  - `GET /codebooks/{codebookId}` — return codebook metadata (snapshotId, scenarioCount,
    codebookId, compiledAt, **domain**).
  - `GET /codebooks/{codebookId}/scenarios` — return all scenarios in the codebook (fault-origin
    instance identifier, fault-origin type, predicted symptom signature as an ordered list of
    alarm types, trail tags).
  - `GET /codebooks/{codebookId}/scenarios/{scenarioId}` — return a single scenario's predicted
    symptom signature and trail tags.
  - `GET /codebooks/{codebookId}/trail-signatures?trailId={trailId}` — **Correlation-Engine
    projection.** Return per-trail scenario signatures for the codebook in the shape the
    Correlation Engine consumes for codebook decode: a list of `TrailScenarioSignature`, each
    `{ trailId, scenarioId, rootCauseAlarmType, expectedSymptoms[{ alarmType, managedObjectId }] }`.
    With `trailId` set, return only the signatures whose source scenario's `trailIds[]` contains
    that `trailId`; without it, return every scenario fanned out across each of its `trailIds[]`.
    This is a **pure read projection** over the already-persisted scenario data (it does not change
    how codebooks are compiled or stored): `expectedSymptoms` is the **Correlation-Engine-facing
    alias of the scenario's `predictedSymptoms`** (same `{alarmType, managedObjectId}` items — one
    underlying truth), and `rootCauseAlarmType` is the origin's own `alarmType`-vocabulary token
    (the `alarmType` of the predicted symptom whose `managedObjectId` is the scenario's
    `faultOriginObjectId`, i.e. the first/seed symptom — **not** the object-type `faultOriginType`),
    drawn from the same Knowledge `alarmTypeVocabulary` value space as `AlarmEvent.alarmType`. The
    native `GET /codebooks/{codebookId}/scenarios` endpoint is retained for other consumers (e.g.
    Pattern Manager reconcile). Both endpoints are published in the checked-in `openapi.json`.
  - `GET /codebooks?snapshotId={snapshotId}` — return the codebook(s) compiled for a given
    `snapshotId` (typically one; a list for extensibility).
  - `GET /codebooks?domain={domain}` — return all codebooks compiled for the given domain,
    ordered by `compiledAt` descending. Supports domain-scoped lookup by Pattern Manager and
    Correlation Engine.
  - `GET /codebooks/active?domain={domain}&snapshotId={snapshotId}` — return the **single
    active codebook** for the specified `(domain, snapshotId)` key. Returns exactly one
    codebook or 404 if none has been compiled for that key. This is the deterministic retrieval
    endpoint that guarantees Pattern Manager (P2) and Correlation Engine (P3) retrieve the same
    codebook for a given `(domain, snapshotId)`.
  - `/health` and `/metrics` (Prometheus-compatible).

- **APIs consumed from other services** (integration points — built against each producer's
  published OpenAPI, never against source code):
  - **Topology Service — graph query API:** list objects by type scoped to `snapshotId` and
    `domain` (to enumerate domain-scoped fault-origin instances, including Interface instances);
    bounded traversal by edge type (to fetch graph closures for propagation, including
    HOSTS/TERMINATES/ADJACENCY_OVER edges for interface-fault scenarios).
    Integration point name: `topology-query`.
  - **Knowledge Service — domain-scoped fault-origin list:** retrieve the versioned list of
    fault-origin object types for the specified `domain` (for Core IP: Fiber, LineCard, Port,
    Interface, Node). Integration point name: `knowledge-fault-origins`.
  - **Knowledge Service — domain-scoped propagation templates:** retrieve the versioned set of
    propagation templates (per-edge-type cascade rules, including HOSTS, TERMINATES,
    ADJACENCY_OVER for interface cascades) for the specified `domain`. Integration point name:
    `knowledge-propagation-templates`.
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
    Domain-parameterized mock responses (different fault-origin lists / templates per domain,
    including Interface as a fault origin in the Core IP mock) are used to verify domain-scoped
    behaviour without live services.
  - Integration tests point each integration point at the real service in Docker Compose. The
    same code runs in both modes — no code change, only config.
  - No integration point URL or mock toggle is hard-coded.

- **Data owned:** PostgreSQL — Codebook Store (schema: `codebook`). Owns: codebooks table
  (codebookId, snapshotId, scenarioCount, compiledAt, **domain** [non-nullable], **active**
  [boolean, non-nullable — true for the single active codebook per `(domain, snapshotId)` key,
  false/absent for superseded codebooks]), scenarios table (scenarioId, codebookId,
  faultOriginObjectId, faultOriginType, predictedSymptoms as ordered alarm-type list, trailIds).
  Each `predictedSymptoms[].alarmType` is a canonical **`alarmType`-vocabulary token** drawn from
  the domain's Knowledge `alarmTypeVocabulary` — the same value space as `AlarmEvent.alarmType`
  (the canonical correlation join key), **distinct from** `eventType` (X.733 category) and
  `probableCause`. The origin's own symptom is first/seed in `predictedSymptoms` (its
  `managedObjectId` equals `faultOriginObjectId`); its `alarmType` is the scenario's root-cause
  alarm-type token, surfaced by the `trail-signatures` projection as `rootCauseAlarmType`. The
  `(domain, snapshotId, active=true)` combination is unique — enforced at the store level.
  No other service writes to this schema.

---

## Non-functional

- **Idempotency key:** envelope `eventId` (dedup consumed `trails.built` events); regenerating
  for a new `snapshotId` always mints a fresh `codebookId` and a new codebook; re-processing
  the same `eventId` is a no-op (the existing codebook is preserved and re-emitted if already
  compiled).
- **One-active-codebook invariant:** the store enforces that exactly one codebook record per
  `(domain, snapshotId)` is active at any time. The persist step (Task 7) sets the new codebook
  active and supersedes any prior active codebook for that key atomically. Consumers querying
  the active-codebook endpoint always receive a single deterministic result.
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
  always retrieve the codebook's `snapshotId` and `domain` from the `codebook.generated` event
  or the codebook query API.
- **Permissive licenses only:** all runtime dependencies must be Apache-2.0, BSD, MIT, or
  PostgreSQL-licensed. No GPL/AGPL/BSL/source-available components.

---

## Acceptance criteria

Each criterion maps to a single pytest test.

1. **Fiber-cut signature matches expected cascade.**
   Given a mock Topology Service returning a synthetic FiberSpan instance with RIDES_ON edges to
   an IPLink, TRAVERSES edges from that IPLink to an LSP, and SERVES edges from that LSP to a
   VPNService, and a mock Knowledge Service returning the standard Core IP propagation templates
   (RIDES_ON, TRAVERSES, SERVES) for domain `core-ip`, the compiled codebook scenario for that
   FiberSpan instance contains the expected ordered symptom set:
   `[FiberSpan-alarm, LinkDown(IPLink), LSPDown(LSP), ReachabilityLoss(VPNService)]`.
   _(Note: alarm-type identifier strings are illustrative placeholders; replaced with the shared
   alarm-type vocabulary confirmed at design — see OQ-2.)_

2. **Line-card fault and port fault produce distinguishable signatures.**
   Given mock graph instances for a LineCard (HOSTED_ON edges to two Ports, each Port with a
   HOSTS edge to one Interface, each Interface with a TERMINATES edge to an IPLink) and a Port
   (with one Interface, one IPLink), the compiled scenarios for each instance have distinct
   signatures: the LineCard scenario contains PortDown alarm types absent from the Port scenario,
   and the Port scenario's LOS / port-layer discriminator alarm is absent from the LineCard
   scenario's top-level signature.
   _(Note: alarm-type identifier strings are illustrative placeholders; replaced with the shared
   alarm-type vocabulary confirmed at design — see OQ-2.)_

3. **Interface fault-origin scenario signature matches the expected interface cascade.**
   Given a mock Topology Service returning a synthetic Interface instance with a TERMINATES edge
   to an IPLink, an ADJACENCY_OVER edge to an IGPAdjacency, that IPLink's TRAVERSES edge to an
   LSP, and that LSP's SERVES edge to a VPNService, and a mock Knowledge Service returning the
   Core IP propagation templates (HOSTS, TERMINATES, ADJACENCY_OVER, TRAVERSES, SERVES) for
   domain `core-ip`, the compiled codebook scenario for that Interface instance has fault-origin
   type `Interface` and contains the expected symptom set:
   `[InterfaceDown(Interface), LinkDown(IPLink), AdjDown(IGPAdjacency), LSPDown(LSP), ReachabilityLoss(VPNService)]`.
   The interface-fault scenario is distinguishable from the fiber-cut scenario (fiber-cut has no
   `InterfaceDown` origin alarm) and from a port-fault scenario (port fault cascades through
   HOSTS before reaching InterfaceDown, producing additional PortDown entries above InterfaceDown
   in the chain).
   _(Note: alarm-type identifier strings are illustrative placeholders; replaced with the shared
   alarm-type vocabulary confirmed at design — see OQ-2.)_

4. **Every scenario is tagged to at least one trail.**
   Given a mock Trail Builder returning at least one `trailId` for any `managedObjectId` queried,
   every scenario in the compiled codebook has a non-empty `trailIds[]`.

5. **Regenerating after a topology change produces a new codebook tied to the new snapshotId.**
   Given two sequential `trails.built` events with distinct `snapshotId` values (`snap-A` and
   `snap-B`), processing both events produces two separate codebook records with distinct
   `codebookId` values; the codebook for `snap-B` carries `snapshotId = snap-B`; and two
   `codebook.generated` events are emitted, each carrying the correct `snapshotId` and
   `codebookId`, validating against the `CodebookGeneratedEvent` Pydantic binding from
   `libs/event-model`.

6. **Duplicate `trails.built` events (same `eventId`) are deduplicated.**
   Given the same `trails.built` event delivered twice (identical `eventId`), the service
   compiles the codebook exactly once and emits `codebook.generated` exactly once.

7. **All outbound integration calls go through config-switchable integration points.**
   When `TOPOLOGY_QUERY_MODE=MOCK`, `KNOWLEDGE_FAULT_ORIGINS_MODE=MOCK`,
   `KNOWLEDGE_PROPAGATION_TEMPLATES_MODE=MOCK`, and `TRAIL_BUILDER_MODE=MOCK` are set, the
   service completes a full compilation cycle using mock responses derived from the respective
   producers' published OpenAPI specs, without making any real HTTP calls. When any integration
   point URL env var is absent or unset, the service refuses to start and logs a structured
   configuration error.

8. **Codebook query API returns scenario signature and trail tags by `codebookId`.**
   After compiling a codebook, `GET /codebooks/{codebookId}/scenarios/{scenarioId}` returns the
   correct predicted symptom list and `trailIds[]` for that scenario, with a `200` response that
   validates against the published `openapi.json` schema.

9. **`codebook.generated` event carries domain and validates against the frozen event-model binding.**
   The `CodebookGeneratedEvent` payload emitted after compilation is deserializable by the
   `CodebookGeneratedEvent` Pydantic class from `libs/event-model` without validation errors,
   carries `snapshotId`, `scenarioCount` (matching the number of persisted scenarios), a
   non-empty `codebookId`, and `domain` matching the domain of the triggering `trails.built`
   event.

10. **`domain` read directly from `TrailsBuiltEvent` — no Topology lookup required.**
    Given a `trails.built` event with `domain = "core-ip"` in its payload, the service reads
    the domain directly from the event and does not make any outbound call to the Topology
    Service's snapshot metadata endpoint during domain resolution. (Verified via mock assertion
    that the snapshot-metadata endpoint receives zero calls when `domain` is present on the
    event.)

11. **Unknown `schemaVersion` in a consumed event is rejected.**
    A `trails.built` envelope carrying `schemaVersion >= 2` is rejected (not processed) and
    routed to `trails.built.dlq`; the service does not crash.

12. **Unprocessable `trails.built` messages route to the DLQ.**
    A malformed `trails.built` message (missing required `snapshotId` field) is routed to
    `trails.built.dlq` and not retried indefinitely; the consumer continues processing
    subsequent valid messages.

13. **Compiled codebook record carries the snapshot's domain.**
    After processing a `trails.built` event for snapshot `snap-X` with `domain = "core-ip"`,
    the persisted codebook record has `domain = "core-ip"` (non-null). The
    `GET /codebooks/{codebookId}` response includes `"domain": "core-ip"` and validates against
    the published `openapi.json` schema.

14. **Fault-origin list and propagation templates are fetched with the snapshot's domain parameter.**
    When compiling a codebook for domain `core-ip`, the Knowledge Service integration point is
    called with `domain=core-ip` on each request (verified via mock assertion that the outbound
    request carries the domain parameter). No domain-specific data is hard-coded in the service.

15. **Domain-scoped enumeration query is passed to the Topology Service.**
    When enumerating fault-origin instances for domain `core-ip` and snapshot `snap-X`, the
    Topology Service integration point is called with both `snapshotId=snap-X` and
    `domain=core-ip` as query parameters (verified via mock assertion).

16. **Domain-scoped codebook query API filters by domain.**
    Given two persisted codebooks — one for domain `core-ip` (codebookId `CB-1`) and one for
    domain `transport` (codebookId `CB-2`) — `GET /codebooks?domain=core-ip` returns exactly
    the `core-ip` codebook and not the `transport` codebook; the response validates against the
    published `openapi.json` schema.

17. **A different domain's codebook compiles using that domain's fault-origins and templates without code change.**
    Given mock Knowledge Service responses returning a distinct fault-origin list and propagation
    templates for domain `transport` (different object types and cascade rules from `core-ip`),
    and mock Topology Service responses for domain `transport`, the service compiles a complete
    codebook for `transport` using those domain-specific inputs — without any code change or
    additional configuration beyond pointing to the same Knowledge and Topology service endpoints.
    The persisted record carries `domain = "transport"`.

18. **ONE-ACTIVE: exactly one active codebook per `(domain, snapshotId)` after compilation.**
    After compiling a codebook for `(domain="core-ip", snapshotId="snap-X")`, exactly one
    codebook record in the store is active for that key, and
    `GET /codebooks/active?domain=core-ip&snapshotId=snap-X` returns a `200` response
    containing that codebook (with its `codebookId` matching the one emitted on
    `codebook.generated`); the response validates against the published `openapi.json` schema.

19. **SUPERSEDE: recompiling the same `(domain, snapshotId)` makes the new codebook the single active one.**
    Given an existing active codebook `CB-OLD` for `(domain="core-ip", snapshotId="snap-X")`,
    when a second compilation completes for the same `(domain, snapshotId)` (producing
    `CB-NEW`), then: (a) `GET /codebooks/active?domain=core-ip&snapshotId=snap-X` returns
    `CB-NEW` (not `CB-OLD`); (b) exactly one active codebook exists for that key (no duplicate
    actives); and (c) both `CB-OLD` and `CB-NEW` remain retrievable by their individual
    `codebookId` values (the prior codebook's content is not destroyed).

20. **DETERMINISTIC-RETRIEVAL: two retrievals of the active codebook for the same `(domain, snapshotId)` return the same codebook.**
    Given a compiled active codebook for `(domain="core-ip", snapshotId="snap-X")`, two
    sequential calls to `GET /codebooks/active?domain=core-ip&snapshotId=snap-X` (simulating
    Pattern Manager in P2 and Correlation Engine in P3) both return `200` responses with
    identical `codebookId` values; no interleaving compilation occurs between the two calls.

21. **CE-PROJECTION-SHAPE: the `trail-signatures` projection returns the frozen
    Correlation-Engine shape.**
    `GET /codebooks/{codebookId}/trail-signatures` returns a `200` list of `TrailScenarioSignature`
    items, each shaped `{ trailId, scenarioId, rootCauseAlarmType, expectedSymptoms[{ alarmType,
    managedObjectId }] }`, validating against the published `openapi.json`. The native
    `GET /codebooks/{codebookId}/scenarios` endpoint remains available and unchanged.

22. **CE-PROJECTION-ROOTCAUSE: `rootCauseAlarmType` is the origin's own `alarmType` vocabulary
    token (not the object type).**
    For a scenario with `faultOriginObjectId="FiberSpan:f1"` and `faultOriginType="FiberSpan"`,
    the projected `rootCauseAlarmType` equals the `alarmType` of the predicted symptom whose
    `managedObjectId == faultOriginObjectId` (the first/seed symptom) — e.g. `"FiberFault"` — and
    is a member of the domain's Knowledge `alarmTypeVocabulary` (the same value space as
    `AlarmEvent.alarmType`). It is **not** the object-type value `"FiberSpan"` and is **not** an
    X.733 `eventType` or `probableCause` value.

23. **CE-PROJECTION-ALIAS: `expectedSymptoms` equals the scenario's `predictedSymptoms`.**
    For every projected signature, `expectedSymptoms` is item-for-item identical (same `alarmType`
    + `managedObjectId`, same order) to the source scenario's `predictedSymptoms` returned by
    `GET /codebooks/{codebookId}/scenarios/{scenarioId}` — confirming `expectedSymptoms` is a
    Correlation-Engine-facing alias of one underlying truth, not a separately stored copy.

24. **CE-PROJECTION-FANOUT: signatures fan out per trail from `trailIds[]`.**
    Given a scenario whose `trailIds = ["T1","T2"]`, the projection returns two
    `TrailScenarioSignature` items — one with `trailId="T1"` and one with `trailId="T2"` —
    surfacing the same `scenarioId`, `rootCauseAlarmType`, and `expectedSymptoms`. A request with
    `?trailId=T1` returns only the `T1` signature; a `trailId` matching no scenario returns a
    `200` empty list.

---

## Open questions

All remaining open questions are **design-stage items**.

- **OQ-1 [DESIGN-STAGE]: Trail Builder API surface for trail tagging.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/28)
  The spec requires calling `getTrailsForObject(managedObjectId)` on the Trail Builder Service.
  The Trail Builder's published OpenAPI is not yet frozen (its spec PR is open). This resolves
  when the Trail Builder spec is merged and `services/trail-builder/openapi.json` is checked in;
  at that point, codebook-generator's designer builds the `trail-builder-trails` integration
  point mock and client against that spec. The requirement — every scenario is tagged to at
  least one trail via a Trail Builder API call — is firm; the endpoint path and response schema
  are confirmed at design. Not a spec blocker.

- ~~**OQ-2: Shared alarm-type vocabulary for codebook signatures.**~~ **Resolved by the merged
  canonical `alarmType` contract.** (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/30)
  The codebook signature alarm-type identifiers (`predictedSymptoms[].alarmType`) and the
  projected `rootCauseAlarmType` are drawn from the canonical **`alarmType` vocabulary** — the
  Knowledge-authored, domain-scoped `alarmTypeVocabulary` (e.g. `PortDown`, `InterfaceDown`,
  `LinkDown`, `AdjDown`, `LSPDown`, `ReachabilityLoss`, `LOS`, `FiberFault`) — which is the **same
  value space as `AlarmEvent.alarmType`**, the merged canonical correlation join key
  (`libs/event-model/schema/payloads/AlarmEvent.schema.json`; `docs/architecture.md` Invariants).
  This is **distinct from** `eventType` (X.733 category) and `probableCause` (X.733 probable
  cause); the earlier open question of whether effects map to `eventType`/`probableCause`/a new
  field is settled — they are the `alarmType` token. The propagation templates Knowledge returns
  already carry `trigger.alarmType`/`effect.alarmType` as vocabulary tokens, so the codebook reads
  them straight through. The illustrative §5 effect strings in acceptance criteria 1–3 are the
  vocabulary tokens (e.g. `FiberFault` for a FiberSpan origin). No new `libs/event-model` field is
  introduced (`alarmType` already merged); a future new alarm-type field would be a contract change
  requiring human approval. Not a spec blocker.

- **OQ-3 [DESIGN-STAGE]: Topology Service query API support for snapshotId-scoped and domain-scoped object enumeration.**
  (Tracked: https://github.com/nikhilmohan/correlation-platform/issues/31)
  Task 4 requires enumerating all graph instances of each fault-origin type (including Interface)
  for a specific `snapshotId` and `domain`. The codebook-generator's requirement — enumerate
  fault-origin instances scoped to both the snapshot and the domain — must be accounted for in
  the Topology Service's design. This resolves when the Topology Service is designed and its
  `list objects by type` endpoint (with `snapshotId` and `domain` filter parameters) is
  confirmed in the Topology Service's published OpenAPI; codebook-generator's designer then
  builds its `topology-query` client and mock against that spec. If the Topology API does not
  support domain scoping, the Topology designer must note the constraint and
  codebook-generator's designer adapts accordingly. Not a spec blocker.

- ~~**OQ-4: Domain resolution mechanism for TrailsBuiltEvent.**~~ **Resolved by contract #90.**
  `TrailsBuiltEvent` now carries an optional `domain` field (see schema in
  `libs/event-model/schema/payloads/TrailsBuiltEvent.schema.json`). The service reads `domain`
  directly from the event payload. The Topology snapshot metadata lookup fallback (option (a)
  in the original question) is no longer the primary resolution path. No contract change was
  needed beyond #90.

- ~~**OQ-5: Whether `domain` should be added to `CodebookGeneratedEvent`.**~~ **Resolved by contract #90.**
  `CodebookGeneratedEvent` now carries an optional `domain` field (see schema in
  `libs/event-model/schema/payloads/CodebookGeneratedEvent.schema.json`). The service sets
  `domain` on every `codebook.generated` event it emits so downstream consumers (Pattern
  Manager, Correlation Engine) receive the domain without a secondary API lookup. No additional
  contract change is required.

- **OQ-6 [DESIGN-STAGE]: Supersede mechanism — hard-delete vs. inactive-flag.**
  Task 7 requires that recompiling for a `(domain, snapshotId)` that already has an active
  codebook supersedes the prior active codebook so exactly one remains active. The spec does not
  mandate whether the prior codebook is hard-deleted or marked inactive (e.g. an `active=false`
  flag). The designer must choose the mechanism that best satisfies: (a) the uniqueness
  invariant — `(domain, snapshotId, active=true)` is unique in the store; (b) the
  non-mutability of a given `codebookId`'s content (AC-19 requires the prior codebook to remain
  retrievable by its `codebookId`); (c) the atomicity of the supersede operation. The choice
  has no impact on the Kafka contract or event-model. Not a spec blocker.
