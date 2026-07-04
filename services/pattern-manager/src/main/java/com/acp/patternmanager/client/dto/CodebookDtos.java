package com.acp.patternmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * DTOs bound from the Codebook Generator's published OpenAPI (read API). Only the fields the
 * Pattern Manager needs for reconciliation + RCA override are bound; unknown fields are ignored.
 */
public final class CodebookDtos {

    private CodebookDtos() {
    }

    /** Response of {@code GET /codebooks?domain=...} — the codebooks available for a domain. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodebookListResponse(String domain, String snapshotId, List<CodebookMeta> codebooks) {
        public List<CodebookMeta> codebooks() {
            return codebooks != null ? codebooks : List.of();
        }
    }

    /** A codebook metadata entry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CodebookMeta(String codebookId, String snapshotId, String domain,
            Integer scenarioCount, Boolean active) {
    }

    /** Response of {@code GET /codebooks/{codebookId}/scenarios}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioListResponse(String codebookId, String domain, List<ScenarioOut> scenarios) {
        public List<ScenarioOut> scenarios() {
            return scenarios != null ? scenarios : List.of();
        }
    }

    /** A codebook scenario ({@code ScenarioOut}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScenarioOut(String scenarioId, String faultOriginObjectId, String faultOriginType,
            List<PredictedSymptom> predictedSymptoms, List<String> trailIds) {
        public List<PredictedSymptom> predictedSymptoms() {
            return predictedSymptoms != null ? predictedSymptoms : List.of();
        }

        public List<String> trailIds() {
            return trailIds != null ? trailIds : List.of();
        }
    }

    /** A predicted symptom on a scenario — the {@code alarmType} used for sequence overlap. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PredictedSymptom(String alarmType, String managedObjectId) {
    }

    /** Response of {@code GET /codebooks/{codebookId}/trail-signatures?trailId=...}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrailSignaturesResponse(String codebookId, List<TrailScenarioSignature> signatures) {
        public List<TrailScenarioSignature> signatures() {
            return signatures != null ? signatures : List.of();
        }
    }

    /**
     * A trail-scoped scenario signature — carries the scenario's designated {@code rootCauseAlarmType}
     * (the authoritative RCA-override value) and its expected symptoms.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrailScenarioSignature(String trailId, String scenarioId, String rootCauseAlarmType,
            List<PredictedSymptom> expectedSymptoms) {
        public List<PredictedSymptom> expectedSymptoms() {
            return expectedSymptoms != null ? expectedSymptoms : List.of();
        }
    }
}
