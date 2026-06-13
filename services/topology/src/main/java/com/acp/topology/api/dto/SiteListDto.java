package com.acp.topology.api.dto;

import java.util.List;

/** Frozen {@code GET /topology/sites} envelope (P1-G7). */
public record SiteListDto(
        String domain,
        String snapshotId,
        int count,
        List<SiteDto> sites) {
}
