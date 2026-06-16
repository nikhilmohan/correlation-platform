package com.acp.topology.api.dto;

/**
 * Frozen synchronous 200 response (P1-G1). {@code snapshotId} is minted inline during the lift, so
 * the call is synchronous (not 202). {@code snapshotId} + {@code status} are the mandatory minimum;
 * the rest are additive richer fields a producer may ignore.
 */
public record SnapshotIngestResponse(
        String snapshotId,
        String domain,
        String status,
        int nodeCount,
        int edgeCount,
        String changeType) {
}
