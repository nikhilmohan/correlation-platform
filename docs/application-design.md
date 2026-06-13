# Application Design — AI/ML Alarm Correlation Platform (Core IP MVP)

> Overall design synthesized from the 12 merged service designs + the frozen `libs/event-model`
> contract + `docs/architecture.md`. It gives a crisp per-service view, the three runtime-phase
> interaction walkthroughs (P1 topology onboarding, P2 pattern learning, P3 real-time correlation),
> and a data-integration worked example tracing one fiber-cut storyline end-to-end with exact
> payloads. The worked example was adversarially verified hop-by-hop; open integration gaps are
> tracked in [`design-gaps.md`](design-gaps.md) and resolved via gated per-service PRs.

This is a synthesis task. All the information I need is in the provided extractions, so I'll produce the two markdown sections directly.

## Section A — Per-service summaries

### 1. topology (Spring Boot / Java 17)
**Goals:** Sole owner of the network topology graph. Ingest versioned snapshot files via a published HTTP ingestion API, lift them into a typed multi-layer NebulaGraph (fully abstracted), serve a domain-scoped graph query API, and emit `topology.changed` after every successful ingest.
**Summary:** Accepts a JSON snapshot file at `POST /topology/snapshots`; validates structurally (owned `snapshot.schema.json`), semantically (managedObjectId pattern, objectType==prefix, edge-ref resolution), and against the domain's Knowledge-authored object-type/relation vocabulary; lifts records into NebulaGraph (TAG=objectType, VID=managedObjectId, EDGE=relation) stamped with domain+snapshotId; records snapshot metadata in PostgreSQL (system-of-record for current/previous pointers); mints a snapshotId; emits exactly one `topology.changed`. Serves read-only query endpoints (resolve node/layer, edge, neighbors, bounded traversal, list-by-type, sites, objects-at-site, snapshots). NebulaGraph/nGQL never exposed.
**Dependent services:**
- Consumes Kafka: none.
- Produces Kafka: `topology.changed` (TopologyChangedEvent) → consumed by trail-builder; DLQ `topology.changed.dlq`.
- Calls (API out): Knowledge Service `GET /domains/{domain}/vocabulary` (per-domain object-types + relations) at ingest, fail-closed (502) if unavailable and uncached.
- Called by (API in): trail-builder, codebook-generator, enrichment, noise-filter, pattern-manager, web-ui (graph query API); simulator (ingestion API upload).
**Startup seed data:** No business seed. Bootstraps infrastructure idempotently: NebulaSchemaBootstrap (ADD HOSTS, CREATE SPACE/TAG/EDGE/INDEX IF NOT EXISTS for the core-ip vocabulary + REBUILD, wait until usable), Flyway migrates `topology_meta.snapshot`, runs an orphan-snapshot reaper. Graph and snapshot stores start empty.

### 2. knowledge (Spring Boot / Java 17)
**Goals:** Authoritative, versioned, domain-scoped store and serving API for all authored domain knowledge (propagation templates, fault-origin types, trail policy, model params, object-type/edge-relation/alarm-type vocabularies, attribute catalogue). Sole owner; downstream reads via versioned HTTP and refreshes on `knowledge.updated`. Adding a domain is a data operation.
**Summary:** Stores all authored knowledge as one unified record model (one PostgreSQL record/record_version table pair, jsonb payloads). Eight recordTypes CRUDed/versioned through one path; each write is JSON-Schema validated plus cross-record reference-checked, mints immutable v{n}, flips is_current, then emits exactly one `knowledge.updated`. Read API serves current or pinned versions; a dedicated `GET /domains/{domain}/vocabulary` serves object-type + edge-relation sets for Topology pre-validation. Consumes nothing from Kafka; no outbound HTTP. Passive in all phases.
**Dependent services:**
- Consumes Kafka: none.
- Produces Kafka: `knowledge.updated` (KnowledgeUpdatedEvent) → consumed by any Knowledge consumer (trail-builder, codebook-generator, noise-filter; correlation-engine etc.).
- Calls (API out): none.
- Called by (API in): topology (vocabulary), trail-builder (trail-policy), codebook-generator (fault-origins, propagation-templates), noise-filter + pattern-miner + pattern-manager (model-params), correlation-engine (match/conflict params), web-ui (model-params edit), simulator (optional scenario config).
**Startup seed data:** Seeds the Core IP (domain=core-ip) pack at startup (all v1) via an idempotent seeder through the validated write path: objectTypeVocabulary, edgeRelationVocabulary, alarmTypeVocabulary, faultOriginType records (Fiber/LineCard/Port/Interface/Node), eight propagationTemplate records, trailPolicy/default, two modelParams sets (noise-filter, pattern-miner), attributeCatalogue/default. `SEED_ON_STARTUP=true` default.

### 3. enrichment (Spring Boot / Java 17)
**Goals:** First-stage, source-aware, config-driven alarm normalizer: adapt raw alarms from any NMS/vendor feed via a per-source ruleset into the canonical AlarmEvent, run a deterministic filter pipeline (dedup, self-clear, flap-damp, known-chatter), trail-tag survivors, emit canonical AlarmEvents on history and live paths.
**Summary:** Spring-kafka stream processor (no Kafka Streams). Consumes raw alarms from `alarms.history` (P2) and `alarms.live` (P3); selects a per-source ruleset by envelope `source` (default fallback); applies field mapping to build a canonical AlarmEvent; runs fixed-order pipeline (ruleset-select → Normalize → Dedup → SelfClear → FlapDamp → Chatter → TrailTag → Emit); trail-tags survivors via Trail Builder getTrailsForObject; emits to `alarms.enriched` (history) or `alarms.enriched.live` (live). Only service Active in two phases. Per-source rulesets are its OWN mounted-YAML config (not Knowledge). No HTTP business API.
**Dependent services:**
- Consumes Kafka: `alarms.history` (AlarmEvent, from simulator), `alarms.live` (AlarmEvent, from simulator).
- Produces Kafka: `alarms.enriched` (AlarmEvent) → noise-filter; `alarms.enriched.live` (AlarmEvent) → alarm-manager; DLQs `alarms.history.dlq`, `alarms.live.dlq` (raw bytes).
- Calls (API out): Trail Builder `getTrailsForObject(managedObjectId)` → trailIds[] (Resilience4j retry+circuit-breaker; retry-then-DLQ on outage).
- Called by (API in): none (only Actuator health/metrics).
**Startup seed data:** None. RulesetConfigLoader loads per-source rulesets from mounted YAML (`ENRICHMENT_RULESETS_FILE`, default `/config/rulesets.yaml`); file MUST contain a `default` ruleset or readiness fails. No records written.

### 4. trail-builder (Python 3.13 / FastAPI)
**Goals:** Build overlapping, policy-bounded correlation trails from the Topology graph + Knowledge trail policy (IGP-area-bounded transitive closure over dependency edges, SRLG co-members unioned). Persist trails (members + snapshotId + domain), serve trail-membership queries, emit `trails.built`.
**Summary:** FastAPI + confluent-kafka + networkx + PostgreSQL. Consumes `topology.changed` to trigger a domain-scoped build: reads domain from the event, fetches the domain's trail policy from Knowledge and the graph slice from Topology's query API, computes IGP-area-bounded closures over the policy edge set (incl. HOSTS Port→Interface, TERMINATES Interface→IPLink) and unions SRLG co-members, persists trail/trail_member rows tagged with snapshotId+domain, emits `trails.built`. Also consumes `knowledge.updated` (recordType==trailPolicy) as a policy-refresh trigger only. Serves getTrailsForObject/getTrail/listTrails + internal `POST /trails/rebuild`. Never touches graph DB directly.
**Dependent services:**
- Consumes Kafka: `topology.changed` (TopologyChangedEvent, from topology), `knowledge.updated` (KnowledgeUpdatedEvent, refresh trigger).
- Produces Kafka: `trails.built` (TrailsBuiltEvent) → codebook-generator; DLQ `topology.changed.dlq`.
- Calls (API out): Topology Service (nodes-by-type, neighbors, bounded traversal); Knowledge Service (trail-policy read).
- Called by (API in): codebook-generator, enrichment, noise-filter, pattern-miner (indirectly), web-ui (trail viz).
**Startup seed data:** None — owns no seed data; graph slice from Topology, policy from Knowledge. Tests use fixtures.

### 5. codebook-generator (Python 3.13 / FastAPI)
**Goals:** Compile the codebook — a matrix mapping each candidate root-cause instance (fault origin) to its predicted symptom signature, trail-tagged and domain-scoped — for a snapshot, and serve it via a read-only query API. Model-compilation/serving only, not live RCA.
**Summary:** On `trails.built` (snapshotId, trailIds, domain), resolves domain, fetches domain-scoped fault-origin types + propagation templates from Knowledge, enumerates fault-origin instances (Fiber/LineCard/Port/Interface/Node) from Topology scoped to snapshotId+domain, fetches each instance's bounded closure, runs templates forward via networkx typed-edge BFS to build an ordered predicted symptom signature (origin alarm first), tags each scenario with trailIds via Trail Builder, persists one codebook (fresh codebookId) + N scenarios, sets it the single active codebook for its (domain, snapshotId), emits `codebook.generated`. Full signatures served only via its FastAPI query API.
**Dependent services:**
- Consumes Kafka: `trails.built` (TrailsBuiltEvent, from trail-builder), `knowledge.updated` (cache invalidation only).
- Produces Kafka: `codebook.generated` (CodebookGeneratedEvent) → correlation-engine.
- Calls (API out): Topology (list objects by type, bounded traverse); Knowledge (fault-origins, propagation-templates); Trail Builder (getTrailsForObject, getTrail).
- Called by (API in): pattern-manager (reconciliation), correlation-engine (full signatures for decode).
**Startup seed data:** None. (Core IP closure exists only as a unit-test fixture.)

### 6. simulator (Python / networkx + confluent-kafka + FastAPI)
**Goals:** Generate a domain-grounded synthetic Core IP topology and labeled alarm streams (historical batch + live paced), replay onto Kafka, and serve as the evaluation oracle by persisting ground-truth {rootCause, children} labels per scenario. Source of all synthetic data; owns the integration-threshold definitions.
**Summary:** Generation/replay engine (domain-agnostic engine + swappable Core IP domain pack). P1: builds a typed multi-layer topology into a versioned JSON snapshot file and uploads it to the Topology ingestion API (mock or real, no Kafka). P2: synthesizes a labeled corpus and batch-replays AlarmEvents onto `alarms.history` over a history window. P3: replays the same labeled stream onto `alarms.live` wall-clock paced. Pure Kafka producer (no consumers, no DLQ). Exposes read-only `/labels` ground-truth API + health/metrics. Deterministic with SIM_SEED; fresh eventId/alarmId per run.
**Dependent services:**
- Consumes Kafka: none.
- Produces Kafka: `alarms.history` (AlarmEvent) → enrichment; `alarms.live` (AlarmEvent) → enrichment.
- Calls (API out): Topology ingestion API (`POST` snapshot file upload, mock|real); Knowledge (optional scenario config, local|real, default local).
- Called by (API in): integration harness / web-ui (`GET /labels`, `/scenarios`).
**Startup seed data:** No persistent store. Loads (does not author) scenario/jitter/noise/threshold config from local files or Knowledge; constructs in-process Core IP domain pack (vocabulary, templates, X.733 shapes, attribute catalogue, scenario library: fiber-cut, line-card-fault, port-fault, interface-fault + ≥3 noise classes). Topology graph and label store generated fresh per run.

### 7. noise-filter (Python 3.13)
**Goals:** Statistically clean Phase-2 (history-path) enriched alarms by collapsing post-dedup storms from single propagating faults into ONE clean TransactionEvent per storm via per-trail-window DBSCAN, dropping coincidental noise. Storm reduction primary; outlier removal secondary with retention bias.
**Summary:** Consumes `alarms.enriched` (AlarmEvent), partitions per trailId into tumbling time windows, feature-vectorizes each alarm (relative timestamp, object-type layer, eventType, severity, optional config-gated Topology attribute dims + optional Trail Builder hop-distance dim), runs DBSCAN (eps/minSamples from Knowledge; hdbscan selectable) per trail-window, drops noise points, emits each dense storm cluster as ONE `TransactionEvent` (alarmIds[] + typed alarms[]) on `transactions.clean`. Records aggregate run-stats per window to PostgreSQL; serves a read-only run-stats API. Refreshes params on `knowledge.updated`. Idle in P1/P3 (pipeline).
**Dependent services:**
- Consumes Kafka: `alarms.enriched` (AlarmEvent, from enrichment), `knowledge.updated` (KnowledgeUpdatedEvent).
- Produces Kafka: `transactions.clean` (TransactionEvent) → pattern-miner; DLQ `alarms.enriched.dlq`.
- Calls (API out): Knowledge (model-params + feature-config); Topology (`GET /topology/nodes/{moId}`, only if attribute feature on); Trail Builder (getTrail, only if hop-distance feature on or for snapshotId provenance).
- Called by (API in): web-ui (`GET /api/v1/run-stats`).
**Startup seed data:** None — runs yoyo migration `0001_run_stats.sql` to create empty `nf_run_stats`, loads Knowledge params (refuses readiness until loaded). Tests use synthetic AlarmEvent fixtures.

### 8. pattern-miner (Python 3.13 / PySpark, container-only)
**Goals:** ML-execution-only for P2: apply a dynamic tempo-adaptive session window per trail to DBSCAN-cleaned transactions, then run PrefixSpan (Spark MLlib) to discover frequent ordered alarm-type sequences with support/confidence/lift + provenance. Holds no pattern state (no RCA, reconciliation, XAI, lifecycle, patternId, Pattern Store).
**Summary:** Stateless container-only PySpark batch job. Consumes `TransactionEvent` from `transactions.clean`, dedupes on envelope eventId, fetches mining/windowing params from Knowledge (no hard-coded thresholds), pools each trail's ordered typed alarms[] and splits into activity sessions via a hybrid adaptive closing gap (data-driven percentile clamped by tempo-class floors/ceiling, baseGap fallback), runs PrefixSpan over per-session `alarmType` sequences (the canonical join tokens, not `eventType`), computes support/confidence/lift, emits one `PatternMinedEvent` per discovered sequence. Reads per-alarm `alarmType`/`raisedAt` from in-band typed alarms[]. Owns no datastore. Idle in P1/P3.
**Dependent services:**
- Consumes Kafka: `transactions.clean` (TransactionEvent, from noise-filter).
- Produces Kafka: `patterns.mined` (PatternMinedEvent) → pattern-manager; DLQ `transactions.clean.dlq`.
- Calls (API out): Knowledge (mining-params, mock|real).
- Called by (API in): none (only health/metrics).
**Startup seed data:** None — generates no corpus. Tests use synthetic TransactionEvent batches with inline typed alarms[].

### 9. pattern-manager (Spring Boot / Java 17)
**Goals:** Single owner of the full pattern domain: turn raw mined sequences into governed, reviewable patterns (RCA, structural validation, codebook reconciliation, XAI, derived per-pattern session-window), persist as draft, drive human-approval lifecycle, and be sole emitter of PatternDiscoveredEvent and PatternApprovedEvent. Contains no ML.
**Summary:** Consumes `patterns.mined`, enriches each pattern via RCA (graph-ordering + codebook override), structural validation (connected-dependency-path check), codebook reconciliation, XAI assembly, and deterministic session-window derivation (windowMs + type, from mined timing only — no Knowledge input), upserts to the PostgreSQL Pattern Store as lifecycle=draft, emits one `PatternDiscoveredEvent` per draft. Exposes read API + approve/deprecate/edit endpoints; on approval transitions draft→approved and emits `PatternApprovedEvent` (sole producer). Idempotent on eventId + deterministic UUIDv5 patternId.
**Dependent services:**
- Consumes Kafka: `patterns.mined` (PatternMinedEvent, from pattern-miner).
- Produces Kafka: `patterns.discovered` (PatternDiscoveredEvent) → web-ui (review); `patterns.approved` (PatternApprovedEvent) → correlation-engine (and re-consumed per inventory); DLQ `patterns.mined.dlq`.
- Calls (API out): Topology (`GET /topology/nodes/{moId}`, `GET /topology/traversal` for RCA + structural validation); Codebook Generator (codebooks + scenarios for reconciliation/override); Knowledge (RCA/reconciliation + structural-validation params).
- Called by (API in): web-ui (review/approve/edit/deprecate); correlation-engine (`GET /patterns?lifecycle=approved` bootstrap).
**Startup seed data:** None — Pattern Store starts empty. Flyway-migrated. Test fixtures only.

### 10. correlation-engine (Spring Boot / Kafka Streams)
**Goals:** Real-time correlation core and system of record for incidents (P3). Consume live persisted alarms and correlate them into incidents with a tagged root cause via correlation instances — one per (trailId, patternId) — against approved patterns and the latest in-scope codebook; emit results and serve an incident/stats read API.
**Summary:** Kafka Streams (Processor API). Each alarm on `alarms.persisted.live` is validated, deduped on alarmId, fanned out per trailId. Per (trailId, patternId) it lazily creates a correlation instance on the first opening alarm, admits relevant alarms (firing AlarmStatusChange in-progress), and either fully matches — tagging root cause + children, persisting an incident, emitting `CorrelationResultEvent` + AlarmStatusChange(correlated), destroying the instance — or expires on the pattern's session window (AlarmStatusChange reverted-open). Codebook decode is a fallback feeding the same specificity-then-confidence conflict resolver. Instance state in RocksDB+changelog; incidents in PostgreSQL. Thresholds from Knowledge; session window from the pattern's sessionWindow.
**Dependent services:**
- Consumes Kafka: `alarms.persisted.live` (AlarmEvent, from alarm-manager), `patterns.approved` (PatternApprovedEvent, from pattern-manager), `codebook.generated` (CodebookGeneratedEvent, from codebook-generator).
- Produces Kafka: `correlation.results` (CorrelationResultEvent) → alarm-manager; `alarms.status.changed` (AlarmStatusChange) → alarm-manager; DLQs `alarms.persisted.live.dlq`, `patterns.approved.dlq`, `codebook.generated.dlq`.
- Calls (API out): Pattern Manager (`GET /patterns?lifecycle=approved` bootstrap); Codebook Generator (per-trail scenario signatures by codebookId); Knowledge (match-quality + conflict params, NOT session-window).
- Called by (API in): web-ui (`GET /incidents`, `GET /incidents/{id}`, `GET /stats`).
**Startup seed data:** Incident Store starts empty. At startup bootstraps in-memory PatternStore from Pattern Manager and warms codebook from `codebook.generated`; loaded model state, not seeded records.

### 11. alarm-manager (Spring Boot / Java 17)
**Goals:** Sole owner of live alarm state: persist each live enriched alarm, republish it for the Correlation Engine, keep its lifecycle STATE and correlation-group ROLE+incident linkage in sync. Serve the live alarm query API to web-ui. Live-only, no historical corpus in MVP.
**Summary:** Sits in-line between Enrichment and Correlation Engine on the P3 path. Consumes `alarms.enriched.live` (AlarmEvent), upserts each into the PostgreSQL live alarm store with initial state 'open' (idempotent on alarmId), and republishes the SAME AlarmEvent on `alarms.persisted.live` (republish-once via a published flag). Consumes `alarms.status.changed` as the canonical STATE channel (applies newStatus to lifecycle_state, deduped on eventId) and `correlation.results` as the canonical ROLE+incident channel (sets role + incidentId, deduped on eventId). STATE and ROLE write to disjoint columns, reconciling on alarmId order-independently. Wire-state 'cleared' AlarmEvents drive a clear transition. Append-only state_transition audit. Serves `GET /alarms` + `GET /alarms/{alarmId}`.
**Dependent services:**
- Consumes Kafka: `alarms.enriched.live` (AlarmEvent, from enrichment), `alarms.status.changed` (AlarmStatusChange, from correlation-engine / any service), `correlation.results` (CorrelationResultEvent, from correlation-engine).
- Produces Kafka: `alarms.persisted.live` (AlarmEvent) → correlation-engine; DLQs `alarms.enriched.live.dlq`, `alarms.status.changed.dlq`, `correlation.results.dlq`.
- Calls (API out): none.
- Called by (API in): web-ui (`GET /alarms`, `GET /alarms/{alarmId}`).
**Startup seed data:** None — derives all state from consumed messages. Flyway applies alarm / state_transition / processed_event migrations; seeds no records.

### 12. web-ui (Angular 20 SPA / nginx)
**Goals:** Single-pane-of-glass UI for NOC operators: visualize topology/trails, review and approve discovered patterns with XAI, edit Knowledge model params, monitor real-time alarm correlation (live incidents, streaming, incident-detail, noise-filter stats). Stateless SPA reading only published REST APIs; never touches Kafka or any datastore.
**Summary:** Static Angular 20 SPA (standalone components, signals, OnPush) served by nginx. Eight routes (/dashboard, /streaming, /topology, /topology/:siteId, /patterns, /incidents/:incidentId, /config, /stats). Talks to nine config-switchable (mock|real) integration points across seven backend services, with typed clients from each producer's OpenAPI. "Real-time" is client-side polling of `GET /alarms` + `GET /incidents` (STREAMING_REFRESH_INTERVAL_MS, default 3000ms) with a keyed delta-diff. No BFF, no Kafka, no published OpenAPI; liveness is HTTP 200 on `/`.
**Dependent services:**
- Consumes Kafka: none. Produces Kafka: none.
- Calls (API out): Topology (sites, objects-at-site, nodes, neighbors); Trail Builder (listTrails, getTrail, getTrailsForObject); Pattern Manager (GET patterns, GET pattern, POST approve, PATCH edit); Knowledge (GET/PUT model-params); Correlation Engine (GET incidents, GET incident, GET stats); Alarm Manager (GET alarms, GET alarm); Noise Filter (GET run-stats).
- Called by (API in): none (operators only).
**Startup seed data:** None (no datastore). Ships Vitest/TestBed fixtures generated from each producer's OpenAPI, used only as MSW mock responses for tests.

## Section B — Three-phase interaction walkthroughs

## PHASE P1 — Topology Onboarding (offline)

**Phase map:** Active — simulator, topology, trail-builder, codebook-generator, web-ui. Passive — knowledge. Idle — enrichment, noise-filter, pattern-miner, pattern-manager, correlation-engine, alarm-manager.

**Use case:** Ingest a topology snapshot, build trails, compile the codebook, and visualize topology/trails in the UI.

1. **simulator** builds a typed multi-layer Core IP topology (Site/Node/LineCard/Port/Interface/IPLink/IGPAdjacency/LSP/VPNService/FiberSpan/SRLG) and writes a versioned snapshot file `snapshot-<runId>.json` (nodes[{managedObjectId,objectType,attributes}], edges[{from,to,relation,attributes}], domain=core-ip). It does NOT emit to Kafka in P1.
2. **simulator → topology (API):** simulator uploads the file via `POST /topology/snapshots` (mock|real). Topology returns `{snapshotId, domain, changeType, nodeCount, edgeCount, status}`.
3. **topology → knowledge (API):** during ingest, topology calls Knowledge `GET /domains/core-ip/vocabulary` to fetch the authored objectType + edge-relation sets, and validates the uploaded snapshot's tokens against them. **knowledge** is Passive here, serving vocabulary (and trailPolicy/faultOriginType/propagationTemplate/attributeCatalogue to later consumers). If vocabulary is unavailable and uncached, topology fails closed (422/502, no write, no event).
4. **topology** lifts validated records into NebulaGraph (TAG=objectType, VID=managedObjectId, EDGE=relation, stamped domain+snapshotId), records snapshot metadata in PostgreSQL `topology_meta.snapshot` (cuts current/previous pointers), and emits exactly one `topology.changed` (TopologyChangedEvent{snapshotId, domain, changeType, nodes[], edges[]}); DLQ `topology.changed.dlq` on emit failure.
5. **topology.changed → trail-builder (Kafka):** trail-builder consumes `topology.changed`, reads `domain` directly from the event (defaults core-ip if absent), and triggers a domain-scoped build. It is idempotent on envelope eventId.
6. **trail-builder → knowledge (API):** fetches the domain's `trailPolicy` record (dependencyEdges, igpAreaKey, srlgRule).
7. **trail-builder → topology (API):** fetches the graph slice via Topology query endpoints (`GET /topology/nodes?objectType=`, `GET /topology/nodes/{moId}/neighbors`, `GET /topology/traversal?start=&relation=&maxDepth=` over the dependency-edge vocabulary incl. HOSTS/TERMINATES so Interface objects appear).
8. **trail-builder** computes overlapping IGP-area-bounded transitive closures, unions SRLG co-members, persists `trail` + `trail_member` rows tagged snapshotId+domain, and emits `trails.built` (TrailsBuiltEvent{snapshotId, domain, trailIds[], trailCount}); summary only — full membership via its query API.
9. **trails.built → codebook-generator (Kafka):** codebook-generator consumes `trails.built`, resolves domain from the event.
10. **codebook-generator → knowledge (API):** fetches domain-scoped `fault-origins` (Fiber/LineCard/Port/Interface/Node) and `propagation-templates` (per-edge cascade rules naming effect alarm types).
11. **codebook-generator → topology (API):** enumerates fault-origin instances scoped to snapshotId+domain (list-objects-by-type) and fetches each instance's bounded closure (traverse by edge type).
12. **codebook-generator → trail-builder (API):** calls `getTrailsForObject(managedObjectId)` to tag each scenario with trailIds[].
13. **codebook-generator** runs templates forward (networkx typed-edge BFS) to build ordered predicted symptom signatures (origin alarm first), persists one codebook (fresh codebookId) + N scenarios, sets it the single active codebook for (domain, snapshotId), and emits `codebook.generated` (CodebookGeneratedEvent{snapshotId, domain, scenarioCount, codebookId}).
14. **codebook.generated → correlation-engine (Kafka):** correlation-engine may receive this early to warm its CodebookStore (state-warming only; no correlation work in P1).
15. **web-ui ↔ backends (API, P1 viz):** web-ui (Active) renders the topology/trails views:
    - Topology `GET /topology/sites` → geo-site map; `GET /topology/sites/{siteId}/objects` → site device graph; `GET /topology/nodes/{moId}` + `/neighbors` → object/layer detail + attribute panel.
    - Trail Builder `GET /trails?snapshotId=&domain=core-ip` (listTrails), `GET /trails/{trailId}` (getTrail), `GET /trails/by-object?managedObjectId=` (getTrailsForObject) → trail overlays.
    - Dashboard shows topology KPIs. No writes in P1.

End state: a versioned snapshot in NebulaGraph + PostgreSQL, persisted trails, an active codebook for (core-ip, snapshotId), and a navigable topology/trail UI.

## PHASE P2 — Pattern Learning (offline)

**Phase map:** Active — simulator, enrichment, noise-filter, pattern-miner, pattern-manager, web-ui. Passive — topology, knowledge, trail-builder, codebook-generator. Idle — correlation-engine, alarm-manager.

**Use case:** Replay a labeled history corpus, clean it, mine sequences, govern/approve patterns via the UI.

1. **simulator** synthesizes a labeled corpus (each scenario × ~8 instances + ~30% background + ~20% noise), writes the ground-truth label export `labels-<runId>.jsonl`, and batch-replays canonical AlarmEvents onto `alarms.history` (envelope source=simulator, raw/source-formatted) spread over a history window.
2. **alarms.history → enrichment (Kafka):** enrichment (Active) consumes raw AlarmEvents, selects a per-source ruleset by envelope `source` (default fallback), normalizes to canonical AlarmEvent (managedObjectId, eventType, probableCause via maps, severity, etc.).
3. **enrichment → trail-builder (API):** for each survivor, enrichment calls `getTrailsForObject(managedObjectId)` to populate `trailIds[]` (Trail Builder is Passive, serving trail membership). On Trail Builder outage, after bounded retries the alarm routes to `alarms.history.dlq` (never emitted trail-less).
4. **enrichment** runs the deterministic pipeline (Dedup → SelfClear → FlapDamp → Chatter → TrailTag), then emits canonical AlarmEvents (envelope source overwritten to `enrichment`) to `alarms.enriched`.
5. **alarms.enriched → noise-filter (Kafka):** noise-filter (Active) consumes AlarmEvents, partitions per trailId into tumbling windows.
6. **noise-filter → knowledge (API):** fetches DBSCAN params (eps, minSamples, windowSize, algorithm) + feature config; refreshes on `knowledge.updated`. (knowledge Passive, serving modelParams.)
7. **noise-filter → topology / trail-builder (API, conditional):** if attribute features enabled, `GET /topology/nodes/{moId}` for attributes; for snapshotId provenance (and hop-distance feature) calls Trail Builder `getTrail(trailId)`. snapshotId on the emitted TransactionEvent is derived here.
8. **noise-filter** runs DBSCAN per trail-window, drops noise points, and emits each dense storm cluster as ONE `TransactionEvent` (transactionId, trailId, snapshotId, alarmIds[] + typed alarms[{alarmId,alarmType,eventType,raisedAt,managedObjectId,perceivedSeverity}], windowStart/End, optional domain) on `transactions.clean`. It also writes an aggregate `nf_run_stats` row per finalized window. Poison → `alarms.enriched.dlq`.
9. **transactions.clean → pattern-miner (Kafka):** pattern-miner (Active) consumes TransactionEvents, dedupes on envelope eventId.
10. **pattern-miner → knowledge (API):** fetches mining-params (minSupport, maxPatternLength, WindowingParams tempo profiles, maxSequenceCount, codebookVersion). Fails fast if unavailable (offsets not advanced, replay later).
11. **pattern-miner** pools each trail's ordered typed alarms[], splits into activity sessions via the hybrid adaptive closing gap, runs PrefixSpan over per-session `alarmType` sequences (canonical join tokens, not `eventType`), computes support/confidence/lift, and emits one `PatternMinedEvent` per sequence (sequence[], support, confidence, lift, trailId, timing, provenance{sourceWindowId, snapshotId, codebookVersion, domain}) on `patterns.mined`. No RCA/patternId/lifecycle. Poison → `transactions.clean.dlq`.
12. **patterns.mined → pattern-manager (Kafka):** pattern-manager (Active) consumes PatternMinedEvents, dedupes on eventId, computes deterministic UUIDv5 patternId.
13. **pattern-manager → topology / codebook-generator / knowledge (API):** RCA via Topology `GET /topology/nodes/{moId}` + `GET /topology/traversal` (graph ordering) and structural validation (connectivity from RCA root within maxHops); codebook reconciliation via Codebook Generator `GET /codebooks?domain=` + `GET /codebooks/{codebookId}/scenarios` (an overlapping scenario's scenarioId becomes codebookMatchId and its root cause overrides graph RCA); RCA/reconciliation + structural params from Knowledge. (topology, codebook-generator, knowledge all Passive.)
14. **pattern-manager** derives the per-pattern sessionWindow{windowMs, type} purely from mined timing (no Knowledge call), assembles XAI, upserts to the Pattern Store as lifecycle=draft, and emits one `PatternDiscoveredEvent` (patternId, sequence[], rootCauseAlarmType, support/confidence/lift, timing, sessionWindow, codebookMatchId?, lifecycle=draft) on `patterns.discovered`.
15. **patterns.discovered → web-ui (Kafka→review):** the event drives the review queue. web-ui (Active) actually reads patterns over REST:
    - Pattern Manager `GET /patterns?lifecycle=draft` (list) and `GET /patterns/{patternId}` (full XAI, supportingInstances, structurallyValidated, sessionWindow).
16. **web-ui → pattern-manager (API write):** operator approves/rejects via `POST /patterns/{patternId}/approve {decision, reviewer, notes}` and may edit draft via `PATCH /patterns/{patternId} {optionalAlarms/sequenceFlags, reviewer, notes}`.
17. **pattern-manager → patterns.approved (Kafka):** on an approve decision, pattern-manager (sole producer) transitions draft→approved and emits `PatternApprovedEvent` (patternId, sequence[], rootCauseAlarmType, support/confidence/lift, timing, sessionWindow byte-identical to the discovered event, codebookMatchId?, lifecycle=approved) on `patterns.approved`. **correlation-engine** may consume this early to warm its in-memory PatternStore (no correlation in P2).
18. **web-ui → knowledge / noise-filter (API, learning sub-view):** operator edits model params via Knowledge `PUT/PATCH /knowledge/model-params` (Knowledge emits `knowledge.updated`, re-consumed by noise-filter/pattern-miner to hot-swap params); web-ui also reads Noise Filter `GET /api/v1/run-stats` for storm-reduction stats.

End state: a governed Pattern Store with approved patterns (each carrying sessionWindow), `patterns.approved` published, and an active codebook — the model needed for real-time correlation.

## PHASE P3 — Real-time Correlation (online)

**Phase map:** Active — simulator, enrichment, correlation-engine, alarm-manager, web-ui. Passive — knowledge, trail-builder, codebook-generator, pattern-manager. Idle — topology, noise-filter, pattern-miner.

**Use case:** Replay live alarms wall-clock paced, persist them, correlate into incidents with tagged root cause, drive lifecycle/role, and show live incidents + streaming in the UI.

1. **simulator** synthesizes the same labeled stream and replays canonical AlarmEvents onto `alarms.live` wall-clock paced (PACING_MULTIPLIER); writes the label export (oracle for offline RCA-accuracy evaluation).
2. **alarms.live → enrichment (Kafka):** enrichment (Active, same instance/pipeline as P2) consumes raw live AlarmEvents, selects per-source ruleset, normalizes, runs the deterministic filter pipeline.
3. **enrichment → trail-builder (API):** `getTrailsForObject(managedObjectId)` populates `trailIds[]` (Trail Builder Passive). Outage → `alarms.live.dlq`.
4. **enrichment** emits canonical AlarmEvents (source=enrichment) to `alarms.enriched.live`.
5. **alarms.enriched.live → alarm-manager (Kafka):** alarm-manager (Active) consumes each AlarmEvent, upserts into its PostgreSQL live alarm store with initial lifecycle_state='open' (idempotent on alarmId), and records the raw envelope.
6. **alarm-manager → alarms.persisted.live (Kafka):** alarm-manager republishes the SAME AlarmEvent (faithfully re-serialized, republish-once via the published flag) on `alarms.persisted.live` — the in-line persist-before-correlate hand-off.
7. **alarms.persisted.live → correlation-engine (Kafka):** correlation-engine (Active) consumes the persisted AlarmEvent, validates, dedupes on alarmId, fans out per trailId. (It consumes persisted.live, NOT enriched.live.)
8. **correlation-engine startup/refresh (API, Passive collaborators):** at startup it bootstraps approved patterns from Pattern Manager `GET /patterns?lifecycle=approved` (each with sequence[], rootCauseAlarmType, trailId, sessionWindow{windowMs,type}, codebookMatchId?), warms the codebook from `codebook.generated` and fetches per-trail scenario signatures from Codebook Generator, and fetches match-quality + conflict params from Knowledge (`partialMatchTolerance`, codebook penalties/floor, conflictWeights — NOT session-window). pattern-manager, codebook-generator, knowledge are Passive.
9. **correlation-engine** per (trailId, patternId): lazily creates a correlation instance on the first opening-condition alarm and, on admitting each relevant alarm, emits `AlarmStatusChange{alarmId, newStatus=in-progress, source=correlation-engine, changedAt}` on `alarms.status.changed`.
10. **correlation-engine → outcomes:**
    - **Full match:** tag root cause + children, persist an incident to PostgreSQL (persist-then-emit), emit `CorrelationResultEvent{incidentId, rootCauseAlarmId, childAlarmIds[], matchedPatternId?, matchedCodebookId?, confidence, trailId}` on `correlation.results`, emit `AlarmStatusChange(newStatus=correlated)` per root-cause+child on `alarms.status.changed`, then destroy the instance. (Codebook decode is a fallback feeding the same specificity-then-confidence resolver; match_type discriminates pattern vs codebook.)
    - **Session expiry:** destroy the instance, no incident, emit `AlarmStatusChange(newStatus=reverted-open)` per accumulated alarm.
11. **correlation.results → alarm-manager (Kafka):** alarm-manager consumes CorrelationResultEvent as the canonical ROLE+incident channel — sets role (root-cause/child) and incidentId on affected alarms by alarmId (deduped on eventId).
12. **alarms.status.changed → alarm-manager (Kafka):** alarm-manager consumes AlarmStatusChange as the canonical STATE channel — applies newStatus (open→in-progress→correlated→cleared / reverted-open) to lifecycle_state (deduped on eventId). STATE and ROLE write to disjoint columns, reconciling on alarmId order-independently; every transition is recorded in the append-only state_transition audit. (A 'cleared' wire-state AlarmEvent also drives a clear transition.)
13. **web-ui ↔ backends (API, P3 live ops):** web-ui (Active) shows live operation, polling on STREAMING_REFRESH_INTERVAL_MS (default 3000ms):
    - Correlation Engine `GET /incidents?trailId&from&to&matchType` (dashboard/streaming/stats), `GET /incidents/{incidentId}` (incident-detail: rootCauseAlarmId, childAlarmIds[], matchedPatternId/matchedCodebookId, confidence, trailId), `GET /stats` (raw counts; UI derives alarmReductionRatio = totalAlarmsProcessed/totalIncidentsCreated; RCA accuracy shown "evaluated offline").
    - Alarm Manager `GET /alarms?state=open|in-progress|correlated|cleared&trailId&incidentId&from&to` (streaming + lifecycle view) and `GET /alarms/{alarmId}` (full record + ordered transitions per member alarm).
    - Pattern Manager `GET /patterns?lifecycle=approved` (active-patterns count). No writes in P3.

End state: live alarms persisted, correlated into incidents with tagged root cause + children, lifecycle/role kept in sync by alarm-manager, and a live single-pane-of-glass UI; the simulator's labels serve as the offline RCA-accuracy oracle for the integration harness.
This is a documentation task — produce a worked example section. No code execution needed; I have the complete contract surface. Let me write Section C directly.

## Section C — Data-integration worked example

**Storyline:** A single fiber span fails between two Core IP nodes. We trace the SAME `snapshotId` (`snap-001`), `domain` (`core-ip`), trail (`trail-7a3f`), managedObjectIds (`FiberSpan:fs-12`, `IPLink:l-101`, `Port:p-2`, `Interface:i-9`, `Node:n-A`, `Site:s-DC1`) and alarmIds across all three phases. Every JSON below uses the exact field names from the frozen `libs/event-model` schemas + each producing service's design. The point is to make any integration mismatch visible at the hop where it would occur.

Canonical identity set (reused everywhere):

| moId | objectType | role in story |
|---|---|---|
| `Site:s-DC1` | Site | location of the failing node |
| `Node:n-A` | Node | router carrying the link |
| `Port:p-2` | Port | physical port on n-A |
| `Interface:i-9` | Interface | L3 interface hosted on Port:p-2 |
| `IPLink:l-101` | IPLink | link terminated by Interface:i-9 |
| `FiberSpan:fs-12` | FiberSpan | the cut fiber (root cause object) |

---

## P1 — Topology Onboarding (offline)

### 1.1 Simulator → Topology ingestion API (file upload, NOT Kafka)

`POST /topology/snapshots` request body (the versioned snapshot file; schema = `services/topology/schema/snapshot.schema.json`, lockstep with `services/simulator/schema/topology-snapshot.schema.json`). `objectType`/`relation` tokens are validated against the `core-ip` Knowledge vocabulary.

```json
{
  "schemaVersion": 1,
  "domain": "core-ip",
  "nodes": [
    { "managedObjectId": "Site:s-DC1",     "objectType": "Site",      "attributes": { "name": "DC1", "latitude": 37.3861, "longitude": -122.0839, "region": "us-west" } },
    { "managedObjectId": "Node:n-A",       "objectType": "Node",      "attributes": { "vendor": "acme", "model": "XR-9", "equipmentType": "router", "role": "core" } },
    { "managedObjectId": "Port:p-2",       "objectType": "Port",      "attributes": { "capacity": "100G" } },
    { "managedObjectId": "Interface:i-9",  "objectType": "Interface", "attributes": {} },
    { "managedObjectId": "IPLink:l-101",   "objectType": "IPLink",    "attributes": { "linkType": "backbone", "capacity": "100G", "protectionRole": "working" } },
    { "managedObjectId": "FiberSpan:fs-12","objectType": "FiberSpan", "attributes": { "linkType": "fiber" } }
  ],
  "edges": [
    { "from": "Node:n-A",      "to": "Site:s-DC1",     "relation": "LOCATED_AT", "attributes": {} },
    { "from": "Port:p-2",      "to": "Node:n-A",       "relation": "HOSTED_ON",  "attributes": {} },
    { "from": "Port:p-2",      "to": "Interface:i-9",  "relation": "HOSTS",      "attributes": {} },
    { "from": "Interface:i-9", "to": "IPLink:l-101",   "relation": "TERMINATES", "attributes": {} },
    { "from": "IPLink:l-101",  "to": "FiberSpan:fs-12","relation": "RIDES_ON",   "attributes": {} }
  ]
}
```

Topology lifts this into NebulaGraph (TAG=objectType, VID=managedObjectId, EDGE=relation), records snapshot metadata in PostgreSQL `topology_meta.snapshot`, mints the `snapshotId`, and returns:

```json
{
  "snapshotId": "snap-001",
  "domain": "core-ip",
  "changeType": "full-load",
  "nodeCount": 6,
  "edgeCount": 5,
  "status": "current"
}
```

### 1.2 Topology → `topology.changed` (TopologyChangedEvent) [topic: `topology.changed`]

Envelope wraps the payload; `type` discriminates. Payload `nodes[]`/`edges[]` are `additionalProperties:true`, so Topology's descriptor field set (incl. `domain`, `name?`, `attributes`) is allowed.

```json
{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "type": "TopologyChangedEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T09:00:00Z",
  "source": "topology",
  "traceId": "trace-p1-001",
  "payload": {
    "snapshotId": "snap-001",
    "domain": "core-ip",
    "changeType": "full-load",
    "nodes": [
      { "managedObjectId": "Site:s-DC1",     "objectType": "Site",      "domain": "core-ip", "name": "DC1", "attributes": { "latitude": 37.3861, "longitude": -122.0839, "region": "us-west" } },
      { "managedObjectId": "Node:n-A",       "objectType": "Node",      "domain": "core-ip", "attributes": { "vendor": "acme", "model": "XR-9", "equipmentType": "router", "role": "core" } },
      { "managedObjectId": "Port:p-2",       "objectType": "Port",      "domain": "core-ip", "attributes": { "capacity": "100G" } },
      { "managedObjectId": "Interface:i-9",  "objectType": "Interface", "domain": "core-ip", "attributes": {} },
      { "managedObjectId": "IPLink:l-101",   "objectType": "IPLink",    "domain": "core-ip", "attributes": { "linkType": "backbone", "protectionRole": "working" } },
      { "managedObjectId": "FiberSpan:fs-12","objectType": "FiberSpan", "domain": "core-ip", "attributes": { "linkType": "fiber" } }
    ],
    "edges": [
      { "from": "Node:n-A",      "to": "Site:s-DC1",     "relation": "LOCATED_AT", "domain": "core-ip", "attributes": {} },
      { "from": "Port:p-2",      "to": "Node:n-A",       "relation": "HOSTED_ON",  "domain": "core-ip", "attributes": {} },
      { "from": "Port:p-2",      "to": "Interface:i-9",  "relation": "HOSTS",      "domain": "core-ip", "attributes": {} },
      { "from": "Interface:i-9", "to": "IPLink:l-101",   "relation": "TERMINATES", "domain": "core-ip", "attributes": {} },
      { "from": "IPLink:l-101",  "to": "FiberSpan:fs-12","relation": "RIDES_ON",   "domain": "core-ip", "attributes": {} }
    ]
  }
}
```

> **Integration check (Trail Builder ⟵ Topology):** Trail Builder reads `domain` directly off this payload (no Topology lookup). If `domain` were absent (legacy pre-#90 event), Trail Builder defaults to `core-ip` (logged WARN). It must tolerate the extra node/edge descriptor fields above.

### 1.3 Trail Builder → `trails.built` (TrailsBuiltEvent) [topic: `trails.built`]

Trail Builder consumes `topology.changed`, fetches the `core-ip` `trailPolicy` from Knowledge (`dependencyEdges` incl. HOSTS/TERMINATES/RIDES_ON), pulls the graph slice from Topology's query API, and computes the IGP-area-bounded transitive closure seeded from the fault-origin objects. The summary event:

```json
{
  "eventId": "22222222-2222-4222-8222-222222222222",
  "type": "TrailsBuiltEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T09:00:05Z",
  "source": "trail-builder",
  "traceId": "trace-p1-001",
  "payload": {
    "snapshotId": "snap-001",
    "domain": "core-ip",
    "trailIds": ["trail-7a3f"],
    "trailCount": 1
  }
}
```

> **Integration check:** `trailCount` MUST equal `trailIds.length` (1==1). Full membership is intentionally NOT in this event; every downstream consumer fetches it via the query API.

### 1.4 Trail Builder query API — `GET /trails/trail-7a3f`

```json
{
  "trailId": "trail-7a3f",
  "snapshotId": "snap-001",
  "domain": "core-ip",
  "igpArea": "0.0.0.0",
  "srlgGroup": null,
  "members": [
    { "managedObjectId": "FiberSpan:fs-12", "objectType": "FiberSpan" },
    { "managedObjectId": "IPLink:l-101",    "objectType": "IPLink" },
    { "managedObjectId": "Interface:i-9",   "objectType": "Interface" },
    { "managedObjectId": "Port:p-2",        "objectType": "Port" }
  ]
}
```

`GET /trails?managedObjectId=Port:p-2&domain=core-ip` (the form Enrichment uses for trail-tagging in P2/P3):

```json
{
  "managedObjectId": "Port:p-2",
  "domain": "core-ip",
  "trailIds": ["trail-7a3f"],
  "trails": [ { "trailId": "trail-7a3f", "snapshotId": "snap-001", "domain": "core-ip", "memberCount": 4 } ]
}
```

### 1.5 Codebook Generator → `codebook.generated` (CodebookGeneratedEvent) [topic: `codebook.generated`]

Codebook Generator consumes `trails.built`, fetches `core-ip` fault-origin types + propagation templates from Knowledge, enumerates fault-origin instances from Topology (Fiber, LineCard, Port, Interface, Node), forward-propagates each closure via typed-edge BFS to an ordered predicted symptom signature, tags scenarios with `trailIds`, persists, sets active, and emits the summary:

```json
{
  "eventId": "33333333-3333-4333-8333-333333333333",
  "type": "CodebookGeneratedEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T09:00:10Z",
  "source": "codebook-generator",
  "traceId": "trace-p1-001",
  "payload": {
    "snapshotId": "snap-001",
    "domain": "core-ip",
    "scenarioCount": 5,
    "codebookId": "cb-9f2c"
  }
}
```

> **Integration check:** `codebookId` (`cb-9f2c`) is the SAME value referenced later as `codebookMatchId` (PatternDiscovered/Approved) and `matchedCodebookId` (CorrelationResult).

`GET /codebooks/cb-9f2c/scenarios?faultOriginType=FiberSpan` — the signature for the fiber-cut scenario (origin alarm first):

```json
{
  "codebookId": "cb-9f2c",
  "domain": "core-ip",
  "scenarios": [
    {
      "scenarioId": "cb-9f2c:FiberSpan:fs-12",
      "faultOriginObjectId": "FiberSpan:fs-12",
      "faultOriginType": "FiberSpan",
      "predictedSymptoms": [
        { "alarmType": "FiberFault", "managedObjectId": "FiberSpan:fs-12" },
        { "alarmType": "LOS",        "managedObjectId": "Port:p-2" },
        { "alarmType": "LinkDown",   "managedObjectId": "IPLink:l-101" },
        { "alarmType": "InterfaceDown", "managedObjectId": "Interface:i-9" }
      ],
      "trailIds": ["trail-7a3f"]
    }
  ]
}
```

### 1.6 web-ui geo → site → graph reads (Topology query API)

`GET /topology/sites?domain=core-ip&snapshotId=current`:

```json
{
  "domain": "core-ip",
  "snapshotId": "snap-001",
  "count": 1,
  "sites": [
    { "managedObjectId": "Site:s-DC1", "objectType": "Site", "domain": "core-ip", "name": "DC1",
      "attributes": { "latitude": 37.3861, "longitude": -122.0839, "region": "us-west" }, "snapshotId": "snap-001" }
  ]
}
```

`GET /topology/sites/s-DC1/objects?domain=core-ip`:

```json
{
  "siteId": "s-DC1",
  "domain": "core-ip",
  "snapshotId": "snap-001",
  "count": 1,
  "objects": [
    { "managedObjectId": "Node:n-A", "objectType": "Node", "domain": "core-ip",
      "attributes": { "vendor": "acme", "model": "XR-9", "equipmentType": "router", "role": "core" }, "snapshotId": "snap-001" }
  ]
}
```

> **Integration check (web-ui ⟵ Topology):** web-ui reads `latitude`/`longitude`/`region` off `attributes` for the geo map, then drills `Site → objects-at-site → node graph`. Field-name divergence here is the web-ui OQ1 risk.

---

## P2 — Pattern Learning (offline)

### 2.1 Simulator → `alarms.history` (raw AlarmEvents) [topic: `alarms.history`]

Three labeled alarms from the fiber-cut scenario `sc-fiber-001`. Raw/source-formatted: `source` is the FEED id (`nms-alpha`), `trailIds` empty, severities/eventTypes in the source's vocabulary. `traceId` = scenarioId (Simulator convention).

```json
{ "eventId": "a0000001-0000-4000-8000-000000000001", "type": "AlarmEvent", "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:00:00Z", "source": "nms-alpha", "traceId": "sc-fiber-001",
  "payload": {
    "alarmId": "alm-1001", "managedObjectId": "FiberSpan:fs-12",
    "eventType": "communicationsAlarm", "probableCause": "lossOfSignal", "alarmType": "FiberFault",
    "perceivedSeverity": "critical", "raisedAt": "2026-06-08T10:00:00Z",
    "state": "raised", "trailIds": [] } }
```
```json
{ "eventId": "a0000002-0000-4000-8000-000000000002", "type": "AlarmEvent", "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:00:01Z", "source": "nms-alpha", "traceId": "sc-fiber-001",
  "payload": {
    "alarmId": "alm-1002", "managedObjectId": "Port:p-2",
    "eventType": "communicationsAlarm", "probableCause": "lossOfSignal", "alarmType": "LOS",
    "perceivedSeverity": "critical", "raisedAt": "2026-06-08T10:00:01Z",
    "state": "raised", "trailIds": [] } }
```
```json
{ "eventId": "a0000003-0000-4000-8000-000000000003", "type": "AlarmEvent", "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:00:03Z", "source": "nms-alpha", "traceId": "sc-fiber-001",
  "payload": {
    "alarmId": "alm-1003", "managedObjectId": "IPLink:l-101",
    "eventType": "communicationsAlarm", "probableCause": "linkDown", "alarmType": "LinkDown",
    "perceivedSeverity": "major", "raisedAt": "2026-06-08T10:00:03Z",
    "state": "raised", "trailIds": [] } }
```

### 2.2 Enrichment → `alarms.enriched` (canonical AlarmEvent) [topic: `alarms.enriched`]

Enrichment selects the `nms-alpha` ruleset by envelope `source`, normalizes via its mapping (canonical `eventType` via `eventTypeMap`, X.733 `severityMap`), runs the dedup/self-clear/flap/chatter pipeline, trail-tags survivors via Trail Builder `getTrailsForObject`, and OVERWRITES envelope `source` to `enrichment` (feed identity now only survives in `vendorRaw`). Showing the canonicalized `alm-1003`:

```json
{
  "eventId": "b0000003-0000-4000-8000-000000000003",
  "type": "AlarmEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:00:03Z",
  "source": "enrichment",
  "traceId": "sc-fiber-001",
  "payload": {
    "alarmId": "alm-1003",
    "managedObjectId": "IPLink:l-101",
    "eventType": "communicationsAlarm",
    "probableCause": "linkDown",
    "alarmType": "LinkDown",
    "perceivedSeverity": "major",
    "raisedAt": "2026-06-08T10:00:03Z",
    "state": "raised",
    "vendorRaw": { "feedSource": "nms-alpha", "rawSeverity": "major", "rawEventType": "communicationsAlarm" },
    "trailIds": ["trail-7a3f"]
  }
}
```

> **Integration check (the trailIds + alarmType hop):** `trailIds` is EMPTY on `alarms.history` and POPULATED (`["trail-7a3f"]`) here by Enrichment — this is the exact hop where the field becomes non-empty. Enrichment also populates the canonical **`alarmType`** token (`LinkDown`) from its per-source `alarmTypeMap`; `eventType` stays the X.733 category (`communicationsAlarm`) and `probableCause` the X.733 cause (`linkDown`). `alarmType` is the canonical join key Pattern Miner mines into `sequence`, Codebook signatures use, and Correlation matches on — distinct from `eventType`/`probableCause`.

### 2.3 Noise Filter → `transactions.clean` (TransactionEvent) [topic: `transactions.clean`]

Noise Filter windows the enriched alarms per `trailId`, DBSCAN-clusters the storm into ONE transaction, resolves `snapshotId` via Trail Builder `getTrail(trail-7a3f)`, and emits with BOTH `alarmIds[]` and the TYPED `alarms[]` in the SAME order (ordered by `raisedAt` then `alarmId`):

```json
{
  "eventId": "c0000000-0000-4000-8000-000000000010",
  "type": "TransactionEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:00:05Z",
  "source": "noise-filter",
  "traceId": "sc-fiber-001",
  "payload": {
    "transactionId": "txn-5001",
    "trailId": "trail-7a3f",
    "snapshotId": "snap-001",
    "domain": "core-ip",
    "alarmIds": ["alm-1001", "alm-1002", "alm-1003"],
    "alarms": [
      { "alarmId": "alm-1001", "alarmType": "FiberFault", "eventType": "communicationsAlarm", "raisedAt": "2026-06-08T10:00:00Z", "managedObjectId": "FiberSpan:fs-12", "perceivedSeverity": "critical" },
      { "alarmId": "alm-1002", "alarmType": "LOS",        "eventType": "communicationsAlarm", "raisedAt": "2026-06-08T10:00:01Z", "managedObjectId": "Port:p-2",        "perceivedSeverity": "critical" },
      { "alarmId": "alm-1003", "alarmType": "LinkDown",   "eventType": "communicationsAlarm", "raisedAt": "2026-06-08T10:00:03Z", "managedObjectId": "IPLink:l-101",    "perceivedSeverity": "major" }
    ],
    "windowStart": "2026-06-08T10:00:00Z",
    "windowEnd": "2026-06-08T10:00:05Z"
  }
}
```

> **Integration check (Noise Filter → Pattern Miner):** `alarms[]` and `alarmIds[]` are the SAME set in the SAME order. Each entry carries all SIX required fields (`alarmId, alarmType, eventType, raisedAt, managedObjectId, perceivedSeverity`), mirrored verbatim from the enriched `AlarmEvent`. Pattern Miner builds its `sequence` from `alarms[].alarmType` (the canonical join token) and reads `raisedAt` directly (no AlarmDetailResolver). `snapshotId` here is NOT on the source `alarms.enriched` AlarmEvent — Noise Filter derived it from `getTrail`; if Trail Builder returned nothing, NF would hold/retry rather than fabricate it.

### 2.4 Pattern Miner → `patterns.mined` (PatternMinedEvent) [topic: `patterns.mined`]

Pattern Miner session-windows by burst tempo, runs PrefixSpan over the ordered `alarmType` sequences (the canonical join tokens, not `eventType`), computes support/confidence/lift, and emits one event per discovered sequence. Deliberately NO `rootCauseAlarmType`/`patternId`/`lifecycle` (those are Pattern Manager's):

```json
{
  "eventId": "d0000000-0000-4000-8000-000000000020",
  "type": "PatternMinedEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:30:00Z",
  "source": "pattern-miner",
  "traceId": "sc-fiber-001",
  "payload": {
    "sequence": ["FiberFault", "LOS", "LinkDown", "InterfaceDown"],
    "support": 0.92,
    "confidence": 0.95,
    "lift": 4.7,
    "trailId": "trail-7a3f",
    "timing": { "timeframeMs": 3000, "medianInterArrivalMs": 1000, "maxInterArrivalMs": 2000, "stddevInterArrivalMs": 500 },
    "provenance": {
      "sourceWindowId": "win-trail-7a3f-100000",
      "snapshotId": "snap-001",
      "domain": "core-ip",
      "codebookVersion": "cb-9f2c"
    }
  }
}
```

> **Integration check:** `provenance.snapshotId`/`domain` are copied from `TransactionEvent.snapshotId`/`domain` (same names). `sequence` is an ordered list of canonical **`alarmType`** vocabulary tokens (`FiberFault`/`LOS`/`LinkDown`/`InterfaceDown`) — the SAME value space as Codebook signatures and `rootCauseAlarmType`, which is why Enrichment populates `alarmType` upstream. `timing` keys (`timeframeMs`, `medianInterArrivalMs`, `maxInterArrivalMs`, `stddevInterArrivalMs`) feed Pattern Manager's session-window derivation directly (producer + consumer agree on these ms keys).

### 2.5 Pattern Manager → `patterns.discovered` (PatternDiscoveredEvent) [topic: `patterns.discovered`]

Pattern Manager consumes `patterns.mined`, runs RCA (graph-ordering + codebook override → root cause `FiberFault`), structural validation (internal flag, NOT on the event), codebook reconciliation (finds scenario `cb-9f2c:FiberSpan:fs-12` → `codebookMatchId`), and DERIVES `sessionWindow` purely from `timing` (windowMs = clamp(max(ceil(3000*1.5), ceil(2000*2.0)), 5000, 1800000) = 5000; cv = 500/1000 = 0.5 → not < 0.5 → `gap-based`). Persists as `draft`, mints deterministic UUIDv5 `patternId`, emits:

```json
{
  "eventId": "e0000000-0000-4000-8000-000000000030",
  "type": "PatternDiscoveredEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:30:05Z",
  "source": "pattern-manager",
  "traceId": "sc-fiber-001",
  "payload": {
    "patternId": "pat-3b21",
    "sequence": ["FiberFault", "LOS", "LinkDown", "InterfaceDown"],
    "rootCauseAlarmType": "FiberFault",
    "support": 0.92,
    "confidence": 0.95,
    "lift": 4.7,
    "timing": { "timeframeMs": 3000, "medianInterArrivalMs": 1000, "maxInterArrivalMs": 2000, "stddevInterArrivalMs": 500 },
    "sessionWindow": { "windowMs": 5000, "type": "gap-based" },
    "codebookMatchId": "cb-9f2c:FiberSpan:fs-12",
    "lifecycle": "draft"
  }
}
```

> **Integration check (the sessionWindow + rootCause hop):** `rootCauseAlarmType` and `sessionWindow` FIRST appear here — derived by Pattern Manager, neither was on `patterns.mined`. `structurallyValidated`/edit metadata are deliberately ABSENT (frozen schema, `additionalProperties:false`). `codebookMatchId` = a scenarioId from §1.5.

### 2.6 web-ui pattern review — `GET /patterns/pat-3b21`

```json
{
  "patternId": "pat-3b21",
  "sequence": ["FiberFault", "LOS", "LinkDown", "InterfaceDown"],
  "rootCauseAlarmType": "FiberFault",
  "support": 0.92,
  "confidence": 0.95,
  "lift": 4.7,
  "timing": { "timeframeMs": 3000, "medianInterArrivalMs": 1000, "maxInterArrivalMs": 2000, "stddevInterArrivalMs": 500 },
  "sessionWindow": { "windowMs": 5000, "type": "gap-based" },
  "codebookMatchId": "cb-9f2c:FiberSpan:fs-12",
  "structurallyValidated": true,
  "structuralValidationReason": null,
  "lifecycle": "draft",
  "supportingInstances": [
    { "sourceWindowId": "win-trail-7a3f-100000", "snapshotId": "snap-001", "occurrence": { "trailId": "trail-7a3f", "alarmIds": ["alm-1001","alm-1002","alm-1003"] } }
  ]
}
```

> **Integration check:** the read-API `PatternView` CARRIES `structurallyValidated`/`structuralValidationReason` (internal fields) that the frozen events do NOT — these live only in the Pattern Store + read API.

### 2.7 Operator approves — `POST /patterns/pat-3b21/approve`

Request body (`ApprovalIntent`):
```json
{ "decision": "approve", "reviewer": "noc-operator-1", "notes": "fiber-cut signature confirmed" }
```
Response `200` (`PatternView`, lifecycle now `approved`):
```json
{ "patternId": "pat-3b21", "lifecycle": "approved", "rootCauseAlarmType": "FiberFault",
  "sequence": ["FiberFault", "LOS", "LinkDown", "InterfaceDown"],
  "sessionWindow": { "windowMs": 5000, "type": "gap-based" }, "codebookMatchId": "cb-9f2c:FiberSpan:fs-12" }
```

### 2.8 Pattern Manager → `patterns.approved` (PatternApprovedEvent) [topic: `patterns.approved`]

Pattern Manager is the SOLE producer (web-ui only POSTed intent). `sessionWindow` is read from the persisted record and is BYTE-IDENTICAL to §2.5:

```json
{
  "eventId": "f0000000-0000-4000-8000-000000000040",
  "type": "PatternApprovedEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T10:35:00Z",
  "source": "pattern-manager",
  "traceId": "sc-fiber-001",
  "payload": {
    "patternId": "pat-3b21",
    "sequence": ["FiberFault", "LOS", "LinkDown", "InterfaceDown"],
    "rootCauseAlarmType": "FiberFault",
    "support": 0.92,
    "confidence": 0.95,
    "lift": 4.7,
    "timing": { "timeframeMs": 3000, "medianInterArrivalMs": 1000, "maxInterArrivalMs": 2000, "stddevInterArrivalMs": 500 },
    "sessionWindow": { "windowMs": 5000, "type": "gap-based" },
    "codebookMatchId": "cb-9f2c:FiberSpan:fs-12",
    "lifecycle": "approved"
  }
}
```

> **Integration check (Correlation Engine ⟵ Pattern Manager):** CE reads `patternId`, `sequence`, `rootCauseAlarmType`, `trailId`(via Pattern Manager read API at bootstrap), `confidence`, `sessionWindow` from approved patterns. There is deliberately NO `codebookId` on this event — CE aligns codebook by `snapshotId`+trail scope from `codebook.generated`, not from here.

---

## P3 — Real-time Correlation (online)

### 3.1 Simulator → `alarms.live` (raw AlarmEvents, wall-clock paced) [topic: `alarms.live`]

The SAME fiber-cut burst, now live, new run → fresh `alarmId`s (`alm-2001..2003`) and `eventId`s. Raw, `trailIds: []`, `source` = feed id.

```json
{ "eventId": "10000001-0000-4000-8000-000000000001", "type": "AlarmEvent", "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:00Z", "source": "nms-alpha", "traceId": "live-fiber-001",
  "payload": { "alarmId": "alm-2001", "managedObjectId": "FiberSpan:fs-12",
    "eventType": "communicationsAlarm", "probableCause": "lossOfSignal", "alarmType": "FiberFault",
    "perceivedSeverity": "critical", "raisedAt": "2026-06-08T12:00:00Z", "state": "raised", "trailIds": [] } }
```
```json
{ "eventId": "10000002-0000-4000-8000-000000000002", "type": "AlarmEvent", "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:02Z", "source": "nms-alpha", "traceId": "live-fiber-001",
  "payload": { "alarmId": "alm-2002", "managedObjectId": "IPLink:l-101",
    "eventType": "communicationsAlarm", "probableCause": "linkDown", "alarmType": "LinkDown",
    "perceivedSeverity": "major", "raisedAt": "2026-06-08T12:00:02Z", "state": "raised", "trailIds": [] } }
```

### 3.2 Enrichment → `alarms.enriched.live` (canonical AlarmEvent, trailIds populated) [topic: `alarms.enriched.live`]

Same pipeline/instance as P2, live path. Showing canonicalized `alm-2001`:

```json
{
  "eventId": "20000001-0000-4000-8000-000000000001",
  "type": "AlarmEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:00Z",
  "source": "enrichment",
  "traceId": "live-fiber-001",
  "payload": {
    "alarmId": "alm-2001",
    "managedObjectId": "FiberSpan:fs-12",
    "eventType": "communicationsAlarm",
    "probableCause": "lossOfSignal",
    "alarmType": "FiberFault",
    "perceivedSeverity": "critical",
    "raisedAt": "2026-06-08T12:00:00Z",
    "state": "raised",
    "vendorRaw": { "feedSource": "nms-alpha" },
    "trailIds": ["trail-7a3f"]
  }
}
```

### 3.3 Alarm Manager persists (state `open`) + republishes → `alarms.persisted.live` [topic: `alarms.persisted.live`]

Alarm Manager consumes `alarms.enriched.live`, upserts into its live store with `lifecycle_state = open` (idempotent on `alarmId`), and republishes the SAME AlarmEvent faithfully (republish-once via `published` flag). The republished `alm-2001`:

```json
{
  "eventId": "20000001-0000-4000-8000-000000000001",
  "type": "AlarmEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:00Z",
  "source": "enrichment",
  "traceId": "live-fiber-001",
  "payload": {
    "alarmId": "alm-2001",
    "managedObjectId": "FiberSpan:fs-12",
    "eventType": "communicationsAlarm",
    "probableCause": "lossOfSignal",
    "alarmType": "FiberFault",
    "perceivedSeverity": "critical",
    "raisedAt": "2026-06-08T12:00:00Z",
    "state": "raised",
    "vendorRaw": { "feedSource": "nms-alpha" },
    "trailIds": ["trail-7a3f"]
  }
}
```

> **Integration check:** Correlation Engine consumes `alarms.persisted.live` (NOT `alarms.enriched.live`) — republish guarantees persist-before-correlate. The republished message is byte-faithful (re-serialized from `raw_envelope` via EventCodec); `source` stays `enrichment`, eventId/alarmId unchanged.

### 3.4 Correlation Engine — instance lifecycle + emissions

CE fans out per `trailId`. On the first opening-condition alarm (`alm-2001`, eventType `FiberFault` = the pattern's root) it LAZY-INITS a CorrelationInstance keyed `(trail-7a3f, pat-3b21)`, and fires `in-progress` per admitted alarm.

**3.4a `alarms.status.changed` (AlarmStatusChange = in-progress, on admission)** [topic: `alarms.status.changed`]
```json
{ "eventId": "30000001-0000-4000-8000-000000000001", "type": "AlarmStatusChange", "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:00Z", "source": "correlation-engine", "traceId": "live-fiber-001",
  "payload": { "alarmId": "alm-2001", "newStatus": "in-progress", "source": "correlation-engine", "changedAt": "2026-06-08T12:00:00Z" } }
```
(one such event per admitted alarm; `alm-2002` → `in-progress` likewise.)

On full match (sequence satisfied within `sessionWindow.windowMs`=5000), CE tags root cause + children, persists the incident (persist-then-emit), then emits:

**3.4b `correlation.results` (CorrelationResultEvent = ROLE + incidentId)** [topic: `correlation.results`]
```json
{
  "eventId": "40000000-0000-4000-8000-000000000050",
  "type": "CorrelationResultEvent",
  "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:05Z",
  "source": "correlation-engine",
  "traceId": "live-fiber-001",
  "payload": {
    "incidentId": "inc-8801",
    "rootCauseAlarmId": "alm-2001",
    "childAlarmIds": ["alm-2002"],
    "matchedPatternId": "pat-3b21",
    "matchedCodebookId": "cb-9f2c",
    "confidence": 0.95,
    "trailId": "trail-7a3f"
  }
}
```

**3.4c `alarms.status.changed` (AlarmStatusChange = correlated, on full match)** [topic: `alarms.status.changed`]
```json
{ "eventId": "30000003-0000-4000-8000-000000000003", "type": "AlarmStatusChange", "schemaVersion": 1,
  "occurredAt": "2026-06-08T12:00:05Z", "source": "correlation-engine", "traceId": "live-fiber-001",
  "payload": { "alarmId": "alm-2001", "newStatus": "correlated", "source": "correlation-engine", "changedAt": "2026-06-08T12:00:05Z" } }
```
(one `correlated` per root-cause + child; `alm-2002` → `correlated` likewise.) CE then destroys the instance.

> **Integration check (the incidentId hop):** `incidentId` (`inc-8801`) FIRST appears here, minted by Correlation Engine. `matchedPatternId`=`pat-3b21` (from §2.8), `matchedCodebookId`=`cb-9f2c` (the pattern's `codebookMatchId`'s codebook from §1.5) — `match_type=pattern` is the authoritative discriminator; both ids being set is allowed. RCA context (incidentId/role/trailId) flows ONLY on `correlation.results`; `AlarmStatusChange` is intentionally minimal.

### 3.5 Alarm Manager consumes both streams (STATE vs ROLE, disjoint columns)

- From `alarms.status.changed` (STATE channel): applies `newStatus` to `lifecycle_state` → `alm-2001` goes `open → in-progress → correlated`; deduped on envelope `eventId`.
- From `correlation.results` (ROLE channel): sets `role` + `incidentId` by `alarmId` → `alm-2001` `role=root-cause incidentId=inc-8801`, `alm-2002` `role=child incidentId=inc-8801`; deduped on envelope `eventId`.

STATE and ROLE land in DISJOINT columns and reconcile on `alarmId` order-independently.

> **Integration check (the two-stream join):** lifecycle `correlated` comes ONLY from `AlarmStatusChange` (§3.4c) — a `CorrelationResultEvent` alone sets role+incidentId but leaves `lifecycle_state` unchanged. CE MUST emit BOTH for the live record to be both `correlated` AND role-tagged. Field names AM reads: `rootCauseAlarmId`, `childAlarmIds`, `incidentId` (from CorrelationResult); `alarmId`, `newStatus`, `source`, `changedAt` (from AlarmStatusChange).

### 3.6 web-ui streaming view (polling)

`GET /incidents` (Correlation Engine):
```json
{ "items": [ { "incidentId": "inc-8801", "rootCauseAlarmId": "alm-2001", "childAlarmIds": ["alm-2002"],
  "matchedPatternId": "pat-3b21", "matchedCodebookId": "cb-9f2c", "confidence": 0.95,
  "trailId": "trail-7a3f", "createdAt": "2026-06-08T12:00:05Z" } ], "total": 1, "limit": 50, "offset": 0 }
```

`GET /alarms?state=correlated&incidentId=inc-8801` (Alarm Manager):
```json
{ "items": [
  { "alarmId": "alm-2001", "managedObjectId": "FiberSpan:fs-12", "alarmType": "FiberFault", "eventType": "communicationsAlarm", "perceivedSeverity": "critical",
    "raisedAt": "2026-06-08T12:00:00Z", "lifecycleState": "correlated", "role": "root-cause", "incidentId": "inc-8801", "trailIds": ["trail-7a3f"] },
  { "alarmId": "alm-2002", "managedObjectId": "IPLink:l-101", "alarmType": "LinkDown", "eventType": "communicationsAlarm", "perceivedSeverity": "major",
    "raisedAt": "2026-06-08T12:00:02Z", "lifecycleState": "correlated", "role": "child", "incidentId": "inc-8801", "trailIds": ["trail-7a3f"] }
  ], "total": 2, "limit": 50, "offset": 0 }
```

### 3.7 web-ui incident-detail drill-down (two services joined)

`GET /incidents/inc-8801` (Correlation Engine — incident system of record):
```json
{ "incidentId": "inc-8801", "rootCauseAlarmId": "alm-2001", "childAlarmIds": ["alm-2002"],
  "matchedPatternId": "pat-3b21", "matchedCodebookId": "cb-9f2c", "confidence": 0.95,
  "trailId": "trail-7a3f", "createdAt": "2026-06-08T12:00:05Z" }
```

`GET /alarms/alm-2001` (Alarm Manager — per member alarm, full record + ordered transitions):
```json
{
  "alarmId": "alm-2001",
  "managedObjectId": "FiberSpan:fs-12",
  "eventType": "communicationsAlarm",
  "probableCause": "lossOfSignal",
  "alarmType": "FiberFault",
  "perceivedSeverity": "critical",
  "raisedAt": "2026-06-08T12:00:00Z",
  "clearedAt": null,
  "state": "raised",
  "trailIds": ["trail-7a3f"],
  "vendorRaw": { "feedSource": "nms-alpha" },
  "lifecycleState": "correlated",
  "role": "root-cause",
  "incidentId": "inc-8801",
  "transitions": [
    { "toState": "open",        "reason": "persisted",      "source": null,                "changedAt": null,                   "occurredAt": "2026-06-08T12:00:00Z" },
    { "toState": "in-progress", "reason": "status-change",  "source": "correlation-engine", "changedAt": "2026-06-08T12:00:00Z", "occurredAt": "2026-06-08T12:00:00Z" },
    { "toState": "correlated",  "reason": "status-change",  "source": "correlation-engine", "changedAt": "2026-06-08T12:00:05Z", "occurredAt": "2026-06-08T12:00:05Z" }
  ]
}
```

> **Integration check (web-ui join):** web-ui cross-links by `incidentId` + `trailId` + `rootCauseAlarmId`/`childAlarmIds`: CE supplies the incident shape; AM supplies each member alarm's lifecycle/role/transitions. `confidence` and `matchedPatternId` come from CE only; `lifecycleState`/`role`/`transitions` from AM only.

---

## End-to-end ID flow (single fiber-cut thread)

| Identifier | Minted at | Reused at |
|---|---|---|
| `snap-001` | Topology ingest (P1) | topology.changed, trails.built, getTrail, codebook.generated, TransactionEvent, PatternMined.provenance |
| `trail-7a3f` | Trail Builder (P1) | getTrail, Enrichment trailIds, TransactionEvent, PatternMined, CorrelationResult, AM trailIds |
| `cb-9f2c` | Codebook Generator (P1) | codebookVersion (PatternMined), codebookMatchId base (Pattern), matchedCodebookId (CorrelationResult) |
| `FiberSpan:fs-12` etc. | Topology snapshot (P1) | every AlarmEvent moId, codebook signatures, trail members |
| `pat-3b21` | Pattern Manager (P2) | patterns.discovered/approved, CE bootstrap, matchedPatternId |
| `sessionWindow {5000,gap-based}` | Pattern Manager derive (P2) | patterns.discovered, patterns.approved (byte-identical), CE per-pattern expiry window |
| `inc-8801` | Correlation Engine (P3) | correlation.results, AM incidentId, web-ui incident-detail |

> **Note on per-run alarmId scope:** P2 (`alm-1001..1003`) and P3 (`alm-2001..2003`) intentionally differ — Simulator mints fresh `alarmId`/`eventId` per run (deterministic topology/structure under `SIM_SEED`, but no cross-run id stability). The learned pattern (`pat-3b21`) is the durable artifact bridging the two runs; topology/trail/codebook ids (`snap-001`/`trail-7a3f`/`cb-9f2c`) persist because P3 runs against the SAME ingested snapshot.