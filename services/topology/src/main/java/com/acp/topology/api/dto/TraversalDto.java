package com.acp.topology.api.dto;

import java.util.List;

/** {@code GET /topology/traversal} response. */
public record TraversalDto(
        String start,
        String domain,
        List<String> relations,
        int maxDepth,
        boolean crossDomain,
        List<NodeDto> reached) {
}
