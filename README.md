# AI/ML Alarm Correlation Platform — Core IP MVP

An event-driven, topology-grounded platform that correlates Core IP network alarms into
incidents with a tagged root cause. It combines a model-derived **codebook** (forward
propagation over the network topology) with **data-mined patterns** (DBSCAN noise filtering +
PrefixSpan sequence mining), keeps a human in the loop for pattern approval, and performs
real-time correlation of live alarms. Built as Docker-based microservices on **Apache Kafka**,
**PostgreSQL**, and **NebulaGraph** (topology graph).

## Architecture at a glance

- **Python cohort** (`networkx` / scikit-learn / PySpark): `simulator`, `trail-builder`,
  `codebook-generator`, `noise-filter`, `pattern-miner`.
- **Spring Boot cohort**: `topology` (owns the NebulaGraph topology graph), `knowledge`, `enrichment`,
  `pattern-manager` (owns the Pattern Store), `correlation-engine`.
- **Angular 20**: `web-ui` (topology/trails · pattern review/XAI · config · stats).
- **`libs/event-model`**: the shared canonical event library (Java + Python/Pydantic bindings
  from one JSON Schema) — the contract every service depends on. **Built first, then frozen.**

See [`docs/architecture.md`](docs/architecture.md) for the contract (event model, Kafka
topics, data stores, invariants) and the per-service `spec.md` / `design.md` files for detail.

## How we build it

This project follows an **AI-driven, spec-driven development** workflow:
`spec → design → build + test + review → integration`, gated by human PR approvals and
automated CI. The full workflow, branch model, and gates are in
[`README-workflow.md`](README-workflow.md). Always-loaded agent context is in
[`CLAUDE.md`](CLAUDE.md).

## Getting started

1. Read [`README-workflow.md`](README-workflow.md) — **note the GitHub-auth prerequisite**.
2. Bring up the integration infra: `docker compose up -d kafka postgres nebula-metad nebula-storaged nebula-graphd`.
3. Toolchains are pinned to **Java 17 · Python 3.13 · Node 24** (see CI and Dockerfiles).

## Deploying & operating (remote VM)

See [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) — prerequisites (≥16 GB VM), `docker compose up`,
running the **P1 → P2 → P3** phases, seed/bootstrap data, the remote-readiness audit (ports, env,
no code changes needed), and **extending to a new domain**.
