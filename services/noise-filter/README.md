# noise-filter

**Cohort:** Python (scikit-learn)
**Owned datastore:** PostgreSQL schema `noise_filter` (`nf_run_stats`, `nf_observed_chatter`)

Phase-2 statistical cleaning on the history path. Consumes enriched, trail-tagged alarms from
`alarms.enriched`, groups them per trail within coarse time windows, and runs **DBSCAN per
trail-window** to collapse dense alarm **storms** (a single propagating fault's post-dedup burst)
into ONE clean `TransactionEvent` on `transactions.clean` while dropping sparse coincidental
noise. Storm reduction is the primary mission; subtle outlier removal is secondary. It also
records aggregate **run-stats** per execution and the recurring **observed-noise/chatter
signatures** it drops, and serves both read-only over an OpenAPI 3.1 HTTP API for the web-ui.

All DBSCAN parameters (`eps`, `minSamples`, `windowSize`, `algorithm`) and the active feature set
(attribute keys, hop-distance on/off) come from the **Knowledge Service** — nothing is hard-coded.

## Contract

- **Consumes (Kafka):** `alarms.enriched` (`AlarmEvent`), `knowledge.updated` (`KnowledgeUpdatedEvent`)
- **Produces (Kafka):** `transactions.clean` (`TransactionEvent`, with the typed six-field
  `alarms[]`), `alarms.enriched.dlq` (poison / unknown-version routing)
- **HTTP (read-only):** `GET /api/v1/run-stats`, `GET /api/v1/run-stats/{runId}`,
  `GET /api/v1/observed-chatter`, plus `GET /health`, `GET /metrics`, `GET /openapi.json`.
  The published spec is checked in at `openapi.json` (single source of truth).
- **Depends on (as a client, built against published OpenAPI):** Knowledge Service (model params +
  feature config — reads the **RecordResponse `.payload`** envelope), Topology Service (node
  attributes, only when an attribute feature is enabled), Trail Builder (`getTrail` for
  `snapshotId` provenance + hop-distance seed). Each is config-switchable mock/real.

## Configuration (env)

| Var | Purpose |
|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers |
| `KAFKA_CONSUMER_GROUP_ID` | consumer group for `alarms.enriched` (+ `knowledge.updated`) |
| `KNOWLEDGE_SERVICE_URL` / `KNOWLEDGE_CLIENT_MODE` | Knowledge base URL; `mock` or `real` |
| `TOPOLOGY_SERVICE_URL` / `TOPOLOGY_CLIENT_MODE` | Topology base URL; `mock` or `real` (created only when an attribute feature is enabled) |
| `TRAIL_BUILDER_URL` / `TRAIL_BUILDER_CLIENT_MODE` | Trail Builder base URL; `mock` or `real` |
| `NOISE_FILTER_DB_URL` | PostgreSQL URL for the run-stats / observed-chatter store |
| `LOG_LEVEL` | structured-log level (JSON) |
| `HTTP_PORT` | port for `/health`, `/metrics`, `/openapi.json`, read API (default 8080) |
| `DEDUPE_TTL_SECONDS` | TTL of the `eventId` dedupe cache (default 900) |
| `WINDOW_WATERMARK_LAG_BUCKETS` | Event-time watermark lag: extra whole buckets of event time to wait past a bucket before finalizing it (default 0 — finalize bucket B once an alarm in B+1 arrives for the trail). Tolerates out-of-order event time. |
| `WINDOW_BACKSTOP_SECONDS` | Wall-clock idle/end-of-stream backstop for a bucket with no successor (default 60). Set ABOVE the max upstream release cadence (Enrichment self-clear hold ~15 s + sweep) so it never fires mid-trickle during history batch replay. |

**Window finalization** is event-time-driven (a `(trailId, bucket)` closes when event time advances
past it), NOT wall-clock-inactivity-driven — so a logical window's members stay grouped even when
Enrichment dribbles its held raises into `alarms.enriched` over ~15-20 s wall-clock in history batch
mode. In live mode event time ≈ wall clock, so the next real-time alarm closes the prior bucket
promptly (no added latency); the wall-clock backstop only flushes a genuinely-final/idle bucket.
A late alarm for an already-finalized bucket re-opens it (counted `nf_windows_reopened_total`) rather
than forming a dropped singleton.

`eps`, `minSamples`, `windowSize`, `algorithm`, the attribute key set, and the hop-distance
on/off flag + traversal bound are **Knowledge Service parameters** (loaded at startup, hot-refreshed
on `knowledge.updated`). Run-stats and observed-chatter writes are **best-effort** — a DB failure
never blocks `transactions.clean` emission.

## Develop

```sh
pip install -e '.[dev]'            # installs acp-event-model + service deps
ruff check . && black --check .    # lint + format gate
pytest                             # unit/contract suite (in-memory store stand-ins; integration deselected)
pytest -m integration -o addopts=""   # Testcontainers Postgres + real-entrypoint tests (needs Docker)
```

Regenerate the published OpenAPI artifact after any API change:

```sh
python - <<'PY'
import json
from noise_filter.api import ApiState, create_app
from noise_filter.metrics import Metrics
from noise_filter.repository import InMemoryObservedChatterRepository, InMemoryRunStatsRepository
m = Metrics()
st = ApiState(run_stats_repo=InMemoryRunStatsRepository(),
              chatter_repo=InMemoryObservedChatterRepository(), metrics_registry=m.registry)
json.dump(create_app(st).openapi(), open("openapi.json", "w"), indent=2)
open("openapi.json", "a").write("\n")
PY
```

## Run

```sh
docker build -f services/noise-filter/Dockerfile -t acp/noise-filter:dev .   # context = repo root
# Set the env vars above (NOISE_FILTER_DB_URL, KAFKA_*, *_SERVICE_URL/_MODE), then:
python -m noise_filter            # applies yoyo migrations, then consume loop + HTTP server
python -m noise_filter migrate    # apply migrations only (idempotent)
```

## Data model

- `noise_filter.nf_run_stats` — ONE aggregate row per finalized trail-window execution (identity +
  Knowledge params used + counts + storm/retention stats). No alarm payloads.
- `noise_filter.nf_observed_chatter` — ONE row per distinct chatter signature
  `(managedObjectId, alarmType, eventType, trailId)` with an occurrence count + first/last seen.
  Aggregate noise telemetry only — no per-alarm corpus. These are the candidate chatter entries an
  operator promotes (via the web-ui) into Enrichment's known-chatter list; the Noise Filter is the
  read-only producer and never writes to Enrichment.
