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
4. **Use diagrams as required.** Where a flow, state machine, component interaction, or data
   model is clearer as a picture, include a **Mermaid** diagram (flowchart, sequence,
   classDiagram, stateDiagram). Diagrams supplement prose; they don't replace the contract/test
   detail. Don't add diagrams that carry no information.
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

## Module breakdown
<Internal components and responsibilities. Add a Mermaid component/flow diagram if it clarifies
how modules interact.>

## Data model
<Owned datastore + schema/entities; snapshotId/version references where relevant. A Mermaid
classDiagram / ER-style diagram is encouraged for non-trivial models.>

## Event handling
- **Consumers:** <topic → handler; idempotency/dedupe key; DLQ routing>
- **Producers:** <topic → payload type from libs/event-model>

## API contracts
<REST operations: path/method, request/response shapes. State how the **OpenAPI 3.1** spec is
generated and published (/openapi.json + checked-in openapi.json), and how the service's own
spec drives its contract/unit tests.>

## Integration points (mock vs. real)
<For each outbound dependency: the collaborator + operation, the config key(s) that select its
base URL, and the mock|real toggle. Mock = stub generated from the collaborator's published
OpenAPI spec (used in unit tests); real = the live service (used in integration). No hard-coded
URLs — resolution is by env/config.>

## Key flows
<Sequence of the main paths (e.g. ingest → process → emit). Use Mermaid sequence/flow diagrams
where a picture is clearer than prose.>

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
- **Every acceptance criterion is mapped to a test** in the test plan, **and E2E scenarios** are
  defined from this design unit's point of view (incl. failure/partial paths).
- **Design alternatives** are recorded for each non-trivial consideration (or explicitly noted
  as "no meaningful choice").
- Invariants honoured; any contract change is flagged for the human, not designed around.
- If the service exposes an HTTP API: OpenAPI publication is designed, and **every integration
  point is config-switchable** (mock from the collaborator's OpenAPI for unit tests / real for
  integration) — no hard-coded collaborator URLs.

## Output & process
Write the file under `services/<svc>/design.md`. Branch `design/<svc>`; open a PR into `<svc>`.
The merge is a **human gate** — do not self-merge.
