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
| `SAMPLE_ALARMS_CAP_K` | `10` | Per-pattern cap `K` on the bounded member-alarm sample served on `PatternView.sampleAlarms[]` (operator XAI). |
| `PATTERN_SEED_ON_STARTUP` | `true` | Load the pre-approved seed pattern pack on startup so a fresh deploy can run P3 correlation without first mining. Idempotent (skips patterns already present). |
| `PATTERN_SEED_PACK` | `seed/core-ip-patterns.json` | Classpath resource of the seed pack to load. |
| `PATTERN_SEED_EMIT_APPROVED_EVENTS` | `true` | Emit a `PatternApprovedEvent` per newly seeded pattern (a running Correlation Engine picks them up via `patterns.approved`; a cold-start CE reads the approved set from the read API regardless). |

### Pre-approved seed patterns (`seed/core-ip-patterns.json`)

A fresh deploy needs APPROVED patterns for P3 correlation to match against, but those otherwise only
exist AFTER the resource-heavy pattern-miner runs. On startup, `PatternSeedLoader` (mirroring the
Knowledge Service's `SeedLoader`) idempotently loads a shipped pack of known-good, TRUE-cause-rooted
Core IP cascade patterns directly into the Pattern Store in the `approved` lifecycle — so P3 works
out of the box. Running the miner later refreshes/augments the store as normal.

**Why the seed survives a fresh topology snapshot.** The Correlation Engine matches an approved
pattern to trails **by structure** — the objectType hostability-subset rule (`CompatibilityEvaluator`),
derived from `PatternView.sampleAlarms[].managedObjectId` prefixes (`<objectType>:<id>`) — **not** by
the pattern's `trailId` (provenance-only under pattern generalization). Each seed therefore ships a
sample-alarm objectType witness for every sequence alarmType and its root type, so it generalizes to
every structurally-compatible trail in whatever snapshot a fresh P1 topology ingest produces. Adds no
new topic, payload, or field: seeds reuse the existing `patterns.approved` event and `PatternView`.

### Sample alarms (`PatternView.sampleAlarms[]`)

Each mined `PatternMinedEvent` may carry an optional, bounded `sampleAlarms[]` — a representative
sample of the real member alarms the pattern was mined from (`alarmId`, `alarmType`, `raisedAt`,
`managedObjectId`, `perceivedSeverity`). The Pattern Manager persists these (child table
`pattern.sample_alarm`, migration `V3`), defensively capped at `SAMPLE_ALARMS_CAP_K`, and serves them
as `sampleAlarms[]` on `PatternView` in both `GET /patterns` and `GET /patterns/{patternId}` — always
present, `[]` when none captured. On anchor-consolidation the pattern keeps ONE bounded sample = the
first/creating contributor's; the fold never re-writes the sample (deterministic, bounded,
replay-safe). Events without `sampleAlarms[]` persist normally with zero sample rows (backward-compat).

## Kafka semantics

At-least-once with explicit manual acks. Idempotent on the envelope `eventId`
(`processed_event` dedupe — a redelivered event is a no-op). Un-processable records (malformed
JSON, unsupported `schemaVersion` major, unexpected type) are routed to `patterns.mined.dlq`
with `error`/`errorClass` headers and never persisted.
