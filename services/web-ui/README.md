# web-ui

**Cohort:** Angular 20
**Owned datastore:** — (talks to service APIs)

One app, four modules: topology/trails, pattern review + XAI, config, correlation stats.

> Scaffold stub. See `spec.md` (contract) and `design.md` (how) for detail — both TBD until
> authored via the spec/design workflow. Build via the cohort dev agent on `build/web-ui`.

**Testing:** Vitest + Angular TestBed for unit/component tests (mock backends from producers'
OpenAPI specs); **Playwright** for UI E2E (owned here, run against the integration stack).

## Run
_TBD — added once the implementation lands. Standard: `/health` + `/metrics`, config from env,
Docker Compose entry._
