package com.acp.topology.meta;

import java.time.Instant;

/** A row in {@code topology_meta.snapshot} (snapshot version metadata — no graph data). */
public record SnapshotRecord(
        String snapshotId,
        String changeType,
        String domain,
        int fileSchemaVersion,
        int nodeCount,
        int edgeCount,
        String status,
        String producerSuppliedId,
        Instant ingestedAt,
        String eventId,
        String traceId) {
}
