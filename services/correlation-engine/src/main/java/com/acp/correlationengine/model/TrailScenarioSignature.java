package com.acp.correlationengine.model;

import java.util.List;
import java.util.Objects;

/**
 * One codebook scenario signature, fanned out per trail — the CE-facing projection fetched from
 * the Codebook Generator's {@code GET /codebooks/{codebookId}/trail-signatures} endpoint.
 *
 * <p>{@code expectedSymptoms[].alarmType} and {@code rootCauseAlarmType} live in the SAME
 * vocabulary as {@code AlarmEvent.alarmType}, so the decoder scores live alarms directly on these
 * tokens (no value-space translation). Carries the active codebook's artifact id ({@code codebookId})
 * so a codebook-decode incident's {@code matchedCodebookId} is the artifact id, not a scenario id
 * (AC15/AC25).
 */
public record TrailScenarioSignature(
        String codebookId,
        String trailId,
        String scenarioId,
        String rootCauseAlarmType,
        List<Symptom> expectedSymptoms) {

    public TrailScenarioSignature {
        Objects.requireNonNull(trailId, "trailId");
        Objects.requireNonNull(rootCauseAlarmType, "rootCauseAlarmType");
        expectedSymptoms = expectedSymptoms == null ? List.of() : List.copyOf(expectedSymptoms);
    }

    /** @return the multiset of expected {@code alarmType} tokens (the scenario signature S). */
    public List<String> expectedAlarmTypes() {
        return expectedSymptoms.stream().map(Symptom::alarmType).toList();
    }

    /** One predicted symptom: an {@code alarmType} token + the managed object it is expected on. */
    public record Symptom(String alarmType, String managedObjectId) {
    }
}
