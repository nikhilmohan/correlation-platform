package com.acp.knowledge.api.dto;

import java.util.List;

/**
 * The FROZEN vocabulary-query response (P1-G11): both current vocabulary sets in one call, the
 * single source the Topology Service validates an uploaded snapshot against.
 *
 * @param domain the domain scope
 * @param objectTypes the current object-type token set
 * @param relations the current edge-relation token set
 * @param version an opaque current-read marker (the underlying vocabulary records' version)
 */
public record VocabularyResponse(
        String domain,
        List<String> objectTypes,
        List<String> relations,
        String version) {
}
