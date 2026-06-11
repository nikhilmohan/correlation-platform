# pattern-miner — Service Spec

## Purpose

pattern-miner is the ML execution service for the Pattern Learning phase (P2). It consumes
trail-scoped, DBSCAN-cleaned alarm groups from `transactions.clean`, applies a dynamic,
activity/idle-driven session window (per trail) to finalize transaction boundaries, then runs
PrefixSpan (Spark MLlib via PySpark) to discover frequent ordered alarm sequences. The session
boundary is adaptive: it closes a burst of activity when the trail falls idle, and the idle gap
that triggers closure adapts to the actual tempo of each alarm burst rather than applying a
single fixed gap uniformly to all trails. For each discovered sequence the service computes
support, confidence, and lift — the MVP metrics carried by the frozen `PatternMinedEvent` schema;
conviction is out of MVP scope and would require a future contract change if ever needed. It
attaches mining provenance (source trail, window reference, topology snapshot, and codebook
version in scope — `codebookVersion` is sourced from the Knowledge Service mining-params
response), and emits one `PatternMinedEvent` on `patterns.mined`. The service owns the mining
model and its configuration parameters; it holds **no** pattern state — no RCA, no codebook
reconciliation, no explainability, no Pattern Store, and no lifecycle management. Those
responsibilities belong exclusively to the Pattern Manager (§6.9).

## Scope

**In scope:**
- Consume `transactions.clean` (`TransactionEvent` envelopes) and deduplicate on `eventId`.
- Finalize transaction boundaries by applying a dynamic, activity/idle-driven session window per
  trail: a session is a contiguous burst of activity on a trail, closed when the trail falls idle
  (an inter-arrival gap indicates the burst has ended). The idle gap that closes a burst adapts
  to the actual tempo of each alarm burst — different patterns/incidents have different tempos,
  and the windowing must adapt accordingly. The Miner owns the final session-boundary decision
  (per §6.8); all windowing parameters governing the adaptation are sourced from the Knowledge
  Service (no hard-coded threshold).
- Run PrefixSpan (Spark MLlib) over session-windowed, trail-scoped transactions to discover
  frequent ordered alarm-type sequences. PrefixSpan operates purely as sequence mining over the
  resulting sessions — no topology graph access is involved.
- Compute support, confidence, and lift for each discovered sequence (MVP metrics; conviction
  is not in scope for MVP and is not carried by the frozen `PatternMinedEvent` schema).
- Source all mining configuration — minimum support threshold, maximum pattern length,
  windowing adaptation parameters (including a base/fallback gap), and maximum sequence count —
  from the Knowledge Service; no hard-coded thresholds.
- Attach mining provenance to each result: source `trailId`, `sourceWindowId`, `snapshotId`,
  and `codebookVersion` (sourced from the Knowledge Service mining-params response) in scope
  at mining time.
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
  PrefixSpan is pure sequence mining over session-windowed alarm events; topology structural
  validation of mined patterns is a separate Pattern Manager concern.
- **No `conviction` metric for MVP.** Conviction is not carried by the frozen
  `PatternMinedEvent` schema; adding it would be a future contract change.

## Tasks (high-level)

1. Consume `transactions.clean` and deduplicate incoming `TransactionEvent` envelopes on their
   envelope `eventId` to satisfy the at-least-once Kafka delivery guarantee.
2. Fetch current mining parameters (minimum support, maximum pattern length, windowing
   adaptation parameters including the base/fallback gap, maximum sequence count, and
   `codebookVersion` in scope) from the Knowledge Service before each mining run, ensuring no
   mining configuration threshold is hard-coded.
3. Apply a dynamic, activity/idle-driven session window per trail to the consumed transactions:
   pool per-trail alarm events, then split them into sessions at points where the trail falls
   idle (an inter-arrival gap indicates the burst has ended). The idle gap that closes a burst
   is **adaptive** — it responds to the actual tempo of each alarm burst rather than applying a
   single fixed global gap uniformly. A fast cascade (e.g. sub-second fiber-cut propagation)
   and a slow-developing condition (e.g. congestion building over minutes) must receive
   different session boundaries appropriate to their own tempo. All parameters governing the
   adaptive gap are sourced from the Knowledge Service; a base/fallback gap (also
   Knowledge-sourced) applies when no tempo-specific profile or derivation is available.
4. Run PrefixSpan (Spark MLlib) over the session-windowed, trail-scoped alarm sequences to
   discover all frequent ordered subsequences meeting the minimum support threshold. PrefixSpan
   operates as pure sequence mining — no topology graph is consulted.
5. Compute support, confidence, and lift for every discovered sequence (the MVP metrics;
   conviction is out of MVP scope).
6. Assemble a `PatternMinedEvent` for each discovered sequence, carrying: `sequence`,
   `support`, `confidence`, `lift`, `trailId`, `timing` (inter-arrival statistics), and
   `provenance` (`sourceWindowId`, `snapshotId`, `codebookVersion` — the last sourced from
   the Knowledge Service mining-params response) — with no RCA or lifecycle fields.
7. Emit each `PatternMinedEvent` onto `patterns.mined` (one event per discovered sequence).
8. Route any unprocessable (poison) message to `transactions.clean.dlq`.

## Phase applicability

Consistent with the canonical phase map in `docs/architecture.md`.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail compilation precede the learning phase. | Idle | — |
| P2 — Pattern learning | Core worker: dynamically session-windows transactions by burst tempo and runs PrefixSpan to produce raw mined sequences for the Pattern Manager. | Active | In: `transactions.clean` (TransactionEvent); Knowledge params API. Out: `patterns.mined` (PatternMinedEvent). |
| P3 — Real-time correlation | Not involved; mining is an offline/learning-only activity. Approved patterns are served by the Pattern Manager. | Idle | — |

## Contract

- **Consumes (Kafka):** `transactions.clean`
- **Produces (Kafka):** `patterns.mined`
- **APIs exposed:** — (pattern-miner is a stateless Spark job; no HTTP API surface beyond
  `/health` and `/metrics`; no OpenAPI spec is published)
- **APIs/data consumed from other services:**
  - **Knowledge Service — mining params:** retrieves minimum support, maximum pattern length,
    windowing adaptation parameters (including the base/fallback gap), maximum sequence count,
    and `codebookVersion` in scope. Built against the Knowledge Service's published OpenAPI 3.1
    spec.
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
  length, windowing adaptation parameters including the base/fallback gap, maximum sequence
  count) are sourced from the Knowledge Service at runtime — not from code or static config
  files. No single fixed session-gap literal exists anywhere in the service; all windowing
  parameters are adaptive and Knowledge-sourced. Integration URLs (Knowledge Service base URL)
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
   sub-fields — `sourceWindowId`, `snapshotId`, and `codebookVersion` — each non-empty;
   `codebookVersion` is the value returned by the Knowledge Service mining-params response for
   that run; an event missing any provenance sub-field fails schema validation against the
   frozen `Provenance` model.

7. When the service receives a `TransactionEvent` whose envelope `eventId` has already been
   processed in the current session, no `PatternMinedEvent` is emitted for that duplicate; the
   event is silently acknowledged and dropped.

8. When the service receives a message it cannot deserialise or that fails `TransactionEvent`
   schema validation, the message is routed to `transactions.clean.dlq` and no
   `PatternMinedEvent` is emitted for it.

9. The service reads minimum support, maximum pattern length, windowing adaptation parameters
   (including the base/fallback gap), and maximum sequence count exclusively from the Knowledge
   Service integration point; no windowing gap value or mining threshold is present as a literal
   in the service's source code or default configuration.

10. Given two trails whose alarm bursts have markedly different inter-arrival tempos — one trail
    exhibiting a fast burst (alarms arriving sub-second) and one trail exhibiting a slow burst
    (alarms arriving minutes apart) — the service finalizes different session boundaries
    appropriate to each: the fast burst is kept as a single session (not over-split by a gap
    calibrated for slow conditions) and the slow burst is kept as a single session (not truncated
    by a gap calibrated for fast conditions). This demonstrates that the windowing adapts to the
    burst's own tempo rather than applying one fixed gap uniformly.

11. Given a single trail with two distinct activity bursts separated by a clear idle period
    longer than any intra-burst inter-arrival gap, the service splits the trail's alarms into
    exactly two sessions — one per burst — so that the idle gap closes the first burst and begins
    the second; alarms within each burst remain in the same session and are not further split.

12. When the Knowledge Service windowing configuration is changed (e.g. the base/fallback gap or
    any adaptation parameter is updated) and a fixed set of input alarm events is reprocessed,
    the resulting session boundaries differ from those produced under the previous configuration;
    confirming that the windowing adaptation is governed solely by Knowledge-sourced parameters
    and no windowing threshold is hard-coded in the service.

## Open questions

_The following items were open questions blocking the spec. They are resolved or deferred as
noted below; only design-stage items remain._

1. **[DESIGN-STAGE] Knowledge Service mining-params endpoint path and response shape (#45).**
   The exact endpoint path and response schema (field names, types, versioning) are resolved
   when the Knowledge Service publishes its OpenAPI 3.1 spec. The pattern-miner designer builds
   the client and its unit-test mock against that published OpenAPI — not against assumptions
   made here. This is not a spec blocker.

2. **[DESIGN-STAGE] Session-window finalize semantics and adaptive-gap mechanism (#50).**
   The Miner pools per-trail alarm events and re-windows them into sessions by splitting on idle
   gaps (OQ#50 resolution (b) from the design, now extended). The gap that closes a burst is
   required to be **dynamic and adaptive** rather than a single fixed global value. The precise
   mechanism for adapting the gap per burst tempo is a design-stage decision; the designer must
   choose one of the following (or a justified alternative) and document it in `design.md`:
   - **Per-trail-class / tempo-class gap profiles:** the Knowledge Service supplies a set of
     named gap profiles keyed by trail class or tempo class (e.g. `fast`, `slow`, `default`);
     the Miner selects the profile matching each trail/burst's observed or declared class.
   - **Data-driven gap derivation:** the Miner derives the closing gap from the burst's own
     inter-arrival distribution (e.g. a multiple of the median or 95th-percentile inter-arrival
     time within the burst), with the multiplier/percentile itself sourced from Knowledge.
   - **Hybrid:** an initial Knowledge-supplied tempo-class profile is refined by the burst's
     observed inter-arrival statistics.
   The designer must also specify: (a) how `sourceWindowId` is populated for adaptive sessions
   (composite reference vs. other), and (b) the behaviour when no tempo-specific profile or
   derivation applies — a Knowledge-supplied base/fallback gap must be used (no hard-coded
   default). This is not a spec blocker.

_Closed (human-decided):_

- **#44 — conviction metric:** Omitted for MVP. The frozen `PatternMinedEvent` carries
  support/confidence/lift, which suffice; conviction is not in the schema and adding it would
  be a future contract change. No action required at spec or design stage.
- **#47 — codebookVersion provenance source:** `provenance.codebookVersion` is sourced from
  the Knowledge Service mining-params response (the codebook version in scope for the run is
  included in the same call that returns mining thresholds). No new field, no separate
  endpoint, no Kafka consumer dependency.
