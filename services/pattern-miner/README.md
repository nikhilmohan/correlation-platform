# pattern-miner

**Cohort:** Python (PySpark)
**Owned datastore:** — (stateless Spark job; emits and forgets)
**Phase:** P2 — Pattern learning (Active). Idle in P1/P3.

The ML-execution service for the Pattern Learning phase. It discovers **recurring fault cascades**
from trail-scoped, DBSCAN-cleaned `TransactionEvent`s on `transactions.clean` through a
**three-stage pipeline**, emitting a small, accurate set of root-cause-grounded patterns as
`PatternMinedEvent`s on `patterns.mined` — **one per anchored fault-origin group** (plus one for the
unexplained group):

1. **Stage 1 — time + space correlation.** Re-window the typed `alarms[]` into **dynamic
   activity/idle sessions per trail** (the closing gap adapts to each burst's tempo) → candidate
   cascades. (Retained from the prior design; see "Adaptive session windowing".)
2. **Stage 2 — domain-knowledge anchoring.** Fetch the domain's fault-origin scenarios from the
   **Codebook Service** and assign each candidate cascade to the scenario it best matches (the
   anchor) using a weighted **LCS-ratio (ordered chain coverage) + Jaccard (union set overlap)**
   scorer, thresholded by a Knowledge-sourced `matchConfidenceThreshold`. LCS-ratio prevents
   over-split; Jaccard-union + single-anchor argmax prevents over-merge. Cascades below the
   threshold are **unexplained** (`provenance.anchorScenarioId = null`) — a first-class outcome.
3. **Stage 3 — bounded PrefixSpan per anchored group.** Run **PrefixSpan (Spark MLlib) within each
   anchored group** (not globally) to learn that fault-origin's canonical ordered `alarmType`
   signature, support, confidence, lift. Bounded scope removes the prior global-mining OOM and the
   frequency-lattice explosion.

It holds **no pattern state** — no RCA, no `rootCauseAlarmType`, no `patternId`, no lifecycle, no
codebook *reconciliation* (Stage 2 only *anchors* against the codebook), no explainability, no
Pattern Store, and **no topology-graph access** (the Codebook client reads scenarios only). Those
belong exclusively to the **Pattern Manager** / **Topology Service**. The boundary is enforced by the
frozen `PatternMinedEvent` schema (`extra="forbid"`).

## Contract

- **Consumes:** `transactions.clean` (`TransactionEvent` with typed `alarms[]`: six required fields
  per entry — `alarmId, alarmType, eventType, raisedAt, managedObjectId, perceivedSeverity`).
- **Produces:** `patterns.mined` (`PatternMinedEvent`). Poison → `transactions.clean.dlq`.
- **Mined `sequence` item = `alarms[].alarmType`** — the canonical join token from the domain's
  Knowledge `alarmTypeVocabulary` (e.g. `["FiberFault","LinkDown","AdjDown"]`), **never**
  `eventType` (X.733 category) or `probableCause`.
- **`timing`** (open object) carries the canonical **millisecond** keys the Pattern Manager's
  `SessionWindowDeriver` consumes: `timeframeMs`, `medianInterArrivalMs`, `maxInterArrivalMs`,
  `stddevInterArrivalMs` (median, not mean; ms, not seconds).
- **`provenance`**: `sourceWindowId` (composite session ref), `snapshotId` (from the source
  transaction), `codebookVersion` (from the Knowledge mining-params response — kept verbatim, e.g.
  `"current"`), `domain`, and **`anchorScenarioId`** (the matched Codebook `scenarioId`, or
  null/absent for the unexplained group — landed in `libs/event-model` via PR #331).
- **Idempotency:** dedupe on the envelope `eventId`.

## Knowledge integration (mining params — config-switchable mock/real)

All mining + windowing parameters come from the **Knowledge Service** at runtime — **no hard-coded
thresholds**. Fetched before each run from the frozen versioned-record endpoint:

```
GET /domains/{domain}/model-params/{recordId}
```

- `recordId` = `core-ip/modelParams/pattern-miner` (contains slashes → **percent-encoded** into a
  single path segment); the `recordType` path segment is **kebab-case** `model-params`.
- The response is a **RecordResponse envelope**; params live under `.payload.params[]` as
  `{key, value}` entries with dotted keys: `prefixspan.minSupport`, `prefixspan.maxPatternLength`,
  `prefixspan.maxSequenceCount`, `window.adaptive.baseGapSeconds`, `window.adaptive.gapMultiplier`,
  `window.adaptive.tempoPercentile`, `window.adaptive.profiles`, `codebookVersion`, and the
  **Stage-2 anchoring** keys `anchoring.matchConfidenceThreshold`, `anchoring.weights.order`,
  `anchoring.weights.jaccard` (required — no code default), plus optional `anchoring.scoringMethod`,
  `anchoring.tieBreak`, `anchoring.groupingKeys` (structural template defaults).
- Unit tests mock this with `respx` (enveloped shape); integration points at the real Knowledge
  Service. Base URL + mock/real toggle come from env — no hard-coded URLs.

## Codebook integration (Stage-2 fault-origin scenarios — config-switchable mock/real)

Stage 2 fetches the domain's fault-origin scenarios from the **Codebook Service** via a client built
against its **published `/openapi.json`** (never its source). Endpoints (verified live; no `/api/v1`):

```
GET /codebooks/active?domain={domain}&snapshotId={snapshotId}   # BOTH params required -> codebookId
GET /codebooks/{codebookId}/scenarios                           # -> ScenarioListResponse
```

- The miner already has `domain` + `snapshotId` at mining time; it resolves the active codebook by
  snapshot (OQ-3 path). The symbolic `codebookVersion="current"` is kept verbatim in provenance —
  the concrete `codebookId` is a runtime detail, not a schema field.
- Each scenario carries `{scenarioId, faultOriginObjectId, faultOriginType,
  predictedSymptoms:[{alarmType, managedObjectId}], trailIds:[...]}`; the ordered
  `predictedSymptoms[].alarmType` list is the canonical fault-origin **symptom chain** matched
  against each cascade.
- If the Codebook is unavailable (or no active codebook for the snapshot), the run **fails fast**
  (offsets not committed, retried later) — it **never** falls back to unanchored global mining.
- Unit tests mock this with `respx` against the published spec; integration points at the real
  Codebook Service. Base URL + mock/real toggle + retry policy come from env — no hard-coded URLs.

## Adaptive session windowing (spec OQ#50 — hybrid)

Per trail, alarms are pooled and split into activity sessions closed when the trail falls idle. The
closing gap is a **hybrid**: `max(gapMultiplier × median(intra-burst gaps), tempo-class floor)`,
clamped to `[floor, maxClosingGap]`, with the Knowledge `baseGapSeconds` fallback when a burst has
too few inter-arrivals. A fast cascade (small gap → kept whole) and a slow build-up (large gap →
kept whole) both resolve to one session calibrated to their own tempo; a genuine idle period
between bursts splits them.

## Mining engine (Spark MLlib PrefixSpan — container-only)

PrefixSpan runs as pure sequence mining (no topology). Spark/PySpark is **container-only** (not
installed on the host). The engine is a config toggle:

- `MINING_ENGINE=spark` (default, deployed) — real Spark MLlib `PrefixSpan` in `SPARK_MASTER`
  (`local[*]` in the container).
- `MINING_ENGINE=local` — a pure-Python reference PrefixSpan with identical semantics, used by the
  local unit gate where Spark is unavailable.

## Config (env)

`KAFKA_BOOTSTRAP_SERVERS`, `TRANSACTIONS_CLEAN_TOPIC`, `PATTERNS_MINED_TOPIC`, `DLQ_TOPIC`,
`CONSUMER_GROUP_ID`, `KNOWLEDGE_BASE_URL`, `KNOWLEDGE_CLIENT_MODE` (`mock`/`real`),
`KNOWLEDGE_DOMAIN`, `KNOWLEDGE_MODEL_PARAMS_RECORD_ID`, `KNOWLEDGE_RETRY_MAX`,
`KNOWLEDGE_RETRY_BACKOFF_MS`, `CODEBOOK_BASE_URL`, `CODEBOOK_CLIENT_MODE` (`mock`/`real`),
`CODEBOOK_RETRY_MAX`, `CODEBOOK_RETRY_BACKOFF_MS`, `SPARK_MASTER`, `MINING_ENGINE`,
`BATCH_FLUSH_SECONDS`, `HTTP_PORT`, `LOG_LEVEL`. **No mining/anchoring-threshold or windowing-gap
env vars** — those come only from Knowledge; the scenario set comes only from the Codebook.

**[BATCH-CAP] operational batching + Spark-resilience knobs (NOT mining thresholds):**
`MAX_TRAILS_PER_BATCH` (default `8`) caps a mining **sub-run** to at-most that many WHOLE trails so
the Stage-3 Spark collect fits the driver heap — a larger flush is processed as multiple bounded
sub-runs, each anchoring→grouping→PrefixSpan→emitting independently, with offsets committed **once**
after all sub-runs (at-least-once + `eventId` dedupe keep replay safe). A trail is **never** split
across sub-runs (the cascade stays intact, per-run support not diluted). An optional Knowledge
`batching.maxTrailsPerBatch` overrides the env default. `SPARK_RECREATE_MAX_ATTEMPTS` (default `3`)
and `SPARK_RECREATE_BACKOFF_MS` (default `2000`) bound the SparkSession recreate on a detected
driver/gateway death (`Py4JNetworkError` / connection-refused / empty-answer): the engine resets and
rebuilds a fresh session before retrying; on exhaustion the run fails **clean** (offsets uncommitted,
replayable) and `/health` reports Spark not-ready, self-healing on the next successful build (never a
silent permanent wedge).

## Observability

`GET /health` (liveness/readiness incl. Kafka + Knowledge + **Codebook** reachability);
`GET /metrics` (Prometheus — incl. `pm_cascades_anchored_total`, `pm_cascades_unexplained_total`,
`pm_codebook_fetch_failures_total`, `pm_anchored_group_count`, and the [BATCH-CAP] counters
`pm_mining_sub_runs_total`, `pm_spark_recreate_attempts_total`, `pm_spark_recreate_failures_total`
+ gauge `pm_last_flush_sub_run_count`). `/health` also reports a Spark-subsystem readiness flag
(`spark`) that dips only after recreate exhaustion and self-heals (never latches DOWN). Structured
JSON logs (incl. the per-cascade anchoring outcome and per-sub-run `sub_run_index`,
`trails_in_sub_run`). No business HTTP surface, no published OpenAPI spec.

## Build / test / run

```bash
# Unit gate (host; no Spark, no broker — uses the pure-Python PrefixSpan engine)
python -m venv .venv && . .venv/bin/venv activate
pip install ../../libs/event-model/python
pip install -e ".[dev]"
ruff check src tests && black --check src tests
pytest                      # unit/contract (spark + integration deselected)

# Spark engine tests (container-only: real Spark MLlib PrefixSpan in local[*])
pip install ".[spark]"      # inside the container image (needs a JRE)
pytest -m spark

# Real-entrypoint integration test (serve() → consume→mine→produce + /health over HTTP)
pytest -m integration

# Container
docker build -f services/pattern-miner/Dockerfile -t acp/pattern-miner:dev .   # from repo root
docker run --rm -p 8089:8080 acp/pattern-miner:dev
```
