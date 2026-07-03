# pattern-manager — Enhancement Spec: Pattern Sample Alarm References

> **Status: DRAFT — awaiting human approval.**
> This is an enhancement spec, not a replacement of the base `spec.md`. It describes a
> discrete, bounded capability addition to the Pattern Manager and Pattern Read API. All
> existing scope, contract, and acceptance criteria in the base `spec.md` remain in force.

---

## Purpose

Operators reviewing discovered patterns need to see the **concrete, real alarm instances that
evidence a pattern** before they can trust and approve it. The current pattern read API
(`GET /patterns` / `GET /patterns/{patternId}`) returns only window-level provenance
references (`supportingInstances[]` carrying `{sourceWindowId, snapshotId,
occurrence:{anchorScenarioId,...}}`). The web-ui already renders these references honestly
with a "per-alarm detail not yet served" note — acknowledging the gap.

This spec closes that gap: for each discovered pattern, the Pattern Manager serves a
**bounded sample of the real member alarms** that the Pattern Miner observed when it mined
that pattern's supporting instances. Each sample alarm carries the fields already present in
`transactions.clean` `alarms[]`: `alarmId`, `alarmType`, `raisedAt` (timestamp),
`managedObjectId` (network node/object), and `perceivedSeverity`.

The **explainability / operator-trust rationale** is the primary driver: operators at a NOC
approving or rejecting an abstract alarm-type sequence must be able to see "which real alarms
on which real nodes at which times formed this pattern" before they act. Without this, the
"supporting instances" field is opaque provenance metadata, not decision-grade evidence.

> **This is a cross-service data-provenance gap.** The data (`alarms[]` with full per-alarm
> detail) exists in `transactions.clean`, consumed only by the Pattern Miner. The Pattern
> Miner is currently stateless with respect to member alarms — it emits `PatternMinedEvent`
> and discards the session. The Pattern Manager (the read-API owner) therefore has no path
> to per-alarm detail. The exact mechanism by which the Pattern Manager obtains member-alarm
> data is an **open architectural question** (see Open questions). This spec defines the
> observable capability (what the operator sees, what the API returns) and the data
> constraints, but **does not choose the data-capture mechanism or ownership**. That
> decision is a human + contract-change gate.

---

## Scope

**In scope:**

- Extending the Pattern Manager's pattern read API to serve, per pattern, a **bounded
  representative sample** of real member alarms (up to `K` alarm records per pattern, where
  `K` is a configurable parameter; the operator needs a sample, not an exhaustive list).
- Each sample alarm record carries exactly the fields present in `transactions.clean`
  `alarms[]` at the time of mining:
  - `alarmId` (string — the unique alarm identifier)
  - `alarmType` (string — the canonical alarm-type vocabulary token, same join key used in
    pattern sequences; e.g. `PortDown`, `InterfaceDown`, `LinkDown`, `FiberFault`)
  - `raisedAt` (ISO-8601 UTC timestamp — when the alarm was raised)
  - `managedObjectId` (string in the canonical `<objectType>:<id>` scheme — the network
    node/object the alarm originated from)
  - `perceivedSeverity` (string — X.733 severity)
- The sample is associated with the pattern's `supportingInstances` evidence, so the
  operator can see: "this pattern was observed N times; here are sample alarms from one or
  more of those occurrences."
- The sample is surfaced through the existing pattern read API surface: `GET /patterns` and
  `GET /patterns/{patternId}` responses. The exact response-shape change (a new
  `sampleAlarms[]` sub-field on `PatternView`, or an embedded field under
  `supportingInstances[]`, or a new sub-endpoint) is a **design-stage + contract-change
  decision** for human approval (see Open questions OQ-SA-1).
- The sample is bounded: the store holds at most `K` alarm records per pattern (and
  optionally per occurrence). The default `K` and whether it is per-pattern or per-occurrence
  are configurable (no hard-coded threshold); the UI displays this sample, not the full alarm
  corpus.
- The fields exposed are bounded by what exists in `transactions.clean` `alarms[]` — no new
  field is introduced; no inference, enrichment, or lookup is performed to add fields not in
  that source.
- All new data persisted by the Pattern Manager for this capability is stored in the
  Pattern Store (PostgreSQL, schema `pattern`) — the Pattern Manager remains the single
  owner of pattern state.

**In scope (secondary — related cleanup, lower priority):**

- The mined-event/occurrence field `anchorScenarioId` is populated in some contexts while
  the top-level `PatternDiscoveredEvent.codebookMatchId` / `PatternView.codebookMatchId` is
  null. These should be reconciled so the web-ui's codebook/fault-origin display is
  consistent: if `anchorScenarioId` is present in any supporting instance, the reconciliation
  logic (already present in the Pattern Manager's codebook-override task) should propagate
  it to `codebookMatchId` where applicable. This reconciliation is a Pattern Manager internal
  concern and does **not** require a contract change. It is a "should" for this enhancement,
  not a blocking "must" (see AC-SA-8).

---

## Out of scope

- Storing or serving a **complete historical alarm corpus** for every pattern (this is a
  sample, not a full audit log; the full corpus would require a durable historical-alarm
  store, which is explicitly deferred from MVP per `architecture.md` — "MVP scope — live
  only, no historical corpus").
- **Querying the Alarm Manager** for historical alarm detail: the Alarm Manager owns the
  **live alarm store** (alarms from `alarms.enriched.live` with lifecycle state); historical
  learning-path alarms are **not persisted** there. The Alarm Manager is not a source for
  this capability.
- Any change to the **live alarm path** (P3: `alarms.enriched.live`, `alarms.persisted.live`,
  `correlation.results`): this capability is entirely within the P2 pattern-learning path.
- Serving the sample alarms as a **separate queryable alarm datastore** accessible to
  services other than via the Pattern Manager's pattern read API: the sample is pattern-
  scoped metadata, not a general alarm query endpoint.
- Enriching the sample alarms beyond what is in `transactions.clean` (no topology lookups,
  no codebook lookups, no lifecycle-state assignment).
- Choosing the data-capture mechanism or topology of how the Pattern Manager obtains the
  sample alarms: this is an Open question for human decision (see OQ-SA-2 through OQ-SA-5).
- Changes to the frozen `PatternDiscoveredEvent` or `PatternApprovedEvent` Kafka event
  schemas without explicit human approval of a contract change (see OQ-SA-1).
- Changes to `docs/architecture.md` without explicit human approval.
- Adding new Kafka topics without explicit human approval (a contract change per golden rule).

---

## Tasks (high-level)

These tasks describe the observable work the Pattern Manager performs in support of this
capability. **They do not prescribe the data-capture mechanism** — that is the subject of
the Open questions that a human must resolve before design proceeds.

1. **Receive and persist sample alarm records for each mined pattern.** When a new
   `PatternMinedEvent` is processed, capture and persist a bounded sample (up to `K` alarms)
   of the real member alarms that the mining session observed for that pattern, storing each
   record with `alarmId`, `alarmType`, `raisedAt`, `managedObjectId`, and
   `perceivedSeverity` in the Pattern Store, associated with the `patternId`. The mechanism
   by which these records reach the Pattern Manager is an Open question.

2. **Bound the stored sample.** Enforce the configurable limit `K` (alarms per pattern, or
   alarms per occurrence, per configuration) at ingest time. New alarms beyond the cap for
   an existing pattern are not stored. The cap is read from environment/Knowledge config —
   no hard-coded value.

3. **Serve sample alarms via the pattern read API.** Include the stored sample alarm
   records in the pattern read API response for `GET /patterns` and
   `GET /patterns/{patternId}`. The exact shape of the response extension is an Open
   question (new field on `PatternView`, or sub-endpoint); the designer specifies and
   publishes the shape in the Pattern Manager's `openapi.json`.

4. **Handle absent sample gracefully.** When no sample alarms have been captured for a
   pattern (e.g. the mechanism for obtaining them is not yet implemented, or the pattern
   predates the capability), the API returns the field as an empty list (not absent/null) so
   the web-ui can display "no sample alarms available" without special-casing a missing field.

5. **Reconcile `anchorScenarioId` to `codebookMatchId` (related cleanup).** During the
   existing codebook-reconciliation task (Task 5 of the base spec), if any supporting
   instance carries a populated `anchorScenarioId` but the pattern-level `codebookMatchId`
   is absent, propagate the `anchorScenarioId` to `codebookMatchId` so the UI's
   codebook/fault-origin display is consistent. This is purely internal to the Pattern
   Manager's existing enrichment pipeline.

---

## Phase applicability

This enhancement is active only in P2 (Pattern learning), where patterns are mined and
reviewed. It is consistent with the canonical phase map in `architecture.md` for the
Pattern Manager service.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | No change from base spec. | Idle | — |
| P2 — Pattern learning | Active (same as base spec). Additionally: captures and persists sample alarm records alongside pattern enrichment; serves them via the pattern read API for operator review. | Active | In: per-alarm sample records sourced via the mechanism resolved in OQ-SA-2 (mechanism TBD — human decision); Out: extended `PatternView` in `GET /patterns` / `GET /patterns/{patternId}` responses (shape subject to contract-change approval, OQ-SA-1). Kafka topics consumed/produced are unchanged from the base spec unless Option A is chosen (see OQ-SA-2). |
| P3 — Real-time correlation | No change from base spec. The sample alarms are pattern-review evidence; they are not used by the Correlation Engine. | Passive | Serves: pattern read API (unchanged). Sample alarms are served in read responses if requested; they do not affect `patterns.approved` or `PatternApprovedEvent`. |

---

## Contract

### Changes relative to the base `spec.md`

> **IMPORTANT — CONTRACT CHANGE GATE.** Any change to the following artefacts requires
> explicit human approval BEFORE design or build proceeds, per the contract-change procedure
> in `.claude/agents/CONVENTIONS.md`:
>
> - `PatternMinedEvent` schema in `libs/event-model` — if Option A is chosen (see OQ-SA-2)
> - The Pattern Manager's pattern read API surface (`GET /patterns` / `GET /patterns/{patternId}`
>   response shape — a new field on `PatternView` or a new sub-endpoint)
> - `docs/architecture.md` — if a new Kafka topic, a new data source for pattern-manager, or
>   a new service-level responsibility is added
>
> The spec defers the choice. Do not begin design until the Open questions are resolved by a
> human.

**Consumes (Kafka) — unchanged from base spec:**
- `patterns.mined` — `PatternMinedEvent` (unchanged schema, no new fields without human
  approval of OQ-SA-2 Option A)

**Produces (Kafka) — unchanged from base spec:**
- `patterns.discovered` — `PatternDiscoveredEvent` (unchanged; sample alarms are NOT added
  to this event without human approval of a contract change)
- `patterns.approved` — `PatternApprovedEvent` (unchanged; sample alarms are not carried
  in the approved-pattern event)

**APIs exposed — one new field / shape change (contract change, human approval required):**
- `GET /patterns` and `GET /patterns/{patternId}` — the `PatternView` response type gains a
  new field carrying the sample alarms. The exact field name, nesting (top-level
  `sampleAlarms[]` on `PatternView`, or nested under `supportingInstances[]`, or a separate
  sub-endpoint `GET /patterns/{patternId}/sample-alarms`) is a **design-stage decision**
  subject to OQ-SA-1. Until OQ-SA-1 is resolved, this field is not added to the published
  `openapi.json`. Each sample alarm record in the field carries:
  `alarmId` (string), `alarmType` (string), `raisedAt` (ISO-8601 UTC string),
  `managedObjectId` (string, `<objectType>:<id>` format), `perceivedSeverity` (string).
  When no sample is available, the field is an empty list `[]`.

**APIs/data consumed — varies by mechanism chosen (see Open questions):**
- If Option B or C is chosen: the Pattern Manager gains a new inbound data dependency. This
  is a contract change requiring `docs/architecture.md` update and human approval.
- If Option A is chosen: no new API dependency is added; data arrives with the enriched
  `PatternMinedEvent`. Contract change is to `PatternMinedEvent` itself.
- All options: no change to the Topology Service, Codebook Generator, or Knowledge Service
  integration points in the base spec.

**Data owned (Pattern Store — additive only):**
- Pattern Store (PostgreSQL, schema `pattern`) gains a new table or column(s) to persist
  sample alarm records per pattern. The Pattern Manager remains the single owner; this is
  an internal schema change, not a cross-service ownership change. Schema migration is
  Pattern Manager's own responsibility (Flyway, scoped to the `pattern` schema).

**Integration points (mock vs. real) — unchanged for existing points. If a new inbound
source is introduced (Options B or C), that integration point must also be config-switchable
(mock from the source's published OpenAPI / real for integration).**

---

## Non-functional

- **Idempotency key:** unchanged from base spec (`eventId` for event dedup). Sample alarm
  persistence must also be idempotent: re-processing the same `PatternMinedEvent` (same
  `eventId`) must not duplicate sample alarm records in the Pattern Store.
- **Bounded store:** the sample is capped at `K` alarms per pattern (configurable via
  environment; no hard-coded value; suggested default is a design-stage decision). The cap
  prevents unbounded growth in the Pattern Store from large mining windows.
- **Config:** the sample cap `K` (and whether it is per-pattern or per-occurrence) is
  supplied via environment configuration or the Knowledge Service — never hard-coded.
- **Observability:** unchanged from base spec (`/health`, `/metrics`, structured JSON logs).
  Sample-alarm ingestion failures are logged at WARN with `patternId` and source context.
- **API contract:** the pattern read API's published OpenAPI 3.1 (`services/pattern-manager/
  openapi.json`) is extended to include the new field. The updated `openapi.json` is the
  authoritative HTTP surface and drives contract/unit tests. A surface change is a contract
  change requiring human approval before it is published.
- **Error handling:** if sample alarm data is unavailable or fails to persist for a
  particular pattern, the pattern itself is still persisted and served (sample absence is
  non-fatal); the API returns an empty `sampleAlarms` list for that pattern.
- **Test framework:** unchanged — JUnit 5 (unit and contract tests), Testcontainers for
  integration.

---

## Acceptance criteria

Each criterion is phrased to map to a single unit test.

### Core capability

**AC-SA-1.** Given a pattern persisted in the Pattern Store for which sample alarm records
have been captured, a `GET /patterns/{patternId}` response includes a non-empty `sampleAlarms`
field (field name subject to OQ-SA-1 resolution); each entry in that field carries
`alarmId`, `alarmType`, `raisedAt`, `managedObjectId`, and `perceivedSeverity` — all
non-null strings with `raisedAt` in ISO-8601 UTC format and `managedObjectId` matching the
`<objectType>:<id>` scheme. (JUnit 5 — Pattern Store fixture with stored sample alarms;
mock `GET /patterns/{patternId}` handler; assert field presence and schema conformance)

**AC-SA-2.** Given a pattern for which sample alarm records have been captured, the
`alarmType` value in each sample alarm record matches a value that appears in the pattern's
`sequence[]` field (confirming the sample alarms are member alarms of the pattern, not
unrelated records). (JUnit 5 — fixture: persist sample alarms whose `alarmType` tokens
overlap the pattern's sequence; assert that each returned sample alarm's `alarmType` is a
member of `sequence[]`)

**AC-SA-3.** Given a pattern for which sample alarm records have been captured, the
`managedObjectId` in each returned sample alarm record conforms to the canonical
`<objectType>:<id>` scheme (non-empty string, contains exactly one colon, `objectType`
matches `^[A-Za-z][A-Za-z0-9]*$`, and `id` is non-empty). (JUnit 5 — assert format
constraint on each sample alarm's `managedObjectId` in the API response)

**AC-SA-4.** Given a pattern for which no sample alarm records have been captured (either
not yet obtained or the mechanism has not yet fired), a `GET /patterns/{patternId}` response
includes the `sampleAlarms` field as an empty list `[]` — it is not absent and not null.
(JUnit 5 — fixture: pattern with zero sample alarms in Pattern Store; assert field is
present and empty array)

**AC-SA-5.** Given a pattern for which sample alarm records have been captured, a
`GET /patterns` (list) response includes the `sampleAlarms` field (or equivalent) on the
`PatternView` item for that pattern, with the same content as `GET /patterns/{patternId}`.
The field is present on every `PatternView` item in the list response, even those with an
empty sample. (JUnit 5 — mock list response with two patterns: one with samples, one without;
assert field presence and content on both)

### Bounded sample

**AC-SA-6.** Given a configurable sample cap `K = 3` (set via environment/config, not
hard-coded) and a pattern for which 5 alarm records are available, the Pattern Store
retains at most 3 sample alarm records for that pattern; `GET /patterns/{patternId}` returns
exactly 3 sample alarms. (JUnit 5 — inject `K=3` via config; provide 5 sample alarms at
ingest; assert stored count is <= 3 and API returns <= 3)

**AC-SA-7.** Given the same ingest input processed twice (same pattern, same source alarms,
same `eventId` to simulate Kafka redelivery), the Pattern Store contains the same sample
alarm records after both passes as after the first — no duplicates are created (idempotency
of sample alarm persistence). (JUnit 5 — process the ingest input twice; assert sample
alarm count in the Pattern Store equals the count after the first pass)

### Related cleanup — `anchorScenarioId` reconciliation

**AC-SA-8.** Given a `PatternMinedEvent` where `provenance.anchorScenarioId` is populated
(non-null) but no codebook match would otherwise be found by the standard codebook-override
logic, the Pattern Manager propagates `anchorScenarioId` to the pattern's `codebookMatchId`
field; a subsequent `GET /patterns/{patternId}` returns `codebookMatchId` equal to the
`anchorScenarioId` value from the provenance. (JUnit 5 — mock Codebook Generator returning
no match; mock `PatternMinedEvent` with `provenance.anchorScenarioId = "scenario-42"`;
assert persisted `codebookMatchId = "scenario-42"` and field present in read API response)

### API contract conformance

**AC-SA-9.** A `GET /patterns/{patternId}` response (with sample alarms present) validates
successfully against the published and updated `services/pattern-manager/openapi.json`
schema. (JUnit 5 — schema-validation contract test against the published OpenAPI spec; no
manual field-by-field assertion needed if schema validation passes)

**AC-SA-10.** A `GET /patterns` list response validates successfully against the published
and updated `services/pattern-manager/openapi.json` schema, including the `sampleAlarms`
field (or equivalent) on each `PatternView` item. (JUnit 5 — schema-validation contract
test)

---

## Open questions

> Per the contract-change procedure in `.claude/agents/CONVENTIONS.md`: each question marked
> **[CONTRACT CHANGE — HUMAN DECISION REQUIRED]** must be resolved by a human before
> the designer proceeds. Questions marked **[DESIGN-STAGE]** may be resolved by the
> designer within the approved contract boundary.

---

### OQ-SA-1 — [CONTRACT CHANGE — HUMAN DECISION REQUIRED] Pattern read API response-shape change

**What needs deciding:** how is the sample alarm data surfaced in the Pattern Manager's
pattern read API? Three candidate shapes:

- **(a) New top-level field on `PatternView`:** `sampleAlarms: SampleAlarm[]` added to the
  `PatternView` object returned by `GET /patterns` and `GET /patterns/{patternId}`. Simple
  and co-located with other XAI metadata. Adds data to every list-response item even when
  the operator only wants summary data (pagination cost). Requires updating the frozen
  `PatternView` schema in the published `openapi.json`.

- **(b) Field nested under `supportingInstances[]`:** each `SupportingInstance` entry gains
  a `sampleAlarms: SampleAlarm[]` sub-array carrying the alarms for that occurrence. More
  granular (per-instance samples), but increases nesting and is harder to render in a flat
  UI table.

- **(c) Separate sub-endpoint:** `GET /patterns/{patternId}/sample-alarms` — a new endpoint
  that returns the sample alarms on demand (the operator or web-ui requests it only when
  expanding a pattern card). Keeps the main list response slim; requires an additional API
  call per pattern; new endpoint surface.

**Why a human must decide:** all three options change the Pattern Manager's published
OpenAPI surface — the contract that the web-ui and any other consumer builds against. Per
the golden rule: a change to a service's OpenAPI surface is a contract change requiring
`docs/architecture.md` + spec update and human approval. The designer must not silently
add a field or endpoint before this is approved.

**Linked GitHub issue:** see linked issue on this PR.

---

### OQ-SA-2 — [CONTRACT CHANGE — HUMAN DECISION REQUIRED] Data-capture mechanism and ownership

**The core architectural question:** how does the Pattern Manager obtain the per-alarm
sample data (`alarmId`, `alarmType`, `raisedAt`, `managedObjectId`, `perceivedSeverity`)?
The data exists in `transactions.clean` `alarms[]` at mining time. Below are the candidate
options with their trade-offs. **Do not pick one; this is a human decision.**

#### Option A — Pattern Miner enriches `PatternMinedEvent` with a bounded sample

The Pattern Miner holds the session-to-alarm mapping at mining time (it is the ONLY
component that does). It embeds a bounded sample of the member alarms (up to `K` alarms per
occurrence, or `K` alarms total per mined pattern) directly in `PatternMinedEvent`.
The Pattern Manager reads them from the event and persists them — no new consumer, no join.

**Trade-offs in favour:** the Miner holds the session-alarm mapping that no other component
has; this is the only lossless, zero-inference approach. The Pattern Manager requires no
new inbound data dependency. The join is trivial (data arrives with the event).

**Trade-offs against:** `PatternMinedEvent` is a frozen contract in `libs/event-model`. Adding
a `sampleAlarms[]` field (even optional for backward-compat) is a **contract change** —
requires updating the JSON Schema in `libs/event-model`, publishing a new schema version,
updating `docs/architecture.md`, and human approval before proceeding. Larger event payload
on `patterns.mined` (mitigated by bounding `K`). The Miner is described as "stateless —
emits and forgets"; embedding alarm detail does not break this principle (the Miner still
holds no persistent state — it captures from the in-flight session and embeds in the event),
but it adds richness to the event.

**Boundary for this option:** `sampleAlarms` would be an optional field on
`PatternMinedEvent` (absent means "no sample" — backward-compat with older miner versions).
Bounded to `K` alarms per event (configurable in the miner). Fields bounded by
`TransactionEvent.alarms[]` shape: `alarmId`, `alarmType`, `raisedAt`, `managedObjectId`,
`perceivedSeverity` — no new fields.

**Contract artefacts requiring change:** `libs/event-model` (`PatternMinedEvent.schema.json`
— add optional `sampleAlarms[]` field); `docs/architecture.md` (service table row for
pattern-miner); `services/pattern-miner/spec.md` (produces richer `PatternMinedEvent`).

---

#### Option B — Pattern Manager also consumes `transactions.clean` (or a queryable source)

The Pattern Manager subscribes to `transactions.clean` (currently: noise-filter → pattern-
miner only) or to a queryable read store derived from it, and attempts to reconstruct
member alarms by joining on `trailId` and window-time overlap with the pattern's
`provenance.sourceWindowId`.

**Trade-offs in favour:** keeps `PatternMinedEvent` unchanged. Pattern-miner remains simple.

**Trade-offs against:**
- **The join is inherently lossy.** The miner's `sourceWindowId` (`sw:<trailId>:<hash>`)
  is the miner's own session-window identifier — it does NOT correspond to any
  `transactions.clean` key (`transactionId`, `trailId`/`windowStart..windowEnd`). A
  downstream join on `trailId` + time-range overlap is a **heuristic** that may
  mis-attribute alarms across overlapping windows — especially common in dense storm periods
  when multiple overlapping transactions exist in the same trail/time-range.
- Pattern Manager gains a second Kafka consumer (or a new query dependency) — breaking its
  current single-input (`patterns.mined` only) simplicity. A new consumer or query client
  is a contract change to `docs/architecture.md`.
- The `transactions.clean` stream is high-volume (all learning-path alarms); the Pattern
  Manager does not need to process all of it, only the subset relevant to mined patterns.
  Indexing / windowing this stream is a significant design burden.
- If `transactions.clean` is consumed as a Kafka topic, the join must handle
  out-of-order arrival (pattern-mined arrives before or after the relevant transactions).

**Contract artefacts requiring change:** `docs/architecture.md` (pattern-manager row gains
`transactions.clean` as a new Kafka input or a new query integration point); this spec
(`Contract` section updated). This is a contract change requiring human approval.

---

#### Option C — A new (or extended) service owns a queryable "alarm-by-window" store

A service (new, or an extension of an existing service such as Noise Filter or a future
durable-alarm store) persists `transactions.clean` alarm data in a queryable form, and the
Pattern Manager (or the web-ui) queries it by `trailId` + time-range or `transactionId` to
retrieve alarms for a given pattern occurrence.

**Trade-offs in favour:** keeps `PatternMinedEvent` small; provides a general queryable
alarm corpus for P2 (useful beyond just pattern explainability).

**Trade-offs against:**
- Requires a **new service or a significant extension** of an existing one — new ownership,
  new store, new endpoint, new spec, new Dockerfile, new Compose entry; not trivially added
  to MVP scope.
- The join on `sourceWindowId` is still heuristic (the miner's sourceWindowId is not a
  valid query key for `transactions.clean` data, as described above). The query must be by
  `trailId` + window time-range, which is lossy for the same reason as Option B.
- A new service or store means new contract artefacts: a new entry in `docs/architecture.md`,
  a new spec, and human approval.
- A durable historical-alarm corpus is explicitly **deferred from MVP** per `architecture.md`
  ("Historical/learning-path alarms are not persisted by the Alarm Manager (or anywhere) for
  the MVP"); Option C would partially re-introduce this deferred capability.

**Contract artefacts requiring change:** `docs/architecture.md` (new service entry or
extended ownership); new spec for the new service/capability; human approval.

---

#### Option D — Defer; keep current window-reference evidence (near-term honest)

Keep `supportingInstances[]` as window-level provenance references only. Do not add per-
alarm detail in this spec iteration. The web-ui continues to display the honest "per-alarm
detail not yet served" note. Spec the capability (this document) but mark the per-alarm
sample implementation as phased, with delivery conditional on human approval of a mechanism
choice (one of Options A–C above).

**Trade-offs in favour:** zero contract change in this iteration; the operator-trust gap is
acknowledged and tracked; existing behaviour is preserved.

**Trade-offs against:** the explainability/trust gap remains open. Operators reviewing
patterns cannot see the concrete alarm evidence. This may be acceptable for the initial
P2 learning iteration but is a known UX limitation.

---

#### Key tension (make explicit for the human decision)

The Pattern Miner is **the only component that holds the session-to-alarm mapping** at the
moment it assigns a `sourceWindowId`. If the mapping is not captured at mining time (Option
A), reconstructing it downstream requires a heuristic join (Options B and C) that is
inherently lossy, particularly in storm scenarios with overlapping windows. The miner is
described as "stateless — emits and forgets" — but this is a design intent, not a technical
constraint. Embedding a bounded sample in the event (Option A) does not make the miner
stateful in the persistent sense; it adds per-event richness at the cost of a contract
change. The spec analyst's assessment is: **Option A is the only lossless path**, and its
cost is a well-scoped, bounded contract change to `PatternMinedEvent`. Options B and C are
architecturally heavier and produce an inferior result (lossy join). Option D is safe for
now but leaves the operator-trust gap open. This assessment is for the human's information;
the decision remains with the human.

---

### OQ-SA-3 — [DESIGN-STAGE, conditional] Pattern Miner spec and `PatternMinedEvent` change (if Option A is chosen)

If the human approves Option A: the `PatternMinedEvent` JSON Schema in
`libs/event-model` must be updated to add an optional `sampleAlarms[]` field. This requires:
- A contract-change PR into `main` updating `libs/event-model/java/build/schema-resources/
  schema/payloads/PatternMinedEvent.schema.json` (and the Python binding).
- An update to `services/pattern-miner/spec.md` stating the Pattern Miner populates
  `sampleAlarms[]` with up to `K` alarms per mined pattern occurrence, where `K` is a
  configurable value from environment.
- This spec's base `spec.md` (`patterns.mined` consume contract) updated to reflect the
  optional new field.

The designer must not proceed with the `PatternMinedEvent` change until the contract-change
PR is merged.

---

### OQ-SA-4 — [DESIGN-STAGE] Sample cap `K` — default value, per-pattern vs. per-occurrence

If the data-capture mechanism is approved: what default value of `K` is appropriate? Should
the cap be per-pattern (total alarms across all occurrences combined) or per-occurrence
(alarms from one representative occurrence)? For operator review purposes, one representative
occurrence (showing how the sequence played out in a single real event) is likely more useful
than a pool of alarms from many occurrences. The designer should recommend a default at
design time; the value must be configurable from environment. This is not a contract change.

---

### OQ-SA-5 — [DESIGN-STAGE] Which occurrence's alarms to sample

If multiple occurrences of a pattern exist (support > 1), which occurrence's alarms are
stored as the sample? Candidates: the first occurrence observed (simple), the occurrence
with the highest alarm count (most complete), or a random representative. This is a
designer-stage decision with no contract implication. The outcome should be documented in
`design.md`.
