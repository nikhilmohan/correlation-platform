package com.acp.patternmanager.enrichment;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A supporting example instance assembled from the mined event's provenance.
 *
 * @param sourceWindowId the transaction window the pattern was mined from
 * @param snapshotId the topology snapshot version in scope when mining ran
 * @param occurrence the raw provenance occurrence reference (opaque JSON)
 */
public record SupportingInstance(String sourceWindowId, String snapshotId, JsonNode occurrence) {
}
