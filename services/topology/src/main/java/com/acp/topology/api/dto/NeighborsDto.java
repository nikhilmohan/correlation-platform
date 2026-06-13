package com.acp.topology.api.dto;

import java.util.List;

/** {@code GET /topology/nodes/{id}/neighbors} response. */
public record NeighborsDto(
        String managedObjectId,
        String domain,
        List<Neighbor> neighbors) {

    /** A directly connected node and the edge it was reached over. */
    public record Neighbor(NodeDto node, EdgeDto via) {
    }
}
