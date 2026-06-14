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
| `p1-demonstrable-journey.e2e.ts` | **AC 33.1/.2/.3 + trail-highlight**, solution-goals **P1-1/2/4/6** | Split into 4 granular tests (see "AC 33 granular breakdown" below). Each test name carries its `AC 33.x` id; real mode is pinned to the simulator's seeded geo catalogue. | **REAL P1** (Topology/Trail Builder/Codebook/Knowledge) |
| `config.e2e.ts` | **AC 43** (+ AC 42 cross-check) | Editing a model param + saving **confirms a new persisted version** read back from Knowledge; an out-of-bounds value **blocks submit** with a validation error and makes **no API call** | **REAL** (Knowledge) |
| `dashboard-and-cross-nav.e2e.ts` | **AC 5, 25** | Dashboard shows **non-zero incident count + non-zero alarm-reduction ratio** from CE stats; KPI → incidents list → incident-detail navigation completes and renders | mocked (CE) |
| `streaming.e2e.ts` | **AC 13** | Streaming view ingests alarms and **renders their lifecycle state** (open/in-progress/correlated/cleared) from Alarm Manager | mocked (Alarm Mgr) |
| `incident-detail.e2e.ts` | **AC 17** (+ AC 23 deep-link) | **Direct deep link** to `/incidents/<id>` renders the root-cause alarm **+ ≥1 child alarm** | mocked (CE/Alarm Mgr) |
| `noise-stats.e2e.ts` | **AC 20** | Noise-stats view renders **≥1 run row with non-zero alarmsIn** from the Noise Filter run-stats API | mocked (Noise Filter) |
| `patterns.e2e.ts` | **AC 39** | Operator **approves a draft pattern**; a subsequent read returns it as **approved** from Pattern Manager (stateful mock proves the round-trip) | mocked (Pattern Mgr) |
| `correlation-stats.e2e.ts` | **AC 46, 49** | Stats incidents tab shows **≥1 incident with a tagged root cause + child alarms**; alarm-lifecycle view shows **≥1 correlated alarm with a non-empty incident association** | mocked (CE/Alarm Mgr) |

**14 tests across 8 spec files.** Each test name carries its AC id. `npm run e2e:list` enumerates them.

## AC 33 granular breakdown — the one fully-REAL P1 end-to-end criterion

AC 33 (topology → trails → codebook, visualized in the UI) is split into granular, independently
meaningful tests, one concern each. Real-mode assertions are pinned to the simulator's grounded geo
catalogue (`services/simulator/src/simulator/domains/coreip/geo_catalogue.py`); the `p1-demo`
profile runs `SITE_COUNT=10` and seeds the **first 10** of the 12-entry catalogue. The anchors live
in one place — `e2e/support/geo-anchors.ts` — so the seed/UI contract is reviewable and drift is a
type-check failure, not a silent skew.

**Seeded geo anchors (real mode, p1-demo SITE_COUNT=10):**

| siteId | name (marker label) | region |
|---|---|---|
| LON-01 | London Docklands | UK-South |
| MAN-01 | Manchester Central | UK-North |
| AMS-01 | Amsterdam Zuidoost | EU-West |
| FRA-01 | Frankfurt am Main | EU-Central |
| PAR-01 | Paris Aubervilliers | EU-West |
| MAD-01 | Madrid Alcobendas | EU-South |
| MIL-01 | Milan Caldera | EU-South |
| STO-01 | Stockholm Kista | EU-North |
| DUB-01 | Dublin Citywest | IE |
| WAW-01 | Warsaw Wola | EU-East |

(The full catalogue has 12; ZRH-01 / VIE-01 are the headroom entries NOT in the p1-demo first-10.)
The UI labels markers by **name**, so tests locate them by name (e.g. "London Docklands"). The
**drill-in anchor** for 33.2/33.3 is **LON-01 / London Docklands** — always first in the first-10
set, so guaranteed present to drill into. The **spot-check anchor** in 33.1 is **FRA-01 / Frankfurt
am Main**.

| Test | Concern | Acceptance criteria (real mode) | Mock-mode relaxation | Screenshot |
|---|---|---|---|---|
| **AC 33.1** sites render on the geo map | Geo-site map lists the seeded PoP sites | ≥ **10** markers AND the named anchors **LON-01 "London Docklands"** and **FRA-01 "Frankfurt am Main"** present by name; the LON-01 marker carries its region (UK-South). Map canvas has the accessible ARIA label (AC 52). | ≥ **1** marker (in-app fixture ships 3 generic PoPs — no live stack needed) | `ac-33-1-geo-map.<mode>.png` |
| **AC 33.2** site-specific topology | Drill into a specific named site → its device graph | Click **LON-01 "London Docklands"** → site-graph route renders ≥ **1 node** AND ≥ **1 edge**; selecting a device marks it `aria-pressed` and the attribute panel shows a real attribute (`managedObjectId:` row). | Click first marker (interceptor returns the same `SiteObjectsDto` for any siteId) | `ac-33-2-site-topology.<mode>.png` |
| **AC 33.3** trails render | Trail-cluster overlay on the site graph | On LON-01, the **Trail clusters** overlay surface renders; when trails exist they are listed as **area-bounded clusters** (each row shows its own `(N members)` count, i.e. multiple bounded trails, not one giant trail). Empty snapshot → explicit "No trails for this snapshot" fallback. | Same overlay surface; fixture trails or empty-state both valid | `ac-33-3-trails-overlay.<mode>.png` |
| **AC 33** trail-member highlight | Selecting a trail-member device highlights its trails | On LON-01, selecting a node highlights every trail it belongs to (`getTrailsForObject`); with no membership the click still succeeds and the view does not crash (heading-still-visible guard). | Same | — (interaction, not layout; covered by 33.3 capture) |

### Per-test screenshots (CI artifacts)

The AC 33.x tests each call `shot(page, testInfo, '<name>')` (`e2e/support/screenshots.ts`), which:
- writes a **full-page** screenshot to a deterministic dir — **`services/web-ui/e2e/__screenshots__/<name>.<mode>.png`** — with a stable filename per test (mode-suffixed so real and mock captures never collide), and
- attaches the same image to the **Playwright HTML report** (`e2e/.report/`, also produced in CI via the `html` reporter).

Filenames are predictable for the integration step to find and upload:
`ac-33-1-geo-map.real.png`, `ac-33-2-site-topology.real.png`, `ac-33-3-trails-overlay.real.png`.
`e2e/__screenshots__/` is **gitignored** — screenshots are CI artifacts, never committed binaries.
The integration-tester runs `E2E_MODE=real npm run e2e` against the live P1 stack and uploads
`services/web-ui/e2e/__screenshots__/**` plus the HTML report (`services/web-ui/e2e/.report/**`) as
a GitHub Actions artifact, so the map view / site topology / trails overlay are reviewable on the PR.

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
