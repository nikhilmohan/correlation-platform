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
  cards, ports, IP links, IGP adjacencies, LSPs over link paths, VPN services over LSPs,
  fiber spans, and SRLG groupings. Topology size is configurable (e.g., 10–200 nodes).
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
  alarms per the §5 propagation templates (e.g., fiber cut → `LinkDown` x2 → `AdjDown` →
  `LSPDown` → `VPNloss`/`ReachabilityLoss`), with configurable timing jitter. At minimum:
  **fiber-cut**, **line-card-fault**, and **port-fault** scenarios.
- Generate background noise alongside injected scenarios: at minimum **3 noise classes**
  (e.g., flapping alarms, self-clearing transients, chatty standing alarms, coincidental
  unrelated alarms — the exact classes are a design/config decision).
- Persist and expose a **ground-truth label** per injected scenario in the form
  `{rootCause, children}` for evaluation use.
- Replay modes: **history** (batch — emits to `alarms.history`) and **live** (streamed with
  wall-clock pacing — emits to `alarms.live`), as configured.
- Serve as the integration test oracle: the integration-threshold metrics defined in this spec
  are the numeric targets asserted by the integration test harness.
- Read scenario configuration (topology size, fault scenario selection, timing jitter, noise
  mix, noise class definitions) from local config files or the Knowledge Service; no
  hard-coded values.
- Emitted `AlarmEvent` payloads carry X.733-shaped fields (per the §5 alarm shapes in the
  Solution Design) and reflect realistic Core IP layering.

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
   PE/P/RR/peering nodes, line cards, ports, IP links, IGP adjacencies, LSPs, VPN services,
   fiber spans, SRLG groups — with configurable size, assigning each object a stable
   `managedObjectId` per the `libs/event-model` scheme.
2. Generate the domain-grounded topology snapshot **file** (versioned contract; see Contract
   section) and upload it to the Topology Service's **ingestion API** (client built against
   Topology's published OpenAPI 3.1 spec; integration point is config-switchable mock/real).
3. Load fault scenario configurations (from local files or Knowledge Service) defining which
   root-cause types to inject, their cascade templates, timing jitter parameters, and noise
   mix.
4. Inject labeled fault scenarios into the alarm stream: for each scenario, produce the
   root-cause alarm and its causally downstream child alarms per the configured cascade
   template (supplied by the domain pack), with timing jitter applied. Record the ground-truth
   label `{rootCause, children}` for each injected scenario.
5. Inject background noise alarms (at least 3 noise classes from the domain pack's scenario
   library) interleaved with the labeled scenario alarms, at configurable rates and mix.
6. Replay the alarm stream in **history** mode (batch, onto `alarms.history` — feeds pattern
   mining and discovery) or **live** mode (streamed with wall-clock pacing, onto `alarms.live`
   — feeds real-time alarm correlation), as configured.
7. Make ground-truth labels retrievable for evaluation — the retrieval mechanism (REST API vs.
   file export) is a design decision; see Open questions.
8. Accept generation parameters from a swappable **domain pack** interface — object/edge
   types, propagation templates, alarm shapes, and scenario library are supplied by the pack,
   not hard-coded in the engine. Domain business logic must not leak into the shared engine.
9. Expose `/health` and `/metrics` endpoints and emit structured JSON logs.

## Contract

- **Consumes (Kafka):** — (none; the Simulator is a pure Kafka producer)
- **Produces (Kafka):**
  - `alarms.history` — batch historical alarm replay; feeds enrichment → noise-filter →
    pattern-miner (learning path). Payload: `AlarmEvent` (frozen `libs/event-model`).
  - `alarms.live` — streamed live alarm replay with wall-clock pacing; feeds enrichment →
    correlation-engine (real-time correlation path). Payload: `AlarmEvent` (frozen
    `libs/event-model`).
- **Topology snapshot file (produced artifact — versioned contract):**
  - The Simulator generates a structured JSON topology snapshot file describing the typed
    nodes and edges of the domain graph (for Core IP: the nine object types and their edges).
  - Every object carries its `managedObjectId` in the canonical `<objectType>:<id>` scheme.
  - The **topology-snapshot file schema is a versioned contract**: a change to it requires a
    `docs/architecture.md` update and human approval, exactly like a topic/payload change.
  - The file is uploaded to the Topology Service's ingestion API (see integration points
    below); it is not a Kafka event.
- **APIs exposed:** The Simulator must make ground-truth labels retrievable for evaluation
  (per §6.1 and §10 of the Solution Design). Whether this is a REST API or a file-based
  export is a **design decision** — see Open questions. If a REST API is exposed, it MUST
  publish an OpenAPI 3.1 document at `/openapi.json` (and check in `openapi.json` to
  `services/simulator/`) per the `docs/architecture.md` API contract requirement.
- **APIs/integration points consumed from other services:**
  - **Topology ingestion API (Topology Service):** The Simulator uploads the topology snapshot
    file to the Topology Service's published OpenAPI 3.1 ingestion endpoint. The Simulator's
    upload client is built against Topology's published OpenAPI, never Topology's source.
    This integration point is **config-switchable**: a mock/stub (generated from Topology's
    OpenAPI spec) for unit tests; the real Topology Service endpoint for integration. The
    Topology Service base URL and mock/real mode are controlled by environment variable.
  - **Knowledge Service scenario config endpoint (optional):** read scenario configuration
    parameters. Config-switchable: local-file mode (default/mock for unit tests) vs. real
    Knowledge Service (for integration). URL and mode controlled by environment variable.
- **Data owned:**
  - Ground-truth scenario labels (`{rootCause, children}` per injected scenario) — persisted
    in a store or file; medium is a design decision.
  - Scenario definitions (loaded from local config files or Knowledge Service at startup/run
    time; the Simulator does not author them).

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
| RCA accuracy | Fraction of incidents (in `correlation.results`) whose `rootCauseAlarmId` matches the injected ground-truth root cause for that scenario | **≥ 0.80** |
| Alarm-reduction ratio | Raw alarms emitted ÷ correlated incidents produced | **≥ 5×** |
| Noise-filter effectiveness (removal) | Fraction of injected noise alarms removed by the Noise Filter | **≥ 0.90** |
| Noise-filter effectiveness (retention) | Fraction of real (scenario) alarms retained after the Noise Filter | **≥ 0.95** |
| Pattern quality | Count of injected patterns recovered by the Pattern Miner ÷ total injected patterns | **≥ 0.80** |

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
   the emitted alarm set contains: the root-cause alarm on the `FiberSpan` object, plus child
   alarms including `LinkDown` on each affected `IPLink`, `AdjDown` on each affected
   `IGPAdjacency`, `LSPDown` on affected LSPs, and `ReachabilityLoss` (or `VPNloss`) on
   affected VPN services — matching the §5 propagation template for `RIDES_ON` /
   `ADJACENCY_OVER` / `TRAVERSES` / `SERVES` edges. The ground-truth label for this scenario
   records the `FiberSpan` alarm as root cause and all downstream alarms as children.

5. **Line-card-fault and port-fault scenarios are producible and distinguishable.** The
   Simulator can produce a `line-card-fault` scenario (root cause on a `LineCard` object,
   cascade per the `HOSTED_ON` template) and a `port-fault` scenario (root cause on a `Port`
   object) as distinct, separately-labeled scenarios. Their ground-truth labels differ in
   `rootCause` object type.

6. **At least 3 noise classes are generated.** When noise injection is enabled, the Simulator
   emits alarms belonging to at least 3 distinct configured noise classes. Each noise alarm
   can be identified as noise by its absence from any ground-truth `children` set.

7. **Emitted alarm events validate against the frozen `AlarmEvent` schema.** Every
   `AlarmEvent` payload emitted by the Simulator passes validation by the
   `libs/event-model` Python/Pydantic binding without raising a validation error. Required
   fields (`alarmId`, `managedObjectId`, `eventType`, `probableCause`, `perceivedSeverity`,
   `raisedAt`, `state`, `trailIds`) are present; `state` is one of `raised` or `cleared`.

8. **History replay lands on `alarms.history`.** In history mode, all alarm events are
   produced to the `alarms.history` Kafka topic and zero alarm events are produced to
   `alarms.live`.

9. **Live replay lands on `alarms.live` with wall-clock pacing.** In live mode, all alarm
   events are produced to the `alarms.live` Kafka topic and zero alarm events are produced to
   `alarms.history`. Successive events are emitted with inter-event delays proportional to the
   configured pacing multiplier (i.e., the delay between two events is not zero for a pacing
   factor > 0).

10. **Ground-truth labels are retrievable for evaluation.** For each injected scenario, a
    ground-truth record `{rootCause, children}` can be retrieved after a run (via the
    mechanism determined in design — REST API or file). The retrieved `rootCause` matches the
    injected root-cause alarm's `alarmId`, and `children` contains the `alarmId`s of all
    causally downstream alarms emitted for that scenario.

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

14. **Topology snapshot file validates against the topology-file schema.** The topology
    snapshot file generated by the Simulator in any run validates against the versioned
    topology-snapshot file schema (the contract between the Simulator and the Topology
    Service) without schema errors. All required fields are present and all object references
    are well-formed.

15. **Topology ingestion API integration point is config-switchable.** When the
    `TOPOLOGY_API_MODE` env var is set to `mock`, the Simulator's upload client directs
    requests to the mock/stub (generated from Topology's published OpenAPI spec) and does not
    attempt to contact a real Topology Service. When set to `real`, it contacts the configured
    `TOPOLOGY_API_BASE_URL`. Switching the mode requires no code change.

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

- **OQ-4 (design decision): Topology-snapshot file schema location.** The
  `docs/architecture.md` notes "where it lives (event-model vs. a `schema/` dir) is a design
  decision." The designer must decide whether the versioned topology-snapshot file schema
  lives under `libs/event-model/` (alongside the event-model schemas) or in a separate
  `schema/` directory, and check it in accordingly.
