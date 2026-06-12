# event-model — Library Spec

## Purpose

The shared canonical event library: the single source of truth for the event **envelope**
(`eventId`, `type`, `schemaVersion`, `occurredAt`, `source`, `traceId`, `payload`), the nine
specialized **payloads** (AlarmEvent, TopologyChangedEvent, TrailsBuiltEvent,
CodebookGeneratedEvent, TransactionEvent, PatternMinedEvent, PatternDiscoveredEvent,
PatternApprovedEvent, CorrelationResultEvent), and the `managedObjectId` scheme. Two language
bindings — Java (Spring Boot cohort) and Python/Pydantic (Python cohort) — are generated from
**one JSON Schema**. It is a pure contract/binding library: **no business or domain logic**,
extensible via subclassing. Every service imports it; no service depends on another service's
source code to access event shapes.

## Scope

**In scope:**
- Define the **envelope** with exact fields: `eventId`, `type`, `schemaVersion`, `occurredAt`,
  `source`, `traceId`, `payload`.
- Define all nine **payload schemas** with their authoritative field lists (see Contract section).
- Define the **`managedObjectId` scheme** — the shared identity binding that allows alarms and
  the topology graph to reference the same objects. Format: `<objectType>:<id>` (see Contract
  section for known types and validation rule).
- Enforce the **specialization rule**: each `type` value maps to exactly one payload schema.
- Enforce the **`schemaVersion` compatibility policy**: the initial supported major version is
  **1**. Consumers accept major `1` and **reject major ≥ 2**.
- Provide **(de)serialization helpers and validation** for both Java and Python bindings.
- Be buildable as a versioned, importable **Java library** (Gradle) and a versioned, importable
  **Python/Pydantic package** (pip).
- Carry **no hard-coded thresholds, secrets, or integration URLs** — it is a pure schema/binding
  library.

## Out of scope

- Business logic, algorithms, or domain rules of any service (those belong in the services).
- Kafka topic configuration, producers, or consumers — the library defines payloads only; topic
  ownership is in each service's spec.
- A schema registry (explicitly excluded from MVP; the library replaces it).
- REST or HTTP endpoints — this library has none; it is an importable dependency.
- Automated schema migration or backward-compatibility transformation — the `schemaVersion`
  policy is reject-on-major-≥2; migration is a future concern.
- Redundancy/protection-aware propagation fields, multi-domain payloads, HA/scale — all deferred
  per MVP non-goals.

## Tasks (high-level)

1. Define the **envelope** schema with fields `eventId`, `type`, `schemaVersion`, `occurredAt`,
   `source`, `traceId`, `payload`, and generate both Java and Python bindings from it.
2. Define each of the **nine payload schemas** (see Contract) from one JSON Schema source,
   generating both bindings, such that each `type` string resolves to exactly one payload.
3. Define and encode the **`managedObjectId` scheme** — format `<objectType>:<id>`,
   **domain-agnostic**: `objectType` is any alphanumeric token starting with a letter (the
   valid object-type set per domain is authored in the Knowledge Service, not enumerated
   here), `id` a stable non-empty string — so the Simulator (alarm generation) and Topology
   Service (graph nodes) use the same identifier format per §4.5. (Core IP's MVP set is
   `Node`, `LineCard`, `Port`, `IPLink`, `IGPAdjacency`, `LSP`, `VPNService`, `FiberSpan`,
   `SRLG`, `Site` — an example, not a validation constraint.)
4. Implement **`schemaVersion` validation** in both bindings: deserialization raises/rejects when
   the event's `schemaVersion` is ≥ 2 (i.e., major exceeds supported major `1`).
5. Provide **(de)serialization helpers** in both bindings: serialize an envelope+payload to JSON;
   deserialize JSON to the correct typed payload based on `type`; raise on schema violations.
6. Publish the library as importable **versioned artifacts** (Gradle for Java, pip-installable
   package for Python), versioned with the repo, so every downstream service pins a version.

## Contract

- **Consumes (Kafka):** — (this is a library, not a runtime service; it has no Kafka consumers)
- **Produces (Kafka):** — (this is a library, not a runtime service; it has no Kafka producers)
- **APIs exposed:** — (this library exposes no HTTP API; it is an importable dependency, not a
  service)
- **APIs/data consumed from other services:** — (no runtime dependencies; the library depends
  only on its own JSON Schema source)
- **Integration points (mock vs. real):** N/A — this is a build-time dependency library with no
  outbound runtime calls; mock vs. real integration point switching does not apply.
- **Data owned:** — (no datastore; schema source files are the library's sole artifact)

### Envelope fields

| Field | Type | Required | Notes |
|---|---|---|---|
| `eventId` | string (UUID) | yes | globally unique per event; idempotency key |
| `type` | string | yes | discriminator; maps 1:1 to a payload schema |
| `schemaVersion` | integer | yes | initial value `1`; consumers accept `1`, reject ≥ `2` |
| `occurredAt` | datetime (ISO-8601) | yes | when the event occurred |
| `source` | string | yes | originating service name |
| `traceId` | string | yes | distributed trace identifier |
| `payload` | object | yes | typed per `type`; one of the nine payload schemas |

### Payload schemas (authoritative field lists from §7)

**AlarmEvent** (X.733-aligned; carried on `alarms.*` topics):

| Field | Type | Required | Notes |
|---|---|---|---|
| `alarmId` | string | yes | unique alarm identifier |
| `managedObjectId` | string | yes | must match topology graph identity scheme (format: `<objectType>:<id>`) |
| `eventType` | string | yes | X.733 event type |
| `probableCause` | string | yes | X.733 probable cause |
| `perceivedSeverity` | string | yes | X.733 severity |
| `raisedAt` | datetime | yes | when the alarm was raised |
| `clearedAt` | datetime | no | set when alarm is cleared |
| `state` | string (enum: raised, cleared) | yes | current alarm state |
| `vendorRaw` | object | no | original vendor alarm payload, pass-through |
| `trailIds` | string[] | yes | populated by Enrichment Service; empty before enrichment |

**TopologyChangedEvent** (carried on `topology.changed`):

| Field | Type | Required | Notes |
|---|---|---|---|
| `snapshotId` | string | yes | identifies the topology snapshot version |
| `changeType` | string | yes | describes the nature of the topology change |
| `nodes` | object[] | yes | typed node descriptors |
| `edges` | object[] | yes | typed edge descriptors |

**TrailsBuiltEvent** (carried on `trails.built`):

Summary only — full trail membership is fetched via the Trail Builder API (`getTrail(trailId)`),
per §6.4.

| Field | Type | Required | Notes |
|---|---|---|---|
| `snapshotId` | string | yes | topology snapshot this build references |
| `trailIds` | string[] | yes | array of trail identifiers built in this snapshot |
| `trailCount` | integer | yes | number of trails built (must equal `trailIds.length`) |

**CodebookGeneratedEvent** (carried on `codebook.generated`):

Summary only — full signatures are fetched via the Codebook Generator API, per §6.5.

| Field | Type | Required | Notes |
|---|---|---|---|
| `snapshotId` | string | yes | topology snapshot this codebook was compiled from |
| `scenarioCount` | integer | yes | number of scenarios in this codebook |
| `codebookId` | string | yes | identifies this codebook version; referenced as `matchedCodebookId` in CorrelationResultEvent |

**TransactionEvent** (carried on `transactions.clean`):

Raw DBSCAN-cleaned, trail-scoped alarm groups from the Noise Filter (§6.7); the Pattern Miner
finalizes session-window boundaries downstream (§6.8).

| Field | Type | Required | Notes |
|---|---|---|---|
| `transactionId` | string | yes | unique identifier for this transaction group |
| `trailId` | string | yes | trail scope of the alarm group |
| `snapshotId` | string | yes | topology snapshot in scope when this group was formed |
| `alarmIds` | string[] | yes | alarm identifiers in this DBSCAN-cleaned group |
| `alarms` | object[] | yes | ordered per-alarm detail for the same group as `alarmIds` (sequence preserved — the Pattern Miner mines ordered sequences). Populated by the Noise Filter from the enriched `AlarmEvent`s it already holds, so the Pattern Miner needs no separate alarm-detail lookup. Each item: `alarmId` (string), `eventType` (string), `raisedAt` (datetime), `managedObjectId` (string, `<objectType>:<id>` scheme), `perceivedSeverity` (string) — all required; mirrored from the `AlarmEvent` payload |
| `windowStart` | datetime | yes | start of the raw time window |
| `windowEnd` | datetime | yes | end of the raw time window |

**PatternMinedEvent** (carried on `patterns.mined`; Miner output only — no RCA, no lifecycle):

| Field | Type | Required | Notes |
|---|---|---|---|
| `sequence` | string[] | yes | ordered alarm type sequence discovered |
| `support` | float | yes | frequency of the sequence |
| `confidence` | float | yes | conditional probability |
| `lift` | float | yes | lift over baseline |
| `trailId` | string | yes | trail scope the pattern was mined from (top-level field) |
| `timing` | object | yes | inter-arrival timing statistics for the sequence |
| `provenance` | object | yes | nested object; see sub-fields below |

`provenance` sub-fields:

| Sub-field | Type | Required | Notes |
|---|---|---|---|
| `sourceWindowId` | string | yes | identifies the transaction window this pattern was mined from |
| `snapshotId` | string | yes | topology snapshot version in scope when mining ran |
| `codebookVersion` | string | yes | codebook version in scope when mining ran |

**PatternDiscoveredEvent** (carried on `patterns.discovered`; Pattern Manager output):

| Field | Type | Required | Notes |
|---|---|---|---|
| `patternId` | string | yes | stable identifier assigned by Pattern Manager |
| `sequence` | string[] | yes | ordered alarm type sequence |
| `rootCauseAlarmType` | string | yes | alarm type tagged as root cause |
| `support` | float | yes | |
| `confidence` | float | yes | |
| `lift` | float | yes | |
| `timing` | object | yes | timing statistics |
| `codebookMatchId` | string | no | matched codebook scenario, if any |
| `lifecycle` | string | yes | pattern lifecycle state (e.g. `draft`) |

**PatternApprovedEvent** (carried on `patterns.approved`; Pattern Manager output after human approval):

| Field | Type | Required | Notes |
|---|---|---|---|
| `patternId` | string | yes | |
| `sequence` | string[] | yes | |
| `rootCauseAlarmType` | string | yes | |
| `support` | float | yes | |
| `confidence` | float | yes | |
| `lift` | float | yes | |
| `timing` | object | yes | |
| `codebookMatchId` | string | no | |
| `lifecycle` | string | yes | `approved` at the point this event is emitted |

**CorrelationResultEvent** (carried on `correlation.results`):

| Field | Type | Required | Notes |
|---|---|---|---|
| `incidentId` | string | yes | unique incident identifier |
| `rootCauseAlarmId` | string | yes | `alarmId` of the tagged root-cause alarm |
| `childAlarmIds` | string[] | yes | `alarmId`s of correlated child alarms |
| `matchedPatternId` | string | no | pattern that matched, if any |
| `matchedCodebookId` | string | no | codebook scenario that matched, if any; references `codebookId` from CodebookGeneratedEvent |
| `confidence` | float | yes | correlation confidence score |
| `trailId` | string | yes | trail scope of the incident |

### `managedObjectId` scheme

The `managedObjectId` is the shared identity binding for network objects. Format:

```
<objectType>:<id>
```

- `objectType` MUST be an **alphanumeric token starting with a letter**. The scheme is
  **domain-agnostic**: the event-model does not enumerate the valid object types — the valid
  object-type set **per domain is authored in the Knowledge Service**, not frozen here.
  (Core IP's MVP set is `Node`, `LineCard`, `Port`, `IPLink`, `IGPAdjacency`, `LSP`,
  `VPNService`, `FiberSpan`, `SRLG`, `Site` — an example, not a validation constraint.)
- `id` MUST be a stable, non-empty string (no colon characters permitted in `id`).
- **Validation rule:** the value matches `<objectType>:<non-empty-id>` where `objectType` is
  an alphanumeric token starting with a letter (regex `^[A-Za-z][A-Za-z0-9]*:[^:]+$`). Values
  that fail this shape are invalid; the event-model does NOT check `objectType` against any
  per-domain list (that belongs to the Knowledge Service).
- **Examples:** `Node:PE1`, `Site:LON-01`, `gNodeB:g-7`, `Port:PE1-LC2-P3`, `FiberSpan:SPAN-AB-01`.
- This same scheme is used by the Simulator when generating alarms and by the Topology Service
  when persisting graph nodes, per §4.5. It is defined once here and referenced by both cohorts.

## Non-functional

- **Idempotency key:** `eventId` (UUID) in the envelope — consumers use it for deduplication.
  `alarmId` is the idempotency key for alarm-specific deduplication in the AlarmEvent payload.
- **Config:** no environment variables, no hard-coded thresholds, no secrets, no integration
  URLs — this is a pure schema/binding library.
- **Observability:** N/A — this is a build-time library with no runtime process; `/health` and
  `/metrics` do not apply.
- **API contract:** N/A — this library has no HTTP surface. The JSON Schema is the contract;
  it is checked into the repo. Any change to the schema is a contract change requiring
  `docs/architecture.md` update and human approval before dependent services proceed.
- **`schemaVersion` compatibility policy:** The initial supported major version is **1**.
  Consumers MUST accept envelopes with `schemaVersion = 1`. Consumers MUST reject any envelope
  with `schemaVersion ≥ 2` (i.e., any major version that exceeds the supported major). Minor
  version increments are additive/backward-compatible; major increments are breaking. The
  boundary values for testing are: `schemaVersion = 1` → accept; `schemaVersion = 2` → reject.
- **Error handling:** N/A for the library itself. Services consuming events from Kafka route
  deserialization failures (e.g., unknown major version, missing required field) to the
  `<topic>.dlq` dead-letter topic — per each service's spec.
- **Licenses:** all library dependencies must be permissive (Apache-2.0, MIT, BSD, PostgreSQL).
  No GPL/AGPL/BSL components.

## Acceptance criteria

### Cross-binding and single-source-of-truth

1. **Wire-format agreement (Java ↔ Python):** Given a valid JSON envelope+payload constructed in
   the Java binding, the Python binding deserializes it to an equivalent typed object with
   identical field values, and vice versa — for each of the nine payload types. (One test per
   payload type; all must pass.)

2. **Single source of truth propagation:** Given a change to one field in one payload's JSON
   Schema source (e.g., adding an optional field to `AlarmEvent`), regenerating both bindings
   produces updated Java and Python classes that reflect the change — with no manual edits to
   either binding's source. (Verified by: re-run the binding-generation step; assert the new
   field is present in both generated artifacts.)

3. **Unknown major `schemaVersion` rejected:** Deserializing an envelope with `schemaVersion = 1`
   succeeds in both the Java binding and the Python binding. Deserializing an envelope with
   `schemaVersion = 2` raises a validation error / exception in both bindings. (Two boundary
   values; both assertions required in each binding.)

### Envelope

4. **Envelope round-trip per payload type:** For each of the nine payload types, serializing an
   envelope+payload to JSON and deserializing it back yields an object equal to the original
   (all required fields present and unchanged, optional fields round-tripping correctly). (Nine
   tests — one per payload type — in each binding.)

5. **`type` discriminates to exactly one payload:** Deserializing a JSON envelope with `type`
   set to each of the nine defined type strings resolves to exactly the corresponding typed
   payload class and no other. Deserializing an envelope with an unrecognized `type` string
   raises a validation error.

6. **Required envelope fields enforced:** Deserializing a JSON envelope with any required
   envelope field (`eventId`, `type`, `schemaVersion`, `occurredAt`, `source`, `traceId`,
   `payload`) absent raises a validation error in both bindings.

### AlarmEvent

7. **`managedObjectId` required on AlarmEvent:** Deserializing an `AlarmEvent` payload with
   `managedObjectId` absent raises a validation error in both bindings.

8. **AlarmEvent `state` enum enforced:** Deserializing an `AlarmEvent` with a `state` value
   other than `raised` or `cleared` raises a validation error in both bindings.

9. **AlarmEvent optional fields:** Deserializing an `AlarmEvent` with `clearedAt` and
   `vendorRaw` absent succeeds; the resulting object represents those fields as absent/null.

### PatternMinedEvent

10. **PatternMinedEvent carries no RCA or lifecycle fields:** The `PatternMinedEvent` schema
    defines no `rootCauseAlarmType`, `lifecycle`, or `patternId` field. Attempting to serialize
    an object with those fields results in either validation failure or those fields being
    stripped — they are not present in the wire format.

11. **PatternMinedEvent provenance is a nested object with required sub-fields:** Deserializing
    a `PatternMinedEvent` where `provenance` is absent, or where any of `provenance.sourceWindowId`,
    `provenance.snapshotId`, or `provenance.codebookVersion` is absent, raises a validation error
    in both bindings. `trailId` is a top-level field and is validated independently.

### TrailsBuiltEvent

12. **TrailsBuiltEvent required fields enforced:** Deserializing a `TrailsBuiltEvent` with any
    of `snapshotId`, `trailIds`, or `trailCount` absent raises a validation error in both bindings.
    Deserializing a valid `TrailsBuiltEvent` with all three fields present succeeds.

### CodebookGeneratedEvent

13. **CodebookGeneratedEvent required fields enforced:** Deserializing a `CodebookGeneratedEvent`
    with any of `snapshotId`, `scenarioCount`, or `codebookId` absent raises a validation error
    in both bindings. Deserializing a valid `CodebookGeneratedEvent` with all three fields present
    succeeds.

### TransactionEvent

14. **TransactionEvent required fields enforced:** Deserializing a `TransactionEvent` with any
    of `transactionId`, `trailId`, `snapshotId`, `alarmIds`, `alarms`, `windowStart`, or
    `windowEnd` absent raises a validation error in both bindings. Deserializing a valid
    `TransactionEvent` with all seven fields present succeeds. Each `alarms` item requires
    `alarmId`, `eventType`, `raisedAt`, `managedObjectId`, and `perceivedSeverity`; a missing
    sub-field or an unknown extra sub-field (the item is `additionalProperties: false`) raises
    a validation error.

### `managedObjectId` scheme

15. **`managedObjectId` valid format accepted:** A `managedObjectId` value of the form
    `<objectType>:<non-empty-id>` passes the library's `managedObjectId` validation in both
    bindings — for Core-IP types (e.g., `Node:PE1`, `Port:PE1-LC2-P3`, `FiberSpan:SPAN-AB-01`)
    **and** for domain-agnostic types not enumerated in the event-model (e.g., `Site:LON-01`,
    `gNodeB:g-7`), since the per-domain object-type set is authored in the Knowledge Service.

16. **`managedObjectId` invalid format rejected:** Each of the following is rejected by the
    library's `managedObjectId` validation in both bindings: (a) a value with no colon
    separator (e.g., `NoColon`), (b) an empty `id` component (e.g., `Node:`), (c) a colon in
    the `id` (e.g., `Node:a:b`), (d) an empty `objectType` (e.g., `:x`), (e) an `objectType`
    not starting with a letter (e.g., `9bad:x`), (f) an empty string. (All must fail
    validation.)

### Build and import

17. **Java binding builds cleanly:** Running the Java build (Gradle) with no pre-existing
    generated artifacts produces a buildable, importable JAR with no compilation errors.

18. **Python binding installs cleanly:** Installing the Python package (pip install) with no
    pre-existing generated artifacts produces an importable package with no import errors.

## Open questions

None.
