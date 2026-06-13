package com.acp.topology.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Snapshot summary used by {@code GET /topology/snapshots} and {@code .../current}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotSummaryDto(
        String snapshotId,
        String domain,
        String changeType,
        String status,
        int nodeCount,
        int edgeCount,
        String ingestedAt) {
}
