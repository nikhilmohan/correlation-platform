# Development Workflow

How we build the Alarm Correlation Platform: AI-native, spec-driven development with human
gates. The Solution Design is the source of truth for *what*; this file is the *how*.

> ## ⚠️ Prerequisite: GitHub CLI authentication
> Remote operations (creating the remote repo, pushing, opening PRs, configuring branch
> protection) require a working `gh` login. If `gh auth status` reports a failing token,
> resolve it before those steps:
> ```bash
> unset GH_TOKEN          # if an invalid token env var is set
> gh auth login           # interactive browser/device login
> gh auth status          # confirm success
> ```
> All **local** scaffolding and per-service development proceeds without remote auth.

## Toolchains (pinned)

| Cohort | Runtime | Pinned version | Tooling |
|---|---|---|---|
| Python | CPython | **3.13** | ruff, black, pytest (`--cov-fail-under=80`) |
| Spring Boot | Temurin JDK | **17** | Gradle, JUnit 5, JaCoCo |
| Angular | Node | **24** | npm, Angular CLI, lint, karma/jest |

CI (`.github/workflows/ci.yml`) and the per-service Dockerfiles are authoritative. Align your
local toolchain to these versions (`sdkman`, `pyenv`, `nvm`) before service work. Spark
(`pattern-miner`) is **container-only** — not installed locally.

## Branch model

- `main` — protected; release.
- `integration` — protected; services merge here; integration tests run here.
- `<svc>` — the service's working branch; spec, design, and code land here progressively.
- short-lived working branches → PR into `<svc>`: `spec/<svc>`, `design/<svc>`, `build/<svc>`.

**Parallelism is across services; sequencing is within a service.** Each service walks
`spec → design → code → review → test` in order. Many services can run that pipeline at the
same time, each on its own `<svc>` branch.

To run services in parallel, give each concurrently-active service its own checkout off its
service branch (which already carries the approved spec + design):
```bash
git worktree add ../wt-<svc> <svc>     # branch off <svc>, not the default branch
# run the cohort dev agent + code-reviewer inside ../wt-<svc>
git worktree remove ../wt-<svc>        # after it merges
```
The streams **rejoin** only at the merge into `integration` and the integration test run.

## The flow & the gates

```
spec/<svc>  --(HUMAN GATE: approve spec PR → <svc>)-->
design/<svc> --(HUMAN GATE: approve design PR → <svc>; every criterion mapped to a test)-->
build/<svc> --[code-reviewer APPROVE, ≤3 rounds]--[AUTOMATED GATE: CI green]--
            --(HUMAN GATE: approve build PR → <svc>)-->
<svc>       --(HUMAN GATE: approve <svc> PR → integration)-->
integration --[integration-tester: scenarios pass thresholds, ≤5 rounds]--
            --(HUMAN GATE: approve integration → main)--> Released
```

| Stage | Transition | Gate | Pass condition |
|---|---|---|---|
| Spec | `spec/<svc>` → `<svc>` | **Human** | Spec approved; open questions resolved |
| Design | `design/<svc>` → `<svc>` | **Human** | Design approved; every acceptance criterion mapped to a test |
| Code review | within `build/<svc>` | **Automated** | code-reviewer verdict APPROVE (cap 3 rounds → escalate) |
| Code CI | `build/<svc>` → `<svc>` | **Automated** | Lint + unit tests + build + coverage all green |
| Code merge | `build/<svc>` → `<svc>` | **Human** | CI green **and** reviewer APPROVE **and** human approval |
| Service → integration | `<svc>` → `integration` | **Human** | Service meets all its acceptance criteria; CI green |
| Integration test | on `integration` | **Automated (on demand)** + human triage | Scenarios meet thresholds; failures → labeled issues (cap 5 → escalate) |
| Release | `integration` → `main` | **Human** | All services integrated; integration suite green |

## Non-negotiable process rules

1. **Contract-first.** Build and freeze `libs/event-model` before any service. Services depend
   on it and the topic contracts in `docs/architecture.md` — never on each other's code.
2. **A new topic / payload / field is a contract change.** Never made silently by a service
   agent — it requires an `architecture.md` update **and** human approval first.
3. **Separation of duties.** Dev agents write code; the code-reviewer and integration-tester
   are **read-only** — they report and raise issues; the dev agent fixes.
4. **Specs derive, they don't invent.** The product-analyst extracts each spec from the
   Solution Design / `architecture.md`; ambiguities go to "Open questions", not guesses.
5. **Traceability.** Every spec acceptance criterion → a unit test → checked at review →
   exercised at integration.
6. **Subagents are one level deep.** They cannot call each other; the main session and the
   human at PR gates are the orchestrator.
7. **Bounded loops.** Code↔review caps at 3 rounds; integration fix caps at 5 — then a human.

## Agents & skills

Project-local agents live in `.claude/agents/`; skills in `.claude/skills/`.

| Agent | Role |
|---|---|
| `product-analyst` | Uses the `spec` skill. Derives a service spec; opens `spec/<svc>` PR. |
| `designer` | Uses the `design` skill. Turns an approved spec into a buildable design. |
| `java-dev` | Spring Boot cohort. Tests first, then code. |
| `python-ai-dev` | Python cohort. pytest first; ruff/black clean; no hard-coded thresholds. |
| `angular-dev` | `web-ui` (Angular 20). Standalone, typed, tested. |
| `code-reviewer` | Read-only. Reviews the PR diff; posts report + verdict. |
| `integration-tester` | Read-only. Runs Compose + Simulator scenarios; raises labeled issues. |

## Kickoff sequence

1. Build the contract (`libs/event-model`): `@product-analyst` → human-approve spec →
   `@designer` → human-approve design → `@python-ai-dev` (+ Java binding) → review → CI →
   merge. **Freeze it.**
2. Seed each service spec: `@product-analyst write the spec for <svc>`. Human-approve.
3. Design each service: `@designer design <svc>`. Human-approve.
4. Build in parallel (worktree per service): dev agent → `@code-reviewer` → APPROVE → CI →
   merge `build/<svc>` → `<svc>`.
5. Integrate in roadmap order (skeleton → learning slice → real-time slice): merge each
   ready `<svc>` → `integration`.
6. Integration test on `integration`: `@integration-tester run integration tests`; fix
   labeled issues; repeat until green.
7. Release: human-approve `integration` → `main`.
