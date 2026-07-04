# correlation-engine

**Cohort:** Spring Boot · **Owned datastore:** PostgreSQL (Incident Store, schema `incident`)

The real-time correlation core + **system of record for incidents** (P3). It consumes live
persisted alarms and correlates them by creating, advancing, and concluding **correlation
instances** — one per `(trailId, patternId)` — against approved patterns and the latest in-scope
codebook. An instance is born lazily on the first matching alarm, re-matches incrementally, and
either fully matches and fires immediately (tag root cause, persist incident, emit
`CorrelationResultEvent`, fire `AlarmStatusChange(correlated)`, then destroy) or its per-pattern
session window expires (destroy, no incident, fire `AlarmStatusChange(reverted-open)`). It persists
incidents to the owned `incident` schema and serves a read API for the web-ui Correlation Stats
module. See `spec.md` (contract) and `design.md` (how).

## Contract (frozen — no new topic/payload/field)

| Direction | Topic | Payload (event-model) |
|---|---|---|
| In | `alarms.persisted.live` | `AlarmEvent` |
| In | `patterns.approved` | `PatternApprovedEvent` (refresh trigger; `trailId` from the Pattern Manager read API) |
| In | `codebook.generated` | `CodebookGeneratedEvent` (summary; full signatures fetched via API) |
| In | `trails.built` | `TrailsBuiltEvent` (refresh trigger — rebuilds the pattern-generalization compatibility index on a new topology snapshot) |
| Out | `correlation.results` | `CorrelationResultEvent` |
| Out | `alarms.status.changed` | `AlarmStatusChange` (`source = correlation-engine`) |

Poison messages route to `<topic>.dlq` (never dropped). Consumers are idempotent — alarms dedupe on
`alarmId`, events on `eventId`.

## Read API (OpenAPI 3.1 at `/openapi.json`, checked in to `openapi.json`)

- `GET /incidents` — canonical `{ items, total, limit, offset }` envelope; filters `trailId`,
  `from`/`to`, `matchType` (`pattern`|`codebook`), `limit` (≤500), `offset`. Each item carries
  `rootCauseAlarmId`, `rootCauseAlarmType`, `childAlarmIds[]`, `matchedPatternId?`,
  `matchedCodebookId?`, `confidence`, `trailId`, `createdAt`.
- `GET /incidents/{incidentId}` — a single incident (same shape); `404` if absent.
- `GET /stats` — `totalAlarmsProcessed`, `correlatedAlarmCount` (auto-correlation-rate numerator),
  `totalIncidentsCreated`, `patternMatchCount`, `codebookMatchCount`, `confidenceDistribution`,
  `rcaAccuracy` (eval-mode only; `null` in production).

## Operational endpoints

- `GET /actuator/health` — liveness + readiness (readiness gates on Knowledge params loaded).
- `GET /actuator/prometheus` — Micrometer/Prometheus metrics (`incidents_created_total`,
  `alarms_processed_total`, `pattern_match_total`, `codebook_match_total`,
  `instance_session_expirations_total`, `alarms_status_changed_total`, `dlq_routed_total`, …).
- Structured JSON logs (Logstash encoder). Config is from the environment only — no secrets in code.

## Configuration (environment)

| Variable | Default | Meaning |
|---|---|---|
| `INTEGRATION_MODE` | `real` | `mock` (stub collaborators) or `real` (Compose services) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `KAFKA_ENABLED` | `true` | disable to load the read API without a broker |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/acp` / `acp` / `acp` | Incident Store |
| `PATTERN_MANAGER_BASE_URL` | `http://pattern-manager:8080` | Pattern Manager read API |
| `CODEBOOK_GENERATOR_BASE_URL` | `http://codebook-generator:8080` | Codebook Generator API |
| `KNOWLEDGE_BASE_URL` | `http://knowledge:8080` | Knowledge Service API |
| `KNOWLEDGE_DOMAIN` | `core-ip` | Knowledge domain |
| `KNOWLEDGE_REFRESH_MS` | `60000` | match-params cache TTL |
| `TRAIL_BUILDER_BASE_URL` | `http://trail-builder:8080` | Trail Builder read API (pattern-generalization compatibility index) |
| `TRAIL_BUILDER_MODE` | _(falls back to `INTEGRATION_MODE`)_ | `mock` \| `real` for the Trail Builder integration point |
| `TRAIL_BUILDER_MAX_RETRIES` | `2` | bounded per-trail member-fetch retry count (fetch failure omits the trail from the index) |
| `EXPIRY_TICK_MS` | `1000` | session-expiry / uncovered-buffer decode cadence |
| `RCA_EVAL_MODE` | `off` | `on` enables the eval-mode `rcaAccuracy` when a labels oracle is wired |
| `SERVER_PORT` | `8080` | HTTP port |

All match-quality / conflict thresholds are sourced from the Knowledge Service
(`GET /domains/{domain}/model-params/core-ip/modelParams/correlation-engine`) — none hard-coded. The
per-pattern session window comes from each pattern's `sessionWindow` field (Pattern Manager).

## Build & run

```bash
# Unit + contract tests + JaCoCo (the single green gate):
./gradlew --no-daemon clean build

# Testcontainers integration tests (real PostgreSQL — Docker required):
./gradlew --no-daemon integrationTest

# Container image (build from the REPO ROOT — composite build needs libs/event-model):
docker build -f services/correlation-engine/Dockerfile -t acp/correlation-engine:0.1.0 .
docker run --rm -p 8080:8080 \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 -e DB_URL=jdbc:postgresql://postgres:5432/acp \
  acp/correlation-engine:0.1.0
```

## Design notes / resolved ambiguities

- **Instance state structure (Open Question 2):** the design proposes a Kafka Streams RocksDB store
  keyed by `(trailId, patternId)`. This build realizes the same **observable behaviour** with the
  Kafka-free `CorrelationEngine` core (in-memory `(trailId, patternId)` registry) driven by
  `@KafkaListener` consumers + a scheduled wall-clock expiry tick. Every acceptance criterion
  (isolation, per-pattern windows, lazy init, incremental match, idempotency) is asserted against
  this core. Persistent restart-recovery of in-flight instances (the RocksDB changelog benefit) is
  the one property not carried over; incidents (the durable system of record) are fully persisted.
