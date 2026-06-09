# Architecture — Alarm Correlation Platform (Core IP MVP)

Shared technical context for design and build. Defines the contracts and invariants every
service must respect. (Full narrative lives in the Solution Design doc.)

## Service inventory
| Service | Cohort | Responsibility | Consumes | Produces |
|---|---|---|---|---|
| simulator | Python | domain-grounded synthetic topology + labeled alarms; eval oracle. Multi-domain by design (Core IP is the MVP domain pack) | — | alarms.history, alarms.live (+ a topology snapshot **file** uploaded to the Topology ingestion API) |
| topology | Spring Boot | sole owner of AGE graph; versioned snapshots; query API. Ingests topology snapshots via a published **ingestion API** (file upload), not a Kafka topic | topology snapshot file (via ingestion API) | topology.changed |
| knowledge | Spring Boot | authored templates/policy/params (versioned) | — | knowledge.updated |
| trail-builder | Python | policy-bounded correlation trails | topology.changed | trails.built |
| codebook-generator | Python | forward-propagation codebook | trails.built | codebook.generated |
| enrichment | Spring Boot | normalize, dedup, deterministic filter, trail-tag | alarms.history, alarms.live | alarms.enriched, alarms.enriched.live |
| noise-filter | Python | DBSCAN noise removal | alarms.enriched | transactions.clean |
| pattern-miner | Python | PrefixSpan mining only | transactions.clean | patterns.mined |
| pattern-manager | Spring Boot | Pattern Store, RCA, reconcile, XAI, lifecycle | patterns.mined, patterns.approved | patterns.discovered, patterns.approved |
| correlation-engine | Spring Boot | real-time match/score/RCA; incidents | alarms.persisted.live, patterns.approved, codebook.generated | correlation.results |
| alarm-manager | Spring Boot | sole owner of **live alarm state**: persists each live enriched alarm, republishes it for correlation, and maintains its lifecycle (open→correlated→cleared) + correlation-group membership (root-cause/child) from `correlation.results`; serves the live alarm query API | alarms.enriched.live, correlation.results | alarms.persisted.live |
| web-ui | Angular 20 | topology/trails, pattern review, config, stats | service APIs | patterns.approved (via API) |

## Runtime phases (the operating model)
The deployed system operates in **three runtime phases** (Solution Design §3). These are *runtime
operating phases* — distinct from the *build roadmap* (skeleton → learning → real-time slice, §9),
which is the order we build/integrate in. Every service spec and design must state its **role and
applicability in each phase** (a "Phase applicability" section: per phase, the service's role, an
**Active / Passive / Idle** classification, and its inputs/outputs in that phase).

- **P1 — Topology onboarding** (offline): ingest topology → build trails → compile codebook →
  visualize. Establishes the graph + scopes that the learning and real-time phases depend on.
- **P2 — Pattern learning** (offline): enrich + deterministic-filter historical alarms → DBSCAN
  noise removal → PrefixSpan mining → RCA + codebook reconciliation + explainability → human
  approval of patterns.
- **P3 — Real-time correlation** (online): enrich + filter *live* alarms → stateful match/decode
  against approved patterns + codebook → score + conflict-resolve → tag RCA + child alarms → stats.

**Classification:** *Active* = performs the phase's core work; *Passive* = serves queries / acts as
a dependency / refreshes state in that phase but drives no work of its own; *Idle* = not involved.

| Service | P1 Topology onboarding | P2 Pattern learning | P3 Real-time correlation |
|---|---|---|---|
| simulator | Active — generate topology file, upload to Topology ingestion API | Active — replay `alarms.history` | Active — replay `alarms.live` (wall-clock paced) |
| topology | Active — ingest file, lift to AGE, mint `snapshotId`, emit `topology.changed` | Passive — serves graph query API | Idle — no real-time consumer queries it; topology context is already materialized into trails + codebook during P1 |
| knowledge | Passive — serves trail policy, fault-origin list, propagation templates | Passive — serves DBSCAN / session-window / min-support params | Passive — serves params + approved policy |
| trail-builder | Active — build trails on `topology.changed`, emit `trails.built` | Passive — serves `getTrailsForObject` / `getTrail` | Passive — serves trails |
| codebook-generator | Active — compile codebook, emit `codebook.generated` | Passive — serves codebook for reconcile | Passive — serves codebook for match |
| enrichment | Idle | Active — enrich `alarms.history` → `alarms.enriched` | Active — enrich `alarms.live` → `alarms.enriched.live` |
| noise-filter | Idle | Active — DBSCAN over `alarms.enriched` → `transactions.clean` | Idle (history path only) |
| pattern-miner | Idle | Active — PrefixSpan over `transactions.clean` → `patterns.mined` | Idle |
| pattern-manager | Idle | Active — RCA + reconcile + XAI + lifecycle → `patterns.discovered` / `patterns.approved` | Passive — serves approved patterns |
| correlation-engine | Idle | Idle | Active — match/score/RCA over `alarms.persisted.live` → `correlation.results` |
| alarm-manager | Idle | Idle | Active — persist live alarms from `alarms.enriched.live`, republish on `alarms.persisted.live`; maintain lifecycle + correlation-group role from `correlation.results`; serve the live alarm view to web-ui |
| web-ui | Active — topology & trails visualization | Active — pattern review/approve, config edits | Active — live incidents & correlation stats |

This table is the **canonical phase map**; each service's spec/design restates *its own* row in
detail (role + Active/Passive/Idle + per-phase I/O) and must stay consistent with it. A change to a
service's phase applicability updates this table + the service docs.

## Event model (the contract)
No schema registry. `libs/event-model` (versioned with the repo) defines the **envelope**
(`eventId, type, schemaVersion, occurredAt, source, traceId, payload`) and **payloads**
(AlarmEvent, TopologyChangedEvent, TrailsBuiltEvent, CodebookGeneratedEvent, TransactionEvent,
PatternMinedEvent, PatternDiscoveredEvent/PatternApprovedEvent, CorrelationResultEvent,
KnowledgeUpdatedEvent).
Two bindings from one JSON Schema: Java (Spring services) + Python/Pydantic (Python services).
Consumers reject unknown major `schemaVersion`.

## Kafka topics
topology.changed, trails.built, codebook.generated, knowledge.updated,
alarms.history, alarms.live, alarms.enriched, alarms.enriched.live, alarms.persisted.live,
transactions.clean, patterns.mined, patterns.discovered, patterns.approved, correlation.results,
*.dlq. Producers/consumers per the table. **Adding a topic is a contract change.**

> **Live alarm path (real-time).** On the live path the Alarm Manager sits **in-line** between
> Enrichment and the Correlation Engine: Enrichment emits `alarms.enriched.live` → the Alarm
> Manager persists each live alarm (initial state `open`) and republishes it on
> **`alarms.persisted.live`** → the **Correlation Engine consumes `alarms.persisted.live`** (not
> `alarms.enriched.live`). This guarantees every alarm entering correlation has been persisted
> first, and makes the Alarm Manager the single source of truth for **live alarm state**. The
> Alarm Manager also consumes `correlation.results` to update each alarm's lifecycle
> (`open`→`correlated`→`cleared`) and its correlation-group role (root-cause / child) + incident
> linkage. (The historical/learning path persists nothing here — historical alarms are mined
> in-flight from Kafka: simulator → enrichment → noise-filter → pattern-miner. No new event
> payload: `alarms.persisted.live` carries the existing `AlarmEvent`.)

> **Topology ingestion is file/API-based, not a topic.** The raw topology snapshot is **not**
> a Kafka event. The Simulator generates a domain-grounded topology snapshot **file** and uploads
> it to the Topology Service's published **ingestion API**; the Topology Service lifts it into the
> AGE graph, versions it (`snapshotId`), and emits `topology.changed`. The **topology-snapshot file
> schema** is a versioned contract (see "Topology snapshot file" below), exactly like the topic and
> event-model contracts. (Historical note: an earlier `topology.raw` topic was removed in favour of
> this — the raw-vs-lifted distinction and large snapshot payloads suit a file/API hand-off better.)

## Data stores & ownership
Apache AGE — topology graph; only via Topology Service. PostgreSQL — pattern store (owned by
Pattern Manager), incident store (owned by Correlation Engine), knowledge store (owned by
Knowledge Service), and the **live alarm store (owned by the Alarm Manager)**; separation by
schema. Kafka — the bus. **Single owners:** each store is written by exactly one service; others
read via that service's API or events, never the store directly.

**Alarm Manager owns the live alarm store** — the single source of truth for **live alarm state**.
It holds each live alarm (from `alarms.enriched.live`) with its **lifecycle state** (`open` →
`correlated` → `cleared`) and, once correlated, its **correlation-group role** (root-cause / child)
and **incident linkage**, updated from `correlation.results`. It serves the **web-ui**'s live alarm
view (which alarms are open/correlated, their state, RCA/child role, and incident membership).

**MVP scope — live only, no historical corpus.** Historical/learning-path alarms are **not
persisted** by the Alarm Manager (or anywhere) for the MVP: they are replayed by the Simulator and
**mined in-flight** through the Kafka stream (simulator → enrichment → noise-filter → pattern-miner).
A durable historical-alarm corpus is deferred post-MVP.

**No new event payload** — the Alarm Manager consumes the existing `alarms.enriched.live` (the
`AlarmEvent` payload) and `correlation.results` (`CorrelationResultEvent`), republishes the
`AlarmEvent` on the new `alarms.persisted.live` topic, and exposes the live alarm store via a query
API.

## Invariants
Identity binding: alarms use the same `managedObjectId` as the graph (defined in event-model).
Snapshot versioning: each topology load mints a `snapshotId`; trails/codebook reference it.
Idempotency: dedupe on `eventId`/`alarmId`. Observability: JSON logs + `/metrics`, `/health`.
Errors: poison messages → `<topic>.dlq`. Contract-first: with the library + topic contracts
frozen, the services build in parallel. API contracts: every service publishes an OpenAPI spec
and exposes configurable integration points (mock for unit tests, real for integration) — see
below.

## API contracts & integration points
Alongside the event model (the Kafka contract), every service's **synchronous API is also a
contract**.

- **Published OpenAPI spec (mandatory).** Every service that exposes an HTTP API publishes an
  OpenAPI 3.1 document at `/openapi.json` (and a human-readable UI such as Swagger UI / Spring
  springdoc / FastAPI docs) and checks the generated `openapi.json` into its
  `services/<svc>/` directory. The spec is the **single source of truth for the service's HTTP
  surface** — request/response shapes reuse the `libs/event-model` payloads where applicable.
- **Used for unit testing.** A service's own published OpenAPI spec drives its **contract/unit
  tests** — request/response schema validation and (provider-side) contract verification —
  so the implementation cannot drift from the published spec.
- **Used by collaborating services for integration.** A consuming service builds its client
  against the **producer's published OpenAPI spec**, never against the producer's source code.
  This keeps the contract-first, no-cross-service-coupling invariant intact for synchronous
  calls just as the topic contracts do for events. A change to a service's OpenAPI surface is a
  **contract change** → it requires an `architecture.md`/spec update **and** human approval,
  exactly like adding a topic/payload/field.
- **Configurable integration points (mock vs. real).** Every service defines its outbound
  integration points (the other services / endpoints it calls) through **configuration, not
  hard-coded URLs**. Each integration point is switchable per environment:
  - **Unit testing → mock.** Backed by a **mock/stub generated from the collaborator's
    published OpenAPI spec** (e.g. Prism/WireMock/`respx`/MockWebServer), so unit tests run in
    isolation with no live dependency.
  - **Integration testing → actual.** Pointed at the **real collaborating service** (the
    Docker Compose address on the `integration` branch).
  - Resolution is by environment/config (base-URLs + a `mock|real` toggle from env or the
    Knowledge Service where appropriate), so the same code runs against mocks in CI and against
    live services in integration without modification.

This requirement is captured per service in each `services/<svc>/spec.md` (Contract section)
and detailed in `design.md` (API contracts + integration points), and is checked by the
`code-review` and `integration-test` skills.

## Topology snapshot file & ingestion API
Topology is loaded by **file upload to an API**, not by a Kafka event:

- **Topology snapshot file (a versioned contract).** A domain-grounded topology snapshot is a
  structured file (JSON) describing the typed nodes and edges of the graph (per the domain's
  layer model — for the Core IP domain, the §"Topology Graph Model" types: Node/LineCard/Port/
  IPLink/IGPAdjacency/LSP/VPNService/FiberSpan/SRLG and their edges). Every object carries its
  `managedObjectId` in the canonical `<objectType>:<id>` scheme. The **file schema is a contract**
  (versioned; a change to it requires an `architecture.md`/spec update + human approval, like a
  topic/payload). It is the hand-off between any topology *producer* (the Simulator today) and the
  Topology Service. Where it lives (event-model vs. a `schema/` dir) is a design decision.
- **Topology ingestion API (owned by the Topology Service).** The Topology Service publishes an
  OpenAPI 3.1 ingestion endpoint that accepts a topology snapshot file, lifts it into AGE, mints a
  `snapshotId`, and emits `topology.changed`. Producers (the Simulator) build their upload client
  against the Topology Service's **published OpenAPI**, never its source — same no-coupling rule as
  every other synchronous call. The endpoint is a config-switchable integration point for the
  producer (mock from Topology's OpenAPI for unit tests, real Topology in integration).

## Domain extensibility (Core IP is the MVP domain)
The platform targets the **Core IP** domain for the MVP, but generation of grounded synthetic data
is **domain-parameterized by design**: the Simulator separates a reusable generation/replay engine
from a **domain pack** (the domain's object/edge types, propagation templates, alarm shapes, and
scenario library). The Core IP domain pack is the only one built for the MVP; a new domain is added
as a new pack without reworking the engine. Domain-specific business data does **not** leak into the
shared reusable engine, and the engine is extensible (a new domain pack plugs in). Downstream
services remain domain-agnostic: they operate on the typed graph, the canonical event model, and
Knowledge-Service-authored templates/policy — none of which hard-code Core IP specifics.

## Test frameworks (standard per cohort)
The unit/contract test framework is fixed per cohort — do not substitute:

| Cohort | Unit / contract tests | E2E |
|---|---|---|
| Java (Spring Boot) | **JUnit 5** (+ Testcontainers for integration) | — (covered by integration-test) |
| Python | **pytest** | — (covered by integration-test) |
| web-ui (Angular 20) | **Vitest + Angular TestBed** (component/unit, jsdom; mock backends from producers' OpenAPI) | **Playwright** (browser E2E, owned by web-ui, run against the integration stack) |

Playwright is **E2E only** — it drives a real browser against a running app and is **not** the
UI unit-test runner. UI unit/component tests use Vitest + TestBed against mocked backends.
Cross-service end-to-end behaviour is asserted by the `integration-test` skill (Simulator
oracle + topic chain); the web-ui's Playwright suite covers UI user flows.

## Test oracle
Simulator injects fiber-cut, line-card-fault, port-fault + ≥3 noise classes with ground-truth
`{rootCause, children}`. Integration thresholds (RCA accuracy, alarm-reduction ratio,
noise-filter effectiveness, pattern quality) are defined in `services/simulator/spec.md`.
