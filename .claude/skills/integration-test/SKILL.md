---
name: integration-test
description: >-
  On-demand end-to-end integration test on the integration branch for the Alarm Correlation
  Platform. Brings up Compose, drives the Simulator's labeled scenarios as oracle, asserts the
  topic chain + one-incident-per-root-cause + metric thresholds + contract conformance, writes
  a report and files labeled issues. Read-only on source. Loop cap 5 rounds, then escalate.
---

# Skill: `integration-test` — end-to-end test on `integration`

A **read-only** end-to-end run on the `integration` branch. Drive the Simulator's ground-truth
scenarios through the live topic chain and assert outcomes. Report and raise issues only —
never edit service source. You may write only under `reports/integration/`.

## Inputs
- `docs/architecture.md`, `docker-compose.yml`, `services/simulator/spec.md` (labeled
  scenarios + metric thresholds — the oracle).

## Procedure
1. Bring up the stack: `docker compose up -d` (infra + built services). Wait until every
   service `/health` is ready. Run only one stack at a time (or distinct ports).
2. Drive the Simulator's labeled scenarios: fiber-cut, line-card-fault, port-fault, plus ≥3
   noise classes, in `history` and `live` modes as the scenario requires.
3. Assert the chain and outcomes (below).
4. Write the report; file one labeled GitHub issue per failure; tear the stack down.

## Assertions
- **Topic chain:** events flow correctly topology.raw → topology.changed → trails.built →
  codebook.generated, and alarms.* → alarms.enriched(.live) → transactions.clean →
  patterns.mined → patterns.discovered → patterns.approved → correlation.results.
- **One incident per root cause:** the correct root-cause alarm is tagged and the rest are
  children — including a **partial match** (one trap dropped).
- **Metric thresholds** (from the Simulator spec): RCA accuracy, alarm-reduction ratio,
  noise-filter effectiveness, pattern quality.
- **Contract conformance:** envelope + payloads + `schemaVersion` on the wire; each HTTP
  service serves its **OpenAPI 3.1** at `/openapi.json` and its responses conform to it.
- **Real integration points:** services run with integration points set to **real** (live
  Compose addresses), not mocks — confirm collaborators are reached over the wire.

## Output
- `reports/integration/<timestamp>.md` — pass/fail per scenario and metric, with the numbers.
- One GitHub issue per failure via `gh`, labeled `service:<owner>` for the owning dev agent.

## Loop
The integration fix loop is capped at **5 rounds**. If still failing after five, escalate to a
human. You raise issues; the owning dev agent fixes; you re-run.
