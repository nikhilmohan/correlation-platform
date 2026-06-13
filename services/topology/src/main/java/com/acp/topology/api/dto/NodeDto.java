package com.acp.topology.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Frozen node shape (P1-G9). There is NO separate {@code layer} field: {@code layer == objectType}
 * (the objectType, sourced from the vertex's NebulaGraph TAG, IS the layer indicator). No
 * NebulaGraph internals are ever exposed — only this typed shape.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeDto(
        String managedObjectId,
        String objectType,
        String domain,
        String snapshotId,
        String name,
        Map<String, Object> attributes) {
}
