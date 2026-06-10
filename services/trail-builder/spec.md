# trail-builder — Service Spec

## Purpose

The Trail Builder Service builds correlation trails from the topology graph and the
trail policy authored in the Knowledge Service. A trail is an overlapping,
policy-bounded cluster of topologically connected objects: the transitive closure over
dependency edges from each seed object, bounded by IGP area, with all links sharing
an SRLG group joined into the same trail. Because a device may participate in multiple
LSP paths and SRLG groups, trails overlap — a single object may belong to many trails.

**Domain scoping.** Trails are domain-scoped by design. Each topology snapshot carries
a `domain` identifier; the trail policy used to compute trails is fetched from the
Knowledge Service for that specific domain (trail policy is domain-scoped data, authored
per-domain in the Knowledge Service). Persisted trail records carry `domain` alongside
`snapshotId`. All query APIs operate within a domain by default. For the MVP the platform
builds and operates on the Core IP single domain; the design does not preclude future
cross-domain trail assembly (via explicit cross-domain edges authored in Knowledge), but
that is out of MVP scope.

The service persists trail definitions (member set + `snapshotId` + `domain`), serves
trail membership queries to downstream consumers (Enrichment, Noise Filter, Pattern
Miner, and the web-ui topology/trails module for visualization), and emits `trails.built`
(a summary event) on the `trails.built` topic so dependent services can react.

## Scope

**In scope:**
- Consuming `topology.changed` (carrying `snapshotId` and `domain`) to trigger trail
  (re)builds.
- Optionally consuming `knowledge.updated` (payload: `KnowledgeUpdatedEvent`) to
  detect when the trail-policy record in the Knowledge Service has changed (i.e.,
  `recordType == "trailPolicy"`) and re-fetch the current policy for the relevant
  domain — keeping trail computation aligned with the latest authored parameters
  without waiting for the next `topology.changed` event.
- Fetching graph closures (neighbors, traversal by dependency-edge types) from the
  Topology Service via its published query API, scoped to the snapshot's domain.
- Fetching the trail policy for the snapshot's `domain` from the Knowledge Service
  via its published API (domain-parameterized call). The trail policy (IGP-area bound,
  SRLG-union rule, and any other authored policy parameters) is domain-scoped data;
  a different domain may have a different policy without any code change.
- Computing overlapping, policy-bounded trails per domain: transitive closure over
  dependency edges bounded by IGP area; union of SRLG members into a shared trail.
  Trail computation is performed using the trail policy and graph slice for the
  snapshot's domain.
- Persisting trail definitions — member `managedObjectId` list + `snapshotId` +
  `domain` — in PostgreSQL.
- Exposing `getTrailsForObject(managedObjectId, domain)`, `getTrail(trailId)`, and
  `listTrails(snapshotId, domain)` as HTTP endpoints (published as OpenAPI 3.1) to
  Enrichment, Noise Filter, Pattern Miner, and the web-ui topology/trails module
  (trail visualization). Trail queries are domain-scoped by default (a trail belongs
  to exactly one domain for MVP; queries operate within a domain).
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
  policy parameters) is authored exclusively in the Knowledge Service, domain-scoped.
  Trail Builder reads it; it does not define, store, or author policy.
- **Alarm enrichment / trail tagging of alarms:** tagging each alarm with its
  `trailIds` is the Enrichment Service's responsibility. Trail Builder only answers
  "which trails contain this object?" queries.
- **Noise filtering, pattern mining, correlation:** all downstream ML and correlation
  work is out of scope.
- **Codebook generation:** computing propagation-forward codebook scenarios is the
  Codebook Generator Service's responsibility.
- **Pattern state / lifecycle:** owned by the Pattern Manager Service.
- **Knowledge authoring / versioning:** owned by the Knowledge Service.
- **Cross-domain trail assembly (MVP).** A cross-domain trail spans objects from more
  than one domain via explicit cross-domain edges (authored in Knowledge). The trail
  record structure carries `domain` and the architecture does not preclude cross-domain
  trails, but assembling cross-domain trails is explicitly **out of MVP scope**. MVP
  builds Core IP single-domain trails only.

## Tasks (high-level)

1. **Consume `topology.changed` and trigger a domain-scoped trail build.** On receipt
   of a `topology.changed` event (carrying a new `snapshotId` and `domain`), check
   idempotency (dedupe on envelope `eventId`) and, if not already processed, initiate
   a trail build for the new snapshot within its domain. Support on-demand builds
   triggered via an API call (accepting `snapshotId` and `domain` as inputs).

2. **React to `knowledge.updated` for trail-policy changes.** On consuming a
   `knowledge.updated` event where `recordType == "trailPolicy"`, re-fetch the current
   trail policy from the Knowledge Service API for the relevant domain so the next
   build uses the latest parameters. This event is a refresh trigger only — it does
   not itself trigger a trail rebuild.

3. **Fetch graph closures from the Topology Service.** Using the Topology Service's
   published query API (neighbors, bounded traversal by dependency-edge types), obtain
   the graph data needed to compute trail membership for the snapshot. All graph reads
   go through this API — never direct database access. Graph traversal is scoped to
   the snapshot's domain.

4. **Fetch trail policy from the Knowledge Service (domain-parameterized).** Retrieve
   the current trail policy for the snapshot's `domain` from the Knowledge Service via
   its published API (domain is a parameter of the policy fetch call). The trail policy
   (IGP-area boundary definition, SRLG-union rule, and any policy-parameterized bounds)
   is domain-scoped; a different domain's policy is fetched independently. Policy
   parameters must not be hard-coded.

5. **Compute trails per domain.** Apply the domain's trail policy to the fetched graph
   data for that domain's graph slice: transitive closure over dependency edges from
   each seed object, bounded by IGP area; union all links sharing an SRLG group into
   the same trail. Trails overlap — a seed object appearing on multiple LSPs and/or in
   an SRLG group produces membership in multiple trails. Computation stays within the
   domain; cross-domain graph traversal is not performed in MVP.

6. **Persist trail definitions with domain.** Store each trail as its member set of
   `managedObjectId` values, the `snapshotId` it was built from, and the `domain` it
   belongs to. A trail belongs to exactly one domain (for MVP). A rebuild for a new
   `snapshotId` in a given domain supersedes prior trails for that domain+snapshot
   combination; existing trail records for older snapshots are retained until
   explicitly superseded.

7. **Serve domain-scoped trail membership queries and trail browse via API.** Expose
   three query operations, all domain-scoped by default:
   - `getTrailsForObject(managedObjectId, domain)` — returns all trails the given
     object belongs to within the specified domain.
   - `getTrail(trailId)` — returns the trail's complete member list (typed
     `managedObjectId` values in the `<objectType>:<id>` scheme), the `snapshotId`
     it was built from, and the `domain` it belongs to. The response must be
     sufficient for the web-ui to overlay trail membership on a typed multi-layer
     topology graph; geometry and graph topology come from the Topology Service —
     not duplicated here.
   - `listTrails(snapshotId, domain)` — returns the set of all trail summaries built
     for the given snapshot within the given domain. Each summary carries the
     `trailId`, member count, `domain`, and the seed/bounds context (e.g. IGP area
     or SRLG context, where available). Supports pagination and/or filtering if
     natural; exact shape is a design-stage detail.
   The web-ui topology/trails module (trail visualization) is a first-class consumer
   of all three operations alongside Enrichment, Noise Filter, and Pattern Miner.
   Publish the full API as OpenAPI 3.1 at `/openapi.json`; check the generated
   `openapi.json` into `services/trail-builder/`.

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
| P1 — Topology onboarding | Builds domain-scoped, policy-bounded correlation trails from the topology graph and Knowledge domain trail policy; persists trail definitions (with domain); notifies downstream services. Concurrently serves the trail-query API (`listTrails`, `getTrail`, `getTrailsForObject`) to the web-ui topology/trails module. | Active | In: `topology.changed` (+ Topology Service graph-closure API scoped to domain, Knowledge Service domain-parameterized trail-policy API). Out: `trails.built`; serves trail-query API to web-ui and other consumers. |
| P2 — Pattern learning | Serves domain-scoped trail membership queries to consumers that scope historical alarms and transactions by trail (Enrichment, Noise Filter, Pattern Miner). | Passive | In: —. Out: serves `getTrailsForObject` / `getTrail` API (no topic output of its own). |
| P3 — Real-time correlation | Serves domain-scoped trail membership queries to real-time consumers (e.g. Enrichment live-path trail-tagging). | Passive | In: —. Out: serves `getTrailsForObject` / `getTrail` API (no topic output of its own). |

## Contract

- **Consumes (Kafka):**
  - `topology.changed` (payload: `TopologyChangedEvent` — fields `snapshotId`,
    `changeType`, `nodes`, `edges`, **`domain`**; envelope `eventId` is the
    idempotency key). Python/Pydantic binding from `acp_event_model`. Primary trigger
    for trail builds. The `domain` field scopes the build: trail-builder fetches the
    trail policy for this domain and computes trails within this domain's graph slice.
  - `knowledge.updated` (payload: `KnowledgeUpdatedEvent` — fields `recordType`
    (string), `recordId` (string, optional), `version` (string), `domain` (string);
    Python/Pydantic binding from `acp_event_model`). Used as a refresh trigger: when
    `recordType == "trailPolicy"`, the service re-fetches the current trail policy from
    the Knowledge Service API for the event's `domain`. No trail rebuild is triggered
    by this event alone — policy is refreshed in-cache; the rebuild fires on the next
    `topology.changed`.

- **Produces (Kafka):** `trails.built` (payload: `TrailsBuiltEvent` — fields
  `snapshotId` (string), `trailIds` (array of strings), `trailCount` (integer, must
  equal `len(trailIds)`); Python/Pydantic binding from `acp_event_model`). Full trail
  membership is intentionally out of the event; downstream consumers fetch it via the
  query API.

- **APIs exposed** (published as OpenAPI 3.1 at `/openapi.json`; generated
  `openapi.json` checked into `services/trail-builder/`; a surface change is a
  contract change):
  - `GET /trails?managedObjectId={managedObjectId}&domain={domain}` — returns all
    trail identifiers (and optionally trail summaries) for the given object within the
    specified domain. Corresponds to `getTrailsForObject(managedObjectId, domain)`.
    The `domain` parameter scopes the query; it is required for MVP. Consumers:
    Enrichment, Noise Filter, Pattern Miner, web-ui (topology/trails module).
  - `GET /trails/{trailId}` — returns the trail's member `managedObjectId` list
    (typed `<objectType>:<id>` values), the `snapshotId` it was built from, and the
    `domain` it belongs to. Corresponds to `getTrail(trailId)`. The response is
    visualization-ready: member identities carry type information via the
    `managedObjectId` prefix. Consumers: Enrichment, Noise Filter, Pattern Miner,
    web-ui (topology/trails module).
  - `GET /trails?snapshotId={snapshotId}&domain={domain}` — returns the set of all
    trail summaries built for the given snapshot within the given domain. Each summary
    carries at minimum `trailId`, member count, and `domain`; additional seed/bounds
    context (e.g. IGP area, SRLG group) is included where cheaply available. Supports
    pagination and/or filtering; exact query parameters and response shape are
    design-stage details. Corresponds to `listTrails(snapshotId, domain)`. Primary
    consumer: web-ui (topology/trails module).

    > **Note:** `GET /trails?managedObjectId=&domain=` and
    > `GET /trails?snapshotId=&domain=` are distinguished by their query parameters.
    > The designer may choose a different path shape (e.g.
    > `GET /domains/{domain}/snapshots/{snapshotId}/trails`) — this is a design-stage
    > decision. The semantic contract (the operation, its `domain` scoping, and its
    > consumers) is fixed here.

  - `POST /trails/rebuild` — triggers an on-demand trail rebuild (accepts `snapshotId`
    and `domain`; `domain` is required). Returns the resulting `TrailsBuiltEvent`
    summary.
  - `GET /health` — liveness/readiness probe.
  - `GET /metrics` — Prometheus metrics.
  - `GET /openapi.json` — machine-readable API contract.

- **APIs consumed from other services** (each is a named config-switchable integration
  point; built against the producer's published OpenAPI spec — never against producer
  source code):
  - **Topology Service query API** — graph closure and traversal endpoints (get
    neighbors, traverse by edge type, resolve `managedObjectId`). Used to fetch graph
    data for trail computation, scoped to the snapshot's domain.
  - **Knowledge Service trail-policy API** — reads the current trail policy for a
    given `domain` (IGP-area bound, SRLG-union rule, and any authored policy
    parameters). The domain is passed as a parameter to the fetch call. Used at build
    time and on `knowledge.updated` refresh.

- **Integration points (mock vs. real):**
  - **Topology Service** — config key: `TOPOLOGY_SERVICE_BASE_URL`; toggle:
    `TOPOLOGY_SERVICE_MODE` (`mock` | `real`). In unit tests, backed by a mock/stub
    generated from the Topology Service's published OpenAPI spec (e.g. via `respx` or
    a Prism server). In integration, pointed at the live Topology Service.
  - **Knowledge Service** — config key: `KNOWLEDGE_SERVICE_BASE_URL`; toggle:
    `KNOWLEDGE_SERVICE_MODE` (`mock` | `real`). In unit tests, backed by a mock/stub
    generated from the Knowledge Service's published OpenAPI spec, returning
    domain-scoped policy. In integration, pointed at the live Knowledge Service.
  - No integration point URL or mode may be hard-coded; all resolved from environment
    or configuration at startup.

- **Data owned:** PostgreSQL — trail definitions schema. Owns: trail records (trail
  identifier, member `managedObjectId` list, `snapshotId`, **`domain`**). Does not
  share its schema with any other service.

## Non-functional

- **Idempotency key:** envelope `eventId` (UUID) for `topology.changed` deduplication.
  Rebuilding for a new `snapshotId` (within a domain) supersedes existing trails for
  that domain+snapshot combination. Multiple deliveries of the same `topology.changed`
  event must not produce duplicate `trails.built` events or duplicate trail records.

- **Config:** all integration base-URLs, mock/real toggles, database connection
  strings, Kafka broker addresses, and any tuneable parameters come from environment
  variables or the Knowledge Service. No thresholds, URLs, or policy values may be
  hard-coded in source. Trail policy bounds (IGP-area definition, SRLG rules) are
  read from the Knowledge Service at build time for the specific domain; they are not
  duplicated in this service's config. The `domain` is always carried from the
  triggering event or API request — never defaulted in config.

- **Observability:** `/health` (liveness/readiness), `/metrics` (Prometheus), structured
  JSON logs on all code paths (including error paths). Log entries must include
  `traceId` (from the consumed event envelope or request header), `snapshotId`, and
  `domain` where applicable.

- **API contract:** publishes OpenAPI 3.1 at `/openapi.json`; the generated
  `openapi.json` is checked into `services/trail-builder/` and is the single source
  of truth for the HTTP surface. The service's own contract/unit tests validate
  request/response shapes against this spec. A surface change is a contract change
  requiring `docs/architecture.md` update and human approval.

- **Error handling:** poison `topology.changed` messages (deserialization failures,
  schema-version mismatches, or processing errors that cannot be retried) are routed
  to `topology.changed.dlq`. Errors from Topology/Knowledge integration point calls
  are logged and surfaced via `/health` or `/metrics`; they do not silently drop a
  build.

- **Snapshot alignment:** every persisted trail and every `trails.built` event carries
  the `snapshotId` it was built from. Combined with `domain`, this invariant enables
  downstream services (Codebook Generator, Enrichment) to detect and reject stale or
  domain-mismatched trail data.

## Acceptance criteria

Each criterion maps to a single `pytest` test.

1. **Multi-trail overlap.** Given a synthetic topology where object `X` participates
   in two LSP paths and one SRLG group, `getTrailsForObject(X, domain)` returns at
   least three distinct trail identifiers. (Overlap is real, not a degenerate
   single-trail result.)

2. **Policy-bounded trails.** Given a synthetic topology spanning multiple IGP areas,
   no single trail contains objects from more than one IGP area. The closure is bounded
   — there is no unbounded whole-network trail. (Verifies the IGP-area bound from the
   trail policy.)

3. **SRLG union.** Given a synthetic topology where two IP links share an SRLG group,
   both links appear in the same trail. (Verifies the SRLG-union rule from §5.)

4. **`getTrailsForObject` completeness.** For each object in the synthetic topology,
   `getTrailsForObject(managedObjectId, domain)` returns exactly the set of trail
   identifiers to which that object belongs — no more, no fewer. Cross-checked against
   the persisted trail records.

5. **`getTrail` correctness, domain, and visualization readiness.** `getTrail(trailId)`
   returns the full member `managedObjectId` list, the `snapshotId` it was built from,
   and the `domain` it belongs to. The returned `snapshotId` and `domain` match those
   used to trigger the build. Every member value in the list conforms to the
   `<objectType>:<id>` scheme so that the web-ui can resolve each member's layer
   without an additional lookup.

6. **`topology.changed` triggers a domain-scoped build and emits `trails.built`.** On
   consuming a `topology.changed` event with a new `snapshotId` and `domain`, the
   service (re)builds trails for that domain and emits a `trails.built` event whose
   payload deserializes without error against the `TrailsBuiltEvent` Pydantic model
   from `acp_event_model`, with `trailCount == len(trailIds)` and `snapshotId`
   matching the triggering event.

7. **Idempotency.** Delivering the same `topology.changed` event (same `eventId`)
   twice produces exactly one `trails.built` emission and does not create duplicate
   trail records.

8. **`knowledge.updated` refresh trigger (domain-scoped).** On consuming a
   `knowledge.updated` event with `recordType == "trailPolicy"` and a given `domain`,
   the service re-fetches the trail policy from the Knowledge Service API using that
   domain as the policy-fetch parameter. No `trails.built` event is emitted as a
   result. A subsequent `topology.changed` event for the same domain uses the newly
   fetched policy.

9. **Trail policy is fetched per domain.** Given two topology snapshots with different
   `domain` values (`domain-A` and `domain-B`) processed in sequence, the service
   makes two separate Knowledge Service trail-policy API calls — one parameterized
   with `domain-A` and one with `domain-B`. Each call receives and uses the policy
   specific to that domain. (Verifies domain-scoped policy fetch — a new domain's
   trails can be built with its own policy without a code change.)

10. **Trails carry domain and queries are domain-scoped.** Every trail record returned
    by `getTrail(trailId)` carries a non-empty `domain` field. Given two builds for
    different domains on the same `snapshotId`, `listTrails(snapshotId, domain-A)`
    returns only trails for `domain-A` and `listTrails(snapshotId, domain-B)` returns
    only trails for `domain-B` — no cross-domain leakage.

11. **Domain-agnostic computation (new domain without code change).** Given a
    synthetic non-Core-IP domain with its own trail policy (different IGP-area bounds
    or SRLG rules) mocked at the Knowledge Service integration point, the service
    builds trails for that domain using that domain's policy and carries the correct
    `domain` on all resulting trail records — without any code change to trail-builder.
    (Mirrors topology's domain-agnostic criterion.)

12. **Config-switchable integration points.** With `TOPOLOGY_SERVICE_MODE=mock` and
    `KNOWLEDGE_SERVICE_MODE=mock`, all unit tests run to completion with no live
    service dependency. The mock is backed by stubs generated from the respective
    published OpenAPI specs (including domain-parameterized trail-policy responses).
    With `*_MODE=real`, the service routes calls to the configured base-URL. The same
    code path is exercised in both modes.

13. **No hard-coded URLs, policy values, or domain defaults.** Changing
    `TOPOLOGY_SERVICE_BASE_URL` or `KNOWLEDGE_SERVICE_BASE_URL` in the environment
    causes the service to contact the new address without a code change. The `domain`
    used in policy fetches is always derived from the triggering event or API request,
    never defaulted in config.

14. **Poison message handling.** A `topology.changed` message that cannot be
    deserialized (malformed JSON or unknown major `schemaVersion`) is routed to
    `topology.changed.dlq` without crashing the consumer or dropping subsequent
    messages.

15. **`snapshotId` and `domain` alignment.** Every trail record returned by
    `getTrail(trailId)` carries the `snapshotId` and `domain` from the build that
    created it. A subsequent build for a different `snapshotId` in the same domain
    creates new trail records tagged with the new `snapshotId`, leaving prior snapshot
    records intact.

16. **OpenAPI contract compliance.** Requests and responses for
    `GET /trails?managedObjectId=&domain=`, `GET /trails?snapshotId=&domain=`,
    `GET /trails/{trailId}`, and `POST /trails/rebuild` validate against the
    checked-in `openapi.json` schema (request inputs and response bodies, including
    the `domain` field on trail responses).

17. **`listTrails(snapshotId, domain)` enumerates all trails for a snapshot+domain.**
    Given a completed trail build for snapshot `S` and domain `D` that produced N
    trails, `GET /trails?snapshotId=S&domain=D` (or equivalent) returns exactly N
    trail summary records, each carrying a `trailId`, `domain`, and member count
    greater than zero. The union of all `trailId` values equals the `trailIds` array
    from the corresponding `trails.built` event for snapshot `S`.

18. **`getTrail(trailId)` members are typed `managedObjectId`s sufficient for
    visualization.** For every `trailId` returned by `listTrails` or
    `getTrailsForObject`, `GET /trails/{trailId}` returns a response containing
    `snapshotId`, `domain`, and a non-empty member list in which every entry matches
    the pattern `<objectType>:<id>`. No additional per-member API call to Trail Builder
    is needed for the web-ui to identify each member's layer type.

## Open questions

The items below are tracked for human resolution. Items 1–4 are pre-existing
design-stage items (not contract gaps) and do not block the spec. Item 5 is a new
contract question arising from the domain-scoping update and must be resolved before
the design stage proceeds.

1. **[DESIGN-STAGE] Topology Service query API surface for trail computation.**
   The Topology Service query API must expose bounded-traversal and neighbor endpoints
   sufficient for computing transitive closures. The exact endpoint names, path
   parameters, and response shapes will be defined in the Topology Service's published
   `openapi.json`. Trail Builder builds its Topology integration-point mock against
   that published spec once it is checked in.
   _(Tracked: [#24](https://github.com/nikhilmohan/correlation-platform/issues/24))_

2. **[DESIGN-STAGE] Knowledge Service trail-policy API shape (domain-parameterized).**
   The trail policy fields (IGP-area boundary definition, SRLG-union rule, and authored
   parameters) that Trail Builder reads from the Knowledge Service will be defined in
   the Knowledge Service's published `openapi.json`. The endpoint must accept `domain`
   as a parameter and return the domain-scoped policy. Trail Builder builds its
   Knowledge integration-point mock against that published spec once checked in.
   _(Tracked: [#25](https://github.com/nikhilmohan/correlation-platform/issues/25))_

3. **[DESIGN-STAGE] On-demand rebuild API authentication / authorization.**
   `POST /trails/rebuild` initiates a potentially expensive operation. Whether the
   endpoint requires authentication or is restricted to internal callers is a policy
   decision finalized in `design.md`.
   _(Tracked: [#27](https://github.com/nikhilmohan/correlation-platform/issues/27))_

4. **[DESIGN-STAGE] Trail record retention policy for older snapshots.**
   Every trail record carries `snapshotId` and `domain`. The exact retention rule
   (keep N snapshots per domain, TTL, or explicit deletion trigger) is finalized in
   `design.md`.
   _(Tracked: [#29](https://github.com/nikhilmohan/correlation-platform/issues/29))_

5. **[CONTRACT QUESTION] Should `TrailsBuiltEvent` carry a `domain` field?**
   The `TrailsBuiltEvent` payload currently carries `snapshotId`, `trailIds[]`, and
   `trailCount`. With domain-scoped trails, downstream consumers of `trails.built`
   (primarily Codebook Generator) may need to know which domain the trails belong to
   without a follow-up API call. Adding `domain` to `TrailsBuiltEvent` is a contract
   change (`libs/event-model` + `docs/architecture.md` update + human approval).
   Alternatively, `domain` may be derivable from `snapshotId` (if `snapshotId`
   encodes or references the domain), in which case no event payload change is needed.
   **Human decision required** before the design stage — do not add `domain` to
   `TrailsBuiltEvent` without approval. Flagged as an Open question, not resolved here.
   _(See also: contract-change procedure in `.claude/agents/CONVENTIONS.md`.)_
