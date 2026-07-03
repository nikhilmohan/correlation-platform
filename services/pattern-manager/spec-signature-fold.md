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
count/popularity metric on the EXISTING pattern instead."

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
    sequence with repeats significant -- is fixed by this spec.
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
  pattern row with occurrence count = the `instanceCount` from that `PatternMinedEvent` (the
  mined support count for that window), emit one `PatternDiscoveredEvent`, and record the
  contributing event (same as the anchored create path).

- On a **subsequent occurrence** of the same cascade signature (a row already exists):
  **aggregate -- do not create a new row**. The aggregation mirrors the anchored fold:
  - Occurrence/instance count: increment by the new occurrence's `instanceCount`
    (i.e. sum across all contributing occurrences).
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
  the count. The existing `processed_event` / `contributing_event` mechanism applies; no new
  dedup mechanism is introduced.

- **Row-lock serialization**: the fold acquires a `SELECT ... FOR UPDATE` row lock on the
  existing pattern row before aggregating, preventing concurrent fold races on the same
  signature -- same serialization approach as the anchored fold.

- **PatternView `instanceCount` field**: this existing field on `PatternView` (and the
  Pattern Store) is the vehicle for the aggregated occurrence count. It is the sum of
  `instanceCount` values contributed by all folded occurrences. The field is already present
  in the read API and `openapi.json`; no new field is introduced. The field description must
  be updated in `openapi.json` to state "total number of mined occurrences folded into this
  pattern across all contributing events and trails." This is an additive clarification to the
  existing field description, not a new field.
  - See OQ-SF-3 below for the question of whether a separate `occurrenceCount` field should
    be added for clarity.

- The anchored consolidation path (`consolidateAnchored`) is **unchanged**: identity,
  aggregation formula, fold guard, and event emission are all as-is.

- Updating the Pattern Manager's published `openapi.json` with the clarified `instanceCount`
  description (if changed -- see OQ-SF-3).

- Updating the Pattern Manager's structured logs to include the fold outcome
  (`action=fold` vs `action=create`) and the contributing signature for unexplained folds,
  at the same log level as the anchored fold.

**In scope (operational note -- forward-only):**

- This fix applies to newly consumed `PatternMinedEvent` messages from the point of
  deployment forward. Existing duplicate rows in a deployed Pattern Store are not
  automatically collapsed by this fix (see OQ-SF-1: migration/backfill question).

---

## Out of scope

- Changing the anchored consolidation path (`consolidateAnchored`, `anchorIdentity`) -- it
  is correct and unchanged.
- Changing `PatternMinedEvent`, `PatternDiscoveredEvent`, or `PatternApprovedEvent` Kafka
  event schemas -- this is an internal Pattern Store identity and aggregation change; no
  event-model contract change is required.
- Changing the Kafka topics consumed or produced -- `patterns.mined`, `patterns.discovered`,
  `patterns.approved` are unchanged.
- Changing the pattern read API surface beyond an `instanceCount` description update (see
  OQ-SF-3 for the question of a new field). If a new `occurrenceCount` field is decided, that
  is a contract change requiring human approval (additive, but still a change to the published
  OpenAPI surface).
- Automatic migration or backfill of already-persisted duplicate rows -- this is explicitly
  flagged as OQ-SF-1 for human decision.
- Cross-snapshot fold: patterns from different `snapshotId` values are intentionally distinct
  (consistent with the anchored path's scoping). If a new topology snapshot is loaded, new
  pattern rows are created for that snapshot; folding across snapshots is out of scope for
  MVP.
- Cross-domain fold: the signature is scoped to a single domain; cross-domain folding is out
  of scope.
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
   occurrence's `instanceCount`, support/confidence/lift (occurrence-weighted mean), timing
   (combined, then recompute `sessionWindow`), and supporting instances (union) into the
   existing row; bump `updatedAt`; emit no event. If the fold guard detects a replay (same
   `eventId` already recorded), record the processed event and return no-op.

3. **Create a new signature row for a first occurrence.** If no Pattern Store row exists for
   the cascade signature identity: persist a new draft pattern row (same enrichment pipeline
   as before -- RCA, structural validation, codebook reconciliation, XAI); record the
   contributing event; emit one `PatternDiscoveredEvent`. Sample alarms (if present in the
   event) are persisted on create and never replaced on subsequent folds.

4. **Ensure replay safety for the unexplained fold.** Guarantee that re-processing the same
   `PatternMinedEvent` (same `eventId`) on an existing signature row does not double-count:
   the fold guard (contributing-event dedup) must block the aggregate step on replay, same as
   the anchored path.

5. **Surface the aggregated occurrence count via the read API.** The Pattern Store's
   `instanceCount` field accumulates the total mined occurrences across all contributing
   events and trails. The read API serves this as the existing `PatternView.instanceCount`
   field. Update the `openapi.json` description of `instanceCount` to reflect its meaning as
   the cross-occurrence aggregate (see OQ-SF-3 for whether a separate field is needed).

---

## Phase applicability

This enhancement is active only in P2 (Pattern learning). It is consistent with the canonical
phase map in `architecture.md` for the Pattern Manager service and does not change P1 or P3
behaviour.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 -- Topology onboarding | No change from base spec. | Idle | -- |
| P2 -- Pattern learning | Active (same as base spec). Additionally: the unexplained consolidation path now folds cross-trail occurrences of the same cascade signature into one row, accumulating occurrence count rather than creating duplicate rows. | Active | In: `patterns.mined` (`PatternMinedEvent`) -- unchanged. Out: `patterns.discovered` (one per UNIQUE cascade signature, not one per occurrence); `patterns.approved` -- unchanged. Serves: read API with `instanceCount` reflecting cross-occurrence aggregate. |
| P3 -- Real-time correlation | No change from base spec. The Pattern Store has fewer rows (one per signature rather than N per recurrence) and each carries an accumulated `instanceCount`. | Passive | Serves: pattern read API -- unchanged shape, fewer rows, higher occurrence counts. |

---

## Contract

### No event-model or topic changes

This fix is entirely internal to the Pattern Manager's consolidation logic and Pattern Store.

- **Consumes (Kafka) -- unchanged:** `patterns.mined` (`PatternMinedEvent`). No new fields
  consumed; no new topics.
- **Produces (Kafka) -- unchanged:** `patterns.discovered` (`PatternDiscoveredEvent`) and
  `patterns.approved` (`PatternApprovedEvent`). Schemas unchanged. The observable change: for
  unexplained patterns, `PatternDiscoveredEvent` is emitted once per unique cascade signature
  (on first occurrence) rather than once per mined occurrence. This is a reduction in event
  volume, not a schema change.
- **APIs exposed -- unchanged shape, possible description update:** `GET /patterns` and
  `GET /patterns/{patternId}` are unchanged. The `instanceCount` field on `PatternView`
  acquires a clearer description. See OQ-SF-3 for whether a new `occurrenceCount` field
  should be added (a contract change requiring human approval if yes).
- **APIs/data consumed from other services -- unchanged.** Topology, Codebook Generator, and
  Knowledge Service integration points are unchanged.
- **Integration points (mock vs. real) -- unchanged.**
- **Data owned -- Pattern Store (PostgreSQL, schema `pattern`) -- internal change only.** The
  identity function and fold logic change; the table schema may need a new index or the
  contributing-event table extended to cover unexplained folds (currently the contributing
  event table is used only by the anchored path). The schema migration is Pattern Manager's
  own responsibility (Flyway, scoped to the `pattern` schema). No cross-service data
  ownership change.

---

## Non-functional

- **Idempotency key:** `eventId` (same as base spec). The fold guard on `eventId` via the
  contributing-event dedup mechanism makes the unexplained fold replay-safe, exactly as the
  anchored fold.
- **Config:** no new configurable parameters introduced by this fix. The cascade signature
  key components (`sequence`, `domain`, `snapshotId`) are intrinsic to the event -- no
  thresholds or knobs.
- **Observability:** structured JSON log at INFO for each unexplained fold:
  `action=create|fold|noop`, `patternId`, `signature` (the ordered sequence tokens, or a
  hash of them), `domain`, `snapshotId`, and on fold: the updated `instanceCount`. Consistent
  with the existing anchored-path logging.
- **Error handling:** unchanged from base spec. A failed fold rolls back the transaction; the
  Kafka offset is not committed; the event is redelivered and re-folded safely (idempotent).
  Poison messages route to `patterns.mined.dlq` as before.
- **API contract:** the published `openapi.json` is the authoritative HTTP surface. If
  `instanceCount` description is updated, that is not a schema change (no field add/remove/
  type change). If a new `occurrenceCount` field is added (OQ-SF-3), that is an additive
  contract change requiring human approval before the designer publishes the update.
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
does NOT increment `instanceCount` again; the Pattern Store row's `instanceCount` remains
unchanged from after the first processing.
(JUnit 5 -- process the event; record the `instanceCount`; process the same event again;
assert `instanceCount` is unchanged.)

**AC-SF-10 (different snapshotId = different rows).** Given two `PatternMinedEvent` messages
with the same `sequence` and `domain` but different `snapshotId` values, the Pattern Store
contains TWO separate pattern rows (distinct `patternId` values).
(JUnit 5 -- assert two rows; confirms snapshot-scoped identity.)

**AC-SF-11 (anchored path is unchanged).** Given a `PatternMinedEvent` where
`anchorScenarioId` is non-null, the service routes it through the anchored consolidation path
(identity = `anchorIdentity(domain, snapshotId, codebookVersion, anchorScenarioId)`),
producing behaviour identical to the pre-fix anchored path (fold + aggregate on same anchor,
create on first).
(JUnit 5 -- publish an anchored event; assert the existing AC-C1 / anchored-fold criteria
still hold; assert `trailId` and `sourceWindowId` are NOT part of the identity for this event.)

**AC-SF-12 (read API exposes the accumulated occurrence count).** Given a pattern that has
been folded from 5 occurrences across 4 trails (total `instanceCount = 12`), a
`GET /patterns/{patternId}` response returns `instanceCount = 12` and the response validates
against the published `openapi.json` schema.
(JUnit 5 -- fixture: pattern with accumulated count; call read API; assert `instanceCount`
value; validate response against `openapi.json`.)

**AC-SF-13 (exactly N occurrences = exactly 1 row in the store).** Given N = 12
`PatternMinedEvent` messages all sharing the cascade signature
`["IPLinkDown","LinkDown","LinkBundleDegraded"]` with the same `domain` and `snapshotId` but
varying `trailId` and `sourceWindowId`, the Pattern Store contains exactly 1 row for that
sequence after all 12 are processed (validating the motivating live-evidence case).
(JUnit 5 -- publish 12 events; assert one row in Pattern Store; assert `instanceCount >= 12`.)

---

## Open questions

**OQ-SF-1 (`service:pattern-manager`, `question`) -- Data migration / backfill for existing
duplicate rows.**
This fix is forward-only: from deployment forward, new occurrences fold into one row per
signature. Existing duplicate rows that were persisted under the old per-occurrence identity
(`perEventIdentity(trailId, sequence, sourceWindowId, snapshotId)`) remain as separate rows
and are NOT automatically collapsed.

Options:
a. **Forward-only** (minimum risk): accept that existing duplicates remain until the next
   re-mine cycle; operators see both old duplicates and new folded rows until the store is
   re-populated.
b. **One-time collapse migration** (recommended for a clean store): a Flyway migration or
   operational script collapses existing rows with the same `(sequence, domain, snapshotId)`
   into one, summing `instanceCount` and picking representative metrics. Requires downtime
   or careful ordering.
c. **Clear and re-mine** (simplest in dev/test): wipe the Pattern Store and replay
   `patterns.mined` from the beginning; the fold will produce the correct result. This is
   the approach for the development/test environment.

Human decision required: choose the migration strategy before the designer specifies the
Flyway migration plan. The spec recommends option c for the development environment and
option b for a production-grade deployment, but the choice is a human/operational decision.

**OQ-SF-2 (`service:pattern-manager`, `question`) -- Signature normalization: ordered
sequence with repeats significant, or collapse consecutive repeats?**
This spec defines the signature as the ordered `sequence[]` of `alarmType` tokens with
repeats and order significant -- two patterns are the same if and only if their ordered
sequences are element-wise identical. No normalization is applied (no consecutive-repeat
collapse, no sort, no dedup).

If the mining algorithm can produce `["A","A","B"]` and `["A","B"]` as distinct outputs for
what is operationally the same cascade, and if the operator requirement is to fold those too,
then normalization (e.g. collapse consecutive repeats) would be needed. This spec does NOT
apply normalization; it preserves the mined sequence exactly.

If the product owner or mining team believes normalization is required, this must be a human
decision before design proceeds -- it changes the identity semantics. The designer must not
apply normalization without this decision.

**OQ-SF-3 (`service:pattern-manager`, `question`) -- Reuse `instanceCount` vs. add a new
`occurrenceCount` / `timesObserved` field on `PatternView`.**
The existing `PatternView.instanceCount` field (already in the read API and `openapi.json`)
is the natural vehicle for the aggregated cross-occurrence count. Under this spec, its
semantics become "total mined occurrences folded into this pattern across all contributing
events and trails." This is an additive description change, not a field change.

Alternative: add a dedicated `occurrenceCount` (or `timesObserved`) field to `PatternView`
with a clearer name, keeping `instanceCount` for its existing per-event meaning (the mined
support count of the first contributing event). This would be an additive contract change to
the published OpenAPI surface -- a new field requires human approval (per the golden rule:
a read-API surface change = contract change + `architecture.md`/spec update + human approval).

Recommendation: reuse `instanceCount` with an updated description (no new field, no contract
change). The designer may propose a new field if the name ambiguity is a real UX concern, but
must flag it for human approval before publishing.

Human decision required if the designer proposes a new field: approve the additive
`PatternView` / `openapi.json` change.

**OQ-SF-4 (`service:pattern-manager`, `question`) -- Anchored patterns' occurrence count
exposure for consistency.**
The anchored path already folds cross-trail and aggregates `instanceCount`. The anchored
`instanceCount` is already served via `PatternView.instanceCount`. This spec does not change
the anchored path. However, the operator-facing meaning of `instanceCount` should be
consistent across anchored and unexplained patterns after this fix: in both cases it is the
"total occurrences folded in."

If OQ-SF-3 decides to add a new explicit `occurrenceCount` field, it should be populated for
BOTH anchored and unexplained patterns from `instanceCount`. This is a consistency note for
the designer, not a new spec requirement.
