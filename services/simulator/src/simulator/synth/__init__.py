"""P3 topology-and-pattern-driven live alarm synthesis (additive mode).

Reads the already-deployed topology + trails + approved patterns from the running services'
published APIs and synthesizes a wall-clock-paced ``alarms.live`` stream grounded in those real
objects/patterns, targeting a configurable ~60-70% pattern-aligned auto-correlation + RCA rate.
Regenerates nothing (no topology build, no pattern mining, no ``POST /topology/snapshots``) and
introduces no contract change (no new topic, no event-model change, no collaborator OpenAPI change).
"""
