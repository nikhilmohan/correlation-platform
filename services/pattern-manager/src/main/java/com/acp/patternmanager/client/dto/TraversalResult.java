package com.acp.patternmanager.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The bounded-traversal result served by {@code GET /topology/traversal} (the Topology Service
 * {@code TraversalDto}). Carries the {@code reached} node set and the {@code edges} walked. Only
 * the fields the Pattern Manager needs are bound; unknown fields are ignored.
 *
 * @param start the start managedObjectId
 * @param maxDepth the traversal depth bound
 * @param reached nodes reachable from {@code start} within {@code maxDepth}
 * @param edges edges walked during the traversal
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraversalResult(
        String start,
        Integer maxDepth,
        List<TopologyNode> reached,
        List<TraversalEdge> edges) {

    public List<TopologyNode> reached() {
        return reached != null ? reached : List.of();
    }

    public List<TraversalEdge> edges() {
        return edges != null ? edges : List.of();
    }

    /**
     * A traversed dependency edge.
     *
     * @param edgeId edge identity
     * @param from source managedObjectId
     * @param to target managedObjectId
     * @param relation relation type
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TraversalEdge(String edgeId, String from, String to, String relation) {
    }
}
