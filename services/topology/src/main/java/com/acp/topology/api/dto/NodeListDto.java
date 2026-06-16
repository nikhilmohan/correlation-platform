package com.acp.topology.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** {@code GET /topology/nodes} response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeListDto(
        String domain,
        String objectType,
        String snapshotId,
        int count,
        List<NodeDto> nodes) {
}
