package com.acp.topology.api.dto;

import java.util.List;

/**
 * {@code GET /topology/traversal} response.
 *
 * <p>{@code reached} are the distinct nodes reachable from {@code start} over {@code relations}
 * within {@code maxDepth} hops. {@code edges} are the typed, directed edges of that closure
 * (#252): every edge whose {@code from} and {@code to} are both in the closure node set
 * ({@code start} + {@code reached}) AND whose {@code relation} is one of the requested
 * {@code relations} (the traversal is relation-scoped). Edges let consumers (e.g. the
 * codebook-generator's forward-propagation) walk the cascade rather than seeing isolated nodes;
 * an edge-less closure yields an empty (never null) list.
 */
public record TraversalDto(
        String start,
        String domain,
        List<String> relations,
        int maxDepth,
        boolean crossDomain,
        List<NodeDto> reached,
        List<EdgeDto> edges) {
}
