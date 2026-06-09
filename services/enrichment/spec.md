# enrichment — Service Spec

## Purpose

The Enrichment Service is the first-stage alarm processing pipeline for the platform,
active in **both Phase 2 (pattern learning) and Phase 3 (real-time correlation)** — the
only service that participates as Active in two runtime phases. It consumes raw alarm
streams from `alarms.history` (batch/learning) and `alarms.live` (streaming/real-time),
normalizes each alarm to the canonical `AlarmEvent` (X.733-aligned), deduplicates
repeated identical alarms within a short window, applies a suite of deterministic noise
filters (flap-damping, self-clear suppression, maintenance suppression, known-chatter
removal), and tags every surviving alarm with its `trailIds` via the Trail Builder
`getTrailsForObject` API. It emits enriched, trail-tagged alarms onto `alarms.enriched`
(history path, consumed by the Noise Filter in P2) and `alarms.enriched.live` (live path,
consumed by the Correlation Engine in P3). The same service instance, same code, and same
processing logic handle both input paths; they differ only in which input topic is
consumed and which output topic is emitted.

## Scope

**In scope:**
- Consume `alarms.history` and `alarms.live` topics.
- Normalize each consumed alarm to the canonical `AlarmEvent` payload (X.733-aligned
  fields: `alarmId`, `managedObjectId`, `eventType`, `probableCause`, `perceivedSeverity`,
  `raisedAt`, `clearedAt`, `state`, `vendorRaw`, `trailIds`) using the frozen
  `libs/event-model` Java binding.
- Deduplicate repeated identical alarms: count-collapse alarms with the same `alarmId`
  within a short sliding window so only one representative reaches downstream.
- Apply deterministic noise filters (all thresholds from Knowledge Service, not
  hard-coded):
  - **Self-clear suppression:** suppress transient alarms that clear within a configured
    hold-time window.
  - **Flap-damping:** when a managed object oscillates (raises and clears) more than N
    times within a configured window, collapse the oscillation into a single summary alarm.
  - **Maintenance suppression:** drop alarms for objects currently within an active
    maintenance window (maintenance window list from Knowledge Service).
  - **Known-chatter removal:** drop alarms whose (`managedObjectId`, `eventType`) pair
    appears on the configured known-chatter list from Knowledge Service.
- Tag each surviving alarm's `trailIds` field by calling the Trail Builder
  `getTrailsForObject(managedObjectId)` API.
- Emit normalized, deduped, filtered, trail-tagged `AlarmEvent`s on:
  - `alarms.enriched` — history path (consumed by Noise Filter in P2).
  - `alarms.enriched.live` — live path (consumed by Correlation Engine in P3).
- Route poison/undeserializable messages to the appropriate dead-letter topic.
- Consume `knowledge.updated` events and refresh filter parameters (hold-times, flap
  thresholds, chatter list, maintenance windows) from the Knowledge Service API on each
  update notification.
- Expose `/health` and `/metrics` endpoints; emit structured JSON logs; configure all
  integration URLs and thresholds via environment variables (no hard-coded values).

## Out of scope

- Statistical or ML-based noise removal (DBSCAN/HDBSCAN) — that is the Noise Filter
  Service's responsibility (P2 only, downstream of enrichment).
- Building or computing trails — the Enrichment Service only reads trails via the Trail
  Builder `getTrailsForObject` API; trail construction is solely the Trail Builder's
  responsibility.
- Correlation, scoring, root-cause analysis, or incident creation — those belong to the
  Correlation Engine.
- Pattern mining, pattern lifecycle management, or codebook reconciliation — those belong
  to the Pattern Miner and Pattern Manager.
- Topology graph ownership or querying the AGE graph directly — topology access is
  exclusively through the Topology Service API, and trail tags are obtained via the Trail
  Builder API.
- Long-term alarm persistence or an alarm store — the Enrichment Service holds only the
  small transient windowed state needed for flap detection and dedup windowing; it does not
  own a domain alarm database.
- Redundancy/protection-aware propagation (FRR, ECMP) — deferred per MVP non-goals.
- Multi-domain support beyond Core IP — the platform is extensible by design, but the MVP
  builds only the Core IP domain pack.

## Tasks (high-level)

1. Consume raw `AlarmEvent` messages from `alarms.history` and `alarms.live` and
   normalize each to the canonical `AlarmEvent` schema defined in `libs/event-model`.
2. Deduplicate incoming alarms: count-collapse repeated identical alarms (same `alarmId`)
   within a short sliding window so only one representative is forwarded downstream.
3. Apply the self-clear suppression filter: discard transients that raise and clear within
   the configured hold-time, emitting nothing downstream for those alarms.
4. Apply the flap-damping filter: when an object oscillates more than the configured N
   times within the configured window, emit a single summary alarm in place of the burst.
5. Apply the maintenance suppression filter: drop all alarms for managed objects whose
   `managedObjectId` is covered by an active maintenance window sourced from Knowledge.
6. Apply the known-chatter removal filter: drop alarms whose (`managedObjectId`,
   `eventType`) pair is on the Knowledge-sourced known-chatter list.
7. Tag each surviving alarm with its `trailIds` by calling the Trail Builder
   `getTrailsForObject(managedObjectId)` API for the alarm's `managedObjectId`.
8. Emit each surviving, trail-tagged `AlarmEvent` on the correct output topic:
   `alarms.enriched` for the history path and `alarms.enriched.live` for the live path.
9. Consume `knowledge.updated` events and refresh filter parameters (hold-times, flap
   thresholds, chatter list, maintenance windows) from the Knowledge Service API on each
   update notification.
10. Route messages that cannot be deserialized or that violate the `AlarmEvent` schema to
    the dead-letter topic (`alarms.history.dlq` for messages from `alarms.history`;
    `alarms.live.dlq` for messages from `alarms.live`).

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no alarms are flowing in this phase. | Idle | — |
| P2 — Pattern learning | Enriches the historical alarm stream: normalize, dedup, deterministic filter, and trail-tag alarms from `alarms.history`; feeds the Noise Filter downstream. | Active | In: `alarms.history` (Kafka), Trail Builder `getTrailsForObject` API, Knowledge Service filter-params API; Out: `alarms.enriched` (Kafka) |
| P3 — Real-time correlation | Enriches the live alarm stream: same normalize/dedup/filter/trail-tag processing on `alarms.live`; feeds the Correlation Engine downstream. | Active | In: `alarms.live` (Kafka), Trail Builder `getTrailsForObject` API, Knowledge Service filter-params API; Out: `alarms.enriched.live` (Kafka) |

> Enrichment is the only service Active in two runtime phases. The same service instance
> and codebase serve both paths; the processing logic is identical — only the consumed
> topic and produced topic differ between P2 and P3.

## Contract

- **Consumes (Kafka):** `alarms.history`, `alarms.live`, `knowledge.updated`
- **Produces (Kafka):** `alarms.enriched`, `alarms.enriched.live`
- **APIs exposed:** None (stream-processing service; no HTTP business API). Exposes
  `/health` (liveness/readiness) and `/metrics` (Prometheus) only. No OpenAPI business
  surface.
- **APIs/data consumed from other services:**
  - **Trail Builder `getTrailsForObject(managedObjectId)`** — returns the list of
    `trailId`s for the given managed object; used to populate `trailIds` on each surviving
    alarm. Client built against Trail Builder's published OpenAPI 3.1 spec.
  - **Knowledge Service filter-params API** — retrieves the current configured values for
    hold-time, flap N/window thresholds, known-chatter list, and maintenance windows.
    Refreshed on receipt of `knowledge.updated` events. Client built against Knowledge
    Service's published OpenAPI 3.1 spec.
- **Integration points (mock vs. real):**
  - **Trail Builder `getTrailsForObject`** — base URL and mode configured via environment
    variables (`TRAIL_BUILDER_BASE_URL`, `TRAIL_BUILDER_MODE=mock|real`). Unit tests use a
    mock/stub server generated from Trail Builder's published OpenAPI spec (e.g.,
    WireMock/MockWebServer). Integration tests point at the real Trail Builder service at
    its Docker Compose address.
  - **Knowledge Service filter-params** — base URL and mode configured via environment
    variables (`KNOWLEDGE_BASE_URL`, `KNOWLEDGE_MODE=mock|real`). Unit tests use a
    mock/stub generated from the Knowledge Service's published OpenAPI spec. Integration
    tests point at the real Knowledge Service at its Docker Compose address.
- **Data owned:** No domain datastore. The service holds only transient windowed state
  (flap-detection counters and dedup windows) in-memory or in a Kafka Streams state store
  scoped to the service. This state is ephemeral and not a domain store.

## Non-functional

- **Idempotency key:** `eventId` (envelope UUID) for general event deduplication;
  `alarmId` (AlarmEvent payload field) for alarm-specific dedup within the sliding window.
  Consumers dedupe on both keys per the platform's at-least-once Kafka guarantee.
- **Config:** All thresholds and integration URLs are supplied via environment variables or
  retrieved from the Knowledge Service — **no hard-coded values**. Required env vars
  include (at minimum): `KAFKA_BOOTSTRAP_SERVERS`, `TRAIL_BUILDER_BASE_URL`,
  `TRAIL_BUILDER_MODE`, `KNOWLEDGE_BASE_URL`, `KNOWLEDGE_MODE`. Filter parameters
  (hold-time, flap N, flap window duration, chatter list, maintenance windows) are fetched
  from the Knowledge Service API and refreshed on `knowledge.updated`.
- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus), structured
  JSON logs with `traceId` propagated from the consumed event envelope.
- **API contract:** The Enrichment Service exposes no OpenAPI business surface (it is a
  pure stream processor). Collaborating services consume its Kafka output according to the
  frozen `AlarmEvent` schema in `libs/event-model`; any change to the `AlarmEvent` payload
  is a contract change requiring `docs/architecture.md` update and human approval.
- **Error handling:** Messages that cannot be deserialized, that carry an unknown major
  `schemaVersion` (≥ 2), or that fail `AlarmEvent` schema validation are routed to
  `alarms.history.dlq` or `alarms.live.dlq` respectively. Transient errors calling the
  Trail Builder or Knowledge APIs are handled per configured retry/circuit-breaker policy
  (policy detail is a design decision); after exhausting retries the alarm is routed to the
  appropriate DLQ rather than dropped silently.

## Acceptance criteria

Each criterion maps to a single JUnit 5 unit test.

1. **Dedup collapses duplicates:** Given two `AlarmEvent` messages on `alarms.history`
   with the same `alarmId` arriving within the configured dedup window, the service emits
   exactly one `AlarmEvent` on `alarms.enriched` and not two.

2. **Flap-damping produces a single summary:** Given an alarm for a `managedObjectId`
   that raises and clears more than the configured N times within the configured flap
   window, the service emits exactly one summary `AlarmEvent` on the output topic, not the
   full oscillation sequence.

3. **Self-clear suppression removes transients:** Given an alarm that raises and clears
   within the configured hold-time, the service emits no `AlarmEvent` on the output topic
   for that alarm.

4. **Maintenance suppression removes in-window alarms:** Given a `managedObjectId`
   covered by an active maintenance window (as returned by the Knowledge Service mock), an
   alarm for that object is not emitted on the output topic.

5. **Known-chatter removal drops listed alarms:** Given an alarm whose (`managedObjectId`,
   `eventType`) pair is present on the Knowledge Service's known-chatter list (as returned
   by the Knowledge Service mock), that alarm is not emitted on the output topic.

6. **Every surviving alarm carries correct `trailIds`:** Given a surviving alarm (one that
   passes all filters), the `trailIds` field in the emitted `AlarmEvent` exactly matches
   the list returned by the Trail Builder mock for the alarm's `managedObjectId`
   (non-empty when the mock returns trails; empty array when the mock returns none).

7. **History path lands on `alarms.enriched`:** Given an alarm consumed from
   `alarms.history`, the surviving enriched alarm is emitted on `alarms.enriched` and not
   on `alarms.enriched.live`.

8. **Live path lands on `alarms.enriched.live`:** Given an alarm consumed from
   `alarms.live`, the surviving enriched alarm is emitted on `alarms.enriched.live` and
   not on `alarms.enriched`.

9. **Same service instance handles both paths:** Given that the service is configured to
   consume both `alarms.history` and `alarms.live`, alarms from both inputs are processed
   and emitted to their respective output topics within the same running service instance
   without requiring separate deployments.

10. **Output validates against the frozen `AlarmEvent` binding:** Given any alarm emitted
    on `alarms.enriched` or `alarms.enriched.live`, deserializing it with the
    `libs/event-model` Java `AlarmEvent` binding succeeds without validation errors: all
    required fields are present, `managedObjectId` matches the `<objectType>:<id>` scheme,
    and `trailIds` is a non-null array.

11. **Filter thresholds are read from Knowledge Service, not hard-coded:** Given that the
    Knowledge Service mock returns hold-time T and flap threshold N, a transient that
    clears at T+1 seconds is NOT suppressed, and an oscillation of N−1 times is NOT
    flap-damped. Changing the mock's returned values changes the filtering outcome without
    any code modification.

12. **Poison messages routed to DLQ:** Given a message on `alarms.history` that cannot be
    deserialized as a valid `AlarmEvent` (e.g., malformed JSON or unknown major
    `schemaVersion` ≥ 2), the service routes it to `alarms.history.dlq` and continues
    processing subsequent valid messages without crashing.

## Open questions

1. **Dedup window key definition:** The spec requires deduplication keyed on `alarmId`.
   It is unclear whether `alarmId` alone is sufficient, or whether the key should be
   `(managedObjectId, eventType, perceivedSeverity)` for sources that may re-emit the
   same logical alarm with a different `alarmId`. The exact dedup key definition affects
   acceptance criterion 1 and boundary conditions for the dedup window. Needs human
   resolution before design begins.
   *(GH issue: #39 — labels: `question`, `service:enrichment`.)*

2. **Flap summary alarm shape:** When flap-damping collapses N oscillations into a single
   summary alarm, the shape of the emitted `AlarmEvent` is not specified in §6.6 or the
   frozen schema. Specifically: which fields carry over from the originals, what is the
   `alarmId` of the summary (new synthetic ID or first alarm's ID), and what is the
   `state` value (`raised` or `cleared`)? A new convention or field may constitute a
   contract change to `AlarmEvent` requiring `docs/architecture.md` update and human
   approval.
   *(GH issue: #40 — labels: `question`, `service:enrichment`.)*

3. **Trail Builder unavailability behavior:** When the Trail Builder `getTrailsForObject`
   API is unavailable or returns an error for a given `managedObjectId`, the desired
   behavior is not specified: (a) emit the alarm with an empty `trailIds` array and
   continue, (b) route the alarm to the DLQ, or (c) retry and hold until resolution.
   The choice determines whether downstream consumers (Noise Filter, Correlation Engine)
   can safely receive alarms with empty `trailIds`. Needs human resolution.
   *(GH issue: #42 — labels: `question`, `service:enrichment`.)*
