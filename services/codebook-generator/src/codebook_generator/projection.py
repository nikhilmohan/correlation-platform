"""Correlation-Engine ``trail-signatures`` projection (pure read transform).

Turns one persisted :class:`~codebook_generator.models.Scenario` into one-or-more
``TrailScenarioSignature`` items (spec criteria 21-24):

- ``expectedSymptoms`` == the scenario's ``predictedSymptoms`` (CE-facing alias; one truth).
- ``rootCauseAlarmType`` is the origin's own ``alarmType`` token (the predicted symptom whose
  ``managedObjectId == faultOriginObjectId``) — NOT the object-type ``faultOriginType``.
- per-``trailId`` fan-out: one signature per ``(scenario, trailId)`` pair.

No I/O; no compile/store change.
"""

from __future__ import annotations

from pydantic import BaseModel, Field

from .models import PredictedSymptom, Scenario
from .vocabulary import root_cause_alarm_type


class TrailScenarioSignature(BaseModel):
    """Frozen Correlation-Engine signature shape."""

    trailId: str
    scenarioId: str
    rootCauseAlarmType: str
    expectedSymptoms: list[PredictedSymptom] = Field(default_factory=list)


def project_scenario(
    scenario: Scenario, trail_id: str | None = None
) -> list[TrailScenarioSignature]:
    """Project one scenario to its per-trail signatures.

    Args:
        scenario: the source scenario.
        trail_id: when set, emit a single signature for that trail (only if the scenario's
            ``trailIds`` contains it); when None, fan out across each ``trailIds`` entry.
    """
    rca = root_cause_alarm_type(scenario)
    expected = list(scenario.predictedSymptoms)

    if trail_id is not None:
        if trail_id not in scenario.trailIds:
            return []
        trail_ids = [trail_id]
    else:
        trail_ids = list(scenario.trailIds)

    return [
        TrailScenarioSignature(
            trailId=t,
            scenarioId=scenario.scenarioId,
            rootCauseAlarmType=rca,
            expectedSymptoms=expected,
        )
        for t in trail_ids
    ]


def project_codebook(
    scenarios: list[Scenario], trail_id: str | None = None
) -> list[TrailScenarioSignature]:
    """Project every scenario in a codebook, fanning out per trail."""
    signatures: list[TrailScenarioSignature] = []
    for scenario in scenarios:
        signatures.extend(project_scenario(scenario, trail_id))
    return signatures
