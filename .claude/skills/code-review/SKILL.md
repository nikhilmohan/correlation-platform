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
3. **Run the gate the way CI + the container will — NOT the way that happens to pass locally.**
   This is the single most important rule (every post-approval miss traced back to it):
   - Run from a **clean checkout of the PR branch in an isolated worktree** — never the shared
     working tree (a concurrent branch switch corrupts it) and never a pre-warmed dev env.
   - **Build/install exactly as CI does**, from the artifacts in the repo — do NOT pre-install
     deps or use a cached toolchain that the repo doesn't provide:
     - Python: a **fresh venv + NON-EDITABLE install** (`pip install ./libs/event-model/python`
       then `pip install "./services/<svc>[dev]"`) — an editable/`-e` install or a pre-loaded
       venv **hides packaging bugs** (data files not bundled, source-tree-relative path
       resolution). Then run the lint+pytest gate from that install.
     - Java: use the **repo's `./gradlew`** (not a system/cached gradle) — if `./gradlew` won't
       bootstrap, that is itself a Blocker (e.g. a missing `gradle-wrapper.jar` swallowed by
       `.gitignore`). Run `./gradlew build`.
     - Angular: `npm ci` (not `npm install`) from the committed `package-lock.json`, then the
       lint+test+build the CI job runs.
   - **Report exactly what ran and where** (the install mode + toolchain source), so "tests
     pass" is verifiable, not asserted.
4. Post a report to the PR via `gh`, grouped Blocker / Major / Minor, ending in a verdict.

## Universal checklist
- Implementation matches `design.md` and satisfies **every acceptance criterion** in `spec.md`.
- **Contract adherence:** exact topic/payload names from `architecture.md`; **no silent new
  topic/payload/field**; depends on `libs/event-model` + topic contracts, not other services'
  code; **no domain logic leaked into the shared lib**.
- **Idempotency:** consumers dedupe on `eventId`/`alarmId`.
- **API contract:** if the service exposes HTTP, it **publishes OpenAPI 3.1** (`/openapi.json`
  + checked-in `openapi.json`); its own spec drives its contract/unit tests; clients to other
  services are built against the **producer's published OpenAPI**, not its source. The checked-in
  `openapi.json` must be **drift-guarded by a test that FAILS on drift** (regenerates ≠ guards).
- **Response semantics, not just shapes:** the **HTTP status codes + error bodies** the code
  returns must match what `spec.md`/`design.md` say (e.g. missing/invalid input → the spec's
  exact code: 400 vs 422 vs 404). A frozen acceptance criterion that names a status code is a
  contract — a divergence is a Major even when the happy path works. Verify with a test that
  asserts the *spec's* code, and that `openapi.json` declares it.
- **Integration points:** outbound dependencies are **config-driven and mock|real switchable**
  (mock from the collaborator's OpenAPI in unit tests; real in integration) — **no hard-coded
  collaborator URLs**.
- **DLQ / error handling:** poison messages → `<topic>.dlq`, not dropped.
- **Observability:** `/health`, `/metrics`, structured logging, config-from-env (no secrets).
- **Tests:** meaningful and **passing**, coverage at/above the gate; criterion→test traceable.
- **No cross-service coupling.** Permissive licenses only.
- **Deployable, not just present (verify, don't eyeball):**
  - **Dockerfile is real and BUILDS** — not a scaffold/placeholder with commented-out
    install/CMD. Run `docker build` (context per the file) when Docker is available; confirm it
    installs the service + its deps, runs non-root, EXPOSEs the health/metrics port, and the
    entrypoint actually starts the service. A stub Dockerfile is a Major.
  - **README is real** — run/config/migrate/observability, not a "TBD/once it lands" stub.
  - **docker-compose service entry exists** in the root `docker-compose.yml` (every service
    needs one — a built service with no Compose entry can't be brought up). Check the **host
    port does not collide** with an already-merged service, `depends_on` the right
    infra/collaborators, and that a service whose design splits processes (e.g. consumer vs
    read-API) is modelled faithfully. A missing/colliding Compose entry is a Major.
- **Clean-clone bootstrap:** the service builds from a fresh clone with no out-of-band setup —
  no data files missing from the package/wheel, no build artifact swallowed by `.gitignore`
  (e.g. `gradle-wrapper.jar`), no source-tree-relative path that breaks once installed/containerized.

## Per-cohort checklist (test frameworks are fixed — flag substitutions)
- **Python:** `ruff check` + `black --check` clean; type hints; **no hard-coded thresholds**
  (params from Knowledge/env); tests use **pytest**; `pytest --cov` ≥ 80% **run against a
  non-editable install** (per Procedure §3). **Packaging:** any non-`.py` runtime asset
  (migrations SQL, vendored schemas, templates) must be declared `package-data` in
  `pyproject.toml` **and** resolved via `importlib.resources` (not `Path(__file__).parents[N]`)
  — verify the asset is present in `site-packages` after the non-editable install and that the
  migrate/startup path finds it off the wheel. Linters pinned (no floating `>=`) so a new
  release can't fail CI.
- **Java:** Spring idioms; constructor injection; explicit **idempotent** Kafka config; tests
  use **JUnit 5** (Testcontainers where integration is needed); the **repo's `./gradlew`
  bootstraps** (wrapper jar committed) and `./gradlew build` (JaCoCo gate) is green.
- **Angular:** standalone components; typed (no `any`); signals; WCAG 2.1; unit/component tests
  use **Vitest + TestBed** (not Karma/Jest); **Playwright** used for E2E only (not unit);
  `package-lock.json` committed so **`npm ci`** works; `npm run lint`, `npm test`, `npm run build`
  green.

## Output
A report on the PR grouped **Blocker / Major / Minor**, ending in exactly one verdict:
**APPROVE** or **CHANGES REQUESTED**.

## Loop
Code↔review is capped at **3 rounds**. If not APPROVE-able after three, escalate to a human.
