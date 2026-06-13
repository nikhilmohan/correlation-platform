package com.acp.knowledge.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body for a POST create. {@code recordId} is the stable record identifier; {@code payload} is the
 * record payload validated against the recordType's record-model schema. {@code author} is the
 * optional edit-context user (from the web-ui).
 *
 * @param recordId the stable record identifier
 * @param payload the record payload (validated on write)
 * @param author the edit author (nullable)
 */
public record CreateRecordRequest(String recordId, JsonNode payload, String author) {
}
