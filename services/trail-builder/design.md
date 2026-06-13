# trail-builder — Design

Buildable design for the Trail Builder Service, derived from the approved, merged
`services/trail-builder/spec.md` (PRs #33, #88, #93) on the `trail-builder` branch. Honours
the contract-first, single-owner, idempotency, DLQ, `/health`+`/metrics`, and
permissive-license invariants. Reads the Topology graph via its query API only (never the topology
graph store directly — Topology is the single owner of that store).

The contract surface this design builds against (`topology.changed` carrying `domain`,
`TrailsBuiltEvent` carrying `domain`, the §5 Interface model with `HOSTS` / `TERMINATES`) is
already FROZEN in `libs/event-model` and merged into `docs/architecture.md`. No contract change
is proposed here.

**Data-integration API freeze (prior revision).** Applies the producer-side fixes for three
data-integration gaps where Trail Builder's published query-API shape/paths diverged from its
consumers' (Codebook Generator, Enrichment, Noise Filter, web-ui) expectations. Trail Builder's
checked-in `openapi.json` is the **single source of truth**; that revision FROZE the paths and
response schemas of `getTrail`, `getTrailsForObject`, and `listTrails` so consumers align to them
(consumer-side alignment is handled in those services' own fixes). Gaps addressed: **P1-G4**
(getTrail/getTrailsForObject response schemas), **P1-G10** (getTrailsForObject endpoint path),
**P2-GAP-09** (`snapshotId` guaranteed on getTrail). This is **service-owned API surface only** —
no Kafka topic or event-model change; `TrailsBuiltEvent` is unchanged.

**Design-readiness fixes (this revision).** Two cross-service-consistency fixes, both
**service-owned, no contract change** (`TrailsBuiltEvent` and every topic unchanged):

- **Q1 + Q7 — `domain` required-param reconciliation (query API vs. event path).** The frozen
  `getTrailsForObject` (`GET /trails/by-object?managedObjectId=&domain=`) makes `domain` a
  **strictly REQUIRED** query parameter with a **clear 400** when missing. This revision removes
  the prior ambiguity where a `core-ip` default-domain fallback was described as if it applied
  broadly: the default-domain fallback is **scoped to the Kafka event-ingestion path only** (a
  legacy `topology.changed` whose optional `domain` is absent), and **never** to the HTTP query
  API. The query API has **no domain default** — a missing `domain` is a 400. This is exactly the
  call shape the live consumers now send: **Enrichment** (`enrichment-readiness-fixes`) and
  **Codebook Generator** (`codebook-readiness-fixes`) both pass `domain` on every
  `getTrailsForObject` call (both treat `domain` as required, sourcing it from their own resolved
  domain — Enrichment from `ENRICHMENT_DOMAIN`, Codebook from the event's resolved `domain`). The
  endpoint is therefore aligned with the actual consumer call shape and future-proof for
  multi-domain. The frozen by-object response shape `{ managedObjectId, domain, trailIds }` is
  unchanged.
- **Q3 — Topology call shapes pinned to the FROZEN Topology query API + snapshot pinning.** The
  graph-closure reads this service makes are pinned to Topology's **frozen** query-API paths,
  required params (incl. `snapshotId=current|previous` scoping), and DTOs (`NodeListDto`,
  `NeighborsDto`, `TraversalDto`, `NodeDto`). Trail Builder builds its Topology client against
  these frozen shapes; the producer's checked-in `services/topology/openapi.json` is the
  build-time artifact, and referencing the frozen shape here is sufficient. The `snapshotId`
  persisted on every trail and emitted on `trails.built` is the snapshot **carried by the
  triggering `topology.changed` event** (the in-scope snapshot), consistent with Topology's
  current/previous `snapshotId` model — never re-resolved by a Topology lookup.

**MVP-achievability fix (this revision) — exercise the IGP-area-bounded closure on real data.**
A **data-only, no-contract-change** fix that closes the cross-service gap the MVP-achievability
gate flagged (`docs/mvp-achievability.md`, P1 row "Trail Builder builds trails…"): the
load-bearing IGP-area prune in trail closure (step 4 below — prune members whose `igpArea`
differs from the seed area) was **inert** because no P1 producer populated `igpArea`, so closure
spanned the **entire connected dependency component** (coarse, whole-network trails that violate
the no-whole-network-trail property **AC-2** on real data), and the unit tests passed only
because their fixtures **injected** `igpArea` — masking the gap. The fix is upstream and now
landed: **Knowledge** added `igpArea` to the `core-ip/attributeCatalogue/default` deviceKeys and
its `trailPolicy/default` bounds closure on `boundary` of type `igp-area` with `attributeKey`
`igpArea` (`design/knowledge-mvp-grounding`); the **Simulator** emits a grounded
per-`Node`/per-`Interface` `igpArea` (one `area-0` backbone plus numbered edge areas,
`IGP_AREA_COUNT` default 3, `design/simulator-mvp-grounding`) carried in each node's `attributes`.
The **Trail Builder side** of this revision: (a) confirms and states that `TrailClosure` reads
`igpArea` from the Topology node attributes (`NodeDto.attributes.igpArea`) per the trail-policy
`igpArea` boundary key and step 4 prunes cross-area members — and that this **now actually fires**
because the data carries `igpArea` (previously inert); and (b) adds an **integration assertion
that runs on Simulator-generated topology** (grounded `igpArea` on real nodes — **not**
`igpArea`-injected unit fixtures) so the area-bound and AC-2 hold on a real multi-area topology
and the gap cannot silently regress (Test plan, integration assertion **INT-IGPAREA**; E2E
scenario 15). **No Kafka topic or event-model change**: `igpArea` is a descriptive node attribute
carried within the already-frozen `NodeDto.attributes` map; `TrailsBuiltEvent` and every topic
are unchanged.

## Stack

- **Language / runtime:** Python 3.13 (pinned to the local toolchain per `CLAUDE.md`).
- **Graph closure:** `networkx` (BSD-3-Clause) — in-memory directed multigraph for the
  per-snapshot graph slice and the transitive-closure traversal.
- **Query API:** FastAPI (MIT) + Uvicorn (BSD-3-Clause); Pydantic v2 (MIT) for request/response
  models and for the `acp_event_model` payload bindings. OpenAPI 3.1 is generated by FastAPI.
- **Kafka client:** `confluent-kafka` (Apache-2.0) consumer/producer.
- **Persistence:** PostgreSQL (PostgreSQL License) via SQLAlchemy Core (MIT) +
  `psycopg` (LGPL-with-linking-exception, permissive in practice) driver; Alembic (MIT) for
  schema migrations.
- **HTTP client (integration points):** `httpx` (BSD-3-Clause); `respx` (BSD-3-Clause) for
  OpenAPI-stub mocking in unit tests.
- **Event contract:** `acp_event_model` (the repo's `libs/event-model` Python/Pydantic binding)
  — `TopologyChangedEvent`, `KnowledgeUpdatedEvent`, `TrailsBuiltEvent`, `Envelope`,
  `ManagedObjectId`, `deserialize`/`serialize`.
- **Metrics:** `prometheus-client` (Apache-2.0). **Logging:** stdlib `logging` with a JSON
  formatter. **Tests:** `pytest` (MIT) — the fixed Python-cohort unit/contract framework.

All licenses above are MIT / BSD / Apache-2.0 / PostgreSQL — permissive only.

## Task breakdown (from the spec)

Every spec Task (1–8) is realized below; no task is dropped or re-scoped.

| Spec task | Realized by (modules / flow) |
|---|---|
| **1. Consume `topology.changed` and trigger a domain-scoped build (+ on-demand API).** | `kafka_consumer.TopologyChangedConsumer` decodes the envelope, reads `domain` directly from the payload, calls `idempotency.IdempotencyStore.seen(eventId)`, and on a new event invokes `build_service.BuildService.build(snapshotId, domain, traceId)`. On-demand path: `api.routes.rebuild` (`POST /trails/rebuild`) calls the same `BuildService.build`. Key flow A. |
| **2. React to `knowledge.updated` for trail-policy changes.** | `kafka_consumer.KnowledgeUpdatedConsumer` decodes `KnowledgeUpdatedEvent`; when `recordType == "trailPolicy"` it calls `policy_client.invalidate(domain)` so `KnowledgePolicyClient` re-fetches on next access. No build is triggered, no `trails.built` emitted. |
| **3. Fetch graph closures from the Topology Service (FROZEN shapes, snapshot-scoped).** | `topology_client.TopologyClient` calls Topology's **frozen** query API over `httpx`, domain- and snapshot-scoped, traversing the dependency-edge relations from the policy (incl. `HOSTS`/`TERMINATES`): **list-by-type** `GET /topology/nodes?objectType={t}&domain={d}&snapshotId={current\|previous}` to `NodeListDto { domain, objectType?, snapshotId, count, nodes: NodeDto[] }` (enumerate seeds), **neighbors** `GET /topology/nodes/{moId}/neighbors?relation={r}` to `NeighborsDto { managedObjectId, domain, neighbors: [{ node: NodeDto, via: EdgeDto }] }`, **bounded traverse** `GET /topology/traversal?start={moId}&relation={r}&...&maxDepth={K}&crossDomain=false` to `TraversalDto { start, domain, relations[], maxDepth, crossDomain, reached: NodeDto[] }`. `NodeDto { managedObjectId, objectType, domain, snapshotId, name?, attributes }`. The `snapshotId` scoping token is `current` for the build's in-scope snapshot (the snapshot carried by the triggering event is Topology's current snapshot for the domain). Never touches the topology graph store directly (Topology is the single owner). |
| **4. Fetch trail policy from Knowledge (domain-parameterized).** | `policy_client.KnowledgePolicyClient.get_policy(domain)` calls the Knowledge read API for the `trailPolicy` record scoped to `domain`, returning a `TrailPolicy` value object (IGP-area key, SRLG-union rule, dependency-edge set). Cached per-domain; invalidated by Task 2. No hard-coded policy values. |
| **5. Compute trails per domain, traversing Interface objects.** | `closure.TrailClosure` builds a `networkx.MultiDiGraph` slice and computes overlapping, IGP-area-bounded transitive closures over the policy's dependency-edge set (which includes `HOSTS` Port to Interface and `TERMINATES` Interface to IPLink, so `Interface:*` objects are natural members), then unions SRLG-co-member links into shared trails. The IGP-area bound reads `igpArea` from each node's `NodeDto.attributes` map under the key named by the policy `boundary.attributeKey` (`igpArea`); step 4 prunes cross-area members. This now **actually fires** on real data because the Simulator emits a grounded `igpArea` per Node/Interface (previously inert — no producer populated it). Algorithm logical flow below. |
| **6. Persist trail definitions with domain.** | `repository.TrailRepository` writes `trail` + `trail_member` rows in one transaction tagged with `snapshotId` and `domain`; a rebuild for the same `domain`+`snapshotId` supersedes prior rows for that pair; older snapshots retained per the retention rule (Data model). |
| **7. Serve domain-scoped queries + browse via API.** | `api.routes`: **`GET /trails/by-object?managedObjectId=&domain=`** (getTrailsForObject → frozen `{ managedObjectId, domain, trailIds }`), `GET /trails/{trailId}` (getTrail → frozen `TrailDetail` with `members[{managedObjectId,objectType}]` + `snapshotId`), `GET /trails?snapshotId=&domain=` (listTrails summaries). FastAPI publishes OpenAPI 3.1 at `/openapi.json`; the generated `openapi.json` is checked into `services/trail-builder/` as the **frozen single source of truth** consumers build against (paths + response schemas frozen; P1-G4/P1-G10/P2-GAP-09). |
| **8. Emit `trails.built` with `domain`.** | `event_publisher.TrailsBuiltPublisher` builds a `TrailsBuiltEvent` (`snapshotId`, `trailIds`, `trailCount == len(trailIds)`, `domain` taken from the triggering event — no Topology lookup) wrapped in an `Envelope`, serialized via `acp_event_model.serialize`, produced to `trails.built`. |

## Phase applicability (design view)

Consistent with the spec's Phase applicability table and the canonical phase map in
`docs/architecture.md` (trail-builder row: P1 Active, P2 Passive, P3 Passive). A
`knowledge.updated` trail-policy refresh or a new `topology.changed` may fire a P1-style Active
rebuild at any wall-clock time; such a rebuild is classified as P1 work.

| Phase | Active/Passive/Idle | Modules/handlers exercised | Inputs/Outputs |
|---|---|---|---|
| P1 — Topology onboarding | **Active** | `TopologyChangedConsumer`, `IdempotencyStore`, `BuildService`, `TopologyClient`, `KnowledgePolicyClient`, `TrailClosure`, `TrailRepository`, `TrailsBuiltPublisher`. **Concurrently** the query API (`api.routes` getTrailsForObject / getTrail / listTrails) serves the web-ui topology-trails module. `KnowledgeUpdatedConsumer` active as a refresh trigger. | In: `topology.changed` (domain read from event), Topology graph-closure API (domain-scoped), Knowledge trail-policy API (domain-parameterized), `knowledge.updated`. Out: `trails.built` (carrying `domain`); `topology.changed.dlq` on poison; query API responses. |
| P2 — Pattern learning | **Passive** | `api.routes` query endpoints only (getTrailsForObject / getTrail / listTrails). `KnowledgeUpdatedConsumer` may refresh policy. Build pipeline dormant unless a rebuild is triggered (then P1 work). | In: query API calls from Enrichment / Noise Filter / Pattern Miner / web-ui. Out: query responses. No topic output of its own. |
| P3 — Real-time correlation | **Passive** | `api.routes` query endpoints only (live-path Enrichment trail-tagging via getTrailsForObject). Build pipeline dormant unless triggered. | In: query API calls (e.g. Enrichment live path). Out: query responses. No topic output of its own. |

## Module breakdown

```mermaid
flowchart TD
  TC["topology.changed consumer"] --> BS["BuildService orchestrator"]
  KU["knowledge.updated consumer"] --> PC["KnowledgePolicyClient cache"]
  API["FastAPI query and rebuild routes"] --> BS
  API --> RP["TrailRepository reads"]
  BS --> IDS["IdempotencyStore eventId dedupe"]
  BS --> TOP["TopologyClient graph closure API"]
  BS --> PC
  BS --> CL["TrailClosure networkx algorithm"]
  CL --> RP
  BS --> PUB["TrailsBuiltPublisher trails.built"]
  RP --> DB[("PostgreSQL trail store")]
  TOP --> TOPSVC["Topology Service query API external"]
  PC --> KNSVC["Knowledge Service policy API external"]
```

- **`kafka_consumer`** — `TopologyChangedConsumer` (build trigger, dedupe, DLQ routing) and
  `KnowledgeUpdatedConsumer` (policy-refresh trigger only). Each rejects unknown major
  `schemaVersion` and routes poison messages to `topology.changed.dlq`.
- **`build_service`** — orchestrates a build: idempotency check, policy fetch, graph fetch,
  closure compute, persist, emit. Shared by the Kafka path and the `POST /trails/rebuild` path.
  **Snapshot pinning:** the `snapshotId` used to scope the Topology graph reads, persisted on every
  trail, and set on the emitted `trails.built` is the `snapshotId` carried by the triggering
  `topology.changed` event (or supplied on the `POST /trails/rebuild` request) — that is the
  in-scope snapshot. No Topology lookup re-resolves it; the Topology reads use the
  `snapshotId=current|previous` scoping token consistent with Topology's current/previous model.
- **`idempotency`** — `IdempotencyStore` backed by the `processed_event` table; `seen(eventId)`
  is an atomic insert-if-absent.
- **`topology_client`** — `TopologyClient` against the Topology Service's **frozen** published
  OpenAPI (`services/topology/openapi.json`); config-switchable mock/real; domain- **and
  snapshot-scoped** traversal over the policy dependency edges. Pins to the frozen paths/DTOs:
  list-by-type `GET /topology/nodes?objectType=&domain=&snapshotId=current|previous` (`NodeListDto`),
  neighbors `GET /topology/nodes/{moId}/neighbors?relation=` (`NeighborsDto`), bounded traverse
  `GET /topology/traversal?start=&relation=&maxDepth=&crossDomain=false` (`TraversalDto` with
  `reached: NodeDto[]`). Always passes the in-scope `snapshotId` token (`current`) so the graph
  slice matches the snapshot the build is for.
- **`policy_client`** — `KnowledgePolicyClient` against the Knowledge Service published OpenAPI;
  per-domain `TrailPolicy` cache; `invalidate(domain)`.
- **`closure`** — `TrailClosure`: networkx graph build + IGP-area-bounded transitive closure +
  SRLG union (the algorithm). The IGP-area bound reads the `igpArea` value from each node's
  `NodeDto.attributes` (the key is the policy `boundary.attributeKey`, `igpArea`) and step 4
  drops members whose area differs from the seed's. On Simulator-generated topology every Node
  (and the Interfaces it hosts) carries a grounded `igpArea`, so the prune fires on real data —
  it was previously inert (no producer populated `igpArea`; fixtures injected it).
- **`repository`** — `TrailRepository`: transactional persist + supersession + the three read
  queries.
- **`event_publisher`** — `TrailsBuiltPublisher`: builds + serializes + produces `TrailsBuiltEvent`.
- **`api`** — FastAPI app, routes, request/response models, `/health`, `/metrics`, `/openapi.json`.
- **`config`** — env-driven settings (base URLs, modes, DB DSN, Kafka brokers, default-domain
  fallback). **`observability`** — JSON logging + Prometheus metrics.

## Data model / DB schema

Trail Builder owns a PostgreSQL schema (`trailbuilder`). Three tables: `trail` (one row per
trail), `trail_member` (trail-to-`managedObjectId`, including `Interface:*` members), and
`processed_event` (`eventId` dedupe). Columns lead with `domain` and `snapshot_id` so the
domain/snapshot scoping is the primary access path.

```mermaid
erDiagram
  trail ||--o{ trail_member : has
  trail {
    text trail_id PK
    text domain
    text snapshot_id
    text seed_managed_object_id
    text igp_area
    text srlg_group
    int member_count
    timestamptz built_at
  }
  trail_member {
    bigint id PK
    text trail_id FK
    text domain
    text snapshot_id
    text managed_object_id
    text object_type
  }
  processed_event {
    text event_id PK
    text snapshot_id
    text domain
    timestamptz processed_at
  }
```

**`trail`** — `trail_id TEXT PRIMARY KEY` (deterministic, content-derived: a stable hash of
`domain` + `snapshot_id` + sorted member set, so a re-run for the identical slice is
reproducible and idempotent). `domain TEXT NOT NULL`, `snapshot_id TEXT NOT NULL`,
`seed_managed_object_id TEXT NOT NULL`, `igp_area TEXT NULL`, `srlg_group TEXT NULL` (the
seed/bounds context surfaced in `listTrails`), `member_count INT NOT NULL CHECK member_count
greater than 0`, `built_at TIMESTAMPTZ NOT NULL`. Indexes: `idx_trail_domain_snapshot` on
(domain, snapshot_id) for `listTrails`; PK covers `getTrail`.

**`trail_member`** — `id BIGSERIAL PRIMARY KEY`, `trail_id TEXT NOT NULL REFERENCES trail
ON DELETE CASCADE`, `domain TEXT NOT NULL`, `snapshot_id TEXT NOT NULL`, `managed_object_id
TEXT NOT NULL` (typed `<objectType>:<id>`), `object_type TEXT NOT NULL` (parsed prefix, lets
the web-ui filter Interface vs Port without re-parsing). Unique constraint
`uq_member` on (trail_id, managed_object_id). Indexes: `idx_member_domain_object` on
(domain, managed_object_id) for `getTrailsForObject`; `idx_member_trail` on (trail_id) for
`getTrail`.

**`processed_event`** — `event_id TEXT PRIMARY KEY` (envelope `eventId`), `snapshot_id`,
`domain`, `processed_at`. The PK gives at-least-once idempotency (Task 1 / AC-7).

**Supersession + retention (resolves Open question 4).** A build for `(domain, snapshot_id)`
runs in one transaction that first deletes any existing `trail` rows for that exact pair
(`trail_member` cascades) then inserts the new set — so re-delivery or rebuild for the same
pair never duplicates rows. **Retention: keep the current + the immediately previous
`snapshot_id` per domain**; older snapshots' trail rows are pruned at the end of a successful
build (delete trail rows for the domain whose snapshot_id is neither current nor previous).
This matches the spec "retained until explicitly superseded" intent while bounding growth;
configurable via `TRAIL_RETENTION_SNAPSHOTS` (default 2).

## Event handling

- **Consumers:**
  - `topology.changed` → `TopologyChangedConsumer` → `BuildService.build`. **Idempotency key:**
    envelope `eventId` (`processed_event` PK). **DLQ:** deserialization failure, unknown major
    `schemaVersion`, or non-retryable processing error → re-produce the raw message (with a
    `dlqReason` header) to `topology.changed.dlq`; offset committed so subsequent messages flow.
  - `knowledge.updated` → `KnowledgeUpdatedConsumer`. When `recordType == "trailPolicy"`, call
    `policy_client.invalidate(domain)`. No build, no emission. Poison messages on this topic are
    logged and skipped (refresh-only; a missed refresh self-heals on the next event), not DLQ'd,
    to avoid coupling the build path to refresh-trigger noise.
- **Producers:**
  - `trails.built` → `TrailsBuiltEvent` from `acp_event_model` (`snapshotId`, `trailIds`,
    `trailCount`, `domain`), wrapped in `Envelope` (`type = TrailsBuiltEvent`, `schemaVersion = 1`,
    `traceId` propagated from the triggering event, `source = "trail-builder"`). Emitted once per
    successful build (idempotency guarantees one emission per `eventId`).

## API contracts / API schema

FastAPI generates OpenAPI 3.1 at `/openapi.json`; the generated document is checked into
`services/trail-builder/openapi.json` and is **the single, frozen source of truth** for the HTTP
surface that every consumer (Codebook Generator, Enrichment, Noise Filter, web-ui) generates its
client against. The three trail-query operations — `getTrail`, `getTrailsForObject`,
`listTrails` — have **frozen paths and frozen response schemas** below; their shapes do not drift
without a contract change (data-integration fixes **P1-G4**, **P1-G10**, **P2-GAP-09**). The
service's own contract/unit tests validate request inputs and response bodies against this
checked-in document (AC-16). All trail queries are domain-scoped.

**Frozen path disambiguation (P1-G10).** `getTrailsForObject` and `listTrails` no longer share
`GET /trails` distinguished only by query param. `getTrailsForObject` is moved to the explicit
sub-resource **`GET /trails/by-object`**, chosen because: (a) it is the path the web-ui already
targets, removing the consumer-side mismatch; (b) it cleanly separates a per-object lookup from
the snapshot-scoped `listTrails` browse, so the two operations cannot collide on one path; (c) it
reads as a self-describing sub-resource. `listTrails` keeps `GET /trails?snapshotId=&domain=`.

**`domain` is a strictly REQUIRED query param on the query API — clear 400, no default (Q1 + Q7).**
On `getTrailsForObject` (`GET /trails/by-object?managedObjectId=&domain=`) **and** on `listTrails`
(`GET /trails?snapshotId=&domain=`), `domain` is required. A request missing `domain` returns
**400** with a structured error body (FastAPI/Pydantic `RequestValidationError` for the missing
required query parameter) — it is **never** silently defaulted to `core-ip`. The decision and its
justification:

- **Chosen: strictly required + clear 400 (no query-API domain default).** Rationale: (1) a trail
  belongs to exactly one domain, and `getTrailsForObject(moId, domain)` is meaningless without the
  domain that scopes which trails are wanted — defaulting would silently return Core-IP trails for
  a non-Core-IP query, a correctness hazard once a second domain exists; (2) it matches the spec's
  frozen contract ("Both query parameters are required for MVP"); (3) it matches **what the live
  consumers actually send today** — Enrichment (`enrichment-readiness-fixes`) passes `domain` on
  every call (sourced from `ENRICHMENT_DOMAIN`, MVP `core-ip`), and Codebook Generator
  (`codebook-readiness-fixes`) passes the event's resolved `domain` on every per-symptom-object
  call; neither relies on a server default. The endpoint is thus aligned with the real call shape
  and future-proof for multi-domain.
- **Rejected: server-side `core-ip` default-domain fallback on the query API (backward-compat).**
  This was considered for tolerance toward a consumer that historically called with
  `managedObjectId` only, but it is rejected: the consumers no longer call that way (both now send
  `domain`), so the fallback would be dead code that masks a genuine caller bug (a forgotten
  `domain`) and silently returns wrong-domain trails. A clear 400 surfaces the bug at the caller.

**Default-domain fallback is scoped to the event-ingestion path only — not the query API.** The
`DEFAULT_DOMAIN` (`core-ip`) fallback exists solely for a **legacy `topology.changed` event whose
optional `domain` field is absent** (the `TrailsBuiltEvent`/`TopologyChangedEvent` `domain` is
optional in the event model for backward-compat). On the inbound query API the `domain` is always
caller-supplied and required. The two paths are kept deliberately distinct so the query contract
stays strict while the consumer (Kafka) path remains tolerant of an older producer.

| Operation | Method + path (FROZEN) | Request | Response 200 (FROZEN) | Errors |
|---|---|---|---|---|
| getTrailsForObject | `GET /trails/by-object?managedObjectId={moId}&domain={domain}` | both query params required | `TrailsForObjectResponse { managedObjectId, domain, trailIds: string[] }` | 400 missing param; 422 bad `managedObjectId` shape |
| listTrails | `GET /trails?snapshotId={snapshotId}&domain={domain}` | both query params required, optional `limit`/`offset` | `ListTrailsResponse { snapshotId, domain, count, trails: TrailSummary[] }` | 400 missing param |
| getTrail | `GET /trails/{trailId}` | path param | `TrailDetail { trailId, domain, snapshotId, members: TrailMember[], memberCount, igpArea?, srlgGroup? }` | 404 unknown trailId |
| rebuild | `POST /trails/rebuild` | `RebuildRequest { snapshotId, domain }` (both required) | `TrailsBuiltSummary { snapshotId, domain, trailIds: string[], trailCount }` | 400 missing field; 502 if Topology/Knowledge unavailable |
| health | `GET /health` | — | `{ status, dependencies: { topology, knowledge, db, kafka } }` | 503 if not ready |
| metrics | `GET /metrics` | — | Prometheus text | — |
| openapi | `GET /openapi.json` | — | OpenAPI 3.1 document | — |

**Frozen response schemas (the contract consumers build against):**

- **`TrailsForObjectResponse` (getTrailsForObject — FROZEN, P1-G4).**
  `{ managedObjectId: string, domain: string, trailIds: string[] }`. `trailIds` is a
  (possibly empty) array of trail-id strings — the **canonical, intentionally minimal** shape:
  Enrichment sets `AlarmEvent.trailIds` directly from it, Codebook Generator unions `trailIds`
  across symptom objects, and web-ui reads `trailIds` to highlight member trails on device-select
  — none needs per-trail summaries here. A richer list variant is **not** added: no consumer
  requires trail summaries from the per-object lookup, and `listTrails` (summaries) / `getTrail`
  (detail) already serve those needs. This replaces the prior `{ ..., trails: TrailSummary[] }`
  variant so the producer shape exactly matches what the consumers read.
- **`TrailDetail` (getTrail — FROZEN, P1-G4 + P2-GAP-09).** `{ trailId: string, domain: string,
  snapshotId: string, members: TrailMember[], memberCount: integer, igpArea?: string,
  srlgGroup?: string }`.
  - `snapshotId` is **always present and guaranteed** (P2-GAP-09): the source `AlarmEvent` carries
    no `snapshotId`, so Noise Filter derives the REQUIRED `TransactionEvent.snapshotId` from
    `getTrail(trailId).snapshotId`. A contract test asserts the field is present and non-null on
    every `getTrail` 200 (AC-21).
  - `members: TrailMember[]` where **`TrailMember { managedObjectId: string, objectType: string }`**
    — each member carries **both** the typed `managedObjectId` (`<objectType>:<id>` scheme; AC-5/18)
    **and** the parsed `objectType`, so the web-ui distinguishes `Interface` from
    `Port`/`IPLink`/`Node` without an extra call or re-parse. `members` includes any `Interface:*`
    members in the closure (AC-19).
  - `memberCount == len(members)`.
- **`TrailSummary` (listTrails item).** `{ trailId, domain, memberCount, igpArea?, srlgGroup? }`.
- `TrailsBuiltSummary` mirrors the `TrailsBuiltEvent` payload (`trailCount == len(trailIds)`).

These frozen shapes are exactly what the consumer designs read: Enrichment / Codebook Generator
consume `TrailsForObjectResponse.trailIds[]`; Noise Filter consumes `TrailDetail.snapshotId` (and
`members`); web-ui consumes all three. This freeze is **service-owned API surface only** — it
introduces no Kafka topic and no event-model payload change; `TrailsBuiltEvent` is unchanged.

`POST /trails/rebuild` (resolves Open question 3): for the MVP it is an **internal-only** endpoint
— guarded by a shared bearer token from `REBUILD_API_TOKEN` (env). When the var is unset
(local/CI) the guard is disabled. No public auth surface is in MVP scope; richer authz is
deferred.

## Integration points (mock vs. real)

No hard-coded URLs; every collaborator base URL and mock/real toggle resolves from env at
startup. Clients are built against the collaborator's **published OpenAPI spec**, never source.

| Collaborator + operation | Config keys | Mock (unit) | Real (integration) |
|---|---|---|---|
| **Topology Service** — graph closure against the **FROZEN** query API, domain- and snapshot-scoped: list-by-type `GET /topology/nodes?objectType=&domain=&snapshotId=current|previous` to `NodeListDto`; neighbors `GET /topology/nodes/{moId}/neighbors?relation=` to `NeighborsDto`; bounded traverse `GET /topology/traversal?start=&relation=&...&maxDepth=&crossDomain=false` to `TraversalDto { ..., reached: NodeDto[] }` over `HOSTS`/`TERMINATES`/`RIDES_ON`/`ADJACENCY_OVER`/`TRAVERSES`/`SERVES`/`MEMBER_OF`. Each `NodeDto.attributes` map carries the grounded **`igpArea`** value (Simulator-emitted, Topology-carried) the closure reads for the step-4 area bound. | `TOPOLOGY_SERVICE_BASE_URL`, `TOPOLOGY_SERVICE_MODE` (`mock`/`real`) | `respx` stubs generated from Topology's **frozen** checked-in `services/topology/openapi.json`, returning fixture `NodeListDto`/`NeighborsDto`/`TraversalDto` graph slices. **Note:** unit-fixture `igpArea` values are injected and so do **not** prove the bound on real data — that is the role of the integration assertion INT-IGPAREA. | live Topology Service on the `integration` Compose network, fed a **Simulator-generated** multi-area topology carrying grounded `igpArea` |
| **Knowledge Service** — read the domain-scoped `trailPolicy` record (IGP-area bound, SRLG-union rule, dependency-edge set) | `KNOWLEDGE_SERVICE_BASE_URL`, `KNOWLEDGE_SERVICE_MODE` (`mock`/`real`) | `respx` stubs from Knowledge published `openapi.json` returning per-domain policy | live Knowledge Service |
| **Codebook Generator / Enrichment / Noise Filter / web-ui** (inbound consumers of our query API) | n/a (we publish) | each builds its client from **our** checked-in, **frozen** `openapi.json` (`getTrail`, `getTrailsForObject`, `listTrails` paths + response schemas frozen — P1-G4/P1-G10/P2-GAP-09; `domain` **required** on `getTrailsForObject` and `listTrails`) | call our query API with `domain` always supplied: Enrichment + Codebook call `GET /trails/by-object?managedObjectId=&domain=` and read `trailIds[]`; Noise Filter reads `getTrail.snapshotId` (+ `members`); web-ui reads all three |

The Topology graph-closure operations are now pinned to Topology's **frozen** query-API paths,
required params (incl. `snapshotId=current|previous` scoping), and DTOs — `NodeListDto`,
`NeighborsDto`, `TraversalDto`, `NodeDto` (Q3). The Knowledge trail-policy record shape remains a
design-stage item tracked in issue #25; the dev agent finalizes the Knowledge client against the
producer's checked-in `openapi.json`. (Former Topology issue #24 is resolved by the Topology
API-freeze: the shapes are frozen above.) All clients are built against each producer's checked-in
`openapi.json`, never against producer source.

## Key flows (sequence / data-flow diagrams)

### Flow A — topology.changed to trails.built (domain-scoped build)

```mermaid
sequenceDiagram
  participant K as topology.changed topic
  participant C as TopologyChangedConsumer
  participant B as BuildService
  participant I as IdempotencyStore
  participant P as KnowledgePolicyClient
  participant T as TopologyClient
  participant A as TrailClosure
  participant R as TrailRepository
  participant E as TrailsBuiltPublisher
  K->>C: TopologyChangedEvent snapshotId domain eventId
  C->>I: seen of eventId
  I-->>C: false meaning new
  C->>B: build snapshotId domain traceId
  B->>P: get_policy domain
  P-->>B: TrailPolicy igpAreaKey srlgRule dependencyEdges
  B->>T: GET topology nodes objectType domain snapshotId current to list seeds NodeListDto
  T-->>B: NodeListDto seeds
  B->>T: GET topology traversal start relation HOSTS and TERMINATES maxDepth K crossDomain false
  T-->>B: TraversalDto reached NodeDto array domain-scoped and snapshot-scoped
  B->>A: compute graph and policy
  A-->>B: overlapping bounded trails incl Interface members
  B->>R: persist trails snapshotId domain then prune old snapshots
  B->>E: emit snapshotId trailIds trailCount domain
  E-->>K: TrailsBuiltEvent on trails.built
```

### Flow B — query path (getTrailsForObject / getTrail / listTrails)

```mermaid
sequenceDiagram
  participant UI as web-ui topology-trails module
  participant API as FastAPI routes
  participant R as TrailRepository
  participant DB as PostgreSQL
  UI->>API: GET trails by-object with managedObjectId and domain
  API->>R: trails_for_object moId domain
  R->>DB: select trail_id where domain and managed_object_id
  DB-->>R: trail id rows
  R-->>API: trailIds list
  API-->>UI: TrailsForObjectResponse managedObjectId domain trailIds
  UI->>API: GET trails by trailId
  API->>R: get_trail trailId
  R->>DB: select members where trail_id
  DB-->>R: typed member rows incl Interface
  R-->>API: TrailDetail with snapshotId
  API-->>UI: viz-ready members managedObjectId objectType snapshotId domain
```

### Flow C — knowledge.updated policy refresh

```mermaid
sequenceDiagram
  participant K as knowledge.updated topic
  participant C as KnowledgeUpdatedConsumer
  participant P as KnowledgePolicyClient
  K->>C: KnowledgeUpdatedEvent recordType domain
  C->>C: recordType equals trailPolicy
  C->>P: invalidate domain
  Note over P: next get_policy domain re-fetches with no build and no emit
```

## Algorithm logical flow

The core algorithm computes **overlapping, policy-bounded trails** as the transitive closure
over the policy's **dependency-edge set** from each seed, bounded by IGP area, then unions
SRLG-co-member links into shared trails. All bounds come from the domain's Knowledge trail
policy — nothing is hard-coded.

**Inputs:** the per-snapshot graph slice — `NodeDto`/`EdgeDto` data assembled from Topology's
frozen `NodeListDto` (seeds), `NeighborsDto`, and `TraversalDto` (`reached: NodeDto[]`) responses
via `TopologyClient`, **domain- and snapshot-scoped** (the `snapshotId=current` token for the
in-scope snapshot); `TrailPolicy { dependencyEdges, igpAreaKey, srlgRule }` from Knowledge. The
`igpArea` value for each member is read from that node's **`NodeDto.attributes` map under the key
`policy.igpAreaKey`** (`igpArea`) — the same grounded attribute the Simulator emits per
Node/Interface and Topology carries through unchanged.

**IGP-area bound is now exercised on real data (MVP-achievability fix).** The step-4 area prune
reads `igpArea` from `NodeDto.attributes` per the policy boundary key. On Simulator-generated
topology every Node (and the Interfaces it hosts) carries a grounded `igpArea` (`area-0` backbone
plus numbered edge areas), so the prune **fires** and trails are bounded to a single IGP area —
yielding multiple area-bounded trails on a multi-area topology, not one giant whole-network trail.
This was **previously inert**: no producer populated `igpArea`, closure spanned the whole
connected dependency component, and only `igpArea`-injecting fixtures kept the unit tests green.
The guarantee now rests on the **integration assertion over real (igpArea-bearing) Simulator
data** (Test plan **INT-IGPAREA** / E2E scenario 15), not on fixtures that inject `igpArea`.

**Why Interface is in the closure:** the policy `dependencyEdges` includes `HOSTS`
(Port to Interface) and `TERMINATES` (Interface to IPLink). Closure over those edges therefore
spans Port then Interface then IPLink, so `Interface:*` is a natural member sitting between the
`Port:*` and `IPLink:*` members (AC-19). Interfaces are never filtered out.

```mermaid
flowchart TD
  S["Build networkx MultiDiGraph from the domain graph slice"] --> F["Keep only edges whose relation is in policy dependencyEdges as an undirected closure view"]
  F --> SEEDS["Enumerate seed objects of the fault-capable types per policy"]
  SEEDS --> LOOP{"more seeds"}
  LOOP -->|yes| CLOSE["Transitive closure from seed over dependency edges"]
  CLOSE --> BOUND["Drop any object whose IGP area differs from the seed area per policy igpAreaKey"]
  BOUND --> CAND["Candidate trail equals bounded reachable member set incl Interface members"]
  CAND --> LOOP
  LOOP -->|no| SRLG["Union trails that contain links sharing an SRLG group per policy srlgRule over MEMBER_OF"]
  SRLG --> DEDUP["Deduplicate identical member sets into one trail"]
  DEDUP --> ID["Assign deterministic trail_id per member set and record seed igpArea and srlgGroup context"]
  ID --> OUT["Emit overlapping trail set where a member may appear in many trails"]
```

**Steps (dev-implementable):**
1. Build a `networkx.MultiDiGraph`; each node keyed by `managedObjectId` with `objectType` and
   the `igpArea` attribute read from **`NodeDto.attributes[policy.igpAreaKey]`** (the grounded
   per-Node/Interface `igpArea` the Simulator emits and Topology carries).
2. Restrict to edges whose `relation` is in `policy.dependencyEdges` and treat that edge view as
   undirected for reachability (a Port and its IPLink correlate regardless of edge direction).
3. Enumerate **seeds** = the fault-capable object types named by policy (Core IP: Node,
   LineCard, Port, Interface, Fiber). For each seed, compute the connected reachable set over the
   dependency-edge view.
4. **Bound by IGP area:** prune members whose `igpArea` (from `NodeDto.attributes[policy.igpAreaKey]`)
   differs from the seed area, so no trail spans two areas (AC-2). There is no unbounded
   whole-network trail. This prune **now fires on real data** because the Simulator populates a
   grounded `igpArea` per Node/Interface — it was previously inert (no producer set `igpArea`,
   so closure spanned the whole connected component). The no-whole-network-trail guarantee is
   verified on Simulator-generated data by the integration assertion **INT-IGPAREA**, not by
   `igpArea`-injecting unit fixtures.
5. **SRLG union (AC-3):** for each SRLG group (edges of relation `MEMBER_OF`), merge the trails
   containing its co-member links into one trail so fate-shared links land together.
6. Deduplicate identical member sets; assign a deterministic `trail_id`; record `seed`,
   `igpArea`, `srlgGroup` for `listTrails` context.
7. Output the overlapping trail set — a seed on two LSP paths and one SRLG yields membership in
   at least three trails (AC-1).

**Outputs:** a list of trails (member sets + bounds context) persisted by `TrailRepository`;
their ids go into the `trails.built` event.

## Seed data & examples

**N/A — consumes upstream.** Trail Builder owns no seed data; the graph slice comes from the
Topology Service and the policy from Knowledge. Unit tests use small fixture graph slices and a
fixture `trailPolicy` (served by the Topology/Knowledge OpenAPI mocks) — e.g. a slice with
`Port:PE1-LC2-P3 HOSTS Interface:PE1-LC2-P3.100 TERMINATES IPLink:PE1-PE2`, two LSP
paths through a shared device, and two `IPLink`s in one `SRLG` — to exercise overlap, area
bound, SRLG union, and Interface membership. **The unit fixtures inject `igpArea` on their
nodes** to drive the step-4 prune logic; this proves the logic but **not** that real data carries
`igpArea`. The IGP-area bound on real data is proven by the integration assertion **INT-IGPAREA**
over a **Simulator-generated** multi-area topology (grounded, not injected `igpArea`) — see the
Test plan.

## UI wireframes

**N/A — web-ui renders trail viz.** Trail Builder serves the viz-ready trail API
(`listTrails`, `getTrail` with typed members incl. Interface, `getTrailsForObject`); the web-ui
topology-trails module overlays trail membership on the Topology-supplied multi-layer graph.

## Error handling

| Failure mode | Handling |
|---|---|
| Poison `topology.changed` (malformed JSON, fails Pydantic validation) | Routed to `topology.changed.dlq` with a `dlqReason` header; offset committed; consumer continues with subsequent messages (AC-14). Never silently dropped. |
| Unknown major `schemaVersion` (at least 2) on `topology.changed` | Rejected by the envelope check, routed to `topology.changed.dlq` as poison (AC-14). |
| Topology Service unavailable / errors during a build | Bounded retry with exponential backoff (`httpx`); on exhaustion the build is **held, not dropped** — the `eventId` is NOT marked processed (so a later redelivery retries), the error is logged with `traceId`/`snapshotId`/`domain`, a `build_failures_total{reason="topology"}` metric increments, and `/health` reports the dependency degraded. `POST /trails/rebuild` returns 502. |
| Knowledge Service unavailable / errors during policy fetch | Same retry/hold policy as Topology (`build_failures_total{reason="knowledge"}`); a previously cached policy for the domain may be used if present and `KNOWLEDGE_STALE_OK=true`, else the build holds. |
| `domain` missing on the **`topology.changed` event** (event path only — legacy producer, backward-compat) | Default to the MVP domain `core-ip` via `DEFAULT_DOMAIN` (per the event-model optional-`domain` backward-compat note); logged at WARN. The `trails.built` and persisted records carry the resolved `core-ip`. **This fallback applies ONLY to the Kafka event path; it is NOT applied to the HTTP query API** (Q1 + Q7). |
| `domain` missing on the **query API** (`GET /trails/by-object`, `GET /trails`) | **400** with a structured JSON error body (required-query-param validation). **No `core-ip` default is applied** — the query contract is strict so a wrong/forgotten `domain` surfaces at the caller rather than silently returning Core-IP trails (Q1 + Q7). This matches the call shape the live consumers send (Enrichment + Codebook always pass `domain`). |
| Snapshot supersession / re-delivery | Idempotency on `eventId` (one emission, no duplicate rows; AC-7). A new `snapshotId` for the domain supersedes that domain+snapshot pair and prunes per retention; older snapshots records remain until pruned (AC-15). The `snapshotId` is always the one carried by the triggering event — never re-resolved from Topology. |
| Topology query returns an unexpected/non-frozen shape (e.g. version skew) | Treated as a build dependency error: validated against the frozen `NodeListDto`/`NeighborsDto`/`TraversalDto`/`NodeDto` shapes on decode; a decode failure logs with `traceId`/`snapshotId`/`domain`, increments `build_failures_total{reason="topology"}`, and holds the build (eventId not marked processed) rather than persisting a partial trail set. |
| Bad query request (malformed `managedObjectId`) | 422 with a structured JSON error body; no partial result. |
| `getTrail` unknown `trailId` | 404 with structured error. |
| Empty closure (a seed with no dependency neighbours) | Produces a singleton trail (the seed itself), never an error; logged at DEBUG. |
| `knowledge.updated` poison | Logged and skipped (refresh-only path); not DLQ'd, since a missed refresh self-heals on the next event/build. |

All paths emit structured JSON logs including `traceId`, `snapshotId`, and `domain` where
applicable. No build is ever silently dropped: it is either completed, retried, or held with a
metric + health signal.

## Design alternatives

| Consideration | Alternatives considered | Chosen + rationale |
|---|---|---|
| Closure engine | (a) `networkx` in-memory; (b) push traversal into Topology via repeated API calls; (c) graph queries in our own store | **(a)** — networkx (BSD) is the Solution-Design-mandated library; pulling the domain slice once and closing in-memory minimizes Topology round-trips and keeps the algorithm testable in isolation. (b) is chatty; (c) violates single-owner (Topology owns the graph). |
| Interface membership | (a) traverse `HOSTS`/`TERMINATES` as dependency edges so Interface is a natural member; (b) treat Interface as a pass-through and stitch Port to IPLink directly | **(a)** — §5 makes Interface first-class and fault-capable (InterfaceDown originates a cascade); (b) would drop a real fault origin from the trail and break AC-19. |
| `trailId` scheme | (a) deterministic content hash of (domain, snapshot, member set); (b) random UUID per build | **(a)** — reproducible across re-runs of an identical slice, so re-delivery/rebuild is naturally idempotent and diff-able; (b) churns ids on every build. |
| Supersession granularity | (a) per `(domain, snapshotId)` pair; (b) global per `snapshotId`; (c) never delete | **(a)** — domains are independent; superseding by domain+snapshot preserves other domains trails and matches the spec. (b) would cross-domain-leak; (c) grows unbounded. |
| getTrailsForObject path (P1-G10) | (a) overload `GET /trails?managedObjectId=` (distinguished from `listTrails` by query param); (b) explicit sub-resource `GET /trails/by-object?managedObjectId=`; (c) nested `GET /domains/{d}/snapshots/{s}/trails` | **(b)** — frozen. It is the path web-ui already targets (removing the consumer mismatch), and it prevents `getTrailsForObject` and `listTrails` colliding on one path. (a) overloads a single path on a query param (the prior divergence source); (c) is verbose and not what any consumer calls. |
| getTrailsForObject response shape (P1-G4) | (a) `{ managedObjectId, domain, trailIds: string[] }`; (b) `{ managedObjectId, domain, trailIds, trails: TrailSummary[] }` (richer) | **(a)** — frozen canonical. Enrichment, Codebook Generator and web-ui all consume only `trailIds[]`; `listTrails`/`getTrail` already cover summaries/detail, so a richer per-object variant is dead weight and would invite divergent consumer assumptions. |
| getTrail snapshotId (P2-GAP-09) | (a) guarantee `snapshotId` in `TrailDetail`; (b) make it optional | **(a)** — Noise Filter derives the REQUIRED `TransactionEvent.snapshotId` from `getTrail(trailId).snapshotId` (the source `AlarmEvent` has none), so the field is frozen as always-present and contract-tested. Optional would break a required downstream field. |
| Idempotency store | (a) dedicated `processed_event` table; (b) rely on deterministic `trailId` upsert only | **(a)** — explicit `eventId` PK guarantees exactly-one `trails.built` emission (AC-7), which a member-set hash alone cannot (two different events could yield the same trails). |
| Policy cache invalidation | (a) `knowledge.updated`-driven invalidate + lazy re-fetch; (b) TTL cache; (c) fetch every build | **(a)** — event-driven freshness with no per-build latency (Task 2); (c) adds latency to every build; (b) risks stale policy between TTL ticks. |
| `domain` on the query API (Q1 + Q7) | (a) strictly REQUIRED with a clear 400, no default; (b) server-side `core-ip` default-domain fallback for backward-compat; (c) required on `getTrailsForObject` but defaulted on `listTrails` | **(a)** — chosen. A trail belongs to exactly one domain, so a per-object/listing lookup is meaningless without it; the spec freezes both params as required; and the live consumers (Enrichment, Codebook Generator) **already pass `domain` on every call**, so a default would be dead code that masks a forgotten-`domain` caller bug and could silently return wrong-domain trails once a second domain exists. (b) is rejected for that masking hazard; (c) is inconsistent. The `core-ip` default is kept **only** on the Kafka event path (legacy optional-`domain` producer) — a deliberately different path from the strict query API. |
| Topology call shapes + snapshot scoping (Q3) | (a) pin to Topology's FROZEN query-API paths/params/DTOs (`NodeListDto`/`NeighborsDto`/`TraversalDto`/`NodeDto`) with `snapshotId=current|previous` scoping, snapshot taken from the triggering event; (b) treat Topology endpoints as TBD/design-stage and finalize later; (c) re-resolve the snapshot via a Topology lookup at build time | **(a)** — chosen. Topology's query API is now frozen (its API-freeze closed issue #24), so pinning the exact paths/DTOs removes the build-time ambiguity and lets the dev agent generate the client + mock directly from `services/topology/openapi.json`. Scoping every read to `snapshotId=current` (the in-scope snapshot the event carries) guarantees the graph slice matches the snapshot the trails are persisted under. (b) leaves a buildable gap; (c) adds a redundant lookup and risks reading a different snapshot than the event named. |
| Proving the IGP-area bound (MVP-achievability fix) | (a) keep relying on unit fixtures that inject `igpArea` (status quo — the gate found this masks the real gap); (b) change the Trail Builder algorithm to fabricate/default an area when none is present; (c) confirm the closure reads `igpArea` from real `NodeDto.attributes` and add an **integration assertion on Simulator-generated data** (INT-IGPAREA) plus an E2E scenario | **(c)** — chosen. The root cause was upstream (no producer emitted `igpArea`), now fixed by Knowledge (`attributeCatalogue` `igpArea` + `trailPolicy` boundary) and the Simulator (grounded per-Node/Interface `igpArea`). Trail Builder's closure already reads `igpArea` from node attributes per the policy boundary key — the only missing piece was a test over **real** data, so we add INT-IGPAREA (Simulator-generated, grounded `igpArea`, asserts area-bounded trails + AC-2 no-whole-network-trail). (a) is the masking bug the gate flagged — rejected. (b) would invent data Trail Builder does not own (the area partition is the Simulator/topology's; defaulting would re-create whole-network trails or wrong bounds) and is a correctness hazard — rejected. No contract change: `igpArea` rides inside the already-frozen `NodeDto.attributes`; `TrailsBuiltEvent`/topics unchanged. |

## Test plan

### Acceptance criterion to test (unit/contract — `pytest`)

| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| 1 | Multi-trail overlap | `test_object_on_two_lsps_one_srlg_yields_three_trails` | `getTrailsForObject(X, domain)` returns at least 3 distinct trail ids. |
| 2 | Policy-bounded (IGP area) | `test_no_trail_spans_two_igp_areas` (unit) **+ `INT-IGPAREA` (integration, the load-bearing guarantee — see below)** | unit: every trail's members share one IGP area; no whole-network trail. **Note:** the unit test reads `igpArea` from fixture node attributes — it injects `igpArea`, so it proves the *prune logic* but NOT that real data carries the attribute. The **actual AC-2 guarantee on real data** rests on integration assertion `INT-IGPAREA`, which runs on **Simulator-generated** topology (grounded `igpArea` on real nodes, no injection) and asserts a multi-area topology yields multiple area-bounded trails rather than one whole-network trail. |
| 3 | SRLG union | `test_two_links_sharing_srlg_in_same_trail` | both `IPLink`s of one SRLG appear in the same trail. |
| 4 | `getTrailsForObject` completeness + frozen path/shape (P1-G4, P1-G10) | `test_get_trails_for_object_exact_set` | served at the frozen path `GET /trails/by-object?managedObjectId=&domain=`; returns the frozen shape `{ managedObjectId, domain, trailIds: string[] }`; `trailIds` equals the persisted set for the object, no more no fewer; empty `[]` when none. |
| 5 | `getTrail` correctness + domain + viz readiness + frozen member shape (P1-G4) | `test_get_trail_returns_members_snapshot_domain_typed` | returns frozen `TrailDetail`: full `members` where **every member is `{ managedObjectId, objectType }`** (both fields present), `managedObjectId` matches `<objectType>:<id>` and `objectType` equals its parsed prefix; matching `snapshotId` + `domain`; `memberCount == len(members)`. |
| 6 | `topology.changed` triggers build + emits `trails.built` with domain | `test_topology_changed_emits_trails_built_with_domain` | emitted payload deserializes as `TrailsBuiltEvent`; `trailCount == len(trailIds)`; `snapshotId`/`domain` match the trigger; Topology mock records zero domain-resolution calls. |
| 7 | Idempotency | `test_duplicate_event_id_one_emission_no_dup_rows` | same `eventId` twice gives exactly one `trails.built` and no duplicate trail rows. |
| 8 | `knowledge.updated` refresh trigger | `test_knowledge_updated_trailpolicy_refetches_no_emit` | `recordType == "trailPolicy"` re-fetches policy for that domain; no `trails.built`; next `topology.changed` uses the new policy. |
| 9 | Trail policy fetched per domain | `test_policy_fetched_per_domain` | two snapshots (domain-A, domain-B) give two Knowledge calls, each parameterized with its domain. |
| 10 | Trails carry domain + queries domain-scoped | `test_listtrails_domain_isolation` | every `getTrail` has non-empty `domain`; `listTrails(S, A)` and `listTrails(S, B)` never leak across domains. |
| 11 | Domain-agnostic computation (new domain, no code change) | `test_non_core_ip_domain_builds_with_its_policy` | mocked non-Core-IP policy gives trails built and tagged with that domain, no code change. |
| 12 | Config-switchable integration points | `test_mock_mode_runs_with_no_live_dependency` | with both `*_MODE=mock` all unit tests pass offline against OpenAPI stubs; same code path used with `real`. |
| 13 | No hard-coded URLs / policy / domain defaults | `test_base_url_env_change_redirects_calls` + `test_domain_never_defaulted_in_config` | changing `*_BASE_URL` redirects calls without code change; the domain in policy fetch derives from the event/request. |
| 14 | Poison message handling | `test_poison_topology_changed_routed_to_dlq` | malformed or unknown-major-`schemaVersion` message goes to `topology.changed.dlq`, consumer survives, next message processed. |
| 15 | `snapshotId` + `domain` alignment | `test_new_snapshot_creates_new_records_keeps_prior` | new-snapshot build tags new rows with the new `snapshotId`; prior snapshot records intact. |
| 16 | OpenAPI contract compliance (frozen surface published) | `test_responses_validate_against_checked_in_openapi` | requests/responses for the three GETs + rebuild validate against the checked-in frozen `openapi.json`, incl. `domain`; the document declares `getTrail`, `getTrailsForObject` (at `/trails/by-object`), `listTrails` with their frozen response schemas. |
| 17 | `listTrails` enumerates all trails for snapshot+domain | `test_listtrails_returns_all_n_with_counts` | `listTrails(S, D)` returns exactly N summaries, each with `trailId`/`domain`/member-count over zero; union of ids equals the `trails.built` `trailIds`. |
| 18 | `getTrail` members typed + viz-sufficient | `test_get_trail_members_all_typed_no_extra_call` | every member matches `<objectType>:<id>`; response carries `snapshotId`+`domain`; no per-member call needed. |
| 19 | Closure traverses through Interface | `test_trail_includes_interface_between_port_and_iplink` | a Port HOSTS Interface TERMINATES IPLink path gives a trail member list containing the `Interface:*` entry. |
| 20 | `trails.built` domain matches event without lookup | `test_trails_built_domain_from_event_no_topology_lookup` | trigger `domain="core-ip"` gives emitted `domain="core-ip"`; Topology mock records zero domain-resolution calls. |
| 21 | `getTrail` guarantees `snapshotId` for Noise-Filter provenance (P2-GAP-09) | `test_get_trail_response_always_includes_snapshot_id` | every `getTrail` 200 includes a non-null `snapshotId` matching the build snapshot; a consumer can resolve `TransactionEvent.snapshotId` from it; the field is asserted present in the checked-in `openapi.json` `TrailDetail` schema. |
| 22 | `getTrailsForObject` lives at the frozen path, not on `GET /trails` (P1-G10) | `test_get_trails_for_object_path_is_by_object` | `GET /trails/by-object?managedObjectId=&domain=` returns 200 with the frozen shape; `GET /trails?managedObjectId=&domain=` does NOT serve getTrailsForObject (it is not the frozen per-object operation); the path collision with `listTrails` is gone. |
| 23 | Frozen consumer-facing shapes match what consumers read | `test_frozen_shapes_match_consumer_contracts` | `getTrailsForObject` exposes `trailIds: string[]` (Enrichment/Codebook consume it); `getTrail.members[i]` exposes both `managedObjectId` and `objectType`; `getTrail` exposes `snapshotId` (Noise Filter) — all asserted against the checked-in `openapi.json`. |
| 24 | `domain` strictly required on the query API, clear 400, no default (Q1 + Q7) | `test_query_api_missing_domain_is_400_not_defaulted` | `GET /trails/by-object?managedObjectId=X` (no `domain`) and `GET /trails?snapshotId=S` (no `domain`) each return **400** with a structured error; **no** `core-ip` default is applied (no trails returned as if for `core-ip`); the call shape consumers send (`?managedObjectId=&domain=`) returns 200. |
| 25 | Default-domain fallback applies to the event path only, not the query API (Q1 + Q7) | `test_default_domain_only_on_event_path` | a `topology.changed` with `domain` absent resolves to `core-ip` (records + `trails.built` carry `core-ip`, WARN logged); the same run's query API still returns 400 for a `domain`-less request — the two paths are independent. |
| 26 | Topology reads pinned to FROZEN shapes + snapshot scoping (Q3) | `test_topology_client_uses_frozen_paths_and_snapshot_scope` | the Topology mock (stubbed from `services/topology/openapi.json`) asserts the client calls `GET /topology/nodes?objectType=&domain=&snapshotId=current` (list-by-type, `NodeListDto`) and `GET /topology/traversal?start=&relation=&maxDepth=&crossDomain=false` (`TraversalDto`); responses decode against the frozen `NodeListDto`/`TraversalDto`/`NodeDto`; the `snapshotId` token passed equals the build's in-scope snapshot. |
| 27 | Persisted + emitted `snapshotId` comes from the triggering event, not a Topology lookup (Q3) | `test_persisted_snapshot_id_from_event_no_topology_resolution` | a `topology.changed (snapshotId=S, core-ip)` build persists trails tagged `snapshotId=S` and emits `trails.built` with `snapshotId=S`; the Topology mock records zero snapshot-resolution calls (only `snapshotId=current`-scoped graph reads). |

### Integration assertion on Simulator-generated data (MVP-achievability fix — the load-bearing AC-2 guarantee)

The unit tests above exercise the closure on small fixture graph slices whose nodes carry an
**injected** `igpArea`. They prove the *prune logic*, but — as the MVP-achievability gate found —
they **cannot** prove the area-bound holds on real data, because the real gap was that no producer
populated `igpArea` at all. The guarantee that the IGP-area bound (and therefore AC-2, the
no-whole-network-trail property) holds **now rests on the integration assertion below**, which
runs against **Simulator-generated** topology (grounded `igpArea` on real Nodes/Interfaces — **not**
`igpArea`-injecting fixtures), on the integration stack (real Topology + real Knowledge + Kafka +
PostgreSQL). It is the regression guard that the upstream fix (Knowledge `attributeCatalogue`
`igpArea` + Simulator emit) stays wired through to Trail Builder's prune.

| ID | Assertion (on Simulator-generated, multi-area topology — Testcontainers / integration stack) | Asserts |
|---|---|---|
| **INT-IGPAREA** | Drive a P1 build from a **Simulator-generated** snapshot configured with multiple IGP areas (`IGP_AREA_COUNT` ≥ 2, e.g. the `p1-demo` profile, `IGP_AREA_COUNT=3`) — the real Topology serves `NodeDto.attributes.igpArea` (grounded, not injected); Trail Builder runs the real closure over the real Knowledge `trailPolicy/default` (`boundary.attributeKey=igpArea`). | (a) **Area-bounded:** for every built trail, all members resolve (via Topology) to a **single** `igpArea` — no trail spans members of two different `igpArea` values. (b) **No whole-network trail (AC-2 on real data):** the multi-area topology yields **multiple** area-bounded trails (more than one trail, and no single trail contains the full connected dependency component / all areas); the largest trail's member count is strictly less than the whole connected-component size. (c) **Grounding precondition:** at least two distinct `igpArea` values are present in the snapshot the build consumed (so the assertion is meaningful — it fails loudly if `igpArea` ever reverts to unpopulated, which is exactly the regression the gate flagged). |

This assertion is the explicit regression guard for the formerly-inert prune: if a future change
drops `igpArea` from the Simulator emission or the Knowledge catalogue/policy, INT-IGPAREA fails
(precondition (c) or area-bound (a)/(b)) rather than the gap silently re-masking behind
`igpArea`-injecting unit fixtures.

### E2E scenarios (from this design unit's point of view)

Service-scoped end-to-end paths the integration stage exercises (Trail Builder + real Topology
+ real Knowledge + Kafka + PostgreSQL), including failure/partial paths.

| # | Scenario | Trigger to path | Expected outcome |
|---|---|---|---|
| 1 | Happy path Core IP build | `topology.changed (core-ip)` then fetch policy + graph slice then compute then persist then emit | `trails.built (core-ip)` with `trailCount == len(trailIds)`; `getTrail`/`listTrails`/`getTrailsForObject` return the persisted, viz-ready trails incl. Interface members. |
| 2 | Overlap + SRLG + area bound together | snapshot with a device on 2 LSPs, 2 links in 1 SRLG, 2 IGP areas | `getTrailsForObject` returns at least 3 trails; SRLG links co-trailed; no trail crosses areas. |
| 3 | Interface-in-trail end to end | snapshot with Port then Interface then IPLink | the built trail `getTrail` member list contains the `Interface:*` entry between Port and IPLink. |
| 4 | Idempotent redelivery | duplicate `topology.changed` (same `eventId`) | exactly one `trails.built`; no duplicate trail/member rows. |
| 5 | Policy refresh then rebuild | `knowledge.updated (trailPolicy, core-ip)` then next `topology.changed (core-ip)` | first event causes no emission; the rebuild uses the refreshed policy bounds. |
| 6 | Two domains, isolation | builds for `core-ip` and a synthetic `domain-B` on the same `snapshotId` | separate Knowledge policy calls; `listTrails` per domain returns only that domain trails; no leakage. |
| 7 | Poison message (failure path) | malformed `topology.changed` then a valid one | poison goes to `topology.changed.dlq`; the valid event still builds + emits; consumer never crashes. |
| 8 | Dependency-down (partial path) | Topology Service down during a build | build held (eventId not marked processed), error logged, `/health` degraded, `build_failures_total` increments; on recovery a redelivery completes the build. |
| 9 | Snapshot supersession | build snapshot S1 then S2 for `core-ip` | S2 trails persisted + emitted; S1 retained as previous; older-than-previous pruned per retention. |
| 10 | web-ui viz consumption | web-ui calls `listTrails` then `getTrail` against the live service | typed members (incl. Interface) returned; web-ui overlays trails without a per-member call. |
| 11 | Enrichment / Codebook trail-tag against frozen path (P1-G4/P1-G10) | Enrichment + Codebook clients (generated from our `openapi.json`) call `GET /trails/by-object?managedObjectId=&domain=` | 200 with `{ managedObjectId, domain, trailIds }`; Enrichment sets `AlarmEvent.trailIds` and Codebook unions `trailIds` — no shape/path adapter needed. |
| 12 | Noise Filter snapshotId provenance (P2-GAP-09) | Noise Filter client calls `getTrail(trailId)` to populate `TransactionEvent.snapshotId` | `getTrail` 200 carries `snapshotId`; Noise Filter resolves the REQUIRED `TransactionEvent.snapshotId` for every emitted transaction; no hold/retry for a missing field. |
| 13 | Consumer query with `domain` (Q1 + Q7) vs. without | Enrichment/Codebook clients call `GET /trails/by-object?managedObjectId=&domain=`; a malformed caller omits `domain` | the `domain`-bearing calls (the real consumer shape) return 200 with `{ managedObjectId, domain, trailIds }`; the `domain`-less call returns 400, never silently scoped to `core-ip`. |
| 14 | Build pinned to frozen Topology shapes + event snapshot (Q3) | `topology.changed (snapshotId=S, core-ip)` against the live Topology Service | the build's graph reads hit the frozen `GET /topology/nodes` (list-by-type, `snapshotId=current`) and `GET /topology/traversal` paths and decode `NodeListDto`/`TraversalDto`; persisted trails and `trails.built` carry `snapshotId=S` taken from the event, with no Topology snapshot-resolution call. |
| 15 | **IGP-area bound on Simulator-generated data (MVP-achievability fix; assertion INT-IGPAREA)** | A **Simulator-generated** multi-area snapshot (`IGP_AREA_COUNT` ≥ 2, grounded `igpArea` per Node/Interface — NOT injected fixtures) flows `topology.changed` then build against real Topology + real Knowledge `trailPolicy/default` (`boundary.attributeKey=igpArea`) | every built trail's members share one `igpArea` (no cross-area trail); the multi-area topology yields **multiple** area-bounded trails, not one whole-network trail (**AC-2 on real data**); at least two distinct `igpArea` values are present in the consumed snapshot. This is the regression guard that the formerly-inert area-prune now fires on real, `igpArea`-bearing data. |

## Config & observability

**Config (env only; no hard-coded URLs/thresholds/domain defaults):**
`TOPOLOGY_SERVICE_BASE_URL`, `TOPOLOGY_SERVICE_MODE` (`mock`/`real`),
`KNOWLEDGE_SERVICE_BASE_URL`, `KNOWLEDGE_SERVICE_MODE`, `KNOWLEDGE_STALE_OK`,
`DATABASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP`,
`TOPOLOGY_CHANGED_TOPIC` / `KNOWLEDGE_UPDATED_TOPIC` / `TRAILS_BUILT_TOPIC` / `*_DLQ_TOPIC`,
`TRAIL_RETENTION_SNAPSHOTS` (default 2), `DEFAULT_DOMAIN` (backward-compat fallback `core-ip`,
applied **only on the Kafka event path** when a legacy `topology.changed` omits `domain` — **never
applied to the HTTP query API**, where `domain` is strictly required with a 400; Q1 + Q7),
`REBUILD_API_TOKEN` (optional),
`HTTP_RETRY_MAX` / `HTTP_RETRY_BACKOFF_MS`, `LOG_LEVEL`. All trail-policy bounds (IGP-area,
SRLG, dependency-edge set) come from Knowledge at build time — never from config.

**Observability:**
- `GET /health` — liveness/readiness incl. DB, Kafka, and Topology/Knowledge reachability;
  503 when not ready.
- `GET /metrics` — Prometheus: `builds_total{domain}`, `build_duration_seconds`,
  `trails_built{domain}`, `build_failures_total{reason}`, `dlq_messages_total`,
  `policy_refreshes_total{domain}`, `query_requests_total{op}`,
  `trail_distinct_igp_areas{domain}` (the count of distinct `igpArea` values seen across the
  built trails — a non-trivial value on a multi-area topology is the runtime signal that the
  area-bound is firing on real data, complementing the INT-IGPAREA integration assertion).
- Structured JSON logs on every path (incl. errors) carrying `traceId`, `snapshotId`, `domain`.

## Build & run

- **Layout:** `services/trail-builder/src/` (modules above), `tests/` (pytest), `pyproject.toml`
  (ruff + black + pytest config), `requirements.txt`, `openapi.json` (checked in), `Dockerfile`,
  `README.md`, Alembic `migrations/`.
- **Lint/format/test:** `ruff check . && black --check . && pytest` (CI gates per `CLAUDE.md`).
- **OpenAPI:** generated by FastAPI; a `make openapi` target dumps `/openapi.json` to the
  checked-in `services/trail-builder/openapi.json` (CI fails if it drifts).
- **Dockerfile:** `FROM python:3.13-slim` (pinned toolchain), install `requirements.txt`,
  `EXPOSE 8000`, run Uvicorn (API + `/health` + `/metrics`) and the Kafka consumer loop.
- **Local run:** `docker compose up trail-builder` on the integration network (real Topology /
  Knowledge / Kafka / PostgreSQL); unit tests run with `*_MODE=mock` and no live dependency.
