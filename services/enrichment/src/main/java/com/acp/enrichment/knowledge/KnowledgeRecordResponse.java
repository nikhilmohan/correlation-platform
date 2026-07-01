package com.acp.enrichment.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One element of the Knowledge {@code GET /domains/{domain}/{recordType}} list response — a
 * versioned record envelope. Only the fields Enrichment reads are bound; the free-form authored
 * content is under {@code payload} (matches Knowledge's {@code RecordResponse} OpenAPI schema, whose
 * {@code payload} is a {@code JsonNode}). The alarm-type vocabulary lives at
 * {@code payload.alarmTypes[]}.
 *
 * @param recordId the record id (e.g. {@code default})
 * @param isCurrent whether this is the current version
 * @param payload the authored record body (holds {@code alarmTypes} for the vocabulary record)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeRecordResponse(String recordId, Boolean isCurrent, JsonNode payload) {}
