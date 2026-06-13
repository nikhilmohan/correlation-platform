# simulator — Design

> Buildable design for the `simulator` service (Python cohort). Built from the approved, merged
> `services/simulator/spec.md`. Honours the platform invariants in `CLAUDE.md` and the contracts in
> `docs/architecture.md` and the frozen `libs/event-model` Python binding (`acp-event-model`).

## Stack

- **Language / runtime:** Python 3.13 (pinned per CLAUDE.md / CI).
- **Topology generation:** [`networkx`](https://networkx.org/) (BSD-3) — typed multi-layer graph
  construction + closure traversal for cascade propagation.
- **Event model / validation:** `acp-event-model` (the frozen `libs/event-model` Python/Pydantic v2
  binding) — `AlarmEvent`, `Envelope`/`TypedEnvelope`, `serialize`, `ManagedObjectId`/`validate`,
  `KNOWN_OBJECT_TYPES`. No payload field is hand-authored here; the binding is the single source of
  truth for the envelope, the `AlarmEvent` shape, and the `managedObjectId` scheme.
- **Kafka producer:** [`confluent-kafka`](https://pypi.org/project/confluent-kafka/) (Apache-2.0)
  Python client (librdkafka). `kafka-python` (Apache-2.0) is the fallback if a pure-Python client is
  preferred for the test rig; chosen → `confluent-kafka` for `acks=all` idempotent producer config.
- **HTTP surface (ground-truth + control):** [`FastAPI`](https://fastapi.tiangolo.com/) (MIT) +
  `uvicorn` (BSD-3). FastAPI auto-generates the OpenAPI 3.1 document served at `/openapi.json`.
- **Topology ingestion client:** `httpx` (BSD-3) HTTP client built **against Topology's published
  OpenAPI 3.1** (no dependency on Topology source). Mock vs. real is config-switchable.
- **Snapshot-file & config validation:** `jsonschema` (MIT) against the versioned snapshot schema;
  Pydantic models for scenario/config files.
- **Metrics:** `prometheus-client` (Apache-2.0) → `/metrics`.
- **Logging:** stdlib `logging` + a JSON formatter (structured JSON on stdout).
- **Lint / format / test:** `ruff` + `black`; **pytest** (cohort-standard unit/contract runner).

All dependencies are permissive (MIT / BSD / Apache-2.0). No GPL/AGPL/copyleft components.

## Task breakdown (from the spec)

Every spec Task (1–9) is realized below and traceable to concrete modules/flows.

| Spec task | Realized by (modules / flow) |
|---|---|
| **1. Generate typed multi-layer topology (Core IP pack), configurable size, stable `managedObjectId`s** | `engine/topology_builder.py` drives a `networkx` `DiGraph`; the **Core IP domain pack** (`domains/coreip/topology_model.py`) supplies the object types + layer-construction rules. The pack now also emits **`Site` nodes** (the domain-agnostic Site object type, geo attributes) and places each device in a site via a **`LOCATED_AT`** edge, emits **`Interface` nodes** (L3 endpoints) on ports via **`HOSTS`** (Port→Interface) with each interface **`TERMINATES`**-ing its `IPLink` and the IGP adjacency generated **between interfaces** (`ADJACENCY_OVER` Interface→IGPAdjacency, per §5 #91), and populates the well-known **device/connection `attributes`** keys (grounded values). IDs minted via `acp_event_model.ManagedObjectId` → generic `<objectType>:<id>`. Size from `TOPOLOGY_NODE_COUNT`; site count / devices-per-site from `SITE_COUNT` / `DEVICES_PER_SITE`; interfaces-per-port from `INTERFACES_PER_PORT` (default 1). |
| **2. Generate snapshot file (versioned contract) + upload to Topology ingestion API (client from OpenAPI; config-switchable mock/real)** | `engine/snapshot_writer.py` serializes the graph to the snapshot JSON, validated against the **single canonical `services/topology/schema/snapshot.schema.json`** (Topology-owned; no independent Simulator copy); `integrations/topology_client.py` is an `httpx` client generated from Topology's published OpenAPI, `POST /topology/snapshots`, reading `snapshotId` from the frozen **200 `SnapshotIngestResponse`**, selected by `TOPOLOGY_API_MODE` (`mock`/`real`) + `TOPOLOGY_API_BASE_URL`. |
| **3. Load fault-scenario configs (local files or Knowledge Service)** | `config/scenario_loader.py` resolves scenario defs, jitter, noise mix from local files (default/mock) or the Knowledge Service (`integrations/knowledge_client.py`), switchable by `KNOWLEDGE_MODE`. Validated at startup. |
| **4. Inject labeled fault scenarios (root cause → cascade per §5 templates, jitter); record `{rootCause, children}`** | `engine/scenario_runner.py` + `engine/cascade.py` run the pack's propagation templates forward over the graph closure with jitter; `engine/labels.py` records the ground-truth label per scenario, **incl. the root-cause `alarmType` token (`rootCauseAlarmType`)** so the RCA oracle compares on the canonical join token. Every emitted `AlarmEvent` carries its **canonical `alarmType`** token (a Knowledge `alarmTypeVocabulary` member — `FiberFault`, `LOS`, `LinkDown`, `InterfaceDown`, `AdjDown`, `LSPDown`, `ReachabilityLoss`, `PortDown`) set from the pack's alarm shape; `alarmType` is the cross-source canonical join key, **distinct from `eventType` (X.733 category) and `probableCause` (X.733 probable cause)**. Templates + per-alarm `alarmType` supplied by `domains/coreip/propagation.py` + `domains/coreip/alarm_shapes.py`. |
| **5. Inject background noise (≥3 noise classes), configurable rate/mix** | `engine/noise.py` interleaves noise alarms from the pack's noise generators (`domains/coreip/noise.py`); rate/mix from config. Noise alarms are excluded from every label's `children` set. |
| **6. Replay in history (batch → `alarms.history`) or live (wall-clock paced → `alarms.live`)** | `engine/replay.py` with two strategies: `BatchReplay` (fire-and-flush) and `LiveReplay` (wall-clock paced via `PACING_MULTIPLIER`). Topic selected by mode. `integrations/kafka_producer.py` emits `TypedEnvelope[AlarmEvent]`. |
| **7. Make ground-truth labels retrievable for evaluation** | **Decision (OQ-2): both** — labels are written to a **flat JSONL file** at end-of-run (`labels.export_to_file`) **and** served by a small read-only **FastAPI** surface (`api/labels_api.py`). File export is the canonical, no-broker oracle source; REST is convenience. OpenAPI 3.1 published + `openapi.json` checked in. |
| **8. Domain-pack interface — object/edge types, templates, alarm shapes, scenario library supplied by the pack; no domain leakage into engine** | `engine/domain_pack.py` defines the `DomainPack` `Protocol`; `domains/coreip/` is the only implementation. The pack **declares the domain vocabulary** the snapshot is built from: its **object-type set** (the Core-IP layers — incl. **`Interface`** — **plus the domain-agnostic `Site`**), its **edge-relation vocabulary** (the Core-IP relations — incl. **`HOSTS`** and **`TERMINATES`** — **plus `LOCATED_AT`**), the **`domain` identifier** (`core-ip`) stamped on the snapshot, and the **well-known `attributes` keys** it populates on devices/connections — the same vocabulary Topology validates against (authored canonically in Knowledge; the pack must align). The engine imports the Protocol only; criterion-19 test asserts no Core-IP literals in `engine/`. |
| **9. `/health` + `/metrics` + structured JSON logs** | `api/health.py`, `api/metrics.py` (FastAPI routes); `obs/logging.py` JSON formatter; `obs/metrics.py` Prometheus registry. |

## Phase applicability (design view)

The Simulator is **Active in all three runtime phases** and is the evaluation oracle throughout
(consistent with the spec phase table and the canonical phase map in `architecture.md` row
"simulator").

| Phase | Active/Passive/Idle | Modules/handlers exercised | Inputs/Outputs |
|---|---|---|---|
| **P1 — Topology onboarding** | **Active** | `engine/topology_builder`, `engine/snapshot_writer`, `integrations/topology_client` (upload). Replay/cascade modules dormant. | Out: topology snapshot **file** → Topology ingestion API (HTTP upload, mock/real). No Kafka. |
| **P2 — Pattern learning** | **Active** | `config/scenario_loader`, `engine/scenario_runner`, `engine/cascade`, `engine/noise`, `engine/labels`, `engine/replay:BatchReplay`, `integrations/kafka_producer`. Topology builder dormant (snapshot already uploaded; same graph reused for ID sharing). | Out: `alarms.history` (`AlarmEvent`, batch). Label export written. |
| **P3 — Real-time correlation** | **Active** | Same scenario/cascade/noise/label modules + `engine/replay:LiveReplay` (wall-clock paced). | Out: `alarms.live` (`AlarmEvent`, wall-clock paced). Label export written. |

The ground-truth labels persisted in P2/P3 and the integration thresholds (see below) are the oracle
the `integration-test` harness asserts across all phases.

## Module breakdown

The codebase is split into a **domain-agnostic engine** and a **swappable domain pack**. This split
is the structural realization of spec Task 8 / criterion 19.

> **The synthesizer.** The engine modules `topology_builder`, `cascade`, `scenario_runner`,
> `noise`, and `labels` collectively form the **synthesizer** — the component that turns *config +
> the domain pack* into the topology snapshot and the labeled, evaluation-grade alarm corpus.
> Everywhere this design says "the synthesizer", it means these engine modules acting together
> (driven by the seeded RNG and the pack's grounded model). `replay` then ships what the
> synthesizer produced; `integrations` move it onto Kafka / the Topology API.

```mermaid
flowchart TB
  subgraph cli["entrypoint / CLI"]
    main["main.py<br/>(orchestrator: P1 upload / P2 history / P3 live)"]
  end
  subgraph cfg["config"]
    settings["settings.py<br/>(env, validate-at-startup)"]
    sloader["scenario_loader.py"]
  end
  subgraph engine["engine (domain-AGNOSTIC — no Core-IP literals)"]
    dp["domain_pack.py<br/>(DomainPack Protocol)"]
    tb["topology_builder.py"]
    sw["snapshot_writer.py"]
    sr["scenario_runner.py"]
    casc["cascade.py<br/>(forward propagation)"]
    noise["noise.py"]
    labels["labels.py<br/>(ground-truth store)"]
    replay["replay.py<br/>(Batch / Live)"]
  end
  subgraph pack["domains/coreip (the ONLY pack for MVP)"]
    tm["topology_model.py<br/>(9 typed layers + edges)"]
    prop["propagation.py<br/>(§5 templates)"]
    shapes["alarm_shapes.py<br/>(canonical alarmType plus X.733 per alarm type)"]
    lib["scenario_library.py<br/>(fiber-cut, line-card, port, interface + noise)"]
  end
  subgraph integ["integrations (config-switchable mock/real)"]
    topo["topology_client.py<br/>(from Topology OpenAPI)"]
    kp["kafka_producer.py"]
    kn["knowledge_client.py"]
  end
  subgraph api["api (FastAPI)"]
    health["/health"]
    metrics["/metrics"]
    labelsapi["/labels, /scenarios"]
  end
  subgraph obs["obs"]
    log["logging.py (JSON)"]
    met["metrics.py (Prometheus)"]
  end

  main --> settings --> sloader
  main --> tb --> dp
  tb --> tm
  tb --> sw --> topo
  main --> sr --> casc --> prop
  sr --> noise --> lib
  sr --> labels
  casc --> shapes
  sr --> replay --> kp
  labels --> labelsapi
  sloader --> kn
  engine -.imports only.-> dp
```

- **`engine/domain_pack.py`** — the `DomainPack` `Protocol`: `domain_id()` (e.g. `core-ip`),
  `object_types()` (includes `Interface` and the domain-agnostic `Site`), `edge_relations()` (includes
  `HOSTS`, `TERMINATES`, and `LOCATED_AT`), `attribute_keys()` (the well-known device/connection keys the pack populates),
  `build_topology(graph, size, rng)`, `propagation_templates()`, `alarm_shape(alarm_type)`,
  `alarm_type_vocabulary()` (the canonical `alarmType` token set the pack emits — must be a subset
  of the domain's Knowledge `alarmTypeVocabulary`), `scenario_library()`, `noise_classes()`.
  `object_types()`/`edge_relations()`/`attribute_keys()`/`alarm_type_vocabulary()`/`domain_id()` are
  the **domain vocabulary** the snapshot + alarms are stamped/validated with; the engine depends only
  on this Protocol. `alarm_shape(alarm_type)` returns the full shape for one canonical `alarmType`
  token — its `alarmType` (the join key), plus the X.733 `eventType`/`probableCause`/`perceivedSeverity`
  — so every alarm the engine emits carries the required `alarmType`.
- **`engine/topology_builder.py`** — asks the pack to populate a `networkx` `DiGraph` of typed nodes
  and typed edges (including `Site` nodes + `LOCATED_AT` edges and `Interface` nodes + `HOSTS`/
  `TERMINATES` edges), copying each node/edge's `attributes` map through; mints `managedObjectId`s.
  Domain-agnostic: it never names a Core-IP type, the `Site`/`Interface` type, or a relation literal
  — it iterates `pack.object_types()`/`pack.edge_relations()`.
- **`engine/cascade.py`** — given a root-cause node + the pack's propagation templates, walks the
  graph closure (BFS over the template-relevant edge relations) producing the ordered child alarm
  set. The §5 logic (see Algorithm logical flow) lives in template *data* from the pack; the
  traversal is generic.
- **`engine/labels.py`** — the ground-truth store (see Data model). One record per injected scenario:
  `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children,
  snapshotId}`. `rootCauseAlarmType` is the root-cause alarm's canonical `alarmType` token (the join
  key the RCA oracle compares against `correlation.results.rootCauseAlarmType`), recorded from the
  same alarm-shape the root alarm was emitted with — so the oracle compares like-for-like on the
  canonical token, not on `probableCause`.
- **`domains/coreip/`** — the only concrete pack: the typed Core-IP layers + edges **plus `Site`
  nodes and `LOCATED_AT` edges and `Interface` nodes + `HOSTS`/`TERMINATES` edges** (Port HOSTS
  Interface; Interface TERMINATES IPLink; `ADJACENCY_OVER` runs Interface→IGPAdjacency, per §5 #91),
  the well-known `attributes` keys on devices (`vendor`, `model`,
  `equipmentType`, `role`, `capacity`) and connections (`linkType`, `capacity`, `protectionRole`),
  the §5 propagation templates, the **canonical `alarmType` tokens** + their X.733 alarm shapes (each
  shape pairs an `alarmType` join token with its X.733 `eventType`/`probableCause`/`perceivedSeverity`),
  the scenario library (fiber-cut, line-card-fault, port-fault, **interface-fault** + ≥3 noise
  classes). It declares `domain_id() == "core-ip"`, the full object-type/relation/attribute vocabulary
  stamped on the snapshot (Core-IP object types now include `Interface`; relations include
  `HOSTS`/`TERMINATES`), and the canonical **`alarm_type_vocabulary()`** = `{FiberFault, LOS,
  PortDown, InterfaceDown, LinkDown, AdjDown, LSPDown, ReachabilityLoss}` (a subset of the domain's
  Knowledge `alarmTypeVocabulary` — the value space every emitted `AlarmEvent.alarmType` is drawn
  from). These `alarmType` tokens are the canonical join tokens, **not** the lowercase X.733
  `probableCause` tokens (`lossOfSignal`/`linkDown`) and **not** the X.733 `eventType` categories.
  `Site`/`LOCATED_AT` are domain-agnostic and reused by any future pack; the geo/attribute *values*
  are Core-IP grounded.

## Data model / DB schema

**Decision (OQ-2 / "Data owned"): file-based, no relational store.** The Simulator is a short-lived
generation/replay job, not a long-running data service; a relational DB adds operational weight with
no query need. The two owned artifacts are written to a run-scoped output directory
(`SIM_OUTPUT_DIR`, default `/data/sim`):

1. **Topology snapshot file** — `snapshot-<runId>.json` (the versioned contract; schema below).
2. **Ground-truth label export** — `labels-<runId>.jsonl` (one JSON object per scenario) + an
   in-process index served by the labels API.

The label record model (Pydantic, internal):

```mermaid
classDiagram
  class SimRun {
    +string runId
    +string snapshotId
    +int topologyNodeCount
    +int seed
    +datetime startedAt
  }
  class GroundTruthLabel {
    +string scenarioId
    +string scenarioType
    +string rootCause
    +string rootCauseManagedObjectId
    +string rootCauseAlarmType
    +string[] children
    +datetime injectedAt
  }
  class EmittedAlarm {
    +string eventId
    +string alarmId
    +string managedObjectId
    +string alarmType
    +string scenarioId
    +bool isNoise
    +string noiseClass
  }
  SimRun "1" --> "*" GroundTruthLabel
  SimRun "1" --> "*" EmittedAlarm
  GroundTruthLabel "1" --> "*" EmittedAlarm : rootCause + children
```

- `scenarioType` ∈ `{fiber-cut, line-card-fault, port-fault, interface-fault}`; `rootCause` is the root-cause
  alarm's `alarmId`; `rootCauseAlarmType` is the root-cause alarm's **canonical `alarmType`** token
  (a Knowledge `alarmTypeVocabulary` member — e.g. `FiberFault` for fiber-cut, `InterfaceDown` for
  interface-fault), recorded so the RCA-accuracy oracle compares the injected root cause to
  `correlation.results.rootCauseAlarmType` on the **same token space** (not on `probableCause`);
  `children` is the list of causally-downstream alarm `alarmId`s. Each `EmittedAlarm.alarmType` is
  the alarm's canonical join token, so the oracle can also key the minable signature / ground-truth
  off `alarmType` rather than `probableCause`.
- `EmittedAlarm.scenarioId` is null for noise; `isNoise=true` ⇒ `noiseClass` set; noise alarms
  (`isNoise=true`) appear in no label's `children`, which is what makes them identifiable as noise
  (criterion 6).

### Topology snapshot file schema (versioned contract — single canonical source)

**Decision (OQ-4 — resolved to the frozen Topology contract): the snapshot file schema has ONE
canonical source, `services/topology/schema/snapshot.schema.json`, owned by the Topology Service
(the validating owner). The Simulator validates its generated snapshot file against THAT same file
— it keeps NO independent copy of the schema.** Rationale: the event-model is the Kafka
envelope/payload contract; the snapshot is a file/API hand-off (not a topic), and the consuming
owner (Topology) already publishes the single schema its ingestion endpoint validates against.
Co-locating a second copy under the Simulator would risk drift between producer and validator, so
the Simulator's `snapshot_writer` loads and validates against the **canonical Topology schema**
(vendored/synced from `services/topology/schema/snapshot.schema.json` at build time, never
re-authored). Any change to that schema remains a contract change requiring an `architecture.md`
update + human approval (per spec Contract section).

> **No new contract change here.** The required **`AlarmEvent.alarmType`** field is **already merged**
> on `main` (`libs/event-model/schema/payloads/AlarmEvent.schema.json` lists `alarmType` in
> `required[]`; `architecture.md` Invariants pin it as the canonical join key with value space =
> Knowledge `alarmTypeVocabulary`). This design only makes the Simulator **populate** that existing
> required field — it adds no topic, payload, field, or OpenAPI surface. The frozen Topology ingestion
> contract (`POST /topology/snapshots` → **200 `SnapshotIngestResponse`**) and the single canonical
> snapshot schema are **also already merged/frozen** (Topology design P1-G1); the Simulator aligns to
> them. `Site`, the `LOCATED_AT` relation, the **`Interface`** object type, the
> **`HOSTS`/`TERMINATES`** relations (and `ADJACENCY_OVER` running between interfaces), the
> domain-agnostic `managedObjectId` scheme, and the well-known device/connection `attributes` keys are
> all already in the **merged multi-domain + Interface contract** (event-model #81 + the §5 Interface
> model #91 + the `architecture.md` "Topology snapshot file" / "Domain extensibility" sections).
> `alarmType` is the canonical join key (value space = Knowledge `alarmTypeVocabulary`);
> `Interface`/`HOSTS`/`TERMINATES` are **domain vocabulary** (authored in Knowledge, validated by
> Topology), not an event-model surface — the produced `AlarmEvent` and snapshot file stay conforming.
> This design only generates **conforming** data against the contract's already-published vocabulary —
> it adds no topic, payload, field, or OpenAPI surface beyond the merged contract.

The canonical schema (`services/topology/schema/snapshot.schema.json`, owned by Topology) is the
structure the ingestion API expects — `required: [schemaVersion, domain, nodes, edges]`, each node
`{managedObjectId, objectType, name?, attributes?}` and each edge `{from, to, relation, attributes?}`
under the generic `^[A-Za-z][A-Za-z0-9]*:[^:]+$` `managedObjectId` pattern. The schema deliberately
**does NOT enum the `objectType`/`relation` tokens** (it requires only the generic id pattern and a
non-empty `objectType`/`relation`); the per-domain object-type/relation vocabulary (incl. `Site`,
`Interface`, `LOCATED_AT`, `HOSTS`, `TERMINATES`) is validated **semantically by Topology against the
domain's Knowledge vocabulary** at ingest, not by this JSON Schema. The Simulator therefore (a)
JSON-Schema-validates its generated file against the canonical Topology schema, and (b) constrains
the `objectType`/`relation` tokens it emits to the **domain pack's vocabulary** — which the pack
keeps aligned to the Knowledge vocabulary Topology validates against — so a generated file Topology
accepts is produced by construction.

The `managedObjectId` pattern matches the **domain-agnostic** scheme `^[A-Za-z][A-Za-z0-9]*:[^:]+$`
in the merged event-model `managedObjectId.schema.json` (#81) — the engine reuses
`acp_event_model.validate` so the two never drift. The `objectType`/`relation` tokens the Simulator
emits (incl. the domain-agnostic **`Site`** / **`LOCATED_AT`** and the Core-IP **`Interface`** object
type with the **`HOSTS`**/**`TERMINATES`** relations) and the well-known `attributes` keys come from
the **domain pack's vocabulary**, the same vocabulary Topology validates the upload against (authored
canonically in Knowledge). **Well-known `attributes` keys** — devices: `vendor`, `model`,
`equipmentType`, `role`, `capacity`; connections: `linkType`, `capacity`, `protectionRole`; `Site`:
`name`, `latitude`, `longitude`, `region`. The set is open (extensible per domain), so `attributes`
stays an open object. Referential integrity (every edge endpoint resolves to a node in `nodes[]`;
every device has exactly one `LOCATED_AT` edge to a `Site`; every `Port` `HOSTS` ≥1 `Interface` and
each `Interface` `TERMINATES` exactly one `IPLink`; the IGP adjacency is `ADJACENCY_OVER` an
`Interface`, never a `Port`/`IPLink` directly; no dangling references) is enforced by
`snapshot_writer` post-build validation (criteria 1, 14, 25, 26, 28).

## Event handling

- **Consumers:** **none.** The Simulator is a pure Kafka producer (spec Contract: "Consumes (Kafka):
  none"). There is no inbound stream, hence **no DLQ** (spec Non-functional confirms this). `*.dlq`
  routing is N/A for this service.
- **Producers:**
  - `alarms.history` — `TypedEnvelope[AlarmEvent]`, batch (history mode).
  - `alarms.live` — `TypedEnvelope[AlarmEvent]`, wall-clock paced (live mode).
  - Both carry the frozen `AlarmEvent` payload (envelope `source="simulator"`, `type="AlarmEvent"`,
    `schemaVersion=1`). Serialized via `acp_event_model.serialize`. **Every payload includes the
    required canonical `alarmType`** token (a `alarmTypeVocabulary` member from the pack's
    `alarm_shape`) alongside `eventType`/`probableCause` — the Simulator is the **origin** of these
    alarms and is therefore one of the two `alarmType` populators (with Enrichment), per the
    `architecture.md` alarmType invariant.
- **Idempotency:** every emitted event gets a fresh UUID `eventId` (envelope) and a unique `alarmId`
  (payload) so at-least-once redelivery is dedupable downstream on `eventId`/`alarmId`. Producer is
  configured `enable.idempotence=true`, `acks=all`. **Re-runs with the same `SIM_SEED`** reproduce
  the same *cascade structure and timing* (deterministic generation, OQ-3 decision below) but mint
  **new** `eventId`/`alarmId` per run (spec Non-functional: "re-runs SHOULD produce new ids"), so a
  replayed corpus never collides with a prior run's ids.

## API contracts / API schema

The Simulator exposes a **small read-only HTTP surface** (FastAPI) for ground-truth retrieval +
liveness/metrics. OpenAPI 3.1 is auto-generated by FastAPI and served at `/openapi.json`; the
generated document is checked in as `services/simulator/openapi.json` (authoritative surface; any
change is a contract change). Request/response models are Pydantic.

| Method | Path | Request | Response (200) | Errors |
|---|---|---|---|---|
| GET | `/health` | — | `{"status":"ok","kafka":"connected","run":"<runId|idle>"}` | `503` if startup incomplete or Kafka connection lost |
| GET | `/metrics` | — | Prometheus text exposition (`Content-Type: text/plain`); ≥1 metric e.g. `simulator_alarms_emitted_total` | — |
| GET | `/openapi.json` | — | OpenAPI 3.1 document | — |
| GET | `/labels` | query `?scenarioId=` optional | `GroundTruthLabel[]` — **frozen shape** `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children[]}` | `404` unknown `scenarioId` |
| GET | `/labels/{scenarioId}` | path | one `GroundTruthLabel` (same frozen shape) | `404` |
| GET | `/scenarios` | — | scenario-def summary (type, root-cause object type, root-cause `alarmType`, expected child count) | — |
| POST | `/runs` (optional control) | `{mode:"history"|"live", config?}` | `{runId}` accepted `202` | `400` invalid config, `409` run in progress |

**`/labels` response is frozen** at `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId,
rootCauseAlarmType, children[]}`. `rootCauseAlarmType` is the canonical `alarmType` token of the
injected root cause (a Knowledge `alarmTypeVocabulary` member), added so the RCA-accuracy oracle can
compare the injected root cause to `correlation.results.rootCauseAlarmId` / `rootCauseAlarmType` on
the **same token space** (like-for-like, not `probableCause`). `/labels` is the REST mirror of the
canonical JSONL file export (which carries the identical fields); the integration oracle MAY use
either. No write endpoints expose alarm content (read-only oracle).

## Integration points (mock vs. real)

No collaborator URL is hard-coded; all resolve from env. Mock = a stub generated from the
collaborator's **published OpenAPI** (used in unit tests); real = the live service (integration).

| Collaborator + operation | Config key(s) | mock | real |
|---|---|---|---|
| **Topology Service — ingestion API** (`POST /topology/snapshots`; upload snapshot file; lift → graph → `topology.changed`) | `TOPOLOGY_API_MODE` (`mock`\|`real`), `TOPOLOGY_API_BASE_URL` | local stub generated from Topology's published OpenAPI 3.1 — records the uploaded file, returns a synthetic **200** `SnapshotIngestResponse` `{snapshotId, domain, status, nodeCount, edgeCount, changeType}`; never contacts a real service | `httpx` client `POST {TOPOLOGY_API_BASE_URL}/topology/snapshots` → reads `snapshotId` from the **200** `SnapshotIngestResponse` body |
| **Knowledge Service — scenario config** (optional read of scenario/jitter/noise params) | `KNOWLEDGE_MODE` (`local`\|`real`), `KNOWLEDGE_API_BASE_URL` | `local` = read scenario/threshold config from local files (default) | `real` = fetch from Knowledge Service API |
| **Kafka** (produce `alarms.history`/`alarms.live`) | `KAFKA_BOOTSTRAP_SERVERS` | embedded/in-memory producer double in unit tests | real broker in integration |

The Topology upload client is built **against Topology's published OpenAPI, never its source**
(invariant: contract-first, no cross-service code coupling). `TOPOLOGY_API_MODE` switching requires
no code change (criterion 15).

**Frozen ingestion contract (Topology P1-G1).** The Simulator's upload aligns exactly to Topology's
**frozen** ingestion operation: `POST /topology/snapshots` with the snapshot file as the JSON body,
returning **HTTP 200** (synchronous — `snapshotId` is minted inline during the lift, **not** 202
async) with body `SnapshotIngestResponse { snapshotId, domain, status, nodeCount, edgeCount,
changeType }`. The client reads **`snapshotId` from the 200 body** and records it on the `SimRun` so
later P2/P3 alarms share the same `snapshotId` identity. The Simulator validates its generated file
against the **single canonical schema `services/topology/schema/snapshot.schema.json`** (Topology
owns + publishes it; the Simulator keeps no independent copy) before upload, so Topology's ingest
JSON-Schema check passes by construction. (The earlier Simulator assumption of a `202 {snapshotId}`
bare body is corrected here to the frozen `200 SnapshotIngestResponse`.)

## Key flows (sequence / data-flow diagrams)

### (a) P1 — topology generation → snapshot file → upload to Topology ingestion API

```mermaid
sequenceDiagram
  participant CLI as main (P1)
  participant TB as topology_builder
  participant Pack as coreip pack
  participant SW as snapshot_writer
  participant Val as jsonschema + event-model validate
  participant TC as topology_client (mock/real)
  participant Topo as Topology ingestion API

  CLI->>TB: build_topology(size=N, seed)
  TB->>Pack: build_topology(graph, N, rng)
  Pack-->>TB: typed nodes plus Site nodes plus Interface nodes plus edges incl LOCATED_AT HOSTS TERMINATES plus attributes (managedObjectIds minted)
  TB-->>CLI: networkx DiGraph
  CLI->>SW: write_snapshot(graph)
  SW->>Val: validate every managedObjectId plus canonical Topology snapshot schema plus refs
  Val-->>SW: ok (no dangling refs)
  SW-->>CLI: snapshot-(runId).json
  CLI->>TC: upload(snapshot file)
  alt TOPOLOGY_API_MODE=mock
    TC-->>CLI: 200 SnapshotIngestResponse stub, no network
  else real
    TC->>Topo: POST /topology/snapshots (file body)
    Topo-->>TC: 200 SnapshotIngestResponse snapshotId domain status nodeCount edgeCount changeType
    TC-->>CLI: snapshotId from 200 body
  end
  CLI->>CLI: record snapshotId on SimRun (shared with later alarms)
```

### (b) P2 — labeled scenario generation → cascade + noise → batch replay to `alarms.history`

```mermaid
sequenceDiagram
  participant CLI as main (P2 history)
  participant SL as scenario_loader
  participant SR as scenario_runner
  participant C as cascade (forward-prop)
  participant N as noise
  participant L as labels
  participant R as BatchReplay
  participant KP as kafka_producer
  participant K as Kafka alarms.history

  CLI->>SL: load(scenarios, instances, jitter, intervals, noiseMix, background, window)
  SL-->>CLI: scenario defs + synthesis params
  loop each scenario x SCENARIO_INSTANCES
    SR->>C: propagate(randomFaultOrigin, templates, graph, baseInterval+jitter)
    C-->>SR: ordered [rootCauseAlarm, childAlarm...]
    SR->>L: record {rootCause, children}
  end
  SR->>N: generate noise (3plus classes, NOISE_RATE, HARD_NOISE_FRACTION near cascades)
  N-->>SR: noise alarms (not in any label.children)
  SR->>SR: add BACKGROUND_FRACTION non-pattern alarms (in no label.children)
  SR->>R: merge + spread over [HISTORY_START, HISTORY_END]
  loop each alarm
    R->>KP: serialize TypedEnvelope[AlarmEvent]
    KP->>K: produce to alarms.history (acks=all, idempotent)
  end
  R-->>CLI: counts, then L.export_to_file writes labels-runId.jsonl
```

P2 batch timing: relative inter-event gaps (`BASE_INTERVAL_MS`/`BACKGROUND_INTERVAL_MS` + jitter)
are computed first, then the whole ordered stream is **mapped onto the `[HISTORY_START,
HISTORY_END]` wall-clock window** so each alarm's `raisedAt`/`occurredAt` falls inside the
configured historical window (batch emit is still fire-and-flush — the window sets the timestamps,
not the emit rate). P3 instead uses live wall-clock + `PACING_MULTIPLIER` (below).

### (c) P3 — live replay to `alarms.live` (wall-clock paced)

```mermaid
sequenceDiagram
  participant CLI as main (P3 live)
  participant SR as scenario_runner
  participant R as LiveReplay
  participant Clock as wall clock
  participant KP as kafka_producer
  participant K as Kafka alarms.live

  CLI->>SR: build labeled+noise+background stream (same synthesis as P2)
  SR-->>R: time-ordered alarms with relative offsets
  loop each alarm i
    R->>Clock: sleep(offset_i * PACING_MULTIPLIER + jitter)
    Clock-->>R: elapsed
    R->>KP: serialize TypedEnvelope[AlarmEvent]
    KP->>K: produce to alarms.live
  end
  Note over R,K: zero events to alarms.history, inter-event delay above 0 for pacing above 0
```

## Algorithm logical flow

Two non-trivial algorithms: the **§5 forward-propagation cascade** and the **domain-pack
abstraction** boundary.

### Cascade / scenario generation (forward propagation per §5 templates)

Inputs: a chosen `rootCauseNode` (type ∈ fault-origins `{Fiber, LineCard, Port, Interface, Node}`
from the pack — `Interface` is a first-class fault origin per §5 #91), the pack's per-edge-relation
propagation templates, the `networkx` graph, and jitter params (from config/Knowledge — never
hard-coded). Output: an ordered alarm list + a `{rootCause, children}` label.

```mermaid
flowchart TD
  A[Pick scenario from library:<br/>fiber-cut / line-card-fault / port-fault / interface-fault] --> B[Select root-cause object<br/>of the scenario fault-origin type]
  B --> C["Emit root-cause alarm<br/>pack.alarm_shape sets canonical alarmType plus X.733 fields<br/>(FiberFault or LOS for fiber-cut, InterfaceDown for interface-fault, ...)"]
  C --> D[Seed BFS frontier with root-cause node]
  D --> E{Frontier empty?}
  E -- yes --> Z["Return ordered alarms plus<br/>label rootCause, rootCauseAlarmType, children"]
  E -- no --> F[Pop node, find outgoing edges<br/>whose relation has a template]
  F --> G["Apply template per edge relation, child carries the canonical effect alarmType:<br/>RIDES_ON Fiber to LinkDown IPLink<br/>HOSTED_ON LineCard to PortDown<br/>HOSTS PortDown to InterfaceDown<br/>TERMINATES InterfaceDown to LinkDown<br/>ADJACENCY_OVER InterfaceDown to AdjDown<br/>TRAVERSES LinkDown to LSPDown<br/>SERVES LSPDown to ReachabilityLoss VPN"]
  G --> H[Emit child alarm with the effect alarmType plus its X.733 shape;<br/>raisedAt equals parent.raisedAt plus base_delay plus jitter]
  H --> I[Add child node to frontier;<br/>append child alarmId to label.children]
  I --> E
```

Notes:
- The template effect names (`LinkDown`, `PortDown`, `InterfaceDown`, `AdjDown`, `LSPDown`,
  `ReachabilityLoss`) **are the canonical `alarmType` tokens** — each is set on the emitted
  `AlarmEvent.alarmType` (the join key), alongside the X.733 `eventType`/`probableCause` the pack's
  `alarm_shape` returns. `rootCauseAlarmType` on the label is the root alarm's `alarmType` token.
- Jitter is applied per inter-alarm gap: `delay = base_delay + gauss(0, jitter_stddev_ms)`. With
  `jitter_stddev_ms=0` the cascade is deterministic (criterion 12). `MEMBER_OF` (SRLG) fate-sharing
  expands a single fiber/link fault to all co-grouped links before propagating onward.
- All template data, the canonical `alarmType` tokens, and the X.733 alarm shapes come from the
  pack; `cascade.py` only walks edges and applies whatever template/shape the pack provides → engine
  stays domain-agnostic (criterion 19).

### Randomization scope (what is random, how it stays grounded)

Everything stochastic in the synthesizer is driven by **one seeded RNG** (`SIM_SEED` → a single
`random.Random`/`numpy.random.Generator` threaded through `topology_builder`, `scenario_runner`,
`cascade`, and `noise`). Same seed → identical run (criterion 12 determinism). Every random choice
is **bounded by the pack's grounded model** so the §5 propagation grounding is never violated.

| What is randomized | How | Grounding constraint (never violated) |
|---|---|---|
| **Fault-origin instance** per scenario | RNG picks uniformly among the **valid fault-origin-type instances** the pack exposes for that scenario (e.g. a `FiberSpan` for fiber-cut, a `LineCard` for line-card-fault, a `Port` for port-fault, an `Interface` for interface-fault) | only object instances of the scenario's declared fault-origin type are eligible; cascade then follows the pack templates — no impossible origin |
| **Child-alarm timing** | `delay = BASE_INTERVAL_MS + gauss(0, JITTER_STDDEV_MS)` per inter-alarm gap | delay clamped ≥ 0; ordering still respects the causal BFS — a child never precedes its parent |
| **Noise placement / object / class / timing** | RNG selects noise class per `NOISE_MIX`, the target object, and the time offset; `HARD_NOISE_FRACTION` decides near-cascade vs. clearly-separate placement | noise objects/shapes come from the pack's noise library; a noise alarm is **never** added to any label's `children` (criterion 6) |
| **Scenario-instance ordering / interleaving** | the `SCENARIO_INSTANCES` copies of each scenario and the background alarms are interleaved on the timeline by the RNG | each instance is an independent cascade with its own ids; interleaving never merges two scenarios' children |
| **Background-alarm objects / timing** | RNG places `BACKGROUND_FRACTION` of alarms on random in-topology objects at `BACKGROUND_INTERVAL_MS` spacing | background alarms are valid X.733 alarms on real objects but belong to no scenario → in no label's `children` |

Because every choice is constrained to pack-valid instances and the cascade always replays the §5
templates over the real graph closure, **randomization produces variety, never an impossible
cascade or an off-contract alarm shape**. The chosen fault-origin instances, child timing, noise,
and interleaving differ run-to-run only when the seed differs.

### Synthesis strategy / evaluation-grade data (P2 / P3)

The synthesizer does **not** emit an arbitrary alarm soup — it deliberately produces a corpus that
**strongly exercises the downstream learning + noise services** and is **sufficient to compute the
§10 thresholds meaningfully**. Three properties are engineered in, all config-driven with smart
defaults (a default run is evaluation-grade without tuning):

1. **Repeated pattern instances (minable support).** Each selected scenario is injected
   `SCENARIO_INSTANCES` times (**default 8**, range 5–10). The **Pattern Miner uses PrefixSpan**,
   which only mines a sequence whose support clears a minimum-support threshold — a *single*
   instance of a cascade can never be mined. Injecting N≈8 grounded repetitions of each scenario
   signature **guarantees minable support** for every injected pattern, so pattern quality
   (recovered ÷ injected, §10 ≥ 0.80) is measurable rather than vacuously zero. Each instance is an
   independent cascade (fresh ids, possibly different fault-origin instance) but the **same ordered
   canonical `alarmType` signature** (the join token the downstream chain mines on — not
   `probableCause`), which is exactly what PrefixSpan recovers.

2. **Fair share of non-pattern / background alarms.** `BACKGROUND_FRACTION` (**default 0.3**) of
   emitted alarms belong to **no injected pattern** — valid alarms on real objects that appear in
   **no** label's `children`. This forces pattern learning to *discriminate signal from background*
   (a corpus of pure cascades would let any miner "win" trivially) and makes the alarm-reduction
   metric (§10 ≥ 5×) meaningful: there is genuine volume to reduce. This extends the existing noise
   mechanism (label-absence ⇒ not signal) into a deliberate, tunable background fraction.

3. **Evaluation-grade noise for DBSCAN (hard cases).** The **Noise Filter clusters with DBSCAN**
   (dense incident clusters vs. sparse outliers). Easy, clearly-separate noise is trivially
   filtered and would over-state effectiveness. So `HARD_NOISE_FRACTION` (**default 0.4**) of noise
   is placed **near a cascade in time and/or on a topology-adjacent object** — sitting close to a
   dense incident cluster so DBSCAN must separate it under stress — while the remainder is
   clearly-separate easy noise. This stresses both removal (§10 ≥ 0.90) and retention (real alarms
   kept, §10 ≥ 0.95): a filter that just deletes everything near a cluster fails retention; one
   that keeps everything fails removal.

**Sufficiency for the §10 thresholds.** Putting the three together, a default P2/P3 run yields:
enough *repeated* pattern instances to recover (pattern quality), enough *labeled* scenarios to
score root-cause matches (RCA accuracy), enough *background volume* to reduce (alarm-reduction),
and enough *hard+easy noise* to remove while retaining signal (noise-filter removal/retention). The
ground-truth labels (§ Data model) and the per-class noise tagging are the oracle that lets the
integration harness compute each metric.

```mermaid
flowchart TD
  CFG[Config + domain pack] --> SYN[Synthesizer]
  SYN --> P[Repeated pattern instances<br/>SCENARIO_INSTANCES default 8 per scenario]
  SYN --> B[Background / non-pattern alarms<br/>BACKGROUND_FRACTION default 0.3]
  SYN --> NH[Hard noise near cascades<br/>HARD_NOISE_FRACTION default 0.4]
  SYN --> NE[Easy / clearly-separate noise]
  P --> MINE[Pattern Miner / PrefixSpan<br/>minable support, pattern quality]
  B --> MINE
  B --> RED[Alarm-reduction ratio, volume to reduce]
  NH --> DB[Noise Filter / DBSCAN<br/>removal under stress + retention]
  NE --> DB
  P --> RCA[Labeled root causes, RCA accuracy]
```

### Domain-pack abstraction (engine vs. Core-IP pack)

```mermaid
flowchart LR
  E[Engine needs:<br/>types? edges? templates?<br/>alarm shape? scenarios? noise?] --> Q{Resolve via<br/>DomainPack Protocol}
  Q --> P[coreip pack supplies<br/>concrete values]
  P --> R[Engine applies generically]
  E -. forbidden .-> X[Core-IP literal in engine/ ?]
  X -. fails criterion-19 test .-> R
```

Decision logic: the engine **never branches on a domain type name**; it iterates over
`pack.object_types()`, `pack.edge_relations()`, and `pack.propagation_templates()`. Adding a new
domain = adding a new `DomainPack` implementation, no engine edit.

## Seed data & examples

The simulator's heart: generation/seed scripts + config knobs + concrete worked output.

### CLI usage (the uniform interface)

There is **one CLI** — `python -m simulator.main` — with a **phase/mode selector**. The phase
chooses *what the run does*; everything else is env config (see the authoritative defaults table in
*Config & observability*). `--help` prints exactly this usage and exits `0`.

```text
usage: python -m simulator.main --phase {p1,p2,p3} [--mode {upload,history,live}]
                                [--config PATH] [--dry-run] [--help]

One simulator, three phases (the phase selects which generation/replay flow runs):

  --phase p1   Ingest topology  : build the typed Core-IP topology, write the
                                  versioned snapshot file, and upload it to the
                                  Topology ingestion API (mock or real per env).
                                  No Kafka emission.
  --phase p2   Historical alarms: synthesize the labeled corpus (scenarios x N
                                  instances + background + noise) and BATCH-replay
                                  it to alarms.history, spread over the configured
                                  history time window. Writes the label export.
  --phase p3   Live alarms      : synthesize the same labeled stream and replay it
                                  to alarms.live wall-clock paced (PACING_MULTIPLIER).
                                  Writes the label export.

Options:
  --mode {upload,history,live}  Optional explicit alias for the phase action
                                (upload=p1, history=p2, live=p3). If both --phase
                                and --mode are given they must agree, else exit 2.
  --config PATH                 Path to a scenario/config file (overrides the
                                KNOWLEDGE_MODE=local default file location).
  --dry-run                     Build + validate + log the planned run (counts,
                                time window, target topic) WITHOUT emitting or
                                uploading. Verifies config; exits 0 on valid config.
  --help                        Print this usage and exit 0.

Exit codes:
  0   success (run completed, or --help, or --dry-run on valid config)
  2   invalid CLI usage (bad/missing --phase, conflicting --phase/--mode)
  3   invalid or missing required config (validated at startup; structured-log
      error, ZERO events emitted) — see criterion 18
  4   dependency failure (Topology ingestion API unreachable after bounded retry,
      or Kafka broker unreachable / persistent produce failure) — run fails loudly
```

Every exit path emits a structured JSON log line. Any non-zero exit before emission guarantees
zero events were produced (criterion 18). Runnable straight from this doc:

```bash
# P1 — build + upload topology against a mock Topology (no broker needed)
TOPOLOGY_API_MODE=mock python -m simulator.main --phase p1

# P2 — evaluation-grade history corpus to alarms.history (all defaults; minimal env)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 python -m simulator.main --phase p2

# P3 — live paced stream to alarms.live
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 python -m simulator.main --phase p3
```

### Generation scripts & knobs

All knobs are env/config (no hard-coded values). The authoritative **DEFAULTS table** is in
*Config & observability* below — every knob has a default, and **defaults apply when a var is not
provided**, so `python -m simulator.main --phase p2` runs an evaluation-grade demo with minimal
env (only `KAFKA_BOOTSTRAP_SERVERS`). Summary of the knob groups:

| Knob group | Env var(s) | Effect |
|---|---|---|
| Topology size | `TOPOLOGY_NODE_COUNT` | number of `Node`s; line cards/ports/links scale per pack ratios |
| Site count | `SITE_COUNT` | number of `Site` nodes generated (geo attrs); devices distributed across them via `LOCATED_AT` |
| Devices per site | `DEVICES_PER_SITE` | target devices placed per site (rounds out as node count varies) |
| Interfaces per port | `INTERFACES_PER_PORT` | number of `Interface` (L3 endpoint) nodes the pack `HOSTS` on each `Port` (§5 #91); each `TERMINATES` an `IPLink` and carries the IGP adjacency |
| Random seed | `SIM_SEED` | **OQ-3 decision: deterministic generation supported** — same seed reproduces topology, cascade structure, timing offsets, and all RNG-driven choices; ids still fresh per run. Unset → random seed, logged so the run stays reproducible by re-supplying it |
| Timing jitter | `JITTER_STDDEV_MS` | std-dev of per-gap delay noise (Gaussian, applied on top of the base interval) |
| Inter-arrival interval | `BASE_INTERVAL_MS`, `BACKGROUND_INTERVAL_MS` | base inter-event spacing inside a cascade, and mean spacing between background/non-pattern alarms (jitter applied on top) |
| Noise rate/mix | `NOISE_RATE`, `NOISE_MIX` | fraction of total alarms that are noise + class weights |
| Background fraction | `BACKGROUND_FRACTION` | fraction of emitted alarms belonging to no injected pattern (signal-vs-background discrimination) |
| Scenario selection | `SCENARIOS` | which scenarios to inject |
| Scenario-instance count | `SCENARIO_INSTANCES` | how many times **each** selected scenario is injected (drives minable pattern support) |
| History time window | `HISTORY_START`, `HISTORY_END` (or `HISTORY_DURATION`) | P2 wall-clock window the historical alarms are spread over |
| Replay pacing | `PACING_MULTIPLIER` | wall-clock scale of inter-event delays (live only) |
| Hard-noise fraction | `HARD_NOISE_FRACTION` | fraction of noise placed *near* a cascade in time/object (DBSCAN stress) vs. clearly-separate easy noise |

`settings` / `scenario_loader` validate (fail-fast, criterion 18): jitter ≥ 0, base/background
interval > 0, noise rate ∈ [0,1], `BACKGROUND_FRACTION` ∈ [0,1], `HARD_NOISE_FRACTION` ∈ [0,1],
`SCENARIO_INSTANCES` ≥ 1, scenarios ⊆ pack library (incl. `interface-fault`), node count ∈
[10,200], `SITE_COUNT` ∈ [1, node count] (and `DEVICES_PER_SITE` ≥ 1 when set),
`INTERFACES_PER_PORT` ∈ [1,8], and (P2)
`HISTORY_START` < `HISTORY_END`. Missing required config (e.g. `KAFKA_BOOTSTRAP_SERVERS`) → fatal
structured-log error + non-zero exit (`3`) before any emission (criterion 18).

### Worked example — topology snapshot file fragment (small N, fiber-cut-ready)

`snapshot-run42.json` carries the `domain` identifier; **`Site` nodes** with geo attributes,
**devices LOCATED_AT a site**, and the well-known device/connection `attributes` keys are all
present (ids in the generic `<objectType>:<id>` scheme):

```json
{
  "schemaVersion": 1,
  "domain": "core-ip",
  "nodes": [
    { "managedObjectId": "Site:LON-01",         "objectType": "Site",         "attributes": {"name": "London Docklands", "latitude": 51.5033, "longitude": -0.0195, "region": "UK-South"} },
    { "managedObjectId": "Site:MAN-01",         "objectType": "Site",         "attributes": {"name": "Manchester Central", "latitude": 53.4779, "longitude": -2.2426, "region": "UK-North"} },
    { "managedObjectId": "Node:PE1",            "objectType": "Node",         "attributes": {"vendor": "Acme", "model": "XR-9000", "equipmentType": "router", "role": "PE", "capacity": "1.6Tbps"} },
    { "managedObjectId": "Node:P1",             "objectType": "Node",         "attributes": {"vendor": "Acme", "model": "XR-9000", "equipmentType": "router", "role": "P", "capacity": "1.6Tbps"} },
    { "managedObjectId": "LineCard:PE1-LC2",    "objectType": "LineCard",     "attributes": {"vendor": "Acme", "model": "LC-48x100G", "equipmentType": "lineCard", "role": "transport", "capacity": "4.8Tbps"} },
    { "managedObjectId": "Port:PE1-LC2-P3",     "objectType": "Port",         "attributes": {"vendor": "Acme", "model": "QSFP28", "equipmentType": "port", "role": "core", "capacity": "100G"} },
    { "managedObjectId": "Port:P1-LC1-P1",      "objectType": "Port",         "attributes": {"vendor": "Acme", "model": "QSFP28", "equipmentType": "port", "role": "core", "capacity": "100G"} },
    { "managedObjectId": "Interface:PE1-LC2-P3-if0", "objectType": "Interface", "attributes": {"name": "TenGigE0/2/0/3", "addressFamily": "ipv4", "role": "core"} },
    { "managedObjectId": "Interface:P1-LC1-P1-if0",  "objectType": "Interface", "attributes": {"name": "TenGigE0/1/0/1", "addressFamily": "ipv4", "role": "core"} },
    { "managedObjectId": "IPLink:PE1_P1",       "objectType": "IPLink" },
    { "managedObjectId": "IGPAdjacency:PE1_P1", "objectType": "IGPAdjacency" },
    { "managedObjectId": "LSP:PE1-PE9-1",       "objectType": "LSP" },
    { "managedObjectId": "VPNService:CUST-A",   "objectType": "VPNService" },
    { "managedObjectId": "FiberSpan:F-PE1-P1",  "objectType": "FiberSpan" },
    { "managedObjectId": "SRLG:SRLG-7",         "objectType": "SRLG" }
  ],
  "edges": [
    { "from": "Node:PE1",           "to": "Site:LON-01",         "relation": "LOCATED_AT" },
    { "from": "Node:P1",            "to": "Site:MAN-01",         "relation": "LOCATED_AT" },
    { "from": "LineCard:PE1-LC2",   "to": "Port:PE1-LC2-P3",     "relation": "HOSTED_ON" },
    { "from": "Port:PE1-LC2-P3",    "to": "Interface:PE1-LC2-P3-if0", "relation": "HOSTS" },
    { "from": "Port:P1-LC1-P1",     "to": "Interface:P1-LC1-P1-if0",  "relation": "HOSTS" },
    { "from": "Interface:PE1-LC2-P3-if0", "to": "IPLink:PE1_P1", "relation": "TERMINATES" },
    { "from": "Interface:P1-LC1-P1-if0",  "to": "IPLink:PE1_P1", "relation": "TERMINATES" },
    { "from": "FiberSpan:F-PE1-P1", "to": "IPLink:PE1_P1",       "relation": "RIDES_ON",       "attributes": {"linkType": "fiber", "capacity": "100G", "protectionRole": "working"} },
    { "from": "Interface:PE1-LC2-P3-if0", "to": "IGPAdjacency:PE1_P1", "relation": "ADJACENCY_OVER" },
    { "from": "IPLink:PE1_P1",      "to": "LSP:PE1-PE9-1",       "relation": "TRAVERSES" },
    { "from": "LSP:PE1-PE9-1",      "to": "VPNService:CUST-A",   "relation": "SERVES" },
    { "from": "SRLG:SRLG-7",        "to": "IPLink:PE1_P1",       "relation": "MEMBER_OF" }
  ]
}
```

`Site:LON-01` carries geo attributes (`name`/`latitude`/`longitude`/`region`); each device node
carries the well-known device keys (`vendor`/`model`/`equipmentType`/`role`/`capacity`) and is
placed in a site via a `LOCATED_AT` edge; connection edges carry `linkType`/`capacity`/
`protectionRole` where sensible. `SITE_COUNT`/`DEVICES_PER_SITE` control how many sites are
generated and how devices distribute across them; attribute *values* are pack-grounded (a small
catalogue of realistic vendors/models/regions, config-influenced where useful).

The fragment also shows the §5 #91 **Interface layer**: `Port:PE1-LC2-P3` `HOSTS`
`Interface:PE1-LC2-P3-if0` (an L3 endpoint, default `INTERFACES_PER_PORT=1`), that interface
`TERMINATES` `IPLink:PE1_P1`, and the IGP adjacency is `ADJACENCY_OVER` the **interface**
(`Interface:PE1-LC2-P3-if0 → IGPAdjacency:PE1_P1`), **not** the port or the link directly — IGP/BGP
sessions run between interfaces. The peer side mirrors it (`Port:P1-LC1-P1` HOSTS
`Interface:P1-LC1-P1-if0`, which also TERMINATES the same `IPLink`). `INTERFACES_PER_PORT` (default
1) sets how many interfaces each port hosts; interface `attributes` carry descriptive keys (`name`,
`addressFamily`, `role`) from the pack catalogue.

### Worked example — fiber-cut cascade `AlarmEvent` records + ground-truth label

A `fiber-cut` on `FiberSpan:F-PE1-P1` propagates `RIDES_ON → TRAVERSES → SERVES` (LinkDown →
LSPDown → ReachabilityLoss); the interfaces terminating the down link also lose their adjacency
(`TERMINATES`/`ADJACENCY_OVER` from the affected interfaces drives the `AdjDown`). Each emitted
envelope (`source="simulator"`, fresh `eventId`); payload is the frozen `AlarmEvent`. **Every
payload carries the required canonical `alarmType`** join token (a `alarmTypeVocabulary` member),
distinct from `eventType` (X.733 category) and `probableCause` (X.733 probable cause):

```json
{ "eventId":"a1...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T10:00:00Z","source":"simulator","traceId":"sc-fiber-001",
  "payload":{ "alarmId":"ALM-FC-0001","managedObjectId":"FiberSpan:F-PE1-P1","alarmType":"FiberFault","eventType":"communicationsAlarm","probableCause":"lossOfSignal","perceivedSeverity":"critical","raisedAt":"2026-06-10T10:00:00.000Z","state":"raised","trailIds":[] } }

{ "eventId":"a2...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T10:00:00Z","source":"simulator","traceId":"sc-fiber-001",
  "payload":{ "alarmId":"ALM-FC-0002","managedObjectId":"IPLink:PE1_P1","alarmType":"LinkDown","eventType":"communicationsAlarm","probableCause":"linkDown","perceivedSeverity":"critical","raisedAt":"2026-06-10T10:00:00.420Z","state":"raised","trailIds":[] } }

{ "eventId":"a3...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T10:00:01Z","source":"simulator","traceId":"sc-fiber-001",
  "payload":{ "alarmId":"ALM-FC-0003","managedObjectId":"IGPAdjacency:PE1_P1","alarmType":"AdjDown","eventType":"communicationsAlarm","probableCause":"adjacencyDown","perceivedSeverity":"major","raisedAt":"2026-06-10T10:00:00.880Z","state":"raised","trailIds":[] } }

{ "eventId":"a4...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T10:00:01Z","source":"simulator","traceId":"sc-fiber-001",
  "payload":{ "alarmId":"ALM-FC-0004","managedObjectId":"LSP:PE1-PE9-1","alarmType":"LSPDown","eventType":"communicationsAlarm","probableCause":"lspDown","perceivedSeverity":"major","raisedAt":"2026-06-10T10:00:01.310Z","state":"raised","trailIds":[] } }

{ "eventId":"a5...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T10:00:01Z","source":"simulator","traceId":"sc-fiber-001",
  "payload":{ "alarmId":"ALM-FC-0005","managedObjectId":"VPNService:CUST-A","alarmType":"ReachabilityLoss","eventType":"qualityOfServiceAlarm","probableCause":"reachabilityLoss","perceivedSeverity":"critical","raisedAt":"2026-06-10T10:00:01.790Z","state":"raised","trailIds":[] } }
```

Each alarm's `alarmType` (e.g. `FiberFault` on the `FiberSpan`, `LinkDown` on the `IPLink`) is the
canonical join token; `eventType`/`probableCause` keep their X.733 meanings (`communicationsAlarm`/
`lossOfSignal`). Ground-truth label (one record in `labels-run42.jsonl`), now carrying the
root-cause canonical `alarmType` token (`rootCauseAlarmType`):

```json
{ "scenarioId":"sc-fiber-001","scenarioType":"fiber-cut",
  "rootCause":"ALM-FC-0001","rootCauseManagedObjectId":"FiberSpan:F-PE1-P1","rootCauseAlarmType":"FiberFault",
  "children":["ALM-FC-0002","ALM-FC-0003","ALM-FC-0004","ALM-FC-0005"] }
```

A noise alarm emitted in the same run (e.g. flapping on an unrelated port) still carries the
**required canonical `alarmType`** (a valid `alarmTypeVocabulary` token — noise is identified by its
**absence from any label's `children`**, never by a distinct alarmType), its own `alarmId`, and
appears in **no** label's `children`, so the oracle classifies it as noise:

```json
{ "eventId":"n1...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T10:00:00Z","source":"simulator","traceId":"noise-0001",
  "payload":{ "alarmId":"NSE-0001","managedObjectId":"Port:P3-LC1-P7","alarmType":"PortDown","eventType":"qualityOfServiceAlarm","probableCause":"thresholdCrossed","perceivedSeverity":"warning","raisedAt":"2026-06-10T10:00:00.250Z","state":"raised","trailIds":[] } }
```

### Worked example — interface-fault cascade `AlarmEvent` records + ground-truth label (§5 #91)

`Interface` is a first-class fault origin. An `interface-fault` on `Interface:PE1-LC2-P3-if0`
propagates `TERMINATES → ADJACENCY_OVER → TRAVERSES → SERVES` — i.e.
`InterfaceDown ⇒ LinkDown ⇒ AdjDown` (the adjacency on that interface), with `LinkDown` then
fanning out to `LSPDown` and `ReachabilityLoss`. The root alarm sits on the `Interface`, not the
port or link:

```json
{ "eventId":"i1...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T11:00:00Z","source":"simulator","traceId":"sc-iface-001",
  "payload":{ "alarmId":"ALM-IF-0001","managedObjectId":"Interface:PE1-LC2-P3-if0","alarmType":"InterfaceDown","eventType":"communicationsAlarm","probableCause":"interfaceDown","perceivedSeverity":"major","raisedAt":"2026-06-10T11:00:00.000Z","state":"raised","trailIds":[] } }

{ "eventId":"i2...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T11:00:00Z","source":"simulator","traceId":"sc-iface-001",
  "payload":{ "alarmId":"ALM-IF-0002","managedObjectId":"IPLink:PE1_P1","alarmType":"LinkDown","eventType":"communicationsAlarm","probableCause":"linkDown","perceivedSeverity":"critical","raisedAt":"2026-06-10T11:00:00.410Z","state":"raised","trailIds":[] } }

{ "eventId":"i3...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T11:00:00Z","source":"simulator","traceId":"sc-iface-001",
  "payload":{ "alarmId":"ALM-IF-0003","managedObjectId":"IGPAdjacency:PE1_P1","alarmType":"AdjDown","eventType":"communicationsAlarm","probableCause":"adjacencyDown","perceivedSeverity":"major","raisedAt":"2026-06-10T11:00:00.880Z","state":"raised","trailIds":[] } }

{ "eventId":"i4...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T11:00:01Z","source":"simulator","traceId":"sc-iface-001",
  "payload":{ "alarmId":"ALM-IF-0004","managedObjectId":"LSP:PE1-PE9-1","alarmType":"LSPDown","eventType":"communicationsAlarm","probableCause":"lspDown","perceivedSeverity":"major","raisedAt":"2026-06-10T11:00:01.300Z","state":"raised","trailIds":[] } }

{ "eventId":"i5...","type":"AlarmEvent","schemaVersion":1,"occurredAt":"2026-06-10T11:00:01Z","source":"simulator","traceId":"sc-iface-001",
  "payload":{ "alarmId":"ALM-IF-0005","managedObjectId":"VPNService:CUST-A","alarmType":"ReachabilityLoss","eventType":"qualityOfServiceAlarm","probableCause":"reachabilityLoss","perceivedSeverity":"critical","raisedAt":"2026-06-10T11:00:01.760Z","state":"raised","trailIds":[] } }
```

Ground-truth label (root cause is the `Interface`; `rootCauseAlarmType` is `InterfaceDown`):

```json
{ "scenarioId":"sc-iface-001","scenarioType":"interface-fault",
  "rootCause":"ALM-IF-0001","rootCauseManagedObjectId":"Interface:PE1-LC2-P3-if0","rootCauseAlarmType":"InterfaceDown",
  "children":["ALM-IF-0002","ALM-IF-0003","ALM-IF-0004","ALM-IF-0005"] }
```

**Line-card cascade now traverses the interface step.** A `line-card-fault` on
`LineCard:PE1-LC2` cascades `HOSTED_ON ⇒ PortDown`, then `HOSTS ⇒ InterfaceDown` (each interface
on each port), then `TERMINATES ⇒ LinkDown` and `ADJACENCY_OVER ⇒ AdjDown` — i.e. the full
`PortDown ⇒ InterfaceDown ⇒ LinkDown ⇒ AdjDown` chain — then `TRAVERSES ⇒ LSPDown` and
`SERVES ⇒ ReachabilityLoss`. The label root is the `LineCard` alarm and `children` includes the
`PortDown`, `InterfaceDown`, `LinkDown`, `AdjDown`, `LSPDown`, and `ReachabilityLoss` alarms — the
`InterfaceDown` step is the addition over the pre-#91 chain.

```mermaid
flowchart TD
  LC[LineCard fault<br/>line-card-fault root] --> PD[PortDown<br/>via HOSTED_ON]
  PD --> IFD[InterfaceDown<br/>via HOSTS]
  IFRoot[Interface fault<br/>interface-fault root] --> IFD
  IFD --> LD[LinkDown<br/>via TERMINATES]
  IFD --> AD[AdjDown<br/>via ADJACENCY_OVER]
  LD --> LSP[LSPDown<br/>via TRAVERSES]
  LSP --> RL[ReachabilityLoss VPN<br/>via SERVES]
```

### Noise classes (≥3, from the pack's library)

| Class | Behaviour |
|---|---|
| `flapping` | rapid raised/cleared pairs on one object |
| `self-clearing transient` | a `raised` followed shortly by a `cleared` (no operator action) |
| `chatty standing` | repeated `raised` on a standing condition, never matching a cascade |
| `coincidental unrelated` | a lone real-looking alarm on an object outside any injected scenario |

## Integration thresholds (test oracle — owned by this spec)

The Simulator owns the metric **definitions**; the numeric MVP targets are **config, not literals**
(resolved from `INTEGRATION_THRESHOLDS` env / a checked-in `integration-thresholds.yaml` consumed by
the integration harness — criterion 20). The design exposes ground truth so the oracle can compute
each metric:

| Metric | How the design makes it computable | MVP target |
|---|---|---|
| RCA accuracy | `/labels` (or JSONL) gives `rootCause` (alarmId) **and `rootCauseAlarmType`** (canonical join token) per scenario; oracle compares to `rootCauseAlarmId` / `rootCauseAlarmType` in `correlation.results` on the same token space | ≥ 0.80 |
| Alarm-reduction ratio | count emitted alarms (metric `simulator_alarms_emitted_total`) ÷ incidents in `correlation.results` | ≥ 5× |
| Noise-filter removal | noise alarms are tagged (label-absence) so oracle counts injected-noise removed by Noise Filter | ≥ 0.90 |
| Noise-filter retention | scenario alarms (in some label) retained after Noise Filter | ≥ 0.95 |
| Pattern quality | injected scenario signatures (from labels) vs. patterns recovered by Pattern Miner | ≥ 0.80 |

These thresholds are surfaced to (not asserted by) the simulator; the `integration-test` harness
reads them from config and asserts them.

## Error handling

| Failure mode | Handling |
|---|---|
| **Missing/invalid required config** (e.g. no `KAFKA_BOOTSTRAP_SERVERS`, node count out of range, jitter < 0, unknown scenario) | Validate at startup; emit one structured JSON error log; **exit non-zero before emitting any event** (criterion 18). |
| **Invalid scenario config from Knowledge/file** | `scenario_loader` rejects with a clear error naming the bad field; aborts the run (no partial emission). |
| **Topology ingestion API unavailable / non-2xx** | `topology_client` retries with bounded exponential backoff (configurable attempts); on exhaustion logs structured error, marks `/health` non-200, and fails the P1 run with non-zero exit. No silent success. |
| **Snapshot validation failure** (dangling ref, bad `managedObjectId`, schema mismatch) | `snapshot_writer` raises before upload; run aborts; nothing uploaded. |
| **Event serialization / payload-validation failure** | `acp_event_model.serialize`/`AlarmEvent` raises `ValidationError`; the offending alarm is logged with its scenarioId and the run fails (a generation bug must not silently drop an alarm). |
| **Kafka producer error** (broker down, delivery failure) | producer delivery callback logs structured error, increments `simulator_produce_errors_total`, marks `/health` non-200; bounded retry via librdkafka; persistent failure fails the run non-zero. |
| **Live-replay pacing failure** (clock skew / oversleep) | pacing computed against a monotonic clock; if a gap is missed it is logged (gauge `simulator_pacing_drift_ms`) and the next event proceeds — pacing degrades gracefully, never crashes. |
| **No DLQ** | N/A — the Simulator consumes no Kafka stream (pure producer); spec confirms no inbound DLQ. |
| **schemaVersion** | Producer only ever emits `schemaVersion=1`; rejection of `>=2` is a consumer concern, N/A here. |

Nothing is ever silently dropped: every generated alarm is either emitted or fails the run loudly.

## Design alternatives

| Consideration | Alternatives considered | Chosen + rationale |
|---|---|---|
| Ground-truth retrieval (OQ-2) | (a) REST only; (b) flat file only; (c) queryable DB | **File (canonical) + thin REST mirror.** File is broker-free and trivially consumed by the integration oracle and CI artifacts; REST adds convenient query without forcing a DB. DB rejected — no query/scale need for a short-lived job. |
| Deterministic replay (OQ-3) | (a) always random; (b) optional seed | **Optional `SIM_SEED` (deterministic generation), fresh ids per run.** Determinism makes regression/CI reproducible; fresh `eventId`/`alarmId` keeps idempotency honest and avoids cross-run collisions (satisfies spec's "SHOULD produce new ids"). |
| Snapshot schema location (OQ-4) | (a) under `libs/event-model/`; (b) independent producer `schema/` copy; (c) single canonical Topology-owned schema | **(c) single canonical `services/topology/schema/snapshot.schema.json`.** Event-model is the Kafka contract; the snapshot is a file/API hand-off whose **consuming owner (Topology) already publishes the one schema its ingest validates against**. The Simulator validates against THAT same file (synced at build time) rather than keeping a second copy — eliminating producer/validator drift. (b) rejected: an independent copy can silently diverge from the validating owner. Still versioned + change = contract change. |
| Domain extensibility | (a) config-data-only pack; (b) Python `Protocol` pack interface | **`Protocol` interface (`domains/coreip` impl).** Allows code-level generators (graph topology, X.733 shapes) the engine can't express in pure data, while keeping the engine domain-agnostic and testable for "no Core-IP literals". |
| Kafka client | (a) `kafka-python`; (b) `confluent-kafka` | **`confluent-kafka`** for first-class `enable.idempotence`/`acks=all` and delivery callbacks (librdkafka); `kafka-python` retained as a lighter test double option. |
| Cascade traversal | (a) recursive per-template; (b) generic BFS over template-relevant edges | **Generic BFS** — single domain-agnostic walker driven by pack template data; supports SRLG fate-sharing and avoids per-template engine code. |
| Live pacing clock | (a) `time.sleep` on relative offsets; (b) monotonic-scheduler | **Monotonic-clock scheduler** — drift-aware, degrades gracefully, measurable via `simulator_pacing_drift_ms`. |
| Site placement (#81) | (a) one global site; (b) random device-to-site; (c) `SITE_COUNT`/`DEVICES_PER_SITE` even spread | **Even spread driven by `SITE_COUNT`/`DEVICES_PER_SITE`** — yields realistic site-level grouping the web-ui can visualize, keeps every device placed (exactly one `LOCATED_AT`), and is config-tunable; single-site is a `SITE_COUNT=1` special case. `Site`/`LOCATED_AT` live in the pack as domain-agnostic types so no engine branch. |
| Attribute values (#81) | (a) hard-coded constants; (b) fully-random strings; (c) small grounded catalogue | **Grounded catalogue in the pack** — realistic vendor/model/equipmentType/region values (config-influenced) so downstream features (e.g. Noise Filter `equipmentType`) are meaningful, while keeping `attributes` descriptive (never identity). Hard-coded rejected (no variety); random strings rejected (not grounded, breaks feature realism). |
| Interface layer + adjacency anchoring (#91) | (a) keep `ADJACENCY_OVER` on `IPLink` (pre-#91); (b) interfaces but adjacency still on the link; (c) full §5 Interface layer with adjacency on the interface | **Full §5 Interface layer** — `Port` `HOSTS` `Interface`, `Interface` `TERMINATES` `IPLink`, `ADJACENCY_OVER` runs **between interfaces**; `Interface` is a first-class fault origin. Matches the merged #91 model so the generated topology + cascades exercise the real interface step (PortDown⇒InterfaceDown⇒LinkDown⇒AdjDown) the downstream services learn against. (a)/(b) rejected — they would mis-anchor the adjacency and skip the interface fault origin, drifting from the authored Knowledge vocabulary Topology validates. `Interface`/`HOSTS`/`TERMINATES` live in the pack (domain vocabulary), so the engine stays domain-agnostic. |
| Interfaces per port (#91) | (a) fixed 1; (b) random; (c) `INTERFACES_PER_PORT` knob (default 1) | **`INTERFACES_PER_PORT` knob, default 1** — one L3 endpoint per port is the realistic Core-IP default and keeps the worked example simple, while the knob lets a run generate multiple sub-interfaces per port for richer cascades without code change. Fixed-1 rejected (no flexibility); fully-random rejected (non-reproducible without a seed and harder to assert). |
| Canonical join token for ground-truth / minable signature | (a) key off `probableCause` (X.733); (b) key off `eventType` (X.733 category); (c) key off the canonical `alarmType` join token | **(c) `alarmType`.** `architecture.md` pins `alarmType` as the **single canonical join key** the whole mining → codebook → correlation chain joins on (its value space = Knowledge `alarmTypeVocabulary`); `correlation.results.rootCauseAlarmType` is on that token space. Keying the emitted-alarm join token + the label `rootCauseAlarmType` + the minable signature off `alarmType` lets the RCA oracle compare like-for-like. (a)/(b) rejected — `probableCause`/`eventType` are X.733 fields with different meanings and would mis-align the oracle and the mined signature against the canonical chain. |
| `/labels` carries the root-cause join token (Q1) | (a) keep `{...,children[]}` only; (b) add `rootCauseAlarmType`; (c) embed full per-child `alarmType` arrays | **(b) add `rootCauseAlarmType`** (and each `EmittedAlarm` already carries its `alarmType`). RCA accuracy compares the injected root cause to `correlation.results.rootCauseAlarmType` — the label must expose the root-cause token to compare on the same space. (a) rejected (no token to compare). (c) deferred — children's `alarmType`s are available via the per-alarm records / minable signature; a per-child array on the label is redundant for the RCA oracle, kept out to keep the frozen `/labels` shape minimal. |

## Test plan

### Acceptance criterion → test (unit/contract — pytest)

| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| 1 | Topology snapshot valid & internally consistent | `test_snapshot_internally_consistent` | N=20 build → every Node id `Node:<id>`; every LineCard→existing Node, Port→existing LineCard, IPLink→two existing Ports, SRLG→existing IPLinks; every device has one `LOCATED_AT`→existing `Site`; no dangling refs |
| 2 | `managedObjectId` shared between snapshot & alarms | `test_alarm_moids_subset_of_snapshot` | every emitted alarm `managedObjectId` ∈ snapshot node ids |
| 3 | `managedObjectId` conforms to the (now domain-agnostic) scheme | `test_all_moids_pass_event_model_validator` | every snapshot + alarm moid (incl. `Site:*`) passes `acp_event_model.validate` under the generic `<objectType>:<id>` scheme (objectType `^[A-Za-z][A-Za-z0-9]*$`, non-empty colon-free id) |
| 4 | Fiber-cut cascade correct | `test_fiber_cut_cascade_matches_templates` | root alarm on `FiberSpan` with `alarmType=FiberFault`; children carry the canonical effect `alarmType`s `LinkDown`(IPLink), `AdjDown`(IGPAdjacency), `LSPDown`(LSP), `ReachabilityLoss`(VPNService); label root=FiberSpan alarm, `rootCauseAlarmType=FiberFault`, children=all downstream |
| 5 | Line-card & port faults producible & distinguishable | `test_linecard_and_port_scenarios_distinct` | line-card-fault root objectType=`LineCard` (HOSTED_ON⇒HOSTS⇒TERMINATES cascade incl. the `InterfaceDown` step), port-fault root objectType=`Port` (HOSTS⇒TERMINATES cascade); labels differ in rootCause object type and `rootCauseAlarmType`; both produce an `InterfaceDown` `alarmType` child |
| 6 | ≥3 noise classes generated | `test_at_least_three_noise_classes` | with noise enabled, ≥3 distinct noise classes emitted; each noise alarm absent from every label.children |
| 7 | Emitted alarms validate vs frozen `AlarmEvent` (required fields incl. `alarmType`) | `test_emitted_alarms_validate_against_event_model` | every payload constructs as `AlarmEvent` w/o ValidationError; **all required fields present incl. `alarmType`** (`alarmId, managedObjectId, eventType, probableCause, alarmType, perceivedSeverity, raisedAt, state, trailIds`); `state` ∈ {raised,cleared}; a payload missing `alarmType` raises ValidationError |
| 7a | **Every emitted `AlarmEvent.alarmType` is a valid canonical vocabulary token** (Q4/Q7) | `test_every_alarm_has_valid_alarm_type_token` | across a full run, **every** emitted alarm (scenario root, scenario child, noise, background) has a non-empty `alarmType` ∈ the pack's `alarm_type_vocabulary()` (a subset of Knowledge `alarmTypeVocabulary`); `alarmType` differs from `eventType` and `probableCause` (distinct token spaces) |
| 8 | History lands on `alarms.history` | `test_history_mode_targets_history_topic` | history mode → all alarms on `alarms.history`, zero on `alarms.live` |
| 9 | Live lands on `alarms.live` with pacing | `test_live_mode_targets_live_topic_with_pacing` | live mode → all on `alarms.live`, zero on `alarms.history`; inter-event delay > 0 for pacing>0 |
| 10 | Ground-truth labels retrievable | `test_ground_truth_labels_retrievable` | after run, label retrievable (file + `/labels`); `rootCause`=injected root alarmId; `children`=downstream alarmIds |
| 10a | **`/labels` carries `rootCauseAlarmType`** (Q1) | `test_labels_carry_root_cause_alarm_type` | every `/labels` and JSONL record includes `rootCauseAlarmType`; its value is a canonical `alarmTypeVocabulary` token and **equals the `alarmType` of the alarm identified by `rootCause`** (like-for-like with `correlation.results.rootCauseAlarmType`); `/labels` response matches the frozen shape `{scenarioId, scenarioType, rootCause, rootCauseManagedObjectId, rootCauseAlarmType, children[]}` (validated against checked-in `openapi.json`) |
| 11 | Topology size configurable, no hard-coded count | `test_topology_size_configurable` | runs N=10 and N=50 → ~10 and ~50 nodes; no default count compiled in (config-driven) |
| 12 | Timing jitter configurable, no hard-coded value | `test_jitter_configurable` | `jitter_stddev_ms=0` → deterministic intervals; `=500` → varied intervals; distributions differ measurably |
| 13 | Noise mix configurable, no hard-coded rate | `test_noise_mix_configurable` | rate 0 → zero noise; non-zero → noise present; two mixes → statistically different noise:scenario ratios |
| 14 | Snapshot validates vs the **canonical Topology** topology-file schema | `test_snapshot_validates_against_schema` | any-run snapshot passes `jsonschema` validation against the **single canonical `services/topology/schema/snapshot.schema.json`** (no independent Simulator copy; incl. `Site`/`LOCATED_AT`/`attributes`); all required fields + refs well-formed (criterion 27 adds the Site-specific assertions) |
| 15 | Topology ingestion config-switchable | `test_topology_api_mode_switch` | `TOPOLOGY_API_MODE=mock` → stub used, no real call; `=real` → `POST {TOPOLOGY_API_BASE_URL}/topology/snapshots`; switch needs no code change |
| 15a | **Ingestion reads `snapshotId` from the frozen 200 `SnapshotIngestResponse`** (Q3) | `test_ingestion_reads_snapshot_id_from_200_response` | upload returns **HTTP 200** (not 202) with body `{snapshotId, domain, status, nodeCount, edgeCount, changeType}`; the client reads `snapshotId` from that 200 body and records it on the `SimRun`; the stub is generated from Topology's published `openapi.json` so the contract is enforced; a 202 or a bare body would fail the test |
| 16 | `/health` 200 when running | `test_health_endpoint` | GET `/health` → 200 when started+Kafka up; non-200 before startup / on lost Kafka |
| 17 | `/metrics` Prometheus format | `test_metrics_endpoint` | GET `/metrics` → 200, `text/plain`, ≥1 metric incl. `simulator_alarms_emitted_total` |
| 18 | Config validation fails fast | `test_missing_required_config_aborts` | start w/o required env → structured JSON error log + non-zero exit, zero events emitted |
| 19 | Domain-pack separation — no Core-IP literals in engine | `test_engine_has_no_coreip_literals` | `DomainPack` Protocol exists; engine source contains no Core-IP object-type/template/alarm/scenario literals (static scan of `engine/`) |
| 20 | Integration thresholds owned by spec, from config | `test_thresholds_sourced_from_config` | the five thresholds present in harness config (0.80/5/0.90/0.95/0.80), not hard-coded literals in service code |

**Design-added criteria (synthesis strategy — not new contract, internal generation behaviour).**
These cover the new evaluation-grade synthesis knobs; the spec's 1–20 mapping above is unchanged.

| # | Acceptance criterion (design-added) | Test | Asserts |
|---|---|---|---|
| 21 | **Scenario-instance count drives minable support — configurable, default minable.** Each selected scenario is injected `SCENARIO_INSTANCES` times; default (8) yields ≥ minimum-support repetitions of each scenario signature. | `test_scenario_instances_repeats_signature` | `SCENARIO_INSTANCES=N` → exactly N labels per scenario type; each instance shares the same ordered canonical `alarmType` signature (the join token, not `probableCause`) with fresh ids; default ≥ 5 (PrefixSpan-minable) |
| 22 | **Background fraction configurable; background alarms are non-pattern.** A configurable fraction of emitted alarms belong to no injected pattern and appear in no label's `children`. | `test_background_fraction_configurable` | `BACKGROUND_FRACTION=0` → no background alarms; `=0.3` → ~30% of emitted alarms in no label.children; vary fraction → measurably different background:signal ratio |
| 23 | **Hard noise is placed near cascades for DBSCAN stress.** A configurable fraction of noise is placed near a cascade in time and/or on a topology-adjacent object; the rest is clearly separate. | `test_hard_noise_fraction_near_cascade` | `HARD_NOISE_FRACTION=0.4` → ~40% of noise alarms within a near-cascade time/object window of a scenario; remainder clearly separated; still in no label.children |
| 24 | **History timestamps fall inside the configured window.** P2 alarms' `raisedAt` lie within `[HISTORY_START, HISTORY_END]`. | `test_history_timestamps_within_window` | with a set window, every emitted P2 alarm `raisedAt` ∈ window; invalid window (`START ≥ END`) fails fast (exit 3) |
| 25 | **Generated topology includes `Site` nodes + `LOCATED_AT` edges (configurable).** The pack emits `Site` nodes (geo attrs) and a `LOCATED_AT` edge per device into a site; counts driven by `SITE_COUNT`/`DEVICES_PER_SITE`. | `test_topology_has_sites_and_located_at` | snapshot has ≥1 `Site` node carrying `name`/`latitude`/`longitude`/`region`; every device node has exactly one `LOCATED_AT` edge whose `to` is a `Site`; `SITE_COUNT=N` → N sites; no device unplaced |
| 26 | **Devices carry the well-known `attributes` keys (grounded).** Device nodes carry `vendor`/`model`/`equipmentType`/`role`/`capacity`; connection edges carry `linkType`/`capacity`/`protectionRole` where sensible, with pack-grounded values. | `test_device_and_connection_attributes_present` | every device node has the device well-known keys with non-empty pack-catalogue values; connection edges carry the connection keys where the pack populates them; values drawn from the grounded catalogue (not random strings) |
| 27 | **Snapshot validates against the canonical topology-file schema incl. Site/LOCATED_AT/attributes.** The generated snapshot (with sites, `LOCATED_AT`, attributes) passes the canonical Topology schema and the domain-agnostic `managedObjectId` validator. | `test_snapshot_with_sites_validates` | full snapshot passes `jsonschema` against the **canonical `services/topology/schema/snapshot.schema.json`** (generic id pattern + non-empty `objectType`/`relation`; the `Site`/`LOCATED_AT` tokens are emitted from the pack vocabulary and validated **semantically by Topology**, not by a JSON-Schema enum); every `managedObjectId` (incl. `Site:*`) passes `acp_event_model.validate` under the generic `<objectType>:<id>` scheme; `domain == "core-ip"` |
| 28 | **Generated topology includes `Interface` nodes + `HOSTS`/`TERMINATES` edges (§5 #91, configurable).** The pack emits `Interface` nodes on ports (`Port` `HOSTS` `Interface`), each `Interface` `TERMINATES` its `IPLink`, and the IGP adjacency runs `ADJACENCY_OVER` an `Interface` (not a `Port`/`IPLink`); count per port driven by `INTERFACES_PER_PORT` (default 1). | `test_topology_has_interfaces_and_hosts_terminates` | snapshot has ≥1 `Interface` node; every `Interface` has exactly one incoming `HOSTS` from a `Port` and ≥1 `TERMINATES` to an existing `IPLink`; every `ADJACENCY_OVER` edge originates at an `Interface` (never a `Port`/`IPLink`); `INTERFACES_PER_PORT=N` → N interfaces per port; passes the canonical schema (generic id pattern; `Interface`/`HOSTS`/`TERMINATES` tokens validated semantically by Topology against its domain vocabulary) + `acp_event_model.validate` for every `Interface:*` moid; no dangling refs |
| 29 | **Interface-fault scenario produces the InterfaceDown→LinkDown→AdjDown cascade with ground-truth label (§5 #91).** The pack's scenario library includes `interface-fault`; injecting it yields a cascade rooted on an `Interface` whose `children` follow `TERMINATES ⇒ ADJACENCY_OVER ⇒ TRAVERSES ⇒ SERVES`. | `test_interface_fault_cascade_matches_templates` | `interface-fault` selectable in `SCENARIOS`; root alarm objectType=`Interface` (`interfaceDown`); children include `LinkDown`(IPLink), `AdjDown`(IGPAdjacency), `LSPDown`(LSP), `ReachabilityLoss`(VPNService) in causal order; label `rootCause`=Interface alarm, `rootCauseManagedObjectId` an `Interface:*`, `children`=all downstream; distinct from fiber-cut/line-card/port labels by root object type |

### E2E scenarios (from this design unit's point of view)

| # | Scenario | Trigger → path | Expected outcome |
|---|---|---|---|
| 1 | **P1 happy path** (mock Topology) | `--phase p1`, `TOPOLOGY_API_MODE=mock`, N=20 | snapshot file written + validated vs the canonical Topology schema; stub `POST /topology/snapshots` returns **200 `SnapshotIngestResponse`**; client reads `snapshotId` from the 200 body; `/health` 200 |
| 2 | **P1 with real Topology** (integration stack) | `--phase p1`, `mode=real` against running Topology | Topology validates + lifts to its graph, returns **200 `SnapshotIngestResponse {snapshotId, domain, status, nodeCount, edgeCount, changeType}`**, emits `topology.changed`; simulator reads + records the same `snapshotId` from the 200 body |
| 3 | **P2 history corpus → oracle** | `--phase p2`, fiber-cut+line-card+port + noise | all alarms on `alarms.history`, **every one carrying a valid canonical `alarmType`** token; labels file written with `rootCauseAlarmType` per scenario; downstream noise-filter/pattern-miner can be scored against labels and key on the `alarmType` join token |
| 3a | **alarmType present + joinable end-to-end** | `--phase p2` then inspect emitted corpus + labels | **100%** of emitted `AlarmEvent`s have `alarmType` ∈ vocabulary; each label's `rootCauseAlarmType` equals the `alarmType` of its `rootCause` alarm; the ordered `alarmType` signature repeats across the N instances of each scenario (the minable join-token signature) |
| 4 | **P3 live paced stream** | `--phase p3`, `PACING_MULTIPLIER=1.0` | alarms on `alarms.live` with non-zero inter-event delays; labels retrievable; correlation oracle can compute RCA/reduction |
| 5 | **Shared-identity invariant** end-to-end | run P1 then P2 with same `SIM_SEED` | every alarm moid present in the uploaded snapshot (criterion 2 across phases) |
| 6 | **Failure — Topology ingestion down** | `--phase p1`, real mode, endpoint unreachable | bounded retries, structured error, `/health` non-200, non-zero exit, nothing falsely reported uploaded |
| 7 | **Failure — broker unavailable** | `--phase p2`, bad `KAFKA_BOOTSTRAP_SERVERS` | produce errors logged + counted; `/health` non-200; run fails non-zero; no silent drop |
| 8 | **Failure — invalid scenario config** | unknown scenario / negative jitter | startup validation aborts run with structured error, zero events emitted |
| 9 | **Noise-only / scenario-only edges** | `NOISE_RATE=0` then high noise | rate 0 → zero noise alarms; high → labels still pure (noise never in `children`) |
| 10 | **Evaluation-grade default corpus** | `--phase p2` with only `KAFKA_BOOTSTRAP_SERVERS` (all defaults) | corpus has ≥5 instances per scenario (minable), ~30% background, ~20% noise (~40% hard) over a 24h window; sufficient to compute all five §10 thresholds against the labels |
| 11 | **Partial path — zero scenarios but background/noise on** | `SCENARIOS=` empty, background/noise > 0 | no labels written; background+noise still emitted; no alarm in any `children`; pattern-quality oracle correctly recovers nothing (no false patterns) |
| 12 | **P1 snapshot with sites + attributes → Topology** | `--phase p1`, `SITE_COUNT=3`, real/mock Topology | snapshot has 3 `Site` nodes (geo attrs), every device `LOCATED_AT` a site, device/connection well-known `attributes` populated; passes the canonical Topology schema; Topology accepts it (validates types/relations incl. `Site`/`LOCATED_AT` semantically against the domain vocabulary) and returns **200 `SnapshotIngestResponse`** with `snapshotId` |
| 13 | **Partial path — single site** | `--phase p1`, `SITE_COUNT=1` | all devices `LOCATED_AT` the one `Site`; still schema-valid; no unplaced device, no second site |
| 14 | **P1 snapshot with Interface layer → Topology** | `--phase p1`, `INTERFACES_PER_PORT=1`, real/mock Topology | snapshot has `Interface` nodes (`Port` HOSTS `Interface`, `Interface` TERMINATES `IPLink`, IGP adjacency `ADJACENCY_OVER` the interface); passes schema; Topology accepts the `Interface`/`HOSTS`/`TERMINATES` vocabulary and mints `snapshotId` |
| 15 | **P2 interface-fault corpus → oracle** | `--phase p2`, `SCENARIOS=interface-fault` (+ optional others) + noise | interface-fault cascades on `alarms.history` rooted on `Interface` with `InterfaceDown→LinkDown→AdjDown→LSPDown→ReachabilityLoss` children; labels file records the `Interface` root cause; RCA/pattern oracle scores it like any other scenario |

## Config & observability

#### Authoritative DEFAULTS table (config knob → env var → default → effect)

Every knob has a default. **Defaults apply when the var is not provided** and are chosen so a small
demo run is **evaluation-grade out of the box** — `python -m simulator.main --phase p2` runs with
only `KAFKA_BOOTSTRAP_SERVERS` set. "required" rows have no default and fail fast if missing
(criterion 18, exit 3).

| Knob | Env var | Default | Effect |
|---|---|---|---|
| Kafka brokers | `KAFKA_BOOTSTRAP_SERVERS` | **required** (P2/P3) | broker list; missing in P2/P3 → fail fast |
| Topology API mode | `TOPOLOGY_API_MODE` | `mock` | `mock` stub vs `real` Topology ingestion |
| Topology API base URL | `TOPOLOGY_API_BASE_URL` | unset (required only when mode=`real`) | real ingestion endpoint |
| Knowledge mode | `KNOWLEDGE_MODE` | `local` | scenario config from local file vs Knowledge Service |
| Knowledge API base URL | `KNOWLEDGE_API_BASE_URL` | unset (required only when mode=`real`) | Knowledge Service endpoint |
| Topology size | `TOPOLOGY_NODE_COUNT` | `20` | `Node` count (range 10–200); other layers scale per pack |
| Site count | `SITE_COUNT` | `3` | number of `Site` nodes (geo attrs); devices placed via `LOCATED_AT` (range 1–node count) |
| Devices per site | `DEVICES_PER_SITE` | unset → derived (`ceil(devices / SITE_COUNT)`) | target devices per site; when set, `SITE_COUNT` adjusts to fit |
| Interfaces per port | `INTERFACES_PER_PORT` | `1` | `Interface` nodes hosted per `Port` (§5 #91; range 1–8); each TERMINATES an IPLink + carries the adjacency |
| Random seed | `SIM_SEED` | unset → random (logged) | deterministic generation when set; reproducible by re-supplying logged seed |
| Scenario selection | `SCENARIOS` | `fiber-cut,line-card-fault,port-fault,interface-fault` | which scenarios to inject |
| Scenario-instance count | `SCENARIO_INSTANCES` | `8` (range 5–10) | injections **per** scenario; default guarantees PrefixSpan-minable support |
| Timing jitter | `JITTER_STDDEV_MS` | `300` | Gaussian std-dev of per-gap delay, on top of base interval |
| Base interval | `BASE_INTERVAL_MS` | `400` | base inter-alarm spacing inside a cascade |
| Background interval | `BACKGROUND_INTERVAL_MS` | `2000` | mean spacing between background/non-pattern alarms |
| Background fraction | `BACKGROUND_FRACTION` | `0.3` | fraction of emitted alarms in no injected pattern (signal-vs-background) |
| Noise rate | `NOISE_RATE` | `0.2` | fraction of total alarms that are noise |
| Noise mix | `NOISE_MIX` | `flapping:0.4,transient:0.3,chatty:0.2,coincidental:0.1` | noise class weights (≥3 classes) |
| Hard-noise fraction | `HARD_NOISE_FRACTION` | `0.4` | fraction of noise placed near a cascade (DBSCAN stress) vs easy/separate |
| History window start | `HISTORY_START` | `now − 24h` | P2 historical window start (`raisedAt` lower bound) |
| History window end | `HISTORY_END` | `now` | P2 historical window end (or set `HISTORY_DURATION` instead) |
| Replay pacing | `PACING_MULTIPLIER` | `1.0` | P3 wall-clock scale of inter-event delays |
| Output dir | `SIM_OUTPUT_DIR` | `/data/sim` | snapshot + label export location |
| Integration thresholds | `INTEGRATION_THRESHOLDS` | checked-in `integration-thresholds.yaml` | §10 targets (harness reads; not asserted here) |
| HTTP port | `HTTP_PORT` | `8080` | `/health`, `/metrics`, `/labels` |
| Log level | `LOG_LEVEL` | `INFO` | structured-log verbosity |

These defaults make a default P2 run produce 8 instances each of **4** scenarios (fiber-cut,
line-card-fault, port-fault, interface-fault) + ~30% background + 20% noise (40% of it hard) spread
over a 24h window — an evaluation-grade set with no tuning. The P1 snapshot generated with defaults
has 3 `Site` nodes with every device `LOCATED_AT` one of them, one `Interface` per `Port`
(`INTERFACES_PER_PORT=1`) `HOSTS`-ed by the port and `TERMINATES`-ing its `IPLink` with the IGP
adjacency `ADJACENCY_OVER` the interface, and the well-known device/connection `attributes` keys
populated from the pack's grounded catalogue.
- **`/health`** — 200 when started + Kafka connected; non-200 otherwise.
- **`/metrics`** — Prometheus exposition incl. `simulator_alarms_emitted_total{topic,scenario,alarmType}`
  (every emitted alarm counted under its canonical `alarmType`),
  `simulator_scenarios_injected_total{scenario}` (counts instances per scenario),
  `simulator_background_alarms_total`, `simulator_noise_alarms_total{class}`,
  `simulator_hard_noise_alarms_total`, `simulator_produce_errors_total`,
  `simulator_pacing_drift_ms`, `simulator_snapshot_nodes`, `simulator_snapshot_sites`,
  `simulator_snapshot_interfaces` (count of `Interface` nodes),
  `simulator_snapshot_edges{relation}` (incl. `relation="LOCATED_AT"`, `relation="HOSTS"`,
  `relation="TERMINATES"`).
- **Logging** — structured JSON on stdout (one object per line): `ts, level, event, runId,
  scenarioId?, msg`.

## Build & run

- **Layout:** `services/simulator/src/simulator/{main.py, config/, engine/, domains/coreip/,
  integrations/, api/, obs/}`, `services/simulator/openapi.json`, `services/simulator/tests/`. The
  snapshot file is validated against the **single canonical `services/topology/schema/snapshot.schema.json`**
  (Topology-owned; synced/vendored at build time — no independent Simulator schema copy).
- **Build/test:** `ruff check . && black --check . && pytest` (Python 3.13).
- **Dockerfile:** `python:3.13-slim` base (per CI pins); installs `acp-event-model` from
  `libs/event-model/python`; `CMD` runs `python -m simulator.main`. Compose entry wires
  `KAFKA_BOOTSTRAP_SERVERS` + `TOPOLOGY_API_BASE_URL` to the integration stack.
- **Local run (minimal — all defaults apply):** `KAFKA_BOOTSTRAP_SERVERS=localhost:9092
  python -m simulator.main --phase p2` produces an evaluation-grade corpus with no further env.

## UI wireframes

N/A — backend/CLI service (no web-ui surface).
