# alarm-manager — Service Spec

## Purpose
Sole owner of **live alarm state**. On the real-time path the Alarm Manager sits **in-line**
between the Enrichment Service and the Correlation Engine: it consumes `alarms.enriched.live`,
**persists** each live alarm with initial lifecycle state `open` into its live alarm store, and
**republishes** it on `alarms.persisted.live` for the Correlation Engine to consume. It consumes
`alarms.status.changed` (`AlarmStatusChange`) as the **canonical alarm-status-sync channel**,
applying `newStatus` to the referenced alarm's lifecycle state whenever any service fires a
status change (the Correlation Engine fires `in-progress`, `correlated`, and `reverted-open`
transitions). It also consumes `correlation.results` (`CorrelationResultEvent`) as the
**canonical correlation-context channel** — to maintain each alarm's **correlation-group role**
(`root-cause` / `child`) and **incident linkage** (`incidentId`). These two channels play
complementary, non-overlapping roles: `AlarmStatusChange` is authoritative for lifecycle STATE;
`CorrelationResultEvent` is authoritative for ROLE + `incidentId`. It serves the **live alarm
query API** for the web-ui. There is **no historical corpus** — for the MVP, historical/learning-
path alarms are mined in-flight from Kafka and are not persisted by this service. No new event
payload is introduced: `alarms.persisted.live` carries the existing `AlarmEvent`.

## Scope
**In scope:**
- Consuming `alarms.enriched.live` and persisting each enriched alarm into the live alarm
  store with initial lifecycle state `open`, recording all `AlarmEvent` fields including
  `alarmId`, `managedObjectId` (canonical `<objectType>:<id>` scheme), `eventType`,
  `probableCause`, **`alarmType`**, and `trailIds`. `alarmType` is the **platform canonical
  alarm-type join token** (a required `AlarmEvent` field — the single key pattern mining,
  codebook signatures, `rootCauseAlarmType`, and correlation matching all join on); it MUST be
  persisted in its own field, distinct from `eventType` (X.733 category) and `probableCause`
  (X.733 probable cause), and surfaced on the query API (see below) so the web-ui/incident views
  and the alarm-to-incident join can key off it.
- Republishing each persisted alarm on `alarms.persisted.live` (the same `AlarmEvent`,
  faithful to the consumed message) so the Correlation Engine can consume it; the republish is
  idempotent — a redelivered `alarms.enriched.live` message must not produce a second emit on
  `alarms.persisted.live`.
- Consuming `alarms.status.changed` (`AlarmStatusChange`) and applying `newStatus` to the
  referenced alarm (`alarmId`) in the live alarm store. The full set of `newStatus` values
  handled:
  - `open` — set lifecycle state to `open`; record a timestamped audit entry.
  - `in-progress` — set lifecycle state to `in-progress` (a new intermediate state indicating
    the alarm has entered an active correlation instance); record a timestamped audit entry.
  - `correlated` — set lifecycle state to `correlated`; record a timestamped audit entry. The
    ROLE (`root-cause` / `child`) and `incidentId` for that alarm come from the corresponding
    `correlation.results` event (reconciled by `alarmId`); do NOT derive role/incidentId from
    `AlarmStatusChange`.
  - `cleared` — set lifecycle state to `cleared`; record a timestamped audit entry.
  - `reverted-open` — transition the alarm back to lifecycle state `open` with an audit entry
    whose reason notes it was reverted (instance expired without a match); clear any in-progress
    role association if one was set. `reverted-open` is modelled as a **transition to `open`
    with an audit reason**, not as a permanent distinct state in the store.
  Record `source` and `changedAt` from the `AlarmStatusChange` payload in the audit entry for
  each transition. Deduplicate on the `AlarmStatusChange` envelope `eventId` (at-least-once
  delivery; a later authoritative state wins; transitions are auditable).
- Consuming `correlation.results` and updating each referenced alarm's **correlation-group role
  and incident linkage only**: marking the alarm identified by `rootCauseAlarmId` with role
  `root-cause` and the correct `incidentId`; marking each alarm in `childAlarmIds` with role
  `child` and the same `incidentId`. Lifecycle STATE for these alarms is set by the
  corresponding `AlarmStatusChange(newStatus=correlated)` event, not by `CorrelationResultEvent`
  directly. Both channels are consumed; reconciliation is by `alarmId` (and `incidentId` for
  the correlation group). The update is idempotent on the envelope `eventId`.
- Handling alarm clear events via `AlarmStatusChange(newStatus=cleared)` (the canonical path)
  as well as the existing path: when a consumed `AlarmEvent` from `alarms.enriched.live`
  carries `state = "cleared"`, transitioning the corresponding alarm's lifecycle state to
  `cleared`.
- Recording a timestamped audit entry for every lifecycle state transition (`open`,
  `in-progress`, `correlated`, `cleared`, and `open`-via-revert), including the `source` and
  `changedAt` from `AlarmStatusChange` events where available.
- Serving the **live alarm query API** for the web-ui: list/filter alarms by lifecycle state
  (including `in-progress`), `trailId`, `incidentId`, and time window; retrieve a single
  alarm's full record including lifecycle state, role, `incidentId`, the canonical `alarmType`
  join token, and ordered state-transition history with UTC timestamps. The list summary and the
  single-alarm record both return `alarmType` (distinct from `eventType`/`probableCause`).
- Deduplicating consumed events on `alarmId` (alarm events) and envelope `eventId`
  (`AlarmStatusChange` and `CorrelationResultEvent`) so at-least-once Kafka delivery does not
  produce duplicate records or duplicate state transitions.
- Routing unprocessable messages to the appropriate dead-letter topic.
- Publishing OpenAPI 3.1 at `/openapi.json` (with human-readable UI); the checked-in
  `services/alarm-manager/openapi.json` is the single source of truth for the HTTP surface.

## Out of scope
- **Alarm enrichment** — normalisation, dedup, deterministic filtering, and trail-tagging are
  owned by the Enrichment Service; alarm-manager consumes only already-enriched alarms.
- **Alarm correlation and incident creation** — determining which alarms form an incident and
  what the root cause is, and producing `correlation.results`, is owned by the Correlation
  Engine. Alarm-manager only reflects correlation outcomes into live alarm state.
- **Producing `alarms.status.changed`** — the Alarm Manager is a consumer of this topic, not a
  producer. The Correlation Engine (and any other service) fires `AlarmStatusChange` events;
  the Alarm Manager only reacts.
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

2. Consume `alarms.status.changed` (`AlarmStatusChange`); apply `newStatus` to the referenced
   alarm's lifecycle state in the live alarm store; record a timestamped audit entry (including
   `source` and `changedAt`) for each transition; handle the `reverted-open` value as a
   transition back to `open` with an audit reason noting the revert; clear any in-progress role
   association on revert; deduplicate on envelope `eventId`.

3. Consume `correlation.results` (`CorrelationResultEvent`); update ROLE and incident linkage
   only — set `root-cause` role and `incidentId` for the alarm identified by `rootCauseAlarmId`;
   set `child` role and the same `incidentId` for each alarm in `childAlarmIds`; reconcile with
   lifecycle state set by `AlarmStatusChange` by `alarmId`; record a timestamped audit entry for
   the role/incident assignment; idempotent on envelope `eventId`.

4. Handle clear events arriving via `alarms.enriched.live` (when `AlarmEvent.state = "cleared"`):
   transition the matching alarm to lifecycle state `cleared` and record a timestamped audit
   entry. (The canonical status-sync path for `cleared` is `AlarmStatusChange`; both paths must
   be handled consistently and idempotently.)

5. Serve the **live alarm query API** for the web-ui — list alarms filterable by lifecycle
   state (including `in-progress`), `trailId`, `incidentId`, and time window; retrieve a single
   alarm's full record including lifecycle state, role, `incidentId`, and ordered state-
   transition history with UTC timestamps.

6. Route poison messages (events that fail schema validation against the frozen event-model
   binding, or that cannot be processed after retries) to `alarms.enriched.live.dlq`,
   `correlation.results.dlq`, or `alarms.status.changed.dlq` respectively; never drop silently.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no alarms are active in this phase | Idle | — |
| P2 — Pattern learning | Not involved; alarm-manager does not consume the history path (`alarms.enriched`) and does not run during the learning phase | Idle | — |
| P3 — Real-time correlation | Persists live alarms from `alarms.enriched.live`; republishes on `alarms.persisted.live`; maintains lifecycle state from `alarms.status.changed`; maintains correlation-group role + incident linkage from `correlation.results`; serves live alarm view to web-ui | Active | In: `alarms.enriched.live`, `alarms.status.changed`, `correlation.results`; Out: `alarms.persisted.live`; Serves: live alarm query API |

## Contract

- **Consumes (Kafka):** `alarms.enriched.live`, `correlation.results`, `alarms.status.changed`
- **Produces (Kafka):** `alarms.persisted.live` (the existing `AlarmEvent` payload, republished
  after persist; no new payload)
- **APIs exposed:** Published as OpenAPI 3.1 at `/openapi.json` (with human-readable UI);
  checked-in at `services/alarm-manager/openapi.json`. A change to any operation, path,
  request shape, or response shape is a contract change requiring `architecture.md` update
  and human approval.
  - *Live alarm query API (primary consumer: web-ui):*
    - `GET /alarms` — list alarms, filterable by `state` (including `in-progress`), `trailId`,
      `incidentId`, `from` (ISO-8601 UTC), `to` (ISO-8601 UTC); paginated with `limit` /
      `offset` query params. The list response is the **platform-canonical list-pagination
      envelope** `{ items, total, limit, offset }` (P3-G3) — the **same** envelope the
      Correlation Engine `GET /incidents` and the Pattern Manager `GET /patterns` (`PatternPage`)
      return, so the web-ui streaming view reads one uniform envelope (`.items` / `.total` /
      `.limit` / `.offset`) across the endpoints it polls. `items` is an array of the per-alarm
      summary, which includes the canonical `alarmType` join token (distinct from `eventType` /
      `probableCause`); `total` is the count matching the filter; `limit` / `offset` are echoed
      from the request. This envelope is frozen in `services/alarm-manager/openapi.json`.
    - `GET /alarms/{alarmId}` — retrieve a single alarm's full record: all `AlarmEvent`
      fields (including `eventType`, `probableCause`, and the canonical **`alarmType`** join
      token), lifecycle state, `incidentId`, role tag (`root-cause` / `child` / `none`), and
      the ordered list of state transitions with UTC timestamps.
- **APIs/data consumed from other services:** None. The Alarm Manager is a Kafka-consumer and
  HTTP-server only; it does not call other services' HTTP APIs.
- **Integration points (mock vs. real):** The Alarm Manager exposes HTTP APIs consumed by
  web-ui. That consumer builds its client against this service's published OpenAPI. The Alarm
  Manager itself has no outbound HTTP integration points; no mock/real switching for outbound
  calls is required.
- **Data owned:**
  - **Live alarm store** (PostgreSQL) — enriched alarm records (all `AlarmEvent` fields,
    including `eventType`, `probableCause`, and the canonical **`alarmType`** join token stored
    in its own column, distinct from the two X.733 fields) with lifecycle state (`open` /
    `in-progress` / `correlated` / `cleared`), role tag (`root-cause` / `child` / `none`),
    `incidentId`, and an ordered audit log of state transitions each with a UTC timestamp (and,
    for `AlarmStatusChange`-driven transitions, the originating `source` and `changedAt`). No
    corpus; no historical alarm records. `reverted-open` is not a stored state; it is
    represented as a transition back to `open` with an audit entry noting the revert reason.

## Non-functional

- **Idempotency key:** `alarmId` for alarm persistence and republish (idempotent upsert in the
  live alarm store; no second `alarms.persisted.live` emit on redelivery); envelope `eventId`
  for `AlarmStatusChange` application and for `CorrelationResultEvent` application (processed-
  once guard on state and role/incident updates). At-least-once delivery is assumed for all
  three consumed topics; a later authoritative state event on `alarms.status.changed` wins (last
  writer per `eventId` ordering); transitions are auditable. Consumers must not produce
  duplicate records, duplicate republishes, or duplicate state-transition audit entries on Kafka
  redelivery.
- **Precedence — STATE vs. ROLE:** `AlarmStatusChange` (on `alarms.status.changed`) is
  authoritative for lifecycle STATE (`open` / `in-progress` / `correlated` / `cleared`).
  `CorrelationResultEvent` (on `correlation.results`) is authoritative for ROLE (`root-cause` /
  `child`) and `incidentId`. The two are reconciled by `alarmId` (and `incidentId` for the
  group). Neither channel is silently ignored; both are always consumed.
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
  `alarms.enriched.live.dlq`, `correlation.results.dlq`, or `alarms.status.changed.dlq`
  respectively — never dropped silently.
- **Audit:** every lifecycle state transition is stored with a UTC timestamp and is
  retrievable per alarm via `GET /alarms/{alarmId}`; `AlarmStatusChange`-driven entries also
  record the originating `source` and `changedAt` from the payload.

## Acceptance criteria

1. Given a valid `AlarmEvent` consumed from `alarms.enriched.live`, the alarm record is
   persisted in the live alarm store with lifecycle state `open`, all `AlarmEvent` fields
   stored correctly (`alarmId`, `managedObjectId`, `eventType`, `probableCause`, **`alarmType`**,
   `trailIds`, `raisedAt`, `perceivedSeverity`) — in particular the canonical `alarmType` join
   token is persisted in its own field, distinct from `eventType` and `probableCause` — and a
   single timestamped `open` state-transition audit entry recorded.

2. Given a valid `AlarmEvent` consumed from `alarms.enriched.live`, the same `AlarmEvent` is
   republished on `alarms.persisted.live`; the republished message validates against the
   frozen `AlarmEvent` binding from `libs/event-model`.

3. Given the same `AlarmEvent` (same `alarmId`) consumed twice from `alarms.enriched.live`
   (Kafka redelivery), only one alarm record exists in the live alarm store after both
   deliveries and exactly one message is present on `alarms.persisted.live` (no double
   persist, no double republish).

4. Given a valid `CorrelationResultEvent` consumed from `correlation.results`, the alarm
   identified by `rootCauseAlarmId` is updated in the live alarm store with role `root-cause`
   and the correct `incidentId` recorded; each alarm in `childAlarmIds` is updated with role
   `child` and the same `incidentId` recorded; a timestamped role/incident-assignment audit
   entry is added for each affected alarm. (Lifecycle STATE for these alarms is set by the
   corresponding `AlarmStatusChange` events, not by this event.)

5. Given the same `CorrelationResultEvent` (same envelope `eventId`) consumed twice, the live
   alarm store reflects the role/incident update exactly once — no duplicate state-transition
   audit entries are created (idempotent correlation-result application).

6. Given a `CorrelationResultEvent` that assigns alarms to an incident, and a subsequent
   `AlarmStatusChange(newStatus=correlated)` for the root-cause alarm, a
   `GET /alarms/{alarmId}` for the root-cause alarm returns: lifecycle state `correlated`,
   role `root-cause`, the correct `incidentId`, and an audit log containing both an `open`
   entry and a `correlated` entry each with a distinct UTC timestamp.

7. Given a valid `AlarmEvent` with `state = "cleared"` consumed from `alarms.enriched.live`,
   the corresponding alarm's lifecycle state in the live alarm store is updated to `cleared`
   and a timestamped `cleared` state-transition audit entry is added.

8. Given a `GET /alarms` request with `state=open`, only alarms with lifecycle state `open`
   are returned; alarms with state `correlated`, `cleared`, or `in-progress` are absent from
   the response.

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
    containing the `/alarms` and `/alarms/{alarmId}` path operations defined in this spec, in
    which the `GET /alarms` response schema is the `{ items, total, limit, offset }` envelope and
    the operation declares `limit` / `offset` query params.

15. Given any stored alarm record, its `managedObjectId` value conforms to the
    `<objectType>:<id>` format defined in `libs/event-model`; an `AlarmEvent` carrying a
    malformed `managedObjectId` is rejected and routed to `alarms.enriched.live.dlq`.

16. Given an `AlarmStatusChange(newStatus=in-progress)` consumed from `alarms.status.changed`
    for a known alarm, the alarm's lifecycle state in the live alarm store becomes `in-progress`
    and a timestamped audit entry (including `source` and `changedAt` from the payload) is
    recorded for that transition.

17. Given an `AlarmStatusChange(newStatus=reverted-open)` consumed from `alarms.status.changed`
    for an alarm currently in `in-progress` state, the alarm's lifecycle state returns to `open`,
    a timestamped audit entry is recorded with a reason noting it was reverted (instance expired
    without a match), and any in-progress role association is cleared.

18. Given an alarm whose role and `incidentId` were set by a `CorrelationResultEvent` and whose
    lifecycle state was subsequently set to `correlated` by an `AlarmStatusChange(newStatus=
    correlated)`, the live alarm store record for that alarm has both the correct lifecycle state
    (`correlated`) and the correct role + `incidentId` from the `CorrelationResultEvent`,
    reconciled on `alarmId`.

19. Given a `GET /alarms` request with `state=in-progress`, only alarms with lifecycle state
    `in-progress` are returned; alarms with state `open`, `correlated`, or `cleared` are absent
    from the response.

20. Given a `GET /alarms` request with `limit=L` and `offset=O` matching N alarms, the response
    body is the platform-canonical list-pagination envelope `{ items, total, limit, offset }` —
    a JSON object (not a bare array) whose `items` is the array of matching per-alarm summaries
    (paged by `limit`/`offset`), `total` equals N (the full filtered count), and `limit`/`offset`
    echo the request; the body does NOT use `page` / `size` / `totalElements` / `totalPages`. This
    is the same envelope returned by the Correlation Engine `GET /incidents`, so the web-ui reads
    one uniform envelope across both endpoints (P3-G3).

20. Given an `AlarmStatusChange` message consumed from `alarms.status.changed` that fails schema
    validation against the frozen `AlarmStatusChange` binding (e.g. missing required field
    `alarmId` or an unrecognised `newStatus` value), the message is routed to
    `alarms.status.changed.dlq` and the live alarm store is not modified; processing of
    subsequent messages continues.

21. Given a valid `AlarmEvent` whose `alarmType` is the canonical join token (e.g. `PortDown`,
    distinct from its `eventType` and `probableCause`), the `alarmType` value is persisted in the
    live alarm store in its own field AND returned on both the `GET /alarms` per-alarm summary and
    the `GET /alarms/{alarmId}` full record, as a field distinct from `eventType` and
    `probableCause`, matching the ingested value. (`alarmType` is the platform canonical
    alarm-type join key the web-ui/incident views and the alarm-to-incident join rely on.)

## Open questions

None — all open questions resolved by contract #73 (live-only, in-line design) and the merged
`AlarmStatusChange` contract:
- #69 (corpus access mechanism): resolved by removal — no historical-alarm corpus in MVP.
- #70 (clear semantics on history path): resolved — history path not persisted in MVP; clears apply to live alarms only.
- #71 (alarms.enriched in P2 only): resolved — alarm-manager is Idle in P2; it consumes `alarms.enriched.live` (P3) only.
- #72 (state for never-correlated open alarms): resolved — uncorrelated live alarms remain `open` (resting state) until cleared; no additional state for MVP.
- Ordering/reconciliation between `AlarmStatusChange` and `CorrelationResultEvent` for the same alarm: the two channels carry non-overlapping authority (STATE vs. ROLE+incidentId), so no total-order constraint is required. Either can arrive first; both are applied idempotently. The alarm record is consistent once both have been applied. Whether the role/incident fields are cleared when state reverts to `open` via `reverted-open` is a design-stage decision — the designer must specify the exact store behaviour and flag for a human decision if needed (recommendation: clear in-progress role association on revert; `incidentId` and final role from a completed `CorrelationResultEvent` remain set).
