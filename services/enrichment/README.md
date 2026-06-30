# enrichment

**Cohort:** Spring Boot (Java 17 / Spring Boot 3 / Gradle)
**Owned datastore:** none — only transient in-process windowed state (dedup / self-clear / flap)
plus a small Enrichment-owned **chatter overlay** file (config, not a domain store).

Source-aware, configuration-driven first-stage alarm processing. Selects a **per-source ruleset**,
normalizes each raw alarm into the single canonical `AlarmEvent` (X.733-aligned, with the required
canonical `alarmType` join token), runs the fixed deterministic filter pipeline (dedup →
self-clear → flap-damp → known-chatter) with that ruleset's per-source parameters, tags survivors
with `trailIds` via the Trail Builder, and emits onto `alarms.enriched` (P2 history) or
`alarms.enriched.live` (P3 live). One codebase, one instance serves both phases.

## Pipeline (fixed 8-stage order — never reordered/pluggable)

1. **RulesetSelector** — picks the per-source ruleset by envelope `source` (default ruleset on miss).
2. **NormalizeStep** — applies the ruleset field-mapping → canonical `AlarmEvent` (severity /
   eventType / probableCause maps, the required `alarmType` via the source `alarmTypeMap`,
   `managedObjectId` template, `vendorRaw` passthrough).
3. **DedupStep** — count-collapse on `(source, managedObjectId, eventType)` within `dedupWindow`.
4. **SelfClearStep** — hold-time suppression of transients (`selfClearHoldTime`).
5. **FlapDampStep** — `flapN`/`flapWindow` oscillation → one summary `AlarmEvent`.
6. **ChatterStep** — drop known-chatter `(managedObjectId, eventType)` from the source chatter list.
7. **TrailTagStep** — sets `trailIds` via the frozen `GET /trails/by-object?managedObjectId={moId}&domain={domain}`.
8. **Emit** — `alarms.enriched` (history) or `alarms.enriched.live` (live).

Poison / undeserializable / unknown-`schemaVersion` (≥2) / normalize-invalid /
trail-lookup-exhausted messages route to `alarms.history.dlq` / `alarms.live.dlq` (never dropped).
Idempotency: dedupe on envelope `eventId` plus the `(source, managedObjectId, eventType)` window.

## Configuration ownership

Per-source rulesets (field mappings + filter parameters + base known-chatter list) are
**Enrichment's own** mounted YAML config — **not** the Knowledge Service. There is no
`knowledge.updated` consumer and no `KnowledgeClient`. The `alarmTypeVocabulary` value space is
Knowledge-authored; Enrichment only validates that every `alarmTypeMap` value/fallback is a member.

Operator chatter promotions/removals (the chatter-management API) persist to an Enrichment-owned
**chatter overlay** file layered onto the YAML and hot-apply live via an atomic registry swap
(no restart); they survive restart.

## Chatter-management API (OpenAPI 3.1 at `/openapi.json`)

The operator-mediated promote/manage surface of the noise→live chatter feedback loop (the web-ui
reads the Noise Filter's observed-chatter and writes promotions here — Enrichment never calls NF):

| Operation | Path |
|---|---|
| List a source's chatter entries | `GET /api/v1/sources/{source}/chatter` |
| Add (promote) a chatter entry | `POST /api/v1/sources/{source}/chatter` |
| Remove a chatter entry | `DELETE /api/v1/sources/{source}/chatter` |

The match key is `(managedObjectId, eventType)` (both required); `alarmType`/`promotedFrom` are
optional provenance. The checked-in `openapi.json` is the contract source of truth — regenerate it
with `./gradlew generateOpenApi`.

## Config (all via env — no hard-coded URLs/thresholds)

| Env var | Purpose | Default |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka cluster | `localhost:9092` |
| `TRAIL_BUILDER_BASE_URL` | Trail Builder base URL (in-cluster `http://trail-builder:8000`) | `http://trail-builder:8000` |
| `TRAIL_BUILDER_MODE` | `mock` \| `real` | `real` |
| `TRAIL_BUILDER_MAX_RETRIES`, `TRAIL_BUILDER_RETRY_BACKOFF_MS` | Resilience4j retry policy | `3`, `200` |
| `ENRICHMENT_DOMAIN` | domain passed to `getTrailsForObject` | `core-ip` |
| `ENRICHMENT_RULESETS_FILE` | mounted per-source rulesets YAML | `/config/rulesets.yaml` |
| `ENRICHMENT_RULESETS_RELOAD` | file-watch hot-reload | `false` |
| `ENRICHMENT_CHATTER_OVERLAY_FILE` | writable chatter overlay file | `/config/chatter-overlay.json` |
| `ENRICHMENT_HISTORY_TOPIC` / `ENRICHMENT_LIVE_TOPIC` / `ENRICHMENT_ENRICHED_TOPIC` / `ENRICHMENT_ENRICHED_LIVE_TOPIC` / `*_DLQ_TOPIC` | topic overrides | architecture.md names |
| `SERVER_PORT` | HTTP port (chatter API, `/openapi.json`, Swagger UI, Actuator) | `8080` |

## Observability

- `GET /actuator/health` — liveness + readiness (readiness gated on a valid loaded ruleset set
  including a `default`).
- `GET /actuator/prometheus` — Micrometer metrics (`alarms_consumed_total`, `alarms_emitted_total`,
  `filtered_total{filter,source}`, `ruleset_default_fallback_total`, `alarmtype_fallback_total`,
  `dlq_messages_total{topic,reason}`, `trail_lookup_failures_total`, chatter-API metrics, …).
- `GET /openapi.json` — chatter-management OpenAPI 3.1; Swagger UI at `/swagger-ui.html`.
- Structured JSON logs with the envelope `traceId` propagated.

## Build & test

```bash
./gradlew clean build      # unit + contract tests (JUnit 5) + JaCoCo gate; produces the boot jar
./gradlew integrationTest  # real-context entrypoint test (embedded Kafka + WireMock Trail Builder)
./gradlew generateOpenApi  # regenerate the checked-in openapi.json from the live springdoc surface
```

## Run

```bash
# Build the image (REPO ROOT context — composite build pulls in libs/event-model):
docker build -f services/enrichment/Dockerfile -t acp/enrichment:0.1.0 .

# Run against the integration stack (real Kafka + real Trail Builder), rulesets mounted:
docker compose up -d --build enrichment      # host port 8088 → container 8080

# Local isolated run with a mock Trail Builder:
TRAIL_BUILDER_MODE=mock TRAIL_BUILDER_BASE_URL=http://localhost:9999 \
  ENRICHMENT_RULESETS_FILE=services/enrichment/config/rulesets.yaml \
  java -jar build/libs/enrichment-0.1.0.jar
```
