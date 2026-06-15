# topology — Service Spec

## Purpose

The Topology Service is the **sole owner of the network topology graph** stored in NebulaGraph
(a standalone distributed graph DB). It accepts a topology snapshot file submitted via its
published ingestion API, lifts the flat records into a typed multi-layer graph (per the §5 layer
and edge model), versions every load with a unique `snapshotId` (snapshot version metadata
persisted in PostgreSQL), and exposes a query API for all callers. NebulaGraph is fully abstracted
behind this service — no other service touches the graph store directly, and nGQL is an internal
implementation detail invisible to callers. On every successful ingest the service emits a
`topology.changed` event (carrying the new `snapshotId`) on the `topology.changed` topic so
downstream consumers (Trail Builder, Codebook Generator, Enrichment, Web UI) can react. The
service operates domain-agnostically: it processes whatever typed nodes and edges appear in the
snapshot file; it does not hard-code Core-IP-specific business logic.

---

## Scope

**In scope:**

- Publish and enforce the **topology ingestion API** (OpenAPI 3.1): accept a topology snapshot
  file, validate it against the versioned topology-file schema, and reject non-conforming files
  with a clear error.
- **Lift** flat snapshot records into the typed multi-layer graph in NebulaGraph, applying the
  node-type and edge-type rules derived from the §5 layer model
  (Node / LineCard / Port / IPLink / IGPAdjacency / LSP / VPNService / FiberSpan / SRLG and
  their typed edges: HOSTED_ON, RIDES_ON, ADJACENCY_OVER, TRAVERSES, SERVES, MEMBER_OF).
- Maintain **`snapshotId` versioning**: every ingest mints a new `snapshotId`; the service
  retains the current snapshot and the immediately preceding one.
- Emit **`topology.changed`** (a `TopologyChangedEvent` carrying `snapshotId`, `changeType`,
  `nodes[]`, `edges[]`) on the `topology.changed` topic after every successful ingest.
- Expose a **query API** (OpenAPI 3.1): get node by `managedObjectId`, get edge, get neighbors,
  bounded traversal by edge type(s), resolve `managedObjectId` to object + layer, list objects
  by type.
- Own and publish the **topology-snapshot file schema** at
  `services/topology/schema/snapshot.schema.json` (versioned contract co-located with the
  owning service); changes to the schema require the same contract-change approval process as a
  new Kafka topic/payload.
- Publish **OpenAPI 3.1** at `/openapi.json` (plus a human-readable UI) and check the generated
  `openapi.json` into `services/topology/`; this spec is the single source of truth for the
  HTTP surface.
- Expose `/health`, `/metrics` (Prometheus), structured JSON logs; config from env.
- Dockerfile and Docker Compose entry.

---

## Out of scope

- **Does not generate topology data.** Topology creation is the Simulator's responsibility
  (or any future topology producer). This service only ingests what producers upload.
- **Does not author propagation templates, fault-origin types, or trail policy.** Those are
  owned by the Knowledge Service.
- **Does not build trails or codebooks.** Trail Builder and Codebook Generator consume topology
  via this service's query API and the `topology.changed` event.
- **Does not enrich, filter, or correlate alarms.** That belongs to the Enrichment, Noise
  Filter, and Correlation Engine services.
- **Does not expose or share the NebulaGraph store.** No other service receives NebulaGraph
  credentials or issues nGQL queries directly; graph data is available only through this
  service's published API.
- **Does not subscribe to any Kafka topic.** Ingestion is exclusively via the file-upload API.
- **Does not own `topology.raw`.** That topic was removed; ingestion is file/API-based.
- **Does not persist alarm or incident data.** All alarm-domain persistence belongs to the
  Pattern Manager and Enrichment services.
- **Domain-specific business logic is out of scope for the engine.** The typed layers come
  from the ingested file; the service does not hard-code Core IP specifics. New domains are
  supported by uploading a conforming snapshot file — no code change required.
- **`delete` changeType is out of scope for MVP.** Only `full-load` and `incremental` are
  supported changeType values in this release; delete operations are deferred.

---

## Tasks (high-level)

1. **Validate and ingest a topology snapshot file.** Accept a file upload via the ingestion
   API, validate it against the topology-file schema at
   `services/topology/schema/snapshot.schema.json` (reject non-conforming files with a
   structured error), and persist the lifted graph into NebulaGraph under a freshly minted
   `snapshotId`.

2. **Lift flat records into the typed multi-layer graph.** Transform snapshot node and edge
   records into the typed graph nodes and edges defined by the §5 layer model, preserving
   `managedObjectId` on every node.

3. **Maintain snapshot versioning.** Track the current and previous `snapshotId` in PostgreSQL;
   make both available for query. A re-ingest always mints a new `snapshotId`, even if the
   content is identical.

4. **Emit `topology.changed`.** After every successful ingest, publish a `TopologyChangedEvent`
   (envelope + payload per the frozen event-model) on `topology.changed` carrying the new
   `snapshotId`, the `changeType` (one of `full-load` or `incremental`), and the full
   `nodes[]` / `edges[]` summaries. Deduplicate on `eventId` to satisfy at-least-once delivery.

5. **Serve the query API.** Answer caller requests: get a node by `managedObjectId`, get an
   edge, get the direct neighbors of a node, perform bounded traversal by one or more edge
   types, resolve a `managedObjectId` to its object and layer, and list all objects of a given
   type. Callers never see NebulaGraph internals.

6. **Manage the NebulaGraph abstraction boundary.** Ensure all graph reads and writes go through
   internal service logic; no NebulaGraph credentials, endpoints, or nGQL query syntax are
   externally reachable.

---

## Phase applicability

The Topology Service's canonical phase-map row (from `docs/architecture.md` → "Runtime phases"):

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Ingests the topology snapshot file via its ingestion API, lifts records into NebulaGraph, mints `snapshotId`, emits `topology.changed`. This is topology's primary phase. | Active | In: topology snapshot file (via `POST /topology/snapshots` ingestion API). Out: `topology.changed` (Kafka). |
| P2 — Pattern learning | Serves its graph query API to consumers that need topology context (Trail Builder, Codebook Generator, Enrichment); drives no work of its own. | Passive | In: — . Out: graph query API responses (`GET /topology/nodes/…`, neighbors, traversal, resolve, list). No topic I/O. |
| P3 — Real-time correlation | Not involved. No real-time consumer queries the Topology Service: Enrichment (live) tags via Trail Builder `getTrailsForObject`, and Correlation Engine reads patterns + codebook — topology context is already materialized into trails + codebook during P1. The graph query API remains available but is off the real-time critical path. | Idle | In: — . Out: — (no real-time consumer; nothing driven or served on the critical path). |

---

## Contract

- **Consumes (Kafka):** — (none; all ingestion is via the HTTP ingestion API)

- **Produces (Kafka):** `topology.changed`
  - Payload type: `TopologyChangedEvent` (frozen binding from `libs/event-model`)
  - Fields: `snapshotId` (string), `changeType` (string), `nodes` (array of typed node
    descriptors), `edges` (array of typed edge descriptors)
  - Envelope: standard envelope — `eventId` (UUID, idempotency key), `type`, `schemaVersion`,
    `occurredAt`, `source`, `traceId`, `payload`
  - Failed-delivery fallback: `topology.changed.dlq`
  - **`changeType` convention (MVP):** the frozen `TopologyChangedEvent.schema.json` keeps
    `changeType` as a free-form string. For the MVP, the Topology Service emits only two values:
    - `full-load` — a complete snapshot replacement (the graph is replaced in full).
    - `incremental` — a partial update to the existing graph.
    The value `delete` is deferred and out of scope for MVP. This is a documented convention in
    this spec; the event-model schema is not modified (no enum constraint is added). Consumers
    should handle other future values gracefully.

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json` + checked-in
  `services/topology/openapi.json`; a change to this surface is a **contract change**):

  The response shapes below are **frozen** (data-integration freeze — gaps **P1-G1, P1-G7, P1-G8,
  P1-G9** in `docs/design-gaps.md`) and are the **single source of truth** consumers (the Simulator
  upload client, the web-ui typed clients) build against; the checked-in `openapi.json` is authoritative.

  *Ingestion API:*
  - `POST /topology/snapshots` — upload a topology snapshot file; **frozen response: `200`
    (synchronous)** with body `SnapshotIngestResponse { snapshotId, domain, status, nodeCount,
    edgeCount, changeType }` (`snapshotId` minted inline during the lift; `snapshotId` + `status`
    are the mandatory minimum), or a structured validation error on rejection (`422`). **P1-G1**:
    `200` (not `202`) because the lift is synchronous; consumers read `snapshotId` from the `200`
    body. Validates against the single canonical topology-file schema at
    `services/topology/schema/snapshot.schema.json`.

  *Query API:*
  - `GET /topology/nodes/{managedObjectId}` — return the node object and its layer. **Frozen
    response: `NodeDto { managedObjectId, objectType, domain, snapshotId, name?, attributes }`** with
    **no separate `layer` field**; **P1-G9**: `layer == objectType` (documented derivation; consumers
    map `objectType` to `layer`).
  - `GET /topology/edges/{edgeId}` — return the edge object (`EdgeDto { edgeId, from, to, relation,
    domain, attributes, snapshotId }`).
  - `GET /topology/nodes/{managedObjectId}/neighbors` — return direct neighbors (optionally
    filtered by edge type).
  - `GET /topology/traversal` — bounded traversal from a start `managedObjectId` over
    specified edge type(s), up to a caller-supplied depth bound. **Frozen response: `TraversalDto
    { start, domain, relations, maxDepth, crossDomain, reached: NodeDto[], edges: EdgeDto[] }`** —
    `reached` are the distinct nodes reachable within the bound, and **`edges` (#252) are the typed
    directed edges of that closure**: every edge whose `from` and `to` are both in the closure node
    set (`start` + `reached`) **and** whose `relation` is one of the requested `relations` (the
    traversal is **relation-scoped**, so only the requested edge types appear). The edges let
    consumers (e.g. the Codebook Generator's forward-propagation) walk the cascade rather than
    seeing isolated nodes; an edge-less closure returns an empty (never null) `edges` array.
  - `GET /topology/nodes` — list nodes, filterable by `objectType`.
  - `GET /topology/sites` — list sites. **Frozen response: `SiteListDto { domain, snapshotId, count,
    sites: SiteDto[] }`** where **`SiteDto { siteId, name, latitude, longitude, region }`** has
    **flat per-site geo fields** (`siteId` = the Site node's `managedObjectId`); **P1-G7**.
  - `GET /topology/sites/{siteId}/objects` — list the objects at a site **plus the edges** for the
    device graph. **Frozen response: `SiteObjectsDto { siteId, domain, snapshotId, nodeCount,
    edgeCount, nodes: NodeDto[], edges: EdgeDto[] }`** — **nodes AND edges**; **P1-G8**.
  - `GET /topology/snapshots` — list available snapshots (at minimum: current + previous).
  - `GET /topology/snapshots/current` — return the current `snapshotId` and summary.

- **APIs/data consumed from other services:** — (none required for core function; this service
  is a source). If lifting validation ever requires Knowledge Service parameters (e.g., allowed
  edge-type vocabulary), that is a config-switchable integration point (see below); it is not
  required for the MVP.

- **Integration points (mock vs. real):**
  - The topology service is primarily a **server** (not a client). Its consumers — Trail
    Builder, Codebook Generator, Enrichment, Web UI, and the Simulator (as ingestion client) —
    build their clients against the topology service's published OpenAPI.
  - If a Knowledge Service integration point is introduced (e.g., to fetch lifting parameters
    such as the per-domain object-type / edge-relation vocabulary), it must be config-switchable:
    mock (generated from Knowledge Service's published OpenAPI) for unit tests; real Knowledge
    Service for integration. The base URL and mock/real toggle must be settable via environment
    variable. The concrete operation this keys off is Knowledge's **frozen**
    `GET /domains/{domain}/vocabulary` → `{ domain, objectTypes[], relations[], version }`
    (`404` for an unknown domain); the integration must carry a **real, startable default path**
    for that operation so the service starts in isolation/test with no per-environment override
    (see `design.md` for the wired client + default).

- **Data owned:**
  - **NebulaGraph topology graph** — the typed multi-layer topology graph; sole owner; internal
    only; never shared as a store. All graph reads and writes go through this service's API; no
    other service receives NebulaGraph connection details or issues nGQL queries directly.
  - **PostgreSQL snapshot version metadata** — snapshot bookkeeping records (`snapshotId`,
    `domain`, timestamps, ingest audit); owned by this service's PostgreSQL schema; internal
    only; never shared as a store. Graph data lives in NebulaGraph; this store holds only the
    versioning and audit metadata that tracks each snapshot.
  - **Topology-snapshot file schema** — owned by this service at the **single canonical home**
    `services/topology/schema/snapshot.schema.json` (**P1-G2**). This is the **one** checked-in
    copy of the schema: the Simulator (producer) validates its generated file against **this same**
    file — there is **no** independent `services/simulator/schema/...` copy and a CI lockstep guard
    fails the build on any fork or drift. This file is a versioned contract; it is NOT a Kafka
    payload and does NOT reside in `libs/event-model` (it is an HTTP-upload body, not an event-model
    payload). Schema changes are a `services/topology` PR, still contract-gated (an
    `architecture.md`/spec update + human approval is required, exactly as for a new Kafka
    topic/payload). The designer authors the actual JSON Schema file at this path.

    **Approved field-level definition** (this is what the ingestion API validates against):

    Top-level object:
    - `schemaVersion` (integer, required) — the topology-file schema version, independent of
      the event-model `schemaVersion`.
    - `snapshotId` (string, optional) — producer-supplied identifier. If absent, the Topology
      Service mints one on ingest.
    - `domain` (string, required) — the domain-pack identifier (e.g. `core-ip`); supports
      multi-domain per `architecture.md` "Domain extensibility".
    - `nodes` (array, required) — each element is a node object:
      - `managedObjectId` (string, required) — must match the pattern
        `^(Node|LineCard|Port|IPLink|IGPAdjacency|LSP|VPNService|FiberSpan|SRLG):[^:]+$`.
      - `objectType` (string, required) — one of the 9 known types; must be consistent with the
        prefix of `managedObjectId`.
      - `name` (string, optional).
      - `properties` (object, optional) — free-form per-node attributes.
    - `edges` (array, required) — each element is an edge object:
      - `from` (string, required) — a `managedObjectId` present in `nodes`.
      - `to` (string, required) — a `managedObjectId` present in `nodes`.
      - `relation` (string, required) — one of the §5 typed edge vocabulary:
        `HOSTED_ON`, `RIDES_ON`, `ADJACENCY_OVER`, `TRAVERSES`, `SERVES`, `MEMBER_OF`.
      - `properties` (object, optional) — free-form per-edge attributes.

---

## Non-functional

- **Idempotency key:** `eventId` (UUID) on emitted `topology.changed` events. Kafka producer
  must be configured for explicit idempotent delivery. A re-ingest of the same file content
  still mints a new `snapshotId` and emits a new event with a new `eventId`; the deduplication
  guarantee applies to delivery of a given event, not to the ingest operation itself.

- **Config:** all integration URLs, Kafka bootstrap addresses, NebulaGraph connection details
  (host, port, space, credentials), PostgreSQL connection details (JDBC URL, credentials), and
  any tunable limits (e.g., max snapshot file size, snapshot retention count) must be supplied
  via environment variables. No hard-coded URLs, credentials, or thresholds. Both the
  NebulaGraph and PostgreSQL connection configs are internal-only and must never be forwarded to
  callers. Knowledge-Service parameters (if used) are fetched at startup or refresh and must
  have a default for isolated testing.

- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus), structured JSON
  logs on every significant operation (ingest received, validation result, snapshot minted,
  event emitted, query served, error). Log the `snapshotId` and `traceId` on every log line
  where they are in scope.

- **API contract:** publishes OpenAPI 3.1 at `/openapi.json` and checks the generated file into
  `services/topology/openapi.json`. The service's own OpenAPI spec drives its contract/unit
  tests (request/response schema validation); collaborating services integrate against it. A
  change to the OpenAPI surface is a contract change.

- **`changeType` convention:** for MVP, the only emitted `changeType` values are `full-load`
  and `incremental`. The `delete` value is deferred. The frozen `TopologyChangedEvent` schema
  keeps `changeType` as a free-form string — no enum constraint is added to the event-model.

- **Error handling:**
  - Invalid or non-conforming snapshot file → HTTP 422 with a structured validation error body;
    no partial write to NebulaGraph; no `topology.changed` emitted.
  - `topology.changed` delivery failure → route to `topology.changed.dlq`.
  - Unknown `schemaVersion` (major >= 2) in any consumed event binding → reject per event-model
    policy.

- **Test framework:** JUnit 5 (unit/contract tests); Testcontainers for integration tests
  (NebulaGraph + PostgreSQL + Kafka). Unit tests use mocked/stubbed NebulaGraph and Kafka;
  contract tests validate against the checked-in `openapi.json`.

- **Domain-agnostic operation:** the service must not hard-code Core IP object types or edge
  types in business logic; typed layers are driven by the snapshot file and the versioned
  topology-file schema. New domains load without code changes.

---

## Acceptance criteria

Each criterion maps to a single unit test (JUnit 5).

1. **Snapshot load and queryability.** Given a valid topology snapshot file with N nodes and M
   edges submitted to `POST /topology/snapshots`, the service returns **HTTP 200** with the frozen
   `SnapshotIngestResponse { snapshotId, domain, status, nodeCount, edgeCount, changeType }` body
   (**P1-G1**: 200 synchronous, not 202), and subsequent calls to the query API return the correct
   node and edge data. NebulaGraph credentials and nGQL endpoints are not reachable from outside the
   service process.

2. **Schema validation — accept conforming file.** A snapshot file fully conforming to
   `services/topology/schema/snapshot.schema.json` (all required fields present: `schemaVersion`,
   `domain`, `nodes[]`, `edges[]`; each node has a valid `managedObjectId` matching the pattern
   and a consistent `objectType`; each edge has `from`, `to`, `relation` referencing known
   vocabulary) is accepted with HTTP 200 and the payload is persisted to NebulaGraph.

3. **Schema validation — reject missing required field.** A snapshot file missing a top-level
   required field (e.g., absent `domain`, absent `schemaVersion`, or absent `nodes`) is rejected
   with HTTP 422 and a structured error body; no partial write is made to NebulaGraph; no
   `topology.changed` event is emitted.

4. **Schema validation — reject invalid `managedObjectId` scheme.** A snapshot file containing
   a node whose `managedObjectId` does not match the pattern
   `^(Node|LineCard|Port|IPLink|IGPAdjacency|LSP|VPNService|FiberSpan|SRLG):[^:]+$` is
   rejected with HTTP 422 before any write to NebulaGraph.

5. **Schema validation — reject inconsistent `objectType`.** A snapshot file where a node's
   `objectType` is inconsistent with its `managedObjectId` prefix (e.g., `managedObjectId` is
   `Port:p1` but `objectType` is `Node`) is rejected with HTTP 422 before any write to
   NebulaGraph.

6. **Schema validation — reject dangling edge reference.** A snapshot file containing an edge
   whose `from` or `to` `managedObjectId` is not present in the `nodes` array is rejected with
   HTTP 422 before any write to NebulaGraph.

7. **Schema validation — reject unknown edge relation.** A snapshot file containing an edge
   whose `relation` is not one of `HOSTED_ON`, `RIDES_ON`, `ADJACENCY_OVER`, `TRAVERSES`,
   `SERVES`, `MEMBER_OF` is rejected with HTTP 422 before any write to NebulaGraph.

8. **Producer-supplied `snapshotId` is honoured.** When a snapshot file includes a `snapshotId`
   field, the ingestion API uses that value as the `snapshotId` returned in the 200 response and
   carried in the emitted `topology.changed` event.

9. **Service-minted `snapshotId` when absent.** When a snapshot file omits the `snapshotId`
   field, the service mints a unique non-empty `snapshotId` and returns it in the 200 response.

10. **Lifting rules — typed multi-layer graph.** After ingesting a snapshot containing at least
    one node of each of the nine `managedObjectId` object types
    (Node, LineCard, Port, IPLink, IGPAdjacency, LSP, VPNService, FiberSpan, SRLG) and their
    typed edges, the query API returns each node with the correct `objectType` and each edge
    with the correct typed relation (HOSTED_ON, RIDES_ON, ADJACENCY_OVER, TRAVERSES, SERVES,
    MEMBER_OF as applicable).

11. **Bounded traversal by edge type.** Given a known synthetic topology, a bounded traversal
    request over a specified edge type (e.g., RIDES_ON) from a given start node returns exactly
    the expected set of reachable nodes within the depth bound, and no nodes reachable only via
    other edge types. The response also includes **`edges` (#252)** — the typed directed edges of
    the closure (every edge whose both endpoints are in `start` + `reached` and whose `relation` is
    one of the requested relations); the edges carry the correct `from`/`to`/`relation`, include the
    origin's outgoing edge(s), and an edge-less closure returns an empty (never null) `edges` array.

12. **`managedObjectId` resolution.** `GET /topology/nodes/{managedObjectId}` with a valid
    `<objectType>:<id>` value returns the node object and its layer; an unknown
    `managedObjectId` returns HTTP 404.

13. **List objects by type and get neighbors.** `GET /topology/nodes?objectType=Port` returns
    all Port nodes and no nodes of other types. `GET /topology/nodes/{id}/neighbors` returns
    all directly connected nodes.

14. **Snapshot versioning — new `snapshotId` on re-ingest.** Submitting a second snapshot file
    (with any change) causes the service to mint a new `snapshotId` distinct from the first;
    both the current and previous `snapshotId` are available via `GET /topology/snapshots`.

15. **`topology.changed` emission on first ingest — `full-load`.** After the very first
    successful ingest, exactly one `topology.changed` event is emitted with `changeType` equal
    to `full-load`; its payload deserialises correctly against the frozen `TopologyChangedEvent`
    Java binding from `libs/event-model`; the `snapshotId` in the event matches the `snapshotId`
    returned by the ingestion API.

16. **`changeType` values are within the approved convention.** The service never emits a
    `topology.changed` event with a `changeType` outside the set `{ full-load, incremental }`;
    specifically `delete` is never emitted in the MVP.

17. **`topology.changed` event-model conformance.** The emitted `topology.changed` event
    validates against the frozen `TopologyChangedEvent.schema.json` from `libs/event-model`
    (all required fields present: `snapshotId`, `changeType`, `nodes`, `edges`; envelope fields
    present: `eventId`, `type`, `schemaVersion`, `occurredAt`, `source`, `traceId`, `payload`).

18. **OpenAPI 3.1 contract.** The service serves its OpenAPI document at `/openapi.json`; the
    document includes both the ingestion endpoint (`POST /topology/snapshots`) and all query
    endpoints; a contract test confirms that the live service response for each operation
    matches the schema declared in the checked-in `openapi.json`.

19. **NebulaGraph abstraction boundary.** No HTTP endpoint, environment variable, log line, or
    response body exposes a NebulaGraph connection string, NebulaGraph endpoint, or raw nGQL
    query result structure. All graph data is returned through the service's typed API response
    shapes.

20. **Ingestion returns the frozen `SnapshotIngestResponse` (P1-G1).** `POST /topology/snapshots`
    on a valid file returns **HTTP 200** (not 202) with body `{ snapshotId, domain, status,
    nodeCount, edgeCount, changeType }`; `snapshotId` is non-empty and equals the `snapshotId`
    carried in the emitted `topology.changed` event; the live response validates against the
    `SnapshotIngestResponse` schema in the checked-in `openapi.json`.

21. **`GET /topology/sites` returns the frozen flat `SiteDto` (P1-G7).** The response is
    `SiteListDto { domain, snapshotId, count, sites: [...] }` and **each site is
    `{ siteId, name, latitude, longitude, region }`** with **flat top-level geo fields** (not
    nested under `attributes`, not a raw `NodeDto`); `siteId` equals the Site node's
    `managedObjectId`; the response validates against the checked-in `openapi.json`.

22. **`GET /topology/sites/{siteId}/objects` returns nodes AND edges (P1-G8).** The response is
    `SiteObjectsDto { siteId, domain, snapshotId, nodeCount, edgeCount, nodes: NodeDto[],
    edges: EdgeDto[] }`; `nodes` are the devices `LOCATED_AT` the site and `edges` are the
    intra-site edges plus the `LOCATED_AT` edges to the site (each an `EdgeDto`); an unknown
    `siteId` returns HTTP 404; the response validates against the checked-in `openapi.json`.

23. **`GET /topology/nodes/{managedObjectId}` returns the frozen `NodeDto`; `layer == objectType`
    (P1-G9).** The response is exactly `NodeDto { managedObjectId, objectType, domain, snapshotId,
    name?, attributes }` with **no separate `layer` field**; the design + `openapi.json` document
    that `layer` is `objectType`; the response validates against the checked-in `openapi.json`.

24. **Single canonical snapshot-file schema (P1-G2).** A conforming snapshot file validates against
    the single checked-in schema at `services/topology/schema/snapshot.schema.json` and a malformed
    one fails; the canonical schema exists at exactly that one path; there is **no** independent
    `services/simulator/schema/...` copy; the Simulator (producer) references this same file, so
    producer and validator cannot diverge (CI lockstep guard).

25. **Interface model lifts and is queryable end-to-end (Core-IP §5 layering).** Interface is a
    first-class object in the Core IP topology — IP links terminate on interfaces, IGP/BGP
    adjacencies run between interfaces, and an interface is a first-class fault origin — so the
    interface layering must be a tested guarantee, not implicit. Given a snapshot file containing
    `Interface` nodes plus `HOSTS` (Port → Interface), `TERMINATES` (Interface → IPLink), and
    `ADJACENCY_OVER` (Interface → IGPAdjacency) edges:
    - **Lift.** The snapshot lifts correctly into the typed graph: each `Interface` node becomes a
      typed `Interface` vertex (objectType `Interface`), and the `HOSTS`, `TERMINATES`, and
      `ADJACENCY_OVER` edge types are all created on the correct endpoints — no partial or dropped
      interface data.
    - **Layering is queryable.** `GET /topology/nodes?objectType=Interface` lists exactly the
      interfaces; the neighbors/traversal API resolves the layering `Port —HOSTS→ Interface
      —TERMINATES→ IPLink` (from a Port, the `HOSTS` neighbor is its Interface; from that Interface,
      the `TERMINATES` neighbor is its IPLink); and `ADJACENCY_OVER` resolves **between interfaces** —
      an interface's `ADJACENCY_OVER` neighbor is the IGPAdjacency, reflecting that adjacencies run
      between interfaces, not directly between nodes.
    - **Resolution.** `GET /topology/nodes/{managedObjectId}` for an `Interface:*` id returns the
      object as a typed `NodeDto` with `objectType` equal to `Interface` (its layer == objectType).
    - **Domain-agnostic.** `Interface` and the `HOSTS` / `TERMINATES` / `ADJACENCY_OVER` relations are
      validated against the snapshot domain's Knowledge-authored vocabulary like any other
      type/relation (an interface type/relation absent from the domain's vocabulary is rejected with
      HTTP 422 per AC-7/AC-7b); there is **no** Interface-specific code path. This criterion guarantees
      that the Core-IP Interface model is exercised through the generic typed-graph machinery.

---

## Open questions

None. All open questions from the spec phase have been resolved and folded into the spec above
(schema file location and field definition — issues #18 and #19; `changeType` vocabulary
convention — issue #20). Any remaining decisions about internal design (e.g., how the designer
structures nGQL queries, Spring module layout, lifting algorithm) are design-stage decisions and
belong in `design.md`.
