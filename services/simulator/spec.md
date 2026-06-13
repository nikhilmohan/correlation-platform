# simulator — Service Spec

## Purpose

The Simulator generates a domain-grounded synthetic topology and labeled alarm streams
(historical and live), replays them onto Kafka, and serves as the platform's evaluation
harness. It injects known fault scenarios with ground-truth labels — identifying the root
cause and all causally downstream (child) alarms for each injected incident — so that
downstream services (enrichment, noise-filter, pattern-miner, correlation-engine) can be
measured against a known answer.

Two distinct alarm-feed scenarios drive two distinct learning/correlation paths:

- **History / batch** (`alarms.history`): a complete labeled alarm corpus fed to the
  enrichment → noise-filter → pattern-miner pipeline for **pattern mining and discovery**
  (the learning path).
- **Live / streamed** (`alarms.live`): a real-time-paced alarm stream fed to the enrichment →
  correlation-engine pipeline for **real-time alarm correlation** (the operational path).

Generation is **domain-parameterized**: the Simulator is built as a reusable generation and
replay engine coupled to a swappable **domain pack** (the domain's object/edge types,
propagation templates, alarm shapes, and scenario library). The **Core IP domain pack** is
the only pack built for the MVP; additional domains are added as new packs without reworking
the engine.

All events emitted use the frozen `libs/event-model` Python/Pydantic binding; all
`managedObjectId` values follow the `<objectType>:<id>` scheme defined in that contract,
ensuring topology and alarm identities are shared.

## Scope

**In scope:**

- Generate a realistic synthetic Core IP topology containing PE/P/RR/peering nodes, line
  cards, ports, interfaces, IP links, IGP adjacencies, LSPs over link paths, VPN services over LSPs,
  fiber spans, and SRLG groupings. Topology size is configurable (e.g., 10–200 nodes). Each `Node`
  (and the `Interface`s it hosts) carries a grounded **`igpArea`** device attribute (a few IGP
  areas — `area-0` backbone + numbered edge areas) so the Knowledge `trailPolicy` igpArea boundary
  is a real, populated dimension (the prior gap: the boundary referenced an attribute no producer
  emitted, leaving area-bounding inert).
- Place devices into **≥10 distinct grounded telco-PoP geo sites** (`name`/`latitude`/`longitude`/
  `region`), drawn from a fixed grounded catalogue (no reused or fabricated coordinates), so
  `SITE_COUNT=10` yields 10 distinct grounded sites and the web-ui site drill-down is non-trivial.
- Assign stable `managedObjectId`s (per the `libs/event-model` scheme, objectTypes from the
  nine known typed graph layers: `Node`, `LineCard`, `Port`, `IPLink`, `IGPAdjacency`, `LSP`,
  `VPNService`, `FiberSpan`, `SRLG`) that are shared between the topology snapshot file and
  all emitted alarms.
- Generate a domain-grounded topology snapshot **file** and upload it to the Topology
  Service's published **ingestion API** (OpenAPI 3.1). The Simulator is a **client** of that
  API; it builds its upload client against Topology's published OpenAPI, never against
  Topology's source code. The integration point is config-switchable (mock for unit tests;
  real Topology Service for integration). The topology-snapshot file format is a versioned
  contract.
- Generate labeled fault scenarios: inject a known root cause, produce a cascade of child
  alarms per the §5 propagation templates (e.g., fiber cut → optical → `LinkDown` → routing →
  `LSPDown` → service/QoS tail), with configurable timing jitter. The pack ships **8-10 distinct
  grounded fault scenarios** (one per Knowledge `faultOriginType` plus SRLG co-failure / line-card
  fan-out): **fiber-cut, line-card-fault, port-fault, interface-fault, node-failure,
  ip-link-failure, lsp-te-failure, routing-adjacency-failure, srlg-shared-risk-failure** — and each
  scenario's cascade (over the topology closure with fan-out + SRLG fate-sharing) spans **10-20
  distinct alarm TYPES**, injected with **multiple instances per scenario** (so the patterns are
  minable and cover the bulk of the signal — the ~50-60% pattern-coverage target). The alarm-type
  set is the **29-token expanded Core IP `alarmTypeVocabulary`** authored in Knowledge; the pack's
  alarm-shapes and propagation align to that expanded vocabulary and the 28 propagation templates.
  **Every emitted alarm carries the required canonical `alarmType`** token (a member of that
  vocabulary — e.g. `FiberCut`, `LOS`, `LOF`, `OpticalPowerLow`, `PortDown`, `LineCardFault`,
  `CRCErrors`, `InterfaceDown`, `LinkDown`, `IPLinkDown`, `ISISAdjacencyDown`, `BGPPeerDown`,
  `LSPDown`, `TETunnelDown`, `ReachabilityLoss`, `ServiceDegraded`, `Congestion`), set from the
  domain pack's alarm shape. `alarmType` is the cross-source
  canonical **join key**, distinct from `eventType` (X.733 category) and `probableCause` (X.733
  probable cause); the Simulator is an **origin** of alarms and MUST populate it.
- Generate background noise alongside injected scenarios: at minimum **3 noise classes**
  (e.g., flapping alarms, self-clearing transients, chatty standing alarms, coincidental
  unrelated alarms — the exact classes are a design/config decision).
- Persist and expose a **ground-truth label** per injected scenario in the form
  `{rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children}` for evaluation use.
  `rootCauseAlarmType` is the root cause's canonical `alarmType` token, so the RCA-accuracy oracle
  compares the injected root cause to `correlation.results.rootCauseAlarmType` on the **same token
  space** (like-for-like, not `probableCause`).
- Replay modes: **history** (batch — emits to `alarms.history`) and **live** (streamed with
  wall-clock pacing — emits to `alarms.live`), as configured.
- **Configurable demo volume:** a **`TOTAL_ALARMS` target** knob (the Simulator solves
  scenario-instance / background counts to approximately hit the target total) plus **named,
  overridable demo profiles** — `p1-demo` (P1 topology: `SITE_COUNT=10`, ~50 nodes), `p2-demo`
  (~1000 alarms, ~20% noise), `p3-demo` (~500 live alarms) — so the demo volumes are repeatable and
  asserted. Profiles are config (overridable defaults); a **subset** (1-2 scenarios) or the full set
  is runnable. The pinned volumes/coverage are asserted via `simulator_alarms_emitted_total` in the
  integration thresholds.
- Serve as the integration test oracle: the integration-threshold metrics defined in this spec
  are the numeric targets asserted by the integration test harness.
- Read scenario configuration (topology size, fault scenario selection, timing jitter, noise
  mix, noise class definitions) from local config files or the Knowledge Service; no
  hard-coded values.
- Emitted `AlarmEvent` payloads carry X.733-shaped fields (per the §5 alarm shapes in the
  Solution Design) and reflect realistic Core IP layering.
- **Ingest mode (skip generation — replay a pre-created dataset verbatim).** In addition to
  the default *generate* mode, the Simulator can run in an **ingest** mode that **skips the
  generation stage entirely** and replays a fixed, pre-created dataset:
  - **Topology:** load a pre-created topology snapshot JSON file (instead of running the topology
    builder), validate it against the **same single canonical
    `services/topology/schema/snapshot.schema.json`**, and upload it via the **existing** Topology
    ingestion client (P1) — reusing the upload path, skipping generation.
  - **Alarm corpus:** load a pre-created alarm corpus file (an ordered JSONL of the emitted
    `AlarmEvent` stream — the canonical exported corpus format, below) and replay it **verbatim**
    via the **existing** replay path (batch → `alarms.history` for P2, live wall-clock-paced →
    `alarms.live` for P3) — skipping the scenario/cascade/noise synthesizer. Ingested alarms are
    replayed as-is: their `alarmType`, `managedObjectId`, `trailIds`, `raisedAt`, severity, and
    ordering are preserved (no re-jittering, no regeneration).
  - **Labels:** load the matching ground-truth labels file (the JSONL labels format already
    defined) so the oracle/eval still works on the ingested corpus, exactly as for a generated run.
  - **Validation, fail-fast:** ingested `AlarmEvent`s validate against the frozen
    `libs/event-model` `AlarmEvent` binding (incl. the required `alarmType`); the ingested snapshot
    validates against the canonical `snapshot.schema.json`; malformed input aborts the run before
    any emission/upload.
  - Ingest is config/CLI-selected (a mode flag + per-file inputs), reuses the upload and replay
    paths, and **introduces no new Kafka topic and no event-model change** — it replays the
    existing `AlarmEvent` on the existing topics.
- **Generate-and-export round-trip ("generate once, replay many").** A generate run can **export
  its dataset to files** that the ingest mode can later replay verbatim, producing a fixed,
  shareable demo dataset:
  - **Topology snapshot file** — the generated snapshot the Simulator already writes is the
    ingestible topology file (reused as-is).
  - **Alarm corpus file** — the generated, ordered `AlarmEvent` stream (in emit order, with topic)
    is written to a **canonical corpus file** (see Contract) so it can be re-ingested.
  - **Labels file** — the ground-truth labels export the Simulator already writes is reused as the
    ingestible labels file.
  - The round-trip is explicit: **generate → export (snapshot + corpus + labels) → ingest-replay
    reproduces the same stream** on the same topic(s).
- **CLI surface for generate / ingest / export.** The generate, ingest, and export capabilities
  are surfaced as **CLI options** in the Simulator usage (with env-var equivalents), so an operator
  can `generate --export-corpus …` then later `ingest --topology-file … --alarms-file …
  --labels-file …`.

## Out of scope

- Loading or owning the topology graph in Apache AGE — that is the exclusive domain of the
  Topology Service.
- Enriching, correlating, or scoring alarms — those are the responsibilities of the Enrichment
  Service, Noise Filter, Pattern Miner, and Correlation Engine respectively.
- Authoring propagation templates or trail policy — the Knowledge Service owns those. The
  Simulator only consumes scenario configs that encode what cascade to produce; it does not
  define the policy.
- Acting as a general-purpose network traffic or device emulator.
- Schema migration or multi-domain / HA topology modelling (deferred per MVP non-goals in the
  Solution Design).
- **Domain packs beyond Core IP.** Only the Core IP domain pack is built for the MVP. Adding
  a new domain pack in the future must be achievable without reworking the reusable engine,
  but no non-Core-IP domain pack is in scope for this release.

## Tasks (high-level)

1. Generate a typed multi-layer topology using the active domain pack — for the Core IP pack:
   PE/P/RR/peering nodes, line cards, ports, interfaces, IP links, IGP adjacencies, LSPs, VPN
   services, fiber spans, SRLG groups — with configurable size, assigning each object a stable
   `managedObjectId` per the `libs/event-model` scheme. Place devices into **≥10 distinct grounded
   geo sites** (from a fixed grounded catalogue) and stamp a grounded **`igpArea`** device
   attribute on each `Node` (and its `Interface`s) so the Knowledge igpArea trail-policy boundary is
   populated.
2. Generate the domain-grounded topology snapshot **file** (versioned contract; see Contract
   section) and upload it to the Topology Service's **ingestion API** (client built against
   Topology's published OpenAPI 3.1 spec; integration point is config-switchable mock/real).
3. Load fault scenario configurations (from local files or Knowledge Service) defining which
   root-cause types to inject, their cascade templates, timing jitter parameters, and noise
   mix.
4. Inject labeled fault scenarios into the alarm stream: the pack supplies **8-10 distinct
   grounded scenarios**, each producing the root-cause alarm and its causally downstream child
   alarms per the configured cascade templates (the 28 Knowledge propagation templates over the
   topology closure with fan-out + SRLG fate-sharing), spanning **10-20 distinct alarm types**, with
   **multiple instances per scenario** and timing jitter applied. **Every emitted
   `AlarmEvent` carries the required canonical `alarmType` token** (a member of the 29-token Knowledge
   `alarmTypeVocabulary`) set from the pack's alarm shape — the cross-source join key,
   distinct from `eventType`/`probableCause`. Record the ground-truth label
   `{rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children}` for each injected
   scenario, sufficient for the oracle to measure noise-removal, retention, pattern-quality,
   alarm-reduction, and RCA accuracy over the richer pack.
5. Inject background noise alarms (at least 3 noise classes from the domain pack's scenario
   library) interleaved with the labeled scenario alarms, at configurable rates and mix.
6. Replay the alarm stream in **history** mode (batch, onto `alarms.history` — feeds pattern
   mining and discovery) or **live** mode (streamed with wall-clock pacing, onto `alarms.live`
   — feeds real-time alarm correlation), as configured. Support a **`TOTAL_ALARMS` target** knob and
   **named overridable demo profiles** (`p1-demo`/`p2-demo`/`p3-demo`) so the demo volumes
   (~1000 P2, ~500 P3) are repeatable; allow subset (1-2 scenario) runs.
7. Make ground-truth labels retrievable for evaluation — the retrieval mechanism (REST API vs.
   file export) is a design decision; see Open questions.
8. Accept generation parameters from a swappable **domain pack** interface — object/edge
   types, propagation templates, alarm shapes, and scenario library are supplied by the pack,
   not hard-coded in the engine. Domain business logic must not leak into the shared engine.
9. Expose `/health` and `/metrics` endpoints and emit structured JSON logs.
10. **Ingest mode — skip generation, replay a pre-created dataset verbatim.** Add a run mode that,
    instead of generating, **loads pre-created files and skips the generation stage**: a topology
    snapshot file (P1 — validated against the canonical `snapshot.schema.json`, uploaded via the
    existing ingestion client), an alarm corpus file (P2 batch → `alarms.history` / P3 live →
    `alarms.live`, replayed verbatim via the existing replay path — no scenario/cascade/noise
    synthesis), and the matching ground-truth labels file (so the oracle still works). Each
    ingested `AlarmEvent` validates against the frozen `libs/event-model` binding (incl. required
    `alarmType`); the snapshot validates against the canonical schema; malformed input fails fast
    before any emission/upload. No new topic, no event-model change.
11. **Export mode — generate-and-export round-trip.** Add a capability so a *generate* run writes
    its dataset to files the ingest mode (Task 10) can replay verbatim: the generated topology
    snapshot file (reused), the **alarm corpus file** (the ordered emitted `AlarmEvent` stream with
    topic, in the canonical corpus format), and the labels file (reused). Round-trip: generate →
    export → ingest-replay reproduces the same stream.
12. **Surface generate / ingest / export in the CLI.** Add CLI options (+ env equivalents) for the
    mode selector and the per-file inputs/outputs (`--ingest`/`SIM_MODE`, `--topology-file`,
    `--alarms-file`, `--labels-file`, `--export-corpus`), alongside the existing generate CLI, with
    clear usage (generate vs. ingest, which files, which phase/topic).

## Phase applicability

The Simulator is **Active in all three runtime phases** and serves as the **evaluation oracle
throughout**: the ground-truth `{rootCause, children}` labels it persists, and the integration
thresholds it owns (see "Integration thresholds" section), are what the integration test
harness asserts across the learning and real-time phases.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Generates the domain-grounded topology snapshot file and uploads it to the Topology Service ingestion API, establishing the graph that all subsequent phases depend on | Active | Output: topology snapshot file (versioned JSON contract) → Topology ingestion API (HTTP upload, config-switchable mock/real) |
| P2 — Pattern learning | Replays the labeled historical alarm corpus (batch) onto `alarms.history`, feeding the enrichment → noise-filter → pattern-miner learning pipeline; ground-truth labels serve as the oracle for pattern-quality evaluation | Active | Output: `alarms.history` (`AlarmEvent` payloads, batch) |
| P3 — Real-time correlation | Replays the labeled live alarm stream (wall-clock paced) onto `alarms.live`, feeding the enrichment → correlation-engine real-time pipeline; ground-truth labels and integration thresholds are the oracle for RCA accuracy and alarm-reduction evaluation | Active | Output: `alarms.live` (`AlarmEvent` payloads, wall-clock paced) |

**Ingest mode is available in every phase** (it replaces the *generation* stage, not the phase
role): P1 ingests a **topology snapshot file** (validated, uploaded via the existing ingestion API);
P2 ingests an **alarm corpus file** replayed batch onto `alarms.history`; P3 ingests an **alarm
corpus file** replayed wall-clock-paced onto `alarms.live`. In all three the matching **labels
file** is loaded so the oracle still works. The phase role (Active producer/uploader) and the
outputs (topic/upload) are unchanged — only the *source* of the topology/alarms changes from
generated to pre-created.

## Contract

- **Consumes (Kafka):** — (none; the Simulator is a pure Kafka producer)
- **Produces (Kafka):**
  - `alarms.history` — batch historical alarm replay; feeds enrichment → noise-filter →
    pattern-miner (learning path). Payload: `AlarmEvent` (frozen `libs/event-model`).
  - `alarms.live` — streamed live alarm replay with wall-clock pacing; feeds enrichment →
    correlation-engine (real-time correlation path). Payload: `AlarmEvent` (frozen
    `libs/event-model`).
  - Every emitted `AlarmEvent` payload populates the **required canonical `alarmType`** field (a
    Knowledge `alarmTypeVocabulary` token) — the Simulator is an **origin** of alarms and one of
    the two `alarmType` populators (with Enrichment) per the `architecture.md` alarmType invariant.
    `alarmType` is the canonical join key, distinct from `eventType`/`probableCause`. **No
    event-model change**: `alarmType` is already a required field on the merged `AlarmEvent`
    schema; this is a population requirement only.
- **Topology snapshot file (produced artifact — versioned contract):**
  - The Simulator generates a structured JSON topology snapshot file describing the typed
    nodes and edges of the domain graph (for Core IP: the object types and their edges).
  - Every object carries its `managedObjectId` in the canonical `<objectType>:<id>` scheme.
  - The **topology-snapshot file schema is a versioned contract with a single canonical source**,
    `services/topology/schema/snapshot.schema.json`, **owned by the Topology Service** (the
    validating owner). The Simulator validates its generated file against **that same schema** — it
    keeps **no independent copy**. A change to it requires a `docs/architecture.md` update and human
    approval, exactly like a topic/payload change.
  - The file is uploaded to the Topology Service's **frozen** ingestion API
    (`POST /topology/snapshots`, returning **HTTP 200** `SnapshotIngestResponse { snapshotId,
    domain, status, nodeCount, edgeCount, changeType }`); the Simulator reads `snapshotId` from the
    **200** response body. It is not a Kafka event.
- **Alarm corpus file (exported artifact / ingest input — versioned contract):**
  - A generate run can **export** the ordered emitted alarm stream to an **alarm corpus file**: a
    JSONL file where each line is the `TypedEnvelope[AlarmEvent]` as emitted, in emit order, with
    the target topic recorded (so a re-ingest reproduces the same stream on the same topic). The
    corpus file carries the **already-emitted** events — no new payload shape: each line wraps the
    frozen `AlarmEvent` payload, so an ingested corpus validates against the **same frozen
    `libs/event-model` `AlarmEvent` binding** (incl. required `alarmType`). The ingest mode reads
    this same file and replays each `AlarmEvent` **verbatim** (preserving `alarmType`,
    `managedObjectId`, `trailIds`, `raisedAt`, severity, ordering). The corpus file format is a
    **versioned contract owned by the Simulator** (it is a file artifact, not a Kafka topic) — its
    detailed schema/example lives in `design.md`. **No new Kafka topic and no event-model change**:
    the corpus is the existing `AlarmEvent` on the existing `alarms.history`/`alarms.live` topics,
    serialized to a file.
  - On **ingest**, alarms are replayed verbatim with **fresh envelope `eventId`s** per replay run
    (so a re-ingest does not collide with a prior run's events) while preserving the `AlarmEvent`
    payload (incl. `alarmId`, `alarmType`, `managedObjectId`, `raisedAt`) — keeping at-least-once
    dedupe honest and the dataset replayable many times.
- **APIs exposed:** The Simulator must make ground-truth labels retrievable for evaluation
  (per §6.1 and §10 of the Solution Design). Whether this is a REST API or a file-based
  export is a **design decision** — see Open questions. If a REST API is exposed, it MUST
  publish an OpenAPI 3.1 document at `/openapi.json` (and check in `openapi.json` to
  `services/simulator/`) per the `docs/architecture.md` API contract requirement. The
  ground-truth label retrieval surface carries
  `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children}`
  — including `rootCauseAlarmType` (canonical `alarmType` token) so the RCA oracle compares on the
  canonical join-token space.
- **APIs/integration points consumed from other services:**
  - **Topology ingestion API (Topology Service):** The Simulator uploads the topology snapshot
    file to the Topology Service's published, **frozen** ingestion endpoint
    **`POST /topology/snapshots`**, which returns **HTTP 200** `SnapshotIngestResponse
    { snapshotId, domain, status, nodeCount, edgeCount, changeType }` (synchronous — `snapshotId`
    minted inline, **not** 202/async); the Simulator reads `snapshotId` from the **200** body. The
    Simulator's upload client is built against Topology's published OpenAPI, never Topology's
    source, and validates its generated file against the single canonical
    `services/topology/schema/snapshot.schema.json`. This integration point is
    **config-switchable**: a mock/stub (generated from Topology's OpenAPI spec) for unit tests; the
    real Topology Service endpoint for integration. The Topology Service base URL and mock/real
    mode are controlled by environment variable.
  - **Knowledge Service scenario config endpoint (optional):** read scenario configuration
    parameters. Config-switchable: local-file mode (default/mock for unit tests) vs. real
    Knowledge Service (for integration). URL and mode controlled by environment variable.
- **Data owned:**
  - Ground-truth scenario labels (`{rootCause, rootCauseManagedObjectId, rootCauseAlarmType,
    children}` per injected scenario) — persisted in a store or file; medium is a design decision.
  - Scenario definitions (loaded from local config files or Knowledge Service at startup/run
    time; the Simulator does not author them).
  - Exported **alarm corpus file** (the ordered emitted `AlarmEvent` stream + topic, JSONL) when
    export is enabled — the Simulator-owned, versioned, re-ingestible dataset artifact.
- **Files consumed (ingest mode):** a pre-created topology snapshot file, a pre-created alarm
  corpus file, and a pre-created labels file (the same formats the Simulator exports). These are
  read from local paths (config/CLI); they are not Kafka inputs and require no DLQ.

## Non-functional

- **Idempotency key:** `eventId` (UUID, envelope field) for event-level deduplication;
  `alarmId` (AlarmEvent payload field) for alarm-level deduplication. Emitted events carry
  distinct `eventId`s; re-runs of the same scenario SHOULD produce new `eventId`s and
  `alarmId`s (deterministic replay seeded by config is a design decision, see Open questions).
- **Config:** all thresholds, sizes, rates, and integration URLs come from environment
  variables or the Knowledge Service — no hard-coded values. This includes: topology node
  count, scenario selection, timing jitter parameters, noise class mix and rates, replay
  speed/pacing, Kafka broker addresses, Topology Service base URL, Knowledge Service base URL,
  integration-point mode (mock vs. real). Config MUST be validated at startup; missing
  required config is a fatal error reported via structured log before exit.
- **Observability:** `/health` (liveness probe), `/metrics` (Prometheus-compatible), structured
  JSON logs on stdout. Cohort is Python (per CLAUDE.md); test framework is pytest.
- **API contract:** if an HTTP API is exposed, it publishes OpenAPI 3.1 at `/openapi.json`
  and the checked-in `services/simulator/openapi.json` is the authoritative surface; any
  surface change is a contract change requiring `docs/architecture.md` update and human
  approval.
- **Error handling:** the Simulator is primarily a producer; Kafka producer errors (e.g.,
  broker unavailable) are logged and surfaced via `/health`; there is no inbound Kafka stream
  requiring a DLQ. HTTP errors from the Topology ingestion API upload are logged and surfaced
  via `/health`. Config validation errors abort startup cleanly with a structured log entry.

## Integration thresholds (test oracle — owned by this spec)

These are the platform-level evaluation metrics the integration test harness asserts.
The Simulator owns their definitions; the numeric pass thresholds below are the **MVP targets**
approved by the human design decision resolving issue #15. They are **tunable via
config/Knowledge Service** — they are never hard-coded in service code.

| Metric | Definition | Pass threshold (MVP) |
|---|---|---|
| RCA accuracy | Fraction of incidents (in `correlation.results`) whose `rootCauseAlarmId` / `rootCauseAlarmType` matches the injected ground-truth root cause (`rootCause` / `rootCauseAlarmType`) for that scenario — compared on the canonical `alarmType` join-token space | **≥ 0.80** |
| Alarm-reduction ratio | Raw alarms emitted ÷ correlated incidents produced | **≥ 5×** |
| Noise-filter effectiveness (removal) | Fraction of injected noise alarms removed by the Noise Filter | **≥ 0.90** |
| Noise-filter effectiveness (retention) | Fraction of real (scenario) alarms retained after the Noise Filter | **≥ 0.95** |
| Pattern quality | Count of injected patterns recovered by the Pattern Miner ÷ total injected patterns | **≥ 0.80** |
| Distinct patterns × per-pattern span | Distinct labeled scenario signatures and the distinct-`alarmType` span of each | **8-10 patterns, each 10-20 types** |
| Pattern coverage of volume | Scenario (in-some-label) alarms ÷ total emitted | **~50-60%** |
| `p2-demo` / `p3-demo` volume | `simulator_alarms_emitted_total` after the named profile run | **~1000 (P2) / ~500 (P3), within tolerance** |
| Distinct grounded sites | Distinct `Site` nodes with distinct grounded geo at `SITE_COUNT=10` | **= 10 distinct** |

## Acceptance criteria

Each criterion is phrased to map to a single pytest test.

1. **Topology snapshot file is valid and internally consistent.** Given a configured topology
   size N (e.g., N=20), the Simulator generates a topology snapshot file where: every node
   has a `managedObjectId` of the form `Node:<id>`; every line card references a node that
   exists in the snapshot; every port references a line card that exists; every IP link
   references two ports that exist; SRLG groups reference IP links that exist. No dangling
   references.

2. **`managedObjectId` values are shared between topology snapshot file and alarms.** Every
   `managedObjectId` in emitted `AlarmEvent` payloads is present in the topology snapshot file
   generated in the same run. No alarm references an object absent from the snapshot.

3. **`managedObjectId` values conform to the frozen contract scheme.** Every `managedObjectId`
   in the topology snapshot file and in every emitted `AlarmEvent` matches the pattern
   `<knownObjectType>:<non-empty-id>` where `knownObjectType` is one of `Node`, `LineCard`,
   `Port`, `IPLink`, `IGPAdjacency`, `LSP`, `VPNService`, `FiberSpan`, `SRLG`, as enforced
   by the `libs/event-model` Python binding's `managedObjectId` validator.

4. **Fiber-cut scenario cascade is correct.** When the Simulator injects a fiber-cut scenario,
   the emitted alarm set contains: the root-cause alarm on the `FiberSpan` object (canonical
   `alarmType` = `FiberFault`), plus child alarms carrying the canonical effect `alarmType`s
   `LinkDown` on each affected `IPLink`, `AdjDown` on each affected `IGPAdjacency`, `LSPDown` on
   affected LSPs, and `ReachabilityLoss` on affected VPN services — matching the §5 propagation
   template for `RIDES_ON` / `ADJACENCY_OVER` / `TRAVERSES` / `SERVES` edges. The ground-truth
   label for this scenario records the `FiberSpan` alarm as root cause (`rootCauseAlarmType` =
   `FiberFault`) and all downstream alarms as children.

5. **Line-card-fault and port-fault scenarios are producible and distinguishable.** The
   Simulator can produce a `line-card-fault` scenario (root cause on a `LineCard` object,
   cascade per the `HOSTED_ON` template) and a `port-fault` scenario (root cause on a `Port`
   object) as distinct, separately-labeled scenarios. Their ground-truth labels differ in
   `rootCause` object type and `rootCauseAlarmType`.

6. **At least 3 noise classes are generated.** When noise injection is enabled, the Simulator
   emits alarms belonging to at least 3 distinct configured noise classes. Each noise alarm
   can be identified as noise by its absence from any ground-truth `children` set.

7. **Emitted alarm events validate against the frozen `AlarmEvent` schema (required fields incl.
   `alarmType`).** Every `AlarmEvent` payload emitted by the Simulator passes validation by the
   `libs/event-model` Python/Pydantic binding without raising a validation error. **All required
   fields** (`alarmId`, `managedObjectId`, `eventType`, `probableCause`, **`alarmType`**,
   `perceivedSeverity`, `raisedAt`, `state`, `trailIds`) are present; `alarmType` is a non-empty
   canonical token drawn from the domain's Knowledge `alarmTypeVocabulary` (e.g. `FiberFault`,
   `LOS`, `PortDown`, `InterfaceDown`, `LinkDown`, `AdjDown`, `LSPDown`, `ReachabilityLoss`) and is
   distinct from `eventType` (X.733 category) and `probableCause` (X.733 probable cause); `state` is
   one of `raised` or `cleared`. (A payload missing `alarmType` MUST fail validation — `alarmType`
   is in the merged `AlarmEvent` `required[]`.)

8. **History replay lands on `alarms.history`.** In history mode, all alarm events are
   produced to the `alarms.history` Kafka topic and zero alarm events are produced to
   `alarms.live`.

9. **Live replay lands on `alarms.live` with wall-clock pacing.** In live mode, all alarm
   events are produced to the `alarms.live` Kafka topic and zero alarm events are produced to
   `alarms.history`. Successive events are emitted with inter-event delays proportional to the
   configured pacing multiplier (i.e., the delay between two events is not zero for a pacing
   factor > 0).

10. **Ground-truth labels are retrievable for evaluation.** For each injected scenario, a
    ground-truth record `{rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children}` can
    be retrieved after a run (via the mechanism determined in design — REST API or file). The
    retrieved `rootCause` matches the injected root-cause alarm's `alarmId`, and `children`
    contains the `alarmId`s of all causally downstream alarms emitted for that scenario.

10a. **Ground-truth label carries the root-cause canonical `alarmType` (`rootCauseAlarmType`).**
    For each injected scenario, the retrievable ground-truth record includes `rootCauseAlarmType`,
    a canonical `alarmTypeVocabulary` token equal to the `alarmType` of the alarm identified by
    `rootCause`. This lets the RCA-accuracy oracle compare the injected root cause to
    `correlation.results.rootCauseAlarmType` on the **same token space** (like-for-like, not
    `probableCause`). The retrieval surface's response shape is frozen at
    `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children}`.

11. **Topology size is configurable — no hard-coded node count.** Given two separate runs
    configured with node counts N1=10 and N2=50, the topology snapshots produced have
    approximately N1 and N2 nodes respectively. No default topology size is compiled into the
    service binary.

12. **Timing jitter is configurable — no hard-coded jitter value.** Given two separate runs
    configured with different jitter parameters (e.g., jitter_stddev_ms=0 and
    jitter_stddev_ms=500), the inter-alarm timing distributions differ measurably. A run with
    jitter_stddev_ms=0 produces alarms at deterministic intervals; a run with
    jitter_stddev_ms=500 produces alarms at varied intervals.

13. **Noise mix is configurable — no hard-coded noise rate.** Given a configured noise rate of
    0, the Simulator emits zero noise alarms. Given a non-zero noise rate, noise alarms are
    present. Two runs with different noise mix configurations produce statistically different
    ratios of noise-to-scenario alarms.

14. **Topology snapshot file validates against the canonical topology-file schema.** The topology
    snapshot file generated by the Simulator in any run validates, without schema errors, against
    the **single canonical `services/topology/schema/snapshot.schema.json`** (the Topology-owned
    contract between the Simulator and the Topology Service — the Simulator keeps no independent
    copy). All required fields are present and all object references are well-formed.

15. **Topology ingestion API integration point is config-switchable.** When the
    `TOPOLOGY_API_MODE` env var is set to `mock`, the Simulator's upload client directs
    requests to the mock/stub (generated from Topology's published OpenAPI spec) and does not
    attempt to contact a real Topology Service. When set to `real`, it contacts the configured
    `TOPOLOGY_API_BASE_URL` at `POST /topology/snapshots`. Switching the mode requires no code
    change.

15a. **Ingestion reads `snapshotId` from the frozen 200 `SnapshotIngestResponse`.** On upload to
    `POST /topology/snapshots`, the Simulator's client receives **HTTP 200** with body
    `SnapshotIngestResponse { snapshotId, domain, status, nodeCount, edgeCount, changeType }` (the
    frozen synchronous shape — **not** a 202 or a bare `{snapshotId}` body) and reads `snapshotId`
    from that 200 body, recording it on the run so subsequent alarms share the same snapshot
    identity. The mock stub (generated from Topology's published OpenAPI) returns the same 200 shape.

16. **`/health` returns 200 when the service is running.** A GET request to `/health` returns
    HTTP 200. A service that has not completed startup or has lost its Kafka connection returns
    a non-200 response.

17. **`/metrics` returns Prometheus-format output.** A GET request to `/metrics` returns HTTP
    200 with `Content-Type: text/plain` and at minimum one counter or gauge metric
    (e.g., `simulator_alarms_emitted_total`).

18. **Config validation fails fast on missing required config.** Starting the Simulator
    without a required environment variable (e.g., Kafka broker address) produces a structured
    JSON log error and exits with a non-zero exit code before emitting any events.

19. **Domain pack separation — no Core IP literals in the engine.** The generation engine
    contains no Core IP-specific object type names, propagation template literals, alarm shape
    definitions, or scenario library entries; all such domain-specific values are supplied by
    the domain pack. Verifiable by the existence of a domain pack interface/protocol and the
    absence of Core IP-specific literals in engine source files.

20. **Integration thresholds are owned by spec and sourced from config.** The five integration
    threshold values (RCA accuracy ≥ 0.80; alarm-reduction ≥ 5×; noise-filter removal ≥ 0.90;
    noise-filter retention ≥ 0.95; pattern quality ≥ 0.80) are present in the integration
    test harness configuration sourced from this spec, and are not hard-coded as literals in
    service implementation code.

### MVP-grounding acceptance criteria (fixes B1-B5 — pack content + config + ground-truth)

21. **Pack ships 8-10 distinct grounded scenarios, each spanning 10-20 distinct alarm types.**
    The Core IP pack's scenario library contains **9 distinct grounded scenarios** (`fiber-cut`,
    `line-card-fault`, `port-fault`, `interface-fault`, `node-failure`, `ip-link-failure`,
    `lsp-te-failure`, `routing-adjacency-failure`, `srlg-shared-risk-failure`), one per Knowledge
    `faultOriginType` plus SRLG co-failure / line-card fan-out. For each scenario, a single injected
    instance's symptom set spans **at least 10 and at most ~24 distinct canonical `alarmType`
    tokens** (drawn from the 29-token Knowledge `alarmTypeVocabulary`), and each is injected with
    multiple instances (`SCENARIO_INSTANCES`). The 9 root-cause `alarmType`s are distinct per
    scenario.

22. **Each `Node` (and its Interfaces) carries a grounded `igpArea`.** The generated snapshot stamps
    a non-empty `igpArea` device attribute on every `Node` and on every `Interface` it hosts
    (interface inherits its node's area); at least one `area-0` backbone area and one numbered edge
    area are present; `IGP_AREA_COUNT=N` yields N distinct areas. `igpArea` is a Knowledge
    `attributeCatalogue` deviceKey, validated semantically by Topology (no event-model/schema
    change).

23. **`TOTAL_ALARMS` target and demo profiles hit the demo volumes.** With `TOTAL_ALARMS` set (or a
    demo profile selected) the emitted alarm total is within tolerance of the target;
    `DEMO_PROFILE=p2-demo` emits **~1000 alarms at ~20% noise** on `alarms.history`;
    `DEMO_PROFILE=p3-demo` emits **~500 alarms** on `alarms.live`. Profiles are overridable defaults
    (an individual env var overrides the profile value) and a `SCENARIOS` subset runs only the
    selected scenarios.

24. **≥10 distinct grounded geo sites; `SITE_COUNT=10` yields 10 distinct.** The geo catalogue holds
    at least 10 distinct grounded telco-PoP entries (distinct coordinates); a run with
    `SITE_COUNT=10` produces exactly 10 `Site` nodes with 10 distinct `{name,latitude,longitude,
    region}` tuples (no reused or fabricated coordinates). A `SITE_COUNT` above the catalogue size
    fails fast.

25. **Ground-truth supports the oracle metrics on the richer pack.** For a full demo run, every
    scenario has a ground-truth label whose `rootCauseAlarmType` is a 29-token-vocabulary member
    equal to its root alarm's `alarmType`; noise and background alarms appear in no label's
    `children`; the five integration metrics (noise-removal, retention, pattern-quality,
    alarm-reduction, RCA accuracy) are computable from the labels plus
    `simulator_alarms_emitted_total{scenario,alarmType}` over all 9 scenarios.

### Ingest / export / CLI acceptance criteria (no contract change — reuse upload + replay paths)

26. **Ingest a topology snapshot file → uploaded verbatim, generation skipped (P1).** With ingest
    mode selected and a pre-created topology snapshot file given (`INGEST_TOPOLOGY_FILE` /
    `--topology-file`), the Simulator **does not run the topology builder**: it loads the file,
    validates it against the **single canonical `services/topology/schema/snapshot.schema.json`**,
    and uploads it via the **existing** ingestion client to `POST /topology/snapshots`, reading
    `snapshotId` from the 200 `SnapshotIngestResponse`. The uploaded body is byte-equivalent to the
    ingested file (no regeneration, no node/edge mutation). A snapshot that fails schema validation
    aborts the run before any upload.

27. **Ingest an alarm corpus file → replayed verbatim to history/live (P2/P3).** With ingest mode
    and a pre-created alarm corpus file (`INGEST_ALARMS_FILE` / `--alarms-file`), the Simulator
    **does not run the scenario/cascade/noise synthesizer**: it loads the ordered corpus and
    replays each `AlarmEvent` via the **existing** replay path — batch to `alarms.history` in P2,
    wall-clock-paced to `alarms.live` in P3. The replayed alarms preserve their `alarmType`,
    `managedObjectId`, `trailIds`, `raisedAt`, severity, and **order** verbatim (no re-jittering, no
    regeneration). The replayed count equals the corpus line count; the target topic matches the
    phase/mode.

28. **Ingested alarms validate against the frozen `AlarmEvent` binding (incl. `alarmType`).** Every
    `AlarmEvent` read from an ingested corpus file constructs and validates against the frozen
    `libs/event-model` Python/Pydantic binding without error; all required fields (incl.
    `alarmType`) are present. A corpus line whose payload is missing `alarmType` or otherwise
    malformed **fails fast** (the run aborts with a structured error before any emission) — the
    ingest path never silently emits an off-contract alarm.

29. **Ingested labels file is loaded so the oracle still works.** With a pre-created labels file
    (`INGEST_LABELS_FILE` / `--labels-file`), the loaded ground-truth labels are retrievable after
    the run (same `/labels` surface + the frozen
    `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children}`
    shape) and reference `alarmId`s present in the ingested corpus, so the integration oracle scores
    the ingested run exactly as a generated run.

30. **Export-then-ingest round-trips identically.** A generate run with export enabled
    (`EXPORT_CORPUS_FILE` / `--export-corpus`, plus the snapshot + labels files) writes a dataset
    that, when fed back through ingest mode, **reproduces the same alarm stream** — the same ordered
    sequence of `(topic, AlarmEvent payload)` pairs (matching on `alarmId`/`alarmType`/
    `managedObjectId`/`raisedAt`/order), the same uploaded topology snapshot, and the same labels —
    differing only in fresh envelope `eventId`s per replay run.

31. **CLI exposes generate / ingest / export options.** The CLI usage surfaces the mode selector
    and per-file inputs/outputs: a generate path with `--export-corpus PATH` and an ingest path with
    `--topology-file`/`--alarms-file`/`--labels-file` (each with an env-var equivalent —
    `SIM_MODE`, `EXPORT_CORPUS_FILE`, `INGEST_TOPOLOGY_FILE`, `INGEST_ALARMS_FILE`,
    `INGEST_LABELS_FILE`). `--help` documents both paths; selecting ingest without the file(s) the
    phase requires fails fast with a clear usage/config error.

## Open questions

- **OQ-2 (design decision — does not block spec): How are ground-truth labels retrieved?**
  The Solution Design §6.1 states "ground-truth labels retrievable for evaluation" without
  specifying the mechanism. Options include: (a) an HTTP REST endpoint (requires publishing
  an OpenAPI 3.1 spec per the architecture contract), (b) a flat file written at end-of-run
  (simpler, no API surface), (c) a queryable store. This is a design-stage decision; the spec
  captures both that retrieval must be possible and that if a REST API is chosen it must
  follow the OpenAPI 3.1 requirement. No design decision should be made until this is
  confirmed.

- **OQ-3 (design decision): Deterministic vs. random replay seeding.** Should the Simulator
  support a configurable random seed so that re-runs reproduce the exact same alarm sequence
  (useful for regression testing)? The spec requires configurable jitter but does not mandate
  determinism. This is a design-stage decision.

- **OQ-4 (design decision — resolved by the frozen Topology contract): Topology-snapshot file
  schema location.** The `docs/architecture.md` notes "where it lives (event-model vs. a `schema/`
  dir) is a design decision." This is now **resolved to a single canonical source**: the schema is
  **`services/topology/schema/snapshot.schema.json`, owned by the Topology Service** (the validating
  owner), and the Simulator validates its generated file against **that same file** — it keeps **no
  independent copy** (eliminating producer/validator drift). See `design.md` for the rationale.
