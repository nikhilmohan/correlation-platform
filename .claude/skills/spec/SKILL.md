---
name: spec
description: >-
  Author or refresh a service spec for the Alarm Correlation Platform. Derives (never
  invents) services/<svc>/spec.md from docs/architecture.md + the Solution Design, using
  exact topic/payload names. Use when seeding or updating a service's spec.
---

# Skill: `spec` — author a service spec

Produce `services/<svc>/spec.md` — the contract the designer and dev agents build against.
Derive it; do not invent.

## Inputs
- `docs/architecture.md` (the contract: service inventory, event model, Kafka topics, stores,
  invariants).
- The Solution Design's §6 entry for the target service.
- The target service name.

## Procedure
1. Read the inputs. Identify the service's responsibility, the topics it consumes/produces,
   the data it owns, and its acceptance criteria from the Solution Design.
2. Fill the **template** below. Use the **exact** topic and payload names from
   `architecture.md` — do not paraphrase or rename them.
3. Phrase every acceptance criterion so it can map to a **single unit test** (concrete,
   observable, testable).
4. Anything not already in the contract — a needed new topic/payload/field, an undefined
   behaviour, an ambiguity — goes under **Open questions**. Do **not** guess and do **not**
   introduce a contract change here.
5. Stay in the "what/why". No stack, modules, or algorithms — that is the design stage.

## Template (`services/<svc>/spec.md`)
```markdown
# <svc> — Service Spec

## Purpose
<One paragraph: the service's responsibility within the platform.>

## Scope
**In scope:** <bullets>
**Out of scope:** <bullets — e.g. deferred MVP items>

## Contract
- **Consumes (Kafka):** <exact topic names, or "—">
- **Produces (Kafka):** <exact topic names, or "—">
- **APIs exposed:** <REST/gRPC operations, or "—">
- **APIs/data consumed from other services:** <e.g. Topology getNeighbors, Knowledge params>
- **Data owned:** <datastore + what it owns, e.g. "PostgreSQL Pattern Store", or "—">

## Non-functional
- **Idempotency key:** <eventId | alarmId | …>
- **Config:** <env vars / Knowledge-Service params — no hard-coded thresholds>
- **Observability:** /health, /metrics (Prometheus), structured JSON logs
- **Error handling:** poison messages → <topic>.dlq

## Acceptance criteria
1. <Testable criterion>
2. <Testable criterion>
…

## Open questions
- <Ambiguity or needed contract change for a human to resolve, or "None">
```

## Gate (must hold before the spec PR)
- Every topic/payload/API reference matches `docs/architecture.md` exactly.
- **No silent new topic/payload/field** — any such need is an Open question, not part of the
  contract.
- Each acceptance criterion is testable.

## Output & process
Write the file under `services/<svc>/spec.md`. Branch `spec/<svc>`; open a PR into `<svc>`.
The merge is a **human gate** — do not self-merge.
