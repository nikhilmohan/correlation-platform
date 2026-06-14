# trail-builder

**Cohort:** Python (networkx)
**Owned datastore:** PostgreSQL (schema `trailbuilder`)

Builds overlapping, **policy-bounded correlation trails** from the topology graph and the
domain trail policy authored in the Knowledge Service, persists them, serves domain-scoped
trail-membership queries, and emits `trails.built`.

A *trail* is the transitive closure over dependency edges (incl. the interface-layer relations
`HOSTS` Port→Interface and `TERMINATES` Interface→IPLink) from each seed object, bounded by IGP
area, with all links sharing an SRLG group unioned into the same trail. Trails overlap — one
object may belong to many trails. See `spec.md` (contract) and `design.md` (how).

## What it does

- **Consumes** `topology.changed` (build trigger; `domain` read directly from the event) and
  `knowledge.updated` (policy-refresh trigger when `recordType == "trailPolicy"`).
- **Reads** graph closures from the **Topology Service** query API only (never the graph store)
  and the per-domain trail policy from the **Knowledge Service** API (no hard-coded policy).
- **Computes** trails in-memory with `networkx`, **persists** them to PostgreSQL (schema
  `trailbuilder`), and **emits** `trails.built` (`TrailsBuiltEvent`: `snapshotId`, `trailIds`,
  `trailCount`, `domain`).
- **Serves** the frozen trail-query HTTP API (below) + on-demand rebuild.
- Dedupes consumed events on envelope `eventId`; routes poison `topology.changed` to
  `topology.changed.dlq`. Poison `knowledge.updated` is logged and skipped (refresh-only).

## HTTP API (frozen — see `openapi.json`)

The checked-in `openapi.json` is the **single source of truth** consumers (Codebook Generator,
Enrichment, Noise Filter, web-ui) generate their clients against; a surface change is a contract
change. A drift test fails the build if the generated surface diverges from the committed file.

| Operation | Route | Errors |
|---|---|---|
| `getTrailsForObject` | `GET /trails/by-object?managedObjectId={moId}&domain={d}` | **400** missing/blank required param; 422 malformed `managedObjectId` |
| `listTrails` | `GET /trails?snapshotId={s}&domain={d}` (opt `limit`/`offset`) | **400** missing/blank required param; 422 bad `limit`/`offset` |
| `getTrail` | `GET /trails/{trailId}` | 404 unknown `trailId` |
| `rebuildTrails` | `POST /trails/rebuild` (`{snapshotId, domain}`) | **400** missing/blank body field; 401 bad token (if enabled); 502 dependency unavailable |
| health | `GET /health` | 503 if not ready |
| metrics | `GET /metrics` (Prometheus) | — |
| openapi | `GET /openapi.json` (OpenAPI 3.1) | — |

`domain` is **strictly REQUIRED** on the query API — a missing or blank `domain` returns a clear
**400** (never silently defaulted). The `DEFAULT_DOMAIN` fallback applies **only** to the Kafka
event-ingestion path (a legacy `topology.changed` whose optional `domain` is absent).

### Regenerating `openapi.json`

It is generated from the live FastAPI app (`json.dumps(app.openapi(), indent=2, sort_keys=True)`
+ trailing newline). After any API-surface change, regenerate and review the diff (a contract
change needs `docs/architecture.md` + human approval); the drift test
(`tests/test_openapi_contract.py`) keeps it honest.

## Database & migrations

PostgreSQL, all objects inside the service-owned **`trailbuilder`** schema (tables `trail`,
`trail_member`, `processed_event`, plus the Alembic version table). Migrations are **Alembic**:

- `0001_create_schema` issues `CREATE SCHEMA IF NOT EXISTS trailbuilder` (idempotent; safe to
  re-run / race on a fresh shared PostgreSQL — no manual bootstrap, no `public`/global migration).
- `0002_create_tables` creates the tables inside that schema.

The service runs `alembic upgrade head` on startup (`runtime.run_migrations`), so a container
self-migrates before serving. To run manually:

```bash
cd services/trail-builder
DATABASE_URL=postgresql+psycopg://correlation:correlation@localhost:5432/correlation \
  alembic -c alembic.ini upgrade head
```

## Configuration (environment variables)

All config is env-sourced (`pydantic-settings`); no URLs/thresholds/policy are hard-coded.
Trail-policy bounds (IGP-area key, SRLG rule, dependency-edge set) are **not** config — they are
fetched per-domain from the Knowledge Service.

| Env var | Default | Purpose |
|---|---|---|
| `TOPOLOGY_SERVICE_BASE_URL` | `http://topology:8080` | Topology Service base URL |
| `TOPOLOGY_SERVICE_MODE` | `mock` | `mock` (stub) or `real` integration point |
| `KNOWLEDGE_SERVICE_BASE_URL` | `http://knowledge:8080` | Knowledge Service base URL |
| `KNOWLEDGE_SERVICE_MODE` | `mock` | `mock` or `real` |
| `KNOWLEDGE_STALE_OK` | `false` | Serve a cached policy if Knowledge is unreachable |
| `DATABASE_URL` | `postgresql+psycopg://correlation:correlation@postgres:5432/correlation` | SQLAlchemy URL |
| `TRAILBUILDER_DB_SCHEMA` | `trailbuilder` | Owned schema name |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka brokers |
| `KAFKA_CONSUMER_GROUP` | `trail-builder` | Group-id prefix; each topic uses `<group>-<topic>` |
| `TOPOLOGY_CHANGED_TOPIC` | `topology.changed` | Build-trigger topic (consumed) |
| `TOPOLOGY_CHANGED_DLQ_TOPIC` | `topology.changed.dlq` | Poison sink |
| `KNOWLEDGE_UPDATED_TOPIC` | `knowledge.updated` | Policy-refresh topic (consumed) |
| `TRAILS_BUILT_TOPIC` | `trails.built` | Produced summary topic |
| `TRAIL_RETENTION_SNAPSHOTS` | `2` | Retained snapshots per domain |
| `DEFAULT_DOMAIN` | `core-ip` | Fallback domain — **event path only**, never the query API |
| `TOPOLOGY_SNAPSHOT_SCOPE` | `current` | Snapshot scoping token for Topology reads |
| `TRAVERSAL_MAX_DEPTH` | `12` | Bounded-traversal depth handed to Topology (env-configurable) |
| `HTTP_RETRY_MAX` | `3` | Max attempts per collaborator HTTP call |
| `HTTP_RETRY_BACKOFF_MS` | `200` | Backoff between retries (honoured by both clients) |
| `HTTP_TIMEOUT_SECONDS` | `10.0` | Per-request timeout |
| `REBUILD_API_TOKEN` | _unset_ | If set, `POST /trails/rebuild` requires `Authorization: Bearer <token>` |
| `LOG_LEVEL` | `INFO` | Log level (structured JSON logs) |
| `SERVICE_NAME` | `trail-builder` | Log/metric service label |

## Kafka consumers

Each consumed topic runs under its own `<service>-<topic>` consumer group —
`trail-builder-topology.changed` and `trail-builder-knowledge.updated` — so the two topics'
offset commits and rebalances are decoupled. The producer sets `enable.idempotence=true`.

## Observability

- `GET /health` — 200 `ok` when Topology, Knowledge and DB respond; 503 `degraded` otherwise.
- `GET /metrics` — Prometheus exposition (query counts, DLQ count, policy refreshes, build
  failures).
- Structured JSON logs carrying `traceId` / `snapshotId` / `domain`.

## Run locally

```bash
cd services/trail-builder
python3.13 -m venv .venv && . .venv/bin/activate
pip install ../../libs/event-model/python
pip install -e .[dev]

# Migrate (point at your Postgres) then serve API + consumers:
DATABASE_URL=postgresql+psycopg://correlation:correlation@localhost:5432/correlation \
  python -m trailbuilder
```

The entrypoint migrates (`CREATE SCHEMA` + Alembic upgrade), starts the per-topic Kafka consumer
threads, then serves the FastAPI app on `0.0.0.0:8000`.

## Docker

Build from the **repo root** (the image needs both `libs/event-model` and the service source):

```bash
docker build -f services/trail-builder/Dockerfile -t acp/trail-builder:dev .
docker run --rm -p 8000:8000 \
  -e DATABASE_URL=postgresql+psycopg://correlation:correlation@postgres:5432/correlation \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e TOPOLOGY_SERVICE_BASE_URL=http://topology:8080 \
  -e KNOWLEDGE_SERVICE_BASE_URL=http://knowledge:8080 \
  acp/trail-builder:dev
```

Multi-stage, pinned `python:3.13-slim`, runs as non-root user `trail`.

## Tests

`pytest` (the Python-cohort standard). Kafka / Postgres / HTTP boundaries are mocked
(`FakeProducer`, in-memory SQLite, `respx` stubs generated against the collaborators' OpenAPI),
so no live broker/DB is needed. Each spec acceptance criterion maps 1:1 to a test.

```bash
cd services/trail-builder
ruff check . && black --check .
pytest -q --cov --cov-fail-under=80
```
