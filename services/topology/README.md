# topology

**Cohort:** Spring Boot (Java 17) · **Owned datastores:** NebulaGraph (topology graph, nGQL 9669) +
PostgreSQL schema `topology_meta` (snapshot version metadata only — no graph data).

Sole owner of the network **topology graph** in **NebulaGraph**. Accepts a topology snapshot file via
its ingestion API, lifts the flat records into a typed multi-layer graph, versions every load with a
`snapshotId`, and serves a domain-scoped, typed query API. NebulaGraph + nGQL are internal
implementation details, fully abstracted behind this service's API — no other service touches the
graph store. On every successful ingest it emits a `topology.changed` event (frozen
`TopologyChangedEvent` binding) on the `topology.changed` topic.

See `spec.md` (contract) and `design.md` (how). The HTTP surface is the checked-in
[`openapi.json`](openapi.json) (the single source of truth consumers build against); the snapshot-file
JSON Schema is the single canonical [`schema/snapshot.schema.json`](schema/snapshot.schema.json).

## HTTP surface

- **Ingestion:** `POST /topology/snapshots` — upload a snapshot file; synchronous **200**
  `SnapshotIngestResponse { snapshotId, domain, status, nodeCount, edgeCount, changeType }`; **422**
  on a schema-/semantic-/vocabulary-invalid file (no write, no event); **502** if the domain
  vocabulary is unavailable (fail closed).
- **Query (read-only, typed DTOs only):** `GET /topology/nodes/{managedObjectId}` (layer ==
  objectType), `GET /topology/edges/{edgeId}`, `GET /topology/nodes/{id}/neighbors`,
  `GET /topology/traversal`, `GET /topology/nodes` (filter by `objectType`), `GET /topology/sites`
  (flat geo `SiteDto`), `GET /topology/sites/{siteId}/objects` (nodes **and** edges),
  `GET /topology/snapshots`, `GET /topology/snapshots/current`.
- **Ops:** `/actuator/health` (liveness + readiness — readiness DOWN until the NebulaGraph space is
  usable + PostgreSQL migrated + Kafka reachable), `/actuator/prometheus`, `/openapi.json`,
  `/swagger-ui.html`. Structured JSON logs (`snapshotId`, `domain`, `traceId` in scope).

## Configuration (all from env — no hard-coded URLs, credentials, or thresholds)

| Env var | Meaning | Default |
|---|---|---|
| `TOPOLOGY_PORT` | HTTP port | `8080` |
| `TOPOLOGY_KAFKA_BOOTSTRAP_SERVERS` | Kafka bootstrap | `localhost:9092` |
| `TOPOLOGY_NEBULA_HOSTS` | graphd `host:port` list (internal-only) | `localhost:9669` |
| `TOPOLOGY_NEBULA_SPACE` | graph space name | `topology` |
| `TOPOLOGY_NEBULA_USERNAME` / `_PASSWORD` | graphd credentials (internal-only) | `root` / `nebula` |
| `TOPOLOGY_NEBULA_STORAGED_HOST` | storaged `host:port` for idempotent `ADD HOSTS` | `nebula-storaged:9779` |
| `TOPOLOGY_NEBULA_BOOTSTRAP` | run the startup space/schema bootstrap | `true` |
| `TOPOLOGY_POSTGRES_JDBC_URL` / `_USERNAME` / `_PASSWORD` | snapshot-metadata store (internal-only) | local defaults |
| `TOPOLOGY_KNOWLEDGE_BASE_URL` | Knowledge Service base URL | `http://knowledge:8080` |
| `TOPOLOGY_KNOWLEDGE_MODE` | `mock` (stub from Knowledge OpenAPI) or `real` | `real` |
| `TOPOLOGY_KNOWLEDGE_VOCAB_PATH` | frozen vocab path template | `/domains/{domain}/vocabulary` |
| `TOPOLOGY_INGEST_MAX_FILE_BYTES` | max snapshot upload size | `10485760` |
| `TOPOLOGY_TRAVERSAL_MAX_DEPTH` | traversal depth bound | `8` |

The NebulaGraph + PostgreSQL connection configs are **internal-only** and never forwarded to callers
or logs.

## Build & test

```bash
# from services/topology (consumes the frozen event-model binding via Gradle includeBuild)
./gradlew build              # unit + contract tests + JaCoCo coverage gate (the DoD gate)
./gradlew integrationTest    # Testcontainers: NebulaGraph + PostgreSQL (requires Docker)
```

- **Unit / contract tests (JUnit 5):** validation, lifting, snapshot metadata, the event publisher +
  event-model conformance, the query layer, the `GraphReadService` mapping, the `edgeId` round-trip,
  the orphan reaper, the canonical-schema guard, the WebMvc controller slices, and the OpenAPI
  contract test (which also checks the generated `openapi.json` in). Knowledge is mocked
  (WireMock, from its published OpenAPI); NebulaGraph/Kafka are mocked.
- **Integration tests (`@Tag("integration")`, excluded from `build`):** the nGQL repository
  (bounded traversal, domain isolation, keyed edge fetch), the idempotent schema bootstrap, the
  Interface model end-to-end, the full ingest→query path, and the PostgreSQL `change_type` CHECK
  constraint. They run against **Testcontainers NebulaGraph + PostgreSQL**; if Docker is unavailable
  the classes are **skipped** (assumption), never failed.

## Run (Docker)

Build with the **repo root** as the context (the service consumes the event-model composite build):

```bash
docker build -f services/topology/Dockerfile -t acp/topology:0.1.0 .
# or via the root compose (brings up Kafka + PostgreSQL + NebulaGraph + topology):
docker compose up -d topology
```

The service is exposed on host port **8081** by the compose entry. It runs the idempotent NebulaGraph
`ADD HOSTS` + `CREATE SPACE/TAG/EDGE/INDEX` bootstrap on startup, so it works whether or not a
separate init job ran first.
