# correlation-engine — Pattern Generalization Spec (Refinement)

> **Status:** Refinement of `services/correlation-engine/spec.md`.
> This document defines the **pattern generalization** capability: once a pattern is
> approved, it must auto-correlate on ANY trail in the network whose structure is
> compatible with the pattern definition — not only the single trail on which the
> pattern was discovered.
>
> This spec refines and extends the correlation-instance model defined in `spec.md`.
> Every term, topic name, payload name, and field name is used exactly as defined in
> `docs/architecture.md` and `libs/event-model`. Where a needed behaviour is absent
> from the current contract, it is raised as an Open question — not guessed.

---

## Purpose

An approved pattern is a reusable failure **signature** — an ordered `alarmType` sequence
with a `rootCauseAlarmType`, a `sessionWindow`, and a `confidence`. The platform discovers
patterns by mining alarm history on individual trails, but a pattern's value is its
generality: the same failure cascade can occur on any structurally similar trail across the
network. Today the Correlation Engine binds each approved pattern exclusively to its
discovery `trailId`, so the identical failure signature occurring on a different trail is
never auto-correlated. This defeats the purpose of pattern learning.

This refinement makes an approved pattern **network-wide**: after approval the pattern is a
candidate match on every trail whose structure is compatible with its `alarmType` sequence,
regardless of which trail the pattern was discovered on. The discovery `trailId` becomes
provenance metadata (recorded for audit), not a runtime matching constraint. Correlation
instances remain per concrete cascade occurrence, keyed by the trail the live cascade
actually occurred on. The same pattern may therefore drive simultaneous, fully independent
incidents on many trails across the network — one per real occurrence. This is the intended
design: one learned signature, many correlated incidents wherever the cascade physically
manifests.

---

## Scope

**In scope (this refinement):**

- Redefining the approved-pattern registration model so that an approved pattern is indexed
  for matching on every trail that is **structurally compatible** with the pattern's
  `alarmType` sequence — not only the trail it was mined on.
- Defining **structural compatibility**: the rule by which the engine determines, for a
  given approved pattern and a given trail, whether the trail is a candidate host for the
  pattern's cascade. The crux of the definition (exact rule, recommended default, and open
  decisions) is addressed in the Tasks and Open questions sections below.
- Redefining **instance keying**: a correlation instance is keyed by
  `(matchedTrailId, patternId)` where `matchedTrailId` is the trail the live cascade
  actually occurred on (derived from the incoming alarm's `trailIds[]`), not the discovery
  trail. The discovery `trailId` (from `PatternView.trailId` via the Pattern Manager read
  API) is retained as immutable provenance on the pattern record.
- Redefining **runtime selection**: for an alarm arriving on trail T, the engine must
  consider every approved pattern whose `alarmType` sequence is structurally compatible
  with T — not only patterns whose discovery `trailId` equals T. The fan-out driver
  changes from "patterns registered on T by discovery" to "approved patterns compatible
  with T".
- Requiring that compatible-trail sets per pattern be **precomputed and indexed** (not
  evaluated per-alarm at runtime), bounded by a refresh mechanism that is triggered on
  pattern approval/refresh and on topology change, so that per-alarm matching remains a
  bounded index lookup. The indexing mechanism is left to design.
- Preserving **per-occurrence isolation**: a correlation instance for `(matchedTrailId,
  patternId)` is fully isolated from any other instance for the same `patternId` on a
  different `matchedTrailId`. Simultaneous instances of the same pattern on different
  trails each produce their own independent incident with their own root-cause alarm
  resolved from the matched alarm set on that trail.
- Preserving **RCA and incident semantics unchanged**: root cause is still resolved by
  joining the winning pattern's `rootCauseAlarmType` against the `alarmType` field of
  the matched alarms on the matched trail; the incident carries `matchedTrailId`
  (the trail the cascade occurred on), `patternId`, and `confidence` exactly as today.
- Backward compatibility: the discovery trail is trivially compatible with its own pattern
  (it hosted the cascade once; it can host it again). All existing single-trail correlation
  behaviour is preserved as a special case of the generalized model.
- Specifying the dependency on per-trail structure data (member `objectType`s per trail)
  needed to evaluate structural compatibility, and raising — as Open questions — how
  the engine acquires and refreshes this data.

**Out of scope (this refinement, not deferred by mistake):**

- Mining new patterns or altering the pattern lifecycle (`draft` / `approved` /
  `deprecated`): owned by the Pattern Miner and Pattern Manager; unchanged.
- Authoring the structural-compatibility rule as policy in the Knowledge Service
  (MVP decision: the rule is a fixed engine behaviour; Knowledge-authored rule is a
  post-MVP extensibility point).
- Cross-domain pattern generalization: patterns discovered in one domain matching on
  trails from another domain. MVP generalizes within a single domain only.
- Suppressing incidents when the same signature fires on many trails simultaneously
  (incident de-duplication across trails): each occurrence on each compatible trail is
  an independent, valid incident. Volume implications are addressed in Open questions.
- Altering the `PatternApprovedEvent` schema, the `CorrelationResultEvent` schema, or
  any other event payload in `libs/event-model` (no contract change is expected; see
  Open questions if a gap is found).
- Altering the `patterns.approved`, `correlation.results`, or `alarms.status.changed`
  topic contracts.
- Changing the alarm enrichment path, `trailIds[]` population, or any upstream
  service's responsibility.

---

## Out of scope (from the base spec — unchanged)

All items listed in `spec.md § Out of scope` remain out of scope and are not revisited
here.

---

## Tasks (high-level — additions and redefinitions)

These tasks REFINE the existing spec Tasks (numbered per `spec.md`) and add new ones.
Existing tasks not mentioned here are unchanged.

**Task 1 — REDEFINED: Load approved patterns with network-wide compatibility index.**
On startup (and on each `patterns.approved` refresh event), fetch all currently approved
patterns from the Pattern Manager read API (`GET /patterns?lifecycle=approved`). For each
approved pattern, record its discovery `trailId` as provenance. Then — as a distinct,
bounded step — compute or update the **compatible-trail set** for that pattern: the set
of all trails in the current topology snapshot whose structure is compatible with the
pattern's `alarmType` sequence (see Task 1a). Index the pattern against every trail in
its compatible set, replacing the prior single `trailId`-based index. The discovery
`trailId` is expected to appear in the compatible-trail set (backward compatibility).

**Task 1a — NEW: Determine structural compatibility for a pattern.**
Given an approved pattern's `alarmType` sequence and the full trail catalog for the
active topology snapshot, compute the set of trails that are structurally compatible with
the pattern. A trail is a candidate for a pattern when the trail's member objects include
at least one object of each `objectType` that the pattern's `alarmType` sequence requires
— that is, the trail can physically host the cascade (hostability criterion). This
requires knowing, for each `alarmType` in the pattern's sequence, which `objectType`
carries that alarm type (the `alarmType`-to-`objectType` affinity). The exact
compatibility rule, its inputs, and edge cases are specified in the Open questions below;
the recommended default for MVP is the **hostability subset**: a trail is compatible if,
for every distinct `objectType` required by the pattern's sequence, the trail contains at
least one member object of that `objectType`. The computation is performed over the Trail
Builder's `GET /trails/{trailId}` members (each member carries `objectType` alongside
`managedObjectId`). The result is a precomputed set, not a per-alarm evaluation.

**Task 1b — NEW: Refresh the compatibility index on topology change or pattern refresh.**
When a `trails.built` event is received (indicating a new or updated topology snapshot
has produced a new trail catalog), recompute the compatible-trail sets for all currently
approved patterns against the new trail catalog and rebuild the index. When a
`patterns.approved` event is received (a new or updated approved pattern), compute the
compatible-trail set for that pattern against the current trail catalog and update the
index for that pattern only. The index must converge to a consistent state reflecting
the latest topology and latest approved pattern set. Index refreshes must not block
ongoing per-alarm correlation work beyond a brief, bounded transition window.

**Task 3 — REDEFINED: Fan out incoming alarms using the generalized compatibility index.**
For each valid alarm on `alarms.persisted.live` and for each trail T in the alarm's
`trailIds[]`, dispatch the alarm to every approved pattern whose compatible-trail set
includes T — not only patterns whose discovery `trailId` equals T. For each such
`(T, patternId)` pair, proceed with the existing lazy-init / incremental-match /
session-expiry instance lifecycle (Tasks 3–9 of the base spec), keyed by
`(T, patternId)` where T is the matched trail. This is the core fan-out change: the
driver is the compatibility index, not the discovery trail registry.

**Task 4 — UNCHANGED in semantics, REDEFINED in keying.**
Correlation-instance lifecycle management is unchanged (lazy init, incremental match,
full-match fire, session-expiry revert). The **instance key** is `(matchedTrailId,
patternId)` where `matchedTrailId` is the trail from the alarm's `trailIds[]` that is
in the pattern's compatible-trail set. One pattern may have multiple simultaneous live
instances — one per trail where an active cascade is underway. Each is fully independent.

**Task 8 — UNCHANGED in structure, CLARIFIED in trailId semantics.**
`CorrelationResultEvent` is emitted with `trailId` set to `matchedTrailId` — the trail
the live cascade occurred on — not the discovery `trailId`. The `CorrelationResultEvent`
schema already carries `trailId`; its value is now always the matched trail (no schema
change). The discovery `trailId` is retained as provenance on the incident record (see
Data owned below) but does not appear on the event.

**Task NEW: Record discovery provenance on incidents.**
When an incident is created from a pattern match, persist the pattern's discovery
`trailId` (provenance) alongside the incident record in the Incident Store in addition
to the `matchedTrailId`. The discovery `trailId` is read-model / audit data only; it is
not added to `CorrelationResultEvent` (no contract change expected; see Open questions).

---

## Phase applicability

Unchanged from `spec.md`. The Correlation Engine is Active only in P3 — Real-time
correlation. The generalization capability is a P3 concern: the compatibility index is
built during the P3 warm-up phase (on startup / after pattern approval) and used
throughout P3 real-time processing.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no approved patterns exist; no live alarms. | Idle | — |
| P2 — Pattern learning | Not involved in pattern mining or approval. May receive `trails.built` events and maintain a trail catalog for future use, but performs no correlation. | Idle | — |
| P3 — Real-time correlation | Core work phase: builds and maintains the compatible-trail index from approved patterns and the trail catalog; on each live alarm fans out to all compatible `(matchedTrailId, patternId)` pairs; manages instance lifecycle; resolves conflicts; tags root cause; persists incidents; emits `correlation.results`; serves Incident/Stats read API. | Active | In (Kafka): `alarms.persisted.live`, `patterns.approved`, `codebook.generated`, `trails.built` (new consumption — see Open questions); Out (Kafka): `correlation.results`, `alarms.status.changed`; Calls (API): Pattern Manager (approved patterns + provenance trailId), Trail Builder (trail member structure for compatibility), Codebook Generator (scenario signatures), Knowledge Service (match thresholds); Serves (API): Incident/Stats read API (web-ui) |

---

## Contract

The following supplements `spec.md § Contract`. Items not listed here are unchanged.

- **Consumes (Kafka) — ADDITION:**
  - `trails.built` — `TrailsBuiltEvent` (`snapshotId`, `trailIds[]`, `trailCount`,
    `domain`). Consumed as a trigger to rebuild the compatible-trail index when a new
    topology snapshot produces a new trail catalog. **This is a new consumption for the
    Correlation Engine** — it does not currently consume `trails.built`. Whether this
    constitutes a contract change (a new consumer on an existing topic is not a new
    topic, but it is a new dependency) is flagged in Open questions. No change to the
    `trails.built` topic schema or `TrailsBuiltEvent` payload is required.

- **Produces (Kafka) — UNCHANGED:** `correlation.results` and `alarms.status.changed`
  are unchanged. `CorrelationResultEvent.trailId` is set to `matchedTrailId` (the trail
  the cascade occurred on) — consistent with the frozen schema, which already carries
  `trailId` without specifying discovery-vs-matched semantics; this is a clarification
  only, not a schema change.

- **APIs/data consumed from other services — ADDITION:**
  - **Trail Builder** — `GET /trails/{trailId}` (frozen response: `TrailDetail {
    trailId, domain, snapshotId, members: [{ managedObjectId, objectType }],
    memberCount }`) — used to obtain the `objectType` of each trail member for
    structural-compatibility evaluation. Already published by Trail Builder at this
    endpoint; the Correlation Engine has not previously been a consumer. Also optionally
    `GET /trails?snapshotId={snapshotId}&domain={domain}` (list all trail IDs for a
    snapshot/domain) to enumerate all trails when rebuilding the full compatibility
    index. Built against Trail Builder's published `openapi.json`. This is a new
    integration point for the Correlation Engine.

- **Data owned — ADDITIONS to Incident Store:**
  - `discoveryTrailId` — the `trailId` of the trail on which the matched pattern was
    originally mined (provenance); persisted on the incident record alongside
    `matchedTrailId` (`trailId` in the existing schema). Read-model/audit only; not
    added to `CorrelationResultEvent` (see Open questions).
  - The `patternId`-to-compatible-trails index is an internal engine state (in-memory
    or local store); it is not a shared data store and no other service reads it
    directly.

- **Integration points (mock vs. real) — ADDITION:**
  - **Trail Builder API** — config key: `TRAIL_BUILDER_BASE_URL`; toggle:
    `TRAIL_BUILDER_MODE` (`mock` | `real`). In unit tests, backed by a mock/stub
    generated from Trail Builder's published `openapi.json`. In integration, pointed at
    the real Trail Builder service.

---

## Non-functional

Supplements `spec.md § Non-functional`. Items not listed here are unchanged.

- **Idempotency key:** unchanged. The compatibility index is a derived, rebuildable
  cache; it carries no new idempotency requirement beyond correct convergence on refresh.

- **Compatibility index boundedness:** the compatible-trail set for each pattern must be
  precomputed and indexed at pattern-approval/refresh time and on `trails.built` (not
  evaluated per-alarm). Per-alarm correlation work must be a bounded index lookup (the
  set of compatible trails for a pattern is known before any alarm arrives). The index
  size is bounded by `|approved patterns| × |trails in the network|`; the designer must
  ensure this is managed (e.g. lazy/sparse index, batch refresh, bounded trail catalog
  fetch).

- **Refresh ordering:** a new pattern approval must not be matchable until its
  compatible-trail set has been computed and written to the index. A topology change
  (`trails.built`) must trigger index recomputation before new alarms are dispatched
  using the updated trail catalog. The ordering guarantee is a correctness requirement;
  the mechanism is a design decision.

- **Instance isolation across matched trails (generalized):** the isolation invariant
  from `spec.md` is extended: the state of instance `(T1, P)` must never affect
  instance `(T2, P)` for the same pattern P and different trails T1, T2, regardless of
  whether those instances match concurrently.

- **Config:** `TRAIL_BUILDER_BASE_URL` and `TRAIL_BUILDER_MODE` are environment
  variables alongside the existing outbound integration configs. The
  alarmType-to-objectType affinity source (Knowledge Service, codebook, or inline) is
  config-driven — no hard-coded affinity mapping.

- **Error handling:** Trail Builder fetch failures during compatibility index
  computation must not crash the engine or silently yield an incorrect (empty) index.
  The designer must define a bounded-retry / partial-failure model; any trail that
  cannot be fetched within retry bounds leaves that trail absent from the index (a
  conservative safe default — no false positive incidents on unfetchable trails).

- **Observability — ADDITIONS:** expose at minimum:
  - `compatible_trails_per_pattern_gauge` (or equivalent) — the size of the
    compatible-trail set per pattern (to observe generalization breadth).
  - `pattern_generalization_index_refresh_total` — count of index refreshes triggered
    by `patterns.approved` or `trails.built` events.
  - `trail_builder_fetch_errors_total` — count of Trail Builder fetch failures during
    index computation.

---

## Acceptance criteria

Each criterion is testable as a single JUnit 5 test. Criteria are numbered starting
from 31 to extend the base spec's numbering (base spec ends at AC30).

**31. Generalized pattern matches on a non-discovery trail.**
Given an approved pattern P discovered on trail T_disc, and a trail T_other that is
structurally compatible with P (contains at least one member of each `objectType` the
pattern's `alarmType` sequence requires), when the full alarm sequence for P arrives on
T_other (alarms whose `trailIds[]` includes T_other), the engine creates a correlation
instance for `(T_other, P)`, fully matches it, and emits a `CorrelationResultEvent`
with `trailId = T_other` and `matchedPatternId = P.patternId`. No instance for
`(T_disc, P)` is created or affected.

**32. Incompatible trail is not a candidate.**
Given an approved pattern P whose `alarmType` sequence requires `objectType` set {A,
B, C}, and a trail T_incompat that contains members of `objectType` {A, B} only (no
member of type C), when alarms arrive on T_incompat, no correlation instance for
`(T_incompat, P)` is created. The engine does not attempt a match for this
`(trail, pattern)` pair.

**33. Discovery trail remains compatible (backward compatibility).**
Given an approved pattern P discovered on trail T_disc, when the full alarm sequence
for P arrives on T_disc, the engine creates an instance for `(T_disc, P)`, matches it,
and emits `CorrelationResultEvent` with `trailId = T_disc`. The discovery trail is
treated as a compatible trail exactly like any other compatible trail.

**34. Same pattern drives simultaneous independent instances on two compatible trails.**
Given an approved pattern P compatible with both trail T1 and trail T2, and two
simultaneous alarm cascades — one on T1 (alarms with `trailIds[]` containing T1) and
one on T2 (alarms with `trailIds[]` containing T2) — the engine creates two independent
instances `(T1, P)` and `(T2, P)`, each fully matches, and the engine emits two
`CorrelationResultEvent`s: one with `trailId = T1` and one with `trailId = T2`. The
`childAlarmIds[]` sets of the two incidents are disjoint. No alarm appears in both
incidents.

**35. Instance key is the matched trail, not the discovery trail.**
Given an approved pattern P discovered on trail T_disc and a different compatible trail
T_match, when the alarm cascade for P arrives on T_match, the emitted
`CorrelationResultEvent` carries `trailId = T_match` (not T_disc). The Incident Store
record for this incident carries `matchedTrailId = T_match` and
`discoveryTrailId = T_disc`.

**36. Compatibility index is consulted, not discovery-trail registry.**
Given the engine's internal state after loading an approved pattern P (discovery trail
T_disc) and computing compatibility against a trail catalog that includes T_disc and
T_other (both compatible), when an alarm arrives on T_other, the engine dispatches it
to `(T_other, P)` using the compatibility index — not a registry keyed on `T_disc`.
A test that removes T_disc from the compatibility index (by mocking a Trail Builder
response that makes T_disc incompatible) confirms that alarms on T_disc are no longer
dispatched to P, while T_other remains active.

**37. Compatibility index is rebuilt on trails.built.**
Given the engine running with pattern P compatible with trail T1 and T2, when a new
`trails.built` event arrives for a snapshot in which T2 no longer exists (Trail Builder
returns 404 or an empty member list for T2) and a new trail T3 (compatible with P) has
been added, the engine rebuilds the index: P is now compatible with T1 and T3 only. A
subsequent alarm cascade on T2 does not initiate instance `(T2, P)`; an alarm cascade
on T3 does initiate instance `(T3, P)`.

**38. Compatibility index is updated on pattern approval.**
Given the engine running with an empty approved-pattern set and a trail catalog
containing T1 (compatible with pattern P_new) and T2 (incompatible with P_new), when a
`patterns.approved` event arrives for P_new and the engine fetches P_new from the
Pattern Manager read API and computes compatibility against the current trail catalog,
subsequently received alarms on T1 are dispatched to `(T1, P_new)` and alarms on T2
are not.

**39. alarmType-to-objectType affinity drives compatibility — no hard-coded mapping.**
Given a test in which the Trail Builder mock returns trail members with
`objectType` values different from any Core IP default, and the affinity source (see
Open questions) maps the pattern's `alarmType` tokens to those `objectType`s, the
engine correctly identifies trails with matching members as compatible and trails
without as incompatible — with no code change and no hard-coded `alarmType`-to-`objectType`
values in the engine.

**40. Compatibility is precomputed — per-alarm dispatch is a bounded index lookup.**
Given N approved patterns each compatible with M trails, and K alarms arriving
simultaneously, the engine's per-alarm dispatch overhead is O(|trailIds[]| * max
compatible patterns per trail) — a bounded index lookup — and not O(N * M) (no
full-index scan per alarm). A test with a large mocked compatibility index confirms
that alarm processing latency does not scale with the total number of
(pattern, trail) pairs in the index.

**41. Trail Builder fetch failure does not corrupt the index.**
Given a Trail Builder mock that returns HTTP 500 for trail T_fail and 200 for all other
trails, when the engine rebuilds the compatibility index, T_fail is absent from the
index (not incorrectly included as compatible or incompatible). Subsequent alarms on
T_fail do not trigger false-positive instances. The engine continues processing alarms
on all other trails without interruption.

**42. Trail Builder integration point is config-switchable.**
With `TRAIL_BUILDER_MODE=mock`, all unit tests for the pattern-generalization path
(index build, compatibility check, index refresh) run to completion with no live Trail
Builder service. With `TRAIL_BUILDER_MODE=real`, the engine routes calls to
`TRAIL_BUILDER_BASE_URL`. The same code path is exercised in both modes.

**43. CorrelationResultEvent.trailId carries the matched trail, not the discovery trail.**
For every `CorrelationResultEvent` emitted by the engine under the generalized model,
`trailId` equals the trail from which the winning alarm cascade was drawn (the trail in
the matched alarm's `trailIds[]` that triggered the instance). A test that seeds
discovery trail T_disc != matched trail T_match confirms `CorrelationResultEvent.trailId
= T_match`.

**44. Incident record carries both matchedTrailId and discoveryTrailId.**
For an incident created from pattern P (discovered on T_disc) matching on T_match (!=
T_disc), the incident record retrieved via `GET /incidents/{incidentId}` carries both
`trailId = T_match` (the matched trail — existing field) and a `discoveryTrailId =
T_disc` field (provenance). The `discoveryTrailId` is a read-API field; it does not
appear on `CorrelationResultEvent`.

**45. Simultaneous instances of the same pattern on different trails do not interfere.**
Given pattern P with simultaneous instances `(T1, P)` and `(T2, P)` each in-progress,
the session expiry of `(T1, P)` fires `AlarmStatusChange(reverted-open)` only for
alarms in instance `(T1, P)` — not for any alarm in instance `(T2, P)`. And the full
match of `(T2, P)` creates an incident for T2 only. Neither instance's lifecycle event
affects the other.

---

## Open questions

The following questions must be resolved by a human before design proceeds where noted,
or are flagged for human awareness. Each is tracked as a GitHub issue labeled `question`
and `service:correlation-engine`.

**OQ-G1 — DECISION REQUIRED: Exact structural-compatibility rule.**
The recommended default (hostability subset: a trail is compatible with pattern P if the
trail contains at least one member of each `objectType` that the pattern's `alarmType`
sequence requires) is stated in Task 1a, but the following edge cases are unresolved:

- (a) Does the root-cause alarm's `objectType` (the `objectType` of the member that
  raises the `rootCauseAlarmType`) have a stricter presence requirement (e.g. must be
  present in the trail) compared to non-root-cause alarm types in the sequence?
- (b) Should the compatibility rule also enforce that members of the required
  `objectType`s are topologically connected within the trail (i.e. form a connected
  sub-path), or is flat membership sufficient for MVP?
- (c) Should IGP area or SRLG group membership bound pattern generalization (e.g. a
  pattern discovered within one IGP area only matches trails within the same IGP area)?
- (d) Should compatibility also require that the count of members of each required
  `objectType` is at least the count of distinct alarms of that `objectType` in the
  pattern sequence (multiset hostability), or is a single member per type sufficient?

  Recommended default for all: flat single-member hostability, no area/SRLG bounding,
  no topological-connectivity check — keep the MVP rule simple and observable. A human
  must confirm or override this recommendation before design proceeds.

**OQ-G2 — DECISION REQUIRED: Source of alarmType-to-objectType affinity.**
To evaluate structural compatibility, the engine must know which `objectType`(s) can
raise each `alarmType` in the pattern's sequence. The following sources are available
without a contract change:

- (a) The Knowledge Service `alarmTypeVocabulary` — if it stores the
  `alarmType`-to-`objectType` affinity per domain (it is the authoritative home of the
  domain ontology, so this would be the cleanest source). However, whether the current
  Knowledge API exposes this affinity is not confirmed in the existing contract.
- (b) The Pattern Manager's `PatternView` — `sequence[]` tokens are `alarmType`
  values; `PatternMinedEvent.trailId` identifies the trail; the engine could infer
  which `objectType` hosted each `alarmType` by cross-referencing the discovery trail's
  members (Trail Builder `GET /trails/{trailId}`) against the Incident Store's alarm
  records. This is indirect and may be brittle.
- (c) Derive it at pattern-approval time from the Pattern Manager's structural-
  validation data (which already resolves alarm types to graph objects during RCA) —
  but the `objectType` affinity is not currently surfaced on `PatternView` or any event.

  A human must decide the authoritative source and, if it requires a new API field or
  endpoint (e.g. `alarmType`-to-`objectType` mapping on `PatternView`, or a new
  Knowledge endpoint), confirm that as a contract change before design proceeds.

**OQ-G3 — DECISION REQUIRED: When and how does the engine obtain per-trail structure?**
The compatibility index requires knowing each trail's member `objectType`s. Options:

- (a) Fetch `GET /trails/{trailId}` from Trail Builder for each trail at index-build
  time (on pattern approval and on `trails.built`). The Trail Builder API already
  returns `members[].objectType` — no API change needed, but a new integration point
  for the engine.
- (b) Cache the full trail catalog in-memory on each `trails.built` event (fetch all
  trail members eagerly) — fast at alarm time but potentially large.
- (c) Fetch on-demand per trail as alarms arrive — avoids pre-fetching but adds
  per-alarm latency and makes the index non-precomputed (violates AC40).

  Recommended: option (a) — batch fetch at index-build time per pattern approval and
  `trails.built`. A human must confirm whether the Trail Builder API capacity and
  contract support this usage pattern, and whether bulk enumeration
  (`GET /trails?snapshotId=...`) is sufficient for the engine to enumerate all trails.

**OQ-G4 — AWARENESS: Consuming trails.built is a new dependency for the engine.**
The Correlation Engine currently does not consume `trails.built`. Adding this
consumption introduces a new runtime dependency on Trail Builder (in addition to the
existing Pattern Manager, Codebook Generator, and Knowledge Service dependencies). The
`trails.built` topic and `TrailsBuiltEvent` schema are unchanged. The consumer group id
for this consumer would be `correlation-engine-trails.built` (per the platform
convention `"<service>-<topic>"`). No new topic or schema change is required. A human
should confirm whether this new consumption is approved before design proceeds.

**OQ-G5 — AWARENESS: Incident volume implications.**
Once pattern generalization is active, a single approved pattern may drive simultaneous
incidents across many compatible trails in the network. This is the intended behaviour
(the user's architectural intent: "that is the whole point"). However, the platform
stakeholders should be aware of the volume implication: K compatible trails multiplied
by I simultaneously failing patterns could generate K * I incidents in a burst. This is
not a correctness issue and requires no constraint in the spec, but operators and the
web-ui may need filtering / pagination / aggregation capabilities to manage the incident
view at scale. Flagged for awareness; no spec change required unless a volume cap or
aggregation rule is desired.

**OQ-G6 — AWARENESS: discoveryTrailId on CorrelationResultEvent.**
The spec adds `discoveryTrailId` to the Incident Store record (read-API field only) but
does not add it to `CorrelationResultEvent`. If any downstream consumer of
`correlation.results` (e.g. the Alarm Manager) needs to know the discovery trail for
provenance, that is a contract change: adding a field to `CorrelationResultEvent`
requires `docs/architecture.md` update + human approval. Flagged here; the spec assumes
no downstream consumer needs this field for MVP, and therefore no contract change is
made. A human should confirm this assumption.

**OQ-G7 — AWARENESS: Codebook compatibility.**
The existing codebook decode path also uses a `trailId`-scoped approach (codebook
scenarios are fetched per `trailId` via `GET /codebooks/{codebookId}/trail-signatures`).
Whether the codebook decode path should also generalize (i.e. a codebook scenario should
be a candidate on any compatible trail, not only its scenario's `trailId`) is not
addressed by this spec. For MVP the codebook path is left as-is (per-trailId scenarios).
Generalizing codebook decode is a post-MVP extension and would require the Codebook
Generator to produce trail-generic scenario definitions — a contract change. Flagged for
awareness.

**OQ-G8 — DECISION REQUIRED: Should structural compatibility also consider the
codebook / structural validation metadata from the Pattern Manager?**
The Pattern Manager already performs structural validation during pattern enrichment —
verifying that the pattern's alarm-type objects form a connected dependency path in
the topology. Whether the Correlation Engine should use this existing `structurallyValidated`
flag and the resolved `objectType` affinity from the Pattern Manager (via `PatternView`)
as a richer input to the compatibility rule — rather than re-deriving it from Trail
Builder members — is an open question. Using it would require surfacing the per-alarm-type
`objectType` affinity on `PatternView` (a contract change to the Pattern Manager API). A
human must decide whether to leverage this existing signal or keep the engine's
compatibility rule self-contained from Trail Builder members only.
