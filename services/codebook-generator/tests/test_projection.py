"""Correlation-Engine ``trail-signatures`` projection tests (pure read transform).

Exercises :mod:`codebook_generator.projection` directly: per-trail fan-out, the CE-facing
``expectedSymptoms`` alias, and ``rootCauseAlarmType`` derivation from the origin's own
symptom (the seed) rather than the object-type ``faultOriginType``.
"""

from __future__ import annotations

from codebook_generator.models import PredictedSymptom, Scenario
from codebook_generator.projection import (
    TrailScenarioSignature,
    project_codebook,
    project_scenario,
)


def _scenario(trail_ids: list[str]) -> Scenario:
    return Scenario(
        scenarioId="CB-1::Interface:i1",
        faultOriginObjectId="Interface:i1",
        faultOriginType="Interface",
        predictedSymptoms=[
            PredictedSymptom(alarmType="InterfaceDown", managedObjectId="Interface:i1"),
            PredictedSymptom(alarmType="LinkDown", managedObjectId="IPLink:l1"),
            PredictedSymptom(alarmType="LSPDown", managedObjectId="LSP:s1"),
        ],
        trailIds=trail_ids,
    )


def test_projection_fans_out_one_signature_per_trail() -> None:
    """A scenario tagged to N trails projects to N signatures, one per trailId."""
    scenario = _scenario(["TRAIL-1", "TRAIL-2"])
    sigs = project_scenario(scenario)
    assert [s.trailId for s in sigs] == ["TRAIL-1", "TRAIL-2"]
    assert all(isinstance(s, TrailScenarioSignature) for s in sigs)
    assert all(s.scenarioId == "CB-1::Interface:i1" for s in sigs)


def test_projection_expected_symptoms_alias_is_scenario_signature() -> None:
    """``expectedSymptoms`` is the CE-facing alias of the scenario's predictedSymptoms."""
    scenario = _scenario(["TRAIL-1"])
    [sig] = project_scenario(scenario)
    assert sig.expectedSymptoms == scenario.predictedSymptoms


def test_projection_root_cause_is_origin_seed_alarm_not_object_type() -> None:
    """``rootCauseAlarmType`` is the origin instance's own alarm token, not faultOriginType."""
    scenario = _scenario(["TRAIL-1"])
    [sig] = project_scenario(scenario)
    # Origin's own symptom (managedObjectId == faultOriginObjectId) is InterfaceDown.
    assert sig.rootCauseAlarmType == "InterfaceDown"
    assert sig.rootCauseAlarmType != scenario.faultOriginType


def test_projection_filters_to_requested_trail() -> None:
    """When a specific trailId is requested, only matching scenarios are projected."""
    scenario = _scenario(["TRAIL-1", "TRAIL-2"])
    assert [s.trailId for s in project_scenario(scenario, trail_id="TRAIL-2")] == ["TRAIL-2"]
    # A trail the scenario is not tagged to yields no signature.
    assert project_scenario(scenario, trail_id="TRAIL-X") == []


def test_project_codebook_fans_out_across_scenarios_and_trails() -> None:
    """AC-4 (CE view): every tagged scenario contributes at least one trail signature."""
    scenarios = [_scenario(["TRAIL-1"]), _scenario(["TRAIL-1", "TRAIL-2"])]
    sigs = project_codebook(scenarios)
    assert len(sigs) == 3
    # No signature is emitted without a trail tag.
    assert all(s.trailId for s in sigs)
