"""Alarm-type vocabulary handling (OQ-2 resolved — canonical ``alarmType``).

Signature alarm-type tokens are read straight through from the Knowledge propagation
templates' ``trigger.alarmType`` / ``effect.alarmType`` and the fault-origin type's
``originAlarmType`` — no remapping to X.733 ``eventType`` / ``probableCause``. As a
belt-and-braces producer guarantee, every ``predictedSymptoms[].alarmType`` and the derived
``rootCauseAlarmType`` is validated against the domain's fetched ``alarmTypeVocabulary``
token set (spec criteria 22, 25). A token outside the set fails the compile.
"""

from __future__ import annotations

from collections.abc import Iterable

from .models import Scenario


class VocabularyError(ValueError):
    """Raised when a signature carries an alarm-type token outside the domain vocabulary."""


def validate_scenarios(scenarios: Iterable[Scenario], vocabulary: Iterable[str]) -> None:
    """Assert every symptom ``alarmType`` is a member of ``vocabulary``.

    Args:
        scenarios: compiled scenarios to validate.
        vocabulary: the domain's ``alarmTypeVocabulary`` token set.

    Raises:
        VocabularyError: with the offending scenario + token, when any
            ``predictedSymptoms[].alarmType`` is not in ``vocabulary``.
    """
    allowed = set(vocabulary)
    for scenario in scenarios:
        for symptom in scenario.predictedSymptoms:
            if symptom.alarmType not in allowed:
                raise VocabularyError(
                    f"alarmType {symptom.alarmType!r} (scenario {scenario.scenarioId!r}, "
                    f"object {symptom.managedObjectId!r}) is not a member of the domain "
                    f"alarmTypeVocabulary {sorted(allowed)!r}"
                )


def root_cause_alarm_type(scenario: Scenario) -> str:
    """Derive a scenario's root-cause alarm-type token.

    It is the ``alarmType`` of the predicted symptom whose ``managedObjectId`` equals the
    scenario's ``faultOriginObjectId`` (the origin's own / first/seed symptom) — NOT the
    object-type ``faultOriginType``. Falls back to the first symptom when the origin's own
    symptom is somehow absent (it is the seed by construction).
    """
    for symptom in scenario.predictedSymptoms:
        if symptom.managedObjectId == scenario.faultOriginObjectId:
            return symptom.alarmType
    if scenario.predictedSymptoms:
        return scenario.predictedSymptoms[0].alarmType
    raise VocabularyError(
        f"scenario {scenario.scenarioId!r} has no predicted symptoms; cannot derive "
        "rootCauseAlarmType"
    )
