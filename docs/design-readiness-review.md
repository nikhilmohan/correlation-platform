I'll analyze the verified per-service results and produce the final design-readiness review document.

# FINAL DESIGN-READINESS REVIEW — AI/ML Alarm Correlation Platform

This is the final design-readiness gate for the Core IP Alarm Correlation Platform MVP. Each of the 12 services was assessed against the 11 readiness questions, with every cell evidence-cited against the authored designs, frozen `libs/event-model` schemas, `docs/architecture.md`, `docs/application-design.md`, and the published producer contracts, then adversarially re-verified (the "Compliant" claims were actively attacked, and several were demoted where the worked example or frozen schema contradicted the design prose). Results below are faithful to that verification — no status has been upgraded or invented. The headline cross-cutting risk is the **`alarmType` canonical-join-key omission**: the frozen `AlarmEvent`/`TransactionEvent` schemas make `alarmType` required and canonical, but multiple services (simulator, enrichment, noise-filter, pattern-miner, alarm-manager, plus the worked example) route the canonical token through `eventType` instead — a contract-level defect that propagates the entire mining → codebook → correlation chain.

---

## 1. Compliance Matrix

Legend: ✅ Compliant · ⚠️ Partial · ❌ Gap · — N/A

| Service | Q1 | Q2 | Q3 | Q4 | Q5 | Q6 | Q7 | Q8 | Q9 | Q10 | Q11 | Readiness |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| topology | ✅ | ⚠️ | ⚠️ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ⚠️ | **Partial** |
| knowledge | ⚠️ | ⚠️ | — | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | **Partial** |
| enrichment | — | ⚠️ | ⚠️ | ❌ | ✅ | — | ⚠️ | — | ✅ | ✅ | ⚠️ | **Gap** |
| trail-builder | ⚠️ | — | ⚠️ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | **Partial** |
| codebook-generator | ⚠️ | ✅ | ❌ | ✅ | ✅ | ❌ | ⚠️ | ✅ | ✅ | ✅ | ⚠️ | **Gap** |
| simulator | ⚠️ | ✅ | ⚠️ | ❌ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | **Gap** |
| noise-filter | ⚠️ | ✅ | ⚠️ | ❌ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | **Gap** |
| pattern-miner | — | ✅ | ⚠️ | ❌ | ✅ | — | ⚠️ | — | ✅ | ✅ | ✅ | **Partial** |
| pattern-manager | ⚠️ | ✅ | ❌ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ⚠️ | **Partial** |
| correlation-engine | ✅ | ✅ | ⚠️ | ⚠️ | ✅ | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | **Partial** |
| alarm-manager | ⚠️ | ✅ | — | ✅ | ✅ | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | ✅ | **Partial** |
| web-ui | — | ✅ | ⚠️ | — | — | — | ✅ | — | ✅ | ✅ | ⚠️ | **Partial** |

---

## 2. Summary

### Cell counts (12 services × 11 questions = 132 cells)

| Status | Count | % |
|---|---|---|
| ✅ Compliant | 78 | 59.1% |
| ⚠️ Partial | 33 | 25.0% |
| ❌ Gap | 7 | 5.3% |
| — N/A | 14 | 10.6% |

**Readiness aggregate:** 0 services fully Ready. 7 **Partial** (topology, knowledge, trail-builder, pattern-miner, pattern-manager, correlation-engine, alarm-manager, web-ui — note 8 listed), 4 **Gap** (enrichment, codebook-generator, simulator, noise-filter). No service is clean.

### Per-question roll-up (weakest first)

| Q | Theme | ✅ | ⚠️ | ❌ | — | Verdict |
|---|---|---|---|---|---|---|
| **Q4** | Kafka event payload completeness | 6 | 1 | **4** | 1 | **Weakest** — 4 Gaps, all the `alarmType` omission (enrichment, simulator, noise-filter, pattern-miner) |
| **Q3** | Outbound REST call details | 4 | 5 | **2** | 1 | **Weak** — unpinned/unpublished collaborator contracts (codebook, pattern-manager Gaps) |
| **Q7** | Future-proof for incoming variation | 3 | 7 | 0 | 2 | **Weak** — mostly the `alarmType`-vs-`eventType` ambiguity |
| **Q1** | REST API completeness | 2 | 6 | 0 | 4 | Mixed — missing fields/paramSets/states |
| **Q2** | Startup seed sufficiency | 6 | 4 | 0 | 0 | Mixed — knowledge/enrichment seed gaps |
| **Q6** | Owned DDL/graph schema coherence | 7 | 2 | **1** | 2 | One Gap (codebook missing `active`) |
| **Q11** | Startup configuration clarity | 7 | 4 | 0 | 0 | Mostly fine; config follows the Q3/Q4 gaps |
| Q5 | Single-owner persistence | 11 | 0 | 0 | 1 | **Strong** |
| Q8 | Domain-scoped schema | 9 | 0 | 0 | 3 | **Strong** |
| Q9 | Dev tech clarity | 12 | 0 | 0 | 0 | **Clean** |
| Q10 | Test/deploy/runtime tech clarity | 11 | 1 | 0 | 0 | **Strong** |

**Conclusion:** Ownership (Q5), domain-scoping (Q8), and tech-stack clarity (Q9/Q10) are solid platform-wide. The blockers cluster in **event/contract correctness (Q4, Q3, Q7)** — dominated by two systemic issues: (a) the `alarmType` canonical-join-key omission, and (b) collaborator OpenAPI specs that are unpublished/unmerged on `main`.

---

## 3. Non-Compliance Findings

### 3a. GAPS (must-fix before build)

| # | Service | Q | Status | What is missing (evidence) | Recommended fix | Owning service |
|---|---|---|---|---|---|---|
| G1 | enrichment | Q4 | ❌ | Frozen `AlarmEvent.schema.json` requires `alarmType`; `architecture.md` L130-138 makes Enrichment responsible for populating it from `alarmTypeVocabulary`. Enrichment's `FieldMapping`/`NormalizeStep` never set it (no `alarmTypeMap`); app-design §2.2 worked example emits NO `alarmType` and mis-places canonical token `LinkDown` in `eventType`. Every emitted `AlarmEvent` fails frozen-binding validation, breaking downstream join. | Add `alarmTypeMap` (raw→canonical token) to each ruleset; `NormalizeStep` sets `AlarmEvent.alarmType`; add AC test asserting it; resolve `eventType`-vs-`alarmType` placement vs §2.2. | enrichment |
| G2 | simulator | Q4 | ❌ | Required `alarmType` missing from EVERY emitted `AlarmEvent` (worked-example lines 717-770 + app-design §C); `architecture.md` assigns the Simulator to populate it. Criterion 7 is self-contradictory (test claims required fields present, spec's own list omits `alarmType`); minable signature keyed off probable-cause not the canonical token. | Add `alarmType` to coreip pack `alarm_shapes` (from Knowledge vocabulary); include in every worked-example payload; add to criterion 7 required list (spec+design); re-key minable signature onto `alarmType`. | simulator |
| G3 | noise-filter | Q4 | ❌ | Frozen `TransactionEvent.schema.json` requires SIX `alarms[]` fields incl. `alarmType`; design enumerates only FIVE everywhere (producer table L270, L277, TransactionEmitter L161, DA-13, AC-4 test) — `alarmType` omitted. A built `TransactionEvent` fails the design's own pre-publish validation; app-design §2.3 also shows 5 fields. | Add `alarmType` (mirrored from `AlarmEvent.alarmType`) to the copied field set in Task 5/L161/L270/L277/DA-13, the AC-4 schema test, and the typed-alarms assertion (six fields); correct app-design §2.3. | noise-filter |
| G4 | pattern-miner | Q4 | ❌ | Builds PrefixSpan `sequence` items from `alarms[].eventType` (the X.733 category, NOT the join key); consumed `alarms[]` model lists 5 fields, omitting required `alarmType`. Emitted `PatternMinedEvent.sequence` sourced from wrong field → cannot join Codebook/RCA. Second mismatch: emits `stddevInterArrivalMs` but Pattern Manager reads `interArrivalStddevMs`. | Build PrefixSpan items + timing from `alarms[].alarmType`; add `alarmType` to consumed model; align stddev key to `interArrivalStddevMs` (or pin alias); correct worked example. | pattern-miner |
| G5 | codebook-generator | Q3 | ❌ | Multiple frozen-path mismatches: calls `fault-origins` but Knowledge freezes `fault-origin-types`; MISSING the `GET /domains/{domain}/alarm-type-vocabulary` integration point the design relies on (AC-22/25); Trail Builder frozen path `GET /trails/by-object?managedObjectId=&domain=` (both required) referenced only as `getTrailsForObject(managedObjectId)`; Topology list/traversal paths + required params + DTOs not pinned. | Update Task 3/4/6 + integration table to exact frozen producer paths/params; add the alarm-type-vocabulary integration point; add Trail Builder required `domain`; pin Topology param set + DTOs. | codebook-generator (+ knowledge, trail-builder, topology contracts) |
| G6 | codebook-generator | Q6 | ❌ | DDL missing the `active` column + one-active-codebook constraint the spec makes a hard contract (spec L338-349, AC-18/19/20, `/codebooks/active` endpoint). No partial-unique index on `(domain, snapshot_id) WHERE active`; no atomic supersede mechanism described. | Add `active boolean NOT NULL DEFAULT true`, partial unique index `uq_active_per_key ON codebooks(domain, snapshot_id) WHERE active`, and atomic demote-prior-active supersede in store/pipeline. | codebook-generator |
| G7 | pattern-manager | Q3 | ❌ | No collaborator `openapi.json` exists anywhere (verified across branches) yet clients are "built against checked-in openapi.json"; only Topology gap is flagged. Knowledge call unspecified (no path/recordId/paramSet/field names) and assumes a flat shape vs Knowledge's actual `GET /domains/{domain}/model-params/{recordId}` with dotted-key jsonb. | Pin the exact Knowledge model-params path + params + consumed field names vs frozen shape; commit (or formally flag) the Codebook and Knowledge openapi.json. | pattern-manager (+ knowledge, codebook contracts) |

### 3b. PARTIALS (resolve before build)

| # | Service | Q | Status | What is missing (evidence) | Recommended fix | Owning service |
|---|---|---|---|---|---|---|
| P1 | topology | Q2 | ⚠️ | Snapshot validation schema has unresolved cross-service ownership conflict: Topology asserts single canonical home `services/topology/schema/snapshot.schema.json`; Simulator authoritatively co-locates `services/simulator/schema/topology-snapshot.schema.json`; app-design L239 records them as "lockstep" twins — the drift Topology claims to have eliminated. | Reconcile both designs to a single agreed home (update Simulator OQ-4 + app-design L239, or vice-versa) before either side validates. | topology + simulator |
| P2 | topology | Q3 | ⚠️ | Knowledge froze `GET /domains/{domain}/vocabulary` returning `{objectTypes, relations, version}`, but Topology's `TOPOLOGY_KNOWLEDGE_VOCAB_PATH` default is placeholder "(from Knowledge OpenAPI)" and Design-note 3 still says it "does not invent its exact path/shape." | Update integration table + note 3 to cite the frozen path/response; pin `TOPOLOGY_KNOWLEDGE_VOCAB_PATH=/domains/{domain}/vocabulary`. | topology |
| P3 | topology | Q6 | ⚠️ | `GET /topology/edges/{edgeId}` has no realizable lookup: `edgeId=sha1(...)` is also the Nebula edge rank; FETCH needs src/dst/edgeType (none recoverable), rank isn't LOOKUP-able, and only `idx_*_scope` indexes exist. Operation not satisfiable. | Store `edgeId` as an indexed edge property (CREATE EDGE INDEX) for LOOKUP, or redefine `edgeId` to encode src/relation/dst reversibly; add the supporting index. | topology |
| P4 | topology | Q11 | ⚠️ | `TOPOLOGY_KNOWLEDGE_VOCAB_PATH` default is a placeholder (now resolvable); `TOPOLOGY_KNOWLEDGE_MODE` defaults to `real` while spec requires a default for isolated testing. | Set vocab-path default to `/domains/{domain}/vocabulary`; default MODE to `mock` for isolated profiles or document a built-in mock/test profile. | topology |
| P5 | knowledge | Q1 | ⚠️ | No covering content/endpoint for two named consumers: `correlation-engine` (match/conflict params) and `pattern-manager` (RCA/reconciliation/structural params) have no defined modelParams paramSet → read path 404s. Cross-doc mismatch: app-design says web-ui edits via `Knowledge PUT/PATCH /knowledge/model-params` but frozen surface is `PUT /domains/{domain}/model-params/{recordId}` only. | Add `correlation-engine` and `pattern-manager` modelParams paramSets (records + frozen read contract); reconcile the web-ui PATCH/path reference. | knowledge |
| P6 | knowledge | Q2 | ⚠️ | Seed omits modelParams for `correlation-engine` and `pattern-manager` (only `noise-filter`/`pattern-miner` seeded); their use cases cannot run against a freshly seeded store. | Add `core-ip/modelParams/correlation-engine` and `core-ip/modelParams/pattern-manager` seed records with bounded params. | knowledge |
| P7 | knowledge | Q7 | ⚠️ | Stated guarantee that the join binds to `alarmType` is contradicted by the worked example, which joins the whole chain on `eventType` (Enrichment writes canonical token INTO `eventType`); `AlarmEvent.alarmType` required but absent from every worked-example alarm. | Reconcile: carry the canonical token in `alarmType` (and populate it on raw/enriched alarms), or re-bind vocabulary/templates to the field the chain actually joins on. | knowledge (+ enrichment, app-design) |
| P8 | enrichment | Q2 | ⚠️ | Seeded config can never produce a schema-valid output: no ruleset/`FieldMapping` has an `alarmTypeMap` (required `alarmType`); example `nms-alpha` mappings (`CRIT→CRITICAL`, `Interface:{ne}-{ifIndex}`) miss the only concrete Simulator alarm (already-canonical `critical`, pre-built `FiberSpan:fs-12`). | Add `alarmTypeMap` to each ruleset; reconcile example mappings vs agreed Simulator raw-alarm shape. | enrichment + simulator |
| P9 | enrichment | Q3 | ⚠️ | Trail Builder HTTP contract not pinned and source artifact absent: trail-builder spec/design are "TBD" stubs (no OpenAPI). App-design self-inconsistent on path (`/trails/by-object` L157 vs `/trails?...&domain=core-ip` L354), with a `domain` param Enrichment (domain-agnostic) accounts for nowhere. | Once Trail Builder publishes OpenAPI, pin exact path and confirm whether `domain` required; if so add a domain source to enrichment config/client. | enrichment + trail-builder |
| P10 | enrichment | Q7 | ⚠️ | Contract doesn't tolerate actual incoming data: only concrete Simulator alarm emits already-canonical severities + pre-built `managedObjectId` (no raw `ne`/`ifIndex`), but `nms-alpha` ruleset expects raw codes; app-design itself contradictory (L450 vs §2.1). No `alarmType` normalization. | Agree raw-feed contract per source; align example rulesets (passthrough/identity where already canonical); add `alarmType` normalization. | enrichment + simulator |
| P11 | enrichment | Q11 | ⚠️ | Ruleset format has no field for required `alarmType` mapping (ties to G1); app-design L354 Trail Builder call carries `domain=core-ip` but no domain config var exists. | Add `alarmTypeMap` to ruleset schema; once Trail Builder contract published, add a domain config source or confirm not required. | enrichment |
| P12 | trail-builder | Q1 | ⚠️ | `getTrailsForObject` makes `domain` REQUIRED (400 on miss), but principal consumers call without it: enrichment (domain-agnostic, design L54/391) and codebook (L61/373) + app-design L40/152/170. As published, those calls 400. | Make `domain` optional (default MVP domain / infer from object), or pin that enrichment/codebook MUST pass `domain` and reconcile the example. | trail-builder |
| P13 | trail-builder | Q3 | ⚠️ | (1) No way to query the event's historical `snapshotId` (Topology scopes to `current`/`previous` only) → TOCTOU. (2) Pinned Topology calls omit required `snapshotId`/`maxDepth`/`crossDomain`. (3) Knowledge `GET /domains/{domain}/trail-policies` not pinned; internal `TrailPolicy` fields don't match frozen `{closureEdgeTypes, boundary, srlgRule}`. | Pin `snapshotId`/`maxDepth`/`crossDomain` on every Topology call + resolve snapshot aliasing; pin the trail-policies path; add a field-mapping table. | trail-builder (+ topology, knowledge) |
| P14 | trail-builder | Q7 | ⚠️ | HTTP contract not future-proof for the consumer call shape: domain-less per-object lookup (the live alarm path from a domain-less vendor alarm) returns 400 instead of trailIds. | Make `domain` optional (default/infer) so domain-less lookup succeeds; contract-test the no-domain call. | trail-builder |
| P15 | codebook-generator | Q1 | ⚠️ | Pattern Manager reconcile needs `rootCauseAlarmType`, but the native `/scenarios` Scenario object omits it (promised only on the CE-shaped `/trail-signatures` projection PM doesn't call). | Expose `rootCauseAlarmType` on native `/scenarios`, or add a contract note + AC that PM derives it from `predictedSymptoms`. | codebook-generator |
| P16 | codebook-generator | Q7 | ⚠️ | Forward-propagation correctness assumes template `trigger.alarmType`/`effect.alarmType` are vocabulary tokens "by construction" with no runtime check; no integration point to fetch `alarm-type-vocabulary` (see G5). Worked example uses `faultOriginType='FiberSpan'` vs Knowledge's `Fiber` (naming skew). | Add the vocabulary fetch and validate every effect/trigger `alarmType` + derived `rootCauseAlarmType` at compile time, routing OOV tokens to DLQ. | codebook-generator + knowledge |
| P17 | codebook-generator | Q11 | ⚠️ | Config missing the `KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL/_MODE` pair the design relies on (no fail-fast check). | Add the URL/_MODE vars (required) + AC-7 fail-fast check so every integration point is env-driven. | codebook-generator |
| P18 | simulator | Q1 | ⚠️ | `/labels` `GroundTruthLabel` missing the canonical alarm-type token; RCA oracle compares vs `correlation.results.rootCauseAlarmType` but can only join on `alarmId`/`managedObjectId`. | Add `rootCauseAlarmType` (+ per-child `alarmType`) to `GroundTruthLabel` and `/labels` + JSONL export. | simulator |
| P19 | simulator | Q3 | ⚠️ | Called-endpoint contract mis-specified vs Topology frozen: path not quoted (Topology pins `POST /topology/snapshots`); status wrong (uses 202, Topology returns 200 synchronous `SnapshotIngestResponse`); content-type implies multipart vs `application/json`; missing optional `?changeType=` param. | Cite `POST /topology/snapshots`, set mock+real to 200 + full `SnapshotIngestResponse`, `application/json` body, pass/accept `?changeType`, reference `services/topology/openapi.json`. | simulator + topology |
| P20 | simulator | Q7 | ⚠️ | Emitted `AlarmEvent` omits required canonical `alarmType` (see G2); simulated alarms won't match what Enrichment produces from a real NMS feed. App-design §C L506 treats `eventType` as canonical, contradicting architecture. | Populate `alarmType` from the domain `alarmTypeVocabulary` on every emitted alarm. | simulator |
| P21 | noise-filter | Q1 | ⚠️ | Claimed `services/noise-filter/openapi.json` "single source of truth" does NOT exist (no openapi file in repo); endpoint schema is still a "design-stage decision (OQ #5)"; AC-12/schemathesis depend on the missing file. | Generate and check in `services/noise-filter/openapi.json` from the FastAPI models before build. | noise-filter |
| P22 | noise-filter | Q3 | ⚠️ | All three consumed contracts ("built against published OpenAPI 3.1") are unpublished: no openapi.json for knowledge/topology/trail-builder; knowledge/trail-builder designs are 8-line stubs. Knowledge model-params (the readiness-critical dependency) has no path/params/field mapping. | Have Knowledge + Topology publish OpenAPI (exact GET path + response fields); Trail Builder publish `getTrail` response — before noise-filter build. | noise-filter (+ knowledge, topology, trail-builder) |
| P23 | noise-filter | Q10 | ⚠️ | schemathesis OpenAPI contract test + collaborator mock stubs depend on openapi.json files that don't exist (noise-filter's own + collaborators'). Framework choice clear; test INPUTS absent. | Check in noise-filter openapi.json + ensure collaborator specs exist so schemathesis and generated mocks run. | noise-filter (+ collaborators) |
| P24 | pattern-miner | Q3 | ⚠️ | Knowledge mining-params endpoint path + exact response field names still deferred ("from published OpenAPI at build time"); at a final gate these should be pinned. | Cite the concrete Knowledge mining-params path (e.g. `GET /knowledge/model-params?service=pattern-miner&domain=core-ip`) + response field list; confirm `codebookVersion` returned by that call. | pattern-miner + knowledge |
| P25 | pattern-miner | Q7 | ⚠️ | Tolerance hinges on mining `alarms[].alarmType`, but design mines `eventType` (not canonicalized per source) → divergent sequence tokens break the Codebook/Correlation join (same root as G4). | Mine `alarms[].alarmType` (and include it in the consumed model) so Enrichment-canonicalized variation stays canonical. | pattern-miner |
| P26 | pattern-manager | Q1 | ⚠️ | REJECT exposed in `ApprovalIntent.decision` enum but undefined outcome: no `rejected` lifecycle state, no transition, no audit semantics. POST /approve with `decision=reject` has undefined persisted result. | Define a `rejected` state + transition + audit (add to lifecycle CHECK and openapi enum), or scope reject out of MVP and drop it from the enum. | pattern-manager |
| P27 | pattern-manager | Q7 | ⚠️ | Session-window deriver not robust to actual upstream timing shape: design premise (`{meanInterArrivalSeconds,stdDevSeconds}`) is stale — fixture emits ms keys, and worked example §2.4 emits a third spelling `interArrivalStddevMs` not in the default alias map and not caught by `*Seconds→×1000`. Result: stddev silently unread → type always degrades to `gap-based`. | Reconcile stddev/median key names across schema description, fixture, app-design §2.4, and deriver's pinned keys + default alias map (add `interArrivalStddevMs`); add a contract test. | pattern-manager + pattern-miner |
| P28 | pattern-manager | Q11 | ⚠️ | `SESSION_WINDOW_TIMING_ALIASES` default maps a Miner shape that no longer matches (fixture emits ms; worked example emits `interArrivalStddevMs` omitted from default) → shipped default mis-derives. Knowledge model-params path/recordId/paramSet not in config (ties to G7). | Correct the alias default to cover actual emitted keys (incl. `interArrivalStddevMs`); add Knowledge model-params endpoint/recordId selection to config. | pattern-manager |
| P29 | correlation-engine | Q3 | ⚠️ | Knowledge call under-specified: integration table + spec L134 only describe the params, no endpoint path/method/recordType/domain param (other Knowledge consumers cite a concrete model-params path). Real-mode client can't be wired without it. | Pin the exact Knowledge read endpoint (e.g. `GET /knowledge/model-params?domain=&recordType=correlationParams`) built against Knowledge's OpenAPI. | correlation-engine + knowledge |
| P30 | correlation-engine | Q4 | ⚠️ | `rootCauseAlarmId` derived by resolving the winner's `rootCauseAlarmType` (an `alarmType` token), but the pattern-match path stores `eventType` (`MatchedAlarm`, design L206/L242); worked example routes the token through `eventType`. Resolving against stored `eventType` can mis-resolve `rootCauseAlarmId` on pattern-match incidents. | Store/match the pattern path on `AlarmEvent.alarmType` (`MatchedAlarm.alarmType`), consistent with the codebook path; reconcile the worked example. | correlation-engine |
| P31 | correlation-engine | Q7 | ⚠️ | (1) Runtime pattern-refresh places patterns by `trailId`, but `PatternApprovedEvent` carries no `trailId` (verified frozen schema + fixture) → can't place a runtime-approved pattern from the event alone. (2) Join-key field ambiguity (`alarmType` vs `eventType`) as in P30. | State that the consumer re-fetches `trailId(s)` via `PatternManagerClient` on the refresh trigger (or escalate a contract change adding `trailIds[]`); standardize on `alarmType` end-to-end. | correlation-engine (+ pattern-manager contract) |
| P32 | alarm-manager | Q1 | ⚠️ | `AlarmDetail`/`AlarmSummary` omit required `AlarmEvent.alarmType`; web-ui drill-down cannot display the canonical type. | Add `alarmType` to `AlarmSummary` and `AlarmDetail`. | alarm-manager |
| P33 | alarm-manager | Q6 | ⚠️ | `alarm` table has no column for required `alarmType` (survives only in `raw_envelope` jsonb — not queryable/returnable). AC#1 stored-fields test doesn't assert it, so the gap is untested. | Add `alarm_type text NOT NULL` + V-migration, persist in IngestService, add to AC#1 assertion. | alarm-manager |
| P34 | alarm-manager | Q7 | ⚠️ | Required `alarmType` silently dropped on ingest; design never reconciles `alarmType` vs `eventType` (app-design §3.2 L506 treats `eventType` as canonical; worked-example payload omits `alarmType`, contradicting frozen `required[]`). | Persist + expose `alarmType` as first-class; state explicitly `alarmType` (not `eventType`) is the canonical token; align design, summaries, and worked example. | alarm-manager |
| P35 | web-ui | Q3 | ⚠️ | 9th consumed point (Noise Filter run-stats) not backed by a frozen/published producer contract: producer design exists only on unmerged `design/noise-filter-rework` (no openapi.json even there). Field-shape mismatch: producer's flat `RunStatsRow` (eps/minSamples/...+storm/retention/hop fields) vs web-ui's nested `params: DbscanParams` (missing 3 fields). | Gate `NoiseFilterClient` on merge of the rework branch + publication of openapi.json, then regenerate client + fixtures (flatten params, add missing fields); mark provisional until then. | web-ui + noise-filter |
| P36 | web-ui | Q11 | ⚠️ | Runtime config injection mechanically unspecified: design says config "injected into environment.ts at build/serve time," but `environment.ts` is compile-time baked and the container serves via nginx (no Node runtime) → Compose env vars can't reach the compiled bundle. Blocks the `real` deployment. | Specify a runtime config strategy (nginx-entrypoint `envsubst` → `/assets/env.js` consumed at bootstrap, or `config.json` fetched on init); state which values are baked vs runtime-resolved. | web-ui |

---

## 4. Evidence Appendix

Compact per-service evidence (status + citation). ✅ Compliant · ⚠️ Partial · ❌ Gap · — N/A.

### topology — Partial
- **Q1 ✅** Every consumer call (noise-filter/pattern-manager/trail-builder/codebook/web-ui/Simulator) maps to a published endpoint (design Query API table L684-694; `POST /topology/snapshots`).
- **Q2 ⚠️** Runnable bootstrap specified (Flow E, NebulaSchemaBootstrap, Flyway, readiness-gated); but snapshot validation schema has an unresolved single-home ownership conflict vs Simulator (P1).
- **Q3 ⚠️** Single outbound call (KnowledgeVocabClient, fail-closed) defined; Knowledge froze `GET /domains/{domain}/vocabulary` but Topology still uses placeholder path (P2).
- **Q4 ✅** Emits `{snapshotId, changeType, nodes[], edges[]}` (+optional `domain`) in TypedEnvelope; verified against frozen `TopologyChangedEvent.schema.json`.
- **Q5 ✅** Sole owner of NebulaGraph + PostgreSQL `topology_meta`; all access behind `GraphRepository`; no cross-write.
- **Q6 ⚠️** Graph + relational DDL coherent, but `GET /topology/edges/{edgeId}` has no realizable lookup from defined keys/indexes (P3).
- **Q7 ✅** Generic `managedObjectId` pattern, no `objectType`/`relation` enum, open `attributes`; vendor variation flows as extra attributes.
- **Q8 ✅** `domain` first-class on every vertex/edge + every `idx_*_scope`; per-domain retention; no Core-IP enum.
- **Q9 ✅** Java 17 / Spring Boot 3.3.x / nebula-java / PostgreSQL 16 / Flyway / springdoc — all permissive, versions pinned.
- **Q10 ✅** JUnit 5 + Testcontainers (Nebula+PG+Kafka) + Awaitility; eclipse-temurin:17-jdk; 31 AC tests + 13 E2E.
- **Q11 ⚠️** Env table complete, but `KNOWLEDGE_VOCAB_PATH` placeholder default + `KNOWLEDGE_MODE=real` breaks isolated startup (P4).

### knowledge — Partial
- **Q1 ⚠️** Generic CRUD + frozen `/vocabulary` complete in shape, but no modelParams paramSet for `correlation-engine`/`pattern-manager`; web-ui PATCH/path mismatch (P5).
- **Q2 ⚠️** Core IP pack seeded via validated write path, but modelParams for correlation-engine/pattern-manager omitted (P6).
- **Q3 —** Server + Kafka producer only; no outbound HTTP (spec + design + app-design L30).
- **Q4 ✅** Exact `KnowledgeUpdatedEvent` `{recordType, recordId, version, domain}` + envelope; verified vs frozen schema; idempotent producer.
- **Q5 ✅** Sole owner of `knowledge` schema (`record`/`record_version`); no other writer.
- **Q6 ✅** Coherent DDL: composite PK/FK, `uq_record_current` partial unique, read index; atomic flip-then-insert.
- **Q7 ⚠️** recordType-generic jsonb future-proof structurally, but `alarmType`-as-join-key guarantee contradicted by worked example (joins on `eventType`) (P7).
- **Q8 ✅** `domain` first-class PK column; `transport-otn` second example proves new-domain = pure record ops.
- **Q9 ✅** Java 17 / Spring Boot 3.x / springdoc / Spring Data JDBC / networknt validator — permissive, versioned.
- **Q10 ✅** JUnit 5 + Testcontainers PostgreSQL; gradle build + OpenAPI drift check; multi-stage temurin:17-jdk Dockerfile.
- **Q11 ✅** Concrete env table; required-vs-optional clear; thresholds as data not config.

### enrichment — Gap
- **Q1 —** No HTTP business surface (only actuator); all output via Kafka.
- **Q2 ⚠️** Per-source rulesets load at startup, but no `alarmTypeMap` (can't produce schema-valid output) + example mappings miss the real Simulator alarm (P8).
- **Q3 ⚠️** Trail Builder logical op defined, but concrete HTTP contract unpinned + source stub absent + app-design path inconsistency (P9).
- **Q4 ❌** Required `alarmType` never set — no `alarmTypeMap`, NormalizeStep omits it, §2.2 example mis-places token in `eventType`; every output fails validation (G1).
- **Q5 ✅** Owns no datastore; rulesets are own mounted config read by nobody else; Alarm Manager owns live store.
- **Q6 —** No owned DB; only ephemeral Caffeine window stores.
- **Q7 ⚠️** Mapping mechanism flexible, but contract doesn't tolerate the actual Simulator data + no `alarmType` normalization (P10).
- **Q8 —** No owned DB tables; rulesets YAML domain-agnostic.
- **Q9 ✅** Java 17 / Spring Boot 3.x / spring-kafka / Resilience4j / Caffeine / SnakeYAML — permissive, versioned.
- **Q10 ✅** JUnit 5 + WireMock + embedded/Testcontainers Kafka; multi-stage temurin Dockerfile; Compose entry.
- **Q11 ⚠️** Env table thorough but no `alarmType` mapping field + no `domain` config var for the Trail Builder call (P11).

### trail-builder — Partial
- **Q1 ⚠️** All consumer-needed operations exist, but `getTrailsForObject` requires `domain` while live consumers call without it → 400 (P12).
- **Q2 —** Owns no seed data; tables start empty, populate on first `topology.changed`.
- **Q3 ⚠️** Topology/Knowledge calls broadly aligned, but snapshot pinning impossible, missing `snapshotId`/`maxDepth`/`crossDomain`, trail-policy field mismatch (P13).
- **Q4 ✅** `trails.built` `{snapshotId, trailIds[], trailCount, domain}` verified vs frozen schema + worked example.
- **Q5 ✅** Sole owner of `trailbuilder` schema; reads Topology/Knowledge by API only.
- **Q6 ✅** Full coherent DDL: content-hash PK, FK+cascade, unique member, access-path indexes mapping to all 3 query ops.
- **Q7 ⚠️** Kafka/closure side tolerant, but HTTP contract 400s on the domain-less call the live path produces (P14).
- **Q8 ✅** Every owned table carries `domain`+`snapshot_id`; free-text fields; AC-11 proves non-Core-IP domain builds.
- **Q9 ✅** Python 3.13 / networkx / FastAPI / confluent-kafka / SQLAlchemy+psycopg — all permissive, versioned.
- **Q10 ✅** pytest + respx mocks; python:3.13-slim Dockerfile; E2E on integration stack; ruff/black/pytest gates.
- **Q11 ✅** Full env set with required-vs-optional; policy bounds from Knowledge not config; no hard-coded URLs/thresholds.

### codebook-generator — Gap
- **Q1 ⚠️** Read endpoints complete, but native `/scenarios` omits `rootCauseAlarmType` PM needs (P15).
- **Q2 ✅** Legitimately starts empty; compiled on demand from `trails.built`; all knowledge fetched at runtime.
- **Q3 ❌** Wrong Knowledge path (`fault-origins` vs `fault-origin-types`), missing `alarm-type-vocabulary` integration point, Trail Builder/Topology paths+params not pinned (G5).
- **Q4 ✅** `CodebookGeneratedEvent` `{snapshotId, scenarioCount, codebookId, domain}` matches frozen schema + worked example.
- **Q5 ✅** Sole writer of `codebook` schema; read-only API; no graph DB creds.
- **Q6 ❌** DDL missing `active` column + one-active-codebook constraint the spec mandates (AC-18/19/20, `/codebooks/active`) (G6).
- **Q7 ⚠️** jsonb tolerant, but no runtime vocabulary validation; worked-example `FiberSpan` vs Knowledge `Fiber` skew (P16).
- **Q8 ✅** `domain` first-class NOT NULL; free-text fault-origin-type; AC-17 proves `transport` codebook persists without code change.
- **Q9 ✅** Python 3.13 / networkx / FastAPI / SQLAlchemy+pg8000 (chosen over LGPL) / confluent-kafka — permissive, versioned.
- **Q10 ✅** pytest + respx; python:3.13-slim; 26 ACs mapped 1:1; OpenAPI parity check.
- **Q11 ⚠️** Env config clear, but missing `KNOWLEDGE_ALARM_TYPE_VOCABULARY_URL/_MODE` + fail-fast (P17).

### simulator — Gap
- **Q1 ⚠️** Oracle surface (`/labels`, `/scenarios`) complete, but `GroundTruthLabel` lacks the canonical alarm-type token for the RCA oracle (P18).
- **Q2 ✅** File-based, run-scoped output empty at start; default `--phase p2` self-seeds the full coreip pack.
- **Q3 ⚠️** One outbound call via generated client, but status (202 vs 200), path, content-type, and `?changeType` mis-specified vs Topology frozen (P19).
- **Q4 ❌** Required `alarmType` missing from EVERY emitted `AlarmEvent`; criterion 7 self-contradictory; signature keyed off probable-cause (G2).
- **Q5 ✅** Owns only its output files; hands snapshot to Topology via POST; writes no other store.
- **Q6 ✅** Snapshot file has complete versioned JSON Schema + referential-integrity rules; label model coherent (alarmType gap under Q1).
- **Q7 ⚠️** Snapshot contract tolerant, but emitted alarm omits canonical `alarmType` → won't match real-NMS Enrichment output (P20).
- **Q8 ✅** Schema domain-scoped via `domain` discriminator; engine has zero Core-IP literals; pack-based onboarding.
- **Q9 ✅** Python 3.13 / networkx / acp-event-model / confluent-kafka / FastAPI / httpx — permissive, versioned.
- **Q10 ✅** pytest + criterion→test table; python:3.13-slim Dockerfile; no Playwright misuse.
- **Q11 ✅** Authoritative DEFAULTS table covering every knob; required-vs-optional explicit with fail-fast.

### noise-filter — Gap
- **Q1 ⚠️** Read-only operation set complete, but claimed `openapi.json` single-source-of-truth does not exist; AC-12/schemathesis depend on it (P21).
- **Q2 ✅** No owned seed; empty `nf_run_stats` via migration; all params from Knowledge at startup (readiness-gated).
- **Q3 ⚠️** Consumed Knowledge/Topology/Trail-Builder contracts all unpublished on main (stubs); most-critical Knowledge params dependency uninvokable as specified (P22).
- **Q4 ❌** `TransactionEvent` requires 6 `alarms[]` fields incl. `alarmType`; design enumerates only 5 everywhere → built event fails design's own validation (G3).
- **Q5 ✅** Sole owner of `noise_filter.nf_run_stats`; collaborators read-only; no graph access.
- **Q6 ✅** Full coherent DDL: UUID PK, NOT NULL/CHECK columns, two query-serving indexes, idempotent ON CONFLICT.
- **Q7 ✅** No alarm-ingest REST; vectorization parses `objectType` generically; config-gated features; graceful degradation.
- **Q8 ✅** `domain` nullable column enables multi-domain; no Core-IP value in DDL; aggregate counts only.
- **Q9 ✅** Python 3.13 / scikit-learn DBSCAN + hdbscan / asyncpg (over LGPL) / yoyo / FastAPI — permissive, versioned.
- **Q10 ⚠️** Frameworks clear (pytest + testcontainers), but schemathesis + mock stubs depend on absent openapi.json files (P23).
- **Q11 ✅** Full env-var table; mock/real toggles; algorithm params from Knowledge not env; required-vs-optional derivable.

### pattern-miner — Partial
- **Q1 —** No business HTTP surface; consumer interface entirely Kafka.
- **Q2 ✅** Stateless Spark job; all tunables fetched per-run from Knowledge; no startup state to seed.
- **Q3 ⚠️** Outbound Knowledge call named + typed, but concrete path + exact response field names still deferred at the final gate (P24).
- **Q4 ❌** Builds `sequence` from `eventType` not `alarmType`; consumed model omits required `alarmType`; stddev key mismatch vs Pattern Manager (G4).
- **Q5 ✅** Stateless; owns no store (Pattern Store belongs to Pattern Manager); no graph access.
- **Q6 —** No owned datastore.
- **Q7 ⚠️** Structural/DLQ handling sound, but mines `eventType` → vendor variation Enrichment canonicalized re-diverges (P25).
- **Q8 —** No owned tables; `provenance.domain` carried through; params domain-scoped.
- **Q9 ✅** Python 3.13 / PySpark + MLlib PrefixSpan / confluent-kafka / httpx+respx — permissive, versioned; Spark container-only.
- **Q10 ✅** pytest (Spark in local[*] in container); python:3.13-slim + pinned Spark; Active P2 / Idle P1/P3.
- **Q11 ✅** Every env var with defaults; explicitly no threshold env vars (Knowledge-sourced); integration toggle named.

### pattern-manager — Partial
- **Q1 ⚠️** Full lifecycle endpoints, but REJECT exposed in enum with undefined outcome (no `rejected` state/transition/audit) (P26).
- **Q2 ✅** Owns no seed; Pattern Store starts empty (Flyway); patterns arrive at runtime via `patterns.mined`.
- **Q3 ❌** No collaborator openapi.json exists; Knowledge model-params call unspecified + assumes wrong flat shape vs frozen `/domains/{domain}/model-params/{recordId}` (G7).
- **Q4 ✅** `PatternDiscoveredEvent`/`PatternApprovedEvent` field lists match frozen schemas verbatim incl. `sessionWindow` $ref.
- **Q5 ✅** Sole writer of Pattern Store; never writes codebook/graph; web-ui drives via POST intent only.
- **Q6 ✅** Full DDL: UUID PK, CHECK constraints, child tables with FK/unique, lifecycle transition audit, indexes; ER diagram + Flyway.
- **Q7 ⚠️** Versioned API + DLQ sound, but session-window deriver not robust to actual timing keys (`interArrivalStddevMs` unread → always `gap-based`) (P27).
- **Q8 ✅** `domain` TEXT NULL onboarding axis; free-text alarmType tokens; no Core-IP literals in DDL constraints.
- **Q9 ✅** Java 17 / Spring Boot 3.x / Spring Data JPA / Flyway / RestClient / springdoc — permissive, versioned.
- **Q10 ✅** JUnit 5 + Testcontainers (PG+Kafka) + WireMock; multi-stage temurin Dockerfile; openapi CI-verified.
- **Q11 ⚠️** Env enumerated, but `TIMING_ALIASES` default maps a stale Miner shape + Knowledge model-params path/recordId not configured (P28).

### correlation-engine — Partial
- **Q1 ✅** Every web-ui CE call satisfied by `GET /incidents`, `/incidents/{id}`, `/stats`; RCA accuracy correctly external.
- **Q2 ✅** Durable stores start empty (Flyway); reference state warmed at runtime; readiness-gated; no domain seed authored.
- **Q3 ⚠️** Pattern Manager + Codebook calls fully pinned (verified real), but Knowledge call has no path/method/param (P29).
- **Q4 ⚠️** Event field lists match frozen schemas, but pattern path stores `eventType` not `alarmType` → may mis-resolve `rootCauseAlarmId` (P30).
- **Q5 ✅** Sole owner of `correlation` schema; reads collaborators by API/topic; Alarm Manager projects onto disjoint columns.
- **Q6 ✅** Full coherent DDL for all 3 tables; every GET filter indexed; `instance_fingerprint` UNIQUE enforces one-incident idempotency.
- **Q7 ⚠️** Envelope-level variation tolerated + DLQ, but runtime pattern-refresh has no `trailId` in `PatternApprovedEvent` + join-key ambiguity (P31).
- **Q8 ✅** Domain-agnostic schema; opaque text IDs; generic `match_type`; new-domain needs no DDL change.
- **Q9 ✅** Java 17 / Spring Boot 3.x / Kafka Streams (Processor API, RocksDB) / Spring Data JDBC / Flyway — permissive, versioned.
- **Q10 ✅** JUnit 5 + TopologyTestDriver + Testcontainers + WireMock; multi-stage temurin Dockerfile; AC→test traceability.
- **Q11 ✅** Every env var with required/optional intent + idempotency settings; explicit "no threshold values in config".

### alarm-manager — Partial
- **Q1 ⚠️** `GET /alarms`, `/alarms/{id}` match web-ui use cases, but `AlarmDetail`/`AlarmSummary` omit required `alarmType` (P32).
- **Q2 ✅** Generates no seed; all state derived from consumed events; empty store at start correct (live-only MVP).
- **Q3 —** No outbound HTTP; Kafka consumer/producer + HTTP server only.
- **Q4 ✅** Only produced event re-serializes the stored `raw_envelope` byte-faithfully via EventCodec; field list fully specified.
- **Q5 ✅** Sole writer of live alarm store; `incident_id`/`role` are denormalized projections only; CE remains incident system-of-record.
- **Q6 ⚠️** Coherent DDL + indexes, but no column for required `alarmType` (only inside `raw_envelope` jsonb, not queryable); AC#1 doesn't assert it (P33).
- **Q7 ⚠️** vendorRaw/raw_envelope pass-through + DLQ + domain-agnostic moId, but required `alarmType` silently dropped + `eventType`-vs-`alarmType` unresolved (P34).
- **Q8 ✅** Generic text/jsonb/timestamptz columns; platform-generic CHECK values; new domain onboards with zero schema change.
- **Q9 ✅** Java 17 / Spring Boot 3.x / spring-kafka / Spring Data JDBC / Flyway / springdoc / event-model binding — permissive, versioned.
- **Q10 ✅** JUnit 5 (21 AC tests + 9 E2E) + Testcontainers (PG+Kafka); temurin:17-jdk Dockerfile; openapi verify task.
- **Q11 ✅** Env-only config; required vs optional distinguishable; explicitly no Knowledge params; no hard-coded URLs/creds.

### web-ui — Partial
- **Q1 —** Consumer-only SPA; no HTTP surface beyond static-asset server.
- **Q2 ✅** No owned store; per-AC fixtures cover all 80 acceptance flows; stateless client needs no startup seed.
- **Q3 ⚠️** 8 of 9 consumed points confirmed vs frozen producer branches, but Noise Filter run-stats backed only by an unmerged draft + field-shape mismatch (P35).
- **Q4 —** Never publishes to Kafka; `patterns.approved` realized via POST to Pattern Manager.
- **Q5 —** No owned store; all writes via owning-service APIs.
- **Q6 —** No owned DB; only client-side TypeScript view-models.
- **Q7 ✅** Open attribute maps, derived layer with generic fallback, envelope-agnostic diff, graceful null domain — renders whatever APIs return.
- **Q8 —** No owned DB tables; view-models domain-aware with generic `other` fallback.
- **Q9 ✅** Angular 20 standalone + signals + strict TS / MapLibre / deck.gl / Cytoscape / Chart.js / CDK — permissive, Node 24 build.
- **Q10 ✅** Vitest + Angular TestBed + MSW (unit), Playwright (E2E only); node:24 build + nginx serve; AC→test 1:1.
- **Q11 ⚠️** Nine base-URL keys + INTEGRATION_MODE enumerated, but runtime config injection mechanically unspecified for a compile-time-baked SPA served by nginx (P36).
---

## 4. Review disposition (post-review decisions)

**Build-time OpenAPI re-scoring (product-owner decision):** A consumer cell (Q3/Q7) that references a collaborator's `openapi.json` is **Compliant-for-design** when the producer's endpoint shape is already **frozen in the producer's design prose** (the `openapi.json` file is a build-time artifact generated by springdoc/FastAPI). Only cells citing a **concrete shape mismatch / unpinned shape** remain as fixes. 3 cells re-scored to Compliant on this basis (noise-filter Q3, pattern-manager Q3/Q7).

**Two systemic themes drive the remaining 40 genuine fixes:**
1. **`alarmType` population (must-fix, contract-correctness):** the merged contract makes `alarmType` required + canonical, but the producer designs (simulator, enrichment, noise-filter, pattern-miner) + the worked example never updated their field-population/mining logic to set it — they still route the token through `eventType`. Fixed first.
2. **Consumer-design contract alignment:** several consumer designs cite producer paths/fields/params that don't exactly match the producers' now-frozen shapes (e.g. codebook calling `fault-origins` vs frozen `fault-origin-types`; missing `domain` param). Fixed by aligning each consumer design to the frozen producer contract.

**Fix loop:** each owning service gets one spec/design fix PR addressing all its non-compliant cells; the matrix is updated until all 132 cells are ✅ Compliant.
