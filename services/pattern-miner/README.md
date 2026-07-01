# pattern-miner

**Cohort:** Python (PySpark)
**Owned datastore:** — (stateless Spark job; emits and forgets)
**Phase:** P2 — Pattern learning (Active). Idle in P1/P3.

The ML-execution service for the Pattern Learning phase. It consumes trail-scoped,
DBSCAN-cleaned `TransactionEvent`s from `transactions.clean`, re-windows the typed `alarms[]`
into **dynamic activity/idle sessions per trail** (the closing gap adapts to each burst's tempo),
runs **PrefixSpan (Spark MLlib)** over the session-windowed `alarmType`-token sequences, computes
**support / confidence / lift**, and emits **one `PatternMinedEvent` per discovered sequence** on
`patterns.mined`.

It holds **no pattern state** — no RCA, no `rootCauseAlarmType`, no `patternId`, no lifecycle, no
codebook reconciliation, no explainability, no Pattern Store, no topology access. Those belong
exclusively to the **Pattern Manager**. The boundary is enforced by the frozen `PatternMinedEvent`
schema (`extra="forbid"`).

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
  transaction), `codebookVersion` (from the Knowledge mining-params response), `domain`.
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
  `window.adaptive.tempoPercentile`, `window.adaptive.profiles`, `codebookVersion`.
- Unit tests mock this with `respx` (enveloped shape); integration points at the real Knowledge
  Service. Base URL + mock/real toggle come from env — no hard-coded URLs.

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
`KNOWLEDGE_RETRY_BACKOFF_MS`, `SPARK_MASTER`, `MINING_ENGINE`, `BATCH_FLUSH_SECONDS`, `HTTP_PORT`,
`LOG_LEVEL`. **No mining-threshold or windowing-gap env vars** — those come only from Knowledge.

## Observability

`GET /health` (liveness/readiness incl. Kafka + Knowledge reachability); `GET /metrics`
(Prometheus). Structured JSON logs. No business HTTP surface, no published OpenAPI spec.

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
