# pattern-miner — Design

> **Based on the not-yet-merged resolved spec (PR #332).** This design is authored against
> `services/pattern-miner/spec.md` as it stands on `spec/pattern-miner-3stage-resolved` (OQ-1/OQ-2
> resolved; OQ-3 resolved here). It should merge **after** spec PR #332 merges into `pattern-miner`.
> The three-stage discovery this design realizes supersedes the previous global-PrefixSpan design.

> **Scope of this service (paramount boundary).** pattern-miner is **ML execution + domain-knowledge
> anchoring** for the Pattern Learning phase (P2): three stages — (1) time+space session windowing,
> (2) domain-knowledge anchoring against Codebook fault-origin scenarios, (3) bounded PrefixSpan
> **per anchored group** — emitting one `PatternMinedEvent` per anchored fault-origin (plus one for
> the unexplained group) on `patterns.mined`. It holds **no pattern state** — **no RCA**, no
> `rootCauseAlarmType`, no `patternId`, no `lifecycle`, no codebook *reconciliation* (Stage 2 only
> *anchors* against the codebook; full reconciliation is the Pattern Manager's), no explainability
> (XAI), and no Pattern Store. Those belong exclusively to the Pattern Manager. This boundary is
> enforced by the frozen `PatternMinedEvent` schema (`extra="forbid"`) and asserted by the test plan.

> **No topology-graph access (boundary preserved; Codebook read is the only new dependency).** Stage 2
> adds a **Codebook client** (OQ-1 = Option A) that reads fault-origin scenarios over HTTP. It reads
> **scenarios only** — it does NOT touch the NebulaGraph topology graph (Topology Service's sole
> ownership) and grants no graph access. Trail scoping is still taken from
> `TransactionEvent.trailId`, never by querying the graph.

> **Output contract already landed (do NOT change).** `PatternMinedEvent.provenance.anchorScenarioId`
> (optional/nullable string; null/absent = unexplained) is already in `libs/event-model` on `main`
> (PR #331) — both the JSON Schema and the Python `Provenance` binding
> (`anchorScenarioId: str | None = None`, `extra="forbid"`). This design **emits** it from Stage 2's
> anchor result and introduces **no new event-model/contract change**.

## Stack

- **Language:** Python 3.13 (cohort pin per `CLAUDE.md`).
- **Mining engine (Stage 3):** **PySpark + Spark MLlib `PrefixSpan`** (Apache-2.0) for frequent
  ordered-sequence mining — now run **per anchored group** (bounded), not globally. Runs
  container-only (Spark/PySpark not installed locally); the pure-Python `local` engine
  (`mining.local_engine`) gives identical semantics for the local unit gate.
- **Runtime:** stateless Spark job inside its own Docker container (`python:3.13-slim` + pinned
  Spark runtime).
- **Kafka client:** `confluent-kafka` / `kafka-python` (Apache-2.0) for the consumer/producer/DLQ loop.
- **Event model:** `acp-event-model` (Python/Pydantic binding) — source of truth for
  `TransactionEvent` (typed `alarms[]`), `PatternMinedEvent`, `Provenance` (incl.
  `anchorScenarioId`), the envelope, and the codec.
- **HTTP clients (integration points):** `httpx` (permissive) for the **Knowledge client**
  (`knowledge.MiningParamsClient`, existing) and the **new Codebook client** (`codebook.CodebookClient`);
  `respx` (BSD) for both unit-test mocks, generated against each collaborator's published
  `openapi.json`.
- **HTTP for `/health` + `/metrics`:** minimal ASGI app (`starlette`/`fastapi`, MIT) +
  `prometheus-client` (Apache-2.0). No business HTTP surface.
- **Lint/format/typing:** ruff + black + type hints; **pytest** for unit/contract tests.
- All dependencies are permissive (MIT / Apache-2.0 / BSD).

## Task breakdown (from the spec)

Every spec **Task (high-level)** is realized below; none is dropped or re-scoped. The 3-stage respec
adds Stage 2 (anchoring) and re-scopes Stage 3 (bounded PrefixSpan per group).

| Spec task | Realized by (modules / flow) |
|---|---|
| 1. Consume `transactions.clean`, dedupe `TransactionEvent` on envelope `eventId` (at-least-once). | `ingest.Consumer` reads `transactions.clean`, deserializes via `acp_event_model.codec`; `ingest.Dedup` tracks processed `eventId`s for the current run and silently acks+drops duplicates. **Unchanged.** |
| 2. Fetch mining **and anchoring** params from Knowledge before each run (min-support, max-pattern-length, windowing adaptation params incl. base/fallback gap, max-sequence-count, **domain-anchoring matching confidence threshold**, **grouping keys**, `codebookVersion` in scope); no hard-coded thresholds. | `knowledge.MiningParamsClient` calls the frozen `GET /domains/{domain}/model-params/{recordId}`, maps `payload.params[]` dotted keys into typed `MiningParams`. **Extended:** `MiningParams` gains an `AnchoringParams` block (`anchoring.matchConfidenceThreshold`, `anchoring.groupingKeys`, `anchoring.scoringMethod`, `anchoring.tieBreak`, scorer weights) + `codebookVersion`. No threshold literal in source/default config. |
| 3. **Stage 1 — Time + space correlation.** Dynamic activity/idle session window per trail: pool per-trail alarms ordered by `raisedAt`, split at idle gaps; the closing gap adapts to each burst's tempo; all params Knowledge-sourced incl. base/fallback gap. | `windowing.SessionWindower` + `windowing.AdaptiveGap` (**EXISTS, kept as-is** — see Algorithm logical flow → Stage 1). Output = per-trail **candidate cascades** (`Session`s), each an ordered `alarmType`-token sequence with a composite `sourceWindowId`. This output feeds Stage 2. |
| 4. **Stage 2 — Domain-knowledge anchoring.** For each candidate cascade, fetch domain fault-origin scenarios from the Codebook (via pattern-miner's Codebook client, built against Codebook OpenAPI), resolve the active codebook (OQ-3), call `GET /codebooks/{codebookId}/scenarios`, match the cascade's ordered `alarmType` sequence against each scenario's `predictedSymptoms[].alarmType` chain, assign the best match if confidence at/above the Knowledge threshold else "unexplained"; group cascades by anchor. | **NEW** `codebook.CodebookClient` (resolves `codebookVersion="current"` to `codebookId` via `GET /codebooks/active?domain=&snapshotId=` then `GET /codebooks/{id}/scenarios`) + `anchoring.CascadeMatcher` (scores each cascade against each scenario chain, applies the Knowledge threshold + tie-break) + `anchoring.AnchorGrouper` (groups by `scenarioId`, with the null/unexplained group). Records `provenance.anchorScenarioId`. See Algorithm logical flow → Stage 2. |
| 5. **Stage 3 — ML pattern definition.** Run PrefixSpan (Spark MLlib) **within each anchored group** (bounded, not globally) to learn the group's canonical ordered `alarmType` signature, support, confidence, lift; also within the unexplained group if non-empty. | `mining.PrefixSpanMiner` (**EXISTS**) is **re-scoped**: `mining.GroupedMiner` iterates anchored groups and runs `PrefixSpanMiner` **per group** over that group's sessions only. `metrics.MetricsComputer` computes support/confidence/lift **relative to the group**. Bounded scope removes the global-mining OOM (JVM kernel-kill on dense global sessions). |
| 6. Assemble one `PatternMinedEvent` per anchored group carrying `sequence`, `support`, `confidence`, `lift`, `trailId`(s), `timing` (ms inter-arrival stats), `provenance` (`sourceWindowId`, `snapshotId`, `codebookVersion`, **`anchorScenarioId`**). Unexplained group to `anchorScenarioId` null/absent. | `assemble.PatternAssembler` (**EXTENDED**) sets `provenance.anchorScenarioId` = the group's matched `scenarioId` (or `None` for the unexplained group). `timing` via `metrics.TimingComputer` (ms keys, unchanged). |
| 7. Emit each `PatternMinedEvent` on `patterns.mined` (one per anchored group + one unexplained if non-empty). | `emit.Producer` envelopes each event (`type="PatternMinedEvent"`, `schemaVersion=1`, `source="pattern-miner"`, propagated `traceId`) and produces to `patterns.mined`. |
| 8. Route any unprocessable (poison) message to `transactions.clean.dlq`. | `ingest.DlqRouter` catches deserialize/validation failures + unknown major `schemaVersion`, publishes raw bytes + structured error header to `transactions.clean.dlq`, continues. **Unchanged.** |

## Phase applicability (design view)

Consistent with the canonical phase map in `docs/architecture.md` (row `pattern-miner`: Idle /
Active / Idle).

| Phase | Active/Passive/Idle | Modules/handlers exercised | Inputs/Outputs |
|---|---|---|---|
| P1 — Topology onboarding | Idle | All modules dormant; only `/health` answers. | — |
| P2 — Pattern learning | **Active** | `ingest.Consumer`+`Dedup`+`DlqRouter`; `knowledge.MiningParamsClient`; **`codebook.CodebookClient`**; `windowing.SessionWindower`+`AdaptiveGap` (Stage 1); **`anchoring.CascadeMatcher`+`AnchorGrouper` (Stage 2)**; `mining.GroupedMiner`+`PrefixSpanMiner` (Stage 3); `metrics.MetricsComputer`+`TimingComputer`; `assemble.PatternAssembler`; `emit.Producer`. | In: `transactions.clean` (`TransactionEvent`); Knowledge mining+anchoring params API; **Codebook scenarios API** (`GET /codebooks/active`, `GET /codebooks/{id}/scenarios`). Out: `patterns.mined` (`PatternMinedEvent`), `transactions.clean.dlq`. |
| P3 — Real-time correlation | Idle | All mining/anchoring modules dormant — mining is offline/learning-only; approved patterns are served by the Pattern Manager. Only `/health` + `/metrics` answer. | — |

## Module breakdown

```mermaid
flowchart TD
  subgraph ingest["ingest"]
    C["Consumer (transactions.clean)"]
    D["Dedup (eventId set)"]
    DLQ["DlqRouter (transactions.clean.dlq)"]
  end
  K["knowledge.MiningParamsClient (mock or real)"]
  subgraph stage1["Stage 1 — time plus space"]
    W["windowing.SessionWindower (dynamic idle-driven per trail)"]
    AG["windowing.AdaptiveGap (per-burst tempo gap)"]
  end
  subgraph stage2["Stage 2 — domain-knowledge anchoring (NEW)"]
    CB["codebook.CodebookClient (active codebook plus scenarios)"]
    MATCH["anchoring.CascadeMatcher (score cascade vs scenario chain)"]
    GRP["anchoring.AnchorGrouper (group by scenarioId plus unexplained)"]
  end
  subgraph stage3["Stage 3 — bounded ML per group"]
    GM["mining.GroupedMiner (PrefixSpan per anchored group)"]
    M["mining.PrefixSpanMiner (Spark MLlib)"]
  end
  MET["metrics.MetricsComputer (support, confidence, lift per group)"]
  TC["metrics.TimingComputer (ms timing from raisedAt)"]
  A["assemble.PatternAssembler (PatternMinedEvent plus provenance.anchorScenarioId)"]
  E["emit.Producer (patterns.mined)"]

  C --> D
  D --> DLQ
  D --> W
  K --> AG
  AG --> W
  W --> MATCH
  K --> MATCH
  CB --> MATCH
  MATCH --> GRP
  GRP --> GM
  GM --> M
  K --> GM
  M --> MET
  GRP --> TC
  MET --> A
  TC --> A
  K --> A
  GRP --> A
  A --> E
```

- **ingest.Consumer / Dedup / DlqRouter** — as before (consume, dedupe on `eventId`, DLQ poison).
- **knowledge.MiningParamsClient** — fetches `MiningParams` (mining + windowing + **anchoring**
  params + `codebookVersion`) before a run; config-switchable mock/real. **Extended** with the
  anchoring block.
- **windowing.SessionWindower + AdaptiveGap** — Stage 1 (existing). Produces per-trail candidate
  cascades (`Session`s). Not redesigned.
- **codebook.CodebookClient** (NEW) — resolves the active codebook for `(domain, snapshotId)` and
  fetches its fault-origin scenarios; config-switchable mock/real against the Codebook OpenAPI.
- **anchoring.CascadeMatcher** (NEW) — scores each candidate cascade's ordered `alarmType` sequence
  against each scenario's `predictedSymptoms[].alarmType` chain; applies the Knowledge match-confidence
  threshold + tie-break; returns the best-matching `scenarioId` or `None` (unexplained).
- **anchoring.AnchorGrouper** (NEW) — groups candidate cascades by assigned `scenarioId` (one group
  per fault-origin) plus a single unexplained (`None`) group.
- **mining.GroupedMiner** (NEW thin driver) — iterates anchored groups; for each runs
  `PrefixSpanMiner` over **that group's sessions only** (bounded scope) and selects the group's
  representative pattern.
- **mining.PrefixSpanMiner** (existing) — Spark MLlib `PrefixSpan` over a set of sessions; now
  invoked per group.
- **metrics.MetricsComputer / TimingComputer** — support/confidence/lift computed **within the group**;
  ms `timing` from `alarms[].raisedAt` (keys unchanged).
- **assemble.PatternAssembler** (extended) — builds `PatternMinedEvent` + `Provenance`; sets
  `provenance.anchorScenarioId` from the group's anchor.
- **emit.Producer** — envelopes + produces to `patterns.mined`.

## Data model / DB schema

**N/A — no owned store.** pattern-miner is a stateless Spark job: it mines, emits, and forgets (spec
"Data owned: None"). The only in-memory state is the per-run `Dedup` set of processed `eventId`s
(idempotency within a run) and the per-run cached Codebook scenario list (fetched once per run, not
persisted). No PostgreSQL/NebulaGraph schema is owned — the Pattern Store belongs exclusively to the
Pattern Manager; the topology graph to the Topology Service; the codebook store to the Codebook
Service.

The transient in-run data shapes (not persisted) are:

```mermaid
classDiagram
  class Session {
    +str trailId
    +str snapshotId
    +str sourceWindowId
    +list~Alarm~ alarms
    +list~str~ alarmTypes
  }
  class Scenario {
    +str scenarioId
    +str faultOriginObjectId
    +str faultOriginType
    +list~str~ symptomChain
    +list~str~ trailIds
  }
  class MatchResult {
    +Session session
    +str scenarioId
    +float confidence
  }
  class AnchoredGroup {
    +str scenarioId
    +list~Session~ sessions
  }
  Session --> MatchResult
  Scenario --> MatchResult
  MatchResult --> AnchoredGroup
```

(`MatchResult.scenarioId` / `AnchoredGroup.scenarioId` are `None` for the unexplained group.)

## Event handling

- **Consumers:**
  - `transactions.clean` to `ingest.Consumer`. Payload: `TransactionEvent` (event-model), ordered
    typed `alarms[]` with six required fields (`{alarmId, alarmType, eventType, raisedAt,
    managedObjectId, perceivedSeverity}`). Sequence item = **`alarmType`** (canonical join token; not
    `eventType`, not `probableCause`). **Idempotency/dedupe key:** envelope **`eventId`** —
    duplicates acked + dropped (AC-15). **DLQ routing:** deserialize failure, schema-validation
    failure, or unsupported major `schemaVersion` to `transactions.clean.dlq` (AC-16).
- **Producers:**
  - `patterns.mined` from `emit.Producer`. Payload: **`PatternMinedEvent`**, **one per anchored
    group** (plus one unexplained if non-empty) — AC-10. Envelope: `type="PatternMinedEvent"`,
    `schemaVersion=1`, `source="pattern-miner"`, `traceId` propagated where available.
  - `transactions.clean.dlq` from `ingest.DlqRouter` (poison messages; raw bytes + structured error
    header).
- **Provenance:** `sourceWindowId` = the group's representative session reference; `snapshotId` =
  source `TransactionEvent.snapshotId`; `codebookVersion` = Knowledge mining-params value (symbolic,
  e.g. `"current"`); `domain` = source transaction's `domain`; **`anchorScenarioId`** = the group's
  matched `scenarioId` (or `None`/absent for the unexplained group).

## API contracts / API schema

**N/A — no business HTTP surface; no published OpenAPI spec.** pattern-miner exposes only
operational endpoints:

- `GET /health` returns `200 {"status":"ok"}` (liveness/readiness; reports Kafka + Knowledge +
  **Codebook** reachability).
- `GET /metrics` returns Prometheus exposition format.

The service's **inbound** contracts are the Kafka topic + event-model payloads; its **outbound**
synchronous contracts are the Knowledge and Codebook services' published `openapi.json` (see
Integration points). No OpenAPI document is published for pattern-miner.

## Integration points (mock vs. real)

| Collaborator + operation | Endpoint (published shape) | Config key(s) | Mock (unit) | Real (integration) |
|---|---|---|---|---|
| **Knowledge — mining + anchoring params** | `GET /domains/{domain}/model-params/{recordId}` returns the versioned-record envelope `payload.params[]` with dotted keys `prefixspan.*`, `window.adaptive.*`, **`anchoring.matchConfidenceThreshold`**, **`anchoring.groupingKeys`**, **`anchoring.scoringMethod`**, **`anchoring.tieBreak`**, `codebookVersion`. `404` unknown domain/record. | `KNOWLEDGE_BASE_URL`, `KNOWLEDGE_CLIENT_MODE` (`mock`/`real`), `KNOWLEDGE_DOMAIN`, `KNOWLEDGE_MODEL_PARAMS_RECORD_ID` | `respx` stub generated from the Knowledge published `openapi.json` | Live Knowledge Service at the Compose address from env |
| **Codebook — active codebook resolution** (NEW) | `GET /codebooks/active?domain={domain}&snapshotId={snapshotId}` returns `CodebookMeta {codebookId, snapshotId, domain, scenarioCount, active?, …}`. Both query params **required** (verified against `/openapi.json`). `404` if no active codebook for `(domain, snapshotId)`. | `CODEBOOK_BASE_URL`, `CODEBOOK_CLIENT_MODE` (`mock`/`real`), `CODEBOOK_RETRY_MAX`, `CODEBOOK_RETRY_BACKOFF_MS` | `respx` stub generated from the Codebook published `openapi.json` | Live Codebook Service at the Compose address from env |
| **Codebook — fault-origin scenarios** (NEW) | `GET /codebooks/{codebookId}/scenarios` returns `ScenarioListResponse {codebookId, domain, scenarios:[{scenarioId, faultOriginObjectId, faultOriginType, predictedSymptoms:[{alarmType, managedObjectId}], trailIds:[…]}]}`. `404` unknown codebook. | (as above) | `respx` stub returning the scenario list | Live Codebook Service |

- **No hard-coded URLs.** All base URLs + `mock`/`real` toggles + `domain` come from env. No
  domain-specific literal (alarm type, fault-origin name, scenarioId) appears anywhere in source or
  default config (spec Non-functional / AC-17).
- **Both Codebook endpoints and the scenario shape are verified against the live Codebook
  `/openapi.json`** (`GET /codebooks/active` requires `domain`+`snapshotId`; scenario shape carries
  `scenarioId`, `faultOriginObjectId`, `faultOriginType`, `predictedSymptoms[]`, `trailIds[]`). The
  client and its respx mock are generated against that published spec — never against Codebook
  source.

## Key flows (sequence / data-flow diagrams)

### Primary success path — 3-stage P2 mining run

```mermaid
sequenceDiagram
  participant TC as transactions.clean
  participant I as ingest (Consumer plus Dedup)
  participant KS as Knowledge (mining plus anchoring params)
  participant W as Stage 1 SessionWindower plus AdaptiveGap
  participant CB as Codebook client
  participant MA as Stage 2 CascadeMatcher plus AnchorGrouper
  participant PS as Stage 3 PrefixSpan per group (Spark)
  participant AS as PatternAssembler
  participant P as patterns.mined
  TC->>I: TransactionEvent (typed alarms, six fields incl alarmType, snapshotId, domain)
  I->>I: dedupe on eventId, drop duplicates
  I->>KS: GET model-params recordId
  KS-->>I: minSupport, maxLen, WindowingParams, AnchoringParams, codebookVersion
  I->>W: typed alarms per trail, ordered by raisedAt
  W->>W: per burst adaptive closing gap, split on idle gap
  W->>MA: candidate cascades (per-trail sessions of alarmType tokens)
  MA->>CB: GET codebooks active (domain, snapshotId) then GET scenarios
  CB-->>MA: fault-origin scenarios (predictedSymptoms alarmType chains)
  MA->>MA: score each cascade vs each scenario chain, apply Knowledge threshold, group by scenarioId or unexplained
  MA->>PS: one bounded PrefixSpan job per anchored group (group sessions only)
  PS-->>AS: per-group frequent ordered sequence plus support, confidence, lift
  MA->>AS: group anchor (scenarioId or null) plus ms timing
  AS->>P: one PatternMinedEvent per group (provenance.anchorScenarioId set or null)
```

### OQ-3 resolution — codebookVersion "current" to concrete codebookId

```mermaid
sequenceDiagram
  participant M as pattern-miner (Stage 2)
  participant CB as Codebook Service
  Note over M: has domain (from Knowledge, transaction) and snapshotId (from TransactionEvent, provenance)
  M->>CB: GET codebooks active with domain and snapshotId
  CB-->>M: CodebookMeta with codebookId (single active for that domain plus snapshot)
  M->>CB: GET codebooks codebookId scenarios
  CB-->>M: ScenarioListResponse (fault-origin scenarios)
  Note over M: provenance.codebookVersion keeps the symbolic Knowledge value current verbatim, codebookId is a runtime detail, not a schema field
```

### Poison-message / DLQ path

```mermaid
sequenceDiagram
  participant TC as transactions.clean
  participant I as ingest (Consumer)
  participant D as DlqRouter
  participant DLQ as transactions.clean.dlq
  TC->>I: malformed or schema-invalid or unsupported-major message
  I->>I: deserialize or validate fails
  I->>D: route raw bytes plus error reason
  D->>DLQ: publish poison message
  Note over I,DLQ: no PatternMinedEvent emitted, consumer continues
```

## Algorithm logical flow

The Miner reads all tunables from **Knowledge** (never hard-coded): `minSupport`, `maxPatternLength`,
`maxSequenceCount`, `WindowingParams`, and the new `AnchoringParams`
(`matchConfidenceThreshold`, `groupingKeys`, `scoringMethod`, `tieBreak`, scorer weights); the
fault-origin scenario set from **Codebook**. Inputs: a batch of `TransactionEvent`s with typed
`alarms[]`. Output: one `PatternMinedEvent` per anchored group (+ one unexplained if non-empty).

```mermaid
flowchart TD
  S["start P2 mining run"] --> P["fetch MiningParams plus WindowingParams plus AnchoringParams plus codebookVersion from Knowledge"]
  P --> READ["read typed alarms from each TransactionEvent (alarmType plus raisedAt) per trail"]
  READ --> S1["STAGE 1: per-trail adaptive session windowing, produce candidate cascades"]
  S1 --> RESOLVE["STAGE 2a: resolve active codebook via GET codebooks active (domain, snapshotId), then GET scenarios"]
  RESOLVE --> MATCH["STAGE 2b: score each cascade vs each scenario symptom chain"]
  MATCH --> THRESH{"best score at least matchConfidenceThreshold"}
  THRESH -- yes --> ANCHOR["assign cascade to that scenarioId (best, tie-break on chain length then scenarioId)"]
  THRESH -- no --> UNEXP["assign cascade to the unexplained group (anchorScenarioId null)"]
  ANCHOR --> GROUP["STAGE 2c: group cascades by scenarioId plus one unexplained group"]
  UNEXP --> GROUP
  GROUP --> S3["STAGE 3: run PrefixSpan within EACH group (bounded), minSupport plus maxPatternLength"]
  S3 --> METR["compute support, confidence, lift within the group"]
  METR --> TIM["compute ms timing (timeframeMs, medianInterArrivalMs, maxInterArrivalMs, stddevInterArrivalMs)"]
  TIM --> PROV["assemble provenance (sourceWindowId, snapshotId, codebookVersion, domain, anchorScenarioId)"]
  PROV --> EMIT["emit one PatternMinedEvent per group (at most N anchored plus 1 unexplained)"]
```

### Stage 1 — Time + space correlation (EXISTING, retained unchanged)

Stage 1 is the already-built dynamic activity/idle session windowing (`windowing.SessionWindower`,
`windowing.AdaptiveGap`) — this design **retains it as-is** and does not redesign it beyond what the
respec requires. Summary of the retained mechanism (full detail unchanged from the merged design):

- Per trail, pool the typed `alarms[]` across the run, order by `raisedAt`.
- Compute a per-burst **adaptive closing gap**:
  `closingGap = clamp(gapMultiplier * median(interArrivals), lower=profileFloor(tempoClass), upper=maxClosingGap)`,
  with a Knowledge `baseGap` fallback when a burst has too few samples (below `minBurstSamples`) or
  no tempo-class profile matches. The split gap is **sized on the median (p50)** for robustness;
  `tempoPercentile` is used only to **classify** the burst's tempo (floor/ceiling selection).
- Split the trail into sessions where the inter-arrival gap exceeds the closing gap. Each session is
  a **candidate cascade** with a composite `sourceWindowId` (deterministic hash of
  `trailId`+session start/end `raisedAt`+`snapshotId`).

**Output to Stage 2:** the set of candidate cascades (`Session`s), each carrying its ordered
`alarmType` token list (from `alarms[].alarmType`), its `alarms[]` (for later timing), its `trailId`,
`snapshotId`, and `sourceWindowId`. AC-1/AC-2/AC-3 assert Stage 1 behaviour and are unchanged.

### Stage 2 — Domain-knowledge anchoring (NEW) — the accuracy crux

**Goal:** assign each candidate cascade to exactly one fault-origin identity (a Codebook
`scenarioId`) or to "unexplained", so that the emitted pattern set is small and accurate — **zero
over-split** (one ground-truth fault-origin to one group) and **zero over-merge** (one group to one
fault-origin).

**2a. Resolve the codebook and fetch scenarios (OQ-3 resolved).**
The miner already knows `domain` (Knowledge/transaction) and `snapshotId` (on every
`TransactionEvent`, propagated into provenance). It resolves the active codebook with the **existing**
endpoint `GET /codebooks/active?domain={domain}&snapshotId={snapshotId}` returning
`CodebookMeta.codebookId` (the Codebook store guarantees a single active codebook per
`(domain, snapshotId)`), then fetches `GET /codebooks/{codebookId}/scenarios`. The symbolic
`codebookVersion="current"` from Knowledge is **recorded verbatim** into `provenance.codebookVersion`;
the concrete `codebookId` is a runtime detail, not a schema field. **No Codebook API change is
required** (see OQ-3 below). Scenarios are fetched **once per run** and cached in memory. Each
scenario's canonical fault-origin signature is its ordered `predictedSymptoms[].alarmType` list — the
**symptom chain**.

**2b. Match a cascade against a scenario chain.**
Let `C` = the alarmType tokens of the cascade ordered by `raisedAt`, and `S` = a scenario's ordered
`predictedSymptoms[].alarmType` chain. The scorer is selected by the Knowledge-sourced
`anchoring.scoringMethod` (default `ordered_subsequence_jaccard`), computing a match confidence in
`[0, 1]`. The chosen (default) score combines **ordered-chain coverage** and **set overlap** — this
pairing is what delivers no-over-split AND no-over-merge:

```text
# de-duplicate consecutive repeats in C first (storms repeat a token); keep order.
lcs_ratio  = LCS(C_ordered, S) / len(S)                  # how much of the chain, IN ORDER, the cascade contains
jaccard    = size(set(C) intersect set(S)) / size(set(C) union set(S))   # penalizes extra unrelated tokens
confidence = w_order * lcs_ratio + w_jaccard * jaccard   # w_order + w_jaccard = 1, both from Knowledge (default 0.7 / 0.3)
```

Why this shape hits the accuracy target:
- **`lcs_ratio` (ordered coverage) prevents over-split.** Two cascades from the *same* fault-origin
  that manifest slightly differently (a missing/reordered symptom, extra noise-cluster remnant) still
  both cover most of the *same* ordered chain, so both clear the threshold against the *same* scenario
  and land in the **same** group — one pattern, not two. Matching on the codebook's fault-origin chain
  (a stable, authored identity) rather than on the raw mined shape is exactly what removes over-split:
  variants of one fault-origin collapse to one anchor.
- **`jaccard` (set overlap, incl. the union denominator) prevents over-merge.** A cascade that
  contains one scenario's chain *plus* many unrelated tokens (or partially overlaps two scenarios) is
  penalized by the union denominator, so it does not spuriously merge into a fault-origin it doesn't
  belong to; and the **best-single-scenario** assignment (argmax, one anchor per cascade) means a
  cascade is never placed in two groups. One group therefore maps to one fault-origin.
- The **threshold** (`anchoring.matchConfidenceThreshold`, from Knowledge) is the dividing line
  between "confidently this fault-origin" and "unexplained". Because it is Knowledge-sourced, tuning
  it (AC-7) changes anchored-vs-unexplained with **no code change**.

**2c. Assign and group.**
For each cascade, compute confidence against every scenario and pick the **argmax**. If the max
confidence is at/above `matchConfidenceThreshold`, assign the cascade to that scenario's `scenarioId`
(**tie-break**, from Knowledge `anchoring.tieBreak`, default: longer scenario chain wins, then
lexicographically smallest `scenarioId` — deterministic). Otherwise assign to the **unexplained**
group (`scenarioId = None`). Then `AnchorGrouper` groups all cascades by their assigned key: one group
per distinct `scenarioId`, plus at most one unexplained group. **Grouping keys** are the Knowledge
`anchoring.groupingKeys` (default `["scenarioId"]`) — domain-agnostic and configurable.

This yields **at most N+1 groups** for N distinct anchored fault-origins (AC-10), and by construction
no cascade appears in two groups (AC-4/AC-5/AC-20).

### Stage 3 — Bounded ML pattern definition (re-scoped EXISTING)

`mining.GroupedMiner` iterates the anchored groups. For **each** group it runs the existing
`mining.PrefixSpanMiner` (Spark MLlib `PrefixSpan`) over **only that group's** session sequences
(ordered lists of `alarmType` tokens), with `minSupport` / `maxPatternLength` from Knowledge:

- **Bounded scope removes the OOM.** Global PrefixSpan on the full dense session corpus was
  kernel-killed (JVM OOM) and produced ~2,592 frequent sub-fragments. Running it **per anchored
  group** (each group is a small, homogeneous set of one fault-origin's cascades) bounds both memory
  and the frequent-sequence lattice — the primary reason the 3-stage approach is both accurate *and*
  tractable.
- **Representative pattern per group.** Within a group, PrefixSpan yields frequent ordered
  `alarmType` subsequences; `GroupedMiner` selects the group's **representative** learned pattern —
  the maximal frequent ordered sequence meeting `minSupport` (truncated to `maxSequenceCount` /
  `maxPatternLength`) — which is that fault-origin's canonical signature. That single representative
  becomes the group's one `PatternMinedEvent` (AC-9, AC-10, AC-20).
- **Metrics within the group.** `support` = frequency of the sequence **within the group's sessions**;
  `confidence` = P(sequence given its longest proper prefix) within the group; `lift` = joint support
  / product of marginals within the group. `conviction` is not computed (not in the frozen schema).
- The **unexplained** group runs PrefixSpan the same way if non-empty, producing one
  `PatternMinedEvent` with `anchorScenarioId` null/absent (AC-21).
- **Token = `alarmType`.** The PrefixSpan item is always `alarms[].alarmType` (canonical join token),
  never `eventType` (X.733 category) or `probableCause` (AC-9).

### Timing statistics (contract-of-shape on the open `timing` object — unchanged from merged design)

`metrics.TimingComputer` emits **exactly** four ms sub-fields on the open `timing` object, computed
per group from `alarms[].raisedAt`, aggregated over the group's matching sessions:

| Key | Units | Definition |
|---|---|---|
| `timeframeMs` | ms | `max(raisedAt) minus min(raisedAt)` per matching session, median over sessions. |
| `medianInterArrivalMs` | ms | Median consecutive-alarm gap within the sequence, over the group's sessions. |
| `maxInterArrivalMs` | ms | Max consecutive-alarm gap over the group's sessions. |
| `stddevInterArrivalMs` | ms | Stddev of consecutive-alarm gaps over the group's sessions. |

Inter-arrivals are computed **within** a session (never across the idle boundary). Thin/degenerate
occurrences yield `0` for the affected keys; the schema `timing` object stays open
(`additionalProperties:true`) — **no event-model schema change**. These are the exact keys/units the
Pattern Manager `SessionWindowDeriver` consumes (AC-18).

## Seed data & examples

**N/A — pattern-miner generates no seed corpus.** Test inputs are synthetic `TransactionEvent`
batches with typed `alarms[]` populated inline, plus **Codebook scenario fixtures** (respx-served,
generated against the Codebook `openapi.json`) and **Knowledge param fixtures** (respx-served).
Illustrative fixtures used by the test plan:

**Codebook scenario fixture (two distinct fault-origins) — domain-agnostic, served by the mock:**

```json
{
  "codebookId": "cb-001", "domain": "core-ip",
  "scenarios": [
    {"scenarioId": "SC-FIBER", "faultOriginObjectId": "obj-fiber-1", "faultOriginType": "FiberCut",
     "predictedSymptoms": [{"alarmType": "FiberFault", "managedObjectId": "obj-fiber-1"},
                           {"alarmType": "LinkDown", "managedObjectId": "obj-link-1"},
                           {"alarmType": "AdjDown", "managedObjectId": "obj-rtr-1"}],
     "trailIds": ["trail-a", "trail-b"]},
    {"scenarioId": "SC-CARD", "faultOriginObjectId": "obj-card-1", "faultOriginType": "CardFail",
     "predictedSymptoms": [{"alarmType": "PortDown", "managedObjectId": "obj-card-1"},
                           {"alarmType": "InterfaceDown", "managedObjectId": "obj-if-1"}],
     "trailIds": ["trail-c"]}
  ]
}
```

**Worked anchoring example.** Cascade `C1 = [FiberFault, LinkDown, AdjDown]` (trail-a) vs `SC-FIBER`
chain `[FiberFault, LinkDown, AdjDown]`: `lcs_ratio = 3/3 = 1.0`, `jaccard = 3/3 = 1.0`, confidence
`= 0.7*1.0 + 0.3*1.0 = 1.0` at/above threshold to anchored to `SC-FIBER`. A second cascade
`C2 = [FiberFault, AdjDown]` (trail-b, one symptom missing) vs `SC-FIBER`: `lcs_ratio = 2/3` approx
`0.67`, `jaccard = 2/3` approx `0.67`, confidence approx `0.67` — if threshold is `0.5`, **also**
anchored to `SC-FIBER` (same group as C1 to **zero over-split**). A cascade `C3 = [Xyz, Abc]` matches
no chain well (confidence below threshold) to **unexplained**. Result: two anchored groups
(`SC-FIBER`, `SC-CARD`) + one unexplained to three `PatternMinedEvent`s.

**Worked timing example (ms keys):** session `raisedAt` `12:00:00.000 / 12:00:04.000 / 12:00:09.000`
gives gaps `4000, 5000` ms, span `9000` ms, so
`{timeframeMs:9000, medianInterArrivalMs:4500, maxInterArrivalMs:5000, stddevInterArrivalMs:500}`.

## UI wireframes

**N/A.** pattern-miner has no UI; pattern review/approve/edit belong to web-ui (against the Pattern
Manager).

## Error handling

| Failure mode | Handling | Surfaced as |
|---|---|---|
| Message bytes not valid JSON / not a valid envelope | `DlqRouter` to `transactions.clean.dlq`, raw bytes + error header; consumer continues (AC-16) | DLQ message + JSON error log; no emit |
| Payload fails `TransactionEvent` schema validation (`extra="forbid"`, missing/ill-typed `alarms[]`) | to `transactions.clean.dlq` (AC-16) | DLQ message + error log; no emit |
| Unsupported major `schemaVersion` (codec rejects major at least 2) | to `transactions.clean.dlq`, reason `unsupported_schema_version` | DLQ message + error log; no emit |
| Duplicate envelope `eventId` (at-least-once redelivery) | `Dedup` drops it; acked, no reprocessing (AC-15) | Silent drop + debug log; no emit |
| Knowledge Service unavailable/erroring (transient) | `MiningParamsClient` retries with config-driven back-off; on exhaustion the **run fails fast** (no stale/default thresholds — no hard-coded fallback) | Error log + run-failure metric; offsets not advanced so the run can retry |
| **Codebook Service unavailable/erroring (transient)** (NEW) | `CodebookClient` retries with config-driven back-off (`CODEBOOK_RETRY_*`); on exhaustion the **run fails fast** — Stage 2 cannot anchor without scenarios, and mining must not proceed unanchored (that would re-introduce the global-mining defect the respec corrects). Per OQ-1 con: if Codebook is down during a P2 run, the run fails/degrades by design. | Error log + run-failure metric; offsets not advanced; retries when Codebook returns |
| **No active codebook for `(domain, snapshotId)`** (`GET /codebooks/active` returns 404) (NEW) | Run **fails fast** with a clear `no_active_codebook` reason — anchoring cannot proceed; the P2 run is retried once the codebook is compiled for that snapshot. **Never** falls back to unanchored global mining. | Error log + run-failure metric; offsets not advanced |
| **A cascade matches no scenario at/above threshold** (NEW) | **Not an error** — assigned to the unexplained group and emitted as a `PatternMinedEvent` with `anchorScenarioId` null/absent (AC-6, AC-21). | Info log (unexplained count) + `unexplained-cascades` metric |
| A burst has too few inter-arrivals for a stable median, or no tempo-class profile matches | Knowledge `baseGap`/`profileFloor` fallback applies (defined behaviour, not an error) | Debug log + fallback-gap-used metric |
| PrefixSpan yields no frequent sequence for a group at the current `minSupport` | Group emits no pattern; log empty-group result; valid outcome | Empty-group log + metric |
| Spark job failure (executor/driver error mid-run) | Run treated as not-committed: source offsets not committed; job exits non-zero so the container restarts and re-consumes (`eventId` dedupe makes replay safe) | Error log + non-zero exit + failure metric |

Nothing is **silently** dropped except confirmed duplicates (AC-15) and explicit empty results;
every other failure is logged and either DLQ-routed or fails the run.

## Design alternatives

| Consideration | Alternatives considered | Chosen + rationale |
|---|---|---|
| **Where Stage 2 anchoring lives** | (a) in pattern-miner (Codebook client added); (b) in pattern-manager. | **(a) — resolved by the human (OQ-1 Option A).** Co-locates the anchor-then-mine loop; `patterns.mined` carries fully-anchored results; pattern-manager receives clean per-fault-origin inputs. Con (accepted): a new Codebook dependency + run fails if Codebook is down during P2. |
| **`codebookVersion="current"` to `codebookId` resolution (OQ-3)** | (a) `GET /codebooks/active?domain=&snapshotId=` using the miner's known `(domain, snapshotId)`; (b) request a Codebook change to accept `codebookVersion="current"`; (c) a resolve-by-domain-only endpoint. | **(a) — existing endpoints, no contract change.** The miner already has `domain` + `snapshotId` (snapshotId is on every `TransactionEvent` and in provenance). `GET /codebooks/active` **requires both** params (verified) and the store returns the single active codebook for that `(domain, snapshotId)`. So `"current"` is resolved *by snapshot*, kept verbatim in `provenance.codebookVersion`. (b)/(c) would be Codebook contract changes — unnecessary and therefore **not** requested. |
| **Cascade-to-scenario matching score** | (a) pure ordered-subsequence (LCS ratio) only; (b) pure set Jaccard only; (c) **weighted LCS-ratio + Jaccard** (chosen); (d) edit distance / DTW. | **(c).** (a) alone can over-merge (a cascade whose extra noise tokens contain a chain scores high). (b) alone loses order (two different fault-origins with the same token set become indistinguishable to over-merge). (c) combines ordered coverage (over-split guard) with set-overlap-with-union (over-merge guard) — the pairing that hits zero-over-split + zero-over-merge. (d) is heavier and no more accurate on short symptom chains. Scorer is selectable via `anchoring.scoringMethod` so a domain can override without code change. |
| **PrefixSpan scope** | (a) global over the full session corpus (the old, defective design); (b) **per anchored group** (bounded). | **(b).** Global PrefixSpan produced ~2,592 fragments and OOM-killed the JVM. Per-group bounds the lattice + memory and yields the group's canonical signature — accurate, small, tractable. Spec forbids global PrefixSpan. |
| **Codebook-down behaviour** | (a) fail the run + retry; (b) proceed with unanchored global mining; (c) proceed with a cached codebook. | **(a).** (b) re-introduces the global-mining defect (forbidden by spec). (c) needs an owned durable store (violates the no-owned-store invariant). Fail-fast + retry preserves accuracy and statelessness. |
| **Codebook scenario fetch frequency** | (a) once per run (cached in memory); (b) per cascade. | **(a).** The active codebook is constant within a run (single `snapshotId`); fetch once, cache in memory (not persisted). (b) would be N times redundant HTTP for no benefit. |
| **Grouping key** | (a) fixed `scenarioId`; (b) Knowledge-sourced `anchoring.groupingKeys` (default `["scenarioId"]`). | **(b).** Keeps grouping domain-agnostic and future-flexible (e.g. group by `faultOriginType` for a domain that authors multiple scenarios per type) with no code change. Default is `scenarioId` to one group per fault-origin scenario. |
| **Source of per-alarm detail / mined-sequence token / windowing (carried from merged design)** | consume typed `alarms[]` in-band; token = `alarmType`; hybrid median-sized adaptive gap. | **Unchanged** — already resolved in the merged design (issue #99 closed; `alarmType` is the canonical join token; hybrid adaptive windowing). Retained verbatim. |
| **`timing` keys/units** | keep as-is (ms canonical keys on the open object). | **Unchanged** — the P2-GAP-10 alignment stands; no schema change. |

## Test plan

All tests are **pytest**. Spark-dependent tests run via the `local` engine (identical semantics) or
`local[*]` inside the test container. Knowledge params and Codebook scenarios are served by **respx**
mocks generated from each collaborator's published `openapi.json`. Domain values in fixtures are
illustrative — never literals in source/config.

### Acceptance criterion to test (unit/contract)

| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| AC-1 | Fast vs slow trails get different appropriate session boundaries (fast kept whole, slow kept whole). | `test_stage1_different_tempo_trails_get_appropriate_boundaries` | Trail A (sub-second bursts) yields exactly one session; trail B (minutes-apart) yields exactly one session — a single fixed gap would split one of them; proves per-tempo adaptation. |
| AC-2 | One trail, two bursts split by a clear idle period gives exactly two sessions; intra-burst alarms stay together. | `test_stage1_idle_period_splits_trail_into_two_sessions` | Windowing yields exactly two sessions; burst-1 alarms all in session 1, burst-2 in session 2; no further intra-burst split. |
| AC-3 | Changing Knowledge windowing config re-shapes boundaries on the same input. | `test_stage1_knowledge_windowing_config_changes_boundaries` | Reprocess a fixed input with two `WindowingParams` from the mock gives differing session boundaries (count/membership); identical params give identical boundaries (deterministic), proving no hard-coded gap. |
| AC-4 | Cascades matching two distinct scenarios are anchored to the correct distinct fault-origins; groups distinct, no cascade in both (zero over-merge). | `test_stage2_distinct_scenarios_anchor_to_distinct_groups` | Given cascades clearly matching `SC-FIBER` vs `SC-CARD` (mock scenarios), each cascade's `provenance.anchorScenarioId` equals the correct `scenarioId`; the two groups share no cascade. |
| AC-5 | One scenario manifesting in multiple cascades (different trails) gives one group, one event, same `anchorScenarioId` (zero over-split). | `test_stage2_same_scenario_multi_trail_single_group` | Two cascades (variant shapes, different trails) both anchor to the same `scenarioId` to one `PatternMinedEvent` with that `anchorScenarioId`; not split into two anchored groups. |
| AC-6 | Cascade with no confident match goes to unexplained group, event with `anchorScenarioId` null/absent (not forced to closest match). | `test_stage2_unexplained_cascade_not_forced_to_closest` | A cascade whose best confidence is below threshold is NOT assigned to the argmax scenario; it lands in the unexplained group; its emitted event has `anchorScenarioId` null/absent. |
| AC-7 | Match-confidence threshold read exclusively from Knowledge; changing the mock threshold changes anchored-vs-unexplained, no code change. | `test_stage2_threshold_sourced_from_knowledge_changes_outcome` | Same cascade + scenarios: with a high mock threshold gives unexplained; with a low mock threshold gives anchored to `scenarioId`; only the mock value changed. |
| AC-8 | Scenario set sourced from Codebook (domain-scoped); changing the Codebook mock scenarios changes which cascades anchor to which fault-origin, no code change. | `test_stage2_scenario_set_sourced_from_codebook_changes_anchoring` | With scenario set A the cascade anchors to `SC-FIBER`; with a different mock set (different `scenarioId`s/`predictedSymptoms`) it anchors differently or becomes unexplained; only the Codebook mock changed. |
| AC-9 | PrefixSpan within a fiber-cut group emits an event whose `sequence` equals the ordered `alarmType` chain (from `predictedSymptoms`, not a literal) and `support` equals observed frequency in that group (within tolerance). | `test_stage3_group_sequence_and_support` | For the anchored fiber-cut group, an emitted `PatternMinedEvent.sequence` equals the ordered `alarmType` tokens (built from `alarms[].alarmType`, not `eventType`/`probableCause`) and `support` equals the within-group frequency within float tolerance. |
| AC-10 | Per-group PrefixSpan gives total emitted events at most (distinct anchored groups + unexplained); N fault-origins all anchored gives at most N+1 events. | `test_stage3_event_count_bounded_by_groups` | For a run producing N anchored groups (+ optional unexplained), the count of `patterns.mined` events is at most N+1 — not the count of frequent subsequences across the corpus. |
| AC-11 | Raising `minSupport` above a group sequence's support removes it; lowering restores it. | `test_stage3_min_support_filters_and_restores_within_group` | High mock `minSupport` gives the borderline within-group sequence absent from that group's event; lowered `minSupport` gives the same input re-emitting it. |
| AC-12 | Every emitted event validates against the frozen `PatternMinedEvent` model; anchored events carry non-null string `anchorScenarioId`; unexplained carries null/absent; absence does not fail validation. | `test_output_validates_and_anchor_field_semantics` | Each event round-trips `PatternMinedEvent.model_validate`; required fields present/typed; anchored gives `provenance.anchorScenarioId` a non-empty str; unexplained gives None/absent still validating (field not in `provenance.required`). |
| AC-13 | No event carries `rootCauseAlarmType`, `patternId`, or `lifecycle`; `extra="forbid"` enforces. | `test_no_rca_or_lifecycle_fields_on_output` | Emitted dicts contain none of those keys; constructing `PatternMinedEvent`/`Provenance` with such a field raises `ValidationError`. |
| AC-14 | `provenance` carries `sourceWindowId`, `snapshotId`, `codebookVersion` (all non-empty); `codebookVersion` equals Knowledge value; anchored gives `anchorScenarioId` equal to matched scenario's `scenarioId`. | `test_provenance_fields_and_anchor_matches_scenario` | Every event's provenance has the three non-empty sub-fields; `codebookVersion` equals the mock value; for anchored events `anchorScenarioId` equals the `scenarioId` the cascade was matched against. Companion `test_provenance_domain_propagated`. |
| AC-15 | Duplicate `eventId` in the run emits nothing; silently acked/dropped. | `test_duplicate_event_id_dropped` | Feeding the same `eventId` twice yields events once; second acked, no extra emit; dedupe metric increments. |
| AC-16 | Undeserializable/schema-invalid message goes to `transactions.clean.dlq`, no event. | `test_poison_message_routed_to_dlq` | Malformed JSON and schema-invalid `TransactionEvent` (incl. missing/ill-typed `alarms[]`) each land on the DLQ with a reason; no emit; consumer continues. Companion `test_unsupported_schema_version_routed_to_dlq`. |
| AC-17 | No threshold literal (min-support, windowing gap, match confidence, grouping key) in source/default config; all flow from Knowledge/Codebook; changed mocks change behaviour. | `test_no_hardcoded_thresholds` (+ `test_thresholds_sourced_from_knowledge`) | Source/config scan finds no threshold/gap/confidence/grouping literal; runtime asserts the values used equal the Knowledge/Codebook mock values and changing a mock changes behaviour. |
| AC-18 | `timing` carries exactly `timeframeMs`, `medianInterArrivalMs`, `maxInterArrivalMs`, `stddevInterArrivalMs` (ms); old seconds keys absent. | `test_timing_emits_ms_keys` (+ `test_median_inter_arrival_used_not_mean`) | For the worked example the four ms keys equal `{9000,4500,5000,500}`; `meanInterArrivalSeconds`/`stdDevSeconds` absent; validates against the open `timing` schema; median (not mean) proven with an asymmetric gap set. |
| AC-19 | On the Simulator P2 corpus: pattern-set size in `distinct_patterns_min..max` (8-10), each span in `per_pattern_type_span_min..max` (10-20), coverage in `pattern_coverage_min..max` (50-60%); bounds from `integration-thresholds.yaml`. | `test_int_pattern_set_size_span_coverage` (integration) | The integration harness runs the full 3-stage pipeline over the Simulator corpus and asserts the three metrics fall within the yaml-sourced ranges; numeric bounds read from `integration-thresholds.yaml`, not hard-coded. |
| AC-20 | On the same corpus: zero over-split (no ground-truth fault-origin gives more than one anchored event) and zero over-merge (no anchored event spanning more than one ground-truth fault-origin). | `test_int_zero_over_split_zero_over_merge` (integration) | Against the Simulator ground-truth oracle: map each anchored event to ground-truth fault-origins; assert a 1:1 mapping (each ground-truth origin gives exactly one event; each event gives exactly one origin). |
| AC-21 | Unexplained cascades emitted as an "unexplained" event (if non-empty), do not inflate the anchored count, do not fail the run; distinguishable by `anchorScenarioId` null/absent. | `test_int_unexplained_group_emitted_and_distinguishable` (integration) + unit `test_unexplained_group_does_not_inflate_count` | The unexplained event has `anchorScenarioId` null/absent; the anchored-count metric excludes it; the run completes without error. |

Every spec acceptance criterion (AC-1 through AC-21) maps to a named pytest test above. Supplementary
unit tests retained from the merged design: `test_sequence_built_from_alarm_type_not_event_type`,
`test_sequences_and_timing_built_from_typed_alarms`, and the Codebook-client contract tests below.

### Codebook client contract tests (respx against Codebook `openapi.json`)

| Test | Asserts |
|---|---|
| `test_codebook_client_resolves_active_by_domain_snapshot` | `CodebookClient.resolve_codebook_id(domain, snapshotId)` issues `GET /codebooks/active?domain=&snapshotId=` (respx) and returns `CodebookMeta.codebookId`; a 404 raises the error mapped to a fail-fast run (OQ-3 path). |
| `test_codebook_client_fetches_scenarios` | `CodebookClient.get_scenarios(codebookId)` issues `GET /codebooks/{codebookId}/scenarios` and parses the `ScenarioListResponse` into typed `Scenario`s with ordered `symptomChain` equal to `predictedSymptoms[].alarmType`. |
| `test_codebook_client_retries_then_fails_fast` | On repeated 5xx/transport errors the client retries per `CODEBOOK_RETRY_*` then raises; the run fails fast (no unanchored mining). |
| `test_codebook_client_mock_matches_published_openapi` | The respx mock's request paths/params and response bodies conform to the Codebook `openapi.json` (paths, required query params `domain`+`snapshotId`, `ScenarioListResponse` shape). |

### E2E scenarios (from this design unit's point of view)

Exercised by the integration stage against real collaborators (Kafka + Knowledge + **Codebook** in
Compose). `alarms[]` arrive in-band on `transactions.clean`.

| # | Scenario | Trigger to path | Expected outcome |
|---|---|---|---|
| 1 | 3-stage fiber-cut storm mined end to end | Publish a `transactions.clean` batch whose `alarms[]` carry a fiber-cut chain; consume, Knowledge params, Stage 1 window, Stage 2 resolve codebook + anchor, Stage 3 per-group PrefixSpan, emit | One `PatternMinedEvent` for the fiber-cut group with `sequence` equal to the `alarmType` chain, `provenance.anchorScenarioId` equal to the fiber-cut `scenarioId`, `codebookVersion` from Knowledge, correct within-group support. |
| 2 | Zero over-split / zero over-merge on the P2 corpus | Run the full Simulator P2 corpus through the pipeline | Anchored events map 1:1 to ground-truth fault-origins (AC-20); pattern-set size/span/coverage within `integration-thresholds.yaml` (AC-19). |
| 3 | Unexplained group end to end | Include cascades matching no scenario in the corpus | One unexplained `PatternMinedEvent` with `anchorScenarioId` null/absent; anchored count unchanged; run succeeds (AC-21). |
| 4 | OQ-3 resolution end to end | The run resolves `codebookVersion="current"` via `GET /codebooks/active?domain=&snapshotId=` using the run's `snapshotId` | The correct active codebook's scenarios are used; `provenance.codebookVersion` equals `"current"` (verbatim); anchoring reflects that codebook's scenarios. |
| 5 | Anchoring threshold change re-shapes output | Change Knowledge `anchoring.matchConfidenceThreshold`, re-run same input | Cascades move between anchored and unexplained across runs, with no code change (AC-7). |
| 6 | Codebook scenario change re-shapes anchoring | Change the Codebook's scenarios (different `scenarioId`s/chains), re-run | Cascades anchor to different fault-origins / become unexplained, no code change (AC-8). |
| 7 | Bounded mining removes OOM | Run a dense corpus that previously OOM-killed global PrefixSpan | Per-group bounded PrefixSpan completes without OOM; small accurate pattern set emitted. |
| 8 | Codebook down (failure path) | Stop Codebook, publish a transaction | Run fails fast with retries/back-off; no event emitted; offsets not advanced so it retries when Codebook returns; never falls back to unanchored global mining. |
| 9 | No active codebook for snapshot | Publish a transaction under a `snapshotId` with no compiled codebook | Run fails fast with `no_active_codebook`; retries after the codebook is compiled; no unanchored mining. |
| 10 | Knowledge down (failure path) | Stop Knowledge, publish a transaction | Run fails fast with retries/back-off; no event under stale/default thresholds; offsets not advanced. |
| 11 | Poison message isolation | Interleave a malformed message (and one missing `alarms[]`) with valid ones | Poison to `transactions.clean.dlq`; valid transactions still mine and emit; no crash. |
| 12 | At-least-once redelivery | Redeliver an already-consumed `eventId` | No duplicate event; dedupe metric increments. |
| 13 | No-RCA / no-topology boundary holds | Inspect every emitted event + the service's outbound calls | No event carries `rootCauseAlarmType`/`patternId`/`lifecycle`; the service makes no Topology-graph call (only Codebook scenario reads + Knowledge params). |
| 14 | Timing keys consumed by Pattern Manager (P2-GAP-10) | Emitted `patterns.mined` event flows into a real Pattern Manager `SessionWindowDeriver` (or deriver-shaped reader) | `timing` carries the four ms keys; the consumer derives a valid `sessionWindow` with no key-alias remap and no seconds-to-ms conversion. |

## Config & observability

- **Config (env):** existing — `KAFKA_BOOTSTRAP_SERVERS`, `TRANSACTIONS_CLEAN_TOPIC`,
  `PATTERNS_MINED_TOPIC`, `DLQ_TOPIC`, `CONSUMER_GROUP_ID`, `KNOWLEDGE_BASE_URL`,
  `KNOWLEDGE_CLIENT_MODE`, `KNOWLEDGE_DOMAIN`, `KNOWLEDGE_MODEL_PARAMS_RECORD_ID`,
  `KNOWLEDGE_RETRY_MAX`, `KNOWLEDGE_RETRY_BACKOFF_MS`, `SPARK_MASTER`, `MINING_ENGINE`,
  `BATCH_FLUSH_SECONDS`, `LOG_LEVEL`, `HTTP_PORT`. **NEW (Codebook):** `CODEBOOK_BASE_URL` (default
  `http://codebook-generator:8080`), `CODEBOOK_CLIENT_MODE` (`mock`/`real`), `CODEBOOK_RETRY_MAX`,
  `CODEBOOK_RETRY_BACKOFF_MS`. **No mining/anchoring-threshold or windowing-gap env vars** — those
  come only from Knowledge/Codebook.
- **Mining + anchoring params (from Knowledge, never code):** `minSupport`, `maxPatternLength`,
  `maxSequenceCount`, `WindowingParams` (tempo profiles/floors, `gapMultiplier`, `tempoPercentile`,
  class thresholds, `baseGap`, `maxClosingGap`, `minBurstSamples`), **`AnchoringParams`**
  (`matchConfidenceThreshold`, `groupingKeys`, `scoringMethod`, `tieBreak`, scorer weights
  `w_order`/`w_jaccard`), `codebookVersion`. Fault-origin scenario set from **Codebook**.
- **Observability:** `GET /health` (liveness/readiness incl. Kafka + Knowledge + **Codebook**
  reachability); `GET /metrics` (Prometheus): counters for consumed / deduped-dropped / DLQ-routed /
  patterns-emitted / mining-runs / mining-failures / fallback-gap-used / **codebook-fetch-failures** /
  **cascades-anchored** / **cascades-unexplained**, gauges for last-run session count, **anchored
  group count**, per-group sequence count, duration. **Structured JSON logs** for: message consumed,
  duplicate dropped, DLQ routed, params fetched, **codebook resolved (codebookId, scenarioCount)**,
  session window finalized (adaptive gap + tempo class per burst), **anchoring outcome per cascade
  (confidence + matched scenarioId or "unexplained")**, per-group mining started/completed (sequence
  count), events emitted (incl. the ms `timing` stats and `anchorScenarioId`), and every error.

## Build & run

- **Build/lint/test:** `ruff check`, `black --check`, `pytest` (unit/contract). Codebook + Knowledge
  client tests run via respx (no live services).
- **Container-only Spark.** Spark/PySpark is not installed locally — all Spark execution (and
  Spark-dependent Stage-3 tests) runs inside the container; local unit gate uses the `local` engine.
  The `Dockerfile` (`python:3.13-slim` + pinned Spark runtime) installs the service +
  `acp-event-model` and runs the stateless job.
- **Run (P2):** the container consumes `transactions.clean`, runs the 3 stages, emits
  `patterns.mined`, and idles/exits when the window is drained. Compose entry depends on Kafka +
  Knowledge Service + **Codebook Service (codebook-generator)**.
- **Idle phases (P1/P3):** no mining; only `/health` + `/metrics` respond.

## Domain extensibility (how a second domain works with zero code change)

The whole pipeline is **domain-agnostic** — every domain-specific input flows from Knowledge (params)
and Codebook (scenarios), both keyed by `{domain}`:

- **Alarm vocabulary / cascade shapes:** never in source — the mined `sequence` tokens are whatever
  `alarms[].alarmType` values arrive; scenario chains are whatever the Codebook returns.
- **Fault-origin scenario set:** authored in the Codebook per `{domain}`/`{snapshotId}`; the miner
  fetches them at runtime via the domain-scoped active-codebook resolution.
- **Matching threshold, scorer, weights, tie-break, grouping keys:** all in Knowledge
  `AnchoringParams` per `{domain}`.
- **Windowing + mining params:** all in Knowledge per `{domain}`.

**Onboarding a second domain (e.g. `optical`):** (1) author its Knowledge model-params record
(`{domain}/modelParams/pattern-miner`) with its windowing/mining/anchoring params; (2) author its
Codebook fault-origin scenarios for its snapshot(s); (3) run pattern-miner with
`KNOWLEDGE_DOMAIN=optical` (or the domain carried on its transactions) — **no code change**. No
domain-specific alarm type, fault-origin name, threshold, or scenarioId exists as a literal anywhere
in source or default config (AC-17). The Codebook client + `CascadeMatcher` + `AnchorGrouper` are
generic, config-driven components (the reusable template per CLAUDE.md's shared-code rule) —
domain/business logic lives in Knowledge + Codebook, not in pattern-miner's code.
