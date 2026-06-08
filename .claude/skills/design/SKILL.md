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
2. Fill the **template** below.
3. Build the **test plan**: list every acceptance criterion from the spec and map each to a
   specific test (name + what it asserts). This 1:1 mapping is the design-gate condition.
4. Honour the invariants (contract-first; single owners; idempotency on `eventId`/`alarmId`;
   DLQ; `/health` + `/metrics`; permissive licenses).
5. If the spec implies a contract change (new topic/payload/field), **flag it for the human** —
   do not design around it or invent it.

## Template (`services/<svc>/design.md`)
```markdown
# <svc> — Design

## Stack
<Language, frameworks, key libs (permissive licenses), runtime version.>

## Module breakdown
<Internal components and responsibilities.>

## Data model
<Owned datastore + schema/entities; snapshotId/version references where relevant.>

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
<Sequence of the main paths (e.g. ingest → process → emit).>

## Test plan (acceptance criterion → test)
| # | Acceptance criterion | Test | Asserts |
|---|---|---|---|
| 1 | <from spec> | <test name> | <assertion> |
…

## Config & observability
<Env/Knowledge-Service params; /health, /metrics; logging.>

## Build & run
<Build command, Dockerfile notes, local run instructions.>
```

## Gate (must hold before the design PR)
- **Every acceptance criterion is mapped to a test** in the test plan.
- Invariants honoured; any contract change is flagged for the human, not designed around.
- If the service exposes an HTTP API: OpenAPI publication is designed, and **every integration
  point is config-switchable** (mock from the collaborator's OpenAPI for unit tests / real for
  integration) — no hard-coded collaborator URLs.

## Output & process
Write the file under `services/<svc>/design.md`. Branch `design/<svc>`; open a PR into `<svc>`.
The merge is a **human gate** — do not self-merge.
