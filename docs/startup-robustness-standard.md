# Platform Startup-Robustness Standard

**Status: proposed — contract-style addition awaiting human approval.** This is a cross-service
**runtime convention** referenced from `docs/architecture.md` ("Shared infrastructure conventions
(build + runtime)"). It is normative: every service's startup MUST conform. Because it is referenced
from `architecture.md` it is treated like a contract change — it requires human approval (see
`.claude/agents/CONVENTIONS.md` → contract-change procedure) before it is binding.

## Why this exists (the bug class)

The platform must bring **infra + every service** up **consistently** and within a **predictable,
bounded time window** from **clean volumes** (`docker compose down -v` then `up`). A class of
cold-start fragility was repeatedly latent because **reused Docker volumes carry prior state** (a
space already exists, migrations already ran, a storaged host is already ONLINE), so the fragile path
is never taken on a warm volume — and is **only exposed on clean volumes**. Issue **#204** (compose
`graphd → storaged: service_started`) was the **infra-layer** sibling of this class; this standard
fixes the **service-code layer** of the same class.

The failure modes this standard eliminates:

- **"Container up" mistaken for "dependency ready."** A dependency's container being *started* (or a
  row merely *existing*) is not readiness. A freshly `ADD HOSTS`-ed NebulaGraph storaged host is
  *listed* but `OFFLINE` for ~10-30s before it reaches `ONLINE`; a Postgres container accepting TCP is
  not the same as its schema being migrated; a Kafka broker reachable is not the same as the topic
  catalog existing.
- **One-shot bootstrap that latches DOWN forever.** A bootstrap that runs **once** in a try/catch and,
  on any exception, sets readiness to DOWN and **never retries**, turns a transient cold-start race
  into a **permanent** outage — readiness never recovers, even after the dependency becomes ready.
  This is the "useless solution" failure mode.
- **Unbounded or unpredictable startup.** Retrying forever with no deadline makes the startup window
  unpredictable (orchestration cannot reason about it); no retry at all makes it brittle.
- **Non-idempotent bootstrap.** A bootstrap that errors or double-applies on a second run breaks
  restart and self-heal.

## The standard (normative — every service MUST conform)

Every service's startup sequence (and any background re-attempt) MUST satisfy all of the following.

### S1 — Wait for each dependency to be **actually READY**, by a true-readiness predicate

A dependency is "ready" only when its **true readiness predicate** holds — never "container up,"
"port open," or "a row exists." The predicate per dependency **type**:

| Dependency type | READY predicate (NOT this weaker check) |
|---|---|
| **Relational DB (PostgreSQL)** | The service's **own schema migrations are applied** (Flyway/Alembic/yoyo history at the head) — not "TCP connect succeeds." |
| **Kafka** | Broker reachable **and** required topics exist (the platform provisions topics via `kafka-init`; auto-create is off) — not "bootstrap host resolves." |
| **NebulaGraph storaged** | The storaged host shows **`Status ONLINE`** in `SHOW HOSTS` — **not** "a row exists" / `rowsSize() > 0` (a host can be listed but `OFFLINE`). |
| **NebulaGraph space** | `USE <space>` succeeds (the space has propagated to storaged and is usable) — not "`CREATE SPACE` returned." |
| **An HTTP dependency** | Its `/health` (or the specific operation's) returns **200** — not "DNS resolves" / "connection opens." |

Readiness predicates are **polled**, never assumed from the success of a prior step (e.g. `ADD HOSTS`
returning does **not** mean the host is `ONLINE` — that must be polled).

### S2 — Bounded retry with backoff and an explicit **deadline** (predictable window)

Transient cold-start failures (dependency not yet ready) MUST be retried with **backoff** (e.g.
fixed or exponential with a cap) **up to an explicit deadline** — a **configurable maximum startup
window**, never unbounded. On reaching the deadline the attempt **fails this round** (so it can be
re-attempted per S3) and records why; it does not hang forever and does not give up permanently.

- **Target window (default):** a service reaches readiness within **120s** of its dependencies
  becoming available on clean volumes; the **hard overall deadline default is 180s**. Both are
  **config from env** (S5). These bound the predictable window the orchestration relies on.
- Distinguish **transient** (retry: dependency not yet ready, connection refused, space not yet
  usable, storaged not yet ONLINE) from **fatal** (do not retry the same way: malformed config,
  authentication rejected, a genuinely invalid migration) — fatal failures fail fast with a clear
  message rather than burning the whole deadline.

### S3 — Self-healing readiness (never latch DOWN forever)

A failed bootstrap MUST be **re-attempted** — a **background re-attempt loop** runs until the service
is ready **or** an overall deadline/attempt-cap is reached — and MUST NOT set readiness DOWN once and
stop. The **readiness probe reflects true current state** and can therefore **recover**: it flips to
UP automatically once a later attempt succeeds. Specifically:

- The startup bootstrap runs; on failure it schedules a **background retry** (bounded, with backoff
  per S2), it does **not** one-shot-latch.
- `/health` **readiness** returns DOWN while bootstrap is incomplete and UP **only** once the
  dependency readiness predicates (S1) hold; if a dependency flaps, readiness reflects the current
  truth (it can go DOWN and back UP). Liveness stays UP (the process is alive); only readiness gates
  traffic.
- A service MUST NOT accept work (ingest, consume) until readiness is UP.

### S4 — Idempotent bootstrap (re-run = no-op)

The bootstrap MUST be safe to run repeatedly — on a fresh volume, on a warm volume, on every retry,
and across restarts — with **no errors and no double-apply**. Use `IF NOT EXISTS` / `CREATE SCHEMA IF
NOT EXISTS` / idempotent migrations / `ADD HOSTS` only when the host is not already ONLINE. Two
concurrent instances bootstrapping the same shared infra must converge, not corrupt.

### S5 — Timeouts / retries / deadline are **config from env** (no hard-coded thresholds)

All readiness timeouts, retry counts, backoff intervals, and deadlines MUST be **config from env**
(per the CLAUDE.md "no hard-coded thresholds" rule), with sane defaults documented. A service must be
tunable per environment (CI vs. a slow cold-start machine) without code change.

### S6 — A clean-volume cold-start test (the test that would have caught it)

Each service MUST have a test that starts it against its **real** dependencies from **empty volumes**
(Testcontainers / a fresh ephemeral stack) and asserts it **reaches readiness within the deadline**.
A **mock-session / stubbed-dependency unit test cannot catch this class** — it never exercises the
real "ADDed-but-OFFLINE storaged," the real migration apply, or the real space-propagation delay, so
it gives **false confidence**. The cold-start test must use the real dependency, from empty volumes.

## Reference shapes

- **ROBUST (the "good" shape) — already conforming:**
  - **knowledge**: Flyway + idempotent seed (migrations applied = ready; re-run no-op).
  - **trail-builder & codebook-consumer**: idempotent migrations that **abort-on-fail**, plus
    **on-demand HTTP clients with bounded retry**.
- **Self-heal pattern:** run bootstrap on startup; on failure, schedule a bounded background retry
  (backoff + deadline); readiness probe reflects true current state and flips UP when a retry
  succeeds; never one-shot-latch DOWN.

## Conformance gaps tracked (apply or follow up)

| Service | Gap class | Status |
|---|---|---|
| **topology** | `ADD HOSTS` then immediate `CREATE SPACE` (no wait for storaged `ONLINE`); readiness predicate was "a row exists" not `Status ONLINE`; one-shot bootstrap latches readiness DOWN forever (no self-heal). | **Fixed in this round** — `services/topology/design.md` (NebulaGraph bootstrap + self-healing readiness + clean-volume cold-start test). |
| **codebook-api** | RACE — the read-API process can serve before the codebook consumer process completes its migrations. | **Follow-up** — read API must gate readiness on migrations-applied (S1 DB predicate + S3) before serving. Tracked for the codebook design round. |
| **simulator** | NO-RETRY — the CLI oracle fails on first Kafka/topology `produce()` with no retry. | **Follow-up** (lower criticality — runs after the stack is up) — must honor the same wait-for-ready + bounded-retry contract (S1/S2) on its first produce/upload. Tracked for the simulator round. |

(Follow-ups are flagged here, not redesigned in this round; the standard covers them so each service's
next design/build round applies it.)
