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

A third operating mode — **P3 topology-and-pattern-driven live alarm synthesis** — extends the
Simulator so it can synthesize a live alarm stream grounded in the **already-deployed topology,
trails, and approved patterns** read back from the running services' APIs, without regenerating
topology or re-mining patterns. This mode targets a configurable ~60-70% auto-correlation +
RCA rate (the fraction of emitted alarms that form cascades matching approved patterns on their
real trails) and provides full ground-truth labels for verifying that KPI against the
Correlation Engine's `/stats` and `/incidents` responses.

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
  vocabulary — e.g. `FiberFault`, `LOS`, `LOF`, `OpticalPowerLow`, `PortDown`, `LineCardFault`,
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
- **P3 topology-and-pattern-driven live alarm synthesis (additive mode).** A new operating
  mode that synthesizes a P3 live alarm stream grounded in the **already-deployed, already-approved
  state of the platform** — no topology regeneration, no pattern re-mining:
  - **Read** the deployed topology snapshots (Topology Service `GET /topology/snapshots`), trail
    members (Trail Builder `GET /trails/{trailId}` → `members[]` of `{managedObjectId, objectType}`),
    and approved patterns (Pattern Manager `GET /patterns?lifecycle=approved` → `PatternView[]` with
    `trailId`, ordered `sequence[]` of `{alarmType, optional}`, `rootCauseAlarmType`,
    `timing{timeframeMs, medianInterArrivalMs, maxInterArrivalMs, stddevInterArrivalMs}`,
    `sessionWindow{windowMs, type}`, `snapshotId`). These three integrations are each
    **config-switchable** (mock from the collaborator's published OpenAPI for unit tests; real
    service for integration). No Topology Service graph queries are made beyond the snapshot
    listing; no NebulaGraph access.
  - **Persist** the fetched topology+trail+pattern data as a **reusable P3 config snapshot** so
    that repeated P3 runs do not require re-fetching, and a captured (topology, approved-pattern-set)
    config is reusable across runs. The persistence mechanism is a design decision.
  - **Synthesize** pattern-aligned cascades: for each approved pattern, map its `sequence[]`
    alarmTypes onto real `managedObjectId` members of the pattern's trail (from the trail's
    `members[]`), emit the cascade wall-clock-paced using the pattern's `timing` and
    `sessionWindow`, and mark the alarm matching `rootCauseAlarmType` as the root cause. The
    remaining ~30-40% of the alarm stream consists of realistic non-aligned alarms: partial
    cascades, alarms on real managed objects whose sequence does not match any approved pattern,
    and noise — representative of real-world non-correlated traffic.
  - **Configurable aligned fraction and total volume:** the fraction of alarms that are
    pattern-aligned (default range ~60-70%) and the total alarm count are configurable via
    env/profile; no hard-coded thresholds.
  - **Seeded randomization for reproducibility:** P3 synthesis uses a configurable RNG seed so
    that a run with the same seed + same persisted P3 config snapshot produces the same alarm
    sequence (deterministic replay when desired); an absent/null seed produces a fresh
    randomization. The mode is otherwise stateless across runs — topology and patterns change
    rarely, alarm synthesis runs often.
  - **Emit on `alarms.live` only** (wall-clock paced; the existing frozen `AlarmEvent` payload;
    no new topic, no event-model change). The full live path — enrichment → `alarms.enriched.live`
    → alarm-manager → `alarms.persisted.live` → correlation-engine — is unchanged.
  - **Ground-truth labels** for each synthesized cascade (which alarm is root-cause, which are
    children, which pattern it matches) are persisted and retrievable via the same label surface
    already defined, so the auto-correlation + RCA accuracy KPI is verifiable against CE
    `/stats` (`correlatedAlarmCount` / `totalAlarmsProcessed`) and `/incidents`.
  - **Standalone or full-cycle:** P3 synthesis runs either standalone (given a pre-fetched or
    pre-persisted P3 config snapshot) or as the P3 step in a full P1→P2→P3 cycle; behavior is
    identical in both cases.
  - **Backward compatible:** all existing generate/ingest/export modes and P1/P2 behavior are
    unaffected; P3 synthesis is an additive mode selected by config/CLI.
- **P3 network-wide emission with closed-loop target control (additive enhancement).** Extends
  the P3 synthesis mode so that each approved pattern's cascade is emitted on **multiple
  structurally-compatible trails** across different parts of the network (not only its
  discovery trail), and a **closed-loop controller** reliably achieves a configurable
  auto-correlation target on every run:
  - **Compatible-trail discovery:** for each approved pattern, enumerate all deployed trails
    via Trail Builder `GET /trails?snapshotId&domain&limit&offset` (the existing, published
    endpoint — **no contract change**), then `GET /trails/{id}` for each candidate (reusing
    cached results for already-fetched trails), and apply the **hostability rule** (a trail is
    compatible with pattern P if it contains at least one member of each `objectType` required
    by P's alarm sequence, including the root object type) — the same rule the Correlation
    Engine uses to auto-correlate. Prefer compatible trails in **different igp-areas** than
    the pattern's discovery trail to achieve the "different parts of the network" realism
    requirement. Cache discovered compatible trails in the **P3 config snapshot** (Task 14)
    so repeated runs do not re-enumerate. No NebulaGraph access; no new Kafka topic; no
    event-model change.
  - **Closed-loop target control:** given `P3_AUTO_CORRELATION_TARGET` (e.g. 0.6 = 60%, the
    fraction of alarms **ingested at the Correlation Engine — post-enrichment** — that
    auto-correlate, matching CE's own `correlatedAlarmCount / totalAlarmsProcessed`) and
    `P3_TOTAL_ALARMS`, the controller computes the number of complete, in-window aligned
    cascades needed so the CE-measured post-enrichment correlation rate lands at the target
    within `P3_TARGET_TOLERANCE` (default ±3 percentage points). To compensate for the
    legitimate reduction that enrichment's dedup, transient-suppression, and flap-damping
    apply on the live path, the controller **over-provisions** emitted aligned cascades by a
    configurable margin (`P3_ENRICHMENT_OVER_PROVISION_MARGIN`, default 0.0 = no
    over-provision); when non-zero the emitted aligned fraction is `TARGET /
    (1 − margin)`. The Simulator then **distributes** those cascades across the discovered
    compatible trails — spreading across distinct igp-areas where available, bounded by
    `P3_MAX_CASCADES_PER_TRAIL` (default 3) so cascades do not pile on a single trail.
  - **Randomized spread, fresh per run:** compatible trail selection and member-object assignment
    within each trail are re-randomized on each run (respecting `P3_RNG_SEED` when set), so
    successive runs emit different alarms from different network parts while each hitting the
    target.
  - **Spread policy defaults (all configurable):** `P3_MAX_CASCADES_PER_TRAIL` defaults to 3.
    When all compatible trails for a pattern share one igp-area (e.g. rare object types exist
    only in one area), the "prefer distinct areas" preference is unsatisfiable; the controller
    falls back to distributing across the available same-area trails up to the per-trail cap
    and logs a structured info entry that area spread was not achievable for that pattern.
    When a pattern has only one compatible trail, the controller still runs network-wide for
    that pattern (stacking cascades on the one trail up to the cap) and logs the absence of
    multi-trail spread.
  - **Robustness — shortfall handling:** if too few distinct compatible trails exist to reach the
    target while respecting `P3_MAX_CASCADES_PER_TRAIL`, the controller first exhausts distinct
    trails then repeats trails (with staggered timing so each repetition still produces its own
    correlatable incident) until the target is reached. If the target is genuinely unreachable
    (e.g. fewer compatible trails × max-cap than needed cascades), the controller emits the
    maximum achievable and **logs the shortfall clearly** (never silently under-delivers).
  - **Enrichment-safe aligned cascades (required).** On the P3 live path, enrichment's dedup,
    self-clear/transient-suppression, and flap-damping are expected and correct (only the
    separate noise-filter service is P2-only; it is NOT in the P3 live path). Every
    pattern-aligned cascade emitted in network-wide P3 mode MUST be synthesized to survive
    enrichment intact so the full cascade reaches the Correlation Engine and auto-correlates.
    NOTE: The enrichment-safety invariant was corrected during live validation (P3 network-wide
    build #392). The correct invariant is: **distinct dedup keys (distinct managedObjectId per
    element + distinct alarmType per sequence position) + sustained single-raise (no `cleared`
    event emitted for cascade members) + no genuine same-key duplicate**. It is NOT an alarmType
    blocklist and NOT a dedup-window spacing floor. The shipped build already implements this.
    - **Distinct dedup keys:** each cascade element targets a **distinct `managedObjectId`** and
      a **distinct `alarmType`** per sequence position. Enrichment's DedupStep key is
      `(path, source, managedObjectId, eventType, alarmType, state)`; it only collapses repeated
      identical alarms (same object + same alarmType + same state). Distinct-key elements are
      never deduped, regardless of inter-arrival timing.
    - **Sustained single-raise:** aligned cascade members are emitted as sustained single `raised`
      events — no `cleared` event is emitted for cascade members. Enrichment's SelfClearStep
      suppresses a raise only when a matching CLEAR arrives within the hold-time; a sustained
      raise with no paired clear passes through regardless of alarmType. This makes every cascade
      member self-clear-safe and flap-safe without any alarmType restriction.
    - **No genuine same-key duplicate:** distinct-object placement already prevents any
      `(managedObjectId, alarmType, state)` repeat within a cascade.
    - **Session-window timing:** inter-arrival timing stays within the pattern's
      `sessionWindow.windowMs` so CE still matches the sequence. No minimum inter-arrival floor
      above the dedup window is required for distinct-key elements.
    - **Conflict exclusion:** if a pattern's `sessionWindow.windowMs` is too short to fit even
      the minimal natural timing (a genuine structural conflict), that pattern is excluded from
      aligned synthesis and logged. This is distinct from the (now-corrected) dedup-window floor.
    - **No flapping:** no repeated raise/clear sequence on the same object within the cascade window.
    The non-aligned/noise portion of the stream MAY still include realistic transients,
    duplicates, and flaps — those are expected not to correlate, and that is correct behavior.
    Ground-truth labels record the expected-correlatable count as the count of
    enrichment-safe aligned cascade alarms; this count is measurable against CE `/stats`.
  - **Measurability:** ground-truth labels record `{patternId, trailId, managedObjectId
    assignments, scenarioType}` per cascade instance, enabling verification that (a) the expected
    auto-correlation fraction matches CE `/stats` `correlatedAlarmCount/totalAlarmsProcessed`
    and (b) incidents span multiple distinct `trailId`s per pattern — the "different parts of
    the network" claim is checkable.
  - **Backward compatible:** when `P3_AUTO_CORRELATION_TARGET` is unset (or network-wide mode
    is disabled), single-trail P3 synthesis behavior is unchanged. No new Kafka topic; no
    event-model change.

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
- **Generating or modifying topology in P3 synthesis mode.** The P3 topology-and-pattern-driven
  mode reads the deployed topology/trails/patterns; it does not generate, upload, or alter the
  topology graph. Topology generation is P1 only.
- **Re-mining or re-approving patterns in P3 synthesis mode.** P3 synthesis reads already-approved
  patterns from Pattern Manager; it does not run or trigger the P2 learning pipeline.
- **Raising alarms on non-existent managed objects.** Every `managedObjectId` in a P3-synthesized
  alarm must be present in the deployed topology snapshot. The Simulator does not fabricate object
  identities.
- **Writing directly to Pattern Manager, Topology Service, or any service's data store.** The
  Simulator is a read-only consumer of those services' APIs in P3 mode; it never mutates their
  state.
- **Reading non-approved patterns in P3 synthesis.** Only lifecycle=approved patterns from Pattern
  Manager are used for pattern-aligned cascade synthesis (see Open Questions for whether
  non-approved patterns should be included in any future mode).
- **Deciding the auto-correlation target on behalf of the Correlation Engine.** The
  `P3_AUTO_CORRELATION_TARGET` is the simulator's target for what fraction of emitted alarms
  it expects to land in correlated incidents; the Correlation Engine independently determines
  what it actually correlates. The Simulator does not force or override CE behavior — it
  controls only how many and where cascades are emitted.
- **Querying NebulaGraph directly for compatible-trail discovery.** Compatible-trail enumeration
  uses only the Trail Builder's published REST API (`GET /trails?snapshotId&domain` and
  `GET /trails/{id}`); the Simulator has no NebulaGraph credentials or direct graph access.

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

### P3 topology-and-pattern-driven synthesis tasks (additive)

13. **Fetch and validate the deployed topology + trail + approved-pattern state.** In P3 synthesis
    mode, read the current topology snapshot list from the Topology Service (`GET /topology/snapshots`),
    fetch trail members for each trail referenced by an approved pattern from the Trail Builder
    (`GET /trails/{trailId}`), and fetch all approved patterns from the Pattern Manager
    (`GET /patterns?lifecycle=approved`). Each integration point is config-switchable (mock/real).
    Fail fast (before any alarm emission) if the deployed state is empty or inconsistent (no approved
    patterns, no matching trail for a pattern's `trailId`).

14. **Persist the fetched P3 config snapshot for reuse.** After a successful fetch, persist the
    topology snapshot list, trail members, and approved patterns as a reusable **P3 config snapshot**
    (the persistence mechanism is a design decision). A subsequent P3 run can load from the persisted
    snapshot without re-fetching, so repeated randomized alarm runs are isolated from service
    availability and the (topology, approved-pattern) config is reusable across runs.

15. **Synthesize pattern-aligned cascades on real trails.** For each approved pattern, construct one
    or more alarm cascades: map each `sequence[i].alarmType` onto a real `managedObjectId` drawn from
    the pattern's trail's `members[]` (using a placement rule described in Open Questions OQ-P3-1),
    mark the alarm matching the pattern's `rootCauseAlarmType` as the root-cause alarm, and record the
    cascade's ground-truth label `{patternId, trailId, rootCauseAlarmId, rootCauseAlarmType,
    childAlarmIds, scenarioType="pattern-aligned"}`. The number of cascade instances per pattern is
    configurable. Optional sequence elements (`optional=true`) may be emitted or omitted per the
    configured strategy.

16. **Synthesize the non-aligned remainder.** Generate the remaining ~30-40% of the alarm volume as
    realistic non-aligned alarms on real `managedObjectId` values drawn from the deployed topology:
    partial cascades (sequences that do not fully match any approved pattern), single-object alarms
    that do not participate in any approved-pattern trail, and noise. Non-aligned alarms carry
    ground-truth labels `{scenarioType="non-aligned"|"partial-cascade"|"noise"}` so they are excluded
    from the aligned-fraction count. All `managedObjectId` values must exist in the deployed topology
    snapshot.

17. **Emit the synthesized P3 stream wall-clock-paced on `alarms.live`.** Emit the interleaved
    pattern-aligned and non-aligned alarms to `alarms.live` (frozen `AlarmEvent` payload;
    wall-clock-paced using the pattern's `timing.medianInterArrivalMs` /
    `timing.maxInterArrivalMs` / `sessionWindow.windowMs` for aligned cascades; configurable pacing
    for non-aligned alarms). Every emitted `AlarmEvent` carries a valid canonical `alarmType` token
    (from the Knowledge `alarmTypeVocabulary`) and a `managedObjectId` present in the deployed
    topology snapshot. No new topic; no event-model change.

18. **Expose P3 ground-truth labels for oracle evaluation.** Persist and make retrievable the P3
    cascade ground-truth labels (same label-retrieval surface as existing generate/ingest modes)
    including `{patternId, trailId, rootCauseAlarmId, rootCauseAlarmType, childAlarmIds,
    scenarioType}` per cascade, and the overall synthesized-run metadata
    `{totalAlarms, alignedAlarms, nonAlignedAlarms, alignedFraction}` so the 60-70% KPI is
    directly computable.

19. **Support seeded and fresh randomization.** Accept a configurable RNG seed (`P3_RNG_SEED` env /
    `--p3-rng-seed` CLI). With a seed present and the same persisted P3 config snapshot, a P3 run
    produces the same alarm ordering, `managedObjectId` placements, and timing; with no seed it
    produces a fresh randomization. The seed has no effect on the persisted P3 config snapshot itself.

20. **Surface P3 synthesis in the CLI.** Extend the CLI with a `synth` subcommand (or equivalent
    mode flag) for P3 topology-and-pattern-driven synthesis, with options for: load-from-persisted-
    snapshot vs. re-fetch, `--p3-rng-seed`, `--p3-aligned-fraction`, `--p3-total-alarms`, and
    `--p3-config-snapshot-path`. Env-var equivalents for all options. `--help` documents standalone
    vs. full-cycle use.

### P3 network-wide emission and closed-loop target tasks (additive)

21. **Enumerate and cache compatible trails for each approved pattern.** For each approved pattern
    in the P3 config snapshot, call Trail Builder `GET /trails?snapshotId&domain&limit&offset`
    (the existing published endpoint, confirmed present in Trail Builder's published OpenAPI —
    no contract change) to obtain the full trail list, then `GET /trails/{trailId}` for each
    candidate (reusing cached results for already-fetched trails), and apply the hostability rule
    (a trail hosts pattern P if it contains at least one member of each objectType required by
    P's sequence, root objectType included) to produce each pattern's **compatible-trail set**.
    Prefer trails in igp-areas different from the pattern's discovery trail's area; fall back to
    same-area distribution if all compatible trails share one area (log accordingly). Store the
    compatible-trail sets in the P3 config snapshot for reuse across runs. The Trail Builder
    integration point is the same config-switchable (mock/real) client as Task 13.

22. **Compute cascade count and distribution plan to hit the auto-correlation target.** Given
    `P3_AUTO_CORRELATION_TARGET`, `P3_TOTAL_ALARMS`, `P3_TARGET_TOLERANCE`, and
    `P3_MAX_CASCADES_PER_TRAIL`, compute: (a) the number of complete aligned cascades needed
    so that `(cascade_count × avg_cascade_length) / P3_TOTAL_ALARMS` equals the target within
    tolerance; (b) a distribution plan that spreads those cascades across the discovered
    compatible trails — first maximizing distinct igp-areas, then filling up to
    `P3_MAX_CASCADES_PER_TRAIL` per trail. If total distinct-trail capacity is insufficient,
    plan trail repeats (staggered). If the target is genuinely unreachable, compute the
    maximum achievable count and record the projected shortfall in the run metadata.

23. **Emit network-wide aligned cascades per the distribution plan.** For each planned
    (pattern, trail, instance) triple in the distribution plan: select real `managedObjectId`
    members from that trail per the placement rule (OQ-P3-1), synthesize the cascade wall-clock-
    paced within the pattern's `sessionWindow`, and emit on `alarms.live` (existing `AlarmEvent`
    payload). Each cascade on each trail becomes its own independently-correlatable incident.
    Record ground-truth labels per cascade instance: `{patternId, trailId, instanceIndex,
    rootCauseAlarmId, rootCauseAlarmType, childAlarmIds, scenarioType="pattern-aligned",
    igpArea}`. Log a structured warning (never a silent under-delivery) when the realized
    cascade count falls short of the distribution plan due to shortfall. On run completion,
    persist the per-run summary: `{totalAlarms, alignedAlarms, alignedFraction,
    distinctTrailsUsed, distinctAreasUsed, shortfallCascades, enrichmentSafeCount,
    enrichmentConflictPatterns}`.

24. **Enforce enrichment-safe constraints on every aligned cascade.** Before emitting a
    pattern-aligned cascade, validate it against enrichment's processing rules.
    NOTE: Constraints (b) and (c) below were corrected during live validation (P3 network-wide
    build #392); the shipped build already implements the corrected behavior. The enrichment-
    safety invariant is: distinct dedup keys + sustained single-raise + no genuine same-key
    duplicate. It is NOT an alarmType blocklist and NOT a dedup-window spacing floor.
    (a) ensure every cascade element targets a distinct `managedObjectId` and occupies a
    distinct `alarmType` sequence position — enrichment's DedupStep key is
    `(path, source, managedObjectId, eventType, alarmType, state)` and only collapses repeated
    identical alarms; distinct-key elements are never collapsed regardless of timing;
    (b) ensure every aligned cascade member is emitted as a **sustained single `raised` event**
    with NO corresponding `cleared` emitted — enrichment's SelfClearStep suppresses a raise
    only when a matching CLEAR arrives within hold-time; a sustained raise without a clear
    passes through regardless of alarmType; there is NO alarmType blocklist for cascade members;
    (c) ensure inter-arrival timing stays **within the pattern's `sessionWindow.windowMs`** so
    CE still matches the sequence — no minimum floor above the enrichment dedup window is
    required or enforced for distinct-key cascades; if a pattern's `sessionWindow` is too short
    to fit natural timing, exclude that pattern from aligned synthesis, record it in
    `enrichmentConflictPatterns`, and log a structured warning;
    (d) ensure no cascade member triggers flap-damping (no repeated raise/clear sequence on
    the same object within the cascade window).
    The non-aligned and noise portions of the stream are NOT subject to these constraints
    (realistic transients and dups there are correct behavior). Record the count of
    enrichment-safe aligned cascade alarms as `enrichmentSafeCount` in the per-run summary;
    this count is the expected-correlatable count measurable against CE `/stats`.

## Phase applicability

The Simulator is **Active in all three runtime phases** and serves as the **evaluation oracle
throughout**: the ground-truth `{rootCause, children}` labels it persists, and the integration
thresholds it owns (see "Integration thresholds" section), are what the integration test
harness asserts across the learning and real-time phases.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Generates the domain-grounded topology snapshot file and uploads it to the Topology Service ingestion API, establishing the graph that all subsequent phases depend on | Active | Output: topology snapshot file (versioned JSON contract) → Topology ingestion API (HTTP upload, config-switchable mock/real) |
| P2 — Pattern learning | Replays the labeled historical alarm corpus (batch) onto `alarms.history`, feeding the enrichment → noise-filter → pattern-miner learning pipeline; ground-truth labels serve as the oracle for pattern-quality evaluation | Active | Output: `alarms.history` (`AlarmEvent` payloads, batch) |
| P3 — Real-time correlation | Two operating sub-modes: (a) **existing** — replays the labeled live alarm stream (wall-clock paced) onto `alarms.live` from a generated or ingested corpus; (b) **P3 synthesis (additive)** — reads deployed topology + trail + approved-pattern state from Topology, Trail Builder, and Pattern Manager APIs; synthesizes a live alarm stream grounded in those real objects/patterns; emits wall-clock-paced to `alarms.live`; persists and exposes ground-truth labels. Both sub-modes serve as the evaluation oracle for the RCA accuracy + alarm-reduction KPIs. | Active | Inputs (synthesis sub-mode): Topology `GET /topology/snapshots`; Trail Builder `GET /trails/{trailId}`; Pattern Manager `GET /patterns?lifecycle=approved`; optionally: persisted P3 config snapshot file. Output: `alarms.live` (`AlarmEvent` payloads, wall-clock paced); ground-truth label store. |

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
  - **[P3 synthesis] Topology Service — snapshot listing:** `GET /topology/snapshots` to read
    the current snapshot(s) and their `snapshotId`. Client built against Topology's published
    OpenAPI 3.1 spec. Config-switchable: mock (from Topology's OpenAPI) for unit tests; real
    Topology Service for integration. Base URL: `TOPOLOGY_API_BASE_URL`; mode: `TOPOLOGY_API_MODE`.
  - **[P3 synthesis] Trail Builder — trail member lookup:** `GET /trails/{trailId}` to fetch
    the `members[]` of `{managedObjectId, objectType}` for each trail referenced by an approved
    pattern. Client built against Trail Builder's published OpenAPI 3.1 spec. Config-switchable:
    mock (from Trail Builder's OpenAPI) for unit tests; real Trail Builder for integration.
    Base URL: `TRAIL_BUILDER_API_BASE_URL`; mode: `TRAIL_BUILDER_API_MODE`.
  - **[P3 synthesis] Pattern Manager — approved pattern listing:** `GET /patterns?lifecycle=approved`
    to fetch all approved `PatternView` objects with `trailId`, `sequence[]`, `rootCauseAlarmType`,
    `timing`, `sessionWindow`, `snapshotId`. Client built against Pattern Manager's published
    OpenAPI 3.1 spec. Config-switchable: mock (from Pattern Manager's OpenAPI) for unit tests;
    real Pattern Manager for integration. Base URL: `PATTERN_MANAGER_API_BASE_URL`; mode:
    `PATTERN_MANAGER_API_MODE`.
- **Data owned:**
  - Ground-truth scenario labels (`{rootCause, rootCauseManagedObjectId, rootCauseAlarmType,
    children}` per injected scenario) — persisted in a store or file; medium is a design decision.
  - Scenario definitions (loaded from local config files or Knowledge Service at startup/run
    time; the Simulator does not author them).
  - Exported **alarm corpus file** (the ordered emitted `AlarmEvent` stream + topic, JSONL) when
    export is enabled — the Simulator-owned, versioned, re-ingestible dataset artifact.
  - **[P3 synthesis] P3 config snapshot** — the persisted (topology snapshot list, trail members,
    approved patterns) fetched from the deployed services, stored in a Simulator-owned artifact
    (mechanism is a design decision). This snapshot is the input to repeated P3 synthesis runs; it
    is not a Kafka artifact and is not shared with other services. Reusable: a captured
    (topology, approved-pattern-set) config can drive multiple P3 runs.
  - **[P3 synthesis] P3 ground-truth cascade labels** — per-cascade labels
    `{patternId, trailId, rootCauseAlarmId, rootCauseAlarmType, childAlarmIds, scenarioType}` and
    per-run metadata `{totalAlarms, alignedAlarms, nonAlignedAlarms, alignedFraction}`, retrievable
    via the existing label-retrieval surface.
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
  required config is a fatal error reported via structured log before exit. **P3 synthesis
  adds:** `TOPOLOGY_API_BASE_URL` + `TOPOLOGY_API_MODE`, `TRAIL_BUILDER_API_BASE_URL` +
  `TRAIL_BUILDER_API_MODE`, `PATTERN_MANAGER_API_BASE_URL` + `PATTERN_MANAGER_API_MODE`,
  `P3_ALIGNED_FRACTION` (default 0.65, range 0.0-1.0), `P3_TOTAL_ALARMS`, `P3_RNG_SEED`
  (optional; absent = fresh randomization), `P3_CONFIG_SNAPSHOT_PATH` (path to persisted P3
  config snapshot; if absent, re-fetches from services). All P3 config items are env/CLI
  overridable; no hard-coded defaults for URLs or fractions. **Network-wide target adds:**
  `P3_AUTO_CORRELATION_TARGET` (float 0.0-1.0; default unset = single-trail behavior unchanged;
  the target is the CE-measured post-enrichment fraction `correlatedAlarmCount /
  totalAlarmsProcessed`),
  `P3_TARGET_TOLERANCE` (float; default 0.03 = ±3 percentage points),
  `P3_MAX_CASCADES_PER_TRAIL` (int; default 3),
  `P3_ENRICHMENT_OVER_PROVISION_MARGIN` (float 0.0-1.0; default 0.0 = no over-provision;
  when non-zero the emitted aligned fraction is `TARGET / (1 − margin)` to compensate for
  enrichment's legitimate reduction on the live path),
  `P3_ENRICHMENT_DEDUP_WINDOW_MS` (int milliseconds; default defined in design; drives the
  inter-arrival spacing lower bound for enrichment-safe cascade synthesis), and
  `P3_NETWORK_WIDE` (bool; when false or unset, network-wide mode is disabled and existing
  single-trail P3 behavior is used). All network-wide config items are env/CLI overridable;
  no hard-coded thresholds.
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
| Auto-correlation target hit rate | Fraction of network-wide P3 runs (over repeated executions with fresh seeds) where the CE-measured post-enrichment `correlatedAlarmCount / totalAlarmsProcessed` is within `P3_TARGET_TOLERANCE` (default ±3 pp) of `P3_AUTO_CORRELATION_TARGET` | **= 1.0 (every run hits target within tolerance, or logs a measurable shortfall)** |
| Network-wide spatial spread | Distinct `trailId`s used per approved pattern across a single network-wide P3 run | **≥ 2 distinct trails per pattern (where compatible trails exist in ≥ 2 igp-areas)** |

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

21. **Pack ships 9 grounded scenarios with 7 distinct root-cause `alarmType` tokens, each scenario spanning 10-20 distinct alarm types.**
    The Core IP pack's scenario library contains **9 distinct grounded scenarios** (`fiber-cut`,
    `line-card-fault`, `port-fault`, `interface-fault`, `node-failure`, `ip-link-failure`,
    `lsp-te-failure`, `routing-adjacency-failure`, `srlg-shared-risk-failure`), one per Knowledge
    `faultOriginType` plus SRLG co-failure / line-card fan-out. For each scenario, a single injected
    instance's symptom set spans **at least 10 and at most ~24 distinct canonical `alarmType`
    tokens** (drawn from the 29-token Knowledge `alarmTypeVocabulary`), and each is injected with
    multiple instances (`SCENARIO_INSTANCES`). The 9 scenarios produce **7 distinct root-cause
    `alarmType` tokens**: two pairs of scenarios deliberately share a root token to model realistic
    variant behavior — `srlg-shared-risk-failure` is a `FiberSpan`-origin variant of `fiber-cut`
    (both root on `FiberFault`), and `routing-adjacency-failure` is an `Interface`-origin
    routing-emphasis variant of `interface-fault` (both root on `InterfaceDown`). These variants are
    intentional: SRLG co-failure and routing-reconvergence represent distinct fault patterns (different
    propagation topology, different child alarm sets) that realistically share an origin signal; the
    scenario identity (`scenarioType`) and cascade structure — not the root `alarmType` alone —
    distinguish them for the pattern-miner and correlation-engine. A test asserting this grouping
    (`test_ac21_root_alarmtype_token_grouping`) must confirm: `pack.scenario_library()` returns the
    9 named scenarios; calling each once over the layered fixture topology yields exactly 7 distinct
    `rootCauseAlarmType` values; `srlg-shared-risk-failure` and `fiber-cut` share `FiberFault`; and
    `routing-adjacency-failure` and `interface-fault` share `InterfaceDown`.

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

### P3 topology-and-pattern-driven synthesis acceptance criteria

Each criterion maps to a single pytest test.

32. **P3 synthesis mode reads approved patterns from Pattern Manager and fails fast if none.**
    When P3 synthesis mode is selected and `PATTERN_MANAGER_API_MODE=mock`, the Simulator fetches
    patterns from the mock stub (generated from Pattern Manager's published OpenAPI); the mock
    returns a non-empty `PatternView[]` list and the run proceeds. When the mock returns an empty
    list the Simulator logs a structured error and exits with a non-zero code before emitting any
    alarm.

33. **P3 synthesis mode reads trail members from Trail Builder for each pattern's `trailId`.**
    Given a P3 config snapshot with 3 approved patterns referencing 2 distinct `trailId` values,
    the Simulator issues exactly 2 calls to `GET /trails/{trailId}` (deduplication on `trailId`),
    and the returned `members[]` are stored in the P3 config snapshot. When the Trail Builder
    returns HTTP 404 for a `trailId`, the Simulator logs a structured warning and excludes that
    pattern from synthesis (it does not abort the run if other patterns are available).

34. **P3 config snapshot is persisted and a subsequent run loads from it without re-fetching.**
    After a P3 run that fetches from live services, the persisted P3 config snapshot is present
    at the configured path (`P3_CONFIG_SNAPSHOT_PATH`). A second P3 run with
    `P3_CONFIG_SNAPSHOT_PATH` pointing to the persisted file makes zero calls to the Topology,
    Trail Builder, or Pattern Manager APIs (verifiable via the mock call count) and produces an
    alarm stream of the configured volume.

35. **P3 synthesized alarms carry valid `managedObjectId` values from the deployed topology.**
    Every `AlarmEvent` emitted in a P3 synthesis run carries a `managedObjectId` that is present
    in the topology snapshot stored in the P3 config snapshot. No P3-emitted alarm references an
    object absent from the topology snapshot.

36. **Pattern-aligned cascades follow the pattern's sequence and timing.**
    Given an approved pattern with `sequence=[{alarmType:"IPLinkDown"},{alarmType:"ISISAdjacencyDown"}]`,
    `timing.medianInterArrivalMs=500`, and a trail with members including an `IPLink` object and
    an `IGPAdjacency` object, the Simulator emits a cascade of two alarms: the first with
    `alarmType="IPLinkDown"` on the `IPLink` member and the second with
    `alarmType="ISISAdjacencyDown"` on the `IGPAdjacency` member. The inter-arrival time between
    the two alarms is in the range `[0, timing.maxInterArrivalMs]` (and near
    `timing.medianInterArrivalMs` in expectation). The cascade ground-truth label records the
    alarm with `alarmType` matching the pattern's `rootCauseAlarmType` as root cause and the
    other(s) as children.

37. **Root-cause alarm in a pattern-aligned cascade matches the pattern's `rootCauseAlarmType`.**
    For every synthesized pattern-aligned cascade, the ground-truth label's `rootCauseAlarmType`
    equals the approved pattern's `rootCauseAlarmType`. The emitted `AlarmEvent` for the root-cause
    alarm carries that same `alarmType` value and the `managedObjectId` of a trail member of the
    appropriate `objectType` (placement rule applied — see OQ-P3-1).

38. **Configured aligned fraction is honored within tolerance.**
    Given `P3_ALIGNED_FRACTION=0.65` and `P3_TOTAL_ALARMS=200`, a P3 synthesis run emits between
    120 and 145 pattern-aligned alarms (65% ± a tolerance of 5 percentage points) and the
    remainder are non-aligned/noise. The per-run metadata label `alignedFraction` is within the
    same tolerance. The aligned fraction is configurable: a run with `P3_ALIGNED_FRACTION=0.0`
    emits zero pattern-aligned alarms; a run with `P3_ALIGNED_FRACTION=1.0` emits no non-aligned
    alarms (subject to available approved patterns).

39. **P3 non-aligned alarms also carry valid `managedObjectId` values and canonical `alarmType` tokens.**
    Every non-aligned `AlarmEvent` emitted in a P3 synthesis run carries a `managedObjectId`
    present in the P3 config snapshot topology, and an `alarmType` that is a non-empty token from
    the Knowledge `alarmTypeVocabulary`. No fabricated object identities.

40. **P3 stream is emitted on `alarms.live` only.**
    In a P3 synthesis run, all synthesized alarms are produced to `alarms.live` and zero alarms
    are produced to `alarms.history`. Every emitted `AlarmEvent` passes validation against the
    frozen `libs/event-model` Python/Pydantic binding (all required fields present, including
    `alarmType`).

41. **Seeded P3 run is reproducible; unseeded run produces a fresh randomization.**
    Two P3 synthesis runs with the same `P3_RNG_SEED` value and the same persisted P3 config
    snapshot produce alarm streams that are identical in `alarmId`, `alarmType`, `managedObjectId`,
    `raisedAt`, and ordering. Two runs without a seed (or with distinct seeds) produce different
    `alarmId` values and a different ordering with high probability (verified by comparing the
    first 10 alarms' `alarmId`s across runs).

42. **P3 synthesis runs standalone without a prior P1/P2 step.**
    Given a pre-populated persisted P3 config snapshot (with topology, trail members, and approved
    patterns), a P3 synthesis run completes successfully with no call to the Topology ingestion
    API (`POST /topology/snapshots`) and no dependency on a previously generated alarm corpus —
    the run produces a complete alarm stream and ground-truth labels from the persisted config
    alone.

43. **P3 ground-truth labels are retrievable and the 60-70% KPI is directly computable.**
    After a P3 synthesis run, the ground-truth label store contains: one record per synthesized
    cascade with `{patternId, trailId, rootCauseAlarmId, rootCauseAlarmType, childAlarmIds,
    scenarioType}`, and one per-run summary with `{totalAlarms, alignedAlarms, nonAlignedAlarms,
    alignedFraction}`. The `alignedFraction` in the summary equals
    `alignedAlarms / totalAlarms` computed from the per-cascade records. These are retrievable via
    the existing label-retrieval surface without additional configuration.

44. **P3 integration points are all config-switchable (mock vs. real).**
    With `TOPOLOGY_API_MODE=mock`, `TRAIL_BUILDER_API_MODE=mock`, and
    `PATTERN_MANAGER_API_MODE=mock`, the P3 synthesis mode completes a full fetch-and-synthesize
    cycle using only mock stubs (generated from each collaborator's published OpenAPI spec) and
    makes zero calls to live services. Switching any mode to `real` (with a valid base URL)
    requires no code change.

45. **P3 synthesis is backward compatible: existing modes are unaffected.**
    A run in `generate` mode, `ingest` mode, or `export` mode with no P3 synthesis options set
    produces identical behavior to the pre-P3-spec behavior: the P3 integration clients are not
    instantiated and no P3-related API calls are made. The existing P1/P2 acceptance criteria
    (AC 1-31) pass unchanged.

46. **P3 CLI exposes synthesis options; missing required P3 config fails fast.**
    `--help` documents the P3 synthesis subcommand/mode with all P3 options
    (`--p3-aligned-fraction`, `--p3-total-alarms`, `--p3-rng-seed`,
    `--p3-config-snapshot-path`). Starting P3 synthesis without a Pattern Manager URL configured
    (and `PATTERN_MANAGER_API_MODE=real`) produces a structured JSON config-error log and exits
    with a non-zero code before emitting any alarm.

### P3 network-wide emission and closed-loop target acceptance criteria (additive)

Each criterion maps to a single pytest test.

47. **Compatible-trail discovery applies the hostability rule correctly via the existing Trail Builder list endpoint.**
    The Simulator enumerates the full trail list via Trail Builder `GET /trails?snapshotId&domain&limit&offset`
    (the existing published endpoint — no contract change required). Given a P3 config snapshot
    containing one approved pattern with `sequence` requiring `objectType`s `IPLink` and
    `IGPAdjacency` (root type `IPLink`), and a trail list that includes Trail A (members:
    `IPLink:1`, `IGPAdjacency:2`, `Interface:3`) and Trail B (members: `Interface:4`, `Node:5`),
    the compatible-trail filter returns Trail A and excludes Trail B (Trail B lacks an `IPLink`
    member). The discovery trail itself is included in the compatible set if it passes the
    hostability rule.

48. **Compatible-trail sets are cached in the P3 config snapshot and not re-fetched on a second run.**
    After a network-wide P3 run that fetches trails, the persisted P3 config snapshot includes the
    compatible-trail sets for each pattern. A second P3 run loading from that snapshot makes zero
    calls to `GET /trails?snapshotId&domain` or `GET /trails/{id}` for already-cached patterns
    (verifiable via the mock call count).

49. **Cascades are distributed across multiple compatible trails, preferring distinct igp-areas.**
    Given a pattern with 3 compatible trails in 3 distinct igp-areas, and a distribution plan
    requiring 3 cascade instances, the Simulator assigns one cascade instance to each trail (one
    per area). No single trail receives more cascades than `P3_MAX_CASCADES_PER_TRAIL` when
    sufficient distinct trails exist.

50. **Each cascade instance on a different trail uses real member objects from THAT trail.**
    In a network-wide P3 run with cascade instances on Trail A and Trail B, the `managedObjectId`
    values in Trail A's cascade are members of Trail A (not Trail B), and vice versa. No cascade
    instance references an object absent from its assigned trail's `members[]`.

51. **Closed-loop controller targets CE-measured post-enrichment correlation rate within `P3_TARGET_TOLERANCE`.**
    `P3_AUTO_CORRELATION_TARGET=0.6` means the target is the CE-measured fraction
    `correlatedAlarmCount / totalAlarmsProcessed` (post-enrichment ingestion), NOT the raw
    emitted fraction. Given `P3_AUTO_CORRELATION_TARGET=0.6`, `P3_TOTAL_ALARMS=300`,
    `P3_TARGET_TOLERANCE=0.03` (default ±3 pp), `P3_ENRICHMENT_OVER_PROVISION_MARGIN=0.0`,
    and sufficient compatible trails, the Simulator emits a stream of enrichment-safe aligned
    cascades sized so the expected CE-measured rate is in [0.57, 0.63]. The per-run summary
    records `alignedFraction` (emitted) and `enrichmentSafeCount` (the expected-correlatable
    count); an integration assertion cross-checks `enrichmentSafeCount / P3_TOTAL_ALARMS`
    against CE `/stats` `correlatedAlarmCount / totalAlarmsProcessed` within the tolerance.
    When `P3_ENRICHMENT_OVER_PROVISION_MARGIN` is set to a non-zero value (e.g. 0.1), the
    controller emits `TARGET / (1 − margin)` aligned alarms to compensate, and the per-run
    summary records the over-provisioned emitted fraction separately.

52. **Closed-loop controller recalculates correctly for different target values.**
    Two runs with the same `P3_TOTAL_ALARMS` but `P3_AUTO_CORRELATION_TARGET=0.4` and
    `P3_AUTO_CORRELATION_TARGET=0.8` produce `alignedFraction` values that are within
    `P3_TARGET_TOLERANCE` of 0.4 and 0.8 respectively. The higher target produces more
    aligned cascades in total.

53. **Network-wide P3 run produces incidents on multiple distinct trails per pattern (verifiable via labels).**
    After a network-wide P3 run with at least 2 compatible trails per pattern, the ground-truth
    label store contains at least 2 cascade records for one pattern, each with a distinct
    `trailId`. The `distinctTrailsUsed` field in the per-run summary is ≥ 2.

54. **Network-wide P3 ground-truth labels include `igpArea` and `instanceIndex` per cascade.**
    Every cascade-level ground-truth label in a network-wide run includes the fields
    `{patternId, trailId, instanceIndex, rootCauseAlarmId, rootCauseAlarmType, childAlarmIds,
    scenarioType, igpArea}`. Two cascade records for the same pattern on different trails carry
    different `trailId` and `igpArea` values (when distinct areas are available).

55. **Shortfall is logged clearly when the target cannot be fully achieved.**
    Given `P3_AUTO_CORRELATION_TARGET=0.9`, `P3_TOTAL_ALARMS=1000`, and only 2 compatible
    trails with `P3_MAX_CASCADES_PER_TRAIL=1` for all patterns (making the target unreachable),
    the Simulator: (a) emits the maximum achievable aligned cascades without exceeding any cap;
    (b) logs at least one structured warning with `shortfallCascades > 0`; (c) does NOT exit
    with a non-zero code (shortfall is warned, not fatal); (d) records `shortfallCascades`
    in the per-run summary metadata.

56. **Trail repetition with staggered timing is used when compatible-trail capacity is exhausted.**
    Given a distribution plan requiring more cascade instances than the number of distinct
    compatible trails × `P3_MAX_CASCADES_PER_TRAIL`, the controller assigns repeat visits to
    already-used trails. Each repeat cascade's emission timing is staggered (offset from prior
    cascades on that trail by at least `sessionWindow.windowMs`) so CE treats each as a distinct
    incident. The ground-truth labels record `instanceIndex` ≥ 2 for repeated trails.

57. **Seeded network-wide run is reproducible; different seeds produce different trail selections.**
    Two network-wide P3 runs with the same `P3_RNG_SEED` and same P3 config snapshot produce
    identical cascade assignments (same `{patternId, trailId, instanceIndex}` triples in the
    same order). Two runs with different seeds (or no seed) produce different trail orderings
    with high probability (verified by comparing the first 5 `trailId` assignments across runs).

58. **Network-wide mode disabled: existing single-trail P3 behavior is unchanged.**
    A P3 synthesis run with `P3_NETWORK_WIDE=false` (or `P3_AUTO_CORRELATION_TARGET` unset)
    emits each approved pattern's cascade on its single discovery trail only — identical behavior
    to the pre-network-wide spec. The compatible-trail enumeration (Task 21) is not performed,
    and no calls to `GET /trails?snapshotId&domain&limit&offset` are made beyond those already
    required by the existing P3 synthesis (Task 13).

### Enrichment-safe cascade acceptance criteria (additive, required for P3 network-wide)

Each criterion maps to a single pytest test.

59. **Every aligned cascade element targets a distinct managedObjectId/alarmType pair within the enrichment dedup window.**
    Given a synthesized aligned cascade for any approved pattern, no two consecutive cascade
    elements share the same `managedObjectId` AND `alarmType` within the time span of
    `P3_ENRICHMENT_DEDUP_WINDOW_MS`. Each cascade element is assigned to a **distinct
    managed object** (a distinct trail member), so rapid duplicate raises on the same object
    are structurally impossible by construction. A unit test over 100 synthesized cascades
    confirms zero same-object/same-type pairs within the dedup window across all elements.

60. **Aligned cascade members are emitted as sustained single `raised` events — enrichment self-clear and flap safety derives from this, not from an alarmType blocklist.**
    NOTE: This criterion corrects a premise found wrong during live validation (P3 network-wide
    build #392). The original premise — that no transient/self-clearing `alarmType` may be a
    cascade member — conflated alarmType identity with per-instance raise/clear behavior.
    The shipped build already implements the correct behavior described here.

    Transience is a per-instance property (a raise+clear pair arriving within enrichment's
    hold-time), NOT an alarmType property. An alarmType used as a self-clearing noise alarm
    elsewhere is still valid as a sustained cascade member. Enrichment's SelfClearStep
    suppresses a raise only when a matching CLEAR arrives within the hold-time; a sustained
    raise with no paired clear passes through regardless of alarmType.

    The invariant is: aligned cascade elements are emitted as **sustained single `raised` events**
    — the Simulator emits NO `cleared` event for any cascade member. This makes every cascade
    member self-clear-safe and flap-safe by construction, independent of alarmType.
    There is no alarmType blocklist for cascade membership.

    A unit test asserts that, for every synthesized cascade across all approved patterns,
    every cascade element's `state` is `raised` and no corresponding `cleared` event is emitted
    for any `(managedObjectId, alarmType)` cascade pair within the cascade window. The test
    must NOT assert that cascade member `alarmType` values are absent from any transient-type
    set — that is the corrected (invalid) premise.

    The non-aligned/noise portion of the stream MAY still emit self-clearing raise+clear pairs
    — those are intended noise behavior.

61. **Cascade elements have distinct dedup keys — no minimum inter-arrival floor above the dedup window is required.**
    NOTE: This criterion corrects a premise (AC61b) found wrong during live validation (P3
    network-wide build #392). The original premise — that cascade element inter-arrival must
    exceed the enrichment dedup window to avoid deduplication — was wrong for distinct-key
    cascades. The shipped build already implements the correct behavior described here.

    Enrichment's DedupStep key is `(path, source, managedObjectId, eventType, alarmType, state)`.
    It only collapses REPEATED IDENTICAL alarms — same object AND same alarmType AND same state
    within the dedup window. Aligned cascade elements have DISTINCT dedup keys by construction:
    each element targets a distinct `managedObjectId` (enforced by AC59) AND a distinct
    `alarmType` per sequence position. Therefore enrichment never dedups them, regardless of
    inter-arrival spacing.

    The enrichment-safety invariant for spacing is: cascade elements use their **natural pattern
    timing** within the `sessionWindow.windowMs` (so CE still matches the sequence). No minimum
    inter-arrival floor above the dedup window is required or enforced.
    The ONLY spacing concern that would cause deduplication is a genuine repeated
    `(managedObjectId, alarmType, state)` — which distinct-object placement (AC59) already
    prevents by construction.

    (a) A unit test generates 50 cascades and confirms every inter-arrival gap is within
    `sessionWindow.windowMs` (CE session-window bound is honored).
    (b) The test must NOT assert a lower bound of `P3_ENRICHMENT_DEDUP_WINDOW_MS` on
    inter-arrival gaps — that is the corrected (invalid) premise for distinct-key cascades.

62. **Patterns whose sessionWindow is too short for natural cascade timing are excluded and logged.**
    NOTE: This criterion is updated for consistency with the corrected AC61 premise. The
    original conflict condition (sessionWindow <= dedup window) was derived from the now-
    corrected premise that inter-arrival must exceed the dedup window. For distinct-key
    cascades, the dedup window is irrelevant; the only genuine conflict is when a pattern's
    `sessionWindow.windowMs` is too short to accommodate the cascade's natural timing (e.g.
    shorter than the minimum physically-achievable inter-arrival for the pattern's sequence
    length and throughput constraints).

    Given a pattern whose `sessionWindow.windowMs` is too short to fit even the minimum
    natural inter-arrival timing for its sequence (making it impossible to emit all cascade
    elements as distinct, ordered events within the window), the Simulator:
    (a) excludes that pattern from aligned synthesis; (b) logs a structured warning
    identifying the pattern by `patternId` and the conflicting bounds; (c) records the
    pattern's `patternId` in `enrichmentConflictPatterns` in the per-run summary;
    (d) does NOT abort the run if other patterns are conflict-free.
    A unit test confirms that a pattern with a `sessionWindow.windowMs` of 0 ms (or 1 ms)
    is always excluded; a pattern with a `sessionWindow.windowMs` of 30000 ms proceeds.

63. **No aligned cascade member triggers flap-damping.**
    Aligned cascade members do not include repeated raise/clear sequences on the same object
    within the pattern's `sessionWindow`. A unit test confirms that, for every synthesized
    cascade, no `managedObjectId`/`alarmType` pair appears more than once with alternating
    `state=raised` and `state=cleared` within the cascade window.

64. **Non-aligned and noise alarms are NOT constrained to be enrichment-safe.**
    The non-aligned and noise portions of a network-wide P3 run may include realistic
    transients, duplicate raises, and flapping patterns (as those are expected not to correlate
    and are correct behavior). A unit test confirms that the synthesizer applies enrichment-safe
    constraints only to alarms labeled `scenarioType="pattern-aligned"` and not to those
    labeled `"non-aligned"`, `"partial-cascade"`, or `"noise"`.

65. **Per-run summary records enrichmentSafeCount and enrichmentConflictPatterns.**
    After any network-wide P3 run, the per-run summary metadata includes:
    `enrichmentSafeCount` (the count of aligned cascade alarms that passed all enrichment-safe
    checks — the expected-correlatable count for CE cross-check) and
    `enrichmentConflictPatterns` (list of `patternId` values excluded due to
    sessionWindow/dedup-window conflicts, which may be empty). These fields are retrievable
    via the existing label-retrieval surface. A unit test with one conflict-free and one
    conflicting pattern confirms both fields are populated correctly.

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

### P3 synthesis open questions (require human resolution before design)

- **OQ-P3-1 (BLOCKS design): `alarmType`-to-`objectType` placement rules for P3 cascade synthesis.**
  To map each `sequence[i].alarmType` onto a real `managedObjectId` from the trail's `members[]`,
  the Simulator needs a placement rule: which `objectType` in the trail's members is the valid
  target for each `alarmType` (e.g. `IPLinkDown` is raised on an `IPLink` member;
  `ISISAdjacencyDown` on an `IGPAdjacency` member; `InterfaceDown` on an `Interface` member).
  **Unresolved:** (a) Where is this mapping authored — in the Simulator's Core IP domain pack
  (generation-side, not in Knowledge), in the Knowledge Service's domain vocabulary, or derived
  from the pattern's own sequence plus the trail member list at fetch time? (b) What is the
  fallback when no member of the required `objectType` exists in the trail (e.g. the trail has no
  `IGPAdjacency`)? (c) Does the placement apply to optional sequence elements differently than
  mandatory ones? This mapping is a first-class contract input to AC-36 and AC-37; the designer
  cannot proceed without a human decision on authorship and fallback policy.

- **OQ-P3-2 (design decision, does not block spec): P3 config snapshot persistence format.**
  The spec requires that the fetched topology + trail members + approved patterns be persisted as a
  reusable P3 config snapshot. The format and storage mechanism (e.g. a single JSON file,
  multiple files, a local SQLite, a named directory) are left to the designer. The requirement is:
  the snapshot must be loadable without re-fetching, and a given (topology, approved-pattern-set)
  captured at one point in time must be reusable in later P3 runs even if the live services are
  unavailable or have changed state. The designer should confirm the format is versioned and
  validates on load (fail-fast if stale/corrupt).

- **OQ-P3-3 (design decision, does not block spec): Non-aligned alarm generation strategy.**
  The spec requires ~30-40% of the alarm stream to be realistic non-aligned alarms (partial
  cascades, single-object alarms, noise). The strategy for generating these (e.g. randomly
  sampling managed objects from the topology, applying truncated pattern sequences, or reusing
  the existing noise-class machinery from the domain pack) is a design decision. The spec
  requires only that: (a) every non-aligned alarm's `managedObjectId` exists in the topology
  snapshot; (b) non-aligned alarms carry a canonical `alarmType` token; (c) the volume is
  configurable to the `P3_ALIGNED_FRACTION` remainder; (d) each non-aligned alarm carries a
  ground-truth label (`scenarioType="non-aligned"` or `"partial-cascade"` or `"noise"`).

- **OQ-P3-4 (design decision, does not block spec): Optional sequence element handling.**
  Approved patterns have `sequence[].optional` flags. The spec requires that optional elements
  may be emitted or omitted; the strategy (always omit, always emit, randomly emit per seed,
  configurable probability) is a design decision. The chosen strategy must be consistent for a
  given seed (reproducibility) and must not cause a synthesized cascade to fail the Correlation
  Engine's session-window match when the CE's matching logic treats `optional=true` elements as
  non-mandatory.

- **OQ-P3-5 (does not block spec — flag if contract change needed): Does the Trail Builder
  `GET /trails/{trailId}` response include `objectType` per member?**
  The user confirmed (feasibility probe) that the response carries `members[]` of
  `{managedObjectId, objectType}`. The Simulator's P3 placement logic (OQ-P3-1) depends on
  `objectType` being present per member. If the live Trail Builder API does not include
  `objectType` in its `members[]` response, this is a **contract change** to Trail Builder's
  published OpenAPI — flag it as a contract-change issue per the CONVENTIONS.md procedure and
  stop. Do not design a workaround that derives `objectType` from the `managedObjectId` prefix,
  as that would couple the Simulator to the `<objectType>:<id>` encoding rather than the
  published API contract.

- **OQ-P3-6 (does not block spec): Should non-approved patterns (e.g. lifecycle=discovered) be
  usable in P3 synthesis for a "lookahead" or evaluation-only mode?**
  The current spec restricts P3 synthesis to `lifecycle=approved` patterns only. The user
  requirement says "approved patterns"; if future use cases need to synthesize against
  `discovered` (not-yet-approved) patterns for evaluation purposes, that would be a scope
  extension. Flag for human confirmation; do not implement until confirmed.

### Network-wide emission and closed-loop target open questions

- **OQ-NW-1 (RESOLVED): Exact target-to-cascade-count math.**
  DECISION: The target is the CE-measured post-enrichment fraction
  `correlatedAlarmCount / totalAlarmsProcessed`, not the raw emitted fraction. The controller
  uses per-pattern cascade counts: for each pattern,
  `cascade_count = ceil(TARGET_EMITTED_FRACTION × P3_TOTAL_ALARMS / sum_of_mandatory_lengths_across_patterns)`,
  where `TARGET_EMITTED_FRACTION = P3_AUTO_CORRELATION_TARGET / (1 − P3_ENRICHMENT_OVER_PROVISION_MARGIN)`.
  Each COMPLETE enrichment-safe aligned cascade contributes its full mandatory sequence
  length as correlatable alarms at CE (all alarms in a completed cascade are placed in the
  incident). Partial cascades count as non-aligned. Per-pattern computation aggregated to
  the total. Cascades are synthesized enrichment-safe by construction (Task 24) so the
  emitted aligned count equals the expected-correlatable count. Ground-truth labels +
  per-run summary allow the target to be verified against CE `/stats`. Implemented via
  `P3_ENRICHMENT_OVER_PROVISION_MARGIN` (default 0.0) and `P3_TARGET_TOLERANCE` (default
  ±3 pp). No further human resolution needed; design proceeds on this model.

- **OQ-NW-2 (RESOLVED): Spread policy defaults and behavior when compatible types are confined to one area.**
  DECISIONS:
  (a) `P3_MAX_CASCADES_PER_TRAIL` default = **3**.
  (b) When all compatible trails for a pattern share one igp-area, the controller falls back
  to distributing across the available same-area trails up to the per-trail cap and logs a
  structured info entry that area spread was not achievable for that pattern. This fallback
  is acceptable.
  (c) When a pattern has only 1 compatible trail, the controller still runs network-wide for
  that pattern (stacking cascades on the one trail up to the cap, staggered) and logs the
  absence of multi-trail spread. No further human resolution needed.

- **OQ-NW-3 (RESOLVED): Target basis and enrichment compensation.**
  DECISION: The target `P3_AUTO_CORRELATION_TARGET` is defined as the CE-measured
  post-enrichment fraction (`correlatedAlarmCount / totalAlarmsProcessed`). Enrichment's
  dedup, transient-suppression, and flap-damping are expected and correct on the live path;
  the Simulator compensates by (1) synthesizing aligned cascades that are enrichment-safe by
  construction (Task 24 / AC59-65) so they survive enrichment intact, and (2) offering an
  optional `P3_ENRICHMENT_OVER_PROVISION_MARGIN` for any residual gap. The default margin is
  0.0 (no over-provision). The integration assertion cross-checks
  `enrichmentSafeCount / P3_TOTAL_ALARMS` against CE `/stats` within `P3_TARGET_TOLERANCE`
  (±3 pp). Note: the separate noise-filter service is P2-only and is NOT in the P3 live path;
  enrichment's own filtering is the only deduction to account for. No further human
  resolution needed.

- **OQ-NW-4 (RESOLVED — NO contract change): Trail Builder `GET /trails?snapshotId&domain&limit&offset` endpoint.**
  CONFIRMED: Trail Builder's published OpenAPI already includes
  `GET /trails?snapshotId&domain&limit&offset` (verified against
  `services/trail-builder/openapi.json`). No contract change is needed anywhere. The
  Simulator enumerates the full trail list using this existing endpoint. Design proceeds
  on this basis.

---

## HTTP Trigger for On-Demand P3 Synth Ingestion (additive capability)

This section is additive — the existing generate/ingest/export/synth acceptance criteria
(AC 1-65) are unchanged. All topic, payload, and event-model contracts are unchanged: this
capability emits on the existing `alarms.live` topic using the frozen `AlarmEvent` payload
and introduces no new Kafka topic, no new payload type, and no event-model field. The two
new endpoints (`POST /synth/run` and `GET /synth/status`) are on the Simulator's own OpenAPI
surface (self-owned) — they are not cross-service topic or payload additions and require no
`docs/architecture.md` contract change beyond noting the new endpoint surface.

### Purpose of this capability

Today the Simulator's P3 network-wide synth run is CLI-only (`python -m simulator --phase p3`
or equivalent) and the FastAPI process exits after the run completes. The web-ui needs to
TRIGGER a P3 synth run from a dashboard button and poll its status to show a spinner while
the run is in progress. This capability makes the Simulator a **persistent long-lived service**
(the FastAPI stays up continuously serving all existing read endpoints and the new
trigger/status endpoints), and adds two new HTTP endpoints to the Simulator's own OpenAPI:

- `POST /synth/run` — start an asynchronous P3 network-wide synth batch; returns immediately
  with a `runId`.
- `GET /synth/status` — report the current run state (idle/running), progress, and the last
  run summary.

### Additional tasks (additive to Tasks 1-24)

25. **Persistent service mode.** The Simulator runs as a long-lived process: the FastAPI server
    starts and stays up continuously serving all existing endpoints (`/health`, `/metrics`,
    `/labels`, `/scenarios`) plus the new `/synth/run` and `/synth/status` endpoints, without
    exiting after a synth run completes. A synth run executes in the background; the HTTP API
    remains responsive during the run. This changes the Simulator's compose lifecycle from
    one-shot to persistent — see Open Questions OQ-TRIGGER-3.

26. **`POST /synth/run` — trigger an asynchronous P3 network-wide synth run.** Accept an HTTP
    POST at `/synth/run`. Start the existing P3 network-wide synth pipeline (Tasks 13-24:
    read approved patterns + topology + compatible trails, synthesize enrichment-safe aligned
    cascades + non-aligned/noise, emit wall-clock-paced to `alarms.live` at the configured
    auto-correlation target) asynchronously in the background. Return immediately (HTTP 202)
    with a response body containing the `runId` (a UUID identifying this run) and `status:
    "running"`. The POST body optionally accepts override values for the P3 knobs (`target`,
    `totalAlarms`, `seed`); all overrides default to the env/config values
    (`P3_AUTO_CORRELATION_TARGET`, `P3_TOTAL_ALARMS`, `P3_RNG_SEED`) when absent. Validate
    all supplied override values at request time; respond HTTP 422 for any invalid parameter
    (e.g. `target` outside [0.0, 1.0], `totalAlarms` <= 0). If a synth run is already in
    progress, reject the POST with HTTP 409 and a JSON body identifying the active `runId` —
    no queuing; see OQ-TRIGGER-1.

27. **`GET /synth/status` — report current run state and progress.** Return HTTP 200 with a
    JSON body describing: (a) the current `status` (`"idle"` or `"running"`), (b) the `runId`
    of the active run when running, or of the most recent completed run when idle (absent/null
    if no run has ever occurred), (c) a `progress` object when running: `alarmsEmitted`
    (non-negative integer), `alarmsTotal` (total planned for this run), `alignedEmitted`, and
    `nonAlignedEmitted` counts, (d) a `summary` object when the last run has completed or
    failed: `{runId, status, alarmsEmitted, alignedFraction, enrichmentSafeCount,
    shortfallCascades, enrichmentConflictPatterns, failureReason, startedAt, completedAt}`.
    The same summary fields as the per-run summary from Task 23. See OQ-TRIGGER-2 for the
    recommended full shape. This endpoint is suitable for UI polling: the client spins while
    `status == "running"` and stops when `status == "idle"`.

28. **Error propagation to status.** If a triggered run fails during background execution
    (e.g. Pattern Manager unreachable, no approved patterns available, all patterns have
    session-window conflicts), the run terminates and `/synth/status` returns `status: "idle"`
    (or `"failed"` — see OQ-TRIGGER-2) with a `summary` that includes a non-empty
    `failureReason` field. The POST `/synth/run` response returns HTTP 202 when the run is
    accepted (failure is surfaced via polling, not the trigger response), except for failures
    detectable synchronously from the POST body (invalid parameters → HTTP 422) — see
    OQ-TRIGGER-4 for the recommended sync/async failure boundary.

29. **Run parameters from config/env with optional POST body overrides.** The P3 knobs for a
    triggered run default to env/config: `P3_AUTO_CORRELATION_TARGET`, `P3_TOTAL_ALARMS`,
    `P3_RNG_SEED`, `P3_NETWORK_WIDE`, `P3_MAX_CASCADES_PER_TRAIL`, `P3_TARGET_TOLERANCE`,
    `P3_ENRICHMENT_OVER_PROVISION_MARGIN`. The POST body MAY supply override values for
    `target` (maps to `P3_AUTO_CORRELATION_TARGET`), `totalAlarms` (maps to
    `P3_TOTAL_ALARMS`), and `seed` (maps to `P3_RNG_SEED`). All other knobs come from
    env/config only (not overridable via POST body in MVP — see OQ-TRIGGER-5). An absent
    body field means "use the env/config default." The accepted body shape and field types
    are published in the Simulator's own OpenAPI spec.

30. **`POST /synth/run` and `GET /synth/status` added to the Simulator's own OpenAPI spec and
    drift-guarded.** Both endpoints are declared in `services/simulator/openapi.json` (the
    Simulator's self-owned, checked-in OpenAPI 3.1 document). The existing drift guard test
    is extended to cover `/synth/run` and `/synth/status`. The request/response shapes are
    defined in the Simulator's own OpenAPI — they are not a cross-service contract addition.
    A change to these endpoint shapes is a contract change to the Simulator's own API surface
    and follows the existing per-service contract-change procedure.

### Additional acceptance criteria (additive — HTTP trigger capability)

Each criterion maps to a single pytest test.

66. **`POST /synth/run` returns HTTP 202 with a UUID `runId` and `status: "running"` when no
    run is active.**
    Given the Simulator is running as a persistent service with no active synth run, a POST to
    `/synth/run` (with no body or a valid body) returns HTTP 202 with a JSON body containing
    `runId` (a non-empty UUID string) and `status: "running"`. The response is returned before
    the synth run completes — the endpoint does not block until emission finishes.

67. **`POST /synth/run` with an invalid parameter returns HTTP 422.**
    A POST to `/synth/run` with body `{"target": 1.5}` (outside [0.0, 1.0]) returns HTTP 422
    with a JSON body identifying the invalid field and reason. No background run is started.
    A POST with `{"totalAlarms": 0}` and a POST with `{"totalAlarms": -1}` each also return
    HTTP 422.

68. **`POST /synth/run` while a run is active returns HTTP 409.**
    Given a P3 synth run is currently in progress (started by a prior POST), a second POST to
    `/synth/run` returns HTTP 409 with a JSON body containing the active `runId`. No second
    run is started; the in-progress run continues unaffected.

69. **`GET /synth/status` returns `status: "running"` with progress counters while a run is
    active.**
    While a P3 synth run is in progress, `GET /synth/status` returns HTTP 200 with
    `status: "running"`, the active `runId` (matching the UUID returned by the triggering
    POST), and a `progress` object containing: `alarmsEmitted` (a non-negative integer
    less than or equal to `alarmsTotal`), `alarmsTotal` (the planned total for this run),
    `alignedEmitted` (non-negative integer), and `nonAlignedEmitted` (non-negative integer).
    The `summary` field is absent or null while the run is active.

70. **`GET /synth/status` returns `status: "idle"` with a completed `summary` after a run
    finishes successfully.**
    After a P3 synth run completes, `GET /synth/status` returns HTTP 200 with
    `status: "idle"`, the `runId` of the completed run, and a `summary` object containing
    at minimum: `{runId, status: "completed", alarmsEmitted, alignedFraction,
    enrichmentSafeCount, shortfallCascades, enrichmentConflictPatterns, startedAt,
    completedAt}`. `alarmsEmitted` is a positive integer; `alignedFraction` is in [0.0,
    1.0]; `startedAt` and `completedAt` are ISO-8601 timestamps; `completedAt` is after
    `startedAt`.

71. **`GET /synth/status` returns `status: "idle"` with no `runId` or `summary` when no run
    has ever occurred.**
    On a freshly started Simulator instance with no prior synth run, `GET /synth/status`
    returns HTTP 200 with `status: "idle"` and absent or null values for `runId` and
    `summary`. The endpoint does not error.

72. **`GET /synth/status` reflects a failed run with a `failureReason` in the summary.**
    Given a triggered run that fails during background execution (simulated via a mock Pattern
    Manager that returns an empty approved-pattern list after the run has been accepted),
    `GET /synth/status` eventually returns `status: "idle"` (or `"failed"` — see
    OQ-TRIGGER-2) with a `summary` containing a non-empty `failureReason` string. After the
    failed run, a new `POST /synth/run` is accepted with HTTP 202 — the 409 concurrency guard
    is released once the run terminates (by completion or failure).

73. **POST body overrides `target`, `totalAlarms`, and `seed`; env/config defaults apply for
    absent fields.**
    Given env config `P3_AUTO_CORRELATION_TARGET=0.6` and `P3_TOTAL_ALARMS=500`, a POST to
    `/synth/run` with body `{"target": 0.75, "totalAlarms": 200}` starts a run using
    `target=0.75` and `totalAlarms=200`. `GET /synth/status` while running reports
    `alarmsTotal=200`. A subsequent run triggered with an empty body uses `target=0.6` and
    `totalAlarms=500` (the env/config defaults).

74. **POST body `seed` override produces a reproducible alarm sequence for the same seed.**
    Two sequential triggered runs with body `{"seed": 42}` and the same persisted P3 config
    snapshot produce alarm streams that are identical in `alarmType`, `managedObjectId`, and
    ordering — verifiable by comparing the ground-truth label store's cascade records after
    each run. The two runs carry different `runId` values.

75. **`POST /synth/run` and `GET /synth/status` are declared in `openapi.json` and the drift
    guard catches a missing endpoint.**
    The checked-in `services/simulator/openapi.json` contains path entries for `/synth/run`
    (POST, responses: 202/409/422) and `/synth/status` (GET, response: 200 with
    `status`/`runId`/`summary` fields). The drift-guard test, when run against an
    `openapi.json` that is missing the `/synth/status` path, fails with a non-zero exit code.

76. **Persistent service: the FastAPI remains responsive during an active synth run.**
    While a P3 synth run is in progress (triggered via POST), `GET /health` returns HTTP 200
    and `GET /metrics` returns HTTP 200 within 2 seconds. The background run does not block
    the HTTP server's ability to serve requests.

77. **Existing read endpoints are unaffected by the trigger capability.**
    With the persistent service running and no synth run active, `GET /labels`,
    `GET /scenarios`, `GET /health`, and `GET /metrics` all return HTTP 200 with the same
    response shapes as defined by the existing acceptance criteria (AC 16, 17, and the labels
    surface). The new endpoints do not alter any existing endpoint's behavior.

### Open questions — HTTP trigger capability

- **OQ-TRIGGER-1 (design decision — recommended: reject-with-409): Concurrent run policy.**
  The spec recommends rejecting a second `POST /synth/run` with HTTP 409 while a run is in
  progress (single concurrent run, no queue). The alternative — queuing the request — adds
  complexity not needed for MVP (the web-ui button can disable itself after triggering).
  A human must confirm reject-with-409 is the intended behavior, or specify queuing semantics,
  before the designer wires the concurrency guard.

- **OQ-TRIGGER-2 (design decision — recommended shape given): Exact `GET /synth/status`
  response shape and progress granularity.**
  The recommended JSON shape is:
  ```
  {
    "status": "idle" | "running" | "failed",
    "runId": "<uuid or null>",
    "progress": {
      "alarmsEmitted": 0,
      "alarmsTotal": 0,
      "alignedEmitted": 0,
      "nonAlignedEmitted": 0
    },
    "summary": {
      "runId": "<uuid>",
      "status": "completed" | "failed",
      "alarmsEmitted": 0,
      "alignedFraction": 0.0,
      "enrichmentSafeCount": 0,
      "shortfallCascades": 0,
      "enrichmentConflictPatterns": [],
      "failureReason": null,
      "startedAt": "<iso8601>",
      "completedAt": "<iso8601>"
    }
  }
  ```
  Open points requiring human confirmation before the openapi.json is frozen: (a) whether
  `"failed"` is a distinct top-level `status` value or collapses to `"idle"` (with
  `summary.status: "failed"`) — recommend distinct; (b) whether `progress` is present
  when `status == "idle"` — recommend absent or zero-filled; (c) whether `summary` is
  present when no run has ever occurred — recommend null/absent.

- **OQ-TRIGGER-3 (compose/ops change — flag for human confirmation): Compose lifecycle shift
  from one-shot to persistent service.**
  The Simulator currently exits after a run completes; its Docker Compose entry uses a
  one-shot lifecycle (e.g. `restart: no`). Making it a persistent service requires changing
  the compose entry to `restart: unless-stopped` (or equivalent), a health-check that stays
  green while idle between runs, and an `/api/simulator` nginx proxy entry so the web-ui can
  reach `POST /synth/run` and `GET /synth/status` without CORS issues. This is an ops/compose
  change — not a Kafka or event-model contract change — but the deployment-model shift must
  be confirmed by a human before the designer wires compose + the nginx proxy entry. The
  designer/build agent will implement the compose and nginx changes; this open question flags
  the shift for human awareness and approval.

- **OQ-TRIGGER-4 (design decision — recommended: 202-then-fail-in-status): Synchronous vs.
  asynchronous failure detection boundary.**
  Some failure modes (e.g. a POST body `target` value out of range) are detectable
  synchronously at POST time and return HTTP 422. Others (e.g. Pattern Manager returns no
  approved patterns during the background fetch, or `P3_NETWORK_WIDE` is false) are detected
  only during the background run. The recommended boundary: validate only the POST body
  parameters synchronously (HTTP 422 on bad params); all other failures surface via
  `/synth/status` (the run is accepted as HTTP 202, then `status: "failed"` with a
  `failureReason`). A human must confirm this boundary or specify which runtime config
  checks should be synchronous gates before the 202 is issued.

- **OQ-TRIGGER-5 (design decision — recommended: env-only for MVP): Which P3 knobs are
  overridable in the POST body?**
  The spec allows `target`, `totalAlarms`, and `seed` as POST body overrides. Exposing
  additional knobs (e.g. `maxCascadesPerTrail`, `enrichmentOverProvisionMargin`) would make
  the trigger more flexible but increases the validation surface and the openapi.json schema.
  The recommended MVP posture: only `target`, `totalAlarms`, and `seed` are POST-overridable;
  all other knobs come from env/config only. A human must confirm or expand the list before
  the openapi.json body schema is frozen.

- **OQ-TRIGGER-6 (design/ops note — no contract change assumed): nginx proxy entry for
  `/api/simulator`.**
  The web-ui reaches Simulator endpoints via the nginx proxy at `/api/simulator`. The new
  `POST /synth/run` and `GET /synth/status` paths must be forwarded by the nginx proxy
  config. This is not a cross-service Kafka/event-model contract change, but the designer
  and build agent must wire the nginx proxy entry for these two paths. No human decision is
  needed before design (it follows the existing `/api/simulator` proxy pattern); flagged
  here for the designer's awareness.
