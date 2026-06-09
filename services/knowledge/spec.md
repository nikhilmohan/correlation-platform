# knowledge — Service Spec

## Purpose

The Knowledge Service is the authoritative, versioned store for all authored domain knowledge
in the platform: propagation templates, fault-origin types, trail policy, and Phase-2 model
parameters (DBSCAN params, session-window gap, min-support, etc.). It is the single owner of
this knowledge — no other service authors templates, policy, or params. Consumers (Trail
Builder, Codebook Generator, Noise Filter, Pattern Miner, Pattern Manager, Correlation Engine,
web-ui config page) read from it via a versioned API; changes are broadcast via the
`knowledge.updated` Kafka topic so dependents can refresh. The web-ui config page provides the
editing surface; this service backs it with the API and enforces validation.

## Scope

**In scope:**

- Storing, versioning, validating, and serving **propagation templates** (per §5 edge type,
  e.g. `RIDES_ON`, `HOSTED_ON`, `ADJACENCY_OVER`, `TRAVERSES`, `SERVES`, `MEMBER_OF`).
- Storing, versioning, validating, and serving **fault-origin types** (e.g. `Fiber`,
  `LineCard`, `Port`, `Node`).
- Storing, versioning, validating, and serving **trail policy** (the authored rule set: trail =
  transitive closure bounded by IGP area; SRLG fate-sharing).
- Storing, versioning, validating, and serving **Phase-2 model parameters** (DBSCAN params,
  session-window gap, PrefixSpan min-support, max pattern length, and related tuning params).
- **CRUD API** for all four knowledge-record types, exposed as OpenAPI 3.1 at `/openapi.json`
  (+ a human-readable UI such as Swagger UI / springdoc) and checked in as `openapi.json`.
- **Versioned read API**: consumers may fetch the current version or a specific named version
  of any record.
- **Validation** of every edit before persistence: a template must reference a known §5 edge
  type; a fault-origin type must be a known graph object type; params must be within sane
  bounds. Invalid edits are rejected with clear, structured error responses.
- Emitting **`knowledge.updated`** on every successful change so dependent services can
  refresh their local copies.
- **Domain-scoped records**: every knowledge record carries a domain identifier (e.g.
  `coreip` for the MVP domain) so a future domain's templates/policy/params can be authored
  and served without code change (per `docs/architecture.md` "Domain extensibility").
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
   successful write mints a new immutable version; old versions remain retrievable.

2. **Validate edits before persistence.** Before persisting any change, verify: templates
   reference only the known §5 edge types; fault-origin types are known graph object types;
   trail policy is internally consistent; model params are within declared sane bounds. Return
   structured errors for any violation without persisting the change.

3. **Serve current and pinned versions via API.** Expose CRUD and versioned-read endpoints for
   all four record types. A consumer may request the current version or a specific version
   identifier, enabling version pinning.

4. **Emit `knowledge.updated` on every successful change.** After a validated write is
   persisted, publish a `knowledge.updated` event on Kafka so all dependent services can
   refresh their local cached copies.

5. **Scope knowledge by domain.** Tag every record with a domain identifier. For the MVP all
   records carry the `coreip` domain tag. A future domain's records can be authored and
   retrieved without code change.

## Contract

- **Consumes (Kafka):** — (none)
- **Produces (Kafka):** `knowledge.updated` (refresh trigger; emitted on every validated,
  persisted change to any knowledge record)
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

5. **`knowledge.updated` is emitted on every validated change.** After each successful create
   or update of any knowledge record, exactly one `knowledge.updated` Kafka message is
   produced. The message envelope contains a non-null UUID `eventId`, the `source` field set
   to `knowledge`, and a valid `occurredAt` timestamp. (Full payload binding subject to Open
   question OQ-1.)

6. **Published OpenAPI 3.1 is served and matches operations.** `GET /openapi.json` returns a
   valid OpenAPI 3.1 document. The document includes at minimum: `GET`, `POST`, and `PUT`
   operations for each of the four knowledge-record types, and a versioned-read operation
   accepting a version parameter. The service's implementation satisfies the contract defined
   in that document (provider-side contract test).

7. **Domain-scoped records: records carry a domain identifier.** A knowledge record created
   with domain `coreip` is returned with `domain: "coreip"` in all read responses; a query
   filtered by domain `coreip` returns only that domain's records and not records from another
   domain identifier.

8. **Duplicate `eventId` is idempotent.** If the Kafka producer emits a `knowledge.updated`
   event and the same `eventId` is presented to a consumer-side deduplication check, the
   second occurrence is recognised as a duplicate (the `eventId` is a stable UUID tied to the
   specific change, not regenerated on retry).

## Open questions

- **OQ-1 — `knowledge.updated` payload shape (blocks: AC-5, designer's Kafka producer spec,
  all consumer implementations).** The frozen `libs/event-model` defines exactly nine payload
  types in its `type` enum (envelope.schema.json). There is no `KnowledgeUpdatedEvent` schema
  in `libs/event-model/schema/payloads/` and no entry in either the Java or Python type
  registry. The spec records that `knowledge.updated` is produced and that the envelope fields
  alone (eventId, occurredAt, source, traceId) may suffice as a refresh trigger, but the
  frozen binding must be updated before the designer can specify the Kafka producer
  implementation and before any consumer can be built. This is a contract change requiring
  `docs/architecture.md` update + human approval. Tracked: GitHub issue #22.

- **OQ-2 — Exhaustive enumeration of model parameters (blocks: API schema for params resource,
  validation rules, consumer specs).** The Solution Design §6.3/§6.7/§6.8 names a subset of
  parameters (DBSCAN epsilon/minSamples, session-window gap, PrefixSpan min-support,
  max pattern length, max sequence count) but does not close the list. It is unclear whether
  enrichment thresholds (flap-damp window, dedup window, self-clear hold-time) are also owned
  here or are per-service config. The API contract for the params resource and its validation
  rules cannot be fully specified until the complete MVP parameter set is confirmed. A human
  with visibility across all consuming services must close this list. Tracked: GitHub issue #23.
