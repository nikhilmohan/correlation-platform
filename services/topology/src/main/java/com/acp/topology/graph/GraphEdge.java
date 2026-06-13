package com.acp.topology.graph;

import java.util.Map;

/** Internal typed edge record (lifted from a snapshot edge). Mapped to an {@code EdgeDto} on read. */
public record GraphEdge(
        String from,
        String to,
        String relation,
        String domain,
        String snapshotId,
        Map<String, Object> attributes) {
}
