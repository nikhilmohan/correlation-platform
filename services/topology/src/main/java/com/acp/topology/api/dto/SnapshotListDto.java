package com.acp.topology.api.dto;

import java.util.List;

/** {@code GET /topology/snapshots} response (at least current + previous per domain). */
public record SnapshotListDto(List<SnapshotSummaryDto> snapshots) {
}
