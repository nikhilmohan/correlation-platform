# P1 Playwright E2E — review guide

A human-readable map of the P1 end-to-end suite (PR #194). One row per test: the acceptance
criterion / solution-goal it covers, **what it actually asserts**, and **whether it runs against a
real P1 service or a contract-mock**. The tests themselves live in `services/web-ui/e2e/*.e2e.ts`;
this doc is the review lens, not a substitute for reading the specs.

## How to read the "real vs mocked" column
Per the incremental-E2E gate decision, **P1 services are exercised for real; not-yet-built P2/P3
collaborators are mocked at the HTTP contract boundary** (`e2e/support/contract-mocks.ts`), with
bodies pinned 1:1 to the frozen producer OpenAPI / `libs/event-model` (via `src/app/api/models.ts`).
When a P2/P3 service is later built + added to compose, its mock is dropped and **the same spec runs
unchanged** against the real service.

- **REAL (P1):** Topology (8082), Trail Builder (8083), Codebook (8084), Knowledge (8081)
- **CONTRACT-MOCKED (P2/P3 + chatter):** Correlation Engine, Alarm Manager, Pattern Manager,
  Noise Filter, Enrichment

Two run modes: `E2E_MODE=mock` (no stack — in-app fixtures; for local authoring / well-formedness)
and `E2E_MODE=real` (the integration gate — real P1 stack + the contract mocks above).

## Coverage map

| Spec file | AC / goal | What it asserts | Backend |
|---|---|---|---|
| `p1-demonstrable-journey.e2e.ts` | **AC 33**, solution-goals **P1-1/2/4/6** | Geo-site map lists sites (real mode asserts **8–14 sites** per P1-1's ~10 target); drill into a site → device graph renders **nodes + edges** (P1-2) + attribute panel; **trail clusters overlay** the graph (P1-4, with explicit empty-state fallback); selecting a trail-member device highlights its trails; map surface carries the ARIA label (AC 52) | **REAL P1** (Topology/Trail Builder/Codebook/Knowledge) |
| `config.e2e.ts` | **AC 43** (+ AC 42 cross-check) | Editing a model param + saving **confirms a new persisted version** read back from Knowledge; an out-of-bounds value **blocks submit** with a validation error and makes **no API call** | **REAL** (Knowledge) |
| `dashboard-and-cross-nav.e2e.ts` | **AC 5, 25** | Dashboard shows **non-zero incident count + non-zero alarm-reduction ratio** from CE stats; KPI → incidents list → incident-detail navigation completes and renders | mocked (CE) |
| `streaming.e2e.ts` | **AC 13** | Streaming view ingests alarms and **renders their lifecycle state** (open/in-progress/correlated/cleared) from Alarm Manager | mocked (Alarm Mgr) |
| `incident-detail.e2e.ts` | **AC 17** (+ AC 23 deep-link) | **Direct deep link** to `/incidents/<id>` renders the root-cause alarm **+ ≥1 child alarm** | mocked (CE/Alarm Mgr) |
| `noise-stats.e2e.ts` | **AC 20** | Noise-stats view renders **≥1 run row with non-zero alarmsIn** from the Noise Filter run-stats API | mocked (Noise Filter) |
| `patterns.e2e.ts` | **AC 39** | Operator **approves a draft pattern**; a subsequent read returns it as **approved** from Pattern Manager (stateful mock proves the round-trip) | mocked (Pattern Mgr) |
| `correlation-stats.e2e.ts` | **AC 46, 49** | Stats incidents tab shows **≥1 incident with a tagged root cause + child alarms**; alarm-lifecycle view shows **≥1 correlated alarm with a non-empty incident association** | mocked (CE/Alarm Mgr) |

**14 tests across 8 spec files.** Each test name carries its AC id. `npm run e2e:list` enumerates them.

## What to scrutinise during review
1. **`e2e/support/contract-mocks.ts`** — the contract boundary. Confirm the mock response bodies
   match the frozen producer contracts (they mirror `src/app/api/models.ts`). This is the guarantee
   that "the contract is the boundary" and the mock can't silently diverge.
2. **`p1-demonstrable-journey.e2e.ts`** — the only fully-real P1 assertions. Confirm they prove the
   solution-goals P1 outcomes you care about (sites render, trails/codebook visualised), not just
   "a page loaded".
3. **Real-vs-mock split** — confirm *only* not-yet-built collaborators are mocked; every P1 service
   is hit for real in `E2E_MODE=real`.
4. **Gate hygiene** — Playwright is excluded from the unit gate (Vitest globs `src/**/*.spec.ts`;
   CI `angular` job runs lint + Vitest + build only). E2E runs in the integration stage.

## Known scope notes (by design, not gaps)
- P2/P3 ACs run against contract mocks **until** those services exist; the integration-tester drops
  each mock as its service is built (same specs, no assertion change).
- The **chatter** path is an accepted graceful-stub deviation (Enrichment not built; tracked).
- This suite is **authored + self-reviewed**; it is run as the P1 gate by the integration-tester
  against the live compose stack (real P1 + simulator-as-oracle) — that run, not this PR, is the
  pass/fail gate before P2.
