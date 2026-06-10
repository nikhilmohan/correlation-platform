---
name: design
description: >-
  Turn an approved service spec into a buildable design for the Alarm Correlation Platform.
  Produces services/<svc>/design.md with a test plan mapping every acceptance criterion to a
  test, honours the invariants, and flags contract changes for the human. Use after the spec
  is approved and merged on <svc>.
---

# Skill: `design` — turn an approved spec into a buildable design

Produce `services/<svc>/design.md` — enough technical detail that a dev agent can implement
directly, with every acceptance criterion mapped to a test.

## Prerequisite
The spec is approved and merged on `<svc>`. If not, stop.

## Inputs
- `CLAUDE.md`, `docs/architecture.md`, and the approved `services/<svc>/spec.md`.

## Procedure
1. Read the inputs. Choose a cohort-correct stack (Python networkx/scikit-learn/PySpark;
   Spring Boot + Kafka Streams for correlation-engine; Angular 20 for web-ui) consistent with
   the Solution Design's split.
2. **Build on the spec's Tasks.** Take the **Tasks (high-level)** section of the approved
   `spec.md` as the backbone of the design. For each task, detail *how* it is realized — the
   modules, data, events, and flow that implement it. Every spec task must be traceable into the
   design; do not drop or silently re-scope one (a scope change goes back to the spec/human).
3. Fill the **template** below.
4. **Use diagrams.** **Key flows must include at least one Mermaid sequence/data-flow diagram**;
   add **flowcharts** for algorithm logical flow, **ER/classDiagrams** for data models, and
   stateDiagrams for lifecycles where they clarify. Diagrams supplement prose; they don't replace
   the contract/test detail. Don't add diagrams that carry no information.
5. Build the **test plan**: (a) map **every acceptance criterion** from the spec to a specific
   test (name + what it asserts) — the 1:1 mapping is the design-gate condition; (b) define the
   **E2E scenarios** that must be exercised **from this design unit's point of view** — the
   end-to-end paths through this service (and its real collaborators) that prove the tasks work
   together, including failure/partial paths (e.g. DLQ, dropped trap, missing dependency).
6. **Capture design alternatives.** For each non-trivial design consideration where more than
   one credible option exists, record the alternatives considered, the trade-offs, and why the
   chosen one wins. Omit only when there was genuinely no meaningful choice.
7. Honour the invariants (contract-first; single owners; idempotency on `eventId`/`alarmId`;
   DLQ; `/health` + `/metrics`; permissive licenses).
8. If the spec implies a contract change (new topic/payload/field), **flag it for the human** —
   do not design around it or invent it.

## Template (`services/<svc>/design.md`)
```markdown
# <svc> — Design

## Stack
<Language, frameworks, key libs (permissive licenses), runtime version.>

## Task breakdown (from the spec)
<For each Task in spec.md → how this design realizes it: which modules/data/events/flow. Every
spec task must appear here. Use a table if it helps traceability.>
| Spec task | Realized by (modules / flow) |
|---|---|
| <spec task 1> | <design detail> |
…

## Phase applicability (design view)
<Carry the spec's "Phase applicability" into the technical layer. For each of the three runtime
phases (architecture.md → "Runtime phases": P1 Topology onboarding, P2 Pattern learning, P3
Real-time correlation) state, for THIS service: its Active/Passive/Idle role (must match the spec
and the canonical phase map), WHICH modules/handlers/endpoints/flows are exercised in that phase
(and which are dormant), and the per-phase I/O (topics/APIs). This makes the per-phase behaviour
of the implementation explicit and testable.>

| Phase | Active/Passive/Idle | Modules/handlers exercised | Inputs/Outputs |
|---|---|---|---|
| P1 — Topology onboarding | <A/P/I> | <modules/flows, or "dormant"> | <topics/APIs, or "—"> |
| P2 — Pattern learning | <A/P/I> | <modules/flows, or "dormant"> | <topics/APIs, or "—"> |
| P3 — Real-time correlation | <A/P/I> | <modules/flows, or "dormant"> | <topics/APIs, or "—"> |

## Module breakdown
<Internal components and responsibilities. Add a Mermaid component/flow diagram if it clarifies
how modules interact.>

## Data model / DB schema
<Owned datastore + entities. **If this service owns a store, give the concrete DB schema:** tables/
collections, columns with types, keys, indexes, and important constraints (and how `snapshotId`/
version/`eventId` dedupe is represented). Include a Mermaid **ER diagram** (or classDiagram) for
non-trivial models. If the service owns no store, state "N/A — no owned store" and why.>

## Event handling
- **Consumers:** <topic → handler; idempotency/dedupe key; DLQ routing>
- **Producers:** <topic → payload type from libs/event-model>

## API contracts / API schema
<REST operations with **concrete request/response schema** — path + method, request body shape,
response body shape (field names + types, reusing `libs/event-model` payloads where applicable),
status codes, and error responses. State how the **OpenAPI 3.1** spec is generated and published
(/openapi.json + checked-in openapi.json), and how the service's own spec drives its contract/unit
tests. If the service exposes no HTTP API, state "N/A — no HTTP surface" and why.>

## Integration points (mock vs. real)
<For each outbound dependency: the collaborator + operation, the config key(s) that select its
base URL, and the mock|real toggle. Mock = stub generated from the collaborator's published
OpenAPI spec (used in unit tests); real = the live service (used in integration). No hard-coded
URLs — resolution is by env/config.>

## Key flows (sequence / data-flow diagrams)
<The main paths through the service (e.g. ingest → process → persist → emit), each as a **Mermaid
sequence diagram or data-flow diagram** (not just prose) showing the actors/topics/APIs/stores
involved and the order of interactions. Cover the primary success path and the key cross-service
hand-offs. At least one diagram is required.>

## Algorithm logical flow
<**Required when the service implements non-trivial logic / an algorithm** (e.g. trail closure,
forward-propagation, DBSCAN clustering, PrefixSpan mining, pattern/codebook matching + conflict
resolution, RCA). Give a clear logical-flow view — a Mermaid **flowchart** or numbered
decision/step logic — of how the algorithm works: inputs, the core steps/branches, the parameters
it reads (from Knowledge, never hard-coded), and outputs. Make it concrete enough that a dev can
implement it. If the service has no non-trivial algorithm, state "N/A" and why.>

## Seed data & examples
<**Required for the Simulator and any service with seed/fixture/sample data.** Describe the seed/
generation scripts and include **concrete worked examples** — e.g. for the Simulator: how the
synthetic topology file + labeled alarm streams are generated, the script/config knobs (size,
jitter, noise mix, scenarios), and a small **sample of the actual output** (an example topology
file fragment and example AlarmEvents with values). For other services, show representative sample
inputs/outputs/fixtures used in tests. If not applicable, state "N/A" and why.>

## UI wireframes
<**Required for web-ui.** For each module/screen, an ASCII/Mermaid wireframe (or a clear
structured layout description) of the key views — what's on screen, the components, the user
actions, and the data each view reads/writes (which integration-point API). Include the primary
flows (e.g. pattern review/approve/edit, config edit, topology/trail view, stats/alarm view).
Non-web-ui services: "N/A".>

## Error handling
<A first-class section (required for all services). Enumerate the failure modes and the defined
handling for each: poison/invalid messages → which `<topic>.dlq`; unknown major `schemaVersion`
rejection; a dependency/integration point being unavailable or erroring (retry/backoff, degrade,
or fail — and what the consumer sees); validation failures (bad request → status + structured
error); partial/duplicate processing (idempotency); and any algorithm-specific failure (e.g. no
match, empty result). State what is logged, what is surfaced to callers, and what never silently
drops.>

## Design alternatives
<For each non-trivial design consideration with more than one credible option: the alternatives
considered, their trade-offs, and why the chosen approach wins. Omit only if there was no
meaningful choice. Example consideration: "windowing — Kafka Streams session window vs.
processor-API custom store".>
| Consideration | Alternatives considered | Chosen + rationale |
|---|---|---|
| <consideration> | <A vs. B vs. C, trade-offs> | <choice + why> |

## Test plan

### Acceptance criterion → test (unit/contract)
| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| 1 | <from spec> | <test name> | <assertion> |
…

### E2E scenarios (from this design unit's point of view)
<End-to-end paths through this service and its real collaborators that prove the tasks work
together — including failure/partial paths. Each names the trigger, the path, and the expected
outcome. For web-ui these are Playwright flows; for back-end services these are the
service-scoped end-to-end paths the integration-test stage exercises.>
| # | Scenario | Trigger → path | Expected outcome |
|---|---|---|---|
| 1 | <e.g. fiber-cut storm in → one incident out, LOS = root cause, partial-match tolerated> | <input → steps> | <assertion> |
…

## Config & observability
<Env/Knowledge-Service params; /health, /metrics; logging.>

## Build & run
<Build command, Dockerfile notes, local run instructions.>
```

## Gate (must hold before the design PR)
- **Every spec task** appears in the **Task breakdown** (full traceability; no dropped/re-scoped
  task).
- **Phase applicability (design view)** covers all three runtime phases with Active/Passive/Idle +
  the modules/handlers exercised + I/O per phase, consistent with the spec and the canonical phase
  map in `architecture.md`.
- **Every acceptance criterion is mapped to a test** in the test plan, **and E2E scenarios** are
  defined from this design unit's point of view (incl. failure/partial paths).
- **Design alternatives** are recorded for each non-trivial consideration (or explicitly noted
  as "no meaningful choice").
- **Key flows** include at least one **Mermaid sequence/data-flow diagram** (not prose only).
- **Data model / DB schema**: if the service owns a store, the concrete DB schema (tables/columns/
  keys/indexes) is given; otherwise "N/A — no owned store".
- **API contracts / API schema**: if the service exposes HTTP, concrete request/response schemas +
  status/error codes are given; otherwise "N/A — no HTTP surface".
- **Error handling** section is present and enumerates failure modes + defined handling (DLQ,
  schemaVersion rejection, dependency-down, validation, idempotency, algorithm failures).
- **Conditional sections present where applicable** (else "N/A — why"): **Algorithm logical flow**
  (services with non-trivial algorithms), **Seed data & examples** (Simulator / seed-data services),
  **UI wireframes** (web-ui).
- Invariants honoured; any contract change is flagged for the human, not designed around.
- If the service exposes an HTTP API: OpenAPI publication is designed, and **every integration
  point is config-switchable** (mock from the collaborator's OpenAPI for unit tests / real for
  integration) — no hard-coded collaborator URLs.

## Output & process
Write the file under `services/<svc>/design.md`. Branch `design/<svc>`; open a PR into `<svc>`.
The merge is a **human gate** — do not self-merge.
