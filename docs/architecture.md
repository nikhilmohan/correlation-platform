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
frozen, the services build in parallel.

## Test oracle
Simulator injects fiber-cut, line-card-fault, port-fault + ≥3 noise classes with ground-truth
`{rootCause, children}`. Integration thresholds (RCA accuracy, alarm-reduction ratio,
noise-filter effectiveness, pattern quality) are defined in `services/simulator/spec.md`.
