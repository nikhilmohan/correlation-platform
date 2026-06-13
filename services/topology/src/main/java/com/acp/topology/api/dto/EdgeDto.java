package com.acp.topology.api.dto;

import java.util.Map;

/**
 * Frozen edge shape. {@code edgeId} is the opaque, service-decodable composite token that round-trips
 * back into {@code GET /topology/edges/{edgeId}} (decodes to {@code (snapshotId, from, relation, to)}).
 * Never exposes a raw NebulaGraph rank or nGQL result.
 */
public record EdgeDto(
        String edgeId,
        String from,
        String to,
        String relation,
        String domain,
        Map<String, Object> attributes,
        String snapshotId) {
}
