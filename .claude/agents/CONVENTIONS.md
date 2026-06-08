# Agent conventions (shared)

Cross-cutting mechanics every subagent follows. Each agent's own file links here so these
rules live in one place. **Read this alongside your agent file.** (This is reference for the
agents; it is not itself an agent.)

## Escalation — how to "raise it" / "flag the human"
When an agent is told to raise, flag, or escalate, do it **traceably**, never as prose that
vanishes:
- **Ambiguity / blocker / missing prerequisite:** open a GitHub issue via `gh`, titled
  `[<svc>] <short summary>`, labeled `question` (+ `service:<svc>`). State what you need and
  why you stopped. Link it from the relevant PR. Then **stop** — do not guess past it.
- **Inside an open PR** (e.g. a review question, an unmapped criterion): post it as a PR
  comment via `gh pr comment`, so it sits on the artifact under review.
- **Loop-cap reached (escalation):** open an issue labeled `escalated` (+ `service:<svc>`)
  summarizing the rounds tried and the remaining blocker; stop the loop.

## Loop-round counting (code↔review cap 3, integration-fix cap 5)
Rounds are not in your head — **reconstruct them from durable state** before acting:
- Count prior `code-reviewer` review comments / `integration-tester` report runs on the same
  PR/branch via `gh pr view <n> --comments` (or the `reports/integration/` history). The Nth
  pass is round N.
- At the cap, do **not** start another round — escalate per above.

## Contract-change procedure (a new topic / payload / field / OpenAPI surface)
A contract change is **never** made silently inside a service. The procedure is:
1. Whoever detects it (analyst → Open question; designer/dev → flag) **stops** and escalates
   per above. Detection signals: the exact topic/payload/field/operation you need is **absent
   from `docs/architecture.md` and the `libs/event-model` binding**.
2. A human decides. If approved, the change to `docs/architecture.md` (and, if it touches the
   event model, `libs/event-model`) is made as its **own PR into `main`** and re-frozen —
   **before** the dependent design/code proceeds. Draft that PR only when a human asks you to.
3. Dependent work resumes against the updated, merged contract — not before.

## Contract preconditions (before any design or build)
- `libs/event-model` exists, is built, and is **frozen** (its binding for your cohort is
  importable). If it is not, **stop and escalate** — do not invent payloads to work around it.
- Use the **exact** names from `docs/architecture.md` and the event-model binding.

## Self-verification before opening a PR
Before you open or update a PR, confirm and state in the PR body:
- The cohort gate is **green locally** (Java: `./gradlew build`; Python: `ruff` + `black` +
  `pytest --cov`; Angular: `npm run lint` + `npm test` + `npm run build`).
- Every targeted acceptance criterion has a passing test.
- No silent contract change; no cross-service source coupling.

## PR & commit conventions
- Branch: `spec/<svc>`, `design/<svc>`, or `build/<svc>` → PR into `<svc>` (contract-change
  PRs target `main`).
- PR title: `<type>(<svc>): <summary>` (`spec`/`design`/`build`/`docs`/`fix`).
- PR body: what changed, which acceptance criteria are covered, local gate result, and any
  open questions/blockers (linked issues).
- **Never self-merge.** PR merges are human gates. After your work passes, your closing action
  is to report status (reviewer verdict + CI + "awaiting human merge"), not to merge.
