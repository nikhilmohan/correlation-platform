# alarm-manager

Spring Boot service — **sole owner of live alarm state**. On the P3 real-time path the Alarm
Manager sits **in-line** between the Enrichment Service and the Correlation Engine: it consumes
`alarms.enriched.live`, **persists** each live alarm (initial lifecycle state `open`) into its
owned `live_alarm` PostgreSQL schema, and **republishes** the same `AlarmEvent` on
`alarms.persisted.live` for the Correlation Engine (persist-first makes the Alarm Manager the
system of record for live alarms). It maintains each alarm's lifecycle **STATE** from
`alarms.status.changed` (`AlarmStatusChange`) and its correlation-group **ROLE** + incident
linkage from `correlation.results` (`CorrelationResultEvent`), and serves the live alarm query API
to the web-ui. There is **no historical corpus** — the MVP is live-only. See `spec.md` / `design.md`.

## Role on the event path (contract — frozen)

| Direction | Topic | Payload (event-model binding) |
|---|---|---|
| Consume | `alarms.enriched.live` | `AlarmEvent` |
| Consume | `alarms.status.changed` | `AlarmStatusChange` (canonical STATE channel) |
| Consume | `correlation.results` | `CorrelationResultEvent` (canonical ROLE + incident channel) |
| Produce | `alarms.persisted.live` | `AlarmEvent` (republished after persist — no new payload) |
| DLQ | `alarms.enriched.live.dlq` / `alarms.status.changed.dlq` / `correlation.results.dlq` | raw bytes + failure headers |

- **STATE vs. ROLE are complementary, disjoint-column channels.** `AlarmStatusChange` is
  authoritative for lifecycle STATE (`open` / `in-progress` / `correlated` / `cleared`;
  `reverted-open` is a transition back to `open` with an audit reason). `CorrelationResultEvent`
  is authoritative for ROLE (`root-cause` / `child`) + `incidentId`. Reconciled on `alarmId`, in
  any arrival order.
- **Idempotency.** `alarmId` for persist + republish (PK upsert + `published` guard);
  envelope `eventId` for the two event-driven channels (shared `processed_event` guard). Kafka is
  at-least-once — no duplicate records, republishes, or audit entries on redelivery.
- **`alarmType`** is the platform-canonical alarm-type join token (distinct from `eventType` /
  `probableCause`); it is persisted in its own column and returned on both query DTOs.

## Query API (consumed by web-ui)

- `GET /alarms` — list/filter live alarms by `state` (incl. `in-progress`), `trailId`,
  `incidentId`, `from`/`to` (ISO-8601 UTC), paginated with `limit` (default 50, max 500) /
  `offset`. Returns the **platform-canonical** list-pagination envelope
  `{ items, total, limit, offset }` (the same shape as Correlation Engine `GET /incidents` and
  Pattern Manager `GET /patterns`).
- `GET /alarms/{alarmId}` — single alarm full record: all `AlarmEvent` fields (incl. `alarmType`),
  lifecycle state, `role`, `incidentId`, and the ordered `transitions` history (UTC timestamps;
  `source`/`changedAt` populated for `AlarmStatusChange`-driven transitions).
- `GET /openapi.json` — OpenAPI 3.1 (checked in at `services/alarm-manager/openapi.json`, the
  single source of truth; a contract test fails the build on drift). Swagger UI at `/swagger-ui.html`.

## Configuration (env only — no hard-coded URLs/credentials/thresholds)

| Variable | Default | Meaning |
|---|---|---|
| `ALARM_MANAGER_PORT` | `8080` | HTTP port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers |
| `ALARM_DB_JDBC_URL` | `jdbc:postgresql://localhost:5432/correlation` | live alarm store JDBC URL |
| `ALARM_DB_USER` / `ALARM_DB_PASSWORD` | `correlation` / `correlation` | DB credentials |
| `KAFKA_GROUP_ID_ENRICHED` | `alarm-manager-enriched` | consumer group (enriched) |
| `KAFKA_GROUP_ID_STATUS` | `alarm-manager-status` | consumer group (status) |
| `KAFKA_GROUP_ID_CORRELATION` | `alarm-manager-correlation` | consumer group (correlation) |
| `KAFKA_CONSUMER_MAX_RETRIES` | `3` | bounded consumer retries |
| `KAFKA_RETRY_BACKOFF_MS` | `1000` | retry backoff |
| `QUERY_MAX_PAGE_SIZE` | `500` | `GET /alarms` page-size cap |
| `QUERY_DEFAULT_PAGE_SIZE` | `50` | `GET /alarms` default page size |

Flyway is scoped to the owned `live_alarm` schema (`spring.flyway.schemas`/`default-schema`) and
applies **V1** (baseline: schema + `alarm` / `state_transition` / `processed_event`), **V2**
(`in-progress` state + audit `source`/`changed_at`), **V3** (NOT NULL `alarm_type`). All tables
land in `live_alarm`, never `public`.

## Observability

- `/actuator/health` — liveness + readiness. `/actuator/prometheus` — Micrometer metrics
  (`alarms_persisted_total`, `alarms_republished_total`, `status_changes_applied_total{newStatus}`,
  `correlation_results_applied_total`, `alarms_cleared_total`, `dlq_routed_total{topic}`,
  `*_for_unknown_alarm_total`, …).
- Structured JSON logs; the envelope `traceId` is propagated into log MDC.

## Build & test

```bash
./gradlew build            # unit + contract tests + JaCoCo (the merge gate; no Docker needed)
./gradlew integrationTest  # Testcontainers PostgreSQL — Flyway + repository round-trip (needs Docker)
./gradlew bootRun          # local run (set KAFKA_BOOTSTRAP_SERVERS + ALARM_DB_JDBC_URL)
```

## Docker

Build from the **repo root** (the service consumes the event-model binding via a Gradle composite
build):

```bash
docker build -f services/alarm-manager/Dockerfile -t acp/alarm-manager:0.1.0 .
```

Pinned `eclipse-temurin:17-jdk` build stage → `eclipse-temurin:17-jre` runtime, non-root, port 8080.
