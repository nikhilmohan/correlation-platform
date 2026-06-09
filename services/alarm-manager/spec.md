# alarm-manager — Service Spec

## Purpose
Sole owner of **live alarm state**. On the real-time path the Alarm Manager sits **in-line**
between the Enrichment Service and the Correlation Engine: it consumes `alarms.enriched.live`,
**persists** each live alarm with initial lifecycle state `open` into its live alarm store, and
**republishes** it on `alarms.persisted.live` for the Correlation Engine to consume. It also
consumes `correlation.results` to maintain each alarm's lifecycle (`open` → `correlated` →
`cleared`), its **correlation-group role** (`root-cause` / `child`), and its incident linkage.
It serves the **live alarm query API** for the web-ui. There is **no historical corpus** — for
the MVP, historical/learning-path alarms are mined in-flight from Kafka and are not persisted
by this service. No new event payload is introduced: `alarms.persisted.live` carries the
existing `AlarmEvent`.

## Scope
**In scope:**
- Consuming `alarms.enriched.live` and persisting each enriched alarm into the live alarm
  store with initial lifecycle state `open`, recording all `AlarmEvent` fields including
  `alarmId`, `managedObjectId` (canonical `<objectType>:<id>` scheme), and `trailIds`.
- Republishing each persisted alarm on `alarms.persisted.live` (the same `AlarmEvent`,
  faithful to the consumed message) so the Correlation Engine can consume it; the republish is
  idempotent — a redelivered `alarms.enriched.live` message must not produce a second emit on
  `alarms.persisted.live`.
- Consuming `correlation.results` and updating each referenced alarm's live state: marking the
  alarm identified by `rootCauseAlarmId` as `correlated` with role `root-cause`, marking each
  alarm in `childAlarmIds` as `correlated` with role `child`, and recording the `incidentId`
  on all affected alarms.
- Handling alarm clear events: when a consumed `AlarmEvent` from `alarms.enriched.live` carries
  `state = "cleared"`, transitioning the corresponding alarm's lifecycle state to `cleared`.
- Recording a timestamped audit entry for every lifecycle state transition (`open`, `correlated`,
  `cleared`).
- Serving the **live alarm query API** for the web-ui: list/filter alarms by lifecycle state,
  `trailId`, `incidentId`, and time window; retrieve a single alarm's full record including
  lifecycle state, role, `incidentId`, and ordered state-transition history with UTC timestamps.
- Deduplicating consumed events on `alarmId` (alarm events) and envelope `eventId`
  (correlation-result events) so at-least-once Kafka delivery does not produce duplicate
  records or duplicate state transitions.
- Routing unprocessable messages to the appropriate dead-letter topic.
- Publishing OpenAPI 3.1 at `/openapi.json` (with human-readable UI); the checked-in
  `services/alarm-manager/openapi.json` is the single source of truth for the HTTP surface.

## Out of scope
- **Alarm enrichment** — normalisation, dedup, deterministic filtering, and trail-tagging are
  owned by the Enrichment Service; alarm-manager consumes only already-enriched alarms.
- **Alarm correlation and incident creation** — determining which alarms form an incident and
  what the root cause is, and producing `correlation.results`, is owned by the Correlation
  Engine. Alarm-manager only reflects correlation outcomes into live alarm state.
- **Incident store / incidents** — the Correlation Engine is the **system of record for
  incidents** (incident-centric view) and owns the Incident Store. Alarm-manager does not own or
  duplicate incidents; it only **denormalizes** the `incidentId` reference + role tag
  (root-cause / child) onto each live alarm record (the alarm-centric view).
- **DBSCAN / PrefixSpan mining** — owned by Noise Filter and Pattern Miner respectively;
  alarm-manager has no involvement in the learning path.
- **Historical-alarm corpus / persisting historical alarms** — explicitly NOT in MVP. Alarms on
  `alarms.history` and `alarms.enriched` (the P2 learning path) are mined in-flight from Kafka
  (simulator → enrichment → noise-filter → pattern-miner); the alarm-manager does not consume
  these topics and does not persist any historical alarm. A durable historical-alarm corpus is
  deferred post-MVP.
- **Pattern state and lifecycle** — owned exclusively by the Pattern Manager.
- **Topology graph** — owned exclusively by the Topology Service.
- **Knowledge templates, policy, and params** — owned exclusively by the Knowledge Service.
- **Any other service writing alarm records** — alarm-manager is the sole owner of live alarm
  state; no other service writes to the live alarm store.

## Tasks (high-level)

1. Consume `alarms.enriched.live`; persist each `AlarmEvent` into the live alarm store with
   initial lifecycle state `open` (idempotent on `alarmId`); republish the same `AlarmEvent`
   on `alarms.persisted.live` (idempotent — no second emit on redelivery).

2. Consume `correlation.results`; for the alarm identified by `rootCauseAlarmId` set lifecycle
   state `correlated` with role `root-cause` and link `incidentId`; for each alarm in
   `childAlarmIds` set lifecycle state `correlated` with role `child` and link the same
   `incidentId`; record a timestamped state-transition audit entry for every change. The update
   is idempotent on the envelope `eventId`.

3. Handle clear events: when an `AlarmEvent` consumed from `alarms.enriched.live` carries
   `state = "cleared"`, transition the matching alarm in the live alarm store to lifecycle
   state `cleared` and record a timestamped state-transition audit entry.

4. Serve the **live alarm query API** for the web-ui — list alarms filterable by lifecycle
   state, `trailId`, `incidentId`, and time window; retrieve a single alarm's full record
   including lifecycle state, role, `incidentId`, and ordered state-transition history with UTC
   timestamps.

5. Route poison messages (events that fail schema validation against the frozen event-model
   binding, or that cannot be processed after retries) to `alarms.enriched.live.dlq` or
   `correlation.results.dlq` respectively; never drop silently.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no alarms are active in this phase | Idle | — |
| P2 — Pattern learning | Not involved; alarm-manager does not consume the history path (`alarms.enriched`) and does not run during the learning phase | Idle | — |
| P3 — Real-time correlation | Persists live alarms from `alarms.enriched.live`; republishes on `alarms.persisted.live`; maintains lifecycle + correlation-group role from `correlation.results`; serves live alarm view to web-ui | Active | In: `alarms.enriched.live`, `correlation.results`; Out: `alarms.persisted.live`; Serves: live alarm query API |

## Contract

- **Consumes (Kafka):** `alarms.enriched.live`, `correlation.results`
- **Produces (Kafka):** `alarms.persisted.live` (the existing `AlarmEvent` payload, republished
  after persist; no new payload)
- **APIs exposed:** Published as OpenAPI 3.1 at `/openapi.json` (with human-readable UI);
  checked-in at `services/alarm-manager/openapi.json`. A change to any operation, path,
  request shape, or response shape is a contract change requiring `architecture.md` update
  and human approval.
  - *Live alarm query API (primary consumer: web-ui):*
    - `GET /alarms` — list alarms, filterable by `state`, `trailId`, `incidentId`, `from`
      (ISO-8601 UTC), `to` (ISO-8601 UTC); paginated.
    - `GET /alarms/{alarmId}` — retrieve a single alarm's full record: all `AlarmEvent`
      fields, lifecycle state, `incidentId`, role tag (`root-cause` / `child` / `none`), and
      the ordered list of state transitions with UTC timestamps.
- **APIs/data consumed from other services:** None. The Alarm Manager is a Kafka-consumer and
  HTTP-server only; it does not call other services' HTTP APIs.
- **Integration points (mock vs. real):** The Alarm Manager exposes HTTP APIs consumed by
  web-ui. That consumer builds its client against this service's published OpenAPI. The Alarm
  Manager itself has no outbound HTTP integration points; no mock/real switching for outbound
  calls is required.
- **Data owned:**
  - **Live alarm store** (PostgreSQL) — enriched alarm records with lifecycle state (`open` /
    `correlated` / `cleared`), role tag (`root-cause` / `child` / `none`), `incidentId`, and
    an ordered audit log of state transitions each with a UTC timestamp. No corpus; no
    historical alarm records.

## Non-functional

- **Idempotency key:** `alarmId` for alarm persistence and republish (idempotent upsert in the
  live alarm store; no second `alarms.persisted.live` emit on redelivery); envelope `eventId`
  for correlation-result application (processed-once guard on state updates). Consumers must
  not produce duplicate records, duplicate republishes, or duplicate state-transition audit
  entries on Kafka redelivery.
- **Config:** all integration addresses (Kafka bootstrap servers, PostgreSQL JDBC URL,
  consumer group IDs) and tuning parameters from environment variables; no hard-coded
  thresholds, URLs, or credentials in source code.
- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus-format),
  structured JSON logs; the envelope `traceId` is propagated into all log entries for
  correlated tracing.
- **API contract:** publishes OpenAPI 3.1 at `/openapi.json` and checks in
  `services/alarm-manager/openapi.json`; the running implementation must not drift from the
  checked-in spec; the spec drives contract/unit tests.
- **Error handling:** unprocessable or persistently-failing messages are routed to
  `alarms.enriched.live.dlq` and `correlation.results.dlq` respectively — never dropped
  silently.
- **Audit:** every lifecycle state transition is stored with a UTC timestamp and is
  retrievable per alarm via `GET /alarms/{alarmId}`.

## Acceptance criteria

1. Given a valid `AlarmEvent` consumed from `alarms.enriched.live`, the alarm record is
   persisted in the live alarm store with lifecycle state `open`, all `AlarmEvent` fields
   stored correctly (`alarmId`, `managedObjectId`, `trailIds`, `raisedAt`,
   `perceivedSeverity`), and a single timestamped `open` state-transition audit entry
   recorded.

2. Given a valid `AlarmEvent` consumed from `alarms.enriched.live`, the same `AlarmEvent` is
   republished on `alarms.persisted.live`; the republished message validates against the
   frozen `AlarmEvent` binding from `libs/event-model`.

3. Given the same `AlarmEvent` (same `alarmId`) consumed twice from `alarms.enriched.live`
   (Kafka redelivery), only one alarm record exists in the live alarm store after both
   deliveries and exactly one message is present on `alarms.persisted.live` (no double
   persist, no double republish).

4. Given a valid `CorrelationResultEvent` consumed from `correlation.results`, the alarm
   identified by `rootCauseAlarmId` is updated in the live alarm store to lifecycle state
   `correlated` with role `root-cause` and the correct `incidentId` recorded; each alarm in
   `childAlarmIds` is updated to lifecycle state `correlated` with role `child` and the same
   `incidentId` recorded; a timestamped `correlated` state-transition audit entry is added for
   each affected alarm.

5. Given the same `CorrelationResultEvent` (same envelope `eventId`) consumed twice, the live
   alarm store reflects the correlation update exactly once — no duplicate state-transition
   audit entries are created (idempotent correlation-result application).

6. Given a `CorrelationResultEvent` that transitions alarms to `correlated`, a
   `GET /alarms/{alarmId}` for the root-cause alarm returns: lifecycle state `correlated`,
   role `root-cause`, the correct `incidentId`, and an audit log containing both an `open`
   entry and a `correlated` entry each with a distinct UTC timestamp.

7. Given a valid `AlarmEvent` with `state = "cleared"` consumed from `alarms.enriched.live`,
   the corresponding alarm's lifecycle state in the live alarm store is updated to `cleared`
   and a timestamped `cleared` state-transition audit entry is added.

8. Given a `GET /alarms` request with `state=open`, only alarms with lifecycle state `open`
   are returned; alarms with state `correlated` or `cleared` are absent from the response.

9. Given a `GET /alarms` request with a specific `trailId`, only alarms whose `trailIds`
   contain the specified value are returned; alarms on a different trail are excluded.

10. Given a `GET /alarms` request with a specific `incidentId`, only alarms linked to that
    incident are returned; alarms linked to a different incident or with no incident are
    excluded.

11. Given a `GET /alarms` request with `from` and `to` parameters, only alarms whose
    `raisedAt` falls within the specified time window are returned; alarms outside the window
    are excluded.

12. Given an event message that fails schema validation against the frozen `AlarmEvent` binding
    (e.g. missing required field `alarmId`), the message is routed to
    `alarms.enriched.live.dlq` and no record is persisted in the live alarm store and no
    message is published on `alarms.persisted.live`.

13. Given an event envelope with an unknown major `schemaVersion`, the message is rejected and
    routed to the dead-letter topic without persisting any record or publishing any republish.

14. A `GET /openapi.json` request returns HTTP 200 whose body is a valid OpenAPI 3.1 document
    containing the `/alarms` and `/alarms/{alarmId}` path operations defined in this spec.

15. Given any stored alarm record, its `managedObjectId` value conforms to the
    `<objectType>:<id>` format defined in `libs/event-model`; an `AlarmEvent` carrying a
    malformed `managedObjectId` is rejected and routed to `alarms.enriched.live.dlq`.

## Open questions

None — all open questions resolved by contract #73 (live-only, in-line design):
- #69 (corpus access mechanism): resolved by removal — no historical-alarm corpus in MVP.
- #70 (clear semantics on history path): resolved — history path not persisted in MVP; clears apply to live alarms only.
- #71 (alarms.enriched in P2 only): resolved — alarm-manager is Idle in P2; it consumes `alarms.enriched.live` (P3) only.
- #72 (state for never-correlated open alarms): resolved — uncorrelated live alarms remain `open` (resting state) until cleared; no additional state for MVP.
