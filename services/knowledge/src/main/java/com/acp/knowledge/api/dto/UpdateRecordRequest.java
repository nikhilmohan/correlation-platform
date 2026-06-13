package com.acp.knowledge.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body for a PUT update. {@code recordId} comes from the path; this carries the new payload.
 *
 * @param payload the new record payload (validated on write)
 * @param author the edit author (nullable)
 */
public record UpdateRecordRequest(JsonNode payload, String author) {
}
