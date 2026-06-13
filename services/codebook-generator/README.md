# codebook-generator

**Cohort:** Python (networkx)
**Owned datastore:** PostgreSQL (schema: `codebook`)

Compiles the **codebook** — the model-derived matrix of candidate root-cause instances to
predicted symptom signatures. On a `trails.built` event it reads the event's `domain`, fetches
the domain-scoped fault-origin list + propagation templates + alarm-type vocabulary from the
Knowledge Service, enumerates fault-origin instances from the Topology Service, runs each
instance's templates forward over its graph closure (networkx) to a predicted symptom
signature, tags scenarios to trails (Trail Builder), persists the codebook (one active per
`(domain, snapshotId)`), and emits `codebook.generated`. Full signatures are served via the
read API (Pattern Manager P2, Correlation Engine P3).

See `spec.md` (contract) and `design.md` (how).

## Contract (frozen — `libs/event-model`)

- **Consumes:** `trails.built` (`TrailsBuiltEvent`); DLQ `trails.built.dlq`.
- **Produces:** `codebook.generated` (`CodebookGeneratedEvent`); DLQ `codebook.generated.dlq`.
- **Read API (OpenAPI 3.1, `openapi.json`):** `GET /codebooks/{codebookId}`,
  `/codebooks/{codebookId}/scenarios`, `/codebooks/{codebookId}/scenarios/{scenarioId}`,
  `/codebooks/{codebookId}/trail-signatures` (CE projection),
  `/codebooks?snapshotId=…`, `/codebooks?domain=…`,
  `/codebooks/active?domain=…&snapshotId=…`, plus `/health` + `/metrics`.

## Configuration (env only — no hard-coded URLs/thresholds/domains)

| Var | Purpose |
|---|---|
| `DATABASE_URL` | Codebook Store (pg8000), e.g. `postgresql+pg8000://…` |
| `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP` | Kafka (group `codebook-generator-trails.built`) |
| `TOPOLOGY_QUERY_URL` / `_MODE` | Topology graph query (`topology-query`) |
| `KNOWLEDGE_FAULT_ORIGINS_URL` / `_MODE` | Knowledge fault-origin list |
| `KNOWLEDGE_PROPAGATION_TEMPLATES_URL` / `_MODE` | Knowledge propagation templates |
| `KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL` / `_MODE` | Knowledge alarm-type vocabulary |
| `TRAIL_BUILDER_URL` / `_MODE` | Trail Builder trail membership |
| `INTEGRATION_MAX_RETRIES`, `INTEGRATION_BACKOFF_MS`, `TRAVERSAL_MAX_DEPTH` | Tuning |
| `DEFAULT_DOMAIN`, `LOG_LEVEL` | MVP domain fallback / log level |

Each integration point has a base URL and a `MOCK|REAL` toggle; the same code runs in both
modes. Startup fails fast if any required integration-point URL or `DATABASE_URL` is unset.

## Run (local)

```bash
python -m pip install -e '.[dev]'

# Apply schema migrations (yoyo) then run the consumer loop:
python -m codebook_generator            # migrate + consume trails.built
python -m codebook_generator migrate     # migrate only (entrypoint pre-step)

# Serve the read API:
uvicorn codebook_generator.api:app --host 0.0.0.0 --port 8000
```

## Test / lint

```bash
python -m pytest                         # unit/contract (pytest); coverage gate >= 80%
ruff check . && black --check .
python scripts/dump_openapi.py           # regenerate openapi.json (drift-guarded in tests)
```

Unit tests use respx mocks generated from the collaborators' producer shapes and an in-memory
SQLite store (with an attached `codebook` schema); no live services required. Integration
tests point each integration point at the real service in Docker Compose.
