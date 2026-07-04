# pattern-manager -- Enhancement Spec: Unexplained-Pattern Signature Fold (Cross-Trail Deduplication)

> **Status: DRAFT -- awaiting human approval.**
> This is an enhancement spec, not a replacement of the base `spec.md`. It describes a
> discrete, bounded correctness fix to unexplained-pattern consolidation. All existing scope,
> contract, and acceptance criteria in the base `spec.md` (and `spec-sample-alarms.md`) remain
> in force. This spec evolves the identity rule and aggregation behaviour for the unexplained
> path only; the anchored path is unchanged.

---

## Purpose

The web-ui /patterns list shows duplicate pattern rows: the same ordered alarm-type sequence
(e.g. `IPLinkDown -> LinkDown -> LinkBundleDegraded`) appears as many separate rows -- one per
trail, per mining window occurrence. Live evidence: `IPLinkDown->LinkDown->LinkBundleDegraded`
exists as 12 separate pattern rows across 11 trails; other sequences are similarly duplicated.
All are true duplicates of the same cascade shape.

The root cause (confirmed in code and live DB): the unexplained path of
`PatternConsolidationService.persistUnexplained` uses a per-occurrence identity:
`UuidV5.perEventIdentity(trailId, sequence, sourceWindowId, snapshotId)`. Because
`sourceWindowId` is a per-occurrence identifier (a different window hash each time the same
sequence recurs) and `trailId` is also in the key, each recurrence of the same cascade
shape -- and the same cascade on different trails -- mints a new `patternId` and a new row.
The existing `persistUnexplained` also treats an existing row as an idempotent no-op: it does
NOT aggregate or count across occurrences. The result is N rows for N occurrences, with no
popularity or impact signal.

The anchored path already solves this correctly: `UuidV5.anchorIdentity(domain, snapshotId,
codebookVersion, anchorScenarioId)` folds cross-trail and across sub-runs, and the fold
aggregates occurrence counts. This fix makes the unexplained path consistent with the anchored
path.

The operator requirement: "the same pattern should not be a separate pattern -- capture a
count/popularity metric on the EXISTING pattern instead. The significance of a pattern is
that it occurs across trails -- capture metrics indicating extent of occurrence and impact."

---

## Scope

**In scope:**

- Changing the **identity function** for unexplained patterns from a per-occurrence key
  `(trailId, sequence, sourceWindowId, snapshotId)` to a **cascade signature key**
  `(sequence, domain, snapshotId)`. The cascade signature is defined as:
  - **`sequence`**: the ordered list of `alarmType` tokens as produced by the Pattern Miner --
    tokens are joined in order with a canonical separator; two signatures are identical if and
    only if their ordered sequences are identical (repeats and position are significant; no
    normalization such as collapsing consecutive repeats is applied). The exact token-join
    format (separator, encoding) is a designer decision, but the semantic rule -- ordered
    sequence with repeats significant -- is fixed by this spec (see OQ-SF-2: RESOLVED/DEFERRED).
  - **`domain`**: the domain identifier (carried on `PatternMinedEvent.provenance` or the
    equivalent enriched-pattern field) -- scopes the signature to one domain's alarm-type
    vocabulary.
  - **`snapshotId`**: the topology snapshot version (carried on
    `PatternMinedEvent.provenance.snapshotId`) -- re-mints identity when the topology snapshot
    changes, consistent with the anchored path's scoping.
  - `trailId` and `sourceWindowId` are **dropped from the unexplained identity key**. The same
    cascade shape -- regardless of which trail it was mined from and which mining window it
    came from -- maps to the same `patternId`.

- On a **first occurrence** of a cascade signature (no row exists yet): persist a new draft
  pattern row initialising `occurrenceCount = 1`, `instanceCount` from that event's mined
  support count, `trailCount = 1` (the contributing trail), `firstSeen` and `lastSeen` set to
  the event timestamp; emit one `PatternDiscoveredEvent`; record the contributing event and
  the contributing `trailId` in the pattern's distinct-trail set (see persistence note below).

- On a **subsequent occurrence** of the same cascade signature (a row already exists):
  **aggregate -- do not create a new row**. The aggregation mirrors the anchored fold:
  - **`occurrenceCount`**: increment by 1 (counts the number of mined occurrences folded in).
  - **`instanceCount`** (total member-alarm volume): increment by the new occurrence's
    `instanceCount` (i.e. sum of mined support counts across all contributing occurrences).
    This is the total number of individual alarm instances across all folded occurrences,
    distinct from `occurrenceCount` which counts occurrences (events), not alarms.
  - **`trailCount`**: the count of distinct `trailId` values from all contributing occurrences
    (including the current one). To maintain this, the pattern must track the distinct set of
    `trailId` values that have contributed (the `trailId` is carried on every
    `PatternMinedEvent` even for cross-trail folds). The designer picks the persistence
    mechanism (e.g. a set column, a child table, or an HLL counter), but the spec requires
    `trailCount` to reflect the count of DISTINCT contributing trails.
  - **`firstSeen`**: unchanged (retains the timestamp of the earliest contributing occurrence).
  - **`lastSeen`**: bumped to the timestamp of the current occurrence.
  - Support, confidence, lift: occurrence-weighted mean (same formula as the anchored fold's
    `PatternAggregator.weightedMean`).
  - Timing: combine using the anchored fold's `PatternAggregator.combineTiming`; recompute
    `sessionWindow` from the combined timing (deterministic, same deriver).
  - Supporting instances: union (dedup on `sourceWindowId`), same as the anchored fold.
  - Sample alarms: **NOT updated** -- the pattern keeps its bounded sample from the first
    contributing occurrence (fold-keeps-first rule, consistent with
    `spec-sample-alarms.md` and the anchored fold's existing DA-1 comment). Subsequent
    occurrences do not append or replace the sample.
  - `updatedAt`: bumped to the time of the fold.
  - Representative sequence: unchanged (the first contributor's sequence is already the
    canonical one; no replacement logic needed since the signature guarantees identical
    sequences).
  - **Emit no event** for a fold (same as the anchored fold behaviour).

- **Fold guard / replay safety**: a contributing-event guard identical to the anchored path
  -- `INSERT ... ON CONFLICT (event_id) DO NOTHING` on a `contributing_event` (or equivalent
  dedup table) before aggregating -- ensures a re-delivered `PatternMinedEvent` with the same
  `eventId` does not double-count. Only genuinely new occurrences (new `eventId`) increment
  `occurrenceCount`, `instanceCount`, `trailCount`, or `lastSeen`. The existing
  `processed_event` / `contributing_event` mechanism applies; no new dedup mechanism is
  introduced.

- **Row-lock serialization**: the fold acquires a `SELECT ... FOR UPDATE` row lock on the
  existing pattern row before aggregating, preventing concurrent fold races on the same
  signature -- same serialization approach as the anchored fold.

- **Impact / extent metrics on PatternView -- additive read-API contract change (human-approved
  via issue #357):** The following fields are added to `PatternView` and persisted on the
  Pattern Store row. They are populated for BOTH unexplained (signature-folded) AND anchored
  patterns for consistency. This is an additive change to the published OpenAPI surface;
  the designer regenerates `openapi.json` as part of this work. The Kafka topics and
  `PatternMinedEvent` / `PatternDiscoveredEvent` / `PatternApprovedEvent` schemas are
  NOT changed.

  | Field | Type | Meaning |
  |---|---|---|
  | `occurrenceCount` | integer | Number of mined occurrences (distinct `PatternMinedEvent` eventIds) folded into this pattern. Counts events, not alarms. |
  | `trailCount` | integer | Number of DISTINCT trails from which the signature has been observed (the key spatial-spread / cross-trail significance metric). |
  | `firstSeen` | timestamp (ISO-8601) | Timestamp of the first occurrence folded (earliest contributing event). |
  | `lastSeen` | timestamp (ISO-8601) | Timestamp of the most recent occurrence folded; bumped on each fold. |

  The existing **`instanceCount`** field is retained with its current meaning: total number of
  member alarm instances (sum of mined support counts across all contributing occurrences;
  the total member-alarm volume). Its description in `openapi.json` is updated to state "total
  number of individual alarm instances across all folded occurrences (sum of per-occurrence
  mined support counts); see also occurrenceCount for the number of distinct occurrences
  folded."

  For **anchored patterns**: `occurrenceCount`, `trailCount`, `firstSeen`, `lastSeen` are
  populated consistently using the same fold semantics. The anchored consolidation path
  (`consolidateAnchored`) is extended to maintain and expose these fields; the anchored
  identity and aggregation logic are otherwise unchanged.

- **Persistence note for `trailCount`:** to compute `trailCount`, the Pattern Store must
  track the set of distinct `trailId` values that have contributed to a pattern. The designer
  specifies the mechanism (e.g. a `pattern_trail` association table, an array/set column, or
  an approximate counter), but the spec requires:
  - On fold, the contributing `trailId` is recorded (if not already recorded for this pattern).
  - `trailCount` is the cardinality of that distinct set.
  - Idempotent redelivery (same `eventId`) does NOT add the `trailId` again (the fold guard
    blocks the entire aggregate step including trail-set update).

- **One-time Flyway collapse migration for existing duplicate rows (human-approved via issue
  #356):** A Flyway migration collapses existing duplicate unexplained-pattern rows that share
  the same `(sequence, domain, snapshotId)` (the legacy per-occurrence identity produced
  multiple rows for the same cascade shape). The migration:
  - Groups existing unexplained rows by `(sequence, domain, snapshotId)`.
  - For each group: keeps ONE canonical row (the earliest by `createdAt`); sums
    `occurrenceCount` and `instanceCount` across the group; derives `trailCount` from the
    count of distinct `trailId` values found in the collapsed rows' contributing-event records
    (or from any `trailId` column available on those rows); sets `firstSeen = MIN(createdAt)`
    and `lastSeen = MAX(updatedAt)`; keeps the first row's sample alarms (fold-keeps-first);
    deletes the redundant rows (cascade-deleting child records).
  - The designer specifies the exact SQL and Flyway migration version. The migration is
    idempotent: re-running it on an already-collapsed store is a no-op.
  - For the development/test environment, the store will be cleared and re-mined (which
    produces the correct result from the new fold logic without needing the migration). The
    migration is the production upgrade path.

- The anchored consolidation path (`consolidateAnchored`) is extended only to populate the
  four new impact-metrics fields; its identity, fold logic, and event emission are unchanged.

- Updating the Pattern Manager's published `openapi.json` with the four new `PatternView`
  fields and the updated `instanceCount` description (human-approved additive contract change).

- Updating the Pattern Manager's structured logs to include the fold outcome
  (`action=fold` vs `action=create`) and the contributing signature for unexplained folds,
  at the same log level as the anchored fold. On fold, log the updated `occurrenceCount`,
  `trailCount`, and `lastSeen`.

**In scope (operational note -- forward-only + migration):**

- The identity fix applies to newly consumed `PatternMinedEvent` messages from the point of
  deployment forward. Existing duplicate rows are collapsed by the one-time Flyway migration
  described above (production path) or by clearing and re-mining (dev/test path).

---

## Out of scope

- Changing the anchored consolidation path identity, fold logic, or event emission -- only the
  four new impact-metric fields are added to the anchored path for consistency.
- Changing `PatternMinedEvent`, `PatternDiscoveredEvent`, or `PatternApprovedEvent` Kafka
  event schemas -- this is an internal Pattern Store identity/aggregation change and an
  additive read-API change; no event-model contract change is required.
- Changing the Kafka topics consumed or produced -- `patterns.mined`, `patterns.discovered`,
  `patterns.approved` are unchanged.
- Cross-snapshot fold: patterns from different `snapshotId` values are intentionally distinct
  (consistent with the anchored path's scoping). If a new topology snapshot is loaded, new
  pattern rows are created for that snapshot; folding across snapshots is out of scope for MVP.
- Cross-domain fold: the signature is scoped to a single domain; cross-domain folding is out
  of scope.
- Signature normalization (e.g. collapsing consecutive repeats) -- explicitly deferred; see
  OQ-SF-2 resolved/deferred note below.
- Mining-algorithm changes -- owned by Pattern Miner; the Pattern Manager receives
  `PatternMinedEvent` and folds it.

---

## Tasks (high-level)

1. **Derive the cascade signature identity for unexplained patterns.** For a consumed
   `PatternMinedEvent` where `anchorScenarioId` is null/absent, compute the pattern identity
   as a deterministic UUIDv5 (or equivalent stable hash) over `(sequence, domain, snapshotId)`
   -- the cascade signature key. This replaces the previous `perEventIdentity` key that
   included `trailId` and `sourceWindowId`.

2. **Fold an occurrence into an existing signature row.** If a Pattern Store row already
   exists for the computed cascade signature identity: acquire the row lock; apply the
   contributing-event fold guard (dedup on `eventId`); if the guard passes, aggregate the
   occurrence's metrics into the existing row: increment `occurrenceCount` by 1; increment
   `instanceCount` by the event's mined support count; record the contributing `trailId` in
   the distinct-trail set and update `trailCount`; bump `lastSeen`; update
   support/confidence/lift (occurrence-weighted mean), timing (combined, then recompute
   `sessionWindow`), supporting instances (union); bump `updatedAt`; emit no event. If the
   fold guard detects a replay (same `eventId` already recorded), return no-op without
   updating any field.

3. **Create a new signature row for a first occurrence.** If no Pattern Store row exists for
   the cascade signature identity: persist a new draft pattern row (same enrichment pipeline
   as before -- RCA, structural validation, codebook reconciliation, XAI) initialising
   `occurrenceCount = 1`, `instanceCount` from the event's mined support count, `trailCount = 1`,
   `firstSeen` and `lastSeen` from the event timestamp; record the contributing event and the
   contributing `trailId`; emit one `PatternDiscoveredEvent`. Sample alarms (if present in the
   event) are persisted on create and never replaced on subsequent folds.

4. **Ensure replay safety for the unexplained fold.** Guarantee that re-processing the same
   `PatternMinedEvent` (same `eventId`) on an existing signature row does not increment
   `occurrenceCount`, `instanceCount`, or `trailCount` and does not add the `trailId` again:
   the fold guard (contributing-event dedup) must block the entire aggregate step on replay,
   same as the anchored path.

5. **Extend the anchored fold to populate impact-metric fields.** For patterns consolidated
   via the anchored path, populate `occurrenceCount`, `trailCount`, `firstSeen`, and `lastSeen`
   using the same fold semantics. The anchored identity and event-emission logic are otherwise
   unchanged.

6. **Deliver the one-time Flyway collapse migration.** Provide a Flyway migration that
   collapses existing duplicate unexplained rows grouped by `(sequence, domain, snapshotId)`:
   sum `occurrenceCount`/`instanceCount`, derive `trailCount` from the distinct trails of
   the collapsed rows, set `firstSeen = MIN(createdAt)` / `lastSeen = MAX(updatedAt)`, keep
   the first row's sample alarms, delete redundant rows with cascade. Migration must be
   idempotent.

7. **Surface the impact-metric fields via the read API.** Extend `PatternView` with
   `occurrenceCount`, `trailCount`, `firstSeen`, `lastSeen` (human-approved additive
   contract change); update the `instanceCount` description; regenerate and check in
   `openapi.json`.

---

## Phase applicability

This enhancement is active only in P2 (Pattern learning). It is consistent with the canonical
phase map in `architecture.md` for the Pattern Manager service and does not change P1 or P3
behaviour.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 -- Topology onboarding | No change from base spec. | Idle | -- |
| P2 -- Pattern learning | Active (same as base spec). Additionally: the unexplained consolidation path now folds cross-trail occurrences of the same cascade signature into one row, accumulating `occurrenceCount`, `instanceCount`, `trailCount`, `firstSeen`, and `lastSeen` rather than creating duplicate rows. | Active | In: `patterns.mined` (`PatternMinedEvent`) -- unchanged. Out: `patterns.discovered` (one per UNIQUE cascade signature, not one per occurrence); `patterns.approved` -- unchanged. Serves: read API with impact-metric fields on `PatternView`. |
| P3 -- Real-time correlation | No change from base spec. The Pattern Store has fewer rows (one per signature rather than N per recurrence); each carries accumulated impact metrics. | Passive | Serves: pattern read API -- additive fields on `PatternView`, fewer rows, richer occurrence/trail metrics. |

---

## Contract

### Contract change: additive PatternView / openapi.json fields (human-approved, issue #357)

This fix is primarily internal to the Pattern Manager's consolidation logic and Pattern Store,
with one approved additive read-API change.

- **Consumes (Kafka) -- unchanged:** `patterns.mined` (`PatternMinedEvent`). No new fields
  consumed; no new topics.
- **Produces (Kafka) -- unchanged:** `patterns.discovered` (`PatternDiscoveredEvent`) and
  `patterns.approved` (`PatternApprovedEvent`). Schemas unchanged. The observable change: for
  unexplained patterns, `PatternDiscoveredEvent` is emitted once per unique cascade signature
  (on first occurrence) rather than once per mined occurrence. This is a reduction in event
  volume, not a schema change.
- **APIs exposed -- additive change to PatternView (human-approved):** `GET /patterns` and
  `GET /patterns/{patternId}` return `PatternView` responses. Four new fields are added to
  `PatternView` and published in `openapi.json` (regenerated by designer):
  `occurrenceCount` (integer), `trailCount` (integer), `firstSeen` (ISO-8601 timestamp),
  `lastSeen` (ISO-8601 timestamp). The existing `instanceCount` field is retained; its
  description is updated. This additive change is approved per issue #357. It does NOT touch
  `PatternMinedEvent`, `PatternDiscoveredEvent`, `PatternApprovedEvent`, or any Kafka topic.
  The event-model contract (`libs/event-model`) is unchanged; this is a pattern-manager
  read-API (openapi) change only.
- **APIs/data consumed from other services -- unchanged.** Topology, Codebook Generator, and
  Knowledge Service integration points are unchanged.
- **Integration points (mock vs. real) -- unchanged.**
- **Data owned -- Pattern Store (PostgreSQL, schema `pattern`) -- internal change + migration.**
  The identity function, fold logic, and stored fields change. The schema migration (Flyway,
  scoped to the `pattern` schema) adds the four new columns and the one-time collapse migration.
  The designer specifies the DDL. No cross-service data ownership change.

---

## Non-functional

- **Idempotency key:** `eventId` (same as base spec). The fold guard on `eventId` via the
  contributing-event dedup mechanism makes the unexplained fold replay-safe: re-processing the
  same `eventId` does not increment `occurrenceCount`, `instanceCount`, or `trailCount`, and
  does not add the `trailId` to the distinct-trail set again.
- **Config:** no new configurable parameters introduced by this fix. The cascade signature
  key components (`sequence`, `domain`, `snapshotId`) are intrinsic to the event -- no
  thresholds or knobs.
- **Observability:** structured JSON log at INFO for each unexplained fold:
  `action=create|fold|noop`, `patternId`, `signature` (the ordered sequence tokens, or a
  hash of them), `domain`, `snapshotId`, and on fold: the updated `occurrenceCount`,
  `trailCount`, and `lastSeen`. Consistent with the existing anchored-path logging.
- **Error handling:** unchanged from base spec. A failed fold rolls back the transaction; the
  Kafka offset is not committed; the event is redelivered and re-folded safely (idempotent).
  Poison messages route to `patterns.mined.dlq` as before.
- **API contract:** the published `openapi.json` is the authoritative HTTP surface. Adding
  `occurrenceCount`, `trailCount`, `firstSeen`, `lastSeen` to `PatternView` is an additive
  contract change (human-approved, issue #357). The designer regenerates `openapi.json` and
  checks it in. No field is removed or renamed; existing consumers are unaffected by the
  additive fields. The event-model contract and Kafka schemas are NOT changed.
- **Test framework:** JUnit 5 (unit and contract tests), Testcontainers for integration --
  per CLAUDE.md Java cohort standard.

---

## Acceptance criteria

Each criterion maps to a single unit test.

**AC-SF-1 (signature identity -- same sequence, different trails).** Given two
`PatternMinedEvent` messages with the same ordered `sequence` (e.g.
`["IPLinkDown","LinkDown","LinkBundleDegraded"]`), the same `domain`, and the same
`snapshotId`, but different `trailId` values and different `sourceWindowId` values (different
mining windows), the Pattern Store contains exactly ONE pattern row after both events are
processed, and that row's `instanceCount` equals the sum of the two events' `instanceCount`
values.
(JUnit 5 -- publish two synthetic events differing only in `trailId`/`sourceWindowId`; process
both; assert one row; assert `instanceCount = sum`.)

**AC-SF-2 (signature identity -- same sequence, different windows, same trail).** Given two
`PatternMinedEvent` messages with the same `sequence`, `domain`, `snapshotId`, and `trailId`,
but different `sourceWindowId` values (two separate mining windows for the same trail), the
Pattern Store contains exactly ONE pattern row after both events are processed, and that row's
`instanceCount` equals the sum of both events' `instanceCount` values.
(JUnit 5 -- same as AC-SF-1 but with same `trailId`; assert one row and summed count.)

**AC-SF-3 (different sequences = different rows).** Given two `PatternMinedEvent` messages
with different ordered sequences (e.g. `["IPLinkDown","LinkDown"]` vs.
`["IPLinkDown","LinkDown","LinkBundleDegraded"]`), the same `domain`, and the same
`snapshotId`, the Pattern Store contains TWO separate pattern rows after both events are
processed (distinct `patternId` values).
(JUnit 5 -- assert two rows with distinct `patternId`; assert no folding between different
sequences.)

**AC-SF-4 (sequence order is significant).** Given two `PatternMinedEvent` messages with
the same alarm-type tokens but in different order (e.g. `["A","B","C"]` vs. `["B","A","C"]`),
the same `domain`, and the same `snapshotId`, the Pattern Store contains TWO separate rows.
(JUnit 5 -- assert two rows; confirms order-significant identity.)

**AC-SF-5 (sequence repeats are significant).** Given two `PatternMinedEvent` messages where
one sequence is `["A","B","A"]` and the other is `["A","B"]`, the same `domain`, and the same
`snapshotId`, the Pattern Store contains TWO separate rows.
(JUnit 5 -- assert two rows; confirms repeats are not collapsed.)

**AC-SF-6 (fold aggregates occurrence-weighted metrics).** Given two `PatternMinedEvent`
messages with the same cascade signature (same `sequence`, `domain`, `snapshotId`), the first
with `instanceCount=3, support=0.6` and the second with `instanceCount=2, support=0.4`, after
both are processed the single Pattern Store row has `instanceCount = 5` and `support` equal
to the occurrence-weighted mean `(0.6*3 + 0.4*2) / (3+2) = 0.52` (within floating-point
tolerance).
(JUnit 5 -- assert one row; assert `instanceCount=5`; assert `support` within tolerance of
0.52.)

**AC-SF-7 (fold emits one PatternDiscoveredEvent for first occurrence, no event for fold).**
Given two `PatternMinedEvent` messages with the same cascade signature, exactly one
`PatternDiscoveredEvent` is emitted (for the first occurrence); no second event is emitted
when the second occurrence is folded.
(JUnit 5 -- capture published Kafka messages; process both events; assert exactly one
`PatternDiscoveredEvent` for the pattern.)

**AC-SF-8 (fold-keeps-first sample alarms).** Given a first `PatternMinedEvent` with cascade
signature S carrying `sampleAlarms = [alarmA]`, followed by a second event with the same
signature S carrying `sampleAlarms = [alarmB]`, after both are processed the Pattern Store
contains only `[alarmA]` in the sample for the pattern (the first contributor's sample is
kept; the fold does not append or replace).
(JUnit 5 -- process both events; call `GET /patterns/{patternId}`; assert `sampleAlarms`
contains only `alarmA`, not `alarmB`.)

**AC-SF-9 (idempotent replay -- same eventId does not double-count).** Given a
`PatternMinedEvent` that has already been processed (its `eventId` is already recorded in the
contributing-event dedup table for the cascade signature row), redelivering the same event
does NOT increment `occurrenceCount`, `instanceCount`, or `trailCount`; none of these fields
change from their values after the first processing.
(JUnit 5 -- process the event; record `occurrenceCount`, `instanceCount`, `trailCount`;
process the same event again; assert all three are unchanged.)

**AC-SF-10 (different snapshotId = different rows).** Given two `PatternMinedEvent` messages
with the same `sequence` and `domain` but different `snapshotId` values, the Pattern Store
contains TWO separate pattern rows (distinct `patternId` values).
(JUnit 5 -- assert two rows; confirms snapshot-scoped identity.)

**AC-SF-11 (anchored path is unchanged in identity and event emission).** Given a
`PatternMinedEvent` where `anchorScenarioId` is non-null, the service routes it through the
anchored consolidation path (identity = `anchorIdentity(domain, snapshotId, codebookVersion,
anchorScenarioId)`), producing behaviour identical to the pre-fix anchored path (fold +
aggregate on same anchor, create on first; `trailId` and `sourceWindowId` not part of the
identity).
(JUnit 5 -- publish an anchored event; assert the existing AC-C1 / anchored-fold criteria
still hold; assert `trailId` and `sourceWindowId` are NOT part of the identity for this event.)

**AC-SF-12 (read API exposes the accumulated occurrence count -- live evidence case).** Given
N = 12 `PatternMinedEvent` messages all sharing the cascade signature
`["IPLinkDown","LinkDown","LinkBundleDegraded"]` with the same `domain` and `snapshotId` but
varying `trailId` (11 distinct trail values) and `sourceWindowId`, the Pattern Store contains
exactly 1 row after all 12 are processed; a `GET /patterns/{patternId}` response returns
`occurrenceCount = 12`, `trailCount = 11`, and the response validates against the published
`openapi.json` schema (the motivating live-evidence case: 12 occurrences across 11 trails
= 1 row, not 12).
(JUnit 5 -- publish 12 events with 11 distinct trailId values; assert 1 row; assert
`occurrenceCount = 12`, `trailCount = 11`; validate response schema.)

**AC-SF-13 (occurrenceCount and trailCount are distinct metrics).** Given 3
`PatternMinedEvent` messages with the same cascade signature but where the first two share the
same `trailId` (trail-X) and the third has a different `trailId` (trail-Y), after all 3 are
processed: `occurrenceCount = 3` (three distinct events folded) and `trailCount = 2` (two
distinct trails: trail-X and trail-Y).
(JUnit 5 -- assert `occurrenceCount = 3` and `trailCount = 2`; confirms the two metrics
count different things.)

**AC-SF-14 (firstSeen and lastSeen are set and updated correctly).** Given a first
`PatternMinedEvent` with timestamp T1 and a second with the same cascade signature and
timestamp T2 (T2 > T1), after both are processed: `firstSeen = T1` (unchanged by the fold)
and `lastSeen = T2` (bumped by the fold).
(JUnit 5 -- process two events with known timestamps; assert `firstSeen = T1`,
`lastSeen = T2`.)

**AC-SF-15 (idempotent replay does not update lastSeen).** Given a `PatternMinedEvent` at
timestamp T1 that has already been processed, redelivering the same event at wall-clock time
T2 (T2 > T1) does NOT change `lastSeen`; it remains at the value from the first processing.
(JUnit 5 -- process event; record `lastSeen`; redeliver same eventId; assert `lastSeen`
unchanged.)

**AC-SF-16 (anchored patterns also expose the four impact-metric fields).** Given an anchored
`PatternMinedEvent` processed via the anchored consolidation path, `GET /patterns/{patternId}`
returns a response containing `occurrenceCount`, `trailCount`, `firstSeen`, and `lastSeen`
with correct values, and the response validates against the published `openapi.json` schema.
(JUnit 5 -- process one anchored event; call read API; assert all four fields present and
non-null; validate schema.)

**AC-SF-17 (collapse migration produces correct aggregated metrics).** Given N existing
duplicate unexplained-pattern rows in the Pattern Store all sharing the same
`(sequence, domain, snapshotId)` with distinct `trailId` values and individual
`occurrenceCount` values, after running the Flyway collapse migration: exactly 1 row remains
for that signature; its `occurrenceCount` equals the sum of the N individual
`occurrenceCount` values; its `instanceCount` equals the sum of the N individual
`instanceCount` values; its `trailCount` equals the number of distinct `trailId` values
across the N rows; its `firstSeen` equals the minimum `createdAt` of the N rows; its
`lastSeen` equals the maximum `updatedAt` of the N rows; the sample alarms from the earliest
row are retained.
(JUnit 5 with in-process Flyway -- insert N synthetic duplicate rows; run the migration;
assert 1 row with correct aggregated metrics.)

**AC-SF-18 (collapse migration is idempotent).** Running the Flyway collapse migration twice
on an already-collapsed Pattern Store produces the same result as running it once (the second
run is a no-op with the same final state).
(JUnit 5 -- run migration; record state; run migration again; assert state unchanged.)

**AC-SF-19 (PatternView openapi.json schema includes the four new fields).** The checked-in
`openapi.json` for the Pattern Manager includes `occurrenceCount`, `trailCount`, `firstSeen`,
and `lastSeen` in the `PatternView` schema with correct types; the existing `instanceCount`
field is present with an updated description; no existing field is removed or renamed.
(JUnit 5 contract test -- parse checked-in `openapi.json`; assert the PatternView schema
contains the four new fields with the correct JSON Schema types; assert `instanceCount` is
still present.)

---

## Open questions

**OQ-SF-1 (`service:pattern-manager`, `question`) -- Data migration / backfill for existing
duplicate rows. RESOLVED (issue #356).**
Decision: forward-only fix PLUS a one-time Flyway collapse migration (option b + c).

- The identity fix applies to new occurrences from deployment forward.
- A Flyway migration collapses existing duplicate rows grouped by `(sequence, domain,
  snapshotId)`: sums `occurrenceCount`/`instanceCount`, derives `trailCount` from the distinct
  `trailId` values of the collapsed rows, sets `firstSeen = MIN(createdAt)` /
  `lastSeen = MAX(updatedAt)`, keeps the first row's sample alarms, deletes redundant rows
  with cascade children. The designer specifies the exact SQL and Flyway version.
- For the development/test environment: the Pattern Store is cleared and re-mined; the
  migration is not required in that environment but must pass on a store that is already clean.
  The migration is the production upgrade path.

**OQ-SF-2 (`service:pattern-manager`, `question`) -- Signature normalization. DEFERRED (no
change in this spec).**
Decision: no normalization. The signature is the ordered `sequence[]` of `alarmType` tokens
with repeats and order significant. Two patterns are the same if and only if their ordered
sequences are element-wise identical. No consecutive-repeat collapse, sort, or dedup is
applied. Any future normalization is a separate feature requiring a new human decision and spec
update.

**OQ-SF-3 (`service:pattern-manager`, `question`) -- Reuse `instanceCount` vs. add explicit
extent/impact fields. RESOLVED (issue #357).**
Decision: add four explicit fields to `PatternView` (additive read-API contract change,
approved): `occurrenceCount`, `trailCount`, `firstSeen`, `lastSeen`. The existing
`instanceCount` field is retained for total member-alarm volume (sum of mined support counts
across all contributing occurrences). Its description is updated to distinguish it from
`occurrenceCount`. All four new fields are populated for BOTH unexplained (signature-folded)
AND anchored patterns. The designer regenerates `openapi.json` as part of this work. This is
a pattern-manager read-API (openapi) change only; the event-model contract and Kafka schemas
are NOT changed.

**OQ-SF-4 (`service:pattern-manager`, `question`) -- Anchored patterns' impact-metric
consistency. RESOLVED as part of OQ-SF-3 decision.**
All four impact-metric fields (`occurrenceCount`, `trailCount`, `firstSeen`, `lastSeen`) are
populated for both unexplained and anchored patterns (see OQ-SF-3 resolution and Task 5
above). No further open question remains.
