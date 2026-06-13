# knowledge

**Cohort:** Spring Boot (Java 17) · **Owned datastore:** PostgreSQL (logical schema `knowledge`)

The authoritative, versioned store for **all authored domain knowledge**. Stores eight record
types under one unified, domain-scoped record model — propagation templates, fault-origin types,
trail policy, model parameters, object-type vocabulary, edge-relation vocabulary, attribute
catalogue, and the alarm-type vocabulary (the canonical value space for `AlarmEvent.alarmType`).
Every write is validated (JSON-Schema + cross-record reference + param bounds), versioned
immutably, and broadcast on the `knowledge.updated` Kafka topic so consumers re-fetch the specific
version via the versioned API. Knowledge **consumes nothing** from Kafka.

Single owner: no other service authors templates / policy / params / vocabulary. Onboarding a new
domain (or a new protocol-adjacency layer) is a **records-only** data operation — no code change.

## API

- CRUD + versioned read for all eight record types: `/domains/{domain}/{recordType}` (kebab-case
  segments, e.g. `propagation-templates`, `model-params`, `object-type-vocabulary`).
  - `POST /domains/{domain}/{recordType}` → 201 + `v1`
  - `PUT /domains/{domain}/{recordType}/{recordId}` → 200 + new version (immutable; old version
    stays retrievable)
  - `GET .../{recordId}` (current) · `GET .../{recordId}/versions/{version}` (pinned)
- Frozen vocabulary query (Topology snapshot validation):
  `GET /domains/{domain}/vocabulary` → `{ domain, objectTypes[], relations[], version }`; 404 for
  an unknown domain.
- OpenAPI 3.1 at `GET /openapi.json` (+ Swagger UI at `/swagger-ui`). The generated document is
  checked in at `services/knowledge/openapi.json` (the provider contract collaborators build
  against).
- Invalid edits → HTTP 422 with a structured `{ error, recordType, domain, violations[] }` body.

## Run

### Tests + build (Gradle, JUnit 5, Testcontainers)

```bash
cd services/knowledge
./gradlew --no-daemon clean build      # unit/contract tests + JaCoCo gate
```

Integration tests use Testcontainers (PostgreSQL) and an embedded Kafka broker — a running Docker
daemon is required. No external services are needed.

### Docker (build from the repo root — composite build includes `libs/event-model`)

```bash
docker build -f services/knowledge/Dockerfile -t acp/knowledge:0.1.0 .
```

### Compose

The service has an entry in the repo-root `docker-compose.yml` (`knowledge`); it depends on
`postgres` and `kafka-init` (topic catalog) and exposes `8080`.

## Configuration (env only — no hard-coded secrets)

| Env var | Default | Meaning |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/correlation` | PostgreSQL JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `correlation` / `correlation` | shared DB role |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `KAFKA_ENABLED` | `true` | enable the `knowledge.updated` producer |
| `KNOWLEDGE_UPDATED_TOPIC` | `knowledge.updated` | produced topic name |
| `SEED_ON_STARTUP` | `true` | load the Core IP domain pack at startup (dogfood-validated) |

Flyway is schema-scoped: it owns **only** the `knowledge` schema (`CREATE SCHEMA IF NOT EXISTS
knowledge`, its own `knowledge.flyway_schema_history`), and touches no other schema.

## Observability

- `GET /health` (Actuator), `GET /metrics` (Micrometer → Prometheus).
- Structured JSON logs (logstash-logback-encoder); custom metrics `knowledge_writes_total`,
  `knowledge_updated_published_total`, `knowledge_updated_publish_failures_total`.

## Seed data (Core IP domain pack)

Loaded at startup through the **same validated write path** (so the seed is dogfood-validated):
object-type / edge-relation vocabularies, a 29-token `alarmTypeVocabulary`, 7 fault-origin types,
28 propagation templates (the full cascade), 4 model-param sets (noise-filter, pattern-miner,
correlation-engine, pattern-manager), a trail policy bounded on `igpArea`, and the attribute
catalogue (incl. the well-known `igpArea` device key). Idempotent — re-running adds nothing.

## Contract

Depends only on the frozen `libs/event-model` Java binding (`Envelope` + `KnowledgeUpdatedEvent`)
and the `knowledge.updated` topic contract — never on another service's source. A new
topic/payload/field is a contract change requiring `docs/architecture.md` update + human approval.

See `spec.md` (contract, 20 acceptance criteria) and `design.md` (how) for full detail.
