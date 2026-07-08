# simulator

**Cohort:** Python (networkx + scikit-learn-free pure generation engine)
**Owned data:** ground-truth scenario labels + topology snapshot + alarm corpus, written to a
configurable persistent path (`SIM_OUTPUT_DIR`). The Simulator is **stateless** (no DB); all
artifacts are files on the configured volume.

Generates a domain-grounded synthetic Core IP topology and labeled alarm streams (history +
live), uploads the topology snapshot to the Topology Service's ingestion API, replays alarms to
Kafka, and serves as the platform evaluation oracle. See `spec.md` (contract) and `design.md`
(how).

## Architecture (one simulator, three phases × three data-source modes)

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
- **synth** (`--synth` / `SIM_MODE=synth`, P3-only): **P3 topology-and-pattern-driven live
  synthesis.** Reads the already-deployed topology + trails + approved patterns from the running
  services' published APIs (Topology `GET /topology/snapshots`, Trail Builder `GET /trails/{id}`,
  Pattern Manager `GET /patterns?lifecycle=approved`) and synthesizes a pattern-aligned
  `alarms.live` stream — targeting a configurable ~60-70% auto-correlation + RCA rate — with full
  ground-truth labels. Regenerates nothing (no topology build, no pattern mining, no
  `POST /topology/snapshots`); introduces no contract change. Each integration is
  config-switchable (mock/real). The fetched (topology, trails, patterns) are persisted as a
  reusable **P3 config snapshot** so repeated seeded runs replay standalone with zero API calls.

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

# P3 synth: read deployed topology+trails+approved patterns, synthesize onto alarms.live
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  PATTERN_MANAGER_API_MODE=real PATTERN_MANAGER_API_BASE_URL=http://pattern-manager:8080 \
  TRAIL_BUILDER_API_MODE=real   TRAIL_BUILDER_API_BASE_URL=http://trail-builder:8080 \
  TOPOLOGY_API_MODE=real        TOPOLOGY_API_BASE_URL=http://topology:8080 \
  python -m simulator.main --synth \
    --p3-total-alarms 500 --p3-aligned-fraction 0.65 --p3-rng-seed 42

# P3 synth standalone: replay from a persisted P3 config snapshot with ZERO API calls
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
  python -m simulator.main --synth \
    --p3-config-snapshot-path /data/sim/p3-config-snapshot.json --p3-rng-seed 42

# PERSISTENT SERVICE MODE: stay up and serve the HTTP synth trigger (web-ui polls this)
#   POST /synth/run   -> 202 {runId, status:"running"}  (409 if a run is active, 422 on bad body)
#   GET  /synth/status-> {status:"idle"|"running", runId, progress{...}, summary{...}}
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 P3_NETWORK_WIDE=true P3_TOTAL_ALARMS=500 \
  python -m simulator serve            # == python -m simulator.main serve (compose: command:["serve"])

# trigger + poll a P3 synth run against the running service
curl -sX POST localhost:8085/synth/run -H 'content-type: application/json' \
  -d '{"target":0.6,"totalAlarms":500,"seed":42}'
curl -s localhost:8085/synth/status
```

`--help` prints the full option surface; `--dry-run` validates config + inputs without emitting.
`serve` runs the FastAPI app under uvicorn indefinitely (all read endpoints stay available); a
triggered run executes on a background worker thread so `/health` + `/metrics` stay responsive.

## Configuration (env only — no hard-coded values)

Required for P2/P3 generate runs: `KAFKA_BOOTSTRAP_SERVERS`. Key knobs (all have validated
defaults / ranges, see `config/settings.py`): `TOPOLOGY_NODE_COUNT`, `SITE_COUNT`,
`IGP_AREA_COUNT`, `SCENARIOS`, `SCENARIO_INSTANCES`, `NOISE_RATE`, `NOISE_MIX`,
`JITTER_STDDEV_MS`, `PACING_MULTIPLIER`, `TOTAL_ALARMS`, `SIM_OUTPUT_DIR`, `TOPOLOGY_API_MODE`,
`TOPOLOGY_API_BASE_URL`, `KNOWLEDGE_MODE`, `KNOWLEDGE_API_BASE_URL`, `HTTP_PORT`, `LOG_LEVEL`.
Missing required config fails fast with a structured JSON log and a non-zero exit **before** any
event is emitted.

**P3 synth knobs** (all env/CLI overridable, no hard-coded URLs/fractions): `PATTERN_MANAGER_API_MODE`
/ `PATTERN_MANAGER_API_BASE_URL`, `TRAIL_BUILDER_API_MODE` / `TRAIL_BUILDER_API_BASE_URL`,
`TOPOLOGY_API_MODE` / `TOPOLOGY_API_BASE_URL`, `P3_ALIGNED_FRACTION` (default 0.65),
`P3_TOTAL_ALARMS` (default 500), `P3_RNG_SEED` (unset = fresh, logged), `P3_CONFIG_SNAPSHOT_PATH`,
`P3_OPTIONAL_INCLUDE_PROB` (default 1.0), and the non-aligned mix
`P3_PARTIAL_CASCADE_FRACTION`/`P3_RANDOM_ALARM_FRACTION`/`P3_NOISE_FRACTION` (0.4/0.4/0.2, must sum
to 1.0). Starting synth in `real` mode without the collaborator's base URL, an out-of-range aligned
fraction, or a bad mix fails fast before any emission.

**P3 network-wide emission + closed-loop auto-correlation target** (additive; behind
`P3_NETWORK_WIDE`, or auto-on when `P3_AUTO_CORRELATION_TARGET` is set). When active, each approved
pattern's cascade is emitted on **multiple structurally-compatible trails** across the network and a
closed-loop controller sizes the aligned-cascade count so the **CE-measured post-enrichment** rate
(`correlatedAlarmCount / totalAlarmsProcessed`) lands within tolerance of the target on every run.
Off (or target unset) → the existing single-trail P3 behavior is unchanged. Knobs (all env/CLI,
no hard-coded thresholds):

| Env / CLI | Default | Meaning |
|---|---|---|
| `P3_NETWORK_WIDE` / `--p3-network-wide` | `false` | enable network-wide emission (auto-`true` when a target is set) |
| `P3_AUTO_CORRELATION_TARGET` / `--p3-auto-correlation-target` | unset | CE post-enrichment target fraction, range `[0,1]`; unset → single-trail |
| `P3_TARGET_TOLERANCE` / `--p3-target-tolerance` | `0.03` | ±pp band around the target |
| `P3_MAX_CASCADES_PER_TRAIL` / `--p3-max-cascades-per-trail` | `3` | per-trail cascade cap (bounds pile-up) |
| `P3_ENRICHMENT_OVER_PROVISION_MARGIN` / `--p3-enrichment-over-provision-margin` | `0.0` | emitted aligned fraction = `TARGET / (1 − margin)` |
| `P3_ENRICHMENT_DEDUP_WINDOW_MS` / `--p3-enrichment-dedup-window-ms` | `2000` | **must match** the deployed enrichment `dedupWindow`; drives the enrichment-safe inter-arrival lower bound |
| `P3_ENRICHMENT_TRANSIENT_TYPES` / `--p3-enrichment-transient-types` | pack-derived | comma-set of transient alarmTypes excluded from aligned cascades |
| `P3_DEDUP_SPACING_MARGIN` / `--p3-dedup-spacing-margin` | `0.1` | ε so the spacing lower bound is strictly above the dedup window |

Compatible-trail discovery uses the **existing published** Trail Builder `GET /trails?snapshotId&domain&limit&offset`
(paged) + `GET /trails/{id}` (no contract change); results are cached in the P3 config snapshot
(schemaVersion **2**, additive `compatibleTrails`) so a second run makes **zero** `GET /trails`
calls. Aligned cascades are **enrichment-safe by construction** (distinct object per element,
non-transient types, inter-arrival above the dedup window yet within the session window); a pattern
whose `sessionWindow.windowMs ≤ dedupWindow` is **excluded and logged** (recorded in
`enrichmentConflictPatterns`), never aborting the run. When per-trail caps make the target
unreachable the controller emits the maximum achievable and **logs a measurable shortfall**
(`p3.target_shortfall`, `shortfallCascades > 0`, exit 0 — never silent). `P3_ENRICHMENT_DEDUP_WINDOW_MS`
is an operator env that must match the deployed enrichment `FilterParams.dedupWindow` (config-not-contract).

## Observability + API

- `/health` — 200 when started + Kafka-connected, 503 otherwise.
- `/metrics` — Prometheus text (`simulator_alarms_emitted_total`, snapshot gauges, …).
- `/labels`, `/labels/{scenarioId}`, `/scenarios` — ground-truth retrieval for the eval oracle.
  In `synth` mode `/labels` additionally returns per-cascade P3 records
  `{patternId, trailId, rootCauseAlarmId, rootCauseAlarmType, childAlarmIds, scenarioType,
  instanceIndex, igpArea}` and `/labels/p3-summary` returns `{totalAlarms, alignedAlarms,
  nonAlignedAlarms, alignedFraction, distinctTrailsUsed, distinctAreasUsed, shortfallCascades,
  enrichmentSafeCount, enrichmentConflictPatterns, alignedFractionEmitted}` so the auto-correlation
  KPI (and the network-wide spread + enrichment-safe count) is directly computable. `instanceIndex`,
  `igpArea`, and the extra summary fields are additive (single-trail runs default them).
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
