# noise-filter — Service Spec

## Purpose

The Noise Filter's PRIMARY mission is **alarm storm reduction**: when a single fault
detonates a large burst of post-dedup, post-deterministic-filter alarms rippling across a
trail, the service must recognise that burst as ONE event and emit it as ONE clean
`TransactionEvent` capturing the cascade — while dropping coincidental alarms riding along
in the flood. A storm is temporally dense and trail-localised: every alarm it contains
arrived within a short window and shares the same trail. Trail-windowing is the coarse
relational filter; DBSCAN's dense-cluster detection is the storm/cascade detector. A storm
of N alarms from one fault collapses into ONE transaction group, yielding a volume/flood
reduction that keeps operators legible and keeps the Pattern Miner's input clean
enough to learn accurate sequences. Subtle outlier removal is the secondary function; flood
control is the primary one.

The service is the Phase-2 statistical cleaning step on the history path. It consumes
enriched, trail-tagged alarms from `alarms.enriched` (already deduped, flap-damped,
self-clear-suppressed, and known-chatter-removed by the Enrichment Service upstream).
What reaches this service is principally post-dedup alarm storms from single propagating
faults plus coincidental in-window alarms. The service groups these per trail within
coarse time windows and runs DBSCAN per trail-window to distinguish dense storm clusters
from sparse outliers. Dense clusters are emitted as cleaned, trail-scoped alarm groups on
`transactions.clean`; sparse noise points are dropped and never forwarded. The output
feeds the Pattern Miner's session-window and PrefixSpan stages.

**Retention-bias principle.** Because the Noise Filter is a one-way lossy gate, a dropped
real alarm is unrecoverable. A false-positive (noise kept) is cheap: the Pattern Miner's
min-support and the Correlation Engine's noise-tolerant pattern/codebook matching absorb
spurious alarms downstream. A false-negative (real cascade/storm member dropped) is
unrecoverable and breaks the mining input. Therefore the service must tune toward
KEEPING doubtful alarms over dropping them: retention (>= 0.95) is held above removal
(>= 0.90); when in doubt, keep. The Noise Filter is the gentle first pass, not the last
line — downstream defences exist.

All DBSCAN parameters (epsilon, minSamples, window size) and the active feature set are
sourced from the Knowledge Service — no thresholds are hard-coded in the service.

Previously the service was fire-and-forget with respect to operational visibility:
clustering decisions were only metric-counted and debug-logged, nothing was persisted, and
there was no UI presence. This spec adds lightweight operational visibility: the service
now persists one aggregate stats record per finalized trail-window clustering execution
and exposes a read API so the web-ui can present run history in its correlation-stats
module.

**[MVP] Observed-noise / chatter feedback loop (producer side).** The service learns (via
DBSCAN) which alarms are noise/chatter in P2, but historically that insight was discarded —
the sparse outliers it labels as noise were only counted and debug-logged, then dropped, and
the knowledge was never reused on the live path. This spec adds the PRODUCER side of an
operator-mediated noise-to-live feedback loop: in addition to the aggregate per-run counts,
the service now records the recurring **observed-noise / chatter SIGNATURES** it identifies
— the `(managedObjectId, alarmType)` pairs (the chatter key) that DBSCAN repeatedly labeled
as noise across runs/windows — each with an occurrence count and last-seen timestamp, and
exposes them through the run-stats read API. These are the candidate chatter entries an
operator would later promote into Enrichment's per-source known-chatter list (Enrichment-owned,
applied live). The loop is: **NF observes noise -> records aggregate signatures -> serves them
read-only -> the web-ui chatter-management page reads them -> the operator promotes selected
entries into Enrichment's known-chatter list.** The Noise Filter is strictly the PRODUCER and
REPORTER: it only writes signatures from its own clustering and serves them read-only. It does
**not** write to Enrichment, does not auto-promote, and does not change the live path. The
signatures are aggregate noise telemetry (signature keys + counts), NOT a per-alarm corpus, so
they stay within the "live-only, no historical corpus" rule (no alarm payloads are stored).

## Scope

**In scope:**
- Consume `alarms.enriched` (history path only; produced by the Enrichment Service after
  deterministic dedup, flap-damping, self-clear suppression, and known-chatter removal).
- Group consumed alarms per `trailId` within a coarse configurable time window.
- Feature-vectorize each alarm within a trail-window using the alarm's timestamp (relative
  to window start — the primary storm-density signal), object-type layer derived from the
  `managedObjectId` type prefix, `eventType`, and `perceivedSeverity`. Optionally extend
  the vector with device/connection attributes of the alarm's managed object (e.g.
  `equipmentType`, `vendor`, `model`) obtained from the Topology Service query API. The
  active attribute set is determined solely by the Knowledge-Service-sourced feature config;
  no attribute key is hard-coded.
- **[MVP] Topology hop-distance feature (soft, config-driven).** Optionally include one
  additional feature dimension: the propagation hop-distance of each alarm's managed object
  from the trail's fault-origin/seed along dependency edges. This dimension is one
  standardised entry in the DBSCAN feature vector, config-switchable on/off via the
  Knowledge-Service-sourced feature config (no feature is hard-coded). When enabled it
  requires a call to the Trail Builder query API (`getTrail(trailId)`) at vectorization
  time to resolve hop-distance from the trail's seed/root; this integration point is
  config-switchable (mock/real). It is a SOFT feature: it influences cluster density, it
  NEVER gates or drops alarms by a hard hop-threshold. Its primary benefit is separating
  two near-simultaneous faults on the same trail into distinct transaction groups rather
  than conflating them. The exact fault-origin/seed resolution and traversal mechanism is
  a design-stage note (Open questions #7). GUARDRAIL: enabling this feature must not
  reduce retention below the 0.95 oracle floor; the run-stats table makes any regression
  visible and CI-gatable.
- Run DBSCAN (or HDBSCAN) per trail-window to identify dense storm clusters and label
  noise points. Dense clusters represent storm/cascade events from a single propagating
  fault; the cluster detection is the storm-collapse mechanism.
- Drop noise-labeled points; emit each dense cluster (storm) as a `TransactionEvent` on
  `transactions.clean` (fields: `transactionId`, `trailId`, `snapshotId`, `alarmIds[]`,
  `windowStart`, `windowEnd`). ONE storm -> ONE `TransactionEvent`.
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
- **[MVP] Record observed-noise / chatter SIGNATURES (aggregate).** In addition to the per-run
  aggregate counts above, when DBSCAN labels an alarm as noise (a sparse outlier dropped from
  `transactions.clean`), the service records the alarm's **chatter signature** — the
  `(managedObjectId, alarmType)` pair (plus the alarm's `eventType` and the `trailId` in scope)
  — into an NF-owned aggregate observed-chatter store. The store keeps ONE row per distinct
  signature with an **occurrence count** (how many times that signature has been dropped as
  noise across runs/windows), a `firstSeen` and `lastSeen` timestamp. This is an aggregation over
  the noise the service already identifies — it stores signature keys + counts, NOT individual
  alarm records, NOT feature vectors, NOT a per-alarm corpus. It is lightweight noise telemetry
  consistent with the "live-only, no historical corpus" rule. The write is best-effort and
  non-blocking on the same terms as the run-stats write: a failure must not prevent the pipeline
  from emitting `TransactionEvent`s.
- **[MVP] Expose observed-chatter signatures via the read API.** Extend the read-only run-stats
  API with an endpoint that returns the recorded observed-chatter signatures ranked by occurrence
  count (most-frequent noise first), each row exposing `managedObjectId` (optional), `alarmType`,
  `eventType`, `trailId` (optional), `occurrenceCount`, `firstSeen`, and `lastSeen`. These are the
  candidate chatter entries an operator would review and promote into Enrichment's known-chatter
  list via the web-ui. The endpoint is READ-ONLY: the Noise Filter writes signatures only from its
  own clustering, and the operator-mediated promotion happens elsewhere (web-ui -> Enrichment),
  not via this service. Exact endpoint path, query-parameter names, and response schema are a
  design-stage decision (see Open questions #8).

## Out of scope

- **Deterministic noise filtering** — flap-damping, self-clear suppression, maintenance
  suppression, known-chatter lists. That is the Enrichment Service's responsibility
  (upstream, §6.6). What arrives at this service is already deterministically cleaned.
- **Session-window finalization** — splitting cleaned groups into gap-bounded transactions
  used for mining is the Pattern Miner's responsibility (§6.8). This service emits raw,
  coarse-window groups; final session boundaries are not set here.
- **Pattern mining** — discovering frequent sequences is the Pattern Miner's responsibility.
- **Trail building** — the service reads `trailIds[]` already stamped on each `AlarmEvent`
  by Enrichment; it does not call the Trail Builder to resolve trail membership for
  alarm-tagging. The Trail Builder query API is called only when the topology
  hop-distance feature is enabled (to resolve hop-distance from the trail's seed).
- **Live-path statistical (DBSCAN) cleaning** — the service does not consume
  `alarms.enriched.live`. **Live alarms are not unfiltered:** Enrichment applies its
  *deterministic* filters (dedup, self-clear, flap-damping, known-chatter) on the live
  path in real time before they reach Correlation. What the live path deliberately skips
  is this service's *statistical* DBSCAN stage, because: (a) DBSCAN's role is to produce
  clean *training* transactions for the Pattern Miner (a Phase-2 learning concern,
  per §6.7), and (b) real-time noise rejection is instead achieved by the Correlation
  Engine's closest-match decode against approved patterns + codebook, which is
  noise-tolerant by design (tolerates missing alarms, penalises spurious — §6.10). Hence
  this service is Idle in P3. (Adding a real-time statistical cleaning stage would be an
  architecture change, not part of the MVP.)
- **Pattern state and lifecycle** — owned by Pattern Manager (§6.9).
- **Topology graph traversal for graph ownership** — the service does not traverse the
  Apache AGE graph directly. When attribute features are enabled, it reads node/edge
  `attributes` from the Topology Service's published query API. When the topology
  hop-distance feature is enabled, it calls the Trail Builder query API (`getTrail`)
  to resolve hop-distance from the trail seed. Neither access constitutes graph ownership.
- **Hard hop-distance gating** — the hop-distance feature is strictly a soft density
  dimension. Dropping alarms based on a hop-count threshold is explicitly out of scope:
  NF is a one-way lossy gate and a dropped real cascade member is unrecoverable.
- **Attribute catalogue ownership** — the service does not decide which attributes exist
  or what their domain semantics are. The Knowledge Service is the authoritative catalogue
  of device/connection attribute keys per domain. The noise-filter only reads which
  attribute keys to include in the feature vector from its Knowledge-Service-sourced
  feature config.
- **Per-alarm persistence** — the service does not persist individual alarm records,
  feature vectors, or the IDs of dropped alarms. The run-stats table stores aggregate
  counts only, and the observed-chatter table stores aggregate signature keys + occurrence
  counts only. This is lightweight operational telemetry, not a historical alarm corpus,
  and does not violate the architecture's "live-only, no historical corpus" rule (the
  tables store no alarms — only aggregate run counts and chatter signatures + counts).
- **Promoting observed-chatter into the live known-chatter list / writing to Enrichment** —
  the service does NOT write to the Enrichment Service, does NOT call any Enrichment API, and
  does NOT auto-promote any observed-chatter signature into the live path. Enrichment owns the
  per-source known-chatter list (its own config). Promotion is an OPERATOR-MEDIATED action
  performed in the web-ui chatter-management page (which reads NF's observed-chatter signatures)
  and applied to Enrichment's config; the Noise Filter is only the read-only producer/reporter
  of candidate signatures. The clustering write path (DBSCAN -> `transactions.clean`) is
  unchanged by this capability.
- **Write API / mutation of run-stats** — the exposed API is read-only. Run-stats rows
  are written exclusively by the service's own pipeline; no external caller may create,
  update, or delete them.
- **New Kafka topics or event-model changes** — the run-stats capability and the
  observed-chatter signature capability each add a service-owned store and read-API surface
  only. They introduce no new Kafka topic and no change to `AlarmEvent`, `TransactionEvent`, or
  any other `acp-event-model` payload. The topology hop-distance feature likewise introduces no
  new topic or payload field. The operator-mediated promotion to Enrichment's known-chatter list
  is a web-ui-to-Enrichment interaction, not a Kafka topic or event-model change here.
- **Codebook reconciliation or RCA** — owned by Pattern Manager.
- **UI implementation** — the web-ui presents NF run stats in its existing
  correlation-stats module; that module's implementation is a web-ui spec/design concern.
  This spec only states the backend contract the web-ui consumes.

## Tasks (high-level)

1. **Ingest `alarms.enriched`** — consume the history-path topic, deserialize using the
   `acp-event-model` Python/Pydantic binding, deduplicate on `eventId`, and reject unknown
   major `schemaVersion`.
2. **Partition into trail-windows** — group incoming `AlarmEvent` records by each `trailId`
   they carry and bucket them into coarse time windows whose width is the Knowledge-Service
   `windowSize` parameter.
3. **Feature-vectorize alarms** — for each alarm within a trail-window, produce a feature
   vector from: arrival timestamp (relative to window start — the primary storm-density
   signal), object-type layer derived from the `managedObjectId` type prefix, `eventType`,
   and `perceivedSeverity`. When enabled by feature config, extend the vector with
   device/connection attributes (e.g. `equipmentType`, `vendor`, `model`) fetched for the
   alarm's managed object from the Topology Service query API. When the topology
   hop-distance feature is enabled by feature config, extend the vector with the
   propagation hop-distance of the alarm's managed object from the trail's fault-origin/seed
   (resolved via Trail Builder `getTrail(trailId)`). The active feature set is determined
   solely by the Knowledge-Service-sourced feature config; no feature key is hard-coded.
   Topology and Trail Builder calls are skipped when all corresponding features are
   disabled.
4. **Run DBSCAN per trail-window** — apply DBSCAN (or HDBSCAN) with Knowledge-Service
   params `eps` and `minSamples`; label each alarm as cluster-member (storm participant)
   or noise (sparse outlier).
5. **Emit cleaned groups** — for each dense cluster (storm) in a trail-window, construct a
   `TransactionEvent` (with a fresh `transactionId`, the `trailId`, the `snapshotId` in
   scope, the `alarmIds[]` of cluster members, the typed `alarms[]` of cluster members, and
   `windowStart`/`windowEnd`) and publish it to `transactions.clean`. One dense cluster ->
   one `TransactionEvent`. The typed `alarms[]` array (already present and required on the
   merged `TransactionEvent` contract) is populated from the in-hand enriched `AlarmEvent`s:
   each `alarms[]` entry carries the SIX required per-alarm fields — `alarmId`, `alarmType`,
   `eventType`, `raisedAt`, `managedObjectId`, `perceivedSeverity` — each copied verbatim
   from the source `AlarmEvent`. In particular `alarmType` (the canonical Knowledge
   `alarmTypeVocabulary` join token) is a pass-through mirror of `AlarmEvent.alarmType`: the
   Noise Filter does not derive, infer, or alter it. `alarms[]` is ordered identically to
   `alarmIds[]`. This populates an existing required field of the already-merged contract; it
   introduces no new Kafka topic and no event-model change.
6. **Record run-stats** — after finalizing each trail-window execution, write one aggregate
   row to the owned PostgreSQL run-stats table capturing: `runId` (unique), `runTimestamp`,
   `trailId`, `snapshotId`, `domain` (if available), `windowStart`, `windowEnd`, the params
   used (`eps`, `minSamples`, `windowSize`, `algorithm`), and the counts `alarmsIn`,
   `clustersFormed`, `alarmsKept`, `alarmsDropped`, and `noiseRatio` (`alarmsDropped /
   alarmsIn`). This write is best-effort: if it fails, the pipeline has already emitted
   the `TransactionEvent`(s) and processing continues; the failure is logged and counted.
7. **Refresh Knowledge parameters** — on receipt of `knowledge.updated`, re-fetch DBSCAN
   params, window size, and feature config from the Knowledge Service so subsequent windows
   use updated values without requiring a service restart.
8. **Handle errors** — route poison/unparseable messages to `alarms.enriched.dlq`; log
   drops with sufficient context for ops.
9. **Serve run-stats read API** — respond to read requests for run-stats records, supporting
   optional filtering by `trailId` and/or time range (`runTimestamp`). Return aggregate
   stats rows from the owned PostgreSQL table. Validate responses against the service's
   published OpenAPI 3.1 spec.
10. **Record observed-noise / chatter signatures** — when DBSCAN labels alarms as noise in a
    finalized trail-window, upsert each dropped alarm's chatter signature
    (`(managedObjectId, alarmType)` plus `eventType` and `trailId`) into the NF-owned aggregate
    observed-chatter store: increment its `occurrenceCount`, set `firstSeen` on first sight and
    advance `lastSeen`. Store ONE row per distinct signature — no per-alarm records. The write is
    best-effort and non-blocking on the same terms as the run-stats write (task 6): a failure is
    logged + metric-counted and never blocks `TransactionEvent` emission.
11. **Serve observed-chatter read API** — respond to read requests for the recorded
    observed-chatter signatures, ranked by `occurrenceCount` (most-frequent noise first),
    returning `managedObjectId`, `alarmType`, `eventType`, `trailId`, `occurrenceCount`,
    `firstSeen`, `lastSeen` per signature. Read-only: the service writes signatures only from its
    own clustering; promotion into Enrichment's known-chatter list is operator-mediated via the
    web-ui and out of scope here. Validate responses against the published OpenAPI 3.1 spec.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs / Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail construction are underway but no alarms are processed by this service. | Idle | — |
| P2 — Pattern learning | Core worker: collapses post-dedup alarm storms from propagating faults into clean transaction groups so the Pattern Miner receives storm-reduced, incident-dense sequences; records aggregate run-stats per execution for operational visibility; records observed-noise/chatter signatures (aggregate) from the alarms DBSCAN labels as noise. | Active | Consumes: `alarms.enriched`. Produces: `transactions.clean`. Calls: Knowledge Service (DBSCAN params + feature config via its published API); Topology Service (attribute features, when enabled); Trail Builder (hop-distance feature, when enabled). Writes: run-stats table and observed-chatter table (both best-effort). |
| P3 — Real-time correlation | Not involved in the clustering pipeline. Live alarms are still **deterministically** filtered by Enrichment (dedup/self-clear/flap/chatter) on the live path; only this service's **statistical DBSCAN** stage is skipped live. DBSCAN's job is to clean Phase-2 *training* data for the Miner; real-time noise rejection is handled instead by the Correlation Engine's noise-tolerant pattern/codebook matching (tolerates missing, penalises spurious). The run-stats and observed-chatter read API remains available for web-ui queries (an operator can review P2-accumulated chatter candidates in P3). | Idle (pipeline); Passive (read API) | Serves: run-stats + observed-chatter read API (queried by web-ui). |

## Contract

- **Consumes (Kafka):** `alarms.enriched` (payload: `AlarmEvent` from `acp-event-model`)
- **Produces (Kafka):** `transactions.clean` (payload: `TransactionEvent` from `acp-event-model`)
- **APIs exposed:**
  - `/health` (liveness/readiness) and `/metrics` (Prometheus exposition format) — unchanged.
  - **[NEW] Run-stats read API** — the service's first HTTP business surface. A read-only
    API for listing and querying run-stats records (filter by `trailId`, time range).
    Published as OpenAPI 3.1 at `/openapi.json`; the generated `openapi.json` is checked
    into `services/noise-filter/` and serves as the single source of truth for this
    surface. Used for the service's own contract/unit tests and consumed by the web-ui
    (which builds its client against this published spec). Exact endpoint paths,
    query-parameter names, and response schema are a **design-stage decision** (see Open
    questions #5). Fields exposed per row: `runId`, `runTimestamp`, `trailId`, `snapshotId`,
    `domain`, `windowStart`, `windowEnd`, `eps`, `minSamples`, `windowSize`, `algorithm`,
    `alarmsIn`, `clustersFormed`, `alarmsKept`, `alarmsDropped`, `noiseRatio`.
  - **[MVP] Observed-chatter read endpoint** — part of the same read-only run-stats API
    surface (same `/openapi.json`). Returns the recorded observed-noise/chatter signatures
    ranked by occurrence count. Read-only; NF writes signatures only from its own clustering.
    Fields exposed per signature: `managedObjectId` (optional), `alarmType`, `eventType`,
    `trailId` (optional), `occurrenceCount`, `firstSeen`, `lastSeen`. These are the candidate
    chatter entries the web-ui presents for operator-mediated promotion into Enrichment's
    known-chatter list. Exact endpoint path, query params, and response schema are a
    **design-stage decision** (see Open questions #8).
  - A change to this API surface is a contract change requiring an `architecture.md`/spec
    update and human approval, per the architecture convention.
- **APIs / data consumed from other services:**
  - **Knowledge Service** — fetch DBSCAN params (`eps`, `minSamples`), window size, and
    feature config (which device/connection attribute keys and graph-position features to
    include in the feature vector) at startup and on `knowledge.updated`. Built and tested
    against the Knowledge Service's published OpenAPI 3.1 spec.
  - **Topology Service query API** — when one or more device/connection attribute features
    are enabled by feature config, fetch the `attributes` map of a topology node/edge by
    `managedObjectId`. Built and tested against the Topology Service's published OpenAPI
    3.1 spec. Not called when all attribute features are disabled. The exact query
    operation shape (endpoint, request/response fields) is a design-stage dependency on
    the Topology Service's published OpenAPI (see Open questions #4).
  - **Trail Builder query API** — when the topology hop-distance feature is enabled by
    feature config, call `getTrail(trailId)` to obtain the trail's member list and
    seed/root information needed to compute hop-distance at vectorization time. Built and
    tested against the Trail Builder's published OpenAPI 3.1 spec. Not called when the
    hop-distance feature is disabled. The exact fault-origin/seed resolution mechanism is
    a design-stage note (see Open questions #7).
- **Integration points (mock vs. real):**
  - **Knowledge Service** — config-switchable: mock (stub generated from the Knowledge
    Service's published OpenAPI 3.1) for unit tests; real Knowledge Service for integration.
    Resolved by `KNOWLEDGE_SERVICE_URL` and `KNOWLEDGE_CLIENT_MODE=mock|real` env vars.
  - **Topology Service query API** — config-switchable: mock (stub generated from the
    Topology Service's published OpenAPI 3.1) for unit tests; real Topology Service for
    integration. Resolved by `TOPOLOGY_SERVICE_URL` and `TOPOLOGY_CLIENT_MODE=mock|real`
    env vars. The client is only instantiated when at least one attribute feature is
    enabled in feature config; it is fully bypassed otherwise.
  - **Trail Builder query API** — config-switchable: mock (stub generated from the Trail
    Builder's published OpenAPI 3.1) for unit tests; real Trail Builder for integration.
    Resolved by `TRAIL_BUILDER_URL` and `TRAIL_BUILDER_CLIENT_MODE=mock|real` env vars.
    The client is only instantiated when the hop-distance feature is enabled in feature
    config; it is fully bypassed otherwise.
  - **[NEW] PostgreSQL run-stats store** — configured via `NOISE_FILTER_DB_URL` env var.
    In unit tests the store may be backed by an in-memory or containerized PostgreSQL
    instance (designer's choice); in integration it is the shared PostgreSQL service.
    The run-stats write path is always best-effort; the read API depends on the store
    being reachable.
- **Data owned:**
  - **[NEW] PostgreSQL run-stats schema** (NF-owned, internal, single-owner per the
    architecture convention) — one lightweight execution-stats table. Each row corresponds
    to one finalized trail-window clustering execution and stores only aggregate counts and
    params (no individual alarm records, no feature vectors, no dropped-alarm IDs). The
    ephemeral in-process window/dedupe state is separate and unchanged. This is lightweight
    operational telemetry; it stores no alarm payloads and does not constitute a historical
    alarm corpus.
  - **[MVP] PostgreSQL observed-chatter schema** (NF-owned, internal, single-owner) — one
    lightweight aggregate table holding ONE row per distinct observed-noise/chatter signature
    (`(managedObjectId, alarmType)` + `eventType` + `trailId`) with an `occurrenceCount`,
    `firstSeen`, and `lastSeen`. It stores signature keys + counts only — no individual alarm
    records, no feature vectors. This is aggregate noise telemetry, not a historical alarm
    corpus, and stays within the "live-only, no historical corpus" rule. Written only by the
    service's own clustering pipeline (best-effort); read only via the read API.

## Non-functional

- **Idempotency key:** `eventId` (envelope field) — duplicates from at-least-once Kafka
  delivery are detected and dropped within the processing window.
- **Config:** all runtime parameters from environment variables or the Knowledge Service;
  no hard-coded thresholds, feature keys, or attribute key lists. Required env vars:
  `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_GROUP_ID`, `KNOWLEDGE_SERVICE_URL`,
  `KNOWLEDGE_CLIENT_MODE` (`mock|real`), `TOPOLOGY_SERVICE_URL`,
  `TOPOLOGY_CLIENT_MODE` (`mock|real`), `TRAIL_BUILDER_URL`,
  `TRAIL_BUILDER_CLIENT_MODE` (`mock|real`), `LOG_LEVEL`,
  **`NOISE_FILTER_DB_URL`** (PostgreSQL connection URL for the run-stats store). DBSCAN
  `eps`, `minSamples`, and `windowSize` are Knowledge-Service parameters (fetched at
  startup and refreshed on `knowledge.updated`). The feature config (which
  device/connection attribute keys to include in the feature vector, whether the topology
  hop-distance feature is enabled, and their default on/off state) is also a
  Knowledge-Service parameter — no feature key is hard-coded in the service.
- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus exposition
  format), structured JSON logs (no plain-text log lines in production). Stats-write
  failures must be counted in a dedicated `/metrics` counter and logged as structured
  entries.
- **API contract:** the service publishes an OpenAPI 3.1 spec at `/openapi.json` (checked
  into `services/noise-filter/`) covering the run-stats read API. The published spec is
  the single source of truth for the HTTP business surface; the service's own
  contract/unit tests validate against it, and collaborators (web-ui) build their client
  against it. A surface change is a contract change.
- **Stats write resilience:** the run-stats persistence step is best-effort and
  non-blocking. A failure to write a stats row must not prevent the clustering pipeline
  from emitting `TransactionEvent`s to `transactions.clean`. The failure is logged
  (structured) and counted in `/metrics`; the pipeline continues with the next window.
- **Error handling:** poison / unparseable messages -> `alarms.enriched.dlq`. Unknown
  major `schemaVersion` -> `alarms.enriched.dlq` with a structured log entry. Transient
  Knowledge Service failures -> retry with backoff; the service does not start if params
  cannot be loaded. Stats-write failures -> log + metric; pipeline continues.
- **Reproducibility:** for the same input window and the same Knowledge-Service params,
  DBSCAN must produce the same cluster labeling deterministically (required for
  regression tests).
- **Retention-bias guardrail:** the service must never be tuned such that retention of
  real cascade alarms falls below 0.95 (the Simulator oracle floor). The run-stats table
  records `alarmsIn` and `alarmsKept` per execution, making retention computable and
  CI-gatable against the oracle. When the hop-distance feature is enabled, retention must
  remain >= 0.95 — enabling the feature must not push retention below this floor.

## Acceptance criteria

Each criterion maps to one pytest test.

1. **Noise drop — chatty alarm removed.** Given a trail-window containing a known tight
   cascade cluster (fiber-cut pattern: LOS -> LinkDown -> AdjDown -> LSPDown) plus one
   injected coincidental chatty alarm whose feature vector falls outside cluster density,
   the service emits exactly one `TransactionEvent` containing the cascade alarm IDs and
   does not include the chatty alarm's ID.

2. **Cluster preserved intact.** Given a trail-window containing only the tight cascade
   cluster alarms (no injected noise), the service emits one `TransactionEvent` whose
   `alarmIds[]` contains every alarm in the cascade and no alarms are dropped.

3. **DBSCAN params from Knowledge Service — changing params changes results.** Given two
   runs over the same input trail-window, one with tight params (small `eps`, high
   `minSamples`) and one with loose params (large `eps`, low `minSamples`) served by the
   mock Knowledge Service, the output differs: the tight params produce fewer or no dense
   clusters; the loose params produce at least one cluster. Demonstrates that no threshold
   is hard-coded and that the Knowledge Service is the sole source of DBSCAN configuration.

4. **TransactionEvent schema validity (incl. typed `alarms[]`).** Every `TransactionEvent`
   emitted by the service validates against the `TransactionEvent` JSON Schema from
   `libs/event-model` (all top-level required fields present: `transactionId`, `trailId`,
   `snapshotId`, `alarmIds`, `alarms`, `windowStart`, `windowEnd`; `alarmIds` and `alarms`
   are non-empty for emitted events). Additionally, **every `alarms[]` entry carries all SIX
   required per-alarm fields — `alarmId`, `alarmType`, `eventType`, `raisedAt`,
   `managedObjectId`, `perceivedSeverity` — each correctly typed, and each `alarmType` is a
   valid non-empty token mirrored verbatim from the corresponding source `AlarmEvent.alarmType`.**
   A payload that omits `alarmType` (or any other required per-alarm field) on any entry fails
   validation and is never published.

5. **Idempotency on duplicate eventId.** Given the same `AlarmEvent` delivered twice on
   `alarms.enriched` (identical `eventId`), the service processes it once and the output
   `TransactionEvent` references the alarm's ID exactly once.

6. **Poison message to DLQ.** Given a Kafka message on `alarms.enriched` that cannot be
   deserialized (malformed JSON), the service routes it to `alarms.enriched.dlq` and
   continues processing subsequent messages without crashing.

7. **Unknown schemaVersion to DLQ.** Given a Kafka message on `alarms.enriched` with an
   unknown major `schemaVersion`, the service routes it to `alarms.enriched.dlq` with a
   structured log entry and continues processing subsequent messages.

8. **Knowledge param refresh at runtime.** Given that the mock Knowledge Service returns
   updated DBSCAN params after a `knowledge.updated` event is received, subsequent
   trail-windows are processed with the new params (verified by observing the cluster
   labeling change for a fixed input window; no service restart required).

9. **Noise-filter effectiveness measurable.** Given a synthetic trail-window with a known
   count of injected noise alarms (N) and real cascade alarms (M) derived from the
   Simulator's ground-truth label, the service's output `TransactionEvent` contains at
   least ceil(M x 0.9) real alarm IDs and at most floor(N x 0.1) noise alarm IDs. This
   makes the noise-filter effectiveness metric (% injected noise removed vs. real alarms
   retained) computable against the Simulator oracle.

10. **Attribute feature config-driven — inclusion and exclusion.** Given a trail-window
    with alarms whose managed objects have `equipmentType` values in the mock Topology
    Service response, two runs are made: one with `equipmentType` enabled in the mock
    Knowledge Service feature config, one with `equipmentType` disabled. When enabled, the
    feature vector for each alarm includes a dimension for `equipmentType` (verified by
    observing that alarms with distinct `equipmentType` values can be separated into
    different clusters for a carefully constructed window). When disabled, the feature
    vector does not include an `equipmentType` dimension and the Topology Service client
    is not called. Demonstrates that no attribute key is hard-coded and the active feature
    set is solely determined by the Knowledge-Service feature config.

11. **Run-stats row correctness.** Given a trail-window execution that processes `alarmsIn`
    alarms and produces `clustersFormed` dense clusters containing a total of `alarmsKept`
    cluster-member alarms, the service writes exactly one run-stats row with: `alarmsIn`
    equal to the total alarms in the window, `alarmsDropped` equal to `alarmsIn -
    alarmsKept`, `alarmsKept` matching the count of alarms emitted across all
    `TransactionEvent`s for that window, `clustersFormed` matching the number of dense
    clusters identified, `noiseRatio` equal to `alarmsDropped / alarmsIn` (to a reasonable
    floating-point tolerance), and the `eps`, `minSamples`, `windowSize`, and `algorithm`
    values matching the params actually used for that execution.

12. **Run-stats read API returns recorded rows and validates against OpenAPI.** Given one
    or more completed trail-window executions that have written run-stats rows, a GET
    request to the run-stats list endpoint returns those rows with field values matching
    what was recorded, and each response validates against the service's published
    OpenAPI 3.1 spec (all required fields present and correctly typed).

13. **Stats-write failure does not block TransactionEvent emission.** Given a trail-window
    execution where the PostgreSQL run-stats write is configured to fail (e.g. DB
    unavailable or simulated write error), the service still emits the expected
    `TransactionEvent`(s) to `transactions.clean`, logs a structured error entry, and
    increments the stats-write-failure metric counter. The pipeline does not raise an
    unhandled exception or stall.

14. **Run-stats query by trailId returns matching subset.** Given run-stats rows recorded
    for two distinct `trailId` values, a GET request to the run-stats endpoint filtered
    by one `trailId` returns only the rows for that trail and excludes rows for the other
    `trailId`.

15. **Storm reduction — single fault collapses to ONE transaction group.** Given a
    simulated alarm storm of N alarms (N >= 10) produced by a SINGLE fault on one trail
    (post-Enrichment, post-dedup — as generated by the Simulator's fiber-cut or
    line-card-fault scenario), the service emits exactly ONE `TransactionEvent` capturing
    the cascade, and the recorded reduction ratio (`alarmsIn / clustersFormed`) meets or
    exceeds 5 (consistent with the platform's alarm-reduction >= 5x metric). The run-stats
    row for this execution records `alarmsIn` equal to N and `clustersFormed` equal to 1.

16. **Retention floor holds with topology hop-distance feature enabled.** Given a
    Simulator-labeled trail-window where the ground-truth cascade contains M valid alarm
    IDs, with the topology hop-distance feature ENABLED in the mock Knowledge Service
    feature config and the Trail Builder integration point in mock mode, the count of
    valid cascade alarm IDs present in the service's emitted `TransactionEvent`(s) is at
    least ceil(M x 0.95). Enabling the hop-distance feature must not cause the service to
    drop valid cascade/storm members below the 0.95 retention floor.

17. **Long cascade preserved whole — no fragmentation or truncation.** Given a
    trail-window containing a multi-hop cascade alarm sequence with legitimate timing gaps
    between propagation layers (e.g. fiber-cut root alarm followed by LinkDown, then
    AdjDown arriving later due to protocol convergence delay, then LSPDown), the service
    emits ONE `TransactionEvent` whose `alarmIds[]` contains ALL alarms in the cascade
    including the late-arriving far-hop alarms. The cascade is not fragmented into
    multiple transaction groups and the late-arriving alarms are not labeled as noise.

18. **Concurrent faults on the same trail produce separate transaction groups.** Given a
    trail-window containing alarms from TWO near-simultaneous faults on the SAME trail
    (each fault producing its own distinct cascade, constructed so they differ in
    propagation hop-distance from distinct origins), the service emits TWO distinct
    `TransactionEvent`s — one per fault cluster — rather than one conflated group. The
    two transaction groups do not share alarm IDs.

19. **Observed-chatter signature recorded from dropped noise.** Given a trail-window
    containing a cascade cluster plus a coincidental chatty alarm with `managedObjectId` MO,
    `alarmType` AT and `eventType` ET that DBSCAN labels as noise (dropped from
    `transactions.clean`), the service records an observed-chatter signature row for
    `(managedObjectId=MO, alarmType=AT, eventType=ET, trailId=the window trail)` with
    `occurrenceCount >= 1`, a `firstSeen` and a `lastSeen` timestamp. Alarms that remained in
    a dense cluster (kept, emitted) do NOT produce an observed-chatter signature.

20. **Observed-chatter occurrence count aggregates across runs.** Given the SAME chatter
    signature `(managedObjectId, alarmType)` is labeled as noise in N separate trail-window
    executions, the observed-chatter store holds exactly ONE row for that signature with
    `occurrenceCount == N` (aggregated, not N separate rows), with `firstSeen` set from the
    first sighting and `lastSeen` advanced to the most recent sighting.

21. **Observed-chatter read endpoint returns ranked signatures and validates against OpenAPI.**
    Given recorded observed-chatter signatures with differing occurrence counts, a GET request
    to the observed-chatter endpoint returns the signatures ranked by `occurrenceCount`
    descending (most-frequent noise first), each row carrying `managedObjectId` (or null),
    `alarmType`, `eventType`, `trailId` (or null), `occurrenceCount`, `firstSeen`, and
    `lastSeen`; the response validates against the service's published OpenAPI 3.1 spec (all
    required fields present and correctly typed).

22. **Observed-chatter endpoint is read-only.** The observed-chatter endpoint accepts only GET;
    a POST/PUT/PATCH/DELETE to it returns `405` (or `404` for an undefined route). The service
    exposes no API that creates, mutates, or promotes observed-chatter signatures — signatures
    are written exclusively by the clustering pipeline, and promotion into the live known-chatter
    list is operator-mediated (web-ui -> Enrichment), never performed by this service.

23. **Observed-chatter write failure does not block TransactionEvent emission.** Given a
    trail-window execution where the observed-chatter write is configured to fail (DB
    unavailable / simulated write error), the service still emits the expected
    `TransactionEvent`(s) to `transactions.clean`, logs a structured error, increments the
    chatter-write-failure metric counter, and raises no unhandled exception / does not stall.

## Open questions

Items 2–4 are carried forward from the prior spec revision (design-stage, not blockers).
Items 5–6 are from the run-stats capability addition.
Item 1 is resolved (see below). Item 7 is new (design-stage note for the hop-distance
feature, not a blocker). Item 8 is new (design-stage note for the observed-chatter
signature capability, not a blocker).

1. **[RESOLVED] Feature vectorization — topology hop-distance** (was tracked: #48).
   Previously deferred as a design-stage modeling decision, this feature is now promoted
   to an MVP requirement. Resolution: the topology hop-distance of each alarm's managed
   object from the trail's fault-origin/seed along dependency edges is included as ONE
   standardised, soft dimension in the DBSCAN feature vector. It is config-switchable
   on/off via the Knowledge-Service-sourced feature config (same mechanism as all other
   feature dimensions — no feature is hard-coded). It is a SOFT feature only: it
   influences cluster density, it NEVER constitutes a hard alarm-drop gate. Enabling it
   must not reduce retention below the 0.95 oracle floor (guardrail). The exact
   fault-origin/seed resolution mechanism and traversal call shape are a design-stage
   detail (see #7 below). Issue #48 is closed by this resolution; issue #7 is the
   design-stage follow-on.

2. **[DESIGN-STAGE] `snapshotId` provenance for `TransactionEvent`** (tracked: #51).
   `TransactionEvent` requires a `snapshotId`; `AlarmEvent` on `alarms.enriched` does
   not carry it. The exact mechanism for obtaining `snapshotId` is a design decision. The
   leading candidate is to derive it from the trail context via Trail Builder
   `getTrail(trailId)`, since the service already scopes processing per trail — but the
   designer chooses the implementation. No `AlarmEvent` contract change is introduced in
   this spec.

3. **[DESIGN-STAGE] `knowledge.updated` as explicit consumed topic** (tracked: #52).
   Whether noise-filter subscribes to `knowledge.updated` to trigger live param refresh,
   or polls the Knowledge API on its own schedule, is a design-stage wiring choice. DBSCAN
   params are read from the Knowledge Service API regardless. No `architecture.md`
   consumer mapping update is required at the spec stage.

4. **[DESIGN-STAGE] Topology query API shape for node/edge attributes.** The attribute
   feature integration requires the Topology Service to expose a query operation that
   returns the `attributes` map for a given `managedObjectId`. The exact endpoint path,
   request parameters, and response schema (including how it surfaces the `attributes` map
   and handles unknown `managedObjectId`) is a **design-stage API-shape dependency** on
   the Topology Service's published OpenAPI 3.1. The noise-filter designer must build the
   Topology client against that published OpenAPI; if the required operation is absent from
   the Topology Service's published spec, that is a contract gap requiring human resolution
   before the noise-filter design can proceed (a Topology Service contract change, not a
   noise-filter spec change). No new Kafka topic or `AlarmEvent`/`TransactionEvent` field
   is introduced.

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
   query performance), and the schema migration strategy are a **design-stage decision**
   for the designer to specify in `design.md`. No new Kafka topic or event-model change
   is required for any of these decisions.

7. **[DESIGN-STAGE] Topology hop-distance — fault-origin/seed resolution and traversal
   mechanism.** The spec requires the hop-distance feature to measure propagation distance
   from the trail's fault-origin/seed along dependency edges. The exact mechanism for
   identifying the seed (e.g. via the `getTrail(trailId)` response's seed/root field from
   the Trail Builder published OpenAPI, or via a separate heuristic) and for computing
   hop-distance from that seed is a **design-stage decision**. The designer must: (a)
   confirm that the Trail Builder's published OpenAPI 3.1 exposes sufficient seed/root
   information in the `getTrail` response for this computation; (b) if it does not, flag a
   Trail Builder contract gap requiring human resolution before the hop-distance feature
   can be implemented (a Trail Builder spec change, not a noise-filter spec change); (c)
   document the chosen traversal mechanism in `design.md`. No new Kafka topic or
   `AlarmEvent`/`TransactionEvent` field is introduced by this feature.

8. **[DESIGN-STAGE] Observed-chatter signature store + endpoint shape.** The spec states the
   requirement (record `(managedObjectId, alarmType)` chatter signatures from DBSCAN-labeled
   noise, aggregate an occurrence count + first/last seen, expose them read-only ranked by
   occurrence) and the fields to expose, but the exact chatter-key definition (whether
   `managedObjectId` is part of the key or nullable for source-level chatter), the table schema
   (column names/types, the upsert key, indices), the endpoint path / query params / pagination /
   ranking tie-break, and the handling of null `managedObjectId` are a **design-stage decision**
   for the designer to specify in `design.md` and publish in `openapi.json`. No new Kafka topic
   or event-model change is required, and the service introduces no write path to Enrichment —
   promotion is operator-mediated via the web-ui.
