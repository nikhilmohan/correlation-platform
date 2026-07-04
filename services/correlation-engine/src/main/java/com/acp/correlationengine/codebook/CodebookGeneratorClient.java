package com.acp.correlationengine.codebook;

import com.acp.correlationengine.model.TrailScenarioSignature;
import java.util.List;

/**
 * Config-switchable client for the Codebook Generator's CE-oriented projection
 * {@code GET /codebooks/{codebookId}/trail-signatures}. Returns per-trail
 * {@link TrailScenarioSignature}s ({@code {trailId, scenarioId, rootCauseAlarmType,
 * expectedSymptoms[{alarmType, managedObjectId}]}}) whose tokens are in the {@code AlarmEvent.alarmType}
 * vocabulary. Built against the Codebook Generator's published OpenAPI; mock in unit tests, real in
 * integration (AC23).
 */
public interface CodebookGeneratorClient {

    /**
     * @param codebookId the active codebook artifact id (from {@code CodebookGeneratedEvent})
     * @return the trail-scoped scenario signatures for that codebook, each tagged with
     *     {@code codebookId} so a decode incident's {@code matchedCodebookId} is the artifact id
     */
    List<TrailScenarioSignature> fetchTrailSignatures(String codebookId);
}
