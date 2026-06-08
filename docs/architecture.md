# Architecture — Alarm Correlation Platform (Core IP MVP)

Shared technical context for design and build. Defines the contracts and invariants every
service must respect. (Full narrative lives in the Solution Design doc.)

## Service inventory
| Service | Cohort | Responsibility | Consumes | Produces |
|---|---|---|---|---|
| simulator | Python | synthetic topology + labeled alarms; eval oracle | — | topology.raw, alarms.history, alarms.live |
| topology | Spring Boot | sole owner of AGE graph; versioned snapshots; query API | topology.raw | topology.changed |
| knowledge | Spring Boot | authored templates/policy/params (versioned) | — | knowledge.updated |
| trail-builder | Python | policy-bounded correlation trails | topology.changed | trails.built |
| codebook-generator | Python | forward-propagation codebook | trails.built | codebook.generated |
| enrichment | Spring Boot | normalize, dedup, deterministic filter, trail-tag | alarms.history, alarms.live | alarms.enriched, alarms.enriched.live |
| noise-filter | Python | DBSCAN noise removal | alarms.enriched | transactions.clean |
| pattern-miner | Python | PrefixSpan mining only | transactions.clean | patterns.mined |
| pattern-manager | Spring Boot | Pattern Store, RCA, reconcile, XAI, lifecycle | patterns.mined, patterns.approved | patterns.discovered, patterns.approved |
| correlation-engine | Spring Boot | real-time match/score/RCA; incidents | alarms.enriched.live, patterns.approved, codebook.generated | correlation.results |
| web-ui | Angular 20 | topology/trails, pattern review, config, stats | service APIs | patterns.approved (via API) |

## Event model (the contract)
No schema registry. `libs/event-model` (versioned with the repo) defines the **envelope**
(`eventId, type, schemaVersion, occurredAt, source, traceId, payload`) and **payloads**
(AlarmEvent, TopologyChangedEvent, TrailsBuiltEvent, CodebookGeneratedEvent, TransactionEvent,
PatternMinedEvent, PatternDiscoveredEvent/PatternApprovedEvent, CorrelationResultEvent).
Two bindings from one JSON Schema: Java (Spring services) + Python/Pydantic (Python services).
Consumers reject unknown major `schemaVersion`.

## Kafka topics
topology.raw, topology.changed, trails.built, codebook.generated, knowledge.updated,
alarms.history, alarms.live, alarms.enriched, alarms.enriched.live, transactions.clean,
patterns.mined, patterns.discovered, patterns.approved, correlation.results, *.dlq.
Producers/consumers per the table. **Adding a topic is a contract change.**

## Data stores & ownership
Apache AGE — topology graph; only via Topology Service. PostgreSQL — alarm / pattern (owned
by Pattern Manager) / incident / knowledge stores; separation by schema. Kafka — the bus.

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
