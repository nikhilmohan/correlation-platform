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

## Scope

**In scope:**
- Consume `alarms.enriched` (history path only; produced by the Enrichment Service).
- Group consumed alarms per `trailId` within a coarse configurable time window.
- Feature-vectorize each alarm within a trail-window using the alarm's timestamp, position in
  the dependency graph (via `managedObjectId` in the `<objectType>:<id>` scheme), alarm type
  (`eventType`), and severity (`perceivedSeverity`).
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
- **Live-path processing** — the service does not consume `alarms.enriched.live`; live alarms
  flow directly from Enrichment to the Correlation Engine without DBSCAN cleaning (that is
  why the service is Idle in P3).
- **Pattern state and lifecycle** — owned by Pattern Manager (§6.9).
- **Topology graph access** — graph topology context for feature vectorization is derived from
  the `managedObjectId` type prefix alone; if richer graph traversal is needed that is an open
  question (see Open questions).
- **Persistent alarm storage** — the service owns no alarm store; it is stateless over the
  window lifecycle.
- **Codebook reconciliation or RCA** — owned by Pattern Manager.

## Tasks (high-level)

1. **Ingest `alarms.enriched`** — consume the history-path topic, deserialize using the
   `acp-event-model` Python/Pydantic binding, deduplicate on `eventId`, and reject unknown
   major `schemaVersion`.
2. **Partition into trail-windows** — group incoming `AlarmEvent` records by each `trailId`
   they carry and bucket them into coarse time windows whose width is the Knowledge-Service
   `windowSize` parameter.
3. **Feature-vectorize alarms** — for each alarm within a trail-window, produce a feature
   vector from: arrival timestamp (relative to window start), object-type layer derived from
   the `managedObjectId` type prefix, `eventType`, and `perceivedSeverity`.
4. **Run DBSCAN per trail-window** — apply DBSCAN (or HDBSCAN) with Knowledge-Service params
   `eps` and `minSamples`; label each alarm as cluster-member or noise.
5. **Emit cleaned groups** — for each dense cluster in a trail-window, construct a
   `TransactionEvent` (with a fresh `transactionId`, the `trailId`, the `snapshotId` in
   scope, the `alarmIds[]` of cluster members, and `windowStart`/`windowEnd`) and publish it
   to `transactions.clean`.
6. **Refresh Knowledge parameters** — on receipt of `knowledge.updated`, re-fetch DBSCAN
   params and window size from the Knowledge Service so subsequent windows use updated values
   without requiring a service restart.
7. **Handle errors** — route poison/unparseable messages to `alarms.enriched.dlq`; log drops
   with sufficient context for ops.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs / Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail construction are underway but no alarms are processed by this service. | Idle | — |
| P2 — Pattern learning | Core worker: statistically cleans the enriched historical alarm stream so the Pattern Miner receives only incident-dense groups. | Active | Consumes: `alarms.enriched`. Produces: `transactions.clean`. Calls: Knowledge Service (DBSCAN params via its published API). |
| P3 — Real-time correlation | Not involved. Live alarms flow from Enrichment directly to the Correlation Engine via `alarms.enriched.live`; DBSCAN cleaning is not applied on the live path. The Correlation Engine matches against pre-approved patterns that were already derived from DBSCAN-cleaned history, so no live-path noise filtering by DBSCAN is needed or defined. | Idle | — |

## Contract

- **Consumes (Kafka):** `alarms.enriched` (payload: `AlarmEvent` from `acp-event-model`)
- **Produces (Kafka):** `transactions.clean` (payload: `TransactionEvent` from `acp-event-model`)
- **APIs exposed:** none beyond `/health` and `/metrics`. The service exposes no REST business
  API of its own (it is a pure Kafka consumer/producer pipeline). No OpenAPI 3.1 document is
  published for a business surface. Adding an operational query endpoint in future is a contract
  change.
- **APIs / data consumed from other services:**
  - **Knowledge Service** — fetch DBSCAN params (`eps`, `minSamples`) and window size at
    startup and on `knowledge.updated`. Built and tested against the Knowledge Service's
    published OpenAPI 3.1 spec.
- **Integration points (mock vs. real):**
  - **Knowledge Service** — config-switchable: mock (stub generated from the Knowledge
    Service's published OpenAPI 3.1) for unit tests; real Knowledge Service for integration.
    Resolved by `KNOWLEDGE_SERVICE_URL` and `KNOWLEDGE_CLIENT_MODE=mock|real` env vars.
- **Data owned:** none — the service holds no persistent data store. Window state is
  ephemeral (in-process); alarm records are not persisted beyond the processing window.

## Non-functional

- **Idempotency key:** `eventId` (envelope field) — duplicates from at-least-once Kafka
  delivery are detected and dropped within the processing window.
- **Config:** all runtime parameters from environment variables or the Knowledge Service; no
  hard-coded thresholds. Required env vars: `KAFKA_BOOTSTRAP_SERVERS`,
  `KAFKA_CONSUMER_GROUP_ID`, `KNOWLEDGE_SERVICE_URL`, `KNOWLEDGE_CLIENT_MODE` (`mock|real`),
  `LOG_LEVEL`. DBSCAN `eps`, `minSamples`, and `windowSize` are Knowledge-Service parameters
  (fetched at startup and refreshed on `knowledge.updated`).
- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus exposition
  format), structured JSON logs (no plain-text log lines in production).
- **API contract:** the service consumes the `acp-event-model` Python/Pydantic binding as its
  sole wire contract. No HTTP business surface is published; if one is added it must follow
  the OpenAPI 3.1 + checked-in spec convention from `architecture.md`.
- **Error handling:** poison / unparseable messages → `alarms.enriched.dlq`. Unknown major
  `schemaVersion` → `alarms.enriched.dlq` with a structured log entry. Transient Knowledge
  Service failures → retry with backoff; the service does not start if params cannot be loaded.
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

## Open questions

1. **Feature vectorization — richer graph position.** Task 3 derives object-type layer from
   the `managedObjectId` type prefix only (e.g. `FiberSpan`, `IPLink`). If the designer
   determines that a richer graph-position feature (e.g. propagation depth, hop count from
   fault origin) is needed, that would require a synchronous call to the Topology or Trail
   Builder API at vectorization time — a new contract dependency not currently listed here. A
   human must decide before design begins. **Blocked on human decision.**

2. **`snapshotId` source for `TransactionEvent`.** The `TransactionEvent` schema requires a
   `snapshotId`. The `AlarmEvent` payload carried on `alarms.enriched` does not include a
   `snapshotId` field (per `AlarmEvent.schema.json`). The mechanism by which the Noise Filter
   obtains the `snapshotId` in scope — whether it is stamped on the Kafka envelope by
   Enrichment, passed in a header, fetched from a side channel, or derived from context — is
   not specified in `architecture.md` or the event-model. A human must confirm the resolution
   before design proceeds. **Blocked on human decision.**

3. **`knowledge.updated` as an explicit consumed topic.** Refreshing DBSCAN params on
   `knowledge.updated` means the service consumes two topics: `alarms.enriched` and
   `knowledge.updated`. The Kafka topic table in `architecture.md` lists `knowledge.updated`
   consumers as "dependents" without naming noise-filter explicitly. This should be confirmed
   as a contract entry so the topic-consumer mapping is complete and accurate.
   **Needs `architecture.md` update if confirmed.**
