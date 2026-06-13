package com.acp.knowledge.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A single versioned knowledge record (one row of {@code knowledge.record_version} joined to its
 * identity). The same envelope shape for all eight record types per the design's unified record
 * model: {@code (domain, recordType, recordId, version, isCurrent, payload, audit)}.
 *
 * @param domain the domain scope (e.g. {@code core-ip})
 * @param recordType the canonical recordType id (e.g. {@code propagationTemplate})
 * @param recordId the stable record identifier
 * @param version the version label ({@code v1}, {@code v2}, ...)
 * @param isCurrent whether this is the current version of the record
 * @param payload the validated record payload (jsonb)
 * @param author the edit author (web-ui user), nullable
 * @param createdAt when this version was minted
 */
public record KnowledgeRecord(
        String domain,
        String recordType,
        String recordId,
        String version,
        boolean isCurrent,
        JsonNode payload,
        String author,
        Instant createdAt) {
}
