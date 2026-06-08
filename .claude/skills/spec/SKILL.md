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
3. Capture a **high-level view of the Tasks** the service performs — the discrete units of work
   it is responsible for (the *what*, not the *how*). Each task is a short, outcome-oriented
   statement (e.g. "Ingest `topology.raw` and lift flat records into the typed graph"). These
   tasks are what the `design` skill builds on and detail out. Keep them implementation-free.
4. Be explicit about **Out of scope** — what this service deliberately does NOT do (deferred
   MVP items, responsibilities owned by other services). This bounds the design.
5. Phrase every acceptance criterion so it can map to a **single unit test** (concrete,
   observable, testable). Where useful, relate criteria back to the tasks they verify.
6. Anything not already in the contract — a needed new topic/payload/field, an undefined
   behaviour, an ambiguity — goes under **Open questions**. Do **not** guess and do **not**
   introduce a contract change here.
7. Stay in the "what/why". No stack, modules, or algorithms — that is the design stage.

## Template (`services/<svc>/spec.md`)
```markdown
# <svc> — Service Spec

## Purpose
<One paragraph: the service's responsibility within the platform.>

## Scope
**In scope:** <bullets>

## Out of scope
<bullets — what this service deliberately does NOT do: deferred MVP items, responsibilities
owned by other services. Bounds the design.>

## Tasks (high-level)
<The discrete units of work this service is responsible for — outcome-oriented, implementation-
free. The design builds on and details these.>
1. <Task — e.g. "Ingest `topology.raw` and lift flat records into the typed multi-layer graph">
2. <Task>
…

## Contract
- **Consumes (Kafka):** <exact topic names, or "—">
- **Produces (Kafka):** <exact topic names, or "—">
- **APIs exposed:** <REST operations; published as OpenAPI 3.1 at /openapi.json + checked-in
  openapi.json, or "—">
- **APIs/data consumed from other services:** <each as a named integration point, e.g. Topology
  getNeighbors, Knowledge params — built against the producer's published OpenAPI spec>
- **Integration points (mock vs. real):** <list outbound dependencies; each must be config-
  switchable: mock (from collaborator's OpenAPI) for unit tests, real for integration>
- **Data owned:** <datastore + what it owns, e.g. "PostgreSQL Pattern Store", or "—">

## Non-functional
- **Idempotency key:** <eventId | alarmId | …>
- **Config:** <env vars / Knowledge-Service params — no hard-coded thresholds or integration URLs>
- **Observability:** /health, /metrics (Prometheus), structured JSON logs
- **API contract:** publishes OpenAPI 3.1 (/openapi.json + checked-in spec); own spec drives
  contract/unit tests; collaborators integrate against it (a surface change is a contract change)
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
- **Tasks (high-level)** and **Out of scope** are both populated — the design depends on them.
- **No silent new topic/payload/field** — any such need is an Open question, not part of the
  contract.
- If the service exposes an HTTP API, the spec states it **publishes OpenAPI 3.1** and lists
  its **integration points** as config-switchable (mock for unit tests / real for integration),
  per `architecture.md` → "API contracts & integration points".
- Each acceptance criterion is testable.

## Output & process
Write the file under `services/<svc>/spec.md`. Branch `spec/<svc>`; open a PR into `<svc>`.
The merge is a **human gate** — do not self-merge.
