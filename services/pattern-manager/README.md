# pattern-manager

**Cohort:** Spring Boot (Java 17, Gradle) · **Owned datastore:** PostgreSQL, schema `pattern` (the Pattern Store)

Sole owner of the pattern domain. Consumes `patterns.mined`, enriches each mined pattern
(RCA + structural validation + codebook reconciliation + session-window derivation + XAI),
persists it to the Pattern Store, drives the human-approval lifecycle via its HTTP API, and is
the only emitter of `PatternDiscoveredEvent` / `PatternApprovedEvent` on `patterns.discovered` /
`patterns.approved`. See `spec.md` (contract) and `design.md` (how).

## HTTP surface

- OpenAPI 3.1 at `/openapi.json` (checked-in SSoT: `openapi.json`); Swagger UI at `/swagger-ui`.
- `GET /patterns` — list (PatternPage envelope), filterable by lifecycle.
- `GET /patterns/{id}` — full PatternView (XAI + sessionWindow).
- `POST /patterns/{id}/approve` — approve/reject a draft (200 / 404 / 409 / 422).
- `PATCH /patterns/{id}` — edit a draft (200 / 404 / 409 / 422).
- `POST /patterns/{id}/deprecate` — deprecate a draft or approved pattern.
- `GET /health` (liveness/readiness probes) · `GET /metrics` (Prometheus).

## Build & test

```bash
# From this directory (self-contained Gradle; composite-builds libs/event-model/java):
./gradlew --no-daemon clean build     # unit + contract tests + JaCoCo gate (integration-tagged excluded)
```

## Run (Docker)

The image builds from the **repository root** context (the Gradle composite build includes
`libs/event-model/java`):

```bash
# From the repo root:
docker build -f services/pattern-manager/Dockerfile -t acp/pattern-manager:0.1.0 .
docker compose up -d pattern-manager   # published on host :8090 -> container :8080
```

## Configuration (environment variables)

All config is supplied from the environment — no hard-coded URLs, credentials, or thresholds.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP port (container-internal). |
| `DB_URL` | `jdbc:postgresql://localhost:5432/correlation` | Pattern Store JDBC URL (schema `pattern`, Flyway-migrated). |
| `DB_USER` | `correlation` | Pattern Store username. |
| `DB_PASSWORD` | `correlation` | Pattern Store password. |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers. |
| `KAFKA_ENABLED` | `true` | Enable the `patterns.mined` listener (set `false` to run API-only). |
| `KAFKA_CONSUMER_GROUP_ID` | `pattern-manager-patterns.mined` | Consumer group id. |
| `PATTERNS_MINED_TOPIC` | `patterns.mined` | Consumed topic (mined patterns). |
| `PATTERNS_MINED_DLQ_TOPIC` | `patterns.mined.dlq` | DLQ for un-processable mined events (poison → DLQ, never dropped). |
| `PATTERNS_DISCOVERED_TOPIC` | `patterns.discovered` | Emitted draft-pattern topic. |
| `PATTERNS_APPROVED_TOPIC` | `patterns.approved` | Emitted approved-pattern topic. |
| `INTEGRATION_MODE` | `real` | Collaborator wiring: `real` (live) or `mock` (WireMock for unit tests). |
| `TOPOLOGY_BASE_URL` | `http://topology:8080` | Topology Service base URL (structural validation). |
| `TOPOLOGY_DOMAIN` | `core-ip` | Topology domain. |
| `CODEBOOK_BASE_URL` | `http://codebook-api:8000` | Codebook API base URL (reconciliation). |
| `CODEBOOK_DOMAIN` | `core-ip` | Codebook domain. |
| `KNOWLEDGE_BASE_URL` | `http://knowledge:8080` | Knowledge Service base URL (policy/params). |
| `KNOWLEDGE_DOMAIN` | `core-ip` | Knowledge domain. |
| `SESSION_WINDOW_MARGIN_FACTOR` | `1.5` | Session-window derivation: margin multiplier. |
| `SESSION_WINDOW_MIN_MS` | `5000` | Session-window derivation: floor (ms). |
| `SESSION_WINDOW_MAX_MS` | `1800000` | Session-window derivation: ceiling (ms). |
| `SESSION_WINDOW_GAP_FLOOR_FACTOR` | `2.0` | Session-window derivation: gap-floor multiplier. |
| `SESSION_WINDOW_CV_FIXED_THRESHOLD` | `0.5` | Session-window derivation: CV threshold for fixed vs gap-based. |

## Kafka semantics

At-least-once with explicit manual acks. Idempotent on the envelope `eventId`
(`processed_event` dedupe — a redelivered event is a no-op). Un-processable records (malformed
JSON, unsupported `schemaVersion` major, unexpected type) are routed to `patterns.mined.dlq`
with `error`/`errorClass` headers and never persisted.
