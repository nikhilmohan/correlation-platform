# trail-builder — Service Spec

## Purpose

The Trail Builder Service builds correlation trails from the topology graph and the
trail policy authored in the Knowledge Service. A trail is an overlapping,
policy-bounded cluster of topologically connected objects: the transitive closure over
dependency edges from each seed object, bounded by IGP area, with all links sharing
an SRLG group joined into the same trail. Because a device may participate in multiple
LSP paths and SRLG groups, trails overlap — a single object may belong to many trails.
The service persists trail definitions (member set + the `snapshotId` they were built
from), serves trail membership queries to downstream consumers (Enrichment, Noise
Filter, Pattern Miner, web-ui), and emits `trails.built` (a summary event) on the
`trails.built` topic so dependent services can react.

## Scope

**In scope:**
- Consuming `topology.changed` (carrying `snapshotId`) to trigger trail (re)builds.
- Optionally consuming `knowledge.updated` (payload: `KnowledgeUpdatedEvent`) to
  detect when the trail-policy record in the Knowledge Service has changed (i.e.,
  `recordType == "trailPolicy"`) and re-fetch the current policy — keeping trail
  computation aligned with the latest authored parameters without waiting for the
  next `topology.changed` event.
- Fetching graph closures (neighbors, traversal by dependency-edge types) from the
  Topology Service via its published query API.
- Fetching the trail policy (IGP-area bound, SRLG-union rule, and any other
  authored policy parameters) from the Knowledge Service via its published API.
- Computing overlapping, policy-bounded trails: transitive closure over dependency
  edges bounded by IGP area; union of SRLG members into a shared trail.
- Persisting trail definitions — member `managedObjectId` list + the `snapshotId`
  they were built from — in PostgreSQL.
- Exposing `getTrailsForObject(managedObjectId)` and `getTrail(trailId)` as HTTP
  endpoints (published as OpenAPI 3.1).
- Emitting `trails.built` on every completed (re)build, carrying the frozen
  `TrailsBuiltEvent` payload (`snapshotId`, `trailIds[]`, `trailCount`). Full
  trail membership is intentionally not in the event; consumers fetch it via the API.
- Deduplicating consumed `topology.changed` events on `eventId` (at-least-once
  delivery).
- Supporting on-demand trail rebuilds (triggered via API call, not only via Kafka).
- Routing poison `topology.changed` messages to `topology.changed.dlq`.

## Out of scope

- **Graph ownership:** the service never reads Apache AGE directly; it queries the
  Topology Service via its API only. (Single-owner invariant: Topology Service is the
  sole AGE owner.)
- **Trail policy authoring:** trail policy (IGP-area bounds, SRLG rules, and any future
  policy parameters) is authored exclusively in the Knowledge Service. Trail Builder
  reads it; it does not define or store policy.
- **Alarm enrichment / trail tagging of alarms:** tagging each alarm with its
  `trailIds` is the Enrichment Service's responsibility. Trail Builder only answers
  "which trails contain this object?" queries.
- **Noise filtering, pattern mining, correlation:** all downstream ML and correlation
  work is out of scope.
- **Codebook generation:** computing propagation-forward codebook scenarios is the
  Codebook Generator Service's responsibility.
- **Pattern state / lifecycle:** owned by the Pattern Manager Service.
- **Knowledge authoring / versioning:** owned by the Knowledge Service.

## Tasks (high-level)

1. **Consume `topology.changed` and trigger a trail build.** On receipt of a
   `topology.changed` event (carrying a new `snapshotId`), check idempotency
   (dedupe on envelope `eventId`) and, if not already processed, initiate a trail
   build for the new snapshot. Support on-demand builds triggered via an API call.

2. **React to `knowledge.updated` for trail-policy changes.** On consuming a
   `knowledge.updated` event where `recordType == "trailPolicy"`, re-fetch the current
   trail policy from the Knowledge Service API so the next build uses the latest
   parameters. This event is a refresh trigger only — it does not itself trigger a
   trail rebuild.

3. **Fetch graph closures from the Topology Service.** Using the Topology Service's
   published query API (neighbors, bounded traversal by dependency-edge types), obtain
   the graph data needed to compute trail membership for the snapshot. All graph reads
   go through this API — never direct database access.

4. **Fetch trail policy from the Knowledge Service.** Retrieve the current trail
   policy (IGP-area boundary definition, SRLG-union rule, and any policy-parameterized
   bounds) from the Knowledge Service via its published API. Policy parameters must
   not be hard-coded.

5. **Compute trails.** Apply the trail policy to the fetched graph data: transitive
   closure over dependency edges from each seed object, bounded by IGP area; union
   all links sharing an SRLG group into the same trail. Trails overlap — a seed
   object appearing on multiple LSPs and/or in an SRLG group produces membership in
   multiple trails.

6. **Persist trail definitions.** Store each trail as its member set of
   `managedObjectId` values and the `snapshotId` it was built from. A rebuild for
   a new `snapshotId` supersedes prior trails for that snapshot; existing trail
   records for older snapshots are retained until explicitly superseded.

7. **Serve trail membership queries via API.** Expose `getTrailsForObject(managedObjectId)`
   (returns all trails the object belongs to) and `getTrail(trailId)` (returns the
   trail's member list and its `snapshotId`). Publish the API as OpenAPI 3.1 at
   `/openapi.json`; check the generated `openapi.json` into `services/trail-builder/`.

8. **Emit `trails.built`.** After a successful build, produce a `trails.built` event
   with the frozen `TrailsBuiltEvent` payload (`snapshotId`, `trailIds[]`,
   `trailCount`). The `trailCount` must equal the length of `trailIds`. Full trail
   membership is available only via the query API, not in the event payload.

## Phase applicability

Trail-builder's primary work happens in P1. In P2 and P3 it is a passive dependency:
it serves the trail-query API to any consumer that needs to scope alarms by trail, but
it drives no work of its own in those phases. A `knowledge.updated` trail-policy change
or a new `topology.changed` event may trigger a P1-style Active rebuild at any time
(see Task 1 and Task 2 above); such a rebuild is classified as P1 work — not P2 or P3.

| Phase | Role | Active/Passive/Idle | Inputs/Outputs in this phase |
|---|---|---|---|
| P1 — Topology onboarding | Builds policy-bounded correlation trails from the topology graph and Knowledge trail policy; persists trail definitions; notifies downstream services | Active | In: `topology.changed` (+ Topology Service graph-closure API, Knowledge Service trail-policy API). Out: `trails.built` |
| P2 — Pattern learning | Serves trail membership queries to consumers that scope historical alarms and transactions by trail (Enrichment, Noise Filter, Pattern Miner) | Passive | In: —. Out: serves `getTrailsForObject` / `getTrail` API (no topic output of its own) |
| P3 — Real-time correlation | Serves trail membership queries to real-time consumers (e.g. Enrichment live-path trail-tagging) | Passive | In: —. Out: serves `getTrailsForObject` / `getTrail` API (no topic output of its own) |

## Contract

- **Consumes (Kafka):**
  - `topology.changed` (payload: `TopologyChangedEvent` — fields `snapshotId`,
    `changeType`, `nodes`, `edges`; envelope `eventId` is the idempotency key).
    Python/Pydantic binding from `acp_event_model`. Primary trigger for trail builds.
  - `knowledge.updated` (payload: `KnowledgeUpdatedEvent` — fields `recordType`
    (string), `recordId` (string, optional), `version` (string), `domain` (string);
    Python/Pydantic binding from `acp_event_model`). Used as a refresh trigger: when
    `recordType == "trailPolicy"`, the service re-fetches the current trail policy from
    the Knowledge Service API. No trail rebuild is triggered by this event alone —
    policy is refreshed in-cache; the rebuild fires on the next `topology.changed`.

- **Produces (Kafka):** `trails.built` (payload: `TrailsBuiltEvent` — fields
  `snapshotId` (string), `trailIds` (array of strings), `trailCount` (integer, must
  equal `len(trailIds)`); Python/Pydantic binding from `acp_event_model`). Full trail
  membership is intentionally out of the event; downstream consumers fetch it via the
  query API.

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; generated
  `openapi.json` checked into `services/trail-builder/`; a surface change is a
  contract change):
  - `GET /trails?managedObjectId={managedObjectId}` — returns all trail identifiers
    (and optionally trail summaries) for the given object. Corresponds to
    `getTrailsForObject(managedObjectId)`.
  - `GET /trails/{trailId}` — returns the trail's member `managedObjectId` list and
    `snapshotId`. Corresponds to `getTrail(trailId)`.
  - `POST /trails/rebuild` — triggers an on-demand trail rebuild (accepts optional
    `snapshotId`; defaults to current snapshot). Returns the resulting `TrailsBuiltEvent`
    summary.
  - `GET /health` — liveness/readiness probe.
  - `GET /metrics` — Prometheus metrics.
  - `GET /openapi.json` — machine-readable API contract.

- **APIs consumed from other services** (each is a named config-switchable integration
  point; built against the producer's published OpenAPI spec — never against producer
  source code):
  - **Topology Service query API** — graph closure and traversal endpoints (get
    neighbors, traverse by edge type, resolve `managedObjectId`). Used to fetch graph
    data for trail computation.
  - **Knowledge Service trail-policy API** — reads the current trail policy (IGP-area
    bound, SRLG-union rule, and any authored policy parameters). Used at build time and
    on `knowledge.updated` refresh.

- **Integration points (mock vs. real):**
  - **Topology Service** — config key: `TOPOLOGY_SERVICE_BASE_URL`; toggle:
    `TOPOLOGY_SERVICE_MODE` (`mock` | `real`). In unit tests, backed by a mock/stub
    generated from the Topology Service's published OpenAPI spec (e.g. via `respx` or
    a Prism server). In integration, pointed at the live Topology Service.
  - **Knowledge Service** — config key: `KNOWLEDGE_SERVICE_BASE_URL`; toggle:
    `KNOWLEDGE_SERVICE_MODE` (`mock` | `real`). In unit tests, backed by a mock/stub
    generated from the Knowledge Service's published OpenAPI spec. In integration,
    pointed at the live Knowledge Service.
  - No integration point URL or mode may be hard-coded; all resolved from environment
    or configuration at startup.

- **Data owned:** PostgreSQL — trail definitions schema. Owns: trail records (trail
  identifier, member `managedObjectId` list, `snapshotId`). Does not share its schema
  with any other service.

## Non-functional

- **Idempotency key:** envelope `eventId` (UUID) for `topology.changed` deduplication.
  Rebuilding for a new `snapshotId` supersedes existing trails for that snapshot.
  Multiple deliveries of the same `topology.changed` event must not produce duplicate
  `trails.built` events or duplicate trail records.

- **Config:** all integration base-URLs, mock/real toggles, database connection
  strings, Kafka broker addresses, and any tuneable parameters come from environment
  variables or the Knowledge Service. No thresholds, URLs, or policy values may be
  hard-coded in source. Trail policy bounds (IGP-area definition, SRLG rules) are read
  from the Knowledge Service at build time; they are not duplicated in this service's
  config.

- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus), structured
  JSON logs on all code paths (including error paths). Log entries must include
  `traceId` (from the consumed event envelope or request header) and `snapshotId`
  where applicable.

- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; the generated
  `openapi.json` is checked into `services/trail-builder/` and is the single source of
  truth for the HTTP surface. The service's own contract/unit tests validate
  request/response shapes against this spec. A surface change is a contract change
  requiring `docs/architecture.md` update and human approval.

- **Error handling:** poison `topology.changed` messages (deserialization failures,
  schema-version mismatches, or processing errors that cannot be retried) are routed
  to `topology.changed.dlq`. Errors from Topology/Knowledge integration point calls
  are logged and surfaced via `/health` or `/metrics`; they do not silently drop a
  build.

- **Snapshot alignment:** every persisted trail and every `trails.built` event carries
  the `snapshotId` it was built from. This invariant enables downstream services
  (Codebook Generator, Enrichment) to detect and reject stale trail data.

## Acceptance criteria

Each criterion maps to a single `pytest` test.

1. **Multi-trail overlap.** Given a synthetic topology where object `X` participates
   in two LSP paths and one SRLG group, `getTrailsForObject(X)` returns at least three
   distinct trail identifiers. (Overlap is real, not a degenerate single-trail result.)

2. **Policy-bounded trails.** Given a synthetic topology spanning multiple IGP areas,
   no single trail contains objects from more than one IGP area. The closure is bounded
   — there is no unbounded whole-network trail. (Verifies the IGP-area bound from the
   trail policy.)

3. **SRLG union.** Given a synthetic topology where two IP links share an SRLG group,
   both links appear in the same trail. (Verifies the SRLG-union rule from §5.)

4. **`getTrailsForObject` completeness.** For each object in the synthetic topology,
   `getTrailsForObject(managedObjectId)` returns exactly the set of trail identifiers
   to which that object belongs — no more, no fewer. Cross-checked against the
   persisted trail records.

5. **`getTrail` correctness.** `getTrail(trailId)` returns the full member
   `managedObjectId` list for the trail and the `snapshotId` it was built from, and
   the returned `snapshotId` matches the one used to trigger the build.

6. **`topology.changed` triggers a build and emits `trails.built`.** On consuming a
   `topology.changed` event with a new `snapshotId`, the service (re)builds trails
   against that snapshot and emits a `trails.built` event whose payload deserializes
   without error against the `TrailsBuiltEvent` Pydantic model from `acp_event_model`,
   with `trailCount == len(trailIds)` and `snapshotId` matching the triggering event.

7. **Idempotency.** Delivering the same `topology.changed` event (same `eventId`)
   twice produces exactly one `trails.built` emission and does not create duplicate
   trail records.

8. **`knowledge.updated` refresh trigger.** On consuming a `knowledge.updated` event
   with `recordType == "trailPolicy"`, the service re-fetches the trail policy from
   the Knowledge Service API. No `trails.built` event is emitted as a result. A
   subsequent `topology.changed` event uses the newly fetched policy.

9. **Config-switchable integration points.** With `TOPOLOGY_SERVICE_MODE=mock` and
   `KNOWLEDGE_SERVICE_MODE=mock`, all unit tests run to completion with no live service
   dependency. The mock is backed by stubs generated from the respective published
   OpenAPI specs. With `*_MODE=real`, the service routes calls to the configured
   base-URL. The same code path is exercised in both modes.

10. **No hard-coded URLs or policy values.** Changing `TOPOLOGY_SERVICE_BASE_URL` or
    `KNOWLEDGE_SERVICE_BASE_URL` in the environment causes the service to contact the
    new address without a code change.

11. **Poison message handling.** A `topology.changed` message that cannot be
    deserialized (malformed JSON or unknown major `schemaVersion`) is routed to
    `topology.changed.dlq` without crashing the consumer or dropping subsequent
    messages.

12. **`snapshotId` alignment.** Every trail record returned by `getTrail(trailId)`
    carries the `snapshotId` from the build that created it; a subsequent build for a
    different `snapshotId` creates new trail records tagged with the new `snapshotId`,
    leaving prior snapshot records intact.

13. **OpenAPI contract compliance.** Requests and responses for `GET /trails`,
    `GET /trails/{trailId}`, and `POST /trails/rebuild` validate against the checked-in
    `openapi.json` schema (request inputs and response bodies).

## Open questions

The four items below were previously labeled as spec blockers. They are **design-stage**
items — not contract gaps — and are resolved when the relevant collaborating service
publishes its OpenAPI spec or when policy is finalized in `design.md`. None block the
spec being merged; they are tracked for resolution at the design stage.

1. **[DESIGN-STAGE] Topology Service query API surface for trail computation.**
   The Topology Service query API must expose bounded-traversal and neighbor endpoints
   sufficient for computing transitive closures. The exact endpoint names, path
   parameters, and response shapes will be defined in the Topology Service's published
   `openapi.json`. Trail Builder builds its Topology integration-point mock against that
   published spec once it is checked in — standard contract-first flow. Not a spec
   blocker.
   _(Tracked: [#24](https://github.com/nikhilmohan/correlation-platform/issues/24))_

2. **[DESIGN-STAGE] Knowledge Service trail-policy API shape.**
   The trail policy fields (IGP-area boundary definition, SRLG-union rule, and authored
   parameters) that Trail Builder reads from the Knowledge Service will be defined in
   the Knowledge Service's published `openapi.json`. Trail Builder builds its Knowledge
   integration-point mock against that published spec once it is checked in — standard
   contract-first flow. Not a spec blocker.
   _(Tracked: [#25](https://github.com/nikhilmohan/correlation-platform/issues/25))_

3. **[DESIGN-STAGE] On-demand rebuild API authentication / authorization.**
   `POST /trails/rebuild` initiates a potentially expensive operation. Whether the
   endpoint requires authentication or is restricted to internal callers is a policy
   decision finalized in `design.md`. Not a spec blocker.
   _(Tracked: [#27](https://github.com/nikhilmohan/correlation-platform/issues/27))_

4. **[DESIGN-STAGE] Trail record retention policy for older snapshots.**
   Every trail record carries the `snapshotId` it was built from. The default intent
   is to retain trails for the current snapshot and the immediately preceding snapshot,
   with older records pruned — mirroring the topology snapshot retention approach. The
   exact retention rule (keep N snapshots, TTL, or explicit deletion trigger) is
   finalized in `design.md` and reflected in the schema design there. Not a spec
   blocker.
   _(Tracked: [#29](https://github.com/nikhilmohan/correlation-platform/issues/29))_
