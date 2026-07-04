package com.acp.patternmanager.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A supporting example instance on a {@code PatternView}.
 *
 * @param sourceWindowId the transaction window this occurrence came from
 * @param snapshotId the topology snapshot version
 * @param occurrence the raw provenance occurrence reference (opaque JSON)
 */
public record SupportingInstanceView(String sourceWindowId, String snapshotId, JsonNode occurrence) {
}
