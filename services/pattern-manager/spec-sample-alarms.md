# pattern-manager -- Enhancement Spec: Pattern Sample Alarm References

> **Status: DRAFT -- awaiting human approval.**
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
with a "per-alarm detail not yet served" note -- acknowledging the gap.

This spec closes that gap: for each discovered pattern, the Pattern Manager serves a
**bounded sample of the real member alarms** that the Pattern Miner observed when it mined
that pattern's supporting instances. Each sample alarm carries the fields already present in
`transactions.clean` `alarms[]`: `alarmId`, `alarmType`, `raisedAt` (timestamp),
`managedObjectId` (network node/object), and `perceivedSeverity`.

The **explainability / operator-trust rationale** is the primary driver: operators at a NOC
approving or rejecting an abstract alarm-type sequence must be able to see "which real alarms
on which real nodes at which times formed this pattern" before they act. Without this, the
"supporting instances" field is opaque provenance metadata, not decision-grade evidence.

> **Mechanism: RESOLVED -- Option A (issue #347).** The Pattern Miner embeds a bounded
> sample of member alarms into `PatternMinedEvent`; the Pattern Manager consumes, persists,
> and serves them. This is the only lossless path: the miner's `sourceWindowId`
> (`sw:<trailId>:<hash>`) is its own re-windowed session id and does NOT map to any
> `transactions.clean` key, so the miner is the ONLY component holding the session-to-alarm
> mapping at mining time. Downstream join approaches (Options B/C) are lossy due to
> mis-attribution in overlapping-window storms. See OQ-SA-2 below.

> **API shape: RESOLVED -- field on PatternView (issue #348).** `sampleAlarms[]` is a
> top-level field on `PatternView`, returned by `GET /patterns` and `GET /patterns/{patternId}`.
> Not a separate sub-endpoint. Bounded by configurable `K`. See OQ-SA-1 below.

---

## Scope

**In scope:**

- Extending the Pattern Manager's pattern read API to serve, per pattern, a **bounded
  representative sample** of real member alarms (up to `K` alarm records per pattern, where
  `K` is a configurable parameter -- no hard-coded threshold; sourced from environment config
  or Knowledge Service per platform convention).
- Each sample alarm record carries exactly the fields present in `transactions.clean`
  `alarms[]` at the time of mining:
  - `alarmId` (string -- the unique alarm identifier)
  - `alarmType` (string -- the canonical alarm-type vocabulary token, same join key used in
    pattern sequences; e.g. `PortDown`, `InterfaceDown`, `LinkDown`, `FiberFault`)
  - `raisedAt` (ISO-8601 UTC timestamp -- when the alarm was raised)
  - `managedObjectId` (string in the canonical `<objectType>:<id>` scheme -- the network
    node/object the alarm originated from)
  - `perceivedSeverity` (string -- X.733 severity)
- **Data-capture mechanism: Option A (RESOLVED).** The Pattern Miner embeds a bounded
  `sampleAlarms[]` array into `PatternMinedEvent` at mining time. The Pattern Manager reads
  these from the event on consume and persists them in the Pattern Store associated with the
  `patternId`. If `sampleAlarms[]` is absent (older miner version or no alarms in the
  window), no records are stored and the pattern is still persisted normally (backward-compat).
- **API response shape: field on PatternView (RESOLVED).** `sampleAlarms[]` is added as a
  top-level field on the `PatternView` object returned by `GET /patterns` and
  `GET /patterns/{patternId}`. Bounded to at most `K` entries. Field is always present in
  the response -- empty list `[]` when no sample has been captured.
- The sample is associated with the pattern's `supportingInstances` evidence, so the
  operator can see: "this pattern was observed N times; here are sample alarms from one or
  more of those occurrences."
- The fields exposed are bounded by what exists in `transactions.clean` `alarms[]` -- no new
  field is introduced; no inference, enrichment, or lookup is performed to add fields not in
  that source.
- All new data persisted by the Pattern Manager for this capability is stored in the
  Pattern Store (PostgreSQL, schema `pattern`) -- the Pattern Manager remains the single
  owner of pattern state.

**In scope (secondary -- related cleanup, lower priority):**

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
  store, which is explicitly deferred from MVP per `architecture.md`).
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
- **A separate sub-endpoint** for sample alarms (e.g. `GET /patterns/{patternId}/sample-alarms`)
  -- resolved against this option in OQ-SA-1; `sampleAlarms[]` is a field on `PatternView`.
- **Option B** (Pattern Manager consuming `transactions.clean` directly) and **Option C**
  (new queryable alarm-store service) -- both resolved against in OQ-SA-2; the miner's
  `sourceWindowId` is not a valid downstream join key, making both paths inherently lossy.
- Changes to the frozen `PatternDiscoveredEvent` or `PatternApprovedEvent` Kafka event
  schemas: sample alarms are NOT added to either event.
- Changes to `docs/architecture.md` without explicit human approval as part of the ordered
  contract-change sequence (see Contract section below).
- Adding new Kafka topics for this capability (no new topics required; Option A uses the
  existing `patterns.mined` topic with an enriched payload).

---

## Tasks (high-level)

These tasks describe the observable work the Pattern Manager performs in support of this
capability. The data-capture mechanism is Option A (RESOLVED): sample alarms arrive embedded
in `PatternMinedEvent` from the Pattern Miner.

1. **Receive and persist sample alarm records for each mined pattern.** When a new
   `PatternMinedEvent` is processed, read the optional `sampleAlarms[]` array embedded by
   the Pattern Miner and persist each record with `alarmId`, `alarmType`, `raisedAt`,
   `managedObjectId`, and `perceivedSeverity` in the Pattern Store, associated with the
   `patternId`. If `sampleAlarms[]` is absent (backward-compat case), the pattern is still
   persisted normally with no sample alarm records.

2. **Bound the stored sample.** Enforce the configurable limit `K` (alarms per pattern, or
   alarms per occurrence, per configuration) at ingest time. Alarm records beyond the cap
   for an existing pattern are not stored. The cap is read from environment/Knowledge config
   -- no hard-coded value.

3. **Serve sample alarms via the pattern read API.** Include the stored sample alarm
   records as `sampleAlarms[]` on the `PatternView` object in `GET /patterns` and
   `GET /patterns/{patternId}` responses. Each entry carries: `alarmId`, `alarmType`,
   `raisedAt` (ISO-8601 UTC), `managedObjectId` (`<objectType>:<id>`), `perceivedSeverity`.
   Bounded to at most `K` entries. The designer specifies and publishes the updated shape in
   the Pattern Manager's `openapi.json`.

4. **Handle absent sample gracefully.** When no sample alarms have been captured for a
   pattern (the consumed `PatternMinedEvent` carried no `sampleAlarms[]`, or the pattern
   predates the capability), the API returns `sampleAlarms` as an empty list `[]` -- not
   absent and not null -- so the web-ui can display "no sample alarms available" without
   special-casing a missing field.

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
| P1 -- Topology onboarding | No change from base spec. | Idle | -- |
| P2 -- Pattern learning | Active (same as base spec). Additionally: consumes the optional `sampleAlarms[]` field in `PatternMinedEvent`; persists sample alarm records in the Pattern Store; serves them as `sampleAlarms[]` on `PatternView` for operator review. | Active | In: `patterns.mined` (`PatternMinedEvent` -- now carrying optional `sampleAlarms[]`); Out: extended `PatternView` with `sampleAlarms[]` in `GET /patterns` and `GET /patterns/{patternId}` responses. |
| P3 -- Real-time correlation | No change from base spec. Sample alarms are pattern-review evidence only; not used by the Correlation Engine. | Passive | Serves: pattern read API (unchanged). `sampleAlarms[]` present in read responses; does not affect `patterns.approved` or `PatternApprovedEvent`. |

---

## Contract

### Required contract-change sequence (Option A -- human-gated, must be completed in order)

> These are the consequences of the RESOLVED Option A decision. Each artefact change must
> be approved and merged in the sequence below before the dependent stage proceeds. Do not
> begin design or build on any step until its prerequisites are merged.

**Step 1. `libs/event-model` -- `PatternMinedEvent.schema.json`**
Add an OPTIONAL, bounded `sampleAlarms[]` array of
`{alarmId, alarmType, raisedAt, managedObjectId, perceivedSeverity}`.
Backward-compatible (optional field; older miner versions omit it; pattern-manager treats
absence as empty sample). Lands in `main` as its own contract-change PR (same pattern as
the `anchorScenarioId` contract, PR #331).

**Step 2. `docs/architecture.md`**
Update the event-model section and the pattern-miner / pattern-manager service rows to
reflect that `PatternMinedEvent` now carries an optional `sampleAlarms[]` field.

**Step 3. `services/pattern-miner/spec.md`**
The Pattern Miner gains a new task: at mining time, embed a bounded `sampleAlarms[]` (up to
`K` alarms per mined pattern; `K` configurable from environment) into `PatternMinedEvent`.
The miner holds the session-to-alarm mapping via its consumed `transactions.clean` stream
and is the only component able to do this losslessly. The specific bounding and
representative-sampling rule (which `K` alarms to pick when more are available) is a
pattern-miner design detail.

**Step 4. pattern-manager `openapi.json` / `PatternView`**
`PatternView` gains `sampleAlarms: SampleAlarm[]` as a top-level field. The designer updates
and publishes the revised `openapi.json`; this is the contract surface that web-ui and any
other consumer builds against.

**Downstream unlock:** once steps 1-4 are complete, the web-ui can replace the current
"per-alarm detail not yet served" note with a real rendered evidence list, consuming the
`sampleAlarms[]` field from the existing pattern read API.

---

### Changes relative to the base `spec.md`

**Consumes (Kafka):**
- `patterns.mined` -- `PatternMinedEvent` -- **enriched (after Step 1 above is merged)**:
  carries an optional `sampleAlarms[]` array. Absent in events from older miner versions --
  treated as empty sample (backward-compat). No new Kafka topics.

**Produces (Kafka) -- unchanged from base spec:**
- `patterns.discovered` -- `PatternDiscoveredEvent` (unchanged; sample alarms NOT added)
- `patterns.approved` -- `PatternApprovedEvent` (unchanged; sample alarms NOT carried)

**APIs exposed:**
- `GET /patterns` and `GET /patterns/{patternId}` -- `PatternView` gains
  `sampleAlarms: SampleAlarm[]` as a top-level field (after Step 4 above is approved). Each
  `SampleAlarm` entry: `alarmId` (string), `alarmType` (string), `raisedAt` (ISO-8601 UTC
  string), `managedObjectId` (string, `<objectType>:<id>` format), `perceivedSeverity`
  (string). Field is always present -- empty list `[]` when no sample has been captured.
  Bounded to at most `K` entries.

**APIs/data consumed from other services -- unchanged from base spec.** Option A introduces
no new inbound API or Kafka dependency for the Pattern Manager. All sample alarm data
arrives embedded in `PatternMinedEvent` on the existing `patterns.mined` topic.

**Data owned (Pattern Store -- additive only):**
- Pattern Store (PostgreSQL, schema `pattern`) gains a new table or column(s) to persist
  sample alarm records per pattern. The Pattern Manager remains the single owner; this is
  an internal schema change, not a cross-service ownership change. Schema migration is
  Pattern Manager's own responsibility (Flyway, scoped to the `pattern` schema).

**Integration points (mock vs. real) -- unchanged for existing points.** No new outbound
integration point is introduced by Option A.

---

## Non-functional

- **Idempotency key:** unchanged from base spec (`eventId` for event dedup). Sample alarm
  persistence must also be idempotent: re-processing the same `PatternMinedEvent` (same
  `eventId`) must not duplicate sample alarm records in the Pattern Store.
- **Bounded store:** the sample is capped at `K` alarms per pattern (configurable via
  environment or Knowledge Service; no hard-coded value; suggested default is a
  design-stage decision -- see OQ-SA-4). The cap prevents unbounded growth in the Pattern
  Store from large mining windows.
- **Config:** the sample cap `K` (and whether it is per-pattern or per-occurrence) is
  supplied via environment configuration or the Knowledge Service -- never hard-coded.
- **Observability:** unchanged from base spec (`/health`, `/metrics`, structured JSON logs).
  Sample-alarm ingestion failures are logged at WARN with `patternId` and source context.
- **API contract:** the Pattern Manager's published OpenAPI 3.1
  (`services/pattern-manager/openapi.json`) is extended to include `sampleAlarms[]` on
  `PatternView`. The updated `openapi.json` is the authoritative HTTP surface and drives
  contract/unit tests. This is a contract change requiring human approval (Step 4 in the
  sequence above) before it is published.
- **Error handling:** if `sampleAlarms[]` in a `PatternMinedEvent` is malformed or fails to
  persist for a particular pattern, the pattern itself is still persisted and served (sample
  absence is non-fatal); the API returns `sampleAlarms: []` for that pattern. The failure
  is logged at WARN.
- **Test framework:** unchanged -- JUnit 5 (unit and contract tests), Testcontainers for
  integration.

---

## Acceptance criteria

Each criterion maps to a single unit test.

### Core capability

**AC-SA-1.** Given a pattern persisted in the Pattern Store for which sample alarm records
have been captured (sourced from `sampleAlarms[]` in a consumed `PatternMinedEvent`), a
`GET /patterns/{patternId}` response includes a non-empty `sampleAlarms` array on
`PatternView`; each entry carries `alarmId`, `alarmType`, `raisedAt`, `managedObjectId`,
and `perceivedSeverity` -- all non-null strings with `raisedAt` in ISO-8601 UTC format and
`managedObjectId` matching the `<objectType>:<id>` scheme. (JUnit 5 -- Pattern Store fixture
with stored sample alarms; call `GET /patterns/{patternId}`; assert field presence and
schema conformance on each entry)

**AC-SA-2.** Given a pattern for which sample alarm records have been captured, the
`alarmType` value in each sample alarm record matches a value that appears in the pattern's
`sequence[]` field (confirming the sample alarms are member alarms of the pattern, not
unrelated records). (JUnit 5 -- fixture: persist sample alarms whose `alarmType` tokens
overlap the pattern's sequence; assert each returned sample alarm's `alarmType` is a member
of `sequence[]`)

**AC-SA-3.** Given a pattern for which sample alarm records have been captured, the
`managedObjectId` in each returned sample alarm record conforms to the canonical
`<objectType>:<id>` scheme (non-empty string, contains exactly one colon, `objectType`
matches `^[A-Za-z][A-Za-z0-9]*$`, and `id` is non-empty). (JUnit 5 -- assert format
constraint on each sample alarm's `managedObjectId` in the API response)

**AC-SA-4.** Given a pattern for which no sample alarm records have been captured (the
consumed `PatternMinedEvent` carried no `sampleAlarms[]`, or the pattern predates the
capability), a `GET /patterns/{patternId}` response includes `sampleAlarms` as an empty
list `[]` -- not absent and not null. (JUnit 5 -- fixture: pattern with zero sample alarms
in Pattern Store; assert field is present and is an empty array)

**AC-SA-5.** Given a pattern for which sample alarm records have been captured, a
`GET /patterns` (list) response includes `sampleAlarms` as a top-level field on the
`PatternView` item for that pattern, with the same content as `GET /patterns/{patternId}`.
The field is present on every `PatternView` item in the list response, including those with
an empty sample. (JUnit 5 -- list response fixture with two patterns: one with samples, one
without; assert `sampleAlarms` field presence and content on both items)

### Option A event-driven ingest path

**AC-SA-5a.** Given a `PatternMinedEvent` arriving on `patterns.mined` that carries a
non-empty `sampleAlarms[]` array, the Pattern Manager persists each alarm record from that
array in the Pattern Store associated with the `patternId`. A subsequent
`GET /patterns/{patternId}` returns those records in `sampleAlarms[]`. (JUnit 5 -- publish
a synthetic `PatternMinedEvent` with `sampleAlarms`; consume and process; assert Pattern
Store contains the records; call read API and assert response)

**AC-SA-5b.** Given a `PatternMinedEvent` that carries NO `sampleAlarms[]` field (absent --
older miner version, backward-compat case), the Pattern Manager processes the event normally,
persists the pattern, and the Pattern Store contains zero sample alarm records for that
pattern. `GET /patterns/{patternId}` returns `sampleAlarms: []`. (JUnit 5 -- publish event
without `sampleAlarms`; assert pattern persisted; assert `sampleAlarms: []` in response)

### Bounded sample

**AC-SA-6.** Given a configurable sample cap `K = 3` (set via environment/config, not
hard-coded) and a `PatternMinedEvent` that carries 5 alarm entries in `sampleAlarms[]`,
the Pattern Store retains at most 3 sample alarm records for that pattern;
`GET /patterns/{patternId}` returns at most 3 sample alarms. (JUnit 5 -- inject `K=3` via
config; publish `PatternMinedEvent` with 5 `sampleAlarms` entries; assert stored count <= 3
and API response count <= 3)

**AC-SA-7.** Given the same `PatternMinedEvent` processed twice (same `eventId`, simulating
Kafka redelivery), the Pattern Store contains the same sample alarm records after both passes
as after the first -- no duplicates are created. (JUnit 5 -- process the same event twice;
assert sample alarm count in Pattern Store equals the count after the first pass)

### Related cleanup -- `anchorScenarioId` reconciliation

**AC-SA-8.** Given a `PatternMinedEvent` where `provenance.anchorScenarioId` is populated
(non-null) but no codebook match would otherwise be found by the standard codebook-override
logic, the Pattern Manager propagates `anchorScenarioId` to the pattern's `codebookMatchId`
field; a subsequent `GET /patterns/{patternId}` returns `codebookMatchId` equal to the
`anchorScenarioId` value from the provenance. (JUnit 5 -- mock Codebook Generator returning
no match; `PatternMinedEvent` fixture with `provenance.anchorScenarioId = "scenario-42"`;
assert persisted `codebookMatchId = "scenario-42"` and field present in read API response)

### API contract conformance

**AC-SA-9.** A `GET /patterns/{patternId}` response (with sample alarms present) validates
successfully against the published and updated `services/pattern-manager/openapi.json`
schema, including the `sampleAlarms[]` field and `SampleAlarm` item shape. (JUnit 5 --
schema-validation contract test against the published OpenAPI spec)

**AC-SA-10.** A `GET /patterns` list response validates successfully against the published
and updated `services/pattern-manager/openapi.json` schema, including the `sampleAlarms[]`
field on each `PatternView` item. (JUnit 5 -- schema-validation contract test)

---

## Open questions

> Questions marked **[RESOLVED]** have been decided by a human and are locked in -- the
> rationale is recorded for the designer's reference. Questions marked **[DESIGN-STAGE]**
> may be resolved by the designer within the approved contract boundary.

---

### OQ-SA-2 -- [RESOLVED: Option A -- issue #347] Data-capture mechanism

**Decision:** The Pattern Miner embeds a BOUNDED sample of member alarms into
`PatternMinedEvent`; the Pattern Manager persists and serves them. Decided by human.

**Rationale (on record):** The miner's `sourceWindowId` (`sw:<trailId>:<hash>`) is its own
re-windowed session id and does NOT map to any `transactions.clean` key (`transactionId`,
`trailId`/window range). The Pattern Miner is the ONLY component holding the
session-to-alarm mapping at mining time. Downstream join approaches (Option B: Pattern
Manager consuming `transactions.clean`; Option C: new queryable alarm-store service) both
require joining on `sourceWindowId` against a key that does not exist in `transactions.clean`,
making them inherently lossy -- particularly in dense storm periods with overlapping windows
where mis-attribution is most likely. Option A is the only LOSSLESS path. Options B and C
were additionally rejected: Option B adds a high-volume Kafka consumer to the Pattern Manager
with no reliable join key; Option C reintroduces a durable historical-alarm corpus explicitly
deferred from MVP. Option A's cost is a well-scoped, backward-compatible contract change to
`PatternMinedEvent` (optional field).

**Consequences locked in:**
- `PatternMinedEvent` gains an optional `sampleAlarms[]` field (backward-compatible).
- The Pattern Manager reads `sampleAlarms[]` from the consumed event and persists it.
- No new Kafka topics; no new inbound API or data dependency for the Pattern Manager.
- The ordered contract-change sequence (see Contract section) must complete before design
  and build begin.

---

### OQ-SA-1 -- [RESOLVED: field on PatternView -- issue #348] Pattern read API response shape

**Decision:** `sampleAlarms[]` is exposed as a top-level field on `PatternView`, returned
by both `GET /patterns` and `GET /patterns/{patternId}`. NOT a separate sub-endpoint.
Bounded by configurable `K` (no hard-coded threshold; sourced from environment config or
Knowledge Service). Decided by human.

**Rationale (on record):** A top-level field on `PatternView` is the simplest co-location
with other pattern XAI metadata and minimises web-ui call complexity (one API call returns
the full pattern with its evidence). The `K` bound limits pagination cost on the list
endpoint. A sub-endpoint (option c from the original analysis) would require an extra
round-trip per pattern expand; nesting under `supportingInstances[]` (option b) increases
nesting without adding operator value for the initial MVP explainability use-case.

**Consequences locked in:**
- `PatternView` in `openapi.json` gains `sampleAlarms: SampleAlarm[]` as a top-level field.
- Field is present on every `PatternView` item -- empty list `[]` when no sample captured.
- This is a contract change to the Pattern Manager's published OpenAPI surface (Step 4 in
  the ordered sequence) requiring human approval before the designer publishes it.

---

### OQ-SA-3 -- [RESOLVED by OQ-SA-2] Contract artefacts for Option A

Resolved by the OQ-SA-2 decision. The required contract artefact changes are the four-step
ordered sequence documented in the Contract section above (libs/event-model schema ->
docs/architecture.md -> pattern-miner spec -> pattern-manager openapi.json). No further
decision needed here.

---

### OQ-SA-4 -- [DESIGN-STAGE] Sample cap `K` -- default value, per-pattern vs. per-occurrence

What default value of `K` is appropriate? Should the cap be per-pattern (total alarms across
all occurrences combined) or per-occurrence (alarms from one representative occurrence)? For
operator review, one representative occurrence (showing how the sequence played out in a
single real event) is likely more useful than a pool from many occurrences. The designer
recommends a default at design time; the value must be configurable from environment. This
is not a contract change.

---

### OQ-SA-5 -- [DESIGN-STAGE] Which occurrence's alarms to sample (pattern-miner design detail)

If multiple occurrences of a pattern exist (support > 1), which occurrence's alarms does the
Pattern Miner include in `sampleAlarms[]`? Candidates: first occurrence observed (simple),
occurrence with the highest alarm count (most complete), or a random representative. This is
a designer-stage decision for the pattern-miner's `design.md` with no contract implication.

---

### Related -- `anchorScenarioId` / `codebookMatchId` inconsistency (noted, not an AC blocker)

The inconsistency where `occurrence.anchorScenarioId` is populated while the top-level
`codebookMatchId` is null is addressed as AC-SA-8 (a "should", not a blocking "must"). It
is a Pattern Manager internal concern requiring no contract change and should be addressed
the next time pattern-manager is substantively touched. It is not a blocker for the primary
ACs of this spec.
