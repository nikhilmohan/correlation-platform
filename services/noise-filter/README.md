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
| `WINDOW_ALLOWED_LATENESS_BUCKETS` | DA-3c reorder tolerance in whole event-time buckets: a `(trailId, bucket)` finalizes only once the trail watermark has advanced this many buckets past the bucket's OWN last add (default 6). Absorbs cross-partition event-time skew. Alias `WINDOW_WATERMARK_LAG_BUCKETS` (old DA-3b name) retained. |
| `WINDOW_IDLE_GRACE_SECONDS` | DA-3c wall-clock grace that must ALSO elapse since a bucket's last add before the allowed-lateness path finalizes it (default 15). Absorbs arrival-time skew (partition drain lag, Enrichment self-clear hold). |
| `WINDOW_BACKSTOP_SECONDS` | Wall-clock idle/end-of-stream backstop and memory valve for a bucket with no new member (default 300). Set ABOVE the max upstream release cadence + reorder window so it never fires while a bucket is still collecting cross-partition siblings. |
| `WINDOW_MAX_OPEN_WINDOWS` | DA-3c memory bound: max simultaneously-open `(trailId, bucket)` windows (default 200000). Exceeding it force-finalizes the least-recently-added windows (emitted, `nf_windows_force_finalized_total`, never dropped) so a pathological stream cannot OOM. |

**Window finalization (DA-3c — allowed-lateness / bounded reorder).** The real `alarms.enriched`
topic is keyed by `managedObjectId` (Enrichment), so a single trail's alarms — spanning many managed
objects (Node, LineCard, Port, Interface, IPLink, IGPAdjacency, LSP, FiberSpan…) — hash to DIFFERENT
Kafka partitions and arrive **interleaved and out of `raisedAt` order**. A `(trailId, bucket)`
therefore stays open until its OWN members stop arriving: it finalizes when EITHER (a) the trail
event-time watermark has advanced `WINDOW_ALLOWED_LATENESS_BUCKETS` past the bucket's own last add
AND `WINDOW_IDLE_GRACE_SECONDS` of wall-clock have elapsed since it, OR (b) `WINDOW_BACKSTOP_SECONDS`
of wall-clock elapsed since its last add (end-of-stream / idle / memory valve). On finalize the
retained alarms are sorted by `raisedAt` (buffer-and-sort) so out-of-order arrival never fragments or
misorders a window. In live mode event time ≈ wall clock, so finalization is bounded by
`allowed-lateness × windowSize + idle-grace` — far below the backstop, no live-latency regression.
`WINDOW_MAX_OPEN_WINDOWS` bounds memory (force-finalize oldest, never drop). A late alarm for an
already-finalized bucket re-opens it (`nf_windows_reopened_total`) rather than forming a dropped
singleton.

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
