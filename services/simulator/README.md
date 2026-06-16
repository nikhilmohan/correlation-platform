# simulator

**Cohort:** Python (networkx + scikit-learn-free pure generation engine)
**Owned data:** ground-truth scenario labels + topology snapshot + alarm corpus, written to a
configurable persistent path (`SIM_OUTPUT_DIR`). The Simulator is **stateless** (no DB); all
artifacts are files on the configured volume.

Generates a domain-grounded synthetic Core IP topology and labeled alarm streams (history +
live), uploads the topology snapshot to the Topology Service's ingestion API, replays alarms to
Kafka, and serves as the platform evaluation oracle. See `spec.md` (contract) and `design.md`
(how).

## Architecture (one simulator, three phases × two data-source modes)

| Phase | Action | Topic / sink |
|-------|--------|--------------|
| `p1` (upload)  | build the topology snapshot file, upload it via the Topology **ingestion API** (`POST /topology/snapshots`) | not Kafka |
| `p2` (history) | synthesize a labeled corpus, batch-replay it | `alarms.history` |
| `p3` (live)    | replay the stream wall-clock paced | `alarms.live` |

**Data-source modes:**
- **generate** (default): synthesize the topology/alarms from config + the Core IP domain pack.
  Optionally also **export** the emitted wire stream to a corpus file (`--export-corpus`).
- **ingest** (`--ingest` / `SIM_MODE=ingest`): **skip generation** and replay a pre-created
  snapshot/corpus file verbatim. A generate→export→re-ingest round-trip reproduces the exact
  ordered alarm payloads (fresh `eventId`s only).

All emitted `AlarmEvent`s use the frozen `acp_event_model` binding and carry the canonical
`alarmType`; every `managedObjectId` follows the `<objectType>:<id>` scheme shared with the
snapshot. The Topology and Knowledge integration points are **config-switchable** (mock/local
for unit tests, real for integration) via env — no code change to switch.

## Run

```bash
# unit tests + coverage (cohort gate ≥ 80%)
python -m pip install -e '.[dev]'
python -m pytest

# generate + batch-replay a labeled history corpus to alarms.history
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 python -m simulator.main --phase p2

# generate + export the corpus (round-trip source of truth)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  python -m simulator.main --phase p2 --export-corpus /data/sim/corpus.jsonl

# ingest: skip generation, replay a pre-created corpus + labels to alarms.history
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  python -m simulator.main --phase p2 --ingest \
  --alarms-file /data/sim/corpus.jsonl --labels-file /data/sim/labels.jsonl

# upload a topology snapshot (generate then POST to the Topology ingestion API)
TOPOLOGY_API_MODE=real TOPOLOGY_API_BASE_URL=http://topology:8080 \
  python -m simulator.main --phase p1

# live wall-clock-paced replay to alarms.live
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 python -m simulator.main --phase p3
```

`--help` prints the full option surface; `--dry-run` validates config + inputs without emitting.

## Configuration (env only — no hard-coded values)

Required for P2/P3 generate runs: `KAFKA_BOOTSTRAP_SERVERS`. Key knobs (all have validated
defaults / ranges, see `config/settings.py`): `TOPOLOGY_NODE_COUNT`, `SITE_COUNT`,
`IGP_AREA_COUNT`, `SCENARIOS`, `SCENARIO_INSTANCES`, `NOISE_RATE`, `NOISE_MIX`,
`JITTER_STDDEV_MS`, `PACING_MULTIPLIER`, `TOTAL_ALARMS`, `SIM_OUTPUT_DIR`, `TOPOLOGY_API_MODE`,
`TOPOLOGY_API_BASE_URL`, `KNOWLEDGE_MODE`, `KNOWLEDGE_API_BASE_URL`, `HTTP_PORT`, `LOG_LEVEL`.
Missing required config fails fast with a structured JSON log and a non-zero exit **before** any
event is emitted.

## Observability + API

- `/health` — 200 when started + Kafka-connected, 503 otherwise.
- `/metrics` — Prometheus text (`simulator_alarms_emitted_total`, snapshot gauges, …).
- `/labels`, `/labels/{scenarioId}`, `/scenarios` — ground-truth retrieval for the eval oracle.
- OpenAPI 3.1 is served at `/openapi.json`; the checked-in **`openapi.json`** in this directory
  is the authoritative surface (a drift test guards it).
- Structured JSON logs on stdout (one object per line).

## Docker / Compose

Multi-stage image pinned to `python:3.13-slim` (build context = **repo root** so it picks up
both `libs/event-model` and the service source):

```bash
docker build -f services/simulator/Dockerfile -t acp/simulator:dev .
```

The root `docker-compose.yml` provides the `simulator` service (depends on `kafka` +
`kafka-init`, persistent artifacts on the `sim-data` volume, `/health`+`/metrics` on host port
`8085`).
