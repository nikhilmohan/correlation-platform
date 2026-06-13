package com.acp.topology.api.dto;

import java.util.List;

/**
 * Frozen {@code GET /topology/sites/{siteId}/objects} shape (P1-G8): nodes AND edges, so web-ui
 * draws the device-level site graph from one call.
 */
public record SiteObjectsDto(
        String siteId,
        String domain,
        String snapshotId,
        int nodeCount,
        int edgeCount,
        List<NodeDto> nodes,
        List<EdgeDto> edges) {
}
