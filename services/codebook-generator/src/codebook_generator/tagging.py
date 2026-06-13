"""Trail-tag resolution (spec task 6 / criterion 4).

For each scenario, resolve the trail(s) its symptoms occupy by querying the Trail Builder
for every symptom object's ``managedObjectId`` (domain-scoped, ``domain`` required) and
unioning the returned ``trailIds`` — preserving first-seen order for determinism.
"""

from __future__ import annotations

from .clients.trail_builder import TrailBuilderClient
from .models import Scenario


def tag_scenario(
    scenario: Scenario, domain: str, trail_builder: TrailBuilderClient
) -> list[str]:
    """Return the ordered union of trail ids across the scenario's symptom objects."""
    ordered: list[str] = []
    seen: set[str] = set()
    queried: set[str] = set()
    for symptom in scenario.predictedSymptoms:
        if symptom.managedObjectId in queried:
            continue
        queried.add(symptom.managedObjectId)
        resp = trail_builder.get_trails_for_object(symptom.managedObjectId, domain)
        for trail_id in resp.trailIds:
            if trail_id not in seen:
                seen.add(trail_id)
                ordered.append(trail_id)
    return ordered
