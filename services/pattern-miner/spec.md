# pattern-miner — Service Spec

> **RESPEC NOTE (corrective, live P2 integration finding).** This spec supersedes the previous
> version. The previous implementation ran PrefixSpan globally over all session-windowed alarm
> sequences and emitted ~2,592 "patterns" from ~1,500 input alarms — a count larger than the
> alarm corpus itself, because global PrefixSpan yields every frequent sub-fragment (including
> length-1 and repeated-token junk). This is not a set of fault cascades; it is a frequency
> lattice. The stakeholder requirement is a **small, accurate pattern set** (~8-10 distinct
> patterns, each root-cause-grounded, 50-60% alarm coverage) — i.e., each pattern = a recurring
> fault cascade, not a frequent subsequence template. This respec corrects the mining definition.

## Purpose

pattern-miner is the ML execution service for the Pattern Learning phase (P2). It discovers
**recurring fault cascades** — root-cause-grounded, recurring sequences of alarm types that
represent known fault propagation paths in the domain — from the stream of trail-scoped,
DBSCAN-cleaned alarm groups produced by the Noise Filter. It achieves this through a
**three-stage approach**: (1) **time + space correlation** to group alarms into candidate
cascades using session windowing and trail scoping; (2) **domain-knowledge anchoring** to assign
each candidate cascade to a pattern identity by matching it against the domain's authored
fault-origin scenarios (from the Codebook), preventing over-splitting and over-merging, and
flagging cascades with no confident match as "unexplained"; and (3) **ML pattern definition**
(PrefixSpan, Spark MLlib) run within each anchored group to learn that group's canonical ordered
signature, support, confidence, and lift. PrefixSpan remains central to discovery — it runs
bounded per anchored group (not globally), which eliminates the current global-mining explosion
and defines each pattern's shape precisely. The service owns the mining execution and emits
`PatternMinedEvent`s on `patterns.mined`; it holds **no** pattern state, no RCA, no lifecycle,
and no Pattern Store — those belong exclusively to the Pattern Manager.

The discovery logic is **domain-agnostic**: all fault-origin taxonomies, alarm-type vocabularies,
matching thresholds, session-windowing parameters, and grouping keys are sourced per-domain from
the Knowledge Service and the Codebook. No domain-specific alarm types, cascade shapes, or
thresholds are hardcoded. Onboarding a new domain requires authoring its Knowledge vocab/params
and Codebook scenarios — no code change to pattern-miner.

## Scope

**In scope:**
- Consume `transactions.clean` (`TransactionEvent` envelopes) and deduplicate on `eventId`.
- **Stage 1 — Time + space correlation.** Apply a dynamic, activity/idle-driven session window
  per trail (spatial/topology scope already encoded in `TransactionEvent.trailId`) to the
  consumed transactions, grouping alarms into candidate cascades. Session boundaries adapt to
  each burst's inter-arrival tempo (fast cascade vs. slow-developing condition get different
  boundaries). All windowing parameters are sourced from the Knowledge Service; no threshold
  is hardcoded.
- **Stage 2 — Domain-knowledge anchoring.** Match each candidate cascade against the domain's
  authored fault-origin scenarios (Codebook scenarios). Each cascade is assigned to the
  fault-origin scenario it best matches (the anchor), provided the match confidence meets the
  Knowledge-sourced threshold. Cascades with no confident match (below the domain's matching
  confidence threshold, sourced from Knowledge) are flagged as **unexplained** — a first-class,
  correct outcome, not an error. This anchoring ensures the output pattern set is accurate: one
  anchor = one pattern identity, so over-splitting (one cascade split into multiple patterns) and
  over-merging (unrelated cascades combined into one) are minimized. All matching thresholds and
  grouping keys are domain-sourced, never hardcoded.
- **Stage 3 — ML pattern definition.** Run PrefixSpan (Spark MLlib) **within each anchored
  group** (bounded scope per fault-origin) to learn that group's canonical ordered alarm-type
  token signature, support, confidence, and lift. Emit one `PatternMinedEvent` per distinct
  anchored fault-origin pattern. Emit a separate `PatternMinedEvent` for the unexplained cascade
  group (if non-empty), with the anchor identity field indicating "unexplained".
- Source all configuration — minimum support threshold, maximum pattern length, session-windowing
  adaptation parameters (including base/fallback gap), maximum sequence count, domain-anchoring
  matching confidence threshold, and grouping keys — from the Knowledge Service per domain. No
  hardcoded thresholds anywhere.
- Attach mining provenance to each result: source `trailId`(s), `sourceWindowId`, `snapshotId`,
  `codebookVersion`, and the fault-origin anchor identity (`anchorScenarioId` or equivalent, or
  the "unexplained" sentinel) in scope at mining time. (The anchor field requires a contract
  change — see Contract section and OQ-2.)
- Route poison (unprocessable) messages to `transactions.clean.dlq`.
- Expose `/health` and `/metrics` endpoints and emit structured JSON logs.
- Run as a stateless Spark job (container-only — Spark/PySpark is not installed locally; see
  CLAUDE.md).

## Out of scope

- **No RCA.** Root-cause alarm-type assignment belongs solely to the Pattern Manager.
- **No codebook reconciliation.** Detailed matching of mined sequences against all codebook
  signatures for reconciliation is the Pattern Manager's responsibility.
- **No explainability (XAI).** Generating explanations for patterns is the Pattern Manager's
  responsibility.
- **No Pattern Store.** pattern-miner does not persist patterns; it emits and forgets.
- **No pattern lifecycle.** States `draft`, `approved`, `deprecated` and all lifecycle
  transitions belong to the Pattern Manager.
- **No `patternId` assignment.** Stable pattern identifiers are minted by the Pattern Manager.
- **No deterministic or statistical alarm filtering.** Deterministic deduplication and filtering
  are done by the Enrichment Service; DBSCAN noise removal is done by the Noise Filter. By the
  time transactions arrive on `transactions.clean`, filtering is complete.
- **No real-time correlation.** pattern-miner is idle in P3 — approved patterns are served by
  the Pattern Manager to the Correlation Engine.
- **No Topology graph access.** The Topology Service is the sole owner of the NebulaGraph graph.
  Trail scoping (the spatial/topology grounding) is already encoded in `TransactionEvent.trailId`;
  the Miner uses it as a grouping key without querying the graph directly.
- **No global PrefixSpan.** PrefixSpan MUST NOT run over the full session corpus without prior
  domain-knowledge anchoring. Running it globally re-introduces the over-fragmentation defect
  this respec corrects.
- **No `conviction` metric for MVP.** Conviction is not carried by the frozen
  `PatternMinedEvent` schema; adding it would be a future contract change.

## Tasks (high-level)

1. Consume `transactions.clean` and deduplicate incoming `TransactionEvent` envelopes on their
   envelope `eventId` to satisfy the at-least-once Kafka delivery guarantee.

2. Fetch current mining and anchoring parameters from the Knowledge Service before each mining
   run: minimum support, maximum pattern length, session-windowing adaptation parameters
   (including the base/fallback gap), maximum sequence count, domain-anchoring matching
   confidence threshold, grouping keys, and the `codebookVersion` in scope. No mining or
   anchoring configuration threshold is hardcoded.

3. **Stage 1 — Time + space correlation.** Apply a dynamic, activity/idle-driven session window
   per trail to the consumed transactions: pool per-trail alarm events ordered by `raisedAt`,
   then split them into candidate cascades at points where the trail falls idle (an inter-arrival
   gap indicates the burst has ended). The idle gap that closes a burst is adaptive — it responds
   to the actual tempo of each alarm burst rather than applying a single fixed global gap. All
   windowing parameters are Knowledge-sourced; a base/fallback gap (also Knowledge-sourced) applies
   when no tempo-specific profile or derivation is available.

4. **Stage 2 — Domain-knowledge anchoring.** For each candidate cascade (the output of Stage 1),
   match the cascade's ordered alarm-type token sequence against the domain's fault-origin
   scenarios sourced from the Codebook (active `codebookVersion` + `domain`). Assign the cascade
   to the fault-origin scenario it most closely matches (the anchor), provided the match
   confidence meets the Knowledge-sourced threshold. Cascades that do not reach the confidence
   threshold are assigned to an "unexplained" group. Group all candidate cascades by their
   assigned anchor (fault-origin scenario or "unexplained").

5. **Stage 3 — ML pattern definition.** Run PrefixSpan (Spark MLlib) **within each anchored
   group** (bounded to that fault-origin's cascades, not globally) to discover the canonical
   ordered alarm-type-token sequence, support, confidence, and lift for that fault-origin pattern.
   Run PrefixSpan similarly within the unexplained group if it is non-empty.

6. Assemble one `PatternMinedEvent` per anchored group, carrying: the discovered `sequence`
   (alarmType tokens), `support`, `confidence`, `lift`, `trailId`(s) in scope, `timing`
   (inter-arrival statistics in milliseconds: `timeframeMs`, `medianInterArrivalMs`,
   `maxInterArrivalMs`, `stddevInterArrivalMs`), and `provenance` (`sourceWindowId`,
   `snapshotId`, `codebookVersion`, and the fault-origin anchor identity). The "unexplained"
   group produces a `PatternMinedEvent` with the "unexplained" sentinel in the anchor-identity
   field; it is a first-class correct outcome.

7. Emit each `PatternMinedEvent` onto `patterns.mined` (one event per anchored fault-origin
   group, plus one for the unexplained group if non-empty).

8. Route any unprocessable (poison) message to `transactions.clean.dlq`.

## Phase applicability

Consistent with the canonical phase map in `docs/architecture.md`.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail compilation precede the learning phase. | Idle | — |
| P2 — Pattern learning | Core worker: session-windows candidate cascades (Stage 1), anchors each to a domain fault-origin scenario via the Codebook (Stage 2), then runs bounded PrefixSpan per anchored group (Stage 3) to produce a small, accurate set of root-cause-grounded mined patterns. | Active | In: `transactions.clean` (TransactionEvent); Knowledge mining+anchoring params API; Codebook fault-origin scenarios API. Out: `patterns.mined` (PatternMinedEvent). |
| P3 — Real-time correlation | Not involved; mining is an offline/learning-only activity. Approved patterns are served by the Pattern Manager. | Idle | — |

## Contract

- **Consumes (Kafka):** `transactions.clean` (`TransactionEvent`). Its `alarms[]` array carries
  six required fields per entry: `alarmId`, `alarmType`, `eventType`, `raisedAt`,
  `managedObjectId`, `perceivedSeverity`. The Miner builds each mined `sequence` item from
  `alarmType` (the canonical join token) and its timing/windowing from `raisedAt`.
- **Produces (Kafka):** `patterns.mined`
- **APIs exposed:** None (pattern-miner is a stateless Spark job; no HTTP API surface beyond
  `/health` and `/metrics`; no OpenAPI spec is published).
- **APIs/data consumed from other services:**
  - **Knowledge Service — mining + anchoring params:** `GET /domains/{domain}/model-params/{recordId}`
    (versioned-record envelope; `paramSet = "pattern-miner"`). Returns minimum support, maximum
    pattern length, session-windowing adaptation parameters (including the base/fallback gap),
    maximum sequence count, domain-anchoring matching confidence threshold, and `codebookVersion`
    in scope. Built against the Knowledge Service's published OpenAPI 3.1 spec.
  - **Codebook — fault-origin scenarios (Stage 2):** Stage 2 requires reading the domain's
    authored fault-origin scenarios. The existing Codebook API surface referenced in
    `docs/architecture.md` is `GET /codebooks/active` and `GET /codebooks/{id}/scenarios`.
    Whether pattern-miner gains this Codebook client or Stage 2 moves to pattern-manager is the
    subject of **OQ-1** (boundary decision — unresolved, requires human decision before design).
    The exact response shape and `codebookVersion`-to-`{id}` mapping require confirmation
    (OQ-3).
- **Integration points (mock vs. real):**
  - **Knowledge Service mining+anchoring params endpoint** (`GET /domains/{domain}/model-params/{recordId}`)
    — config-switchable per environment: unit tests use a mock/stub generated from the Knowledge
    Service's published OpenAPI spec; integration uses the real Knowledge Service at the Docker
    Compose address from env config.
  - **Codebook fault-origin scenarios endpoint** (exact path TBD pending OQ-1 and OQ-3 resolution)
    — config-switchable per environment: unit tests use a mock/stub generated from the Codebook
    Service's published OpenAPI spec; integration uses the real service. Base URLs and
    `mock|real` toggles provided via environment variables; no hardcoded URLs.
- **Data owned:** None (stateless; pattern-miner holds no datastore and persists no pattern
  state).

> **CONTRACT CHANGE FLAG — `anchorScenarioId` on `PatternMinedEvent` (requires human approval).**
> Stage 2 anchoring requires each emitted `PatternMinedEvent` to carry the fault-origin scenario
> identity it was anchored to (or the "unexplained" sentinel). The current frozen
> `PatternMinedEvent` does not carry this field. Adding it is a **contract change** requiring a
> `libs/event-model` update + a `docs/architecture.md` update, both subject to human approval
> per the golden rules, as their own PR into `main` before design/build proceeds. Stages 1 and 3
> (session windowing and bounded PrefixSpan) can be specced and designed against the current
> frozen contract. Stage 2 (anchoring and the unexplained-cascade flag in the emitted payload)
> cannot be fully implemented until this contract change is approved. See OQ-1 and OQ-2.

## Non-functional

- **Idempotency key:** `eventId` (envelope field); deduplicate on this key before processing
  each `TransactionEvent`.
- **Config:** All mining thresholds and tunable parameters (minimum support, maximum pattern
  length, session-windowing adaptation parameters including the base/fallback gap, maximum
  sequence count, domain-anchoring matching confidence threshold, grouping keys) are sourced from
  the Knowledge Service at runtime — not from code or static config files. No windowing gap
  literal, no domain-specific alarm type token, no fault-origin name exists as a hardcoded value
  anywhere in the service's source or default configuration. Integration URLs (Knowledge Service
  and Codebook base URLs) and mock/real toggles are provided via environment variables.
- **Domain-agnostic requirement (hard — per CLAUDE.md and stakeholder-stated).** No
  domain-specific alarm types, cascade shapes, fault-origin names, or thresholds appear as
  literals in source or config. All domain-specific inputs flow from Knowledge (params) and
  Codebook (scenarios), both keyed by `{domain}`. Adding a new domain = authoring its Knowledge
  vocab/params + Codebook scenarios; no code change to pattern-miner is required.
- **Observability:** `/health` endpoint; `/metrics` endpoint (Prometheus-compatible); structured
  JSON logs for every significant event (message consumed, session window finalized, anchoring
  outcome per cascade including confidence score, mining run started/completed per anchored group,
  events emitted, errors).
- **API contract:** pattern-miner exposes no HTTP API surface and therefore publishes no OpenAPI
  spec. Integration points are built against the Knowledge Service's and (pending OQ-1/OQ-3)
  Codebook Service's published OpenAPI specs.
- **Error handling:** Poison (unprocessable) messages are routed to `transactions.clean.dlq`.
  Transient errors (Knowledge Service or Codebook unavailable) are retried with config-driven
  back-off before the run fails fast. Mining does not proceed with stale or default thresholds
  (no hardcoded fallback). Retry policy is config-driven.
- **Spark/PySpark runtime:** Runs as a container-only stateless Spark job. Spark is not
  installed locally; all Spark execution occurs inside the service's Docker container. Python
  cohort; test framework is **pytest**.
- **Pattern count non-functional target.** On the Simulator's P2 historical corpus (~1,500
  alarms, 7 ground-truth fault-origin types), the three-stage approach must yield a small,
  accurate pattern set: 8-10 distinct anchored patterns (per `integration-thresholds.yaml`
  `distinct_patterns_min/max`), each covering 10-20 alarm-type tokens (`per_pattern_type_span_min/max`),
  with 50-60% alarm coverage (`pattern_coverage_min/max`). These numeric bounds flow from
  `services/simulator/integration-thresholds.yaml` — not hardcoded in pattern-miner.

## Acceptance criteria

### Stage 1 — Time + space correlation (session windowing)

**AC-1.** Given two trails whose alarm bursts have markedly different inter-arrival tempos — one
fast (sub-second inter-arrivals) and one slow (inter-arrivals minutes apart) — the service
finalizes different session boundaries appropriate to each: the fast burst is kept as a single
session (not over-split by a gap calibrated for slow conditions) and the slow burst is kept as a
single session (not truncated by a gap calibrated for fast conditions). Demonstrates that
windowing adapts to each burst's own tempo rather than applying one fixed gap uniformly.

**AC-2.** Given a single trail with two distinct activity bursts separated by a clear idle period
longer than any intra-burst inter-arrival gap, the service splits the trail's alarms into exactly
two sessions — one per burst; alarms within each burst remain in the same session and are not
further split.

**AC-3.** When the Knowledge Service windowing configuration is changed (e.g. the base/fallback
gap or any adaptation parameter is updated) and a fixed set of input alarm events is reprocessed,
the resulting session boundaries differ from those produced under the previous configuration,
confirming that windowing is governed solely by Knowledge-sourced parameters and no windowing
threshold is hardcoded in the service.

### Stage 2 — Domain-knowledge anchoring

**AC-4.** Given a batch of candidate cascades whose alarm-type token sequences clearly match two
distinct fault-origin scenarios sourced from the Codebook mock, the service assigns each cascade
to its correct fault-origin anchor; the two anchored groups are distinct, with no cascade
appearing in both groups (zero over-merge).

**AC-5.** Given a single fault-origin scenario that manifests in multiple candidate cascades
(same alarm-type pattern, different trails), all those cascades are assigned to the same
anchored group — they are not split into multiple distinct anchored groups (zero over-split).

**AC-6.** Given a candidate cascade whose alarm-type sequence does not closely match any Codebook
fault-origin scenario (match confidence below the Knowledge-sourced threshold), the service
assigns that cascade to the "unexplained" group rather than forcing it into the closest-match
anchor. The "unexplained" group produces a `PatternMinedEvent` with the "unexplained" sentinel
in the anchor-identity field.

**AC-7.** The matching confidence threshold used for anchoring is read exclusively from the
Knowledge Service; replacing the Knowledge mock to return a different threshold changes which
cascades are flagged as unexplained vs. anchored, with no code change.

**AC-8.** The fault-origin scenario set used for anchoring is sourced from the Codebook for the
active `codebookVersion` and `domain`; changing the Codebook mock to return a different scenario
set changes which cascades are anchored to which fault-origin, with no code change.

### Stage 3 — Bounded ML pattern definition (PrefixSpan per anchored group)

**AC-9.** Given a correctly anchored group containing cascades matching the fiber-cut
fault-origin scenario (alarm-type sequence `["FiberFault", "LinkDown", "AdjDown"]` per the Core
IP domain vocabulary as an example of the `alarmTypeVocabulary` — not a hardcoded literal in
the service), PrefixSpan run within that group emits a `PatternMinedEvent` whose `sequence`
equals that ordered alarm-type token list (built from `alarms[].alarmType`, the canonical join
token — not `eventType` and not `probableCause`) and whose `support` equals the observed
frequency of the sequence within that anchored group (within floating-point tolerance).

**AC-10.** When PrefixSpan runs within each anchored group independently (not globally), the
total count of emitted `PatternMinedEvent`s on `patterns.mined` is bounded by the number of
distinct anchored fault-origin groups plus the unexplained group (if non-empty) — not by the
count of frequent subsequences across the full corpus. Specifically: given N distinct fault-origin
types where all cascades are anchored, at most N+1 `PatternMinedEvent`s are emitted.

**AC-11.** Raising the minimum support threshold (sourced from the Knowledge mock) above the
support of a previously discovered sequence within an anchored group causes that sequence not to
appear in the emitted `PatternMinedEvent` for that group; lowering it back below the sequence's
support restores it.

### Overall contract and correctness

**AC-12.** Every `PatternMinedEvent` emitted by the service validates against the frozen
`PatternMinedEvent` Pydantic model from `libs/event-model/python`; all currently-required fields
(`sequence`, `support`, `confidence`, `lift`, `trailId`, `timing`, `provenance`) are present and
well-typed.

**AC-13.** No emitted `PatternMinedEvent` contains a `rootCauseAlarmType`, `patternId`, or
`lifecycle` field; the frozen schema's `extra="forbid"` enforces this.

**AC-14.** The `provenance` object on every emitted `PatternMinedEvent` carries `sourceWindowId`,
`snapshotId`, and `codebookVersion` (all non-empty); `codebookVersion` equals the value returned
by the Knowledge Service mining-params response for that run.

**AC-15.** When the service receives a `TransactionEvent` whose envelope `eventId` has already
been processed in the current session, no `PatternMinedEvent` is emitted for that duplicate; the
event is silently acknowledged and dropped.

**AC-16.** When the service receives a message it cannot deserialise or that fails
`TransactionEvent` schema validation, the message is routed to `transactions.clean.dlq` and no
`PatternMinedEvent` is emitted for it.

**AC-17.** No mining or anchoring configuration threshold — minimum support, windowing gap,
matching confidence, or grouping key — is present as a literal in the service's source code or
default configuration; all such values are proven to flow exclusively from the Knowledge Service
and Codebook integration points (confirmed by the Knowledge and Codebook mocks returning
changed values and observing changed behaviour, with no code change).

**AC-18.** The `timing` object on every emitted `PatternMinedEvent` carries exactly
`timeframeMs`, `medianInterArrivalMs`, `maxInterArrivalMs`, and `stddevInterArrivalMs` (all in
milliseconds, computed from `alarms[].raisedAt`); the previous `meanInterArrivalSeconds` /
`stdDevSeconds` keys are absent.

### Pattern-set quality (integration-level assertions against integration-thresholds.yaml)

**AC-19.** On the Simulator's P2 historical alarm corpus, the three-stage approach yields a
pattern-set size in the range `distinct_patterns_min`–`distinct_patterns_max` (8-10 per
`services/simulator/integration-thresholds.yaml`), with each anchored pattern's alarm-type token
span in `per_pattern_type_span_min`–`per_pattern_type_span_max` (10-20), and total alarm
coverage in `pattern_coverage_min`–`pattern_coverage_max` (50-60%). Asserted by the integration
harness against the Simulator oracle; numeric bounds come from `integration-thresholds.yaml`, not
from hardcoded values in pattern-miner.

**AC-20.** On the same corpus, the anchored pattern set has zero over-split patterns (no single
ground-truth fault-origin scenario produces more than one anchored `PatternMinedEvent`) and zero
over-merged patterns (no single anchored `PatternMinedEvent` spans multiple distinct
ground-truth fault-origin types). Asserted by the integration harness against the Simulator's
ground-truth oracle.

**AC-21.** Cascades with no confident domain-knowledge match are emitted as an "unexplained"
`PatternMinedEvent` (if the unexplained group is non-empty), do not inflate the anchored pattern
count, and do not cause the mining run to fail or error. The unexplained group's event is
distinguishable from anchored patterns by its anchor-identity field value.

## Open questions

### OQ-1 — BOUNDARY DECISION (blocks Stage 2 design): Where does domain-knowledge anchoring live?

**Context.** Stage 2 requires pattern-miner to access Codebook fault-origin scenarios to anchor
candidate cascades. Under the current frozen spec, pattern-miner is "pure sequence mining — no
topology/codebook access." Adding a Codebook client to pattern-miner is a service-boundary change.
The alternative is for Stage 2 to live entirely in pattern-manager (which already has Codebook +
RCA + the Pattern Store and already consumes `patterns.mined`).

**Trade-offs:**

_Option A — Stage 2 in pattern-miner (pattern-miner gains a Codebook client):_
- Pro: bounded PrefixSpan and anchoring are co-located; `patterns.mined` carries fully-anchored
  results; pattern-manager receives clean per-fault-origin inputs for RCA and reconciliation
  without needing to re-cluster.
- Pro: the pattern set emerging from `patterns.mined` is immediately accurate and small.
- Con: pattern-miner acquires a new Codebook dependency, breaking its current "no codebook
  access" boundary.
- Con: requires `PatternMinedEvent` to carry an anchor-identity field — a contract change
  (OQ-2). If Codebook is unavailable during a P2 run, the run fails or degrades.

_Option B — Stage 2 in pattern-manager (pattern-miner stays Codebook-free):_
- Pro: pattern-miner's boundary is unchanged; no Codebook client in pattern-miner; no
  `PatternMinedEvent` contract change for an anchor field.
- Pro: pattern-manager already holds the Codebook client, the Pattern Store, and the RCA logic.
- Con: pattern-manager must either (a) post-filter already globally-mined results (which does
  not eliminate the global PrefixSpan defect — the root cause remains) or (b) coordinate with
  pattern-miner to run bounded PrefixSpan per anchored group, which requires a feedback loop
  (a new topic or API — itself a contract change of a different kind).
- Con: pattern-manager is a Spring Boot (Java) service; running PySpark MLlib from it directly
  is not in the current design.
- Con: `patterns.mined` becomes a "raw partial result" topic rather than a "complete anchored
  pattern" topic, changing its semantic contract.

**What the human must decide:** Which option (A, B, or a defined hybrid) governs the
implementation? If Option A: approve the Codebook client addition to pattern-miner and the
`PatternMinedEvent` contract change (OQ-2). If Option B: define how pattern-manager achieves
bounded PrefixSpan per anchored group without a feedback-loop topic or PySpark dependency. This
decision determines the design scope for both pattern-miner and pattern-manager. **Design work on
Stage 2 must not begin until this is resolved.**

A GitHub issue labeled `question` and `service:pattern-miner` is opened (see PR for link) to
track this for human resolution.

### OQ-2 — CONTRACT CHANGE DECISION: anchor-identity field on `PatternMinedEvent`

**Context.** If OQ-1 resolves to Option A, each `PatternMinedEvent` must carry the fault-origin
scenario it was anchored to (or the "unexplained" sentinel) so the Pattern Manager can associate
the mined pattern with its known fault-origin for RCA and reconciliation. This requires a new
field — e.g. `anchorScenarioId` (string, required) — on the frozen `PatternMinedEvent`, either
at the payload top level or within `provenance`.

**Impact:**
- `libs/event-model` must be updated (new field in `PatternMinedEvent`; Python Pydantic model
  and Java binding both require the field).
- `docs/architecture.md` must be updated (event model description of `PatternMinedEvent`).
- The pattern-manager spec must be updated to reflect anchored inputs.
- Both `libs/event-model` and `docs/architecture.md` updates require human approval and must
  land as their own PR into `main` before design/build for Stage 2 proceeds.

**Stages 1 and 3 (session windowing and bounded PrefixSpan) can be specced and designed within
the current frozen contract. Stage 2 and the full anchored payload cannot be implemented until
this contract change is approved.**

**What the human must decide:** Approve or reject the anchor-identity field contract change (and
specify field name / placement if approved). This is a dependency of the pattern-miner designer
and the event-model owner.

### OQ-3 — Codebook API surface for Stage 2

**Context.** Stage 2 requires reading fault-origin scenarios from the Codebook. The Codebook
API surface referenced in `docs/architecture.md` is `GET /codebooks/active` and
`GET /codebooks/{id}/scenarios`. However:
- It is not confirmed whether the Codebook Service exposes an OpenAPI 3.1 spec that pattern-miner
  can build and mock its client against.
- The exact response shape for `GET /codebooks/{id}/scenarios` (fields on each scenario,
  including the canonical ordered alarm-type sequence used for cascade matching) is not
  documented in `docs/architecture.md`.
- Whether `codebookVersion` (from Knowledge mining-params response) maps directly to the
  Codebook `{id}` path parameter is not confirmed.

**The designer cannot build the Stage 2 anchoring client without the Codebook OpenAPI surface,
scenario response shape, and `codebookVersion`-to-`{id}` mapping being resolved.** If the
Codebook API surface does not yet meet these requirements, a contract-change PR for the Codebook
Service is required before Stage 2 design begins.
