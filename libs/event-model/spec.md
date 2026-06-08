# event-model — Library Spec

> **STATUS: TBD — first post-bootstrap work item (kickoff §11 step 1).**
> Scaffold only. To be written via `@product-analyst` using the `spec` skill, derived from
> the Solution Design §6.12 / §7 and `docs/architecture.md`, then human-approved and FROZEN
> before any service is built.

## Purpose
The shared canonical event library: the single source of truth for the event **envelope**
(`eventId, type, schemaVersion, occurredAt, source, traceId, payload`), the specialized
**payloads** (AlarmEvent, TopologyChangedEvent, TrailsBuiltEvent, CodebookGeneratedEvent,
TransactionEvent, PatternMinedEvent, PatternDiscoveredEvent, PatternApprovedEvent,
CorrelationResultEvent), and the `managedObjectId` scheme. Two bindings generated from **one
JSON Schema**: a **Java** library (Spring cohort) and a **Python/Pydantic** library (Python
cohort). It is a pure contract/binding library — **no business/domain logic**, extensible via
subclassing.

<!-- Sections to be completed by @product-analyst: Scope, Contract, Non-functional,
     Acceptance criteria (incl. cross-binding wire-format agreement, unknown-major-version
     rejection), Open questions. -->
