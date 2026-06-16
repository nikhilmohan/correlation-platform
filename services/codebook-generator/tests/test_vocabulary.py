"""Alarm-type vocabulary validation tests (pure logic).

Exercises :mod:`codebook_generator.vocabulary`: every compiled symptom ``alarmType`` and the
derived ``rootCauseAlarmType`` must be a member of the domain's fetched
``alarmTypeVocabulary`` (a producer guarantee), and a token outside the set fails the compile.
"""

from __future__ import annotations

import pytest

from codebook_generator.models import PredictedSymptom, Scenario
from codebook_generator.vocabulary import (
    VocabularyError,
    root_cause_alarm_type,
    validate_scenarios,
)

from .conftest import CORE_IP_VOCABULARY


def _scenario(symptoms: list[tuple[str, str]], origin: str = "FiberSpan:f1") -> Scenario:
    return Scenario(
        scenarioId=f"CB-1::{origin}",
        faultOriginObjectId=origin,
        faultOriginType=origin.split(":", 1)[0],
        predictedSymptoms=[PredictedSymptom(alarmType=a, managedObjectId=o) for (a, o) in symptoms],
    )


def test_validate_accepts_in_vocabulary_signature() -> None:
    """A signature whose every alarmType is in the domain vocabulary passes."""
    scenario = _scenario(
        [
            ("FiberCut", "FiberSpan:f1"),
            ("LinkDown", "IPLink:l1"),
            ("LSPDown", "LSP:s1"),
            ("ReachabilityLoss", "VPNService:v1"),
        ]
    )
    # Does not raise.
    validate_scenarios([scenario], CORE_IP_VOCABULARY)


def test_validate_rejects_out_of_vocabulary_token() -> None:
    """A symptom alarmType outside the domain vocabulary fails the compile."""
    scenario = _scenario([("FiberCut", "FiberSpan:f1"), ("BogusAlarm", "IPLink:l1")])
    with pytest.raises(VocabularyError) as exc:
        validate_scenarios([scenario], CORE_IP_VOCABULARY)
    # Error names the offending token and object for diagnosability.
    assert "BogusAlarm" in str(exc.value)
    assert "IPLink:l1" in str(exc.value)


def test_root_cause_alarm_type_is_origin_own_symptom() -> None:
    """rootCauseAlarmType is the alarmType of the symptom on the fault-origin object."""
    scenario = _scenario(
        [("FiberCut", "FiberSpan:f1"), ("LinkDown", "IPLink:l1")],
        origin="FiberSpan:f1",
    )
    assert root_cause_alarm_type(scenario) == "FiberCut"


def test_root_cause_falls_back_to_first_symptom_when_origin_absent() -> None:
    """When no symptom matches the origin object, the seed (first) symptom is used."""
    scenario = _scenario([("LinkDown", "IPLink:l1")], origin="FiberSpan:f1")
    assert root_cause_alarm_type(scenario) == "LinkDown"


def test_root_cause_raises_without_symptoms() -> None:
    """A scenario with no predicted symptoms cannot yield a root cause."""
    scenario = Scenario(
        scenarioId="CB-1::empty",
        faultOriginObjectId="FiberSpan:f1",
        faultOriginType="FiberSpan",
        predictedSymptoms=[],
    )
    with pytest.raises(VocabularyError):
        root_cause_alarm_type(scenario)
