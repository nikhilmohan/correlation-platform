package com.acp.topology.graph;

import java.util.List;
import java.util.Optional;

/**
 * The NebulaGraph abstraction boundary (the port). The single implementation
 * {@link NebulaGraphRepository} is the ONLY class that issues nGQL / touches the nebula-java
 * {@code Session}. No NebulaGraph detail (space, host, nGQL, raw rows) escapes this package —
 * every method returns the internal typed records ({@link GraphVertex} / {@link GraphEdge}), which
 * the read service maps to typed DTOs. Unit tests mock this port.
 */
public interface GraphRepository {

    /** Idempotent bootstrap: ADD HOSTS (if needed), CREATE SPACE/TAG/EDGE/INDEX IF NOT EXISTS. */
    void bootstrapSchema();

    /** Write all lifted vertices + edges for a snapshot (data not yet current). */
    void writeSnapshot(List<GraphVertex> vertices, List<GraphEdge> edges);

    /** Delete every vertex/edge tagged with {@code snapshotId} (retention eviction / reaper). */
    void deleteSnapshot(String snapshotId);

    /** @return the distinct snapshotIds currently present in the graph (for the orphan reaper). */
    List<String> distinctSnapshotIds();

    /** Resolve a node by managedObjectId within (domain, snapshotId). */
    Optional<GraphVertex> getNode(String managedObjectId, String domain, String snapshotId);

    /** List nodes, optionally filtered by objectType, within (domain, snapshotId). */
    List<GraphVertex> listNodes(String objectType, String domain, String snapshotId);

    /** Get an edge by its decoded key, within snapshotId. */
    Optional<GraphEdge> getEdge(EdgeId.Decoded key);

    /** Direct neighbors of a node over the given relations (or all if empty), within snapshotId. */
    List<GraphEdge> neighbors(String managedObjectId, List<String> relations, String domain,
            String snapshotId, boolean crossDomain);

    /** Bounded traversal: distinct nodes reachable over relations within maxDepth hops. */
    List<GraphVertex> traverse(String start, List<String> relations, int maxDepth, String domain,
            String snapshotId, boolean crossDomain);

    /** The devices LOCATED_AT a site (reverse over LOCATED_AT), within (domain, snapshotId). */
    List<GraphVertex> objectsAtSite(String siteId, String domain, String snapshotId);

    /** All edges whose endpoints are both in {@code memberIds}, within (domain, snapshotId). */
    List<GraphEdge> edgesAmong(List<String> memberIds, String domain, String snapshotId);
}
