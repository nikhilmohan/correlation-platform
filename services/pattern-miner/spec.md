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
  authored fault-origin scenarios sourced from the Codebook via pattern-miner's own Codebook
  client (OQ-1 resolved: Option A — see Open questions). Each cascade is assigned to the
  fault-origin scenario it best matches (the anchor), provided the match confidence meets the
  Knowledge-sourced threshold. Cascades with no confident match are flagged as **unexplained**
  — a first-class, correct outcome, not an error. This anchoring ensures the output pattern set
  is accurate: one anchor = one pattern identity, so over-splitting and over-merging are
  minimized. All matching thresholds and grouping keys are domain-sourced, never hardcoded.
  Anchoring uses the Codebook's fault-origin scenario shape: each scenario carries a
  `scenarioId`, `faultOriginObjectId`, `faultOriginType`, `predictedSymptoms` (ordered list of
  `{alarmType, managedObjectId}`) and `trailIds`. The `predictedSymptoms[].alarmType` list
  (ordered) is the canonical fault-origin symptom chain used for cascade matching; the
  `faultOriginType`/`faultOriginObjectId` is the anchor identity.
- **Stage 3 — ML pattern definition.** Run PrefixSpan (Spark MLlib) **within each anchored
  group** (bounded scope per fault-origin) to learn that group's canonical ordered alarm-type
  token signature, support, confidence, and lift. Emit one `PatternMinedEvent` per distinct
  anchored fault-origin pattern. Emit a separate `PatternMinedEvent` for the unexplained cascade
  group (if non-empty), with `provenance.anchorScenarioId` null/absent.
- **Sample alarms — representative evidence for operator review (XAI).** When assembling each
  `PatternMinedEvent`, populate the optional `sampleAlarms[]` field with a bounded, representative
  subset of the real member alarms drawn from the pattern's supporting session(s). Each entry
  carries the five fields already present on `TransactionEvent.alarms[]` items: `alarmId`,
  `alarmType`, `raisedAt` (ISO-8601), `managedObjectId` (`<objectType>:<id>` scheme), and
  `perceivedSeverity`. The purpose is XAI: downstream (pattern-manager -> web-ui) surfaces these
  concrete alarm instances as evidence behind the abstract alarm-type sequence, so operators can
  assess pattern trustworthiness during review/approval. The sample is bounded by a cap K sourced
  from the Knowledge Service (no hardcoded value). When no sample can be captured (edge case),
  `sampleAlarms` may be absent or empty without failing the event. No new Kafka input or topic is
  required — the alarm detail is already present in the `TransactionEvent.alarms[]` the miner
  already holds. The `PatternMinedEvent.sampleAlarms[]` contract field is already landed in
  `libs/event-model` on `main` (PR #349), backward-compatible (not in `required`).
- Source all configuration — minimum support threshold, maximum pattern length, session-windowing
  adaptation parameters (including base/fallback gap), maximum sequence count, domain-anchoring
  matching confidence threshold, grouping keys, and the sample-alarm cap K — from the Knowledge
  Service per domain. No hardcoded thresholds anywhere.
- Attach mining provenance to each result: source `trailId`(s), `sourceWindowId`, `snapshotId`,
  `codebookVersion`, and `provenance.anchorScenarioId` (the `scenarioId` of the matched
  fault-origin scenario, or null/absent for the unexplained group). `provenance.anchorScenarioId`
  is an optional string field on `PatternMinedEvent.provenance`; null/absent means "unexplained
  cascade" — a first-class outcome. This field is already landed in `libs/event-model` (PR #331).
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
  the Miner uses it as a grouping key without querying the graph directly. The Codebook client
  added for Stage 2 anchoring reads Codebook scenarios only — it does not grant or imply any
  access to the topology graph.
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
   fetch the domain's fault-origin scenarios from the Codebook using pattern-miner's Codebook
   client (built against the Codebook Service's published OpenAPI). Resolve the active codebook
   (see OQ-3 for the remaining `codebookVersion`-to-`codebookId` mapping gap), then call
   `GET /codebooks/{codebookId}/scenarios` to obtain the scenario list. Match the cascade's
   ordered alarm-type token sequence against each scenario's `predictedSymptoms[].alarmType`
   chain. Assign the cascade to the fault-origin scenario it most closely matches (the anchor),
   provided the match confidence meets the Knowledge-sourced threshold; record the matched
   scenario's `scenarioId` as `provenance.anchorScenarioId`. Cascades that do not reach the
   confidence threshold are assigned to the "unexplained" group (`provenance.anchorScenarioId`
   null/absent). Group all candidate cascades by their assigned anchor.

5. **Stage 3 — ML pattern definition.** Run PrefixSpan (Spark MLlib) **within each anchored
   group** (bounded to that fault-origin's cascades, not globally) to discover the canonical
   ordered alarm-type-token sequence, support, confidence, and lift for that fault-origin pattern.
   Run PrefixSpan similarly within the unexplained group if it is non-empty.

6. Assemble one `PatternMinedEvent` per anchored group, carrying: the discovered `sequence`
   (alarmType tokens), `support`, `confidence`, `lift`, `trailId`(s) in scope, `timing`
   (inter-arrival statistics in milliseconds: `timeframeMs`, `medianInterArrivalMs`,
   `maxInterArrivalMs`, `stddevInterArrivalMs`), `provenance` (`sourceWindowId`, `snapshotId`,
   `codebookVersion`, and `anchorScenarioId`), and `sampleAlarms[]` (a bounded sample of real
   member alarms drawn from the pattern's supporting session(s) — see scope item above). The
   "unexplained" group produces a `PatternMinedEvent` with `provenance.anchorScenarioId`
   null/absent — a first-class correct outcome. If no sample can be captured, `sampleAlarms` may
   be absent or empty without failing the event.

7. Emit each `PatternMinedEvent` onto `patterns.mined` (one event per anchored fault-origin
   group, plus one for the unexplained group if non-empty).

8. Route any unprocessable (poison) message to `transactions.clean.dlq`.

## Phase applicability

Consistent with the canonical phase map in `docs/architecture.md`.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; topology and trail compilation precede the learning phase. | Idle | — |
| P2 — Pattern learning | Core worker: session-windows candidate cascades (Stage 1), anchors each to a domain fault-origin scenario via the Codebook client (Stage 2), then runs bounded PrefixSpan per anchored group (Stage 3) to produce a small, accurate set of root-cause-grounded mined patterns. | Active | In: `transactions.clean` (TransactionEvent); Knowledge mining+anchoring params API; Codebook fault-origin scenarios API (`GET /codebooks/active`, `GET /codebooks/{codebookId}/scenarios`). Out: `patterns.mined` (PatternMinedEvent). |
| P3 — Real-time correlation | Not involved; mining is an offline/learning-only activity. Approved patterns are served by the Pattern Manager. | Idle | — |

## Contract

- **Consumes (Kafka):** `transactions.clean` (`TransactionEvent`). Its `alarms[]` array carries
  six required fields per entry: `alarmId`, `alarmType`, `eventType`, `raisedAt`,
  `managedObjectId`, `perceivedSeverity`. The Miner builds each mined `sequence` item from
  `alarmType` (the canonical join token) and its timing/windowing from `raisedAt`.
- **Produces (Kafka):** `patterns.mined` (`PatternMinedEvent`). Every emitted event carries
  `provenance.anchorScenarioId` (optional string, null/absent for the "unexplained" group) and
  an optional `sampleAlarms[]` array of up to K entries, each with `{alarmId, alarmType,
  raisedAt, managedObjectId, perceivedSeverity}`. Both fields are optional in the frozen schema
  (not in `required`), backward-compatible, and already landed in `libs/event-model` on `main`
  (`provenance.anchorScenarioId` via PR #331; `sampleAlarms[]` via PR #349). K is
  Knowledge-sourced; the field may be absent when no sample is available.
- **APIs exposed:** None (pattern-miner is a stateless Spark job; no HTTP API surface beyond
  `/health` and `/metrics`; no OpenAPI spec is published).
- **APIs/data consumed from other services:**
  - **Knowledge Service — mining + anchoring params:** `GET /domains/{domain}/model-params/{recordId}`
    (versioned-record envelope; `paramSet = "pattern-miner"`). Returns minimum support, maximum
    pattern length, session-windowing adaptation parameters (including the base/fallback gap),
    maximum sequence count, domain-anchoring matching confidence threshold, grouping keys, and
    `codebookVersion` in scope. Built against the Knowledge Service's published OpenAPI 3.1 spec.
  - **Codebook Service — fault-origin scenarios (Stage 2):** pattern-miner holds a Codebook
    client (OQ-1 resolved: Option A). The Codebook Service exposes an OpenAPI 3.1 spec at
    `/openapi.json` — pattern-miner's client is built and mocked against it. Endpoints used:
    `GET /codebooks?domain={domain}` (lists codebooks with `codebookId`, `snapshotId`, `domain`),
    `GET /codebooks/active?snapshotId={snapshotId}` (requires `snapshotId` query param), and
    `GET /codebooks/{codebookId}/scenarios`. Each scenario in the response carries:
    `scenarioId`, `faultOriginObjectId`, `faultOriginType`,
    `predictedSymptoms:[{alarmType, managedObjectId}]`, `trailIds:[...]`. The
    `predictedSymptoms[].alarmType` list (ordered) is the canonical fault-origin symptom chain
    used for Stage-2 cascade matching. The `codebookVersion`-to-`codebookId` resolution path
    is the remaining open item (OQ-3).
- **Integration points (mock vs. real):**
  - **Knowledge Service mining+anchoring params endpoint** (`GET /domains/{domain}/model-params/{recordId}`)
    — config-switchable per environment: unit tests use a mock/stub generated from the Knowledge
    Service's published OpenAPI spec; integration uses the real Knowledge Service at the Docker
    Compose address from env config.
  - **Codebook Service fault-origin scenarios endpoints** (`GET /codebooks/active`,
    `GET /codebooks/{codebookId}/scenarios`, `GET /codebooks?domain={domain}`) — config-switchable
    per environment: unit tests use a mock/stub generated from the Codebook Service's published
    OpenAPI spec (`/openapi.json`); integration uses the real Codebook Service. Base URLs and
    `mock|real` toggles provided via environment variables; no hardcoded URLs or domain-specific
    literals.
- **Data owned:** None (stateless; pattern-miner holds no datastore and persists no pattern
  state).

## Non-functional

- **Idempotency key:** `eventId` (envelope field); deduplicate on this key before processing
  each `TransactionEvent`.
- **Config:** All mining thresholds and tunable parameters (minimum support, maximum pattern
  length, session-windowing adaptation parameters including the base/fallback gap, maximum
  sequence count, domain-anchoring matching confidence threshold, grouping keys, and the
  sample-alarm cap K) are sourced from the Knowledge Service at runtime — not from code or static
  config files. No windowing gap literal, no domain-specific alarm type token, no fault-origin
  name, and no sample-alarm cap literal exists as a hardcoded value anywhere in the service's
  source or default configuration. Integration URLs (Knowledge Service base URL, Codebook Service
  base URL) and mock/real toggles are provided via environment variables.
- **Domain-agnostic requirement (hard — per CLAUDE.md and stakeholder-stated).** No
  domain-specific alarm types, cascade shapes, fault-origin names, or thresholds appear as
  literals in source or config. All domain-specific inputs flow from Knowledge (params) and
  Codebook (scenarios), both keyed by `{domain}`. The Codebook client is built against the
  Codebook OpenAPI and is per-domain/snapshot scoped — not hardcoded to Core IP or any specific
  domain. Adding a new domain = authoring its Knowledge vocab/params + Codebook scenarios; no
  code change to pattern-miner is required.
- **Observability:** `/health` endpoint; `/metrics` endpoint (Prometheus-compatible); structured
  JSON logs for every significant event (message consumed, session window finalized, anchoring
  outcome per cascade including confidence score and matched `scenarioId` or "unexplained",
  mining run started/completed per anchored group, events emitted, errors).
- **API contract:** pattern-miner exposes no HTTP API surface and therefore publishes no OpenAPI
  spec. Integration points are built against the Knowledge Service's and Codebook Service's
  published OpenAPI specs (`/openapi.json`).
- **Error handling:** Poison (unprocessable) messages are routed to `transactions.clean.dlq`.
  Transient errors (Knowledge Service or Codebook Service unavailable) are retried with
  config-driven back-off before the run fails fast. Mining does not proceed with stale or default
  thresholds (no hardcoded fallback). Retry policy is config-driven.
- **Spark/PySpark runtime:** Runs as a container-only stateless Spark job. Spark is not
  installed locally; all Spark execution occurs inside the service's Docker container. Python
  cohort; test framework is **pytest**.
- **Pattern count non-functional target.** On the Simulator's P2 historical corpus (~1,500
  alarms, 8-10 ground-truth fault-origin types), the three-stage approach must yield a small,
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
distinct fault-origin scenarios (each with a distinct `predictedSymptoms[].alarmType` chain)
sourced from the Codebook mock, the service assigns each cascade to its correct fault-origin
anchor and records the matched scenario's `scenarioId` in `provenance.anchorScenarioId`; the two
anchored groups are distinct, with no cascade appearing in both groups (zero over-merge).

**AC-5.** Given a single fault-origin scenario that manifests in multiple candidate cascades
(same alarm-type pattern, different trails), all those cascades are assigned to the same
anchored group and produce a single `PatternMinedEvent` with the same `provenance.anchorScenarioId`
— they are not split into multiple distinct anchored groups (zero over-split).

**AC-6.** Given a candidate cascade whose alarm-type sequence does not closely match any Codebook
fault-origin scenario's `predictedSymptoms[].alarmType` chain (match confidence below the
Knowledge-sourced threshold), the service assigns that cascade to the "unexplained" group rather
than forcing it into the closest-match anchor. The "unexplained" group produces a
`PatternMinedEvent` with `provenance.anchorScenarioId` null/absent.

**AC-7.** The matching confidence threshold used for anchoring is read exclusively from the
Knowledge Service; replacing the Knowledge mock to return a different threshold changes which
cascades are flagged as unexplained vs. anchored, with no code change.

**AC-8.** The fault-origin scenario set used for anchoring is sourced from the Codebook for the
active codebook (domain-scoped) via the Codebook mock; changing the Codebook mock to return a
different set of scenarios (with different `scenarioId`s and `predictedSymptoms`) changes which
cascades are anchored to which fault-origin, with no code change.

### Stage 3 — Bounded ML pattern definition (PrefixSpan per anchored group)

**AC-9.** Given a correctly anchored group containing cascades matching a fiber-cut
fault-origin scenario (e.g. alarm-type sequence `["FiberFault", "LinkDown", "AdjDown"]` from the
`predictedSymptoms[].alarmType` chain of that scenario — not a hardcoded literal in the service),
PrefixSpan run within that group emits a `PatternMinedEvent` whose `sequence` equals that ordered
alarm-type token list (built from `alarms[].alarmType`, the canonical join token — not `eventType`
and not `probableCause`) and whose `support` equals the observed frequency of the sequence within
that anchored group (within floating-point tolerance).

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

**AC-12.** Every `PatternMinedEvent` emitted by the service validates against the
`PatternMinedEvent` Pydantic model from `libs/event-model/python`; all required fields
(`sequence`, `support`, `confidence`, `lift`, `trailId`, `timing`, `provenance`) are present and
well-typed. Anchored-group events carry a non-null string `provenance.anchorScenarioId`; the
unexplained-group event carries `provenance.anchorScenarioId` null/absent. The optional field's
absence does not cause schema validation failure (it is not in `provenance.required`).

**AC-13.** No emitted `PatternMinedEvent` contains a `rootCauseAlarmType`, `patternId`, or
`lifecycle` field; the schema's `extra="forbid"` enforces this.

**AC-14.** The `provenance` object on every emitted `PatternMinedEvent` carries `sourceWindowId`,
`snapshotId`, and `codebookVersion` (all non-empty); `codebookVersion` equals the value returned
by the Knowledge Service mining-params response for that run. For anchored-group events,
`provenance.anchorScenarioId` is non-null and matches the `scenarioId` of the Codebook scenario
the cascade was matched against.

**AC-15.** When the service receives a `TransactionEvent` whose envelope `eventId` has already
been processed in the current session, no `PatternMinedEvent` is emitted for that duplicate; the
event is silently acknowledged and dropped.

**AC-16.** When the service receives a message it cannot deserialise or that fails
`TransactionEvent` schema validation, the message is routed to `transactions.clean.dlq` and no
`PatternMinedEvent` is emitted for it.

**AC-17.** No mining or anchoring configuration threshold — minimum support, windowing gap,
matching confidence, or grouping key — is present as a literal in the service's source code or
default configuration; all such values are proven to flow exclusively from the Knowledge Service
and Codebook Service integration points (confirmed by the Knowledge and Codebook mocks returning
changed values and observing changed behaviour, with no code change).

**AC-18.** The `timing` object on every emitted `PatternMinedEvent` carries exactly
`timeframeMs`, `medianInterArrivalMs`, `maxInterArrivalMs`, and `stddevInterArrivalMs` (all in
milliseconds, computed from `alarms[].raisedAt`); the previous `meanInterArrivalSeconds` /
`stdDevSeconds` keys are absent.

### Sample alarms (XAI member-alarm evidence)

**AC-22.** Given a `PatternMinedEvent` emitted for an anchored group whose supporting session
contains at least one alarm, the `sampleAlarms[]` field is present and non-empty, and every
entry carries all five required fields: `alarmId` (non-empty string), `alarmType` (non-empty
string), `raisedAt` (ISO-8601 date-time string), `managedObjectId` (non-empty string in
`<objectType>:<id>` format), and `perceivedSeverity` (non-empty string). The event validates
against the `PatternMinedEvent` Pydantic model from `libs/event-model/python` with
`sampleAlarms` present.

**AC-23.** When the supporting session contains more than K alarms (K sourced from the Knowledge
mock), the `sampleAlarms[]` array contains at most K entries — never more. When it contains K
or fewer alarms, all are included. The value of K is confirmed by replacing the Knowledge mock
to return a different K value and observing the array length change with no code change.

**AC-24.** Every `alarmType` value present in `sampleAlarms[]` is a member of the pattern's
`sequence[]` — the sampled alarms come from the same session(s) used to derive the pattern and
carry only alarm types that appear in that session.

**AC-25.** When no member alarms can be captured for a pattern (edge case: session data
unavailable or empty), the emitted `PatternMinedEvent` either omits `sampleAlarms` or sets it to
an empty array; the event still validates against the schema and no error is raised.

**AC-26.** The sample-alarm cap K is read exclusively from the Knowledge Service and is not
present as a literal anywhere in the service's source or default configuration; replacing the
Knowledge mock to return a different K changes the maximum array length, with no code change.

### Pattern-set quality (integration-level assertions against integration-thresholds.yaml)

**AC-19.** On the Simulator's P2 historical alarm corpus, the three-stage approach yields a
pattern-set size in the range `distinct_patterns_min`--`distinct_patterns_max` (8-10 per
`services/simulator/integration-thresholds.yaml`), with each anchored pattern's alarm-type token
span in `per_pattern_type_span_min`--`per_pattern_type_span_max` (10-20), and total alarm
coverage in `pattern_coverage_min`--`pattern_coverage_max` (50-60%). Asserted by the integration
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
distinguishable from anchored patterns by `provenance.anchorScenarioId` being null/absent.

## Open questions

### OQ-1 — RESOLVED: Option A (domain-knowledge anchoring lives in pattern-miner)

**Decision (human, final).** Stage 2 domain-knowledge anchoring lives in **pattern-miner**.
pattern-miner gains a **Codebook client**. The old "no codebook access" boundary is intentionally
superseded for the Codebook read only. pattern-miner still does NOT touch the NebulaGraph
topology graph — Topology remains another service's sole domain; the Codebook client reads
fault-origin scenarios, not graph data.

**Rationale.** Stage 3 (PrefixSpan/PySpark) is Python and runs in the miner; co-locating Stage 2
keeps the anchor->mine loop in one service and makes `patterns.mined` accurate and small at
source. pattern-manager receives clean per-fault-origin inputs for RCA and reconciliation.

**Trade-off record (for the record; decision is final).**

_Option A — Stage 2 in pattern-miner (chosen):_
- Pro: bounded PrefixSpan and anchoring are co-located; `patterns.mined` carries fully-anchored
  results; pattern-manager receives clean per-fault-origin inputs for RCA and reconciliation
  without needing to re-cluster.
- Pro: the pattern set emerging from `patterns.mined` is immediately accurate and small.
- Con: pattern-miner acquires a new Codebook dependency, breaking its previous "no codebook
  access" boundary (now intentionally superseded by this decision).
- Con: if Codebook is unavailable during a P2 run, the run fails or degrades.

_Option B — Stage 2 in pattern-manager (not chosen):_
- Pro: pattern-miner's boundary would be unchanged; no Codebook client in pattern-miner.
- Con: pattern-manager must coordinate with pattern-miner to run bounded PrefixSpan per anchored
  group, requiring a feedback loop (new topic or API — a different contract change).
- Con: pattern-manager is Spring Boot (Java); running PySpark MLlib from it directly is not in
  the current design.
- Con: `patterns.mined` would become a "raw partial result" topic rather than a "complete
  anchored pattern" topic, changing its semantic contract.

**Spec impact.** The Scope, Out of scope, Tasks, Contract, Non-functional, and AC sections
above have all been updated to reflect Option A as the resolved decision. No further action
required before Stage 2 design begins (OQ-2 is also resolved; the one remaining item is OQ-3).

---

### OQ-2 — RESOLVED: anchorScenarioId contract change approved and landed (PR #331)

**Decision (human, final).** The `PatternMinedEvent.provenance.anchorScenarioId` contract change
is approved and already landed in `libs/event-model` on `main` (PR #331).

**Concrete final shape:**
- Field: `provenance.anchorScenarioId` (string, optional/nullable).
- Placement: within the `provenance` object on `PatternMinedEvent`.
- Null/absent semantics: null or absent means the cascade was "unexplained" (no confident
  scenario match) — a first-class, valid outcome, not an error. There is no separate flag.
- Backward-compat: the field is NOT in `provenance.required`; existing `PatternMinedEvent`
  messages without the field still validate.

**Spec impact.** The contract change caveat block and the "cannot be implemented until this
contract change is approved" caveats that appeared in the previous version of this spec have been
removed throughout. All Contract, Non-functional, and AC sections now reference
`provenance.anchorScenarioId` with the concrete final shape above.

---

### OQ-3 — PARTIALLY RESOLVED (one item remains for the designer)

**Confirmed (human-verified against the live Codebook Service):**

1. The Codebook Service exposes an OpenAPI spec at `/openapi.json`. pattern-miner's Codebook
   client MUST be built and mocked against it. Confirmed.

2. Endpoints available (confirmed):
   - `GET /codebooks/active` — requires a `snapshotId` query param (NOT domain alone).
   - `GET /codebooks/{codebookId}/scenarios` — returns the scenario list for a codebook.
   - `GET /codebooks?domain={domain}` — lists codebooks; each entry carries `codebookId`,
     `snapshotId`, `domain`.

3. Scenario response shape (confirmed): each scenario = `{ scenarioId, faultOriginObjectId,
   faultOriginType, predictedSymptoms:[{alarmType, managedObjectId}], trailIds:[...] }`. The
   `predictedSymptoms[].alarmType` list (ordered) is the canonical fault-origin symptom chain
   used for Stage-2 cascade matching. `faultOriginType`/`faultOriginObjectId` is the anchor
   identity.

**Remaining gap (open — for the designer to resolve before Stage 2 design is final):**

The Knowledge mining-params `codebookVersion` field returns the symbolic value `"current"`, and
the Codebook's `version`/`codebookVersion` field is not populated — so **how `codebookVersion="current"`
resolves to a concrete codebook `{codebookId}` is not cleanly defined**. The likely path is:
mining runs under a topology `snapshotId` in scope -> `GET /codebooks/active?snapshotId={snap}`
-> returns `codebookId`. But it is not confirmed whether:
- `GET /codebooks/active?snapshotId={snap}` reliably returns a single unambiguous active codebook
  for the domain+snapshot combination, or
- a small Codebook API clarification (e.g. accept `"current"` as a codebookVersion, or a
  resolve-by-domain endpoint) is needed.

**The designer must confirm the `codebookVersion="current"` -> `codebookId` resolution path with
the Codebook Service owner and, if a Codebook API change is required, raise that as a contract
change PR before Stage 2 build begins.**

---

### OQ-SA-1 — Sample cap K: default value and per-pattern vs. per-occurrence bound (for the designer)

The spec requires K to be Knowledge-sourced (no hardcoded default). The designer must decide and
document in `design.md`:
- What default value does the Knowledge Service return for K when no explicit value has been
  authored for the domain? (Needs confirmation with the Knowledge Service owner — if Knowledge
  does not yet carry this param, a Knowledge record addition is required before build.)
- Is the bound applied per emitted `PatternMinedEvent` (per anchored group / per pattern
  identity), or per occurrence / per supporting session individually? The contract field is a
  flat array on the event, so the bound applies to the final assembled array; the designer must
  specify how it is applied when multiple sessions support the same pattern.

This is a design decision with no contract implication (the contract field shape is fixed on
`main`). The designer resolves it; no human approval needed unless Knowledge Service record
addition triggers a contract change.

---

### OQ-SA-2 — Which supporting session(s) to sample from; ordering within the sample (for the designer)

When a pattern has multiple supporting sessions/occurrences (e.g. the same fault-origin scenario
anchors several cascades across different trail windows), the designer must specify:
- Which session(s) are used as the source for `sampleAlarms[]` — e.g. the first/earliest
  matching session, the session with highest match confidence, a spread across sessions, or the
  most recent.
- How alarms within the selected source are ordered before applying the K-cap — e.g. by
  `raisedAt` ascending (chronological within the cascade), or another order.
- Whether duplicate alarm instances (same `alarmId` appearing in multiple sessions) are deduped
  before the K-cap is applied.

These are pattern-miner design details with no contract implication. The designer decides and
records the rationale in `design.md`.

---

### Implementation prerequisite note (for the designer/dev — not a spec decision)

The `pattern-miner` branch's bundled `libs/event-model` is currently behind `main` and does NOT
yet include the `sampleAlarms[]` field on `PatternMinedEvent` (landed on `main` via PR #349).
The design/build must sync `libs/event-model` to `main` before the `sampleAlarms` field can be
referenced in code or tests — the same sync that was performed for `pattern-manager` via PR
#341. This is an implementation prerequisite, not a spec or contract decision.
