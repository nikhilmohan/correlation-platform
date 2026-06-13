package com.acp.knowledge.api.dto;

import com.acp.knowledge.domain.KnowledgeRecord;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The versioned-record envelope returned by all read/write endpoints (the design's frozen
 * model-params read/write shape, used uniformly for every recordType).
 *
 * @param domain the domain scope
 * @param recordType the canonical recordType id
 * @param recordId the record identifier
 * @param version the version label
 * @param isCurrent whether this is the current version
 * @param payload the record payload
 */
public record RecordResponse(
        String domain,
        String recordType,
        String recordId,
        String version,
        boolean isCurrent,
        JsonNode payload) {

    public static RecordResponse from(KnowledgeRecord r) {
        return new RecordResponse(r.domain(), r.recordType(), r.recordId(), r.version(),
                r.isCurrent(), r.payload());
    }
}
