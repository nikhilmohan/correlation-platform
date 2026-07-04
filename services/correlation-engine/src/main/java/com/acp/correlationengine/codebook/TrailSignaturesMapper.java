package com.acp.correlationengine.codebook;

import com.acp.correlationengine.model.TrailScenarioSignature;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps the Codebook Generator {@code GET /codebooks/{codebookId}/trail-signatures} response
 * ({@code {codebookId, domain, trailSignatures:[{trailId, scenarioId, rootCauseAlarmType,
 * expectedSymptoms:[{alarmType, managedObjectId}]}]}}) into {@link TrailScenarioSignature}s,
 * carrying the codebook artifact id onto each signature. Reused by the real client + the unit-test
 * mock so both interpret the published shape identically.
 */
public final class TrailSignaturesMapper {

    private TrailSignaturesMapper() {
    }

    public static List<TrailScenarioSignature> fromResponse(JsonNode body) {
        List<TrailScenarioSignature> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        String codebookId = text(body, "codebookId");
        JsonNode signatures = body.get("trailSignatures");
        if (signatures == null || !signatures.isArray()) {
            return out;
        }
        for (JsonNode sig : signatures) {
            List<TrailScenarioSignature.Symptom> symptoms = new ArrayList<>();
            JsonNode expected = sig.get("expectedSymptoms");
            if (expected != null && expected.isArray()) {
                for (JsonNode sym : expected) {
                    symptoms.add(new TrailScenarioSignature.Symptom(
                            text(sym, "alarmType"), text(sym, "managedObjectId")));
                }
            }
            out.add(new TrailScenarioSignature(
                    codebookId,
                    text(sig, "trailId"),
                    text(sig, "scenarioId"),
                    text(sig, "rootCauseAlarmType"),
                    symptoms));
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
