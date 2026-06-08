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
  the topology graph to reference the same objects.
- Enforce the **specialization rule**: each `type` value maps to exactly one payload schema.
- Enforce the **`schemaVersion` compatibility policy**: consumers reject envelopes whose major
  `schemaVersion` exceeds the supported major.
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
  policy is reject-on-unknown-major; migration is a future concern.
- Redundancy/protection-aware propagation fields, multi-domain payloads, HA/scale — all deferred
  per MVP non-goals.

## Tasks (high-level)

1. Define the **envelope** schema with fields `eventId`, `type`, `schemaVersion`, `occurredAt`,
   `source`, `traceId`, `payload`, and generate both Java and Python bindings from it.
2. Define each of the **nine payload schemas** (see Contract) from one JSON Schema source,
   generating both bindings, such that each `type` string resolves to exactly one payload.
3. Define and encode the **`managedObjectId` scheme** — the shared identity format used by the
   Simulator for alarm emission and by the Topology Service for graph nodes — so all services
   referencing network objects use the same identifier format.
4. Implement **`schemaVersion` validation** in both bindings: deserialization raises/rejects when
   the event's major `schemaVersion` exceeds the supported major.
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
| `schemaVersion` | integer | yes | consumers reject unknown major version |
| `occurredAt` | datetime (ISO-8601) | yes | when the event occurred |
| `source` | string | yes | originating service name |
| `traceId` | string | yes | distributed trace identifier |
| `payload` | object | yes | typed per `type`; one of the nine payload schemas |

### Payload schemas (authoritative field lists from §7)

**AlarmEvent** (X.733-aligned; carried on `alarms.*` topics):

| Field | Type | Required | Notes |
|---|---|---|---|
| `alarmId` | string | yes | unique alarm identifier |
| `managedObjectId` | string | yes | must match topology graph identity scheme |
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

| Field | Type | Required | Notes |
|---|---|---|---|
| `snapshotId` | string | yes | topology snapshot this build references |
| (summary fields) | — | — | carries trail summaries; full data available via Trail Builder API |

**CodebookGeneratedEvent** (carried on `codebook.generated`):

| Field | Type | Required | Notes |
|---|---|---|---|
| `snapshotId` | string | yes | topology snapshot this codebook was compiled from |
| (summary fields) | — | — | carries scenario summaries; full data available via Codebook Generator API |

**TransactionEvent** (carried on `transactions.clean`):

| Field | Type | Required | Notes |
|---|---|---|---|
| (trail-scoped alarm group fields) | — | — | DBSCAN-cleaned, trail-scoped alarm group for the Pattern Miner |

**PatternMinedEvent** (carried on `patterns.mined`; Miner output only — no RCA, no lifecycle):

| Field | Type | Required | Notes |
|---|---|---|---|
| `sequence` | string[] | yes | ordered alarm type sequence discovered |
| `support` | float | yes | frequency of the sequence |
| `confidence` | float | yes | conditional probability |
| `lift` | float | yes | lift over baseline |
| `trailId` | string | yes | trail scope the pattern was mined from |
| `timing` | object | yes | inter-arrival timing statistics for the sequence |
| `provenance` | object | yes | source window reference, snapshot version, codebook version in scope |

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
| `matchedCodebookId` | string | no | codebook scenario that matched, if any |
| `confidence` | float | yes | correlation confidence score |
| `trailId` | string | yes | trail scope of the incident |

### `managedObjectId` scheme

The `managedObjectId` is the shared identity binding for network objects: the **same identifier
format** must be used by the Simulator when generating alarms and by the Topology Service when
persisting graph nodes. The scheme is defined once in this library and referenced by both
cohorts. Its exact format (prefix conventions, structure) is specified in the JSON Schema source.

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
- **`schemaVersion` compatibility policy:** consumers MUST reject any envelope whose major
  `schemaVersion` exceeds the highest major version supported by the installed library. Minor
  version increments are additive/backward-compatible; major increments are breaking.
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

3. **Unknown major `schemaVersion` rejected:** Deserializing an envelope whose `schemaVersion`
   major component exceeds the supported major (e.g., `schemaVersion = 2` when the library
   supports major `1`) raises a validation error / exception in both the Java binding and the
   Python binding. A `schemaVersion` at or below the supported major is accepted.

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

### managedObjectId scheme

11. **`managedObjectId` scheme is defined in the library:** The library exposes a documented
    identifier scheme (e.g., format string, validation regex, or factory) for `managedObjectId`
    values. A `managedObjectId` that does not conform to the scheme fails validation when
    validated against the scheme.

### Build and import

12. **Java binding builds cleanly:** Running the Java build (Gradle) with no pre-existing
    generated artifacts produces a buildable, importable JAR with no compilation errors.

13. **Python binding installs cleanly:** Installing the Python package (pip install) with no
    pre-existing generated artifacts produces an importable package with no import errors.

## Open questions

- **`TrailsBuiltEvent` summary fields:** §7 states this event carries "trail summaries (full
  data via API)" but does not enumerate the summary fields. The exact field list for
  `TrailsBuiltEvent` is not specified in `docs/architecture.md` or §7. **Resolution needed:**
  what summary fields (e.g., `trailCount`, `trailIds[]`, `snapshotId`) must this payload carry?
  (A human must decide; the designer cannot define the schema until this is resolved.)

- **`CodebookGeneratedEvent` summary fields:** §7 states this event carries "scenario
  summaries (full data via API)" but does not enumerate the summary fields. **Resolution
  needed:** what summary fields (e.g., `scenarioCount`, `snapshotId`) must this payload carry?

- **`TransactionEvent` field list:** §7 describes `TransactionEvent` as a "DBSCAN-cleaned,
  trail-scoped alarm group" but does not enumerate the fields. **Resolution needed:** what
  fields does this payload carry (e.g., `trailId`, `alarmIds[]`, `windowStart`, `windowEnd`)?

- **`managedObjectId` format:** The scheme is referenced in §4.5 and §7 as "defined once in
  the canonical library" but the exact format (e.g., `<domain>:<type>:<id>`, UUID, structured
  string) is not specified in `docs/architecture.md` or §7. **Resolution needed:** the designer
  cannot produce the JSON Schema constraint until the format is defined by a human.

- **`schemaVersion` initial value:** The library version is "versioned with the repo" per §4.3,
  but the initial `schemaVersion` integer (e.g., `1`) is not stated explicitly. **Resolution
  needed:** confirm the initial supported major version so consumers can be coded to reject
  anything above it.

- **`PatternMinedEvent` provenance sub-fields:** §7 names `provenance` as a field (source
  window, snapshot/codebook version in scope) but does not specify whether `provenance` is a
  nested object with named sub-fields or a flat string. **Resolution needed:** define the
  sub-field names and types for `provenance` so the schema can be specified precisely.
