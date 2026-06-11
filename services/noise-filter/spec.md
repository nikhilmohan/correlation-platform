# noise-filter — Service Spec

## Purpose

The Noise Filter is the Phase-2 statistical cleaning step on the history path. It consumes
enriched, trail-tagged alarms from `alarms.enriched`, groups them per trail within coarse
time windows, and runs DBSCAN per trail-window to distinguish dense incident clusters from
sparse outliers. Dense clusters are emitted as cleaned, trail-scoped alarm groups on
`transactions.clean`; sparse noise points are dropped and never forwarded. The output feeds
the Pattern Miner's session-window and PrefixSpan stages. All DBSCAN parameters (epsilon,
minSamples, window size) are sourced from the Knowledge Service — no thresholds are
hard-coded in the service.

Previously the service was fire-and-forget with respect to operational visibility: clustering
decisions were only metric-counted and debug-logged, nothing was persisted, and there was no
UI presence. This spec adds lightweight operational visibility: the service now persists one
aggregate stats record per finalized trail-window clustering execution and exposes a read API
so the web-ui can present run history in its correlation-stats module.

## Scope

**In scope:**
- Consume `alarms.enriched` (history path only; produced by the Enrichment Service).
- Group consumed alarms per `trailId` within a coarse configurable time window.
- Feature-vectorize each alarm within a trail-window using the alarm's timestamp, position in
  the dependency graph (via `managedObjectId` in the `<objectType>:<id>` scheme), alarm type
  (`eventType`), and severity (`perceivedSeverity`). Optionally include device/connection
  attributes of the alarm's managed object (e.g. `equipmentType`, `vendor`, `model`) obtained
  from the Topology Service query API; which attributes are included is config-driven
  (sourced from the Knowledge Service feature config — no attribute set is hard-coded),
  defaulting to a sensible set (e.g. `equipmentType` enabled, `vendor`/`model` disabled).
- Run DBSCAN (or HDBSCAN) per trail-window to identify dense clusters and label noise points.
- Drop noise-labeled points; emit each dense cluster as a `TransactionEvent` on
  `transactions.clean` (fields: `transactionId`, `trailId`, `snapshotId`, `alarmIds[]`,
  `windowStart`, `windowEnd`).
- Read DBSCAN parameters (epsilon, minSamples) and window size from the Knowledge Service;
  refresh when `knowledge.updated` is received.
- Route poison/unparseable messages to `alarms.enriched.dlq`.
- Deduplicate consumed events on `eventId` (at-least-once Kafka delivery).
- Expose `/health` and `/metrics` (Prometheus); emit structured JSON logs.
- Package as a Docker container with a Compose entry.
- **[NEW] Record one aggregate run-stats row per finalized trail-window execution** in the
  service's owned PostgreSQL run-stats table. Each row captures the identity of the run
  (`runId`, `runTimestamp`, `trailId`, `snapshotId`, `domain` if available, `windowStart`,
  `windowEnd`), the DBSCAN/HDBSCAN params actually used (`eps`, `minSamples`, `windowSize`,
  `algorithm`), and the aggregate counts of that execution (`alarmsIn`, `clustersFormed`,
  `alarmsKept`, `alarmsDropped`, `noiseRatio`). The stats write is best-effort and
  non-blocking: a write failure must not prevent the clustering pipeline from emitting
  `TransactionEvent`s; failures are logged as structured entries and counted in `/metrics`.
- **[NEW] Expose a read API** (the service's first HTTP business surface beyond `/health` and
  `/metrics`) for querying run-stats records: list recent runs with optional filtering by
  `trailId` and/or time range; return the aggregate stats fields listed above. The API is
  read-only. It is published as an OpenAPI 3.1 document at `/openapi.json` and checked into
  `services/noise-filter/`, per the `architecture.md` API-contract convention. Exact endpoint
  paths, query-parameter names, and response schema are a design-stage decision (see Open
  questions #5).

## Out of scope

- **Deterministic noise filtering** — flap-damping, self-clear suppression, maintenance
  suppression, known-chatter lists. That is the Enrichment Service's responsibility
  (upstream, §6.6).
- **Session-window finalization** — splitting cleaned groups into gap-bounded transactions
  used for mining is the Pattern Miner's responsibility (§6.8). This service emits raw,
  coarse-window groups; final session boundaries are not set here.
- **Pattern mining** — discovering frequent sequences is the Pattern Miner's responsibility.
- **Trail building** — the service reads `trailIds[]` already stamped on each `AlarmEvent`
  by Enrichment; it does not call the Trail Builder to resolve trails itself.
- **Live-path statistical (DBSCAN) cleaning** — the service does not consume
  `alarms.enriched.live`. **Live alarms are not unfiltered:** Enrichment applies its
  *deterministic* filters (dedup, self-clear, flap-damping, known-chatter) on the live path in
  real time before they reach Correlation. What the live path deliberately skips is this
  service's *statistical* DBSCAN stage, because: (a) DBSCAN's role is to produce clean *training*
  transactions for the Pattern Miner (a Phase-2 learning concern, per §6.7), and (b) real-time
  noise rejection is instead achieved by the Correlation Engine's closest-match decode against
  approved patterns + codebook, which is noise-tolerant by design (tolerates missing alarms,
  penalizes spurious — §6.10). Hence this service is Idle in P3. (Adding a real-time statistical
  cleaning stage would be an architecture change, not part of the MVP.)
- **Pattern state and lifecycle** — owned by Pattern Manager (§6.9).
- **Topology graph traversal** — the service does not traverse the NebulaGraph graph directly.
  When attribute features are enabled, it reads node/edge `attributes` from the Topology
  Service's published query API. Richer graph-position features (propagation depth, hop count)
  remain a design-stage modeling decision (see Open questions #1) and are not covered by this
  update.
- **Attribute catalogue ownership** — the service does not decide which attributes exist or
  what their domain semantics are. The Knowledge Service is the authoritative catalogue of
  device/connection attribute keys per domain (see `architecture.md` → "Domain extensibility").
  The noise-filter only reads which attribute keys to include in the feature vector from its
  Knowledge-Service-sourced feature config.
- **Per-alarm persistence** — the service does not persist individual alarm records, feature
  vectors, or the IDs of dropped alarms. The run-stats table stores aggregate counts only.
  This is lightweight operational telemetry, not a historical alarm corpus, and does not
  violate the architecture's "live-only, no historical corpus" rule (the table stores no
  alarms — only aggregate run counts).
- **Write API / mutation of run-stats** — the exposed API is read-only. Run-stats rows are
  written exclusively by the service's own pipeline; no external caller may create, update, or
  delete them.
- **New Kafka topics or event-model changes** — the run-stats capability adds a service-owned
  store and a read API. It introduces no new Kafka topic and no change to `AlarmEvent`,
  `TransactionEvent`, or any other `acp-event-model` payload.
- **Codebook reconciliation or RCA** — owned by Pattern Manager.
- **UI implementation** — the web-ui presents NF run stats in its existing correlation-stats
  module; that module's implementation is a web-ui spec/design concern. This spec only states
  the backend contract the web-ui consumes.

## Tasks (high-level)

1. **Ingest `alarms.enriched`** — consume the history-path topic, deserialize using the
   `acp-event-model` Python/Pydantic binding, deduplicate on `eventId`, and reject unknown
   major `schemaVersion`.
2. **Partition into trail-windows** — group incoming `AlarmEvent` records by each `trailId`
   they carry and bucket them into coarse time windows whose width is the Knowledge-Service
   `windowSize` parameter.
3. **Feature-vectorize alarms** — for each alarm within a trail-window, produce a feature
   vector from: arrival timestamp (relative to window start), object-type layer derived from
   the `managedObjectId` type prefix, `eventType`, and `perceivedSeverity`. When enabled by
   feature config, extend the vector with device/connection attributes (e.g. `equipmentType`,
   `vendor`, `model`) fetched for the alarm's managed object from the Topology Service query
   API. The active attribute set is determined solely by the Knowledge-Service-sourced feature
   config; no attribute key is hard-coded. The Topology query call is skipped when all
   attribute features are disabled.
4. **Run DBSCAN per trail-window** — apply DBSCAN (or HDBSCAN) with Knowledge-Service params
   `eps` and `minSamples`; label each alarm as cluster-member or noise.
5. **Emit cleaned groups** — for each dense cluster in a trail-window, construct a
   `TransactionEvent` (with a fresh `transactionId`, the `trailId`, the `snapshotId` in
   scope, the `alarmIds[]` of cluster members, and `windowStart`/`windowEnd`) and publish it
   to `transactions.clean`.
6. **Record run-stats** — after finalizing each trail-window execution, write one aggregate
   row to the owned PostgreSQL run-stats table capturing: `runId` (unique), `runTimestamp`,
   `trailId`, `snapshotId`, `domain` (if available), `windowStart`, `windowEnd`, the params
   used (`eps`, `minSamples`, `windowSize`, `algorithm`), and the counts `alarmsIn`,
   `clustersFormed`, `alarmsKept`, `alarmsDropped`, and `noiseRatio` (`alarmsDropped /
   alarmsIn`). This write is best-effort: if it fails, the pipeline has already emitted
   the `TransactionEvent`(s) and processing continues; the failure is logged and counted.
7. **Refresh Knowledge parameters** — on receipt of `knowledge.updated`, re-fetch DBSCAN
   params and window size from the Knowledge Service so subsequent windows use updated values
   without requiring a service restart.
8. **Handle errors** — route poison/unparseable messages to `alarms.enriched.dlq`; log drops
   with sufficient context for ops.
9. **Serve run-stats read API** — respond to read requests for run-stats records, supporting
   optional filtering by `trailId` and/or time range (`runTimestamp`). Return aggregate stats
   rows from the owned PostgreSQL table. Validate responses against the service's published
   OpenAPI 3.1 spec.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs / Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail construction are underway but no alarms are processed by this service. | Idle | — |
| P2 — Pattern learning | Core worker: statistically cleans the enriched historical alarm stream so the Pattern Miner receives only incident-dense groups; records aggregate run-stats per execution for operational visibility. | Active | Consumes: `alarms.enriched`. Produces: `transactions.clean`. Calls: Knowledge Service (DBSCAN params via its published API). Writes: run-stats table (best-effort). |
| P3 — Real-time correlation | Not involved in the clustering pipeline. Live alarms are still **deterministically** filtered by Enrichment (dedup/self-clear/flap/chatter) on the live path; only this service's **statistical DBSCAN** stage is skipped live. DBSCAN's job is to clean Phase-2 *training* data for the Miner; real-time noise rejection is handled instead by the Correlation Engine's noise-tolerant pattern/codebook matching (tolerates missing, penalizes spurious). The run-stats read API remains available for web-ui queries. | Idle (pipeline); Passive (read API) | Serves: run-stats read API (queried by web-ui). |

## Contract

- **Consumes (Kafka):** `alarms.enriched` (payload: `AlarmEvent` from `acp-event-model`)
- **Produces (Kafka):** `transactions.clean` (payload: `TransactionEvent` from `acp-event-model`)
- **APIs exposed:**
  - `/health` (liveness/readiness) and `/metrics` (Prometheus exposition format) — unchanged.
  - **[NEW] Run-stats read API** — the service's first HTTP business surface. A read-only API
    for listing and querying run-stats records (filter by `trailId`, time range). Published as
    OpenAPI 3.1 at `/openapi.json`; the generated `openapi.json` is checked into
    `services/noise-filter/` and serves as the single source of truth for this surface. Used
    for the service's own contract/unit tests and consumed by the web-ui (which builds its
    client against this published spec). Exact endpoint paths, query-parameter names, and
    response schema are a **design-stage decision** (see Open questions #5). Fields exposed per
    row: `runId`, `runTimestamp`, `trailId`, `snapshotId`, `domain`, `windowStart`,
    `windowEnd`, `eps`, `minSamples`, `windowSize`, `algorithm`, `alarmsIn`,
    `clustersFormed`, `alarmsKept`, `alarmsDropped`, `noiseRatio`.
  - A change to this API surface is a contract change requiring an `architecture.md`/spec
    update and human approval, per the architecture convention.
- **APIs / data consumed from other services:**
  - **Knowledge Service** — fetch DBSCAN params (`eps`, `minSamples`) and window size at
    startup and on `knowledge.updated`; fetch feature config (which device/connection attribute
    keys to include in the feature vector). Built and tested against the Knowledge Service's
    published OpenAPI 3.1 spec.
  - **Topology Service query API** — when one or more attribute features are enabled by feature
    config, fetch the `attributes` map of a topology node/edge by `managedObjectId`. Built and
    tested against the Topology Service's published OpenAPI 3.1 spec. Not called when all
    attribute features are disabled. The exact query operation shape (endpoint, request/response
    fields) is a design-stage dependency on the Topology Service's published OpenAPI (see Open
    questions #4).
- **Integration points (mock vs. real):**
  - **Knowledge Service** — config-switchable: mock (stub generated from the Knowledge
    Service's published OpenAPI 3.1) for unit tests; real Knowledge Service for integration.
    Resolved by `KNOWLEDGE_SERVICE_URL` and `KNOWLEDGE_CLIENT_MODE=mock|real` env vars.
  - **Topology Service query API** — config-switchable: mock (stub generated from the Topology
    Service's published OpenAPI 3.1) for unit tests; real Topology Service for integration.
    Resolved by `TOPOLOGY_SERVICE_URL` and `TOPOLOGY_CLIENT_MODE=mock|real` env vars. The
    client is only instantiated when at least one attribute feature is enabled in feature
    config; it is fully bypassed otherwise.
  - **[NEW] PostgreSQL run-stats store** — configured via `NOISE_FILTER_DB_URL` env var. In
    unit tests the store may be backed by an in-memory or containerized PostgreSQL instance
    (designer's choice); in integration it is the shared PostgreSQL service. The run-stats
    write path is always best-effort; the read API depends on the store being reachable.
- **Data owned:**
  - **[NEW] PostgreSQL run-stats schema** (NF-owned, internal, single-owner per the
    architecture convention) — one lightweight execution-stats table. Each row corresponds to
    one finalized trail-window clustering execution and stores only aggregate counts and params
    (no individual alarm records, no feature vectors, no dropped-alarm IDs). The ephemeral
    in-process window/dedupe state is separate and unchanged. This is lightweight operational
    telemetry; it stores no alarm payloads and does not constitute a historical alarm corpus.

## Non-functional

- **Idempotency key:** `eventId` (envelope field) — duplicates from at-least-once Kafka
  delivery are detected and dropped within the processing window.
- **Config:** all runtime parameters from environment variables or the Knowledge Service; no
  hard-coded thresholds or attribute key lists. Required env vars: `KAFKA_BOOTSTRAP_SERVERS`,
  `KAFKA_CONSUMER_GROUP_ID`, `KNOWLEDGE_SERVICE_URL`, `KNOWLEDGE_CLIENT_MODE` (`mock|real`),
  `TOPOLOGY_SERVICE_URL`, `TOPOLOGY_CLIENT_MODE` (`mock|real`), `LOG_LEVEL`,
  **`NOISE_FILTER_DB_URL`** (PostgreSQL connection URL for the run-stats store). DBSCAN `eps`,
  `minSamples`, and `windowSize` are Knowledge-Service parameters (fetched at startup and
  refreshed on `knowledge.updated`). The feature config (which device/connection attribute
  keys to include in the feature vector, and their default on/off state) is also a
  Knowledge-Service parameter — no attribute key is hard-coded in the service.
- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus exposition
  format), structured JSON logs (no plain-text log lines in production). Stats-write failures
  must be counted in a dedicated `/metrics` counter and logged as structured entries.
- **API contract:** the service publishes an OpenAPI 3.1 spec at `/openapi.json` (checked into
  `services/noise-filter/`) covering the run-stats read API. The published spec is the single
  source of truth for the HTTP business surface; the service's own contract/unit tests validate
  against it, and collaborators (web-ui) build their client against it. A surface change is a
  contract change.
- **Stats write resilience:** the run-stats persistence step is best-effort and non-blocking.
  A failure to write a stats row must not prevent the clustering pipeline from emitting
  `TransactionEvent`s to `transactions.clean`. The failure is logged (structured) and counted
  in `/metrics`; the pipeline continues with the next window.
- **Error handling:** poison / unparseable messages → `alarms.enriched.dlq`. Unknown major
  `schemaVersion` → `alarms.enriched.dlq` with a structured log entry. Transient Knowledge
  Service failures → retry with backoff; the service does not start if params cannot be loaded.
  Stats-write failures → log + metric; pipeline continues.
- **Reproducibility:** for the same input window and the same Knowledge-Service params, DBSCAN
  must produce the same cluster labeling deterministically (required for regression tests).

## Acceptance criteria

Each criterion maps to one pytest test.

1. **Noise drop — chatty alarm removed.** Given a trail-window containing a known tight cascade
   cluster (fiber-cut pattern: LOS → LinkDown → AdjDown → LSPDown) plus one injected
   coincidental chatty alarm whose feature vector falls outside cluster density, the service
   emits exactly one `TransactionEvent` containing the cascade alarm IDs and does not include
   the chatty alarm's ID.

2. **Cluster preserved intact.** Given a trail-window containing only the tight cascade cluster
   alarms (no injected noise), the service emits one `TransactionEvent` whose `alarmIds[]`
   contains every alarm in the cascade and no alarms are dropped.

3. **DBSCAN params from Knowledge Service — changing params changes results.** Given two runs
   over the same input trail-window, one with tight params (small `eps`, high `minSamples`)
   and one with loose params (large `eps`, low `minSamples`) served by the mock Knowledge
   Service, the output differs: the tight params produce fewer or no dense clusters; the loose
   params produce at least one cluster. Demonstrates that no threshold is hard-coded and that
   the Knowledge Service is the sole source of DBSCAN configuration.

4. **TransactionEvent schema validity.** Every `TransactionEvent` emitted by the service
   validates against the `TransactionEvent` JSON Schema from `libs/event-model` (all required
   fields present: `transactionId`, `trailId`, `snapshotId`, `alarmIds`, `windowStart`,
   `windowEnd`; `alarmIds` is non-empty for emitted events).

5. **Idempotency on duplicate eventId.** Given the same `AlarmEvent` delivered twice on
   `alarms.enriched` (identical `eventId`), the service processes it once and the output
   `TransactionEvent` references the alarm's ID exactly once.

6. **Poison message to DLQ.** Given a Kafka message on `alarms.enriched` that cannot be
   deserialized (malformed JSON), the service routes it to `alarms.enriched.dlq` and continues
   processing subsequent messages without crashing.

7. **Unknown schemaVersion to DLQ.** Given a Kafka message on `alarms.enriched` with an
   unknown major `schemaVersion`, the service routes it to `alarms.enriched.dlq` with a
   structured log entry and continues processing subsequent messages.

8. **Knowledge param refresh at runtime.** Given that the mock Knowledge Service returns
   updated DBSCAN params after a `knowledge.updated` event is received, subsequent
   trail-windows are processed with the new params (verified by observing the cluster labeling
   change for a fixed input window; no service restart required).

9. **Noise-filter effectiveness measurable.** Given a synthetic trail-window with a known
   count of injected noise alarms (N) and real cascade alarms (M) derived from the Simulator's
   ground-truth label, the service's output `TransactionEvent` contains at least ⌈M × 0.9⌉
   real alarm IDs and at most ⌊N × 0.1⌋ noise alarm IDs. This makes the §10 noise-filter
   effectiveness metric (% injected noise removed vs. real alarms retained) computable against
   the Simulator oracle.

10. **Attribute feature config-driven — inclusion and exclusion.** Given a trail-window with
    alarms whose managed objects have `equipmentType` values in the mock Topology Service
    response, two runs are made: one with `equipmentType` enabled in the mock Knowledge
    Service feature config, one with `equipmentType` disabled. When enabled, the feature
    vector for each alarm includes a dimension for `equipmentType` (verified by observing that
    alarms with distinct `equipmentType` values can be separated into different clusters for a
    carefully constructed window). When disabled, the feature vector does not include an
    `equipmentType` dimension and the Topology Service client is not called. Demonstrates that
    no attribute key is hard-coded and the active feature set is solely determined by the
    Knowledge-Service feature config.

11. **Run-stats row correctness.** Given a trail-window execution that processes `alarmsIn`
    alarms and produces `clustersFormed` dense clusters containing a total of `alarmsKept`
    cluster-member alarms, the service writes exactly one run-stats row with: `alarmsIn` equal
    to the total alarms in the window, `alarmsDropped` equal to `alarmsIn - alarmsKept`,
    `alarmsKept` matching the count of alarms emitted across all `TransactionEvent`s for that
    window, `clustersFormed` matching the number of dense clusters identified, `noiseRatio`
    equal to `alarmsDropped / alarmsIn` (to a reasonable floating-point tolerance), and the
    `eps`, `minSamples`, `windowSize`, and `algorithm` values matching the params actually
    used for that execution.

12. **Run-stats read API returns recorded rows and validates against OpenAPI.** Given one or
    more completed trail-window executions that have written run-stats rows, a GET request to
    the run-stats list endpoint returns those rows with field values matching what was recorded,
    and each response validates against the service's published OpenAPI 3.1 spec (all required
    fields present and correctly typed).

13. **Stats-write failure does not block TransactionEvent emission.** Given a trail-window
    execution where the PostgreSQL run-stats write is configured to fail (e.g. DB unavailable
    or simulated write error), the service still emits the expected `TransactionEvent`(s) to
    `transactions.clean`, logs a structured error entry, and increments the stats-write-failure
    metric counter. The pipeline does not raise an unhandled exception or stall.

14. **Run-stats query by trailId returns matching subset.** Given run-stats rows recorded for
    two distinct `trailId` values, a GET request to the run-stats endpoint filtered by one
    `trailId` returns only the rows for that trail and excludes rows for the other `trailId`.

## Open questions

Items 1–4 are carried forward from the prior spec revision (design-stage, not blockers).
Items 5–6 are new, added with the run-stats capability.

1. **[DESIGN-STAGE] Feature vectorization — richer graph position** (tracked: #48).
   Task 3 derives object-type layer from the `managedObjectId` type prefix only (e.g.
   `FiberSpan`, `IPLink`). Whether the designer needs richer graph-position features
   (e.g. propagation depth, hop count from fault origin) — and thus a Topology/Trail Builder
   API call at vectorization time — is a modeling decision for the design stage. If richer
   features are chosen, the designer adds the corresponding integration point in `design.md`
   as a config-switchable dependency (no spec/contract change required unless a new topic or
   payload field is introduced).

2. **[DESIGN-STAGE] `snapshotId` provenance for `TransactionEvent`** (tracked: #51).
   `TransactionEvent` requires a `snapshotId`; `AlarmEvent` on `alarms.enriched` does not
   carry it. The exact mechanism for obtaining `snapshotId` is a design decision. The
   leading candidate is to derive it from the trail context via Trail Builder
   `getTrail(trailId)`, since the service already scopes processing per trail — but the
   designer chooses the implementation. No `AlarmEvent` contract change is introduced in
   this spec.

3. **[DESIGN-STAGE] `knowledge.updated` as explicit consumed topic** (tracked: #52).
   Whether noise-filter subscribes to `knowledge.updated` to trigger live param refresh, or
   polls the Knowledge API on its own schedule, is a design-stage wiring choice. DBSCAN
   params are read from the Knowledge Service API regardless. No `architecture.md` consumer
   mapping update is required at the spec stage.

4. **[DESIGN-STAGE] Topology query API shape for node/edge attributes.** The attribute
   feature integration requires the Topology Service to expose a query operation that returns
   the `attributes` map for a given `managedObjectId`. The exact endpoint path, request
   parameters, and response schema (including how it surfaces the `attributes` map and handles
   unknown `managedObjectId`) is a **design-stage API-shape dependency** on the Topology
   Service's published OpenAPI 3.1. The noise-filter designer must build the Topology client
   against that published OpenAPI; if the required operation is absent from the Topology
   Service's published spec, that is a contract gap requiring human resolution before the
   noise-filter design can proceed (a Topology Service contract change, not a noise-filter
   spec change). No new Kafka topic or `AlarmEvent`/`TransactionEvent` field is introduced.

5. **[DESIGN-STAGE] Run-stats read API — exact endpoint shape.** The spec states the
   requirement (list/query recent runs; filter by `trailId` / time range; return aggregate
   stats rows) and the fields to expose, but the exact endpoint path(s), query-parameter
   names, pagination strategy, sort order, and maximum result set are a **design-stage
   decision**. The designer defines these in `design.md` and publishes the resulting
   `openapi.json` as the service's HTTP contract. If the designer determines that any
   required field is absent from the run-stats table (e.g. `domain` is optional and may be
   null), the handling of absent/null fields is also a design decision — not a spec change.

6. **[DESIGN-STAGE] Run-stats DB schema column finalization.** The spec enumerates the
   required columns and their logical types. Final column names, SQL types (e.g. `NUMERIC`
   vs. `FLOAT` for `noiseRatio`), indices (e.g. index on `trailId` and `runTimestamp` for
   query performance), and the schema migration strategy are a **design-stage decision** for
   the designer to specify in `design.md`. No new Kafka topic or event-model change is
   required for any of these decisions.
