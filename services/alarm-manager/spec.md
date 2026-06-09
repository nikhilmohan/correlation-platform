# alarm-manager — Service Spec

## Purpose
Sole owner of alarm persistence. The Alarm Manager consumes enriched alarms from
`alarms.enriched` (historical path) and `alarms.enriched.live` (live path), and correlation
outcomes from `correlation.results`. It owns two PostgreSQL stores: the **operational
alarm-lifecycle store** — every enriched alarm with its tracked lifecycle state
(`open` → `correlated` → `cleared`) plus incident linkage and root-cause/child role tags,
which is the live view the web-ui renders — and the **analytical historical-alarm corpus** —
the durable body of enriched historical alarms, queryable by trail and time window, that the
Noise Filter (DBSCAN) and Pattern Miner (PrefixSpan) mine over (Kafka is a bus, not a
queryable history). The service exposes query APIs for both stores and introduces no new Kafka
topic or event payload.

## Scope
**In scope:**
- Consuming `alarms.enriched` and persisting each enriched alarm into the analytical
  historical-alarm corpus.
- Consuming `alarms.enriched` and `alarms.enriched.live` and persisting each enriched alarm
  into the operational alarm-lifecycle store with initial lifecycle state `open`, recording all
  `AlarmEvent` fields including `managedObjectId` (in the canonical `<objectType>:<id>` scheme)
  and `trailIds`.
- Consuming `correlation.results` and updating the lifecycle state of referenced alarms in the
  operational store: marking the root-cause alarm and each child alarm as `correlated`,
  recording the `incidentId` and each alarm's role (root-cause or child).
- Handling alarm clear events: when a consumed `AlarmEvent` carries `state = "cleared"`,
  transitioning the corresponding alarm's lifecycle state to `cleared` in the operational
  store.
- Serving a **lifecycle query API** for the web-ui: list and filter alarms by lifecycle state,
  `trailId`, time window, and `incidentId`; retrieve a single alarm's full lifecycle history
  including all state transitions with timestamps.
- Serving a **historical-corpus query API** for the learning path: query the analytical corpus
  by `trailId` and time window, returning the enriched alarm set that the Noise Filter and
  Pattern Miner operate over.
- Deduplicating consumed events on `alarmId` (alarm events) and envelope `eventId`
  (correlation-result events) so that at-least-once Kafka delivery does not produce duplicate
  records or duplicate state transitions.
- Recording a timestamped audit entry for every lifecycle state transition.
- Routing unprocessable messages to the appropriate dead-letter topic.
- Publishing OpenAPI 3.1 at `/openapi.json` (with human-readable UI); the checked-in
  `services/alarm-manager/openapi.json` is the single source of truth for the HTTP surface.

## Out of scope
- **Alarm enrichment** — normalisation, dedup, deterministic filtering, and trail-tagging are
  owned by the Enrichment Service; alarm-manager consumes only already-enriched alarms.
- **Alarm correlation and incident creation** — determining which alarms form an incident and
  what the root cause is, and producing `correlation.results`, is owned by the Correlation
  Engine. Alarm-manager only reflects correlation outcomes into alarm lifecycle state.
- **Incident store** — the Incident Store is owned by the Correlation Engine; alarm-manager
  records only the `incidentId` reference and role tag on each alarm record.
- **DBSCAN / PrefixSpan mining** — the Noise Filter and Pattern Miner read the historical
  corpus; they do not own or write to it.
- **Pattern state and lifecycle** — owned exclusively by the Pattern Manager.
- **Topology graph** — owned exclusively by the Topology Service.
- **Knowledge templates, policy, and params** — owned exclusively by the Knowledge Service.
- **Any other service writing alarm records** — alarm-manager is the sole alarm persistence
  owner; no other service writes alarm records into either alarm store.

## Tasks (high-level)

1. Consume `alarms.enriched` and persist each enriched `AlarmEvent` into the analytical
   historical-alarm corpus, keying on `alarmId` to avoid duplicate corpus entries.

2. Consume `alarms.enriched` and `alarms.enriched.live` and upsert each enriched `AlarmEvent`
   into the operational alarm-lifecycle store with initial lifecycle state `open`, recording
   all `AlarmEvent` fields; skip (no-op) if the `alarmId` already exists.

3. Consume `correlation.results` and update the operational store: for the alarm identified by
   `rootCauseAlarmId` set lifecycle state `correlated` with role `root-cause` and link
   `incidentId`; for each `alarmId` in `childAlarmIds` set lifecycle state `correlated` with
   role `child` and link the same `incidentId`; record a timestamped state-transition audit
   entry for every change. The update is idempotent: re-applying the same event (identified by
   the envelope `eventId`) produces no additional change.

4. Handle alarm clear events: when a consumed `AlarmEvent` (from either topic) carries
   `state = "cleared"`, transition the matching alarm's lifecycle state in the operational
   store to `cleared` and record a timestamped state-transition audit entry.

5. Serve the **alarm-lifecycle query API** for the web-ui — list alarms filterable by lifecycle
   state, `trailId`, `incidentId`, and time window; retrieve a single alarm's full record
   including lifecycle state, `incidentId`, role tag, and the ordered list of state transitions
   with timestamps.

6. Serve the **historical-corpus query API** for the learning path — query the analytical
   corpus by `trailId` and/or time window (`from`/`to`), returning the matching enriched alarm
   set; the Noise Filter and Pattern Miner build their clients against this service's published
   OpenAPI.

7. Route poison messages (events that fail schema validation against the frozen event-model
   binding, or that cannot be processed after retries) to `alarms.enriched.dlq`,
   `alarms.enriched.live.dlq`, or `correlation.results.dlq` respectively; never drop silently.

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no alarms exist and no alarm topics are active in this phase | Idle | — |
| P2 — Pattern learning | Persists enriched historical alarms from `alarms.enriched` into the analytical corpus; serves the corpus to Noise Filter and Pattern Miner via the corpus query API | Active | In: `alarms.enriched`; Serves: historical-corpus query API |
| P3 — Real-time correlation | Persists live enriched alarms from `alarms.enriched.live`; updates alarm lifecycle from `correlation.results`; serves the live alarm-lifecycle view to web-ui | Active | In: `alarms.enriched.live`, `correlation.results`; Serves: alarm-lifecycle query API |

## Contract

- **Consumes (Kafka):** `alarms.enriched`, `alarms.enriched.live`, `correlation.results`
- **Produces (Kafka):** — (serves query APIs only; no topics produced)
- **APIs exposed:** Published as OpenAPI 3.1 at `/openapi.json` (with human-readable UI);
  checked-in at `services/alarm-manager/openapi.json`. A change to any operation, path,
  request shape, or response shape is a contract change requiring `architecture.md` update
  and human approval.
  - *Alarm-lifecycle query API (primary consumer: web-ui):*
    - `GET /alarms` — list alarms, filterable by `state`, `trailId`, `incidentId`, `from`
      (ISO-8601 UTC), `to` (ISO-8601 UTC); paginated.
    - `GET /alarms/{alarmId}` — retrieve a single alarm's full record: all `AlarmEvent`
      fields, lifecycle state, `incidentId`, role tag, and the ordered list of state
      transitions with UTC timestamps.
  - *Historical-corpus query API (primary consumers: Noise Filter, Pattern Miner):*
    - `GET /corpus/alarms` — query enriched alarms in the analytical corpus filtered by
      `trailId` and/or time window (`from`, `to`; ISO-8601 UTC); paginated.
- **APIs/data consumed from other services:** None. The Alarm Manager is a Kafka-consumer and
  HTTP-server only; it does not call other services' HTTP APIs.
- **Integration points (mock vs. real):** The Alarm Manager exposes HTTP APIs consumed by
  web-ui, Noise Filter, and Pattern Miner. Those consumers build their clients against this
  service's published OpenAPI. The Alarm Manager itself has no outbound HTTP integration
  points; no mock/real switching for outbound calls is required.
- **Data owned:**
  - **Operational alarm-lifecycle store** (PostgreSQL, schema `alarm_lifecycle`) — enriched
    alarm records with lifecycle state (`open` / `correlated` / `cleared`), `incidentId`,
    role tag (`root-cause` / `child` / `none`), and an ordered audit log of state transitions
    each with a UTC timestamp.
  - **Analytical historical-alarm corpus** (PostgreSQL, schema `alarm_corpus`) — the durable
    body of enriched historical `AlarmEvent` records, indexed by `trailId` and `raisedAt`.

## Non-functional

- **Idempotency key:** `alarmId` for alarm persistence (idempotent upsert on both stores);
  envelope `eventId` for correlation-result application (processed-once guard). Consumers must
  not produce duplicate records or duplicate state-transition audit entries on Kafka
  redelivery.
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
  `alarms.enriched.dlq`, `alarms.enriched.live.dlq`, and `correlation.results.dlq`
  respectively — never dropped silently.
- **Audit:** every lifecycle state transition is stored with a UTC timestamp and is
  retrievable per alarm via `GET /alarms/{alarmId}`.

## Acceptance criteria

1. Given a valid `AlarmEvent` consumed from `alarms.enriched`, the alarm record is persisted
   in the analytical historical-alarm corpus with all `AlarmEvent` fields stored correctly,
   including `alarmId`, `managedObjectId`, `trailIds`, `raisedAt`, and `perceivedSeverity`.

2. Given a valid `AlarmEvent` consumed from `alarms.enriched`, the alarm record is persisted
   in the operational alarm-lifecycle store with lifecycle state `open` and a single
   timestamped `open` state-transition audit entry.

3. Given a valid `AlarmEvent` consumed from `alarms.enriched.live`, the alarm record is
   persisted in the operational alarm-lifecycle store with lifecycle state `open` and a single
   timestamped `open` state-transition audit entry.

4. Given the same `AlarmEvent` (same `alarmId`) consumed twice from the same topic (Kafka
   redelivery), only one alarm record exists in each store after both deliveries and no
   duplicate state-transition audit entry is created (idempotent upsert).

5. Given a valid `CorrelationResultEvent` consumed from `correlation.results`, the alarm
   identified by `rootCauseAlarmId` is updated in the operational store to lifecycle state
   `correlated` with role `root-cause` and the correct `incidentId` recorded; each alarm in
   `childAlarmIds` is updated to lifecycle state `correlated` with role `child` and the same
   `incidentId` recorded.

6. Given the same `CorrelationResultEvent` (same envelope `eventId`) consumed twice, the
   operational store reflects the correlation update exactly once — no duplicate state-transition
   audit entries are created (idempotent correlation-result application).

7. Given a `CorrelationResultEvent` that transitions alarms to `correlated`, a `GET
   /alarms/{alarmId}` for the root-cause alarm returns: lifecycle state `correlated`, role
   `root-cause`, the correct `incidentId`, and an audit log containing both an `open` entry
   and a `correlated` entry each with a distinct UTC timestamp.

8. Given a valid `AlarmEvent` with `state = "cleared"` and `clearedAt` set, consumed from
   `alarms.enriched.live`, the corresponding alarm's lifecycle state in the operational store
   is updated to `cleared` and a timestamped `cleared` state-transition audit entry is added.

9. Given a `GET /corpus/alarms` request with a `trailId` and a time window (`from`, `to`),
   only alarms in the analytical corpus whose `trailIds` contain the specified `trailId` and
   whose `raisedAt` falls within the window are returned; alarms outside the time window or
   on a different trail are excluded from the response.

10. Given a `GET /alarms` request with `state=correlated`, only alarms with lifecycle state
    `correlated` are returned; alarms with state `open` or `cleared` are absent from the
    response.

11. Given a `GET /alarms` request with a specific `incidentId`, only alarms linked to that
    incident are returned; alarms linked to a different incident or with no incident are
    excluded.

12. Given an event message that fails schema validation against the frozen `AlarmEvent` binding
    (e.g. missing required field `alarmId`), the message is routed to the corresponding
    `*.dlq` topic and no partial record is persisted in either store.

13. Given an event envelope with an unknown major `schemaVersion`, the message is rejected and
    routed to the dead-letter topic without persisting any record.

14. A `GET /openapi.json` request returns an HTTP 200 response whose body is a valid OpenAPI
    3.1 document containing the `/alarms`, `/alarms/{alarmId}`, and `/corpus/alarms` path
    operations defined in this spec.

15. Given any stored alarm record, its `managedObjectId` value conforms to the
    `<objectType>:<id>` format defined in `libs/event-model`; an `AlarmEvent` carrying a
    malformed `managedObjectId` is rejected and routed to the dead-letter topic.

## Open questions

1. **Corpus access mechanism for Noise Filter and Pattern Miner.** `architecture.md` states
   "the exact access mechanism (corpus query API vs. topic consumption) is a design-stage
   detail." This spec treats the access as a query API (`GET /corpus/alarms`), which is the
   only option consistent with alarm-manager owning the corpus as a single-owner PostgreSQL
   store. If the chosen mechanism differs (e.g. the consumers read directly from a Kafka
   topic), the Contract section and acceptance criteria 9 require a human-approved contract
   update before design proceeds. Tracked: open question pending design confirmation.

2. **Alarm clear semantics on the history path (`alarms.enriched` / P2).** The frozen
   `AlarmEvent` schema includes `state = "cleared"` and an optional `clearedAt` field. For
   alarms consumed on the P2 history path (`alarms.enriched`), should a `state = "cleared"`
   event transition the operational-store lifecycle state to `cleared`, or should clear events
   on the history path be recorded in the corpus only? This spec applies the `cleared`
   lifecycle transition for both paths (Task 4 and acceptance criterion 8 cover
   `alarms.enriched.live`). A human should confirm whether the `cleared` operational-store
   transition also applies to `alarms.enriched` history-path events.

3. **Whether `alarms.enriched` is also consumed in P3.** The canonical phase map shows
   alarm-manager consuming `alarms.enriched` in P2 (history path) and `alarms.enriched.live`
   in P3 (live path). This spec treats these as phase-distinct topics. A human should confirm
   that alarm-manager does not also need to persist `alarms.enriched` events into the
   operational store during P3 (i.e. that P3 operational persistence is `alarms.enriched.live`
   only).

4. **Lifecycle state for unmatched open alarms.** The architecture defines `open → correlated
   → cleared`. There is no stated state for an alarm that remains unmatched after all
   correlation runs complete (i.e. stays `open` indefinitely). This spec retains `open` as the
   persistent state for such alarms. If an additional terminal state (e.g. `unmatched`) is
   needed and under what condition it should be triggered, that requires a human decision and
   would constitute a contract change to this spec.
