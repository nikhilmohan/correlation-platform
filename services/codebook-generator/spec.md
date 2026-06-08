# codebook-generator — Service Spec

> **STATUS: TBD.** Scaffold stub. To be authored by `@product-analyst` using the `spec`
> skill, derived from `docs/architecture.md` + Solution Design §6, then human-approved.

## Purpose
Compile the codebook: enumerate candidate root-cause instances and generate each one's predicted symptom signature via forward propagation; tag scenarios to trails.

## Scope
**In scope:** _TBD_
**Out of scope:** _TBD_

## Contract
- **Consumes (Kafka):** _TBD (exact topic names from architecture.md)_
- **Produces (Kafka):** _TBD_
- **APIs exposed:** _TBD_
- **APIs/data consumed:** _TBD_
- **Data owned:** _TBD_

## Non-functional
- **Idempotency key:** _TBD (eventId | alarmId)_
- **Config:** _TBD (env / Knowledge-Service params — no hard-coded thresholds)_
- **Observability:** /health, /metrics, structured JSON logs
- **Error handling:** poison messages → <topic>.dlq

## Acceptance criteria
_TBD — derived from Solution Design §6 acceptance criteria; each maps to a unit test._

## Open questions
_TBD_
