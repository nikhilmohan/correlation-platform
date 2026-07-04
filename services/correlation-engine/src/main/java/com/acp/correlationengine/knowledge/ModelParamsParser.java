package com.acp.correlationengine.knowledge;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parses the Knowledge versioned-record envelope
 * {@code {domain, recordType, recordId, version, isCurrent, payload{paramSet, params[]}}} into a
 * {@link MatchParams}, reading {@code payload.params[]} by dotted key. Reused by the real client
 * and the unit-test mock so both interpret the same frozen shape identically.
 */
public final class ModelParamsParser {

    private ModelParamsParser() {
    }

    /**
     * @param envelope the versioned-record envelope JSON node
     * @return the parsed {@link MatchParams}
     * @throws KnowledgeUnavailableException if the payload/params are missing or a required dotted
     *     key is absent (the engine never invents defaults — no hard-coded thresholds)
     */
    public static MatchParams parse(JsonNode envelope) {
        JsonNode payload = envelope == null ? null : envelope.get("payload");
        JsonNode params = payload == null ? null : payload.get("params");
        if (params == null || !params.isArray()) {
            throw new KnowledgeUnavailableException(
                    "model-params record missing payload.params[] array");
        }
        return new MatchParams(
                (int) requireNumber(params, MatchParams.KEY_PARTIAL_MATCH_TOLERANCE),
                requireNumber(params, MatchParams.KEY_CODEBOOK_MISSING_PENALTY),
                requireNumber(params, MatchParams.KEY_CODEBOOK_SPURIOUS_PENALTY),
                requireNumber(params, MatchParams.KEY_CODEBOOK_SCORE_FLOOR),
                requireNumber(params, MatchParams.KEY_CONFLICT_SPECIFICITY),
                requireNumber(params, MatchParams.KEY_CONFLICT_CONFIDENCE));
    }

    private static double requireNumber(JsonNode params, String dottedKey) {
        for (JsonNode p : params) {
            JsonNode key = p.get("key");
            if (key != null && dottedKey.equals(key.asText())) {
                JsonNode value = p.get("value");
                if (value == null || !value.isNumber()) {
                    throw new KnowledgeUnavailableException(
                            "model-params key '" + dottedKey + "' has no numeric value");
                }
                return value.doubleValue();
            }
        }
        throw new KnowledgeUnavailableException(
                "model-params record missing required key '" + dottedKey + "'");
    }
}
