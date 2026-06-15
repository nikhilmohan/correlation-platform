# Architecture — Alarm Correlation Platform (Core IP MVP)

Shared technical context for design and build. Defines the contracts and invariants every
service must respect. (Full narrative lives in the Solution Design doc.)

## Service inventory
| Service | Cohort | Responsibility | Consumes | Produces |
|---|---|---|---|---|
| simulator | Python | domain-grounded synthetic topology + labeled alarms; eval oracle. Multi-domain by design (Core IP is the MVP domain pack) | — | alarms.history, alarms.live (+ a topology snapshot **file** uploaded to the Topology ingestion API) |
| topology | Spring Boot | sole owner of the NebulaGraph topology graph; versioned snapshots; query API. Ingests topology snapshots via a published **ingestion API** (file upload), not a Kafka topic | topology snapshot file (via ingestion API) | topology.changed |
| knowledge | Spring Boot | authored templates/policy/params (versioned) | — | knowledge.updated |
| trail-builder | Python | policy-bounded correlation trails | topology.changed | trails.built |
| codebook-generator | Python | forward-propagation codebook | trails.built | codebook.generated |
| enrichment | Spring Boot | normalize, dedup, deterministic filter, trail-tag | alarms.history, alarms.live | alarms.enriched, alarms.enriched.live |
| noise-filter | Python | DBSCAN noise removal | alarms.enriched | transactions.clean |
| pattern-miner | Python | PrefixSpan mining only | transactions.clean | patterns.mined |
| pattern-manager | Spring Boot | Pattern Store, RCA, reconcile, XAI, lifecycle | patterns.mined, patterns.approved | patterns.discovered, patterns.approved |
| correlation-engine | Spring Boot | real-time match/score/RCA; incidents | alarms.persisted.live, patterns.approved, codebook.generated | correlation.results |
| alarm-manager | Spring Boot | sole owner of **live alarm state**: persists each live enriched alarm, republishes it for correlation, and maintains its lifecycle (open→correlated→cleared) + correlation-group membership (root-cause/child) from `correlation.results`, and keeps live alarm status in sync from generic `alarms.status.changed` (`AlarmStatusChange`, produced by any service); serves the live alarm query API | alarms.enriched.live, correlation.results, alarms.status.changed | alarms.persisted.live |
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
| topology | Active — ingest file, lift to NebulaGraph, mint `snapshotId`, emit `topology.changed` | Passive — serves graph query API | Idle — no real-time consumer queries it; topology context is already materialized into trails + codebook during P1 |
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
KnowledgeUpdatedEvent, AlarmStatusChange).
Two bindings from one JSON Schema: Java (Spring services) + Python/Pydantic (Python services).
Consumers reject unknown major `schemaVersion`.

## Kafka topics
topology.changed, trails.built, codebook.generated, knowledge.updated,
alarms.history, alarms.live, alarms.enriched, alarms.enriched.live, alarms.persisted.live,
alarms.status.changed,
transactions.clean, patterns.mined, patterns.discovered, patterns.approved, correlation.results,
*.dlq. Producers/consumers per the table. **Adding a topic is a contract change.**

> **`alarms.status.changed` (generic alarm-status sync).** Carries the `AlarmStatusChange`
> payload. **Any** service may produce it whenever an alarm's lifecycle status changes
> (`open` / `in-progress` / `correlated` / `cleared` / `reverted-open`); the **Alarm Manager
> consumes** it to keep its live alarm status in sync. It is deliberately minimal and
> **not** correlation-specific — correlation context (incident linkage, root-cause/child role)
> stays on `correlation.results` (`CorrelationResultEvent`).

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
> NebulaGraph graph, versions it (`snapshotId`), and emits `topology.changed`. The **topology-snapshot file
> schema** is a versioned contract (see "Topology snapshot file" below), exactly like the topic and
> event-model contracts. (Historical note: an earlier `topology.raw` topic was removed in favour of
> this — the raw-vs-lifted distinction and large snapshot payloads suit a file/API hand-off better.)

## Data stores & ownership
NebulaGraph — topology graph; only via Topology Service (nGQL, fully abstracted behind the
service API — callers never touch the graph DB). PostgreSQL — topology snapshot metadata &
versioning (owned by Topology Service), pattern store (owned by Pattern Manager), incident store
(owned by Correlation Engine), knowledge store (owned by Knowledge Service), and the **live alarm
store (owned by the Alarm Manager)**; separation by schema. Kafka — the bus. **Single owners:**
each store is written by exactly one service; others read via that service's API or events, never
the store directly. **Topology Service** owns two stores: the NebulaGraph graph (the typed
multi-layer topology) and a PostgreSQL schema for snapshot version metadata (`snapshotId`, domain,
timestamps, ingest audit) — both internal, never shared.

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

## Shared infrastructure conventions (build + runtime)
The PostgreSQL, NebulaGraph, and Kafka **instances are shared** across services — there is **one**
of each in Compose (`cp-postgres`, the NebulaGraph cluster, `cp-kafka`). Services own **logical
slices** within the shared instances, never their own instance. Every service build MUST follow
these conventions so services compose without collision:

- **PostgreSQL — one instance + one database, schema-per-service.** All relational stores live in
  the single shared `postgres` instance / database. Each owning service gets a **named schema** and
  writes **only** that schema (the single-owner rule): `topology_meta` (Topology — snapshot
  metadata; the graph itself is in NebulaGraph), `knowledge` (Knowledge), `trailbuilder` (Trail
  Builder — `trail`/`trail_member` tables), `codebook` (Codebook Generator — Codebook Store), `pattern`
  (Pattern Manager), `incident` (Correlation Engine), `live_alarm` (Alarm Manager), `noise_filter`
  (Noise Filter — `nf_run_stats` + `nf_observed_chatter` tables). (Eight schema-owning services; the
  Simulator, Enrichment, and Pattern Miner own no relational store.) **These eight schema names are the
  authoritative assignment** — each service build uses exactly its assigned name (some service
  designs say only "the Store"; the name here is binding so two services never default to `public`
  and collide). A **shared application role**
  (`correlation`/`correlation`) is used by all services (MVP simplicity); least-privilege
  per-service roles are a post-MVP hardening. Connection is by env (`<service>` sets
  `*_DB_URL`/JDBC URL + `currentSchema`/`search_path` to its own schema).
- **Migrations — per-service, schema-scoped, never global.** Each service runs its **own**
  migrations (Flyway for Java; Alembic or yoyo for Python) that touch **only its own schema**, with a
  **per-schema migration-history table** (e.g. Flyway `schemas=topology_meta`,
  `table=flyway_schema_history` *inside that schema*; yoyo/Alembic version table scoped to
  `noise_filter`).
  No service runs a global/`public`-schema migration, no shared baseline, no cross-schema DDL.
  `CREATE SCHEMA IF NOT EXISTS <svc-schema>` is each service's own first migration step (idempotent).
- **NebulaGraph — Topology only.** Only the Topology Service connects (nGQL, port 9669); it owns the
  graph SPACE + tag/edge schema and bootstraps them idempotently on startup (`CREATE SPACE/TAG/EDGE
  IF NOT EXISTS` + the `ADD HOSTS` storaged-registration step, which **polls until the storaged host
  is `Status ONLINE`** — not merely listed — before `CREATE SPACE`, per the Startup-Robustness
  Standard). No other service receives NebulaGraph credentials/endpoints.
- **Startup robustness — every service (the platform comes up reliably in a bounded window).** The
  platform MUST bring infra + every service up **consistently** and within a **predictable, bounded
  time window** from **clean volumes** (`docker compose down -v`). Every service's startup MUST: wait
  for each dependency to be **actually READY** by a true-readiness predicate (migration applied /
  Kafka reachable + topics present / NebulaGraph storaged `ONLINE` + space usable / an HTTP dep's
  `/health` 200 — never "container up" or "a row exists"); use **bounded retry with backoff and an
  explicit configurable deadline** (predictable window, not unbounded); be **self-healing** (a failed
  bootstrap is re-attempted in the background until ready or deadline — readiness **never latches DOWN
  forever** and reflects true current state); be **idempotent** (re-run = no-op); and take all
  timeouts/retries/deadline as **config from env** (no hard-coded thresholds). Each service ships a
  **clean-volume cold-start test** (real dependencies, empty volumes) that asserts readiness within
  the deadline — a mock/stub unit test cannot catch this class. Full normative detail:
  **`docs/startup-robustness-standard.md`**.
- **Kafka — explicit topic provisioning, no auto-create.** `KAFKA_AUTO_CREATE_TOPICS_ENABLE` is
  **off**. A one-shot **`kafka-init`** Compose job creates the full topic catalog (the topics listed
  under "Kafka topics" + `*.dlq`, with their partition/retention settings) **before** services
  start; services depend on it and **never create topics**. Each consumer uses a **conventioned
  consumer-group id** `"<service>-<topic>"` (e.g. `trail-builder-topology.changed`) so groups never
  collide on the shared broker. Producers are idempotent; consumers dedupe on `eventId`/`alarmId`
  (at-least-once).
- **Compose ownership.** Each service adds its **own app-container entry** to the single shared
  `docker-compose.yml` (image, env wiring to the shared infra, `depends_on` the infra +
  `kafka-init`, `/health`); it must not add or fork an infra instance. Infra services
  (postgres/nebula/kafka/kafka-init) are shared and defined once.

## Invariants
Canonical alarm-type join key: `AlarmEvent.alarmType` (mirrored on `TransactionEvent.alarms[].alarmType`)
is the **single canonical alarm-type token** that the correlation chain joins on — mining sequences,
codebook signatures, `rootCauseAlarmType`, and correlation matching all key off it. Its value space is
the Knowledge-authored, domain-scoped `alarmTypeVocabulary` (e.g. `PortDown`, `InterfaceDown`, `LinkDown`,
`FiberFault`), the same token set the propagation templates use as `trigger.alarmType`/`effect.alarmType`.
`alarmType` is **distinct from** `eventType` (X.733 category, e.g. `communicationsAlarm`) and `probableCause`
(X.733 probable cause, e.g. `lossOfSignal`), which keep their existing meanings. `AlarmEvent` producers
(the **Simulator** for synthetic alarms; the **Enrichment Service** when mapping per-source rulesets to the
canonical model) populate `alarmType` from the domain's `alarmTypeVocabulary` before publishing.
Identity binding: alarms use the same `managedObjectId` as the graph (defined in event-model).
The `managedObjectId` scheme is **domain-agnostic**: format `<objectType>:<id>` where `objectType`
is an alphanumeric token starting with a letter (`^[A-Za-z][A-Za-z0-9]*$`) and `id` is non-empty
with no colon. The **valid `objectType` set and
edge-relation vocabulary for a domain are authored in the Knowledge Service** (domain-scoped), not
frozen in the event-model — so a new domain adds its types without an event-model contract change
(see "Domain extensibility"). The Topology ingestion API validates an uploaded snapshot's
objectTypes/relations against the domain's Knowledge-authored vocabulary.
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
  structured file (JSON) describing the typed nodes and edges of the graph. It carries a `domain`
  identifier; the node `objectType`s and edge `relation`s come from **that domain's vocabulary
  authored in the Knowledge Service** (for the Core IP domain: the §"Topology Graph Model" types
  Node/LineCard/Port/**Interface**/IPLink/IGPAdjacency/LSP/VPNService/FiberSpan/SRLG and their
  edges — note **Interface** is the L3 endpoint on a Port, and IP links/adjacencies are between
  interfaces; protocol-connectivity layers beyond IGP, e.g. BGP, are a future Knowledge-vocabulary
  addition, not in MVP). Every
  object carries its `managedObjectId` in the canonical `<objectType>:<id>` scheme. The **file
  schema is a contract** (versioned; a change to its *structure* requires an `architecture.md`/spec
  update + human approval — but adding a new domain's *types/relations* does not, since those are
  Knowledge data). It is the hand-off between any topology *producer* (the Simulator today) and the
  Topology Service. Where it lives (event-model vs. a `schema/` dir) is a design decision.
  - **Site entity (domain-agnostic).** `Site` is a first-class object type in every domain,
    representing a geographic site. Devices are placed in a site via a **`LOCATED_AT`** relation
    (`<device> LOCATED_AT <Site>`); a Site node carries geo attributes (e.g. name, latitude,
    longitude, region). The web-ui visualizes topology **by site** and expands into the site's
    device-level graph; the Topology query API supports listing sites and the objects located at a
    site. Sites may nest in future (site hierarchy) — out of MVP scope.
  - **Traversal closure edges (additive, #252).** The Topology query API's bounded-traversal
    response (`GET /topology/traversal`) carries, in addition to the reached nodes, an **`edges`**
    array — the typed directed edges of the closure (every edge whose both endpoints are in
    `start` + `reached` and whose `relation` is one of the requested relations; relation-scoped).
    This lets consumers (the **Codebook Generator's** forward-propagation) walk the cascade rather
    than seeing isolated nodes. This is an **additive** field on `TraversalDto`; the frozen response
    shape is published in `services/topology/openapi.json`.
  - **Device & connection attributes (structured, extensible).** Each node and edge carries an
    `attributes` map for descriptive properties. **Well-known keys** (recommended, extensible per
    domain): on devices — `vendor`, `model`, `equipmentType`, `role`, `capacity`; on connections —
    `linkType`, `capacity`, `protectionRole`. The set is open (a domain may add keys) and authored/
    catalogued per domain in the Knowledge Service. Consumers that may use these: the **Noise
    Filter** (e.g. `equipmentType` as a clustering feature), **Trail Builder** and **Codebook
    Generator** (where policy/templates reference equipment characteristics). They are descriptive,
    not identity — identity remains `managedObjectId`.
- **Topology ingestion API (owned by the Topology Service).** The Topology Service publishes an
  OpenAPI 3.1 ingestion endpoint that accepts a topology snapshot file, lifts it into NebulaGraph,
  mints a `snapshotId`, and emits `topology.changed`. Producers (the Simulator) build their upload client
  against the Topology Service's **published OpenAPI**, never its source — same no-coupling rule as
  every other synchronous call. The endpoint is a config-switchable integration point for the
  producer (mock from Topology's OpenAPI for unit tests, real Topology in integration).

## Domain extensibility (Core IP is the MVP domain)
The platform targets the **Core IP** domain for the MVP, but is **domain-parameterized by design**
so it can expand to other network domains (fixed access, RAN, transport, …) and cross-domain cases
with minimal disruption. It does **not** force one universal schema; each domain defines its own
object/edge vocabulary while sharing the same engine, event model, and service mechanics.

- **The Simulator** separates a reusable generation/replay engine from a **domain pack** (the
  domain's object/edge types, propagation templates, alarm shapes, scenario library). The Core IP
  pack is the only one built for MVP; a new domain is a new pack — no engine rework. Domain business
  data does not leak into the shared engine.
- **The Knowledge Service** is the authoritative, domain-scoped home of each domain's **object-type
  set**, **edge-relation vocabulary**, **propagation templates**, **trail policy**, **model params**,
  and **device/connection attribute catalogue**. Adding a domain = authoring these records.

> **Invariant — rules & ontology, never operational data.** The Knowledge Service stores only the
> **abstract domain model**: the ontology (object-type / edge-relation vocabularies, fault-origin
> types, the canonical alarm-**type** vocabulary), the **rules** (propagation templates, trail policy),
> and **parameter bounds** (model-param sets) — *what kinds of things exist in a domain and how faults
> propagate*. It must **never** hold **runtime or source-specific operational data**: no concrete alarm
> instances (`alarmId`, `raisedAt`, severities of actual alarms), no device/topology inventory, no
> concrete `managedObjectId` **values** (only the `<objectType>:<id>` token-format rule), and nothing
> tied to a particular alarm **source** (a specific NMS/OSS/vendor feed or the Simulator). That
> operational data originates from the **source** (the Simulator in the MVP; a real NMS/OSS in
> production), is **adapted per-source by the Enrichment Service** (per-source rulesets → canonical
> `AlarmEvent`), materialized in **Topology** (graph instances) and the live stores — never authored
> into Knowledge. Knowledge content is identical across deployments of the same domain; only the
> source data varies by context. (The Simulator's **domain pack** carries a *generation-side copy* of a
> domain's types/templates/alarm-shapes to synthesize data — but the **authoritative** rules consumed
> for correlation live in Knowledge, and concrete generated alarms never flow back into it.)
- **The event model** is domain-agnostic: the `managedObjectId` scheme accepts any `<objectType>:<id>`
  and the `AlarmEvent` (X.733) is domain-neutral — so **no event-model contract change is needed per
  new domain**.
- **Downstream services** (Topology, Trail Builder, Codebook Generator, Noise Filter, Pattern Miner,
  Pattern Manager, Correlation Engine) operate generically on the typed graph + Knowledge-authored
  templates/policy — none hard-code Core IP specifics.

**To add a new domain (the extension guide):**
1. Author the domain's **object-type set + edge-relation vocabulary** + **propagation templates** +
   **trail policy** + **model params** + **attribute catalogue** in the **Knowledge Service**
   (domain-scoped records). `Site` + `LOCATED_AT` are domain-agnostic and reused.
2. Build a **Simulator domain pack** (types/edges/templates/alarm-shapes/scenarios) for grounded
   synthetic data — or feed real topology files for that domain.
3. Topology ingests that domain's snapshot files (validating types/relations against the domain's
   Knowledge vocabulary); everything downstream works unchanged.
No change to the event-model, topic contracts, or service code is required for a new domain — only
Knowledge data + (for synthetic data) a Simulator pack.

**Cross-domain.** Objects from different domains coexist in the graph under distinct `objectType`
namespaces and may be linked by cross-domain relations (also authored in Knowledge), enabling
cross-domain trails/correlation in future. The MVP builds and tests Core IP only; the structure
does not preclude cross-domain expansion.

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
