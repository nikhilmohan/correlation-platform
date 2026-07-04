package com.acp.patternmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A resolved topology node, as served by {@code GET /topology/nodes/{managedObjectId}} (the
 * Topology Service {@code NodeDto}). Only the fields the Pattern Manager needs are bound; unknown
 * fields are ignored so a Topology schema addition does not break this client.
 *
 * @param managedObjectId canonical {@code <objectType>:<id>} identity
 * @param objectType the object type token (e.g. {@code FiberSpan})
 * @param domain the domain scope
 * @param snapshotId the topology snapshot version
 * @param name display name
 * @param attributes descriptive attribute map
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TopologyNode(
        String managedObjectId,
        String objectType,
        String domain,
        String snapshotId,
        String name,
        Map<String, Object> attributes) {
}
