# simulator — Service Spec

## Purpose

The Simulator generates a grounded synthetic Core IP topology and labeled alarm streams
(historical and live), replays them onto Kafka, and serves as the platform's evaluation
harness. It injects known fault scenarios with ground-truth labels — identifying the root
cause and all causally downstream (child) alarms for each injected incident — so that
downstream services (enrichment, noise-filter, pattern-miner, correlation-engine) can be
measured against a known answer. All events it emits use the frozen `libs/event-model`
Python/Pydantic binding; all `managedObjectId` values follow the `<objectType>:<id>` scheme
defined in that contract, ensuring topology and alarm identities are shared.

## Scope

**In scope:**

- Generate a realistic synthetic Core IP topology containing PE/P/RR/peering nodes, line
  cards, ports, IP links, IGP adjacencies, LSPs over link paths, VPN services over LSPs,
  fiber spans, and SRLG groupings. Topology size is configurable (e.g., 10–200 nodes).
- Assign stable `managedObjectId`s (per the `libs/event-model` scheme, objectTypes from the
  nine known typed graph layers: `Node`, `LineCard`, `Port`, `IPLink`, `IGPAdjacency`, `LSP`,
  `VPNService`, `FiberSpan`, `SRLG`) that are shared between the topology snapshot and all
  emitted alarms.
- Emit a `topology.raw` snapshot onto the `topology.raw` Kafka topic.
- Generate labeled fault scenarios: inject a known root cause, produce a cascade of child
  alarms per the §5 propagation templates (e.g., fiber cut → `LinkDown` x2 →  `AdjDown` →
  `LSPDown` → `VPNloss`/`ReachabilityLoss`), with configurable timing jitter. At minimum:
  **fiber-cut**, **line-card-fault**, and **port-fault** scenarios.
- Generate background noise alongside injected scenarios: at minimum **3 noise classes**
  (e.g., flapping alarms, self-clearing transients, chatty standing alarms, coincidental
  unrelated alarms — the exact classes are a design/config decision).
- Persist and expose a **ground-truth label** per injected scenario in the form
  `{rootCause, children}` for evaluation use.
- Replay modes: **history** (batch — emits to `alarms.history`) and **live** (streamed with
  wall-clock pacing — emits to `alarms.live`).
- Serve as the integration test oracle: the integration-threshold metrics defined in this spec
  are the numeric targets asserted by the integration test harness.
- Read scenario configuration (topology size, fault scenario selection, timing jitter, noise
  mix, noise class definitions) from local config files or the Knowledge Service; no
  hard-coded values.

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

## Tasks (high-level)

1. Generate a typed multi-layer Core IP topology (PE/P/RR/peering nodes, line cards, ports,
   IP links, IGP adjacencies, LSPs, VPN services, fiber spans, SRLG groups) with configurable
   size, and assign each object a stable `managedObjectId` per the `libs/event-model` scheme.
2. Emit a `topology.raw` snapshot onto the `topology.raw` Kafka topic using the canonical
   event envelope from `libs/event-model`.
3. Load fault scenario configurations (from local files or Knowledge Service) defining which
   root-cause types to inject, their cascade templates, timing jitter parameters, and noise
   mix.
4. Inject labeled fault scenarios into the alarm stream: for each scenario, produce the
   root-cause alarm and its causally downstream child alarms per the configured cascade
   template, with timing jitter applied. Record the ground-truth label
   `{rootCause, children}` for each injected scenario.
5. Inject background noise alarms (at least 3 noise classes) interleaved with the labeled
   scenario alarms, at configurable rates and mix.
6. Replay the alarm stream in **history** mode (batch, onto `alarms.history`) or **live** mode
   (streamed with wall-clock pacing, onto `alarms.live`), as configured.
7. Make ground-truth labels retrievable for evaluation — the retrieval mechanism (REST API vs.
   file export) is a design decision; see Open questions.
8. Expose `/health` and `/metrics` endpoints and emit structured JSON logs.

## Contract

- **Consumes (Kafka):** — (none; the Simulator is a pure producer)
- **Produces (Kafka):**
  - `topology.raw` — raw topology snapshot; consumed by the Topology Service
  - `alarms.history` — batch historical alarm replay; consumed by the Enrichment Service
  - `alarms.live` — streamed live alarm replay; consumed by the Enrichment Service
- **APIs exposed:** The Simulator must make ground-truth labels retrievable for evaluation
  (per §6.1 and §10 of the Solution Design). Whether this is a REST API or a file-based
  export is a **design decision** — see Open questions. If a REST API is exposed, it MUST
  publish an OpenAPI 3.1 document at `/openapi.json` (and check in `openapi.json` to
  `services/simulator/`) per the `docs/architecture.md` API contract requirement.
- **APIs/data consumed from other services:**
  - Knowledge Service (optional integration point): read scenario config parameters. This
    integration point MUST be config-switchable: local-file mode (default/mock for unit tests)
    vs. real Knowledge Service (for integration). URL and mode controlled by environment
    variable.
- **Integration points (mock vs. real):**
  - Knowledge Service scenario config endpoint — switchable per env var; mock (local config
    file or stub from Knowledge Service OpenAPI spec) for unit tests; real for integration.
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
  speed/pacing, Kafka broker addresses, Knowledge Service base URL, integration-point mode
  (mock vs. real). Config MUST be validated at startup; missing required config is a fatal
  error reported via structured log before exit.
- **Observability:** `/health` (liveness probe), `/metrics` (Prometheus-compatible), structured
  JSON logs on stdout. Cohort is Python (per CLAUDE.md); test framework is pytest.
- **API contract:** if an HTTP API is exposed, it publishes OpenAPI 3.1 at `/openapi.json`
  and the checked-in `services/simulator/openapi.json` is the authoritative surface; any
  surface change is a contract change requiring `docs/architecture.md` update and human
  approval.
- **Error handling:** the Simulator is primarily a producer; Kafka producer errors (e.g.,
  broker unavailable) are logged and surfaced via `/health`; there is no inbound Kafka stream
  requiring a DLQ. If the Simulator consumes from any Kafka topic in future, poison messages
  route to `<topic>.dlq`. Config validation errors abort startup cleanly with a structured
  log entry.

## Integration thresholds (test oracle — owned by this spec)

These are the platform-level evaluation metrics the integration test harness asserts.
The Simulator owns their definitions; the numeric pass thresholds are set here and referenced
by the `integration-test` skill.

| Metric | Definition | Pass threshold |
|---|---|---|
| Alarm-reduction ratio | raw alarms emitted ÷ correlated incidents produced | **TBD** — see [issue #15](https://github.com/nikhilmohan/correlation-platform/issues/15) |
| RCA accuracy | fraction of incidents (in `correlation.results`) whose `rootCauseAlarmId` matches the injected ground-truth root cause for that scenario | **TBD** — see [issue #15](https://github.com/nikhilmohan/correlation-platform/issues/15) |
| Noise-filter effectiveness | percentage of injected noise alarms removed by the Noise Filter vs. percentage of real (scenario) alarms retained | **TBD** — see [issue #15](https://github.com/nikhilmohan/correlation-platform/issues/15) |
| Pattern quality | count of injected patterns recovered by the Pattern Miner ÷ total injected patterns | **TBD** — see [issue #15](https://github.com/nikhilmohan/correlation-platform/issues/15) |

The Solution Design (§10) names these four metrics but does not specify numeric pass
thresholds. Thresholds are marked TBD and tracked in Open question OQ-3 for a human to
set before integration tests can be written.

## Acceptance criteria

Each criterion is phrased to map to a single pytest test.

1. **Topology snapshot is valid and internally consistent.** Given a configured topology size
   N (e.g., N=20), the Simulator produces a topology snapshot where: every node has a
   `managedObjectId` of the form `Node:<id>`; every line card references a node that exists
   in the snapshot; every port references a line card that exists; every IP link references
   two ports that exist; SRLG groups reference IP links that exist. No dangling references.

2. **`managedObjectId` values are shared between topology and alarms.** Every `managedObjectId`
   in emitted `AlarmEvent` payloads is present in the topology snapshot emitted in the same
   run. No alarm references an object absent from the topology.

3. **`managedObjectId` values conform to the frozen contract scheme.** Every `managedObjectId`
   in the topology snapshot and in every emitted `AlarmEvent` matches the pattern
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

14. **Topology snapshot event uses the canonical envelope.** The event emitted to `topology.raw`
    carries a valid envelope (all required fields: `eventId`, `type`, `schemaVersion=1`,
    `occurredAt`, `source`, `traceId`, `payload`) as defined by the frozen
    `libs/event-model` envelope schema. (See Open question OQ-1 on the payload type for
    `topology.raw`.)

15. **`/health` returns 200 when the service is running.** A GET request to `/health` returns
    HTTP 200. A service that has not completed startup or has lost its Kafka connection returns
    a non-200 response.

16. **`/metrics` returns Prometheus-format output.** A GET request to `/metrics` returns HTTP
    200 with `Content-Type: text/plain` and at minimum one counter or gauge metric
    (e.g., `simulator_alarms_emitted_total`).

17. **Config validation fails fast on missing required config.** Starting the Simulator
    without a required environment variable (e.g., Kafka broker address) produces a structured
    JSON log error and exits with a non-zero exit code before emitting any events.

## Open questions

- **OQ-1 (contract gap — blocks design): What is the payload type for `topology.raw`?**
  The frozen `libs/event-model` envelope schema enumerates nine payload `type` values; none
  corresponds to the raw snapshot emitted by the Simulator onto `topology.raw`. The
  `TopologyChangedEvent` is produced by the Topology Service onto `topology.changed`, not by
  the Simulator. Either: (a) the Simulator emits a payload whose `type` is not yet in the
  frozen event model (a contract change requiring `docs/architecture.md` update + human
  approval before design proceeds), or (b) the raw snapshot uses an existing type in a
  designated way that must be clarified. **Do not guess — this is a contract change decision
  for a human.** Tracked in [GitHub issue #14](https://github.com/nikhilmohan/correlation-platform/issues/14).

- **OQ-2 (design decision — does not block spec): How are ground-truth labels retrieved?**
  The Solution Design §6.1 states "ground-truth labels retrievable for evaluation" without
  specifying the mechanism. Options include: (a) an HTTP REST endpoint (requires publishing
  an OpenAPI 3.1 spec per the architecture contract), (b) a flat file written at end-of-run
  (simpler, no API surface), (c) a queryable store. This is a design-stage decision; the spec
  captures both that retrieval must be possible and that if a REST API is chosen it must
  follow the OpenAPI 3.1 requirement. No design decision should be made until this is
  confirmed.

- **OQ-3 (human must set — blocks integration tests): Numeric pass thresholds for integration
  metrics.** The Solution Design §10 defines four evaluation metrics (alarm-reduction ratio,
  RCA accuracy, noise-filter effectiveness, pattern quality) but does not specify numeric
  pass/fail thresholds. These thresholds are the gate for the integration test harness. A
  human must set them before integration tests can be written. Tracked in
  [GitHub issue #15](https://github.com/nikhilmohan/correlation-platform/issues/15).

- **OQ-4 (design decision): Deterministic vs. random replay seeding.** Should the Simulator
  support a configurable random seed so that re-runs reproduce the exact same alarm sequence
  (useful for regression testing)? The spec requires configurable jitter but does not mandate
  determinism. This is a design-stage decision.
