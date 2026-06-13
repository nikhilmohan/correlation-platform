package com.acp.topology.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Frozen flat site shape (P1-G7): flat top-level geo fields (NOT nested under attributes, NOT a raw
 * NodeDto). {@code siteId} is the Site node's {@code managedObjectId}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SiteDto(
        String siteId,
        String name,
        Double latitude,
        Double longitude,
        String region) {
}
