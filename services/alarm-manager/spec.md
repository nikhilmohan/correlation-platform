# alarm-manager — Service Spec

> **STATUS: TBD.** Scaffold stub. To be authored by `@product-analyst` using the `spec`
> skill, derived from `docs/architecture.md` + Solution Design §4.4, then human-approved.

## Purpose
Sole owner of alarm persistence. Owns two PostgreSQL stores: the **operational alarm-lifecycle
store** (every enriched alarm with state open→correlated→cleared + root-cause/child tags, for the
web-ui live view) and the **analytical historical-alarm corpus** (the durable body of enriched
historical alarms the Noise Filter / Pattern Miner mine over). Consumes `alarms.enriched`,
`alarms.enriched.live`, and `correlation.results`; serves alarm query APIs. Introduces no new
Kafka topic or event payload.

## Scope
**In scope:** _TBD_
**Out of scope:** _TBD_

## Contract
- **Consumes (Kafka):** _TBD (alarms.enriched, alarms.enriched.live, correlation.results)_
- **Produces (Kafka):** _TBD (— ; serves query APIs)_
- **APIs exposed:** _TBD (alarm-lifecycle query for web-ui; historical-corpus query for mining)_
- **APIs/data consumed:** _TBD_
- **Data owned:** _TBD (operational alarm-lifecycle store + analytical historical-alarm corpus)_

## Non-functional
- **Idempotency key:** _TBD (eventId | alarmId)_
- **Config:** _TBD (env / Knowledge-Service params — no hard-coded thresholds)_
- **Observability:** /health, /metrics, structured JSON logs
- **Error handling:** poison messages → <topic>.dlq

## Acceptance criteria
_TBD — derived from architecture.md + Solution Design §4.4; each maps to a unit test._

## Open questions
_TBD_
