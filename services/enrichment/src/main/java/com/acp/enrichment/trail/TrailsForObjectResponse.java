package com.acp.enrichment.trail;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Response of the frozen Trail Builder {@code GET /trails/by-object} contract:
 * {@code { managedObjectId, domain, trailIds: string[] }} (built against Trail Builder's published
 * OpenAPI 3.1 spec, never its source).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TrailsForObjectResponse(String managedObjectId, String domain,
        List<String> trailIds) {

    public TrailsForObjectResponse {
        trailIds = trailIds == null ? List.of() : List.copyOf(trailIds);
    }
}
