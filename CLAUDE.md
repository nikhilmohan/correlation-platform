# CLAUDE.md — AI/ML Alarm Correlation Platform (Core IP MVP)

Always-loaded context for every session. Read this first. Before any design or build work,
also read `docs/architecture.md` and the target `services/<svc>/spec.md` and `design.md`.

## What this is
An event-driven, topology-grounded platform that correlates Core IP network alarms into
incidents with a tagged root cause. Docker-based microservices on Apache Kafka, PostgreSQL,
and Apache AGE. Full detail: the Solution Design doc; per-service detail: each service's
`spec.md` (contract) and `design.md` (how).

## Repo layout
libs/event-model (contract — build first) · services/<svc>/{spec.md,design.md,src} ·
docs/architecture.md · .claude/skills · .claude/agents · reports/integration ·
README-workflow.md

## Cohorts (language follows the workload)
Python: simulator, trail-builder, codebook-generator (networkx), noise-filter (scikit-learn),
pattern-miner (PySpark). Spring Boot: topology, knowledge, enrichment, pattern-manager,
correlation-engine. Angular 20: web-ui.

## Golden rules (invariants — do not violate)
- Contract-first: `libs/event-model` is the single source of truth for the envelope, payloads
  and the `managedObjectId` scheme; build it first; depend on it + topic contracts, never on
  another service's code.
- A new topic/payload is a contract change → update `docs/architecture.md` + human approval.
- Single owners: Topology Service is the only thing touching Apache AGE; Knowledge Service is
  the only home for authored templates/policy/params; Pattern Manager is the only owner of
  pattern state/lifecycle.
- Idempotency: Kafka is at-least-once; consumers dedupe on `eventId`/`alarmId`.
- Every service: `/health` + `/metrics`, structured JSON logs, config from env, Dockerfile +
  Compose entry, README.

## Coding conventions
- Python: ruff + black, type hints, pytest, no hard-coded thresholds (Knowledge/env).
- Java: Spring Boot, Gradle, JUnit 5, constructor injection, explicit idempotent Kafka config.
- Angular: Angular 20 standalone components, typed, lint clean, unit tests.

## Test frameworks (standard — do not substitute)
- Java services: **JUnit 5** (unit/contract); Testcontainers for integration.
- Python services: **pytest** (unit/contract).
- web-ui: **Vitest + Angular TestBed** for unit/component tests; **Playwright** for UI E2E
  (owned by web-ui, run against the integration stack). Playwright is E2E only — never the
  unit-test runner.

## Definition of Done
Implementation satisfies every acceptance criterion in `spec.md`, each maps to a passing unit
test, CI is green, and the code-reviewer has approved.

## Workflow
spec → design → build+test+review → integration, gated by human PR approvals (spec, design)
and CI + reviewer + human (code). Detail in `README-workflow.md`. Subagents are one level
deep; the main session + human are the orchestrator.

## Local toolchain (pinned to installed versions)
This repo pins toolchains to the versions installed on the build machine: **Java 17**,
**Python 3.13**, **Node 24**. CI (`.github/workflows/ci.yml`) and the per-service Dockerfiles
are the authoritative pins (`eclipse-temurin:17-jdk`, `python:3.13-slim`, `node:24`). Spark
(pattern-miner) is container-only — not installed locally.
