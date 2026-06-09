# knowledge — Service Spec

## Purpose

The Knowledge Service is the authoritative, versioned store for all authored domain knowledge
in the platform. It stores domain knowledge as an **extensible, domain-scoped template model**:
propagation templates, fault-origin types, trail policy, and model parameters (DBSCAN params,
session-window gap, min-support, etc.) are authored as **records belonging to a domain**. Core
IP is the MVP domain pack; a future domain is onboarded by authoring new template/record sets
— this is a data operation, not a code change. The Knowledge Service is the single owner of
this knowledge — no other service authors templates, policy, or params. Downstream consumers
read directly from it via a versioned API: Trail Builder consumes trail policy records; Codebook
Generator consumes fault-origin types and propagation templates; Noise Filter and Pattern Miner
consume model-parameter records; Pattern Manager, Correlation Engine, and the web-ui config
page also consume via API. Changes are broadcast via the `knowledge.updated` Kafka topic —
carrying a `KnowledgeUpdatedEvent` refresh trigger — so consumers know what changed and
re-fetch the specific version via the API. The web-ui config page provides the editing surface;
this service backs it with the API and enforces validation.

## Scope

**In scope:**

- Storing, versioning, validating, and serving **propagation templates** (per §5 edge type,
  e.g. `RIDES_ON`, `HOSTED_ON`, `ADJACENCY_OVER`, `TRAVERSES`, `SERVES`, `MEMBER_OF`) as
  domain-scoped template records.
- Storing, versioning, validating, and serving **fault-origin types** (e.g. `Fiber`,
  `LineCard`, `Port`, `Node`) as domain-scoped records.
- Storing, versioning, validating, and serving **trail policy** (the authored rule set: trail =
  transitive closure bounded by IGP area; SRLG fate-sharing) as domain-scoped records,
  **directly consumable by the Trail Builder** via the versioned API.
- Storing, versioning, validating, and serving **model parameters** (DBSCAN params,
  session-window gap, PrefixSpan min-support, max pattern length, and related tuning params)
  as an **extensible, open set of domain-scoped records** — not a fixed enum; the exact MVP
  param set is finalized at design with cross-consumer visibility.
- **CRUD API** for all four knowledge-record types, exposed as OpenAPI 3.1 at `/openapi.json`
  (+ a human-readable UI such as Swagger UI / springdoc) and checked in as `openapi.json`.
- **Versioned read API**: consumers may fetch the current version or a specific named version
  of any record.
- **Validation** of every edit before persistence: a template must reference a known §5 edge
  type; a fault-origin type must be a known graph object type; params must be within sane
  bounds. Invalid edits are rejected with clear, structured error responses. Validation is
  driven by referenced types in the template model, not by a hard-coded Core IP type list.
- Emitting **`knowledge.updated`** carrying a `KnowledgeUpdatedEvent` payload (fields:
  `recordType` (required), `recordId` (optional), `version` (required), `domain` (required))
  on every successful change so dependent services can re-fetch the specific version via the
  Knowledge API.
- **Extensible, domain-scoped template model**: every knowledge record carries a domain
  identifier (e.g. `core-ip` for the MVP domain) so a future domain's templates/policy/params
  can be authored and served without code change (per `docs/architecture.md` "Domain
  extensibility"). The schema is template-driven, not hard-coded per Core IP type.
- Returning structured JSON error responses for invalid edits (validation failures, unknown
  references, out-of-bounds params).

## Out of scope

- **Computing trails** — that is Trail Builder's responsibility; this service only authors and
  serves the trail policy that Trail Builder consumes.
- **Building the codebook** — that is Codebook Generator's responsibility; this service only
  serves the fault-origin types and propagation templates that Codebook Generator consumes.
- **Running DBSCAN, PrefixSpan, or any ML algorithm** — this service authors and serves the
  params those algorithms consume; it does not run them.
- **Owning pattern state or lifecycle** — that is Pattern Manager's exclusive responsibility.
- **Owning the topology graph** — that is Topology Service's exclusive responsibility.
- **Rendering the config UI** — the web-ui config page is the editor; this service exposes the
  API it calls.
- **Redundancy/protection-aware propagation templates** (FRR, ECMP) — deferred beyond MVP.
- **Automated retraining or closed-loop feedback execution** — out of MVP scope.
- **Multi-tenancy or HA/scale hardening** — out of MVP scope.
- **Consuming any Kafka topic** — this service produces `knowledge.updated` but consumes
  nothing from Kafka.

## Tasks (high-level)

1. **Store and version knowledge records.** Accept create and update operations for
   propagation templates, fault-origin types, trail policy, and model parameters. Each
   successful write mints a new immutable version; old versions remain retrievable. Records are
   stored as domain-scoped entries in the extensible template model — adding a future domain
   means authoring new records, not changing code.

2. **Validate edits before persistence.** Before persisting any change, verify: templates
   reference only the known §5 edge types; fault-origin types are known graph object types;
   trail policy is internally consistent; model params are within declared sane bounds. Return
   structured errors for any violation without persisting the change. Validation is driven by
   referenced types in the template model, not by a hard-coded Core IP type list.

3. **Serve current and pinned versions via API.** Expose CRUD and versioned-read endpoints for
   all four record types. A consumer (Trail Builder for trail policy; Codebook Generator for
   fault-origin types and propagation templates; Noise Filter and Pattern Miner for model
   params; others) may request the current version or a specific version identifier, enabling
   version pinning.

4. **Emit `knowledge.updated` on every successful change.** After a validated write is
   persisted, publish a `knowledge.updated` event on Kafka carrying a `KnowledgeUpdatedEvent`
   payload (`recordType`, `recordId`?, `version`, `domain`). Consumers receive the minimal
   refresh trigger and re-fetch the specific version via the Knowledge API; the knowledge
   itself stays in the versioned store, not in the event.

5. **Scope knowledge by domain.** Tag every record with a domain identifier (e.g. `core-ip`
   for the MVP). A future domain's records can be authored, validated, and retrieved without
   code change — the template model is domain-parameterized, not Core IP-specific.

## Contract

- **Consumes (Kafka):** — (none)
- **Produces (Kafka):** `knowledge.updated` — carries a `KnowledgeUpdatedEvent` payload with
  fields `recordType` (required, string), `recordId` (optional, string — identifier of the
  specific changed record; absent implies a broader change of that `recordType`), `version`
  (required, string — the new version; consumers fetch this version via the Knowledge API),
  and `domain` (required, string — the domain scope, e.g. `core-ip`). Emitted on every
  validated, persisted change. Consumers re-fetch the specific version via the Knowledge API;
  the knowledge itself is not embedded in the event.
- **APIs exposed:** Full CRUD + versioned-read for all four knowledge-record types —
  propagation templates, fault-origin types, trail policy, and model parameters. Published as
  **OpenAPI 3.1** at `/openapi.json` (+ springdoc UI); the generated `openapi.json` is
  checked in at `services/knowledge/openapi.json`. A change to this API surface is a contract
  change requiring `docs/architecture.md` update and human approval.
- **APIs/data consumed from other services:** — (none; Knowledge is a server and a Kafka
  producer only)
- **Integration points (mock vs. real):** Knowledge exposes no outbound integration points.
  Downstream consumers of the Knowledge API (Trail Builder, Codebook Generator, Noise Filter,
  Pattern Miner, Pattern Manager, web-ui) each define Knowledge as a config-switchable
  integration point on their own side: mock (generated from this service's published OpenAPI)
  for their unit tests, real Knowledge Service in integration. This service itself has no
  outbound dependencies to switch.
- **Data owned:** PostgreSQL knowledge store (logical schema `knowledge`). Owns the versioned
  tables for propagation templates, fault-origin types, trail policy, and model parameters.
  No other service writes to this schema.

## Non-functional

- **Idempotency key:** `eventId` (UUID, generated per `knowledge.updated` emission; consumers
  dedupe on `eventId` per platform invariant)
- **Config:** all integration URLs, database coordinates, Kafka bootstrap servers, and tuning
  settings from environment variables; no hard-coded values. Notably: the knowledge records
  themselves ARE the authoritative home of the domain thresholds — they are data, not code
  literals, and are authored/validated here at runtime, not baked into the binary.
- **Observability:** `/health`, `/metrics` (Prometheus, Apache-2.0), structured JSON logs
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json` and checks in `openapi.json`;
  the service's own published spec drives its contract/unit tests (request/response schema
  validation and provider-side contract verification); collaborating services build clients
  against this spec; any change to the surface is a contract change
- **Error handling:** invalid-edit requests return structured JSON error responses (HTTP 4xx)
  with clear field-level messages; no DLQ is needed (this service consumes no Kafka topics);
  internal failures on the Kafka producer path are retried with appropriate backoff

## Acceptance criteria

Each criterion maps to a single JUnit 5 test.

1. **CRUD + versioning for all four record types.** Creating a propagation template, a
   fault-origin type, a trail policy record, and a model params record each succeeds and
   returns an initial version identifier. Updating any record returns a new, incremented
   version identifier while the previous version remains retrievable via the API.

2. **Invalid edits are rejected with structured errors — unknown edge type.** A `POST`/`PUT`
   request for a propagation template that references an edge type not in the §5 vocabulary
   (e.g. `UNKNOWN_EDGE`) is rejected with HTTP 422 and a response body that names the
   offending field and the validation rule violated; nothing is persisted.

3. **Invalid edits are rejected with structured errors — out-of-bounds param.** A `POST`/`PUT`
   request for a model-params record where a parameter value violates declared bounds (e.g.
   `minSupport` < 0 or > 1) is rejected with HTTP 422 and a response body naming the
   offending parameter; nothing is persisted.

4. **Version pinning: consumers retrieve a specific version.** After a record is updated twice
   (yielding versions v1 and v2), a `GET` request specifying version v1 returns the v1 content
   unchanged; a `GET` without a version specifier returns the current (v2) content.

5. **`knowledge.updated` is emitted on every validated change with a conformant
   `KnowledgeUpdatedEvent` payload.** After each successful create or update of any knowledge
   record, exactly one `knowledge.updated` Kafka message is produced. The message envelope
   contains a non-null UUID `eventId`, `type` set to `KnowledgeUpdatedEvent`, `source` set to
   `knowledge`, and a valid `occurredAt` timestamp. The payload validates against the frozen
   `KnowledgeUpdatedEvent` JSON Schema
   (`libs/event-model/schema/payloads/KnowledgeUpdatedEvent.schema.json`): `recordType`
   (required, non-empty string), `version` (required, non-empty string matching the new
   version of the changed record), and `domain` (required, non-empty string) are present;
   `recordId` is present and equals the changed record's identifier when a specific record is
   changed. Consumers re-fetch the record via the Knowledge API using `version`; the knowledge
   data is not embedded in the event.

6. **Published OpenAPI 3.1 is served and matches operations.** `GET /openapi.json` returns a
   valid OpenAPI 3.1 document. The document includes at minimum: `GET`, `POST`, and `PUT`
   operations for each of the four knowledge-record types, and a versioned-read operation
   accepting a version parameter. The service's implementation satisfies the contract defined
   in that document (provider-side contract test).

7. **Domain-scoped records: records carry a domain identifier.** A knowledge record created
   with domain `core-ip` is returned with `domain: "core-ip"` in all read responses; a query
   filtered by domain `core-ip` returns only that domain's records and not records from another
   domain identifier.

8. **Duplicate `eventId` is idempotent.** If the Kafka producer emits a `knowledge.updated`
   event and the same `eventId` is presented to a consumer-side deduplication check, the
   second occurrence is recognised as a duplicate (the `eventId` is a stable UUID tied to the
   specific change, not regenerated on retry).

9. **Extensible domain-template model: a non-Core-IP domain record can be CRUDed and
   fetched.** A propagation template record authored with `domain: "other-domain"` (a domain
   that is not `core-ip`) can be created, updated, retrieved (current and pinned version), and
   filtered by domain without any code change. Validation of that record's edge-type reference
   is driven by the referenced type in the template model, not by a hard-coded Core IP list;
   the service does not reject the record solely because the domain is not `core-ip`.

## Open questions

- **OQ-2 (design-stage) — Extensible model-param catalog: exact MVP param set.** The model-
  param catalog is an open/extensible set (records authored here, not a fixed enum in code).
  The exact MVP param set (which consumer's params live here, whether enrichment thresholds
  such as flap-damp window and dedup window are included, and their sane-bounds declarations)
  is finalized at design stage with cross-consumer visibility. This is not a spec blocker.
  Tracked: GitHub issue #23.

- **OQ-3 (design-stage) — Propagation-template effect vocabulary and canonical alarm-type
  identifiers.** Propagation templates name the alarm types their effects produce (e.g.
  `LinkDown`, `AdjDown`, `LSPDown`, `PortDown`, `LOS`, `ReachabilityLoss`). Because Knowledge
  authors these templates, the canonical alarm-type identifier vocabulary is a design-stage
  coordination item shared between the Knowledge Service designer and the Codebook Generator
  designer. This spec does not invent that vocabulary. Tracked: see GitHub issue #30
  (owned on the codebook side; this note is for cross-service visibility).
