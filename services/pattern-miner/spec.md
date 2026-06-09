# pattern-miner — Service Spec

## Purpose

pattern-miner is the ML execution service for the Pattern Learning phase (P2). It consumes
trail-scoped, DBSCAN-cleaned alarm groups from `transactions.clean`, applies a session window
(gap-based, per trail) to finalize transaction boundaries, then runs PrefixSpan (Spark MLlib via
PySpark) to discover frequent ordered alarm sequences. For each discovered sequence it computes
support, confidence, lift, and conviction, attaches mining provenance (source trail, window
reference, topology snapshot, and codebook version in scope), and emits one `PatternMinedEvent`
on `patterns.mined`. The service owns the mining model and its configuration parameters; it holds
**no** pattern state — no RCA, no codebook reconciliation, no explainability, no Pattern Store,
and no lifecycle management. Those responsibilities belong exclusively to the Pattern Manager
(§6.9).

## Scope

**In scope:**
- Consume `transactions.clean` (`TransactionEvent` envelopes) and deduplicate on `eventId`.
- Finalize transaction boundaries by applying a session window (gap-based, per trail; the
  session-gap parameter is sourced from the Knowledge Service — the Miner owns the final
  boundary decision, per §6.8).
- Run PrefixSpan (Spark MLlib) over session-windowed, trail-scoped transactions to discover
  frequent ordered alarm-type sequences.
- Compute support, confidence, lift, and conviction for each discovered sequence.
- Source all mining configuration — minimum support threshold, maximum pattern length,
  session-gap constraint, and maximum sequence count — from the Knowledge Service; no
  hard-coded thresholds.
- Attach mining provenance to each result: source `trailId`, `sourceWindowId`, `snapshotId`,
  and `codebookVersion` in scope at mining time.
- Emit one `PatternMinedEvent` on `patterns.mined` per discovered sequence.
- Route poison (unprocessable) messages to `transactions.clean.dlq`.
- Expose `/health` and `/metrics` endpoints and emit structured JSON logs.
- Run as a stateless Spark job (container-only — Spark/PySpark is not installed locally; see
  CLAUDE.md).

## Out of scope

- **No RCA.** Root-cause alarm type assignment belongs solely to the Pattern Manager (§6.9).
- **No codebook reconciliation.** Matching mined sequences against codebook scenarios is the
  Pattern Manager's responsibility.
- **No explainability (XAI).** Generating explanations for patterns is the Pattern Manager's
  responsibility.
- **No Pattern Store.** pattern-miner does not persist patterns; it emits and forgets.
- **No pattern lifecycle.** States `draft`, `approved`, `deprecated` and all lifecycle
  transitions belong to the Pattern Manager.
- **No `patternId` assignment.** Stable pattern identifiers are minted by the Pattern Manager.
- **No deterministic or statistical alarm filtering.** Deterministic deduplication and
  filtering are done by the Enrichment Service; DBSCAN noise removal is done by the Noise
  Filter. By the time transactions arrive on `transactions.clean`, filtering is complete.
- **No real-time correlation.** pattern-miner is idle in P3 — approved patterns are served by
  the Pattern Manager to the Correlation Engine.
- **No topology graph access.** The Topology Service is the sole owner of the AGE graph.

## Tasks (high-level)

1. Consume `transactions.clean` and deduplicate incoming `TransactionEvent` envelopes on their
   envelope `eventId` to satisfy the at-least-once Kafka delivery guarantee.
2. Fetch current mining parameters (minimum support, maximum pattern length, session-gap,
   maximum sequence count) from the Knowledge Service before each mining run, ensuring no
   mining configuration threshold is hard-coded.
3. Apply a session window (gap-based, per trail) to the consumed transactions to finalize
   session-level sequence boundaries that PrefixSpan will operate over.
4. Run PrefixSpan (Spark MLlib) over the session-windowed, trail-scoped alarm sequences to
   discover all frequent ordered subsequences meeting the minimum support threshold.
5. Compute support, confidence, lift, and conviction for every discovered sequence.
6. Assemble a `PatternMinedEvent` for each discovered sequence, carrying: `sequence`,
   `support`, `confidence`, `lift`, `trailId`, `timing` (inter-arrival statistics), and
   `provenance` (`sourceWindowId`, `snapshotId`, `codebookVersion`) — with no RCA or
   lifecycle fields.
7. Emit each `PatternMinedEvent` onto `patterns.mined` (one event per discovered sequence).
8. Route any unprocessable (poison) message to `transactions.clean.dlq`.

## Phase applicability

Consistent with the canonical phase map in `docs/architecture.md`.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail compilation precede the learning phase. | Idle | — |
| P2 — Pattern learning | Core worker: session-windows transactions and runs PrefixSpan to produce raw mined sequences for the Pattern Manager. | Active | In: `transactions.clean` (TransactionEvent); Knowledge params API. Out: `patterns.mined` (PatternMinedEvent). |
| P3 — Real-time correlation | Not involved; mining is an offline/learning-only activity. Approved patterns are served by the Pattern Manager. | Idle | — |

## Contract

- **Consumes (Kafka):** `transactions.clean`
- **Produces (Kafka):** `patterns.mined`
- **APIs exposed:** — (pattern-miner is a stateless Spark job; no HTTP API surface beyond
  `/health` and `/metrics`; no OpenAPI spec is published)
- **APIs/data consumed from other services:**
  - **Knowledge Service — mining params:** retrieves minimum support, maximum pattern length,
    session-gap, and maximum sequence count. Built against the Knowledge Service's published
    OpenAPI 3.1 spec.
- **Integration points (mock vs. real):**
  - **Knowledge Service mining-params endpoint** — config-switchable per environment:
    - Unit tests: mock/stub generated from the Knowledge Service's published OpenAPI spec
      (e.g. via `respx` or an equivalent OpenAPI-backed stub).
    - Integration: real Knowledge Service at the Docker Compose address, resolved from env
      config.
    - Base URL and `mock|real` toggle are provided via environment variable — no hard-coded
      URLs.
- **Data owned:** — (stateless; pattern-miner holds no datastore and persists no pattern state)

## Non-functional

- **Idempotency key:** `eventId` (envelope field); deduplicate on this key before processing
  each `TransactionEvent`.
- **Config:** All mining thresholds and tunable parameters (minimum support, maximum pattern
  length, session-gap, maximum sequence count) are sourced from the Knowledge Service at
  runtime — not from code or static config files. Integration URLs (Knowledge Service base URL)
  and the mock/real toggle are provided via environment variables. No hard-coded thresholds
  anywhere in the service.
- **Observability:** `/health` endpoint; `/metrics` endpoint (Prometheus-compatible); structured
  JSON logs for every significant event (message consumed, session window finalised, mining run
  started/completed, events emitted, errors).
- **API contract:** pattern-miner exposes no HTTP API surface and therefore publishes no OpenAPI
  spec. The Knowledge Service integration point is built against the Knowledge Service's own
  published OpenAPI spec.
- **Error handling:** Poison (unprocessable) messages are routed to `transactions.clean.dlq`.
  Transient errors (e.g. Knowledge Service unavailable) are retried with back-off before DLQ
  routing; retry policy is config-driven.
- **Spark/PySpark runtime:** Runs as a container-only stateless Spark job. Spark is not
  installed locally; all Spark execution occurs inside the service's Docker container. Python
  cohort; test framework is **pytest**.

## Acceptance criteria

1. Given a set of `TransactionEvent` messages on `transactions.clean` that contains the
   Simulator's injected fiber-cut alarm sequence (e.g. `["lossOfSignal", "linkDown",
   "bgpPeerDown"]`), the service emits at least one `PatternMinedEvent` on `patterns.mined`
   whose `sequence` field matches that ordered sequence and whose `support` value equals the
   observed frequency of the sequence in the input window (within floating-point tolerance).

2. Given a transaction set that also contains a spurious high-support but low-lift
   co-occurrence (two alarm types that appear together often but with lift close to 1.0), the
   service emits a `PatternMinedEvent` for that co-occurrence and the emitted event's `lift`
   field reflects the computed value — allowing the Pattern Manager to identify and flag it
   downstream.

3. When the minimum support threshold fetched from the Knowledge Service is raised to a value
   above the support of a previously discovered sequence, that sequence does **not** appear in
   the emitted `patterns.mined` events for the same input window; when the threshold is lowered
   back below the sequence's support, it reappears.

4. Every `PatternMinedEvent` emitted by the service validates against the frozen
   `PatternMinedEvent` Pydantic model from `libs/event-model/python`; specifically: all
   required fields (`sequence`, `support`, `confidence`, `lift`, `trailId`, `timing`,
   `provenance`) are present and well-typed, and no additional fields are present
   (`model_config extra="forbid"`).

5. No emitted `PatternMinedEvent` contains a `rootCauseAlarmType`, `patternId`, or `lifecycle`
   field. Constructing a `PatternMinedEvent` Pydantic model instance with any of those fields
   raises a `ValidationError` (enforced by the frozen schema's `extra="forbid"`).

6. The `provenance` object on every emitted `PatternMinedEvent` carries all three required
   sub-fields — `sourceWindowId`, `snapshotId`, and `codebookVersion` — each non-empty; an
   event missing any provenance sub-field fails schema validation against the frozen
   `Provenance` model.

7. When the service receives a `TransactionEvent` whose envelope `eventId` has already been
   processed in the current session, no `PatternMinedEvent` is emitted for that duplicate; the
   event is silently acknowledged and dropped.

8. When the service receives a message it cannot deserialise or that fails `TransactionEvent`
   schema validation, the message is routed to `transactions.clean.dlq` and no
   `PatternMinedEvent` is emitted for it.

9. The service reads minimum support, maximum pattern length, session-gap, and maximum sequence
   count exclusively from the Knowledge Service integration point; no mining threshold value is
   present as a literal in the service's source code or default configuration.

## Open questions

1. **`conviction` metric in the frozen schema.** The Solution Design §6.8 lists "conviction" as
   a computed metric alongside support/confidence/lift, but the frozen `PatternMinedEvent`
   schema (`libs/event-model/schema/payloads/PatternMinedEvent.schema.json`) does not include a
   `conviction` field. Should `conviction` be added to the `PatternMinedEvent` payload (a
   contract change requiring `architecture.md` update and human approval), surfaced only in the
   `timing` free-form object, or omitted from the emitted event entirely?
   _Blocks: Acceptance criterion 4 (schema completeness) and Task 5 (compute metrics)._

2. **Knowledge Service mining-params endpoint path and response shape.** The Knowledge Service
   OpenAPI spec is not yet available in this repo. The assumed endpoint and its response fields
   (minSupport, maxPatternLength, sessionGapSeconds, maxSequenceCount) need to be confirmed
   against the Knowledge Service's published OpenAPI 3.1 spec before the integration point can
   be implemented or mocked.
   _Blocks: Task 2, Non-functional Config section, and Acceptance criterion 3._

3. **`codebookVersion` provenance source.** The `PatternMinedEvent.provenance.codebookVersion`
   field must be populated at mining time, but `transactions.clean` (`TransactionEvent`) does
   not carry a `codebookVersion` field (only `snapshotId`). Where should the Miner obtain the
   current codebook version — from the Knowledge Service params response, from a separate
   Knowledge endpoint, or from the most-recent `codebook.generated` event the Miner has
   observed? This is a behavioural contract decision.
   _Blocks: Task 6 and Acceptance criterion 6._

4. **Session-window boundary ownership vs. `TransactionEvent` windows.** The Noise Filter
   already sets `windowStart`/`windowEnd` on each `TransactionEvent`, and §6.8 states the
   Miner owns the final session-window boundary. Clarification is needed on what "finalize"
   means in practice: does the Miner re-window across multiple consecutive `TransactionEvent`
   records for the same `trailId` (aggregating them into a session), or does it treat each
   `TransactionEvent` as an already-finalized unit? The answer determines whether
   `sourceWindowId` maps 1:1 to a single `transactionId` or to a composite session.
   _Blocks: Task 3 and Acceptance criterion 6._
