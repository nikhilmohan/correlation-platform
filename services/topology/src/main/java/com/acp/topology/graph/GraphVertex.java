package com.acp.topology.graph;

import java.util.Map;

/**
 * Internal typed vertex record (lifted from a snapshot node). Lives behind the {@code graph/}
 * boundary; never leaves the package as a NebulaGraph row — it is mapped to a {@code NodeDto} by
 * {@code GraphReadService}.
 */
public record GraphVertex(
        String managedObjectId,
        String objectType,
        String domain,
        String snapshotId,
        String name,
        Map<String, Object> attributes) {
}
