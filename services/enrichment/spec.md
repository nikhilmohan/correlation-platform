# enrichment — Service Spec

## Purpose

The Enrichment Service is the first-stage alarm processing pipeline for the platform,
active in **both Phase 2 (pattern learning) and Phase 3 (real-time correlation)** — the
only service that participates as Active in two runtime phases. It is a
**source-aware, configuration-driven normalizer**: different NMS/vendor feeds (sources)
can inject raw alarms in different formats and with different field conventions; the
Enrichment Service applies a **per-source ruleset** to adapt each source, and always
emits the single canonical `AlarmEvent` (X.733-aligned, defined in `libs/event-model`)
as its output — regardless of source. It consumes raw alarm streams from `alarms.history`
(batch/learning) and `alarms.live` (streaming/real-time), normalizes each alarm through
the per-source ruleset, deduplicates repeated identical alarms within a short window,
applies a suite of deterministic noise filters (flap-damping, self-clear suppression,
known-chatter removal), and tags every surviving alarm with its `trailIds` via the Trail
Builder `getTrailsForObject` API. It emits enriched, trail-tagged alarms onto
`alarms.enriched` (history path, consumed by the Noise Filter in P2) and
`alarms.enriched.live` (live path, consumed by the Correlation Engine in P3). The same
service instance, same code, and same processing logic handle both input paths; they
differ only in which input topic is consumed and which output topic is emitted.

## Scope

**In scope:**
- Consume `alarms.history` and `alarms.live` topics.
- Select the appropriate **per-source ruleset** for each incoming alarm, matching on the
  alarm's source identity. When no source-specific ruleset matches, apply the built-in
  **default ruleset**. The exact mechanism for identifying an alarm's source and selecting
  its ruleset (e.g. envelope `source` field vs. a match-rule predicate) is a
  design-stage decision — see Open questions.
- Apply the **field-mapping** portion of the matched per-source ruleset: translate the
  source's raw alarm fields (severity codes, eventType strings, **raw alarm-type
  identifiers**, managedObjectId construction, and other field/value conventions) into the
  canonical `AlarmEvent` payload (X.733-aligned fields plus the canonical join key:
  `alarmId`, `managedObjectId`, `eventType`, `probableCause`, **`alarmType`**,
  `perceivedSeverity`, `raisedAt`, `clearedAt`, `state`, `vendorRaw`, `trailIds`) using the
  frozen `libs/event-model` Java binding.
- **Populate the REQUIRED canonical `alarmType` field on every emitted `AlarmEvent`.** Each
  per-source ruleset carries an **`alarmTypeMap`** mapping that source's raw alarm-type
  identifier to a canonical token from the domain's Knowledge **`alarmTypeVocabulary`**
  (`FiberFault`, `LOS`, `PortDown`, `InterfaceDown`, `LinkDown`, `AdjDown`, `LSPDown`,
  `ReachabilityLoss`). Enrichment is the source-adaptation boundary, so it MAPS each
  source's raw alarm-type to this canonical token. `alarmType` is the **single canonical
  join key** used downstream (pattern mining, codebook signatures, `rootCauseAlarmType`,
  correlation matching); it is **distinct from** `eventType` (the X.733 category) and
  `probableCause` (the X.733 probable cause). An unmapped raw alarm-type is handled per the
  per-source `alarmTypeMap` policy (a configured fallback vocabulary token, or routed to the
  DLQ) — but `alarmType` MUST always be a valid vocabulary token on every emitted alarm. The
  `alarmType` value space (`alarmTypeVocabulary`) is authored in the Knowledge Service; the
  per-source raw-to-canonical mapping is Enrichment's own configuration. The `alarmType`
  field already exists on the frozen `AlarmEvent` payload — **no contract change is
  required**.
- Apply the **filter parameters** portion of the matched per-source ruleset: the dedup
  window duration, self-clear hold-time, flap N/window, and known-chatter list are all
  per-source values drawn from that source's ruleset configuration. Different sources may
  have different thresholds for the same logical filter stage.
- Deduplicate repeated identical alarms: count-collapse alarms with the same composite
  key **`(managedObjectId, eventType)`** within the per-source dedup window so only one
  representative reaches downstream.
- Apply deterministic noise filters, all driven by per-source ruleset parameters (not
  hard-coded, not sourced from the Knowledge Service):
  - **Self-clear suppression:** suppress transient alarms that clear within the
    per-source hold-time window.
  - **Flap-damping:** when a managed object oscillates (raises and clears) more than the
    per-source N times within the per-source window, collapse the oscillation into a
    single summary alarm (using existing `AlarmEvent` fields; exact field mapping is a
    design-stage decision — see Open questions).
  - **Known-chatter removal:** drop alarms whose (`managedObjectId`, `eventType`) pair
    appears on the per-source known-chatter list (part of the per-source ruleset
    configuration owned by Enrichment).
- Tag each surviving alarm's `trailIds` field by calling the Trail Builder
  `getTrailsForObject(managedObjectId)` API.
- Emit normalized, deduped, filtered, trail-tagged `AlarmEvent`s on:
  - `alarms.enriched` — history path (consumed by Noise Filter in P2).
  - `alarms.enriched.live` — live path (consumed by Correlation Engine in P3).
- Route poison/undeserializable messages to the appropriate dead-letter topic.
- Expose `/health` and `/metrics` endpoints; emit structured JSON logs; configure all
  integration URLs, per-source rulesets, and any remaining thresholds via environment
  variables or mounted configuration (no hard-coded values).

**Configuration ownership invariant:** Per-source rulesets (field mappings + filter
parameters) are **Enrichment's own technical configuration** — a pipeline adaptability
concern owned entirely by Enrichment, not authored domain knowledge. They do NOT live in
or come from the Knowledge Service. The Knowledge Service remains the home of authored
domain policy (templates, fault-origin lists, object-type vocabularies, model params); it
is not consulted for per-source pipeline configuration. The `knowledge.updated` topic is
no longer consumed by Enrichment for threshold refresh; all filter parameters are
per-source and live in Enrichment's configuration.

**Canonical output invariant:** Regardless of source, alarm format, or ruleset applied,
the output of every successful processing pass is exactly one canonical `AlarmEvent`
conforming to the frozen `libs/event-model` binding. Source-specific handling is fully
absorbed inside Enrichment. All downstream services (Noise Filter, Correlation Engine,
Alarm Manager) consume only this canonical representation; they have no visibility into
the source or the ruleset that was applied.

**Processing stages are fixed:** The pipeline stages and their execution order —
(1) source-ruleset selection, (2) field-mapping/normalization, (3) dedup,
(4) self-clear suppression, (5) flap-damping, (6) known-chatter removal,
(7) trail-tagging — are fixed in code. Per-source configuration adjusts field mappings
and filter parameters within each stage; it does NOT add, remove, reorder, or substitute
stages. There are no pluggable or custom stages.

## Out of scope

- **Maintenance suppression (deferred to post-MVP).** Suppressing alarms for objects under
  an active maintenance window is **not** in the MVP. A maintenance window is transient
  *operational* state (sourced from a CMDB / change-management / NMS feed) — **not** authored
  domain knowledge — so it does not belong in the Knowledge Service. The MVP also has no
  oracle for it: the Simulator generates synthetic alarms and does not model real maintenance
  windows. When added post-MVP it will integrate a dedicated, config-switchable
  **maintenance/CMDB feed** as its own integration point (not Knowledge), and gain its own
  acceptance criterion then.
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
- Authoring, versioning, or serving per-source rulesets to other services — rulesets are
  Enrichment's own internal configuration; no other service reads them.

## Tasks (high-level)

1. For each incoming alarm from `alarms.history` or `alarms.live`, select the matching
   per-source ruleset (or the default ruleset when no source-specific match is found).
2. Apply the field-mapping portion of the matched ruleset to translate the source's raw
   alarm fields into the canonical `AlarmEvent` schema defined in `libs/event-model`. This
   includes populating the REQUIRED canonical **`alarmType`** field: map the source's raw
   alarm-type identifier to a canonical `alarmTypeVocabulary` token via the per-source
   **`alarmTypeMap`**. Every emitted `AlarmEvent` carries a valid `alarmType` token (an
   unmapped raw value uses the configured fallback token or routes to the DLQ per policy);
   `alarmType` is the canonical join key, distinct from `eventType` and `probableCause`.
3. Deduplicate incoming alarms: count-collapse repeated identical alarms sharing the same
   composite key **`(managedObjectId, eventType)`** within the per-source dedup window so
   only one representative is forwarded downstream.
4. Apply the self-clear suppression filter using the per-source hold-time: discard
   transients that raise and clear within that hold-time, emitting nothing downstream for
   those alarms.
5. Apply the flap-damping filter using the per-source N/window parameters: when an object
   oscillates more than the per-source N times within the per-source window, emit a single
   summary `AlarmEvent` (using existing `AlarmEvent` fields) in place of the burst. The
   precise field mapping is design-stage (see Open questions); if a new `AlarmEvent` field
   is genuinely required it becomes a contract-change PR before design proceeds.
6. Apply the known-chatter removal filter using the per-source known-chatter list: drop
   alarms whose (`managedObjectId`, `eventType`) pair appears on that list.
7. Tag each surviving alarm with its `trailIds` by calling the Trail Builder
   `getTrailsForObject` API for the alarm's `managedObjectId` and `domain`, using the frozen
   contract `GET /trails/by-object?managedObjectId={moId}&domain={domain}` →
   `{ managedObjectId, domain, trailIds: [] }`; set `AlarmEvent.trailIds` from the response.
8. Emit each surviving, trail-tagged `AlarmEvent` on the correct output topic:
   `alarms.enriched` for the history path and `alarms.enriched.live` for the live path.
9. Route messages that cannot be deserialized or that violate the `AlarmEvent` schema to
   the dead-letter topic (`alarms.history.dlq` for messages from `alarms.history`;
   `alarms.live.dlq` for messages from `alarms.live`).

## Phase applicability

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Not involved; no alarms are flowing in this phase. | Idle | — |
| P2 — Pattern learning | Enriches the historical alarm stream: select per-source ruleset, normalize, dedup, deterministic filter, and trail-tag alarms from `alarms.history`; feeds the Noise Filter downstream. | Active | In: `alarms.history` (Kafka), Trail Builder `getTrailsForObject` API; Out: `alarms.enriched` (Kafka) |
| P3 — Real-time correlation | Enriches the live alarm stream: same per-source-ruleset selection, normalize/dedup/filter/trail-tag processing on `alarms.live`; feeds the Correlation Engine downstream. | Active | In: `alarms.live` (Kafka), Trail Builder `getTrailsForObject` API; Out: `alarms.enriched.live` (Kafka) |

> Enrichment is the only service Active in two runtime phases. The same service instance
> and codebase serve both paths; the processing logic is identical — only the consumed
> topic and produced topic differ between P2 and P3.

## Contract

- **Consumes (Kafka):** `alarms.history`, `alarms.live`
- **Produces (Kafka):** `alarms.enriched`, `alarms.enriched.live` — each a canonical
  `AlarmEvent` with the REQUIRED `alarmType` populated from the source's `alarmTypeMap`.
- **APIs exposed:** None (stream-processing service; no HTTP business API). Exposes
  `/health` (liveness/readiness) and `/metrics` (Prometheus) only. No OpenAPI business
  surface.
- **APIs/data consumed from other services:**
  - **Trail Builder `getTrailsForObject(managedObjectId, domain)`** — frozen contract
    `GET /trails/by-object?managedObjectId={moId}&domain={domain}` returning
    `{ managedObjectId, domain, trailIds: string[] }`; used to populate `trailIds` on each
    surviving alarm (set from the response `trailIds[]`). Client built against Trail
    Builder's published OpenAPI 3.1 spec; Enrichment passes the alarm's `domain`.
- **Integration points (mock vs. real):**
  - **Trail Builder `getTrailsForObject`** — base URL and mode configured via environment
    variables (`TRAIL_BUILDER_BASE_URL`, `TRAIL_BUILDER_MODE=mock|real`). Unit tests use a
    mock/stub server generated from Trail Builder's published OpenAPI spec (e.g.,
    WireMock/MockWebServer). Integration tests point at the real Trail Builder service at
    its Docker Compose address.
- **Data owned:** No domain datastore. The service holds only transient windowed state
  (flap-detection counters and dedup windows) in-memory or in a Kafka Streams state store
  scoped to the service. This state is ephemeral and not a domain store. Per-source
  rulesets are owned configuration — loaded at startup from Enrichment's own configuration
  (not from any other service's datastore). Each ruleset's field mapping includes an
  **`alarmTypeMap`** (raw alarm-type to canonical `alarmTypeVocabulary` token) that drives the
  required `AlarmEvent.alarmType`; the `alarmTypeVocabulary` value space is authored in the
  Knowledge Service, the per-source mapping is Enrichment's own configuration.

## Non-functional

- **Idempotency key:** `eventId` (envelope UUID) for general event deduplication;
  composite `(managedObjectId, eventType)` for alarm-specific dedup within the sliding
  window. Consumers dedupe on both keys per the platform's at-least-once Kafka guarantee.
- **Config:** All per-source rulesets (field mappings, filter parameters, known-chatter
  lists), integration URLs, and any remaining thresholds are supplied via environment
  variables or mounted configuration files — **no hard-coded values**. Required env vars
  include (at minimum): `KAFKA_BOOTSTRAP_SERVERS`, `TRAIL_BUILDER_BASE_URL`,
  `TRAIL_BUILDER_MODE`. Per-source ruleset configuration is Enrichment's own and does NOT
  come from the Knowledge Service.
- **Observability:** `/health` (liveness + readiness), `/metrics` (Prometheus), structured
  JSON logs with `traceId` propagated from the consumed event envelope.
- **API contract:** The Enrichment Service exposes no OpenAPI business surface (it is a
  pure stream processor). Collaborating services consume its Kafka output according to the
  frozen `AlarmEvent` schema in `libs/event-model`; any change to the `AlarmEvent` payload
  is a contract change requiring `docs/architecture.md` update and human approval.
- **Error handling:** Messages that cannot be deserialized, that carry an unknown major
  `schemaVersion` (≥ 2), or that fail `AlarmEvent` schema validation are routed to
  `alarms.history.dlq` or `alarms.live.dlq` respectively. Transient errors calling the
  Trail Builder API are handled per configured retry/circuit-breaker policy (policy detail
  is a design decision); after exhausting retries the alarm is routed to the appropriate
  DLQ rather than dropped silently.

## Acceptance criteria

Each criterion maps to a single JUnit 5 unit test.

1. **Dedup collapses duplicates on composite key:** Given two `AlarmEvent` messages on
   `alarms.history` with the same `(managedObjectId, eventType)` arriving within the
   configured dedup window, the service emits exactly one `AlarmEvent` on `alarms.enriched`
   and not two.

2. **Dedup does not collapse distinct composite keys:** Given two `AlarmEvent` messages
   sharing the same `managedObjectId` but with different `eventType` values arriving within
   the configured dedup window, the service emits both as separate `AlarmEvent`s on the
   output topic.

3. **Flap-damping produces a single summary:** Given an alarm for a `managedObjectId`
   that raises and clears more than the configured N times within the configured flap
   window, the service emits exactly one summary `AlarmEvent` on the output topic, not the
   full oscillation sequence.

4. **Self-clear suppression removes transients:** Given an alarm that raises and clears
   within the configured hold-time, the service emits no `AlarmEvent` on the output topic
   for that alarm.

5. **Known-chatter removal drops listed alarms:** Given an alarm whose (`managedObjectId`,
   `eventType`) pair is present on the per-source known-chatter list in the active
   per-source ruleset configuration, that alarm is not emitted on the output topic.

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
    required fields are present (including a non-null `alarmType`), `managedObjectId` matches
    the `<objectType>:<id>` scheme, and `trailIds` is a non-null array.

11. **Per-source filter parameters govern filtering for that source:** Given two alarms
    representing the same logical transient alarm, one from source A (configured with a
    short hold-time that would suppress it) and one from source B (configured with a long
    hold-time that would not suppress it), source A's alarm is suppressed and source B's
    alarm is emitted — demonstrating that per-source filter parameters are applied
    independently per source.

12. **Each source is normalized by its own field mapping:** Given two alarms from different
    sources (source A maps severity code `CRIT` to `perceivedSeverity=CRITICAL`; source B
    maps severity code `P1` to `perceivedSeverity=CRITICAL`), both alarms are emitted as
    canonical `AlarmEvent`s with `perceivedSeverity=CRITICAL`, and no source-specific field
    values appear in the output.

13. **Unmatched source falls back to the default ruleset:** Given an alarm whose source
    identifier matches no configured per-source ruleset, the service applies the built-in
    default ruleset and emits a valid canonical `AlarmEvent` rather than routing the alarm
    to the DLQ or dropping it silently.

14. **Canonical-output invariant holds across sources:** Given alarms arriving from at least
    two distinct sources, each processed through its own per-source ruleset, every emitted
    `AlarmEvent` on `alarms.enriched` or `alarms.enriched.live` deserializes successfully
    against the frozen `libs/event-model` `AlarmEvent` binding with all required fields
    present — confirming that source-specific handling is fully absorbed inside Enrichment
    and downstream services receive only the canonical representation.

15. **Poison messages routed to DLQ:** Given a message on `alarms.history` that cannot be
    deserialized as a valid `AlarmEvent` (e.g., malformed JSON or unknown major
    `schemaVersion` ≥ 2), the service routes it to `alarms.history.dlq` and continues
    processing subsequent valid messages without crashing.

16. **Every emitted `AlarmEvent` carries a valid canonical `alarmType`:** Given any surviving
    alarm, the emitted `AlarmEvent.alarmType` is a non-null token drawn from the domain's
    Knowledge `alarmTypeVocabulary`, and its value is the token the resolved source's
    `alarmTypeMap` maps the raw alarm-type to (e.g. an alarm from source A whose raw
    alarm-type is `LINK_DOWN` is emitted with `alarmType=LinkDown`; an alarm from source B
    whose raw alarm-type is `port-fault` is emitted with `alarmType=PortDown`). A raw
    alarm-type with no `alarmTypeMap` entry is emitted with the configured fallback
    vocabulary token (or routed to the DLQ per the configured policy) — never emitted without
    a valid `alarmType`.

17. **Trail-tagging calls the frozen Trail Builder by-object contract with `domain`:** Given a
    surviving alarm, the service tags `trailIds` by calling
    `GET /trails/by-object?managedObjectId={moId}&domain={domain}` (both query params present,
    `managedObjectId` = the alarm's managed object, `domain` = the configured domain) and sets
    `AlarmEvent.trailIds` from the frozen `{ managedObjectId, domain, trailIds: [] }` response.

## Open questions

> Design-stage questions do not block the spec; they must be resolved before design is
> complete. The new source-identification question below is the only spec-stage open
> question introduced by this revision.

1. **[DESIGN-STAGE] Flap summary alarm field mapping (#40):** When flap-damping collapses
   N oscillations into a single summary `AlarmEvent`, the precise mapping of fields (which
   fields carry over from the originals, how the `alarmId` of the summary is derived, and
   what `state` value it carries) is finalized at design using existing `AlarmEvent` fields
   only. If a genuinely new `AlarmEvent` field is needed it becomes a contract-change PR
   before design proceeds — it is not decided in the spec.
   *(GH issue: #40 — labels: `design-stage`, `service:enrichment`.)*

2. **[DESIGN-STAGE] Trail Builder unavailability resilience policy (#42):** When the Trail
   Builder `getTrailsForObject` API is unavailable or returns an error, the resilience
   policy (retry-and-hold, degrade with empty `trailIds`, or route to DLQ) is a
   design-stage decision made with full resilience context. Downstream consumers (Noise
   Filter, Correlation Engine) must be considered when the designer selects the policy.
   *(GH issue: #42 — labels: `design-stage`, `service:enrichment`.)*

3. **[DESIGN-STAGE] Per-source ruleset source-identification mechanism:** The spec requires
   that each alarm is matched to a per-source ruleset, with unmatched alarms falling back to
   the default ruleset. The exact mechanism for identifying the alarm's source — for example,
   reading a dedicated `source` field from the event envelope vs. evaluating a match-rule
   predicate over alarm fields vs. a combination — is a design-stage decision. The designer
   must ensure the chosen mechanism: (a) selects exactly one ruleset per alarm (including the
   default when no specific ruleset matches); (b) does not require a new `AlarmEvent` or
   envelope field (if a new field is genuinely needed it becomes a contract-change PR); and
   (c) is covered by acceptance criterion 13 (default-fallback). The current event envelope
   already carries a `source` field — whether and how this suffices is for the designer to
   determine.
   *(Open GH issue to be filed — labels: `design-stage`, `service:enrichment`.)*
