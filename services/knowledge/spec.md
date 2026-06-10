# knowledge — Service Spec

## Purpose

The Knowledge Service is the authoritative, versioned store for all authored domain knowledge
in the platform. It stores domain knowledge as an **extensible, domain-scoped record model**:
propagation templates, fault-origin types, trail policy, model parameters (DBSCAN params,
session-window gap, min-support, etc.), **object-type vocabulary**, **edge-relation
vocabulary**, and **device/connection attribute catalogue** are all authored as **records
belonging to a domain**. Core IP is the MVP domain pack; a future domain is onboarded by
authoring new record sets — this is a data operation, not a code change. The Knowledge
Service is the single owner of this knowledge — no other service authors templates, policy,
params, or vocabulary records. Downstream consumers read directly from it via a versioned
API: Trail Builder consumes trail policy records; Codebook Generator consumes fault-origin
types and propagation templates; Noise Filter and Pattern Miner consume model-parameter
records; **Topology validates ingested snapshots against the domain's object-type and
edge-relation vocabulary served by this service**; Noise Filter, Trail Builder, and Codebook
Generator may consume the attribute catalogue where policy or templates reference equipment
characteristics. Changes are broadcast via the `knowledge.updated` Kafka topic — carrying a
`KnowledgeUpdatedEvent` refresh trigger — so consumers know what changed and re-fetch the
specific version via the API. The web-ui config page provides the editing surface; this
service backs it with the API and enforces validation.

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
- Storing, versioning, validating, and serving the **object-type vocabulary** for each domain:
  the set of valid `objectType` values that may appear in a topology snapshot's
  `managedObjectId` (e.g. for Core IP: `Node`, `LineCard`, `Port`, `IPLink`, `IGPAdjacency`,
  `LSP`, `VPNService`, `FiberSpan`, `SRLG`, plus the domain-agnostic `Site`). The Topology
  Service fetches this vocabulary to validate an uploaded snapshot before lifting it into the
  graph. A new domain authors its own set without code change.
- Storing, versioning, validating, and serving the **edge-relation vocabulary** for each
  domain: the set of valid `relation` values that may appear in a topology snapshot's edges
  (e.g. for Core IP: `HOSTED_ON`, `RIDES_ON`, `ADJACENCY_OVER`, `TRAVERSES`, `SERVES`,
  `MEMBER_OF`, plus the domain-agnostic `LOCATED_AT`). The Topology Service fetches this
  vocabulary to validate an uploaded snapshot before lifting it into the graph. A new domain
  authors its own set without code change.
- Storing, versioning, validating, and serving the **device/connection attribute catalogue**
  for each domain: the set of well-known attribute keys and their meaning/allowed forms for
  device nodes (e.g. `vendor`, `model`, `equipmentType`, `role`, `capacity`) and connection
  edges (e.g. `linkType`, `capacity`, `protectionRole`). The catalogue is open (a domain may
  add keys); it is authored and catalogued per domain here. Consumers that may use these
  include Noise Filter (e.g. `equipmentType` as a clustering feature) and Trail Builder /
  Codebook Generator (where policy/templates reference equipment characteristics). Attribute
  catalogue entries are descriptive; validation rigour is a design-stage decision (see Open
  questions).
- **CRUD API** for all seven knowledge-record types, exposed as OpenAPI 3.1 at `/openapi.json`
  (+ a human-readable UI such as Swagger UI / springdoc) and checked in as `openapi.json`.
- **Versioned read API**: consumers may fetch the current version or a specific named version
  of any record.
- **Validation** of every edit before persistence: a template must reference a known §5 edge
  type; a fault-origin type must be a known graph object type; params must be within sane
  bounds; object-type and edge-relation vocabulary entries must conform to the
  `managedObjectId` token format (`^[A-Za-z][A-Za-z0-9]*$`). Invalid edits are rejected with
  clear, structured error responses. Validation is driven by referenced types in the record
  model, not by a hard-coded Core IP list.
- Emitting **`knowledge.updated`** carrying a `KnowledgeUpdatedEvent` payload (fields:
  `recordType` (required), `recordId` (optional), `version` (required), `domain` (required))
  on every successful change — including changes to vocabulary and catalogue records — so
  dependent services can re-fetch the specific version via the Knowledge API.
- **Extensible, domain-scoped record model**: every knowledge record carries a domain
  identifier (e.g. `core-ip` for the MVP domain) so a future domain's templates, policy,
  params, vocabulary, and catalogue can be authored and served without code change (per
  `docs/architecture.md` "Domain extensibility"). The schema is template-driven, not
  hard-coded per Core IP type.
- Returning structured JSON error responses for invalid edits (validation failures, unknown
  references, out-of-bounds params, malformed token format).

## Out of scope

- **Computing trails** — that is Trail Builder's responsibility; this service only authors and
  serves the trail policy that Trail Builder consumes.
- **Building the codebook** — that is Codebook Generator's responsibility; this service only
  serves the fault-origin types and propagation templates that Codebook Generator consumes.
- **Running DBSCAN, PrefixSpan, or any ML algorithm** — this service authors and serves the
  params those algorithms consume; it does not run them.
- **Owning pattern state or lifecycle** — that is Pattern Manager's exclusive responsibility.
- **Owning the topology graph** — that is Topology Service's exclusive responsibility.
  Knowledge owns the vocabulary/catalogue data; Topology is the sole owner of the graph store
  (Apache AGE) and is the only service that validates a topology snapshot against the
  vocabulary (via the Knowledge API) before ingestion.
- **Rendering the config UI** — the web-ui config page is the editor; this service exposes the
  API it calls.
- **Enforcing attribute values at runtime on live alarms** — attribute catalogue entries are
  descriptive; runtime alarm-field validation is not owned by this service.
- **Redundancy/protection-aware propagation templates** (FRR, ECMP) — deferred beyond MVP.
- **Automated retraining or closed-loop feedback execution** — out of MVP scope.
- **Multi-tenancy or HA/scale hardening** — out of MVP scope.
- **Consuming any Kafka topic** — this service produces `knowledge.updated` but consumes
  nothing from Kafka.

## Tasks (high-level)

1. **Store and version knowledge records.** Accept create and update operations for
   propagation templates, fault-origin types, trail policy, model parameters, object-type
   vocabulary, edge-relation vocabulary, and device/connection attribute catalogue. Each
   successful write mints a new immutable version; old versions remain retrievable. Records are
   stored as domain-scoped entries — adding a future domain means authoring new records, not
   changing code.

2. **Validate edits before persistence.** Before persisting any change, verify: templates
   reference only the known §5 edge types; fault-origin types are known graph object types;
   trail policy is internally consistent; model params are within declared sane bounds;
   object-type and edge-relation vocabulary entries conform to the `managedObjectId`
   token format (`^[A-Za-z][A-Za-z0-9]*$`). Return structured errors for any violation
   without persisting the change. Validation is driven by referenced types in the record
   model, not by a hard-coded Core IP type list.

3. **Serve current and pinned versions via API.** Expose CRUD and versioned-read endpoints for
   all seven record types. A consumer (Trail Builder for trail policy; Codebook Generator for
   fault-origin types and propagation templates; Noise Filter and Pattern Miner for model
   params; Topology for object-type and edge-relation vocabulary; Noise Filter / Trail Builder
   / Codebook Generator for the attribute catalogue) may request the current version or a
   specific version identifier, enabling version pinning.

4. **Serve domain vocabulary and attribute catalogue for Topology validation.** Expose a
   query endpoint that allows the Topology Service to fetch the complete object-type vocabulary
   and edge-relation vocabulary for a given domain. This is the validation gate the Topology
   Service runs against before lifting an uploaded snapshot into the graph.

5. **Emit `knowledge.updated` on every successful change.** After a validated write is
   persisted — for any of the seven record types including vocabulary and catalogue records —
   publish a `knowledge.updated` event on Kafka carrying a `KnowledgeUpdatedEvent` payload
   (`recordType`, `recordId`?, `version`, `domain`). Consumers receive the minimal refresh
   trigger and re-fetch the specific version via the Knowledge API; the knowledge itself stays
   in the versioned store, not in the event.

6. **Scope knowledge by domain.** Tag every record with a domain identifier (e.g. `core-ip`
   for the MVP). A future domain's records — including its object-type vocabulary,
   edge-relation vocabulary, and attribute catalogue — can be authored, validated, and
   retrieved without code change.

## Phase applicability

The Knowledge Service is **Passive in all three runtime phases** (per the canonical phase map
in `docs/architecture.md`). It serves authored knowledge and parameters on demand via its
versioned API, and emits `knowledge.updated` (`KnowledgeUpdatedEvent`) whenever a record is
authored or edited — but it drives none of the phase work itself. Because knowledge records
are editable at any time via the web-ui config page, `knowledge.updated` can fire in any phase.

| Phase | Role | Active/Passive/Idle | Inputs / Outputs |
|---|---|---|---|
| **P1 — Topology onboarding** | Serves the object-type vocabulary and edge-relation vocabulary (to Topology, for snapshot validation); serves the trail policy (to Trail Builder) and the fault-origin list + propagation templates (to Codebook Generator); serves the attribute catalogue (to Trail Builder / Codebook Generator as needed) via the versioned API | **Passive** | In: — (no Kafka consumption). Out: versioned API reads served on request; `knowledge.updated` (`KnowledgeUpdatedEvent`) emitted on `knowledge.updated` topic if any record is authored/edited during this phase. |
| **P2 — Pattern learning** | Serves the Phase-2 model params (DBSCAN params, session-window gap, PrefixSpan min-support, etc.) to Noise Filter, Pattern Miner, and Pattern Manager; serves the attribute catalogue (to Noise Filter, e.g. `equipmentType` as a clustering feature) via the versioned API | **Passive** | In: — (no Kafka consumption). Out: versioned API reads served on request; `knowledge.updated` (`KnowledgeUpdatedEvent`) emitted on `knowledge.updated` topic if any param or catalogue record is authored/edited during this phase. |
| **P3 — Real-time correlation** | Serves params + approved policy to real-time consumers (e.g. Correlation Engine configuration) via the versioned API | **Passive** | In: — (no Kafka consumption). Out: versioned API reads served on request; `knowledge.updated` (`KnowledgeUpdatedEvent`) emitted on `knowledge.updated` topic if any record is authored/edited during this phase. |

## Contract

- **Consumes (Kafka):** — (none)
- **Produces (Kafka):** `knowledge.updated` — carries a `KnowledgeUpdatedEvent` payload with
  fields `recordType` (required, string), `recordId` (optional, string — identifier of the
  specific changed record; absent implies a broader change of that `recordType`), `version`
  (required, string — the new version; consumers fetch this version via the Knowledge API),
  and `domain` (required, string — the domain scope, e.g. `core-ip`). Emitted on every
  validated, persisted change for all seven record types (including `objectTypeVocabulary`,
  `edgeRelationVocabulary`, and `attributeCatalogue`). Consumers re-fetch the specific version
  via the Knowledge API; the knowledge itself is not embedded in the event.
- **APIs exposed:** Full CRUD + versioned-read for all seven knowledge-record types —
  propagation templates, fault-origin types, trail policy, model parameters, object-type
  vocabulary, edge-relation vocabulary, and device/connection attribute catalogue. Includes a
  dedicated **vocabulary query endpoint** allowing the Topology Service to fetch a domain's
  complete object-type set and edge-relation set in a single request (for snapshot
  pre-validation). Published as **OpenAPI 3.1** at `/openapi.json` (+ springdoc UI); the
  generated `openapi.json` is checked in at `services/knowledge/openapi.json`. A change to
  this API surface is a contract change requiring `docs/architecture.md` update and human
  approval.
- **APIs/data consumed from other services:** — (none; Knowledge is a server and a Kafka
  producer only)
- **Integration points (mock vs. real):** Knowledge exposes no outbound integration points.
  Downstream consumers of the Knowledge API (Trail Builder, Codebook Generator, Noise Filter,
  Pattern Miner, Pattern Manager, Topology, web-ui) each define Knowledge as a
  config-switchable integration point on their own side: mock (generated from this service's
  published OpenAPI) for their unit tests, real Knowledge Service in integration. This service
  itself has no outbound dependencies to switch.
- **Data owned:** PostgreSQL knowledge store (logical schema `knowledge`). Owns the versioned
  tables for propagation templates, fault-origin types, trail policy, model parameters,
  object-type vocabulary, edge-relation vocabulary, and device/connection attribute catalogue.
  No other service writes to this schema.

## Non-functional

- **Idempotency key:** `eventId` (UUID, generated per `knowledge.updated` emission; consumers
  dedupe on `eventId` per platform invariant)
- **Config:** all integration URLs, database coordinates, Kafka bootstrap servers, and tuning
  settings from environment variables; no hard-coded values. Notably: the knowledge records
  themselves ARE the authoritative home of the domain thresholds, vocabulary, and catalogue —
  they are data, not code literals, and are authored/validated here at runtime, not baked into
  the binary.
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

1. **CRUD + versioning for all four original record types.** Creating a propagation template, a
   fault-origin type, a trail policy record, and a model params record each succeeds and
   returns an initial version identifier. Updating any record returns a new, incremented
   version identifier while the previous version remains retrievable via the API.

2. **CRUD + versioning for the object-type vocabulary.** Creating an object-type vocabulary
   record for a domain (e.g. `core-ip` with entries `Node`, `LineCard`, `Port`, `IPLink`,
   `IGPAdjacency`, `LSP`, `VPNService`, `FiberSpan`, `SRLG`, `Site`) succeeds and returns an
   initial version identifier. Updating the record (e.g. adding a new type token) returns a
   new version identifier while the prior version remains retrievable.

3. **CRUD + versioning for the edge-relation vocabulary.** Creating an edge-relation vocabulary
   record for a domain (e.g. `core-ip` with entries `HOSTED_ON`, `RIDES_ON`,
   `ADJACENCY_OVER`, `TRAVERSES`, `SERVES`, `MEMBER_OF`, `LOCATED_AT`) succeeds and returns
   an initial version identifier. Updating the record returns a new version identifier while
   the prior version remains retrievable.

4. **CRUD + versioning for the attribute catalogue.** Creating a device/connection attribute
   catalogue record for a domain (e.g. with device keys `vendor`, `model`, `equipmentType`,
   `role`, `capacity` and connection keys `linkType`, `capacity`, `protectionRole`) succeeds
   and returns an initial version identifier. Updating the record (e.g. adding a new key)
   returns a new version identifier while the prior version remains retrievable.

5. **Object-type vocabulary entries are validated against the token format.** A `POST`/`PUT`
   for an object-type vocabulary record that contains an entry failing the token format
   (e.g. `"123Invalid"` — starts with a digit) is rejected with HTTP 422 and a response body
   naming the offending entry; nothing is persisted.

6. **Edge-relation vocabulary entries are validated against the token format.** A `POST`/`PUT`
   for an edge-relation vocabulary record containing an entry that fails the token format is
   rejected with HTTP 422 and a response body naming the offending entry; nothing is persisted.

7. **Vocabulary query endpoint serves domain object-type and edge-relation sets.** A `GET`
   request to the vocabulary query endpoint for domain `core-ip` returns a response containing
   both the complete current object-type set and the complete current edge-relation set for
   that domain. A request for an unknown domain returns HTTP 404.

8. **`knowledge.updated` is emitted for vocabulary and catalogue changes with a conformant
   `KnowledgeUpdatedEvent` payload.** After each successful create or update of an
   object-type vocabulary, edge-relation vocabulary, or attribute catalogue record, exactly one
   `knowledge.updated` Kafka message is produced. The envelope contains a non-null UUID
   `eventId`, `type` set to `KnowledgeUpdatedEvent`, `source` set to `knowledge`, and a valid
   `occurredAt` timestamp. The payload has `recordType` matching the record type changed (e.g.
   `objectTypeVocabulary`, `edgeRelationVocabulary`, or `attributeCatalogue`), a non-empty
   `version` matching the new version, a non-empty `domain`, and a `recordId` equal to the
   changed record's identifier.

9. **Invalid edits are rejected with structured errors — unknown edge type.** A `POST`/`PUT`
   request for a propagation template that references an edge type not in the §5 vocabulary
   (e.g. `UNKNOWN_EDGE`) is rejected with HTTP 422 and a response body that names the
   offending field and the validation rule violated; nothing is persisted.

10. **Invalid edits are rejected with structured errors — out-of-bounds param.** A `POST`/`PUT`
    request for a model-params record where a parameter value violates declared bounds (e.g.
    `minSupport` < 0 or > 1) is rejected with HTTP 422 and a response body naming the
    offending parameter; nothing is persisted.

11. **Version pinning: consumers retrieve a specific version.** After a record is updated twice
    (yielding versions v1 and v2), a `GET` request specifying version v1 returns the v1 content
    unchanged; a `GET` without a version specifier returns the current (v2) content.

12. **`knowledge.updated` is emitted on every validated change with a conformant
    `KnowledgeUpdatedEvent` payload (original four record types).** After each successful
    create or update of any propagation template, fault-origin type, trail policy, or
    model-params record, exactly one `knowledge.updated` Kafka message is produced. The message
    envelope contains a non-null UUID `eventId`, `type` set to `KnowledgeUpdatedEvent`,
    `source` set to `knowledge`, and a valid `occurredAt` timestamp. The payload validates
    against the frozen `KnowledgeUpdatedEvent` JSON Schema
    (`libs/event-model/schema/payloads/KnowledgeUpdatedEvent.schema.json`): `recordType`
    (required, non-empty string), `version` (required, non-empty string matching the new
    version of the changed record), and `domain` (required, non-empty string) are present;
    `recordId` is present and equals the changed record's identifier when a specific record is
    changed.

13. **Published OpenAPI 3.1 is served and matches operations.** `GET /openapi.json` returns a
    valid OpenAPI 3.1 document. The document includes at minimum: `GET`, `POST`, and `PUT`
    operations for each of the seven knowledge-record types, the vocabulary query endpoint, and
    a versioned-read operation accepting a version parameter. The service's implementation
    satisfies the contract defined in that document (provider-side contract test).

14. **Domain-scoped records: records carry a domain identifier.** A knowledge record created
    with domain `core-ip` is returned with `domain: "core-ip"` in all read responses; a query
    filtered by domain `core-ip` returns only that domain's records and not records from another
    domain identifier.

15. **Duplicate `eventId` is idempotent.** If the Kafka producer emits a `knowledge.updated`
    event and the same `eventId` is presented to a consumer-side deduplication check, the
    second occurrence is recognised as a duplicate (the `eventId` is a stable UUID tied to the
    specific change, not regenerated on retry).

16. **Extensible domain record model: a non-Core-IP domain's vocabulary and catalogue can be
    CRUDed and fetched.** An object-type vocabulary record, an edge-relation vocabulary record,
    and an attribute catalogue record authored with `domain: "other-domain"` can each be
    created, updated, retrieved (current and pinned version), and filtered by domain without
    any code change. The service does not reject these records solely because the domain is not
    `core-ip`.

17. **Extensible domain-template model: a non-Core-IP domain's propagation template can be
    CRUDed and fetched.** A propagation template record authored with `domain: "other-domain"`
    can be created, updated, retrieved (current and pinned version), and filtered by domain
    without any code change. Validation of that record's edge-type reference is driven by the
    referenced type in the record model, not by a hard-coded Core IP list; the service does not
    reject the record solely because the domain is not `core-ip`.

## Open questions

- **OQ-2 (design-stage) — Extensible model-param catalog: exact MVP seed param set.**
  *Resolved (issue #23, closed):* the model-param catalog is an **open, extensible set of
  records** authored and versioned here — never a fixed enum in code — and every param record
  is **updatable and retrievable consistently** through the same CRUD + versioned-read API as
  the other record types (asserted by acceptance criteria 1 and 11). The only design-stage
  detail remaining is the **exact seed param set** for the MVP (which consumers' params are
  pre-loaded, whether enrichment thresholds such as flap-damp/dedup windows are included, and
  their sane-bounds declarations) — finalized at design with cross-consumer visibility. Not a
  spec blocker.

- **OQ-3 (design-stage) — Propagation-template effect vocabulary and canonical alarm-type
  identifiers.** Propagation templates name the alarm types their effects produce (e.g.
  `LinkDown`, `AdjDown`, `LSPDown`, `PortDown`, `LOS`, `ReachabilityLoss`). Because Knowledge
  authors these templates, the canonical alarm-type identifier vocabulary is a design-stage
  coordination item shared between the Knowledge Service designer and the Codebook Generator
  designer. This spec does not invent that vocabulary. Tracked: see GitHub issue #30
  (owned on the codebook side; this note is for cross-service visibility).

- **OQ-4 (design-stage) — Attribute catalogue: descriptive vs. enforced validation rigour.**
  The `architecture.md` describes the attribute catalogue as "descriptive" — the attribute
  keys and their allowed forms are catalogued here for consumers to reference, but it is not
  stated whether the Knowledge Service must enforce the catalogue (e.g. reject a topology
  snapshot node that carries an undocumented key) or whether enforcement is purely advisory.
  This is a design-stage decision: the spec treats the catalogue as descriptive (see Out of
  scope). If the design introduces enforcement beyond token-format validation, that is a
  design-stage clarification and does not require a new topic/payload/field. Tracked as an
  open item for the designer.
