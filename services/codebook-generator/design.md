# codebook-generator — Design

> Buildable design for the Codebook Generator Service, authored from the approved, merged
> `spec.md` (PRs #32, #89, #96). Builds on the FROZEN event-model (`TrailsBuiltEvent` and
> `CodebookGeneratedEvent` both carry optional `domain`), the multi-domain contract, and the
> §5 Interface fault-origin model. This is a fresh design; the earlier skeleton PR #79 was
> closed as stale (predated multi-domain / Interface).

The codebook is a **precomputed model** — a matrix of *candidate-root-cause instance to
predicted-symptom signature*, tagged to trails. It is **not** RCA on real data: matching a live
symptom set against the codebook (P3) and reconciling mined patterns against it (P2) are
**downstream** responsibilities of the Correlation Engine and Pattern Manager. This service
compiles and serves the model only.

---

## Stack

- **Language:** Python 3.13 (cohort: Python — graph workload).
- **Graph / propagation:** `networkx` (BSD-3-Clause) — in-memory directed multigraph built from
  Topology query responses; forward-propagation is a typed-edge traversal over it.
- **HTTP API:** FastAPI (MIT) + Uvicorn (BSD) — serves the codebook query API and publishes
  OpenAPI 3.1 at `/openapi.json` plus Swagger UI at `/docs`.
- **Datastore:** PostgreSQL (PostgreSQL licence) — owned Codebook Store (schema `codebook`).
  Access via SQLAlchemy Core (MIT) + a **`pg8000`** (BSD) pure-Python driver (chosen to keep the
  permissive-only invariant strict — `psycopg` is LGPL; selectable only if licence policy is
  relaxed).
- **Kafka client:** `confluent-kafka` (Apache-2.0) consumer/producer.
- **Event model:** `acp-event-model` (the repo's `libs/event-model` Python/Pydantic binding) —
  the single source of truth for `TrailsBuiltEvent`, `CodebookGeneratedEvent`, the envelope,
  the `schemaVersion` policy, and `managedObjectId`.
- **HTTP client (outbound integration):** `httpx` (BSD) with `respx` (BSD) mock transport for
  unit tests.
- **Lint / format / test:** ruff + black, pytest (all permissive).
- **Metrics:** `prometheus-client` (Apache-2.0).

All runtime dependencies are Apache-2.0 / BSD / MIT / PostgreSQL-licensed. No GPL/AGPL/BSL.

---

## Task breakdown (from the spec)

Every spec task (1 to 9) is realized below and is traceable to concrete modules, data, events,
and flow.

| Spec task | Realized by (modules / flow) |
|---|---|
| **1. Consume `trails.built` and trigger compilation** | `consumer.py` (confluent-kafka consumer, group `codebook-generator`) deserializes via `acp_event_model.deserialize`, dedups on envelope `eventId` in `processed_events` table, and invokes `pipeline.compile_codebook(...)`. Unprocessable messages route to `trails.built.dlq` via `dlq.py`. |
| **2. Resolve domain from the event payload** | `domain.py::resolve_domain(event)` reads `payload.domain`; when absent, returns the configured default `core-ip` (env `DEFAULT_DOMAIN`). No Topology snapshot-metadata call is made for domain resolution (OQ-4 resolved by contract #90). |
| **3. Fetch domain-scoped fault-origins + templates from Knowledge** | `clients/knowledge.py` — two integration points: `knowledge-fault-origins` (`GET /domains/{domain}/fault-origins`) and `knowledge-propagation-templates` (`GET /domains/{domain}/propagation-templates`); both pass `domain` as a path/query param. Responses cached per `(domain, knowledgeVersion)`; never authored here. |
| **4. Enumerate domain-scoped fault-origin instances from Topology** | `clients/topology.py::list_objects_by_type(snapshotId, domain, objectType)` — integration point `topology-query`. Iterates the fault-origin type list (incl. **Interface**); every request carries both `snapshotId` and `domain`. |
| **5. Propagate forward, collect predicted symptom sets** | `propagation.py` (networkx engine) — for each fault-origin instance, fetch its bounded graph closure from Topology, build a typed directed graph, run the domain templates forward, accumulate the ordered predicted symptom set (incl. the origin alarm). Handles Fiber, LineCard, Port, **Interface**, Node distinctly (Algorithm logical flow below). |
| **6. Tag each scenario with its trail(s)** | `tagging.py` — for each symptom `managedObjectId`, call `clients/trail_builder.py::get_trails_for_object(...)`; union the `trailIds`; attach to the scenario. Integration point `trail-builder-trails`. |
| **7. Persist codebook with domain** | `store.py` — inserts one `codebooks` row (fresh `codebookId`, `snapshotId`, `domain` non-null, `scenarioCount`) and N `scenarios` rows in one transaction. New `snapshotId` mints a new `codebookId`; never overwrites a prior snapshot's codebook. |
| **8. Emit `codebook.generated` with domain** | `producer.py` builds a `CodebookGeneratedEvent` (`snapshotId`, `scenarioCount`, `codebookId`, `domain`) via the Pydantic binding, wraps in the envelope, publishes to `codebook.generated`. Failed publish routes to `codebook.generated.dlq`. |
| **9. Serve the domain-scoped query API** | `api.py` (FastAPI) — `GET /codebooks/{codebookId}`, `/codebooks/{codebookId}/scenarios`, `/codebooks/{codebookId}/scenarios/{scenarioId}`, `/codebooks?snapshotId=`, `/codebooks?domain=`, plus `/health` and `/metrics`. Reads the Codebook Store; every response carries `domain`. OpenAPI 3.1 published + checked in. |

---

## Phase applicability (design view)

Consistent with the canonical phase map (`architecture.md`) and the spec's Phase applicability:
**P1 Active**, **P2 Passive**, **P3 Passive**.

| Phase | Active/Passive/Idle | Modules/handlers exercised | Inputs/Outputs |
|---|---|---|---|
| P1 — Topology onboarding | **Active** | `consumer.py` (trails.built), `domain.py`, `clients/knowledge.py`, `clients/topology.py`, `propagation.py`, `tagging.py`, `clients/trail_builder.py`, `store.py` (write), `producer.py` | In: `trails.built` (snapshotId, trailIds, domain), Topology query API (domain-scoped enumerate + closure), Knowledge fault-origins API, Knowledge templates API, Trail Builder API reads. Out: `codebook.generated` (snapshotId, scenarioCount, codebookId, domain); Codebook Store writes |
| P2 — Pattern learning | **Passive** | `api.py` read endpoints, `store.py` (read). Compilation pipeline dormant unless a new `trails.built` arrives | In: codebook query API requests from Pattern Manager (reconcile; may filter by domain). Out: codebook query API responses (scenario signatures, trail tags, RCA-per-scenario, domain). No topic output |
| P3 — Real-time correlation | **Passive** | `api.py` read endpoints (esp. full-signature fetch for closest-match decode), `store.py` (read). Compilation pipeline dormant | In: codebook query API requests from Correlation Engine (match; may filter by domain; codebook id also delivered via `codebook.generated`). Out: codebook query API responses (scenario signatures + RCA, domain, scoped by trail/snapshot). No topic output |

---

## Module breakdown

```mermaid
flowchart TD
  KAFKA_IN["trails.built topic"] --> CONS["consumer.py, dedup on eventId"]
  CONS --> PIPE["pipeline.py, compile orchestration"]
  PIPE --> DOM["domain.py, resolve domain from event"]
  PIPE --> KN["clients knowledge.py, fault-origins plus templates"]
  PIPE --> TOPO["clients topology.py, enumerate plus closure"]
  PIPE --> PROP["propagation.py, networkx forward-propagation"]
  PIPE --> TAG["tagging.py, plus clients trail_builder.py"]
  PIPE --> STORE["store.py, Codebook Store writer"]
  PIPE --> PROD["producer.py, codebook.generated"]
  PROD --> KAFKA_OUT["codebook.generated topic"]
  STORE --> PG[("PostgreSQL schema codebook")]
  API["api.py, FastAPI query API"] --> STORE
  CONS -. unprocessable .-> DLQ1["trails.built.dlq"]
  PROD -. publish-fail .-> DLQ2["codebook.generated.dlq"]
```

- **`consumer.py`** — Kafka consumer loop; deserialize, dedup, dispatch, commit-after-success;
  DLQ routing. Manual offset commit only after the pipeline succeeds (at-least-once).
- **`pipeline.py`** — orchestrates one compilation cycle (tasks 2 to 8); transactional persist;
  emits the event last.
- **`domain.py`** — `resolve_domain` (task 2).
- **`clients/knowledge.py`, `clients/topology.py`, `clients/trail_builder.py`** — config-switchable
  HTTP clients (mock/real); built against each producer's published OpenAPI.
- **`propagation.py`** — networkx forward-propagation engine; pure function of (closure graph,
  templates, origin) to ordered symptom signature. No I/O.
- **`tagging.py`** — trail-tag resolution per scenario.
- **`store.py`** — Codebook Store reader/writer (SQLAlchemy Core).
- **`producer.py`** — `codebook.generated` producer + DLQ fallback.
- **`api.py`** — FastAPI app, query endpoints, `/health`, `/metrics`, `/openapi.json`.
- **`vocabulary.py`** — the shared alarm-type vocabulary mapping (see OQ-2 note below).
- **`config.py`** — env-only config; fails fast on missing integration-point URLs.

---

## Data model / DB schema

Owned store: PostgreSQL, schema `codebook`. No other service writes it. Three domain tables plus
an idempotency table.

```mermaid
erDiagram
  CODEBOOKS ||--o{ SCENARIOS : contains
  CODEBOOKS {
    text codebook_id PK
    text snapshot_id
    text domain
    int scenario_count
    text knowledge_version
    timestamptz compiled_at
  }
  SCENARIOS {
    text scenario_id PK
    text codebook_id FK
    text fault_origin_object_id
    text fault_origin_type
    jsonb predicted_symptoms
    text_array trail_ids
  }
  PROCESSED_EVENTS {
    text event_id PK
    text codebook_id
    timestamptz processed_at
  }
```

**`codebook.codebooks`**

| Column | Type | Notes |
|---|---|---|
| `codebook_id` | `text` PK | freshly minted per compilation (`cb-{uuid4}`) |
| `snapshot_id` | `text` NOT NULL | from the triggering `trails.built` |
| `domain` | `text` NOT NULL | first-class; from resolved domain (default `core-ip`) |
| `scenario_count` | `integer` NOT NULL | equals count of related `scenarios` |
| `knowledge_version` | `text` | version of fault-origins/templates used (provenance) |
| `compiled_at` | `timestamptz` NOT NULL DEFAULT now() | |

Indexes (lead with domain): `idx_codebooks_domain_compiled (domain, compiled_at DESC)`,
`idx_codebooks_snapshot (snapshot_id)`. **Regeneration mints a new `codebook_id`** — a new
`snapshot_id` never overwrites a prior snapshot's codebook (no upsert on snapshot).

**`codebook.scenarios`**

| Column | Type | Notes |
|---|---|---|
| `scenario_id` | `text` PK | `{codebook_id}:{fault_origin_object_id}` (stable within a codebook) |
| `codebook_id` | `text` NOT NULL FK to `codebooks` | ON DELETE CASCADE |
| `fault_origin_object_id` | `text` NOT NULL | the candidate-root-cause `managedObjectId` |
| `fault_origin_type` | `text` NOT NULL | e.g. `Fiber`, `LineCard`, `Port`, `Interface`, `Node` |
| `predicted_symptoms` | `jsonb` NOT NULL | ordered list of objects with `alarmType` and `managedObjectId` (the signature; ordering preserved for cascade depth) |
| `trail_ids` | `text[]` NOT NULL | the tagged trails; non-empty when Trail Builder returns trails |

Indexes: `idx_scenarios_codebook (codebook_id)`,
`idx_scenarios_origin (codebook_id, fault_origin_object_id)`.

**`codebook.processed_events`** (idempotency): `event_id` PK, `codebook_id`, `processed_at`.
A `trails.built` whose `event_id` already exists is a no-op — the existing codebook is preserved
and the prior `codebook.generated` is re-emitted (idempotent producer key = `eventId`).

The signature stores both the `alarmType` (from the shared vocabulary) and the
`managedObjectId` of the symptom-bearing object, so Correlation/Pattern Manager can match either
by alarm-type alone (decode) or by object identity, and trail-tagging can resolve each object.

---

## Event handling

- **Consumers:**
  - `trails.built` to `consumer.py`. Dedup key: envelope `eventId` (`processed_events`). Unknown
    major `schemaVersion` (at least 2) rejected via `check_schema_version` to `trails.built.dlq`.
    Malformed/undeserializable or missing `snapshotId` to `trails.built.dlq`. Offset committed
    only after a successful compile + emit (at-least-once; dedup makes redelivery safe).
  - `knowledge.updated` (**design decision: SUBSCRIBED, as a cache-invalidation trigger**). On a
    `KnowledgeUpdatedEvent`, invalidate the cached fault-origins/templates for that event's
    `domain` only (domain-scoped invalidation; the next compile re-fetches). Dedup on `eventId`;
    unprocessable to `knowledge.updated.dlq`. This does **not** trigger recompilation (compilation
    is triggered only by `trails.built`) — it just keeps the per-domain cache fresh. Rationale in
    Design alternatives.
- **Producers:**
  - `codebook.generated` to `CodebookGeneratedEvent` (`snapshotId`, `scenarioCount`, `codebookId`,
    `domain`) from `libs/event-model`. Producer key = `codebookId`. Failed delivery to
    `codebook.generated.dlq` (with the original payload + error metadata).

---

## API contracts / API schema

FastAPI app. OpenAPI 3.1 generated at `/openapi.json`, Swagger UI at `/docs`; the generated
document is checked in at `services/codebook-generator/openapi.json` (a CI check asserts the
checked-in file equals the live `/openapi.json`). The service's own spec drives its
contract/unit tests (response bodies validated against the published schema).

Domain-scoped. All read-only (this service is the sole writer, via the Kafka pipeline).

| Method / path | Request | Response 200 | Errors |
|---|---|---|---|
| `GET /codebooks/{codebookId}` | path `codebookId` | `Codebook` metadata with `codebookId, snapshotId, domain, scenarioCount, knowledgeVersion, compiledAt` | `404` structured `error, detail` when unknown |
| `GET /codebooks/{codebookId}/scenarios` | path `codebookId`; optional query `faultOriginType` | object with `codebookId, domain, scenarios` where each `Scenario` has `scenarioId, faultOriginObjectId, faultOriginType, predictedSymptoms` (list of `alarmType, managedObjectId`), `trailIds` | `404` |
| `GET /codebooks/{codebookId}/scenarios/{scenarioId}` | path | single `Scenario` (incl. `predictedSymptoms` + `trailIds`) | `404` |
| `GET /codebooks?snapshotId={snapshotId}` | query `snapshotId` | object with `snapshotId, codebooks` (list for extensibility; typically one) | `200` empty list when none |
| `GET /codebooks?domain={domain}` | query `domain` | object with `domain, codebooks` ordered by `compiledAt` desc | `400` if neither `snapshotId` nor `domain` given on `/codebooks` |
| `GET /health` | — | `status ok` (readiness checks DB + Kafka) | `503` if not ready |
| `GET /metrics` | — | Prometheus text exposition | — |

`/codebooks` dispatches on the query param (`snapshotId` xor `domain`). Schemas reuse the
`predictedSymptoms` shape from the store; the `CodebookGeneratedEvent` summary fields
(`snapshotId`, `scenarioCount`, `codebookId`, `domain`) map 1:1 to the `Codebook` metadata
object so consumers can correlate the event with the API.

---

## Integration points (mock vs. real)

Four outbound integration points, each resolved by env (no hard-coded URLs). Each has a base-URL
var and a `MODE` toggle (`MOCK` or `REAL`). Mocks are `respx`/`httpx` mock transports generated
from the collaborator's **published OpenAPI** (used in unit tests); real points to the live
Docker Compose service (integration tests). Same code in both modes.

| Integration point | Collaborator / operation | Config keys | Mock (unit) / Real (integration) |
|---|---|---|---|
| `topology-query` | Topology Service — list objects by type (snapshotId + domain scoped) and bounded traverse by edge type (closure) | `TOPOLOGY_QUERY_URL`, `TOPOLOGY_QUERY_MODE` | Mock = respx stub from Topology OpenAPI / Real = live Topology |
| `knowledge-fault-origins` | Knowledge — domain fault-origin type list | `KNOWLEDGE_FAULT_ORIGINS_URL`, `KNOWLEDGE_FAULT_ORIGINS_MODE` | Mock from Knowledge OpenAPI / Real |
| `knowledge-propagation-templates` | Knowledge — domain propagation templates | `KNOWLEDGE_PROPAGATION_TEMPLATES_URL`, `KNOWLEDGE_PROPAGATION_TEMPLATES_MODE` | Mock / Real |
| `trail-builder-trails` | Trail Builder — `getTrailsForObject(managedObjectId)`, `getTrail(trailId)` | `TRAIL_BUILDER_URL`, `TRAIL_BUILDER_MODE` | Mock / Real |

Topology reads are **API-only** — this service never holds AGE credentials or runs openCypher
(single-owner invariant). Mock responses are **domain-parameterized** (Core IP mock returns
Fiber/LineCard/Port/Interface/Node; a `transport` mock returns its own types) so domain-scoped
behaviour is verified without live services.

**OQ dependencies (design-stage):** the exact Topology `list objects` + `traverse` endpoint
shapes (OQ-3, issue #31) and the Trail Builder `getTrailsForObject` shape (OQ-1, issue #28)
resolve when those services' OpenAPI specs are frozen; the client + mock are then built against
those specs. The integration-point contract (domain-scoped enumerate + closure; trail-per-object
lookup) is firm. If Topology cannot scope by `domain`, the codebook-generator filters client-side
by `objectType` namespace and the constraint is recorded — no behaviour change visible to tests.

---

## Key flows (sequence / data-flow diagrams)

### Flow A — Compile codebook (P1, on `trails.built`)

```mermaid
sequenceDiagram
  participant K as trails.built topic
  participant C as Codebook Generator
  participant KN as Knowledge Service
  participant TO as Topology Service
  participant TB as Trail Builder
  participant DB as Codebook Store
  participant OUT as codebook.generated topic
  K->>C: TrailsBuiltEvent, snapshotId, trailIds, domain
  C->>C: dedup on eventId, resolve domain or default core-ip
  C->>KN: GET fault-origins, domain param
  KN-->>C: type list, Fiber LineCard Port Interface Node
  C->>KN: GET propagation-templates, domain param
  KN-->>C: edge cascade rules
  loop for each fault-origin type
    C->>TO: list objects by type, snapshotId and domain
    TO-->>C: instances
  end
  loop for each instance
    C->>TO: traverse closure, bounded by edge types
    TO-->>C: typed subgraph
    C->>C: forward-propagate, build ordered symptom signature
    C->>TB: getTrailsForObject for each symptom object
    TB-->>C: trailIds
  end
  C->>DB: insert codebook plus scenarios, mint codebookId, domain
  DB-->>C: committed
  C->>OUT: CodebookGeneratedEvent, snapshotId scenarioCount codebookId domain
```

### Flow B — Codebook query (P2 reconcile, P3 match)

```mermaid
sequenceDiagram
  participant PM as Pattern Manager, P2 reconcile
  participant CE as Correlation Engine, P3 match
  participant API as Codebook Query API
  participant DB as Codebook Store
  PM->>API: GET codebooks, domain filter
  API->>DB: select by domain, latest compiledAt
  DB-->>API: codebook metadata
  API-->>PM: codebooks with domain
  PM->>API: GET codebook scenarios by codebookId
  API->>DB: select scenarios
  DB-->>API: signatures plus trailIds
  API-->>PM: scenario signatures for reconcile
  CE->>API: GET codebook scenarios by codebookId, full signatures
  API->>DB: select scenarios
  DB-->>API: signatures
  API-->>CE: full signatures for closest-match decode
```

---

## Algorithm logical flow

**Forward propagation** is a typed-edge BFS over the per-instance graph closure, driven by the
Knowledge propagation templates (never hard-coded). Inputs: an origin instance, its closure
(typed directed graph from Topology), and the domain templates (each template names an edge type,
a source state, and an effect state on the target type). Output: an ordered predicted symptom
signature (origin alarm first, then effects in cascade order). Parameters (fault-origin set,
templates, edge types) all come from Knowledge.

```mermaid
flowchart TD
  START["origin instance, type T, with closure graph"] --> ORIG["emit origin alarm, by vocabulary for T"]
  ORIG --> Q["frontier equals origin node, with its initial state"]
  Q --> CHK{"frontier empty"}
  CHK -- yes --> DONE["return ordered signature, dedup preserving first-seen order"]
  CHK -- no --> POP["pop node N with state S"]
  POP --> MATCH["find templates where edge type out of N and source state equals S"]
  MATCH --> APPLY["for each matched out-edge, compute effect alarm on target M"]
  APPLY --> ADD["append effect alarm to signature, push M with effect state"]
  ADD --> CHK
```

**Per-fault-origin cascade specifics** (Core IP templates from §5; vocabulary per OQ-2 note):

- **Fiber (fiber-cut):** origin alarm on Fiber, then `RIDES_ON` gives `LinkDown(IPLink)`, then
  `TRAVERSES` gives `LSPDown(LSP)`, then `SERVES` gives `ReachabilityLoss(VPN)`. Signature:
  `[Fiber-alarm, LinkDown, LSPDown, ReachabilityLoss]`. **No `InterfaceDown`** (distinguishes it
  from interface/port faults).
- **Interface (interface-fault):** origin `InterfaceDown`, then `TERMINATES` gives
  `LinkDown(IPLink)`; `ADJACENCY_OVER` gives `AdjDown(IGPAdjacency)`; then `TRAVERSES` gives
  `LSPDown`, `SERVES` gives `ReachabilityLoss`. Signature:
  `[InterfaceDown, LinkDown, AdjDown, LSPDown, ReachabilityLoss]`. **Starts at `InterfaceDown`**
  (no `PortDown` above it) — distinguishes it from a port fault.
- **Port (port-fault):** origin `PortDown`, then `HOSTS` gives `InterfaceDown(each Interface)`,
  then the interface cascade (`TERMINATES`, `ADJACENCY_OVER`, and so on). Signature contains
  `PortDown` **above** `InterfaceDown` — distinguishes it from an interface fault (which has no
  `PortDown`).
- **LineCard (line-card-fault):** origin `LineCard-alarm`, then `HOSTED_ON` gives
  `PortDown(each Port)`, then each port's `HOSTS`/interface cascade. Signature contains
  **multiple `PortDown`** entries (one per hosted Port) — distinguishes it from a single Port
  fault.
- **Node:** origin `Node-alarm`, then all hosted LineCards/Ports/Interfaces cascade (node-wide
  closure).

Distinguishability is structural: `InterfaceDown` presence, whether `PortDown` precedes it (port
vs. interface vs. line-card), and the `PortDown` count (line-card vs. port) yield distinct
signatures. `MEMBER_OF` (SRLG fate-sharing) groups co-failing origins but is co-failure grouping,
not a forward cascade edge in MVP (protection-aware FRR/ECMP is out of scope per spec).

**Alarm-type vocabulary (OQ-2, design-stage):** signatures use the **shared alarm-type
vocabulary** coordinated with Knowledge (the template author) — the effect identifiers
`InterfaceDown, LinkDown, AdjDown, LSPDown, PortDown, LOS, ReachabilityLoss` map to the
`AlarmEvent.eventType` field (per the OQ-2 resolution comment). They are **read from the
propagation-template effect names returned by Knowledge** at runtime — `vocabulary.py` holds only
the *mapping convention* (effect name to which `AlarmEvent` field), not a hard-coded Core IP list.
No new `libs/event-model` field is introduced (no contract change); if a future domain needs a
new alarm-type field that is escalated as a contract change.

---

## Seed data & examples

N/A — not a seed-data service. A small illustrative fixture used by unit tests (not seed data):
a Core IP closure with `Fiber:f1 RIDES_ON IPLink:l1 TRAVERSES LSP:s1 SERVES VPN:v1`, and
`Port:p1 HOSTS Interface:i1 TERMINATES IPLink:l1`, `Interface:i1 ADJACENCY_OVER
IGPAdjacency:a1`, used to assert the cascade signatures in tests 1 to 3.

## UI wireframes

N/A — no UI; back-end service.

---

## Error handling

| Failure mode | Handling |
|---|---|
| Undeserializable / malformed `trails.built` (e.g. missing `snapshotId`) | Route raw message to `trails.built.dlq` with error metadata; log structured error; commit offset; continue with next message (no infinite retry) |
| Unknown major `schemaVersion` (at least 2) | `check_schema_version` raises; message rejected (not processed) to `trails.built.dlq`; service does not crash |
| Duplicate `eventId` | No-op: existing codebook preserved; prior `codebook.generated` re-emitted; compile runs at most once |
| Integration point unavailable / 5xx (Topology, Knowledge, Trail Builder) | Retry with bounded exponential backoff (config `INTEGRATION_MAX_RETRIES`, `INTEGRATION_BACKOFF_MS`); on exhaustion log structured error and route the triggering `trails.built` to `trails.built.dlq` (no partial codebook persisted — the persist is transactional and only runs on full success) |
| Integration point 4xx (bad request / not found) | Treated as unrecoverable for that compile, route to `trails.built.dlq` with detail; logged |
| `domain` absent on event | Default to `core-ip` (env `DEFAULT_DOMAIN`); logged at info with `domain` field |
| Trail Builder returns no trails for a symptom object | Scenario keeps the union of trails found across its symptoms; if none, `trailIds` is empty (logged as a warning; not fatal). Criterion 4 asserts non-empty when the mock returns at least one |
| `codebook.generated` publish failure | Route payload to `codebook.generated.dlq`; the codebook is already persisted, so it remains queryable; logged |
| Empty enumeration (no fault-origin instances) | Compile a codebook with `scenarioCount` of 0; persist; emit event with count 0 (valid, not an error) |
| API: unknown `codebookId`/`scenarioId` | `404` with structured `error, detail` |
| API: `/codebooks` with neither `snapshotId` nor `domain` | `400` structured error |
| DB unavailable | `/health` returns `503`; consumer pauses (does not commit); retried |

Nothing is silently dropped: every drop is either DLQ-routed or returned as a structured error,
and logged with `traceId`, `domain`, and `snapshotId`.

---

## Design alternatives

| Consideration | Alternatives considered | Chosen + rationale |
|---|---|---|
| Graph representation for propagation | (a) re-query Topology per cascade hop; (b) build an in-memory networkx graph from a bounded closure fetch per instance | (b). networkx keeps propagation a pure in-memory traversal (testable, no I/O in the hot loop); closure is bounded by relevant edge types so it stays small. Fewer round-trips than (a) |
| Domain resolution | (a) Topology snapshot-metadata lookup; (b) read `domain` off the event | (b). Contract #90 puts `domain` on `TrailsBuiltEvent`; reading it avoids a synchronous Topology call (OQ-4). Default `core-ip` covers pre-#90 events |
| `knowledge.updated` subscription | (a) ignore (re-fetch every compile); (b) subscribe for domain-scoped cache invalidation; (c) subscribe and eagerly recompile | (b). Caching fault-origins/templates avoids re-fetching on every compile; domain-scoped invalidation keeps the cache correct without coupling compilation to Knowledge changes. (c) rejected — recompilation is triggered only by `trails.built` (spec), not by Knowledge changes |
| Signature element shape | (a) alarm-type string only; (b) pair of `alarmType` and `managedObjectId` | (b). Correlation needs object identity for trail-scoped decode and Pattern Manager for RCA-per-object; the alarm-type-only view is derivable from (b) |
| Regeneration semantics | (a) upsert by `snapshotId`; (b) always mint a new `codebookId` | (b). Spec requires a new `snapshotId` to mint a new `codebookId` and never overwrite a prior snapshot's codebook; supports historical query and `matchedCodebookId` stability downstream |
| Postgres driver | (a) `psycopg` (LGPL); (b) `pg8000` (BSD) | (b) default — keeps the permissive-only invariant strict (LGPL excluded). `psycopg` selectable only if licence policy is relaxed |
| Trail tagging granularity | (a) tag by origin object only; (b) union trails across all symptom objects | (b). A scenario's symptoms span multiple objects/trails; tagging by the full symptom set yields correct trail membership for decode |

---

## Test plan

Framework: **pytest** (Python cohort). Integration points mocked via `respx`/`httpx` (from
collaborators' OpenAPI). Codebook Store tested against a Postgres test container (or `pg8000` +
ephemeral DB). Every acceptance criterion maps 1:1 to a named test.

### Acceptance criterion → test (unit/contract)

| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| 1 | Fiber-cut signature matches expected cascade | `test_fiber_cut_signature_matches_expected_cascade` | Scenario for `Fiber:f1` has ordered symptoms `[Fiber-alarm, LinkDown(IPLink), LSPDown(LSP), ReachabilityLoss(VPN)]` (no `InterfaceDown`) |
| 2 | Line-card vs port faults produce distinguishable signatures | `test_linecard_and_port_signatures_distinguishable` | LineCard scenario has multiple `PortDown` entries absent from the Port scenario; Port scenario has its port-layer discriminator absent from the LineCard top-level signature |
| 3 | Interface fault-origin scenario matches the interface cascade | `test_interface_fault_signature_matches_expected_cascade` | Scenario for `Interface:i1` has `faultOriginType` of `Interface` and ordered symptoms `[InterfaceDown, LinkDown, AdjDown, LSPDown, ReachabilityLoss]`; differs from fiber-cut (no InterfaceDown) and port-fault (PortDown precedes InterfaceDown there) |
| 4 | Every scenario tagged to at least one trail | `test_every_scenario_has_nonempty_trailids` | With a mock Trail Builder returning at least one trailId per object, every scenario has non-empty `trailIds` |
| 5 | Regeneration produces a new codebook tied to the new snapshotId | `test_regeneration_mints_new_codebook_per_snapshot` | Two events (`snap-A`, `snap-B`) give two records, distinct `codebookId`; `snap-B` record carries `snapshotId` of `snap-B`; two `codebook.generated` events emitted, each validating against `CodebookGeneratedEvent` |
| 6 | Duplicate `trails.built` deduplicated | `test_duplicate_eventid_compiles_once` | Same `eventId` twice gives compile once, emit `codebook.generated` once |
| 7 | All outbound calls via config-switchable integration points | `test_full_cycle_in_mock_mode_no_real_http` + `test_missing_integration_url_fails_startup` | With all `*_MODE=MOCK`, full compile uses mocks (no real HTTP); with any integration URL unset, startup refuses + logs structured config error |
| 8 | Query API returns signature + trail tags by codebookId | `test_get_scenario_returns_signature_and_trails` | `GET /codebooks/{id}/scenarios/{sid}` returns correct `predictedSymptoms` + `trailIds`, `200`, validates against published `openapi.json` |
| 9 | `codebook.generated` carries domain + validates against binding | `test_codebook_generated_event_carries_domain_and_validates` | Emitted payload deserializes via `CodebookGeneratedEvent`; `scenarioCount` matches persisted scenarios; non-empty `codebookId`; `domain` matches triggering event |
| 10 | `domain` read from event — no Topology lookup | `test_domain_read_from_event_no_topology_metadata_call` | With `domain` of `core-ip` on event, snapshot-metadata endpoint receives zero calls (mock assertion) |
| 11 | Unknown `schemaVersion` rejected | `test_unknown_schemaversion_routed_to_dlq` | `schemaVersion` of at least 2 not processed, routed to `trails.built.dlq`, no crash |
| 12 | Unprocessable `trails.built` to DLQ | `test_malformed_trailsbuilt_routed_to_dlq` | Missing `snapshotId` routed to `trails.built.dlq`, not retried indefinitely, consumer continues |
| 13 | Compiled record carries the snapshot's domain | `test_persisted_codebook_and_metadata_carry_domain` | Persisted record `domain` of `core-ip` (non-null); `GET /codebooks/{id}` includes `domain` of `core-ip`, validates against `openapi.json` |
| 14 | Knowledge calls carry domain param | `test_knowledge_calls_include_domain_param` | Both Knowledge integration points called with `domain=core-ip`; no domain-specific data hard-coded |
| 15 | Domain-scoped enumeration to Topology | `test_topology_enumeration_includes_snapshot_and_domain` | Topology `list objects` called with `snapshotId=snap-X` AND `domain=core-ip` (mock assertion) |
| 16 | Domain-scoped query API filters by domain | `test_codebooks_query_filters_by_domain` | Two codebooks (`core-ip` CB-1, `transport` CB-2); `GET /codebooks?domain=core-ip` returns only CB-1; validates against `openapi.json` |
| 17 | Different domain compiles without code change | `test_transport_domain_compiles_with_its_inputs` | With `transport` mock fault-origins/templates/topology, a full `transport` codebook compiles with no code change; persisted `domain` of `transport` |

### E2E scenarios (from this design unit's point of view)

Service-scoped end-to-end paths the integration stage exercises (codebook-generator + real
collaborators in Docker Compose), including failure/partial paths.

| # | Scenario | Trigger → path | Expected outcome |
|---|---|---|---|
| 1 | Core IP codebook compile (happy path) | `trails.built(snap-X, core-ip)`, then fetch fault-origins+templates (Knowledge), enumerate incl. Interface (Topology), forward-propagate, tag trails (Trail Builder), persist, emit | One codebook persisted with `domain=core-ip`; `codebook.generated` emitted with matching `scenarioCount`/`codebookId`/`domain`; scenarios queryable via API incl. fiber-cut and interface cascades |
| 2 | Interface-fault scenario present and distinct | same trigger; assert via query API | A scenario with `faultOriginType=Interface` and the `[InterfaceDown,...]` signature exists and differs from the fiber-cut and port-fault scenarios |
| 3 | Regenerate on new snapshot | `trails.built(snap-A)` then `trails.built(snap-B)` | Two codebooks, distinct `codebookId`; both queryable; two `codebook.generated` events |
| 4 | Poison trigger to DLQ, recovery | malformed `trails.built` then a valid one | First to `trails.built.dlq`; second compiles normally; consumer never stalls |
| 5 | Collaborator down (partial path) | Knowledge unavailable during compile | Retries with backoff; on exhaustion the trigger to `trails.built.dlq`, no partial codebook persisted, structured error logged; service stays up |
| 6 | Domain pass-through to downstream | compile `core-ip`, inspect `codebook.generated` | Event carries `domain=core-ip` so Pattern Manager/Correlation get domain without an API lookup |
| 7 | Query serving for P2/P3 | compile, then `GET /codebooks?domain=core-ip`, `/codebooks/{id}/scenarios` | API returns codebook metadata + full signatures + trail tags for reconcile/match |

---

## Config & observability

**Config (env only; no hard-coded URLs/thresholds/domains):**
`KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP` (default `codebook-generator`),
`TRAILS_BUILT_TOPIC`, `CODEBOOK_GENERATED_TOPIC`, `KNOWLEDGE_UPDATED_TOPIC` (optional subscribe),
DLQ topic names; `DATABASE_URL` (Postgres); `DEFAULT_DOMAIN` (default `core-ip`);
`TOPOLOGY_QUERY_URL`/`_MODE`, `KNOWLEDGE_FAULT_ORIGINS_URL`/`_MODE`,
`KNOWLEDGE_PROPAGATION_TEMPLATES_URL`/`_MODE`, `TRAIL_BUILDER_URL`/`_MODE`;
`INTEGRATION_MAX_RETRIES`, `INTEGRATION_BACKOFF_MS`; `LOG_LEVEL`. Startup fails fast (exit non-zero
+ structured error) if any required integration-point URL is unset (criterion 7).

**Observability:**
- `/health` — liveness + readiness (DB reachable, Kafka consumer assigned).
- `/metrics` — Prometheus: `codebook_events_consumed_total`, `codebook_compiled_total`,
  `codebook_scenarios_generated_total`, `codebook_errors_total`, `codebook_dlq_routed_total`,
  and integration-point latency histograms — labelled by `domain` where applicable.
- Structured JSON logs: `level, timestamp, traceId, service, message, domain, snapshotId`.

## Build & run

- **Build:** `pip install -e services/codebook-generator` (or `uv pip install`); lint
  `ruff check` + `black --check`; test `pytest services/codebook-generator/tests`.
- **OpenAPI:** generated at `/openapi.json`; `scripts/dump_openapi.py` writes
  `services/codebook-generator/openapi.json` (CI asserts it matches the live spec).
- **Dockerfile:** base `python:3.13-slim` (pinned per CLAUDE.md); installs the service +
  `libs/event-model` Python binding; entrypoint runs the consumer loop + Uvicorn API.
- **Compose:** entry `codebook-generator` depends on `kafka`, `postgres`; integration-point URLs
  point at the real `topology`, `knowledge`, `trail-builder` services on the `integration` stack;
  `*_MODE=REAL` there, `MOCK` in unit-test CI.
- **Local run:** `uvicorn codebook_generator.api:app` for the API; the consumer is a separate
  entrypoint (`python -m codebook_generator.consumer`); both share `config.py`.
