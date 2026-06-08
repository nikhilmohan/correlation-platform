---
name: code-review
description: >-
  One language-aware, read-only review per build PR for the Alarm Correlation Platform.
  Detects the changed cohort, applies a universal + per-cohort checklist, runs the
  linters/tests, and posts a Blocker/Major/Minor report + verdict to the PR. Use on a
  build/<svc> PR. Loop cap 3 rounds, then escalate.
---

# Skill: `code-review` — review a build PR

A single, language-aware, **read-only** review of a `build/<svc>` PR against its spec +
design. Report and raise issues only — do not edit source (separation of duties).

## Inputs
- The PR diff; the service `spec.md` + `design.md`; `docs/architecture.md`.

## Procedure
1. Detect the cohort(s) from the diff (Python / Java / Angular).
2. Apply the **universal checklist** + the matching **per-cohort checklist**.
3. **Run the linters/tests to verify** — report what actually ran and its result.
4. Post a report to the PR via `gh`, grouped Blocker / Major / Minor, ending in a verdict.

## Universal checklist
- Implementation matches `design.md` and satisfies **every acceptance criterion** in `spec.md`.
- **Contract adherence:** exact topic/payload names from `architecture.md`; **no silent new
  topic/payload/field**; depends on `libs/event-model` + topic contracts, not other services'
  code; **no domain logic leaked into the shared lib**.
- **Idempotency:** consumers dedupe on `eventId`/`alarmId`.
- **API contract:** if the service exposes HTTP, it **publishes OpenAPI 3.1** (`/openapi.json`
  + checked-in `openapi.json`); its own spec drives its contract/unit tests; clients to other
  services are built against the **producer's published OpenAPI**, not its source.
- **Integration points:** outbound dependencies are **config-driven and mock|real switchable**
  (mock from the collaborator's OpenAPI in unit tests; real in integration) — **no hard-coded
  collaborator URLs**.
- **DLQ / error handling:** poison messages → `<topic>.dlq`, not dropped.
- **Observability:** `/health`, `/metrics`, structured logging, config-from-env (no secrets).
- **Tests:** meaningful and **passing**, coverage at/above the gate; criterion→test traceable.
- **No cross-service coupling.** README + Dockerfile present. Permissive licenses only.

## Per-cohort checklist (test frameworks are fixed — flag substitutions)
- **Python:** `ruff check` + `black --check` clean; type hints; **no hard-coded thresholds**
  (params from Knowledge/env); tests use **pytest**; `pytest --cov` ≥ 80%.
- **Java:** Spring idioms; constructor injection; explicit **idempotent** Kafka config; tests
  use **JUnit 5** (Testcontainers where integration is needed); `./gradlew build` (JaCoCo gate)
  green.
- **Angular:** standalone components; typed (no `any`); signals; WCAG 2.1; unit/component tests
  use **Vitest + TestBed** (not Karma/Jest); **Playwright** used for E2E only (not unit);
  `npm run lint`, `npm test`, `npm run build` green.

## Output
A report on the PR grouped **Blocker / Major / Minor**, ending in exactly one verdict:
**APPROVE** or **CHANGES REQUESTED**.

## Loop
Code↔review is capped at **3 rounds**. If not APPROVE-able after three, escalate to a human.
