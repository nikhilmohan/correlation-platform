package com.acp.topology.api;

import com.acp.topology.api.dto.EdgeDto;
import com.acp.topology.api.dto.NeighborsDto;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.NodeListDto;
import com.acp.topology.api.dto.SiteDto;
import com.acp.topology.api.dto.SiteListDto;
import com.acp.topology.api.dto.SiteObjectsDto;
import com.acp.topology.api.dto.SnapshotListDto;
import com.acp.topology.api.dto.SnapshotSummaryDto;
import com.acp.topology.config.TopologyProperties;
import com.acp.topology.graph.EdgeId;
import com.acp.topology.graph.GraphReadService;
import com.acp.topology.meta.SnapshotMetadataService;
import com.acp.topology.meta.SnapshotRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/**
 * Coordinates query-API reads: resolves the effective {@code snapshotId} (current/previous via the
 * PostgreSQL pointer), infers the {@code domain}, delegates graph reads to {@link GraphReadService},
 * and assembles the frozen DTOs. Enforces the traversal depth bound.
 */
@Service
public class QueryService {

    private final GraphReadService graph;
    private final SnapshotMetadataService metadata;
    private final int maxDepth;

    public QueryService(GraphReadService graph, SnapshotMetadataService metadata,
            TopologyProperties properties) {
        this.graph = graph;
        this.metadata = metadata;
        this.maxDepth = properties.getTraversal().getMaxDepth();
    }

    public NodeDto getNode(String managedObjectId, String domain, String snapshotRef) {
        String dom = resolveDomain(domain, managedObjectId);
        String snapshotId = resolveSnapshotId(dom, snapshotRef);
        return graph.getNode(managedObjectId, dom, snapshotId)
                .orElseThrow(() -> notFound("node " + managedObjectId + " not found"));
    }

    public EdgeDto getEdge(String edgeId) {
        EdgeId.Decoded key;
        try {
            key = EdgeId.decode(edgeId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed edgeId");
        }
        return graph.getEdge(key).orElseThrow(() -> notFound("edge not found"));
    }

    public NodeListDto listNodes(String objectType, String domain, String snapshotRef) {
        String dom = requireDomain(domain);
        String snapshotId = resolveSnapshotId(dom, snapshotRef);
        List<NodeDto> nodes = graph.listNodes(objectType, dom, snapshotId);
        return new NodeListDto(dom, objectType, snapshotId, nodes.size(), nodes);
    }

    public NeighborsDto neighbors(String managedObjectId, List<String> relations, String domain,
            String snapshotRef, boolean crossDomain) {
        String dom = resolveDomain(domain, managedObjectId);
        String snapshotId = resolveSnapshotId(dom, snapshotRef);
        if (graph.getNode(managedObjectId, dom, snapshotId).isEmpty()) {
            throw notFound("node " + managedObjectId + " not found");
        }
        return graph.neighbors(managedObjectId, relations, dom, snapshotId, crossDomain);
    }

    public com.acp.topology.api.dto.TraversalDto traverse(String start, List<String> relations,
            int depth, String domain, String snapshotRef, boolean crossDomain) {
        if (start == null || start.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "start is required");
        }
        if (relations == null || relations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one relation is required");
        }
        if (depth < 1 || depth > maxDepth) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "maxDepth must be in [1.." + maxDepth + "]");
        }
        String dom = resolveDomain(domain, start);
        String snapshotId = resolveSnapshotId(dom, snapshotRef);
        if (graph.getNode(start, dom, snapshotId).isEmpty()) {
            throw notFound("start node " + start + " not found");
        }
        List<NodeDto> reached = graph.traverse(start, relations, depth, dom, snapshotId, crossDomain);
        // #252: include the typed directed edges of the closure (relation-scoped) so consumers can
        // walk the cascade rather than seeing isolated nodes. Empty (never null) when edge-less.
        List<EdgeDto> edges = graph.traverseEdges(start, reached, relations, dom, snapshotId);
        return new com.acp.topology.api.dto.TraversalDto(start, dom, relations, depth, crossDomain,
                reached, edges);
    }

    public SiteListDto listSites(String domain, String snapshotRef) {
        String dom = requireDomain(domain);
        String snapshotId = resolveSnapshotId(dom, snapshotRef);
        List<SiteDto> sites = graph.listSites(dom, snapshotId);
        return new SiteListDto(dom, snapshotId, sites.size(), sites);
    }

    public SiteObjectsDto objectsAtSite(String siteId, String domain, String snapshotRef) {
        String dom = resolveDomain(domain, siteId);
        String snapshotId = resolveSnapshotId(dom, snapshotRef);
        if (graph.getNode(siteId, dom, snapshotId).isEmpty()) {
            throw notFound("site " + siteId + " not found");
        }
        List<NodeDto> nodes = graph.objectsAtSite(siteId, dom, snapshotId);
        List<EdgeDto> edges = graph.edgesForSite(siteId, nodes, dom, snapshotId);
        return new SiteObjectsDto(siteId, dom, snapshotId, nodes.size(), edges.size(), nodes, edges);
    }

    public SnapshotListDto listSnapshots(String domain) {
        String dom = requireDomain(domain);
        List<SnapshotSummaryDto> snapshots = metadata.listByDomain(dom).stream()
                .map(QueryService::toSummary).toList();
        return new SnapshotListDto(snapshots);
    }

    public SnapshotSummaryDto currentSnapshot(String domain) {
        String dom = requireDomain(domain);
        return metadata.findCurrent(dom).map(QueryService::toSummary)
                .orElseThrow(() -> notFound("no snapshot yet for domain " + dom));
    }

    // --- helpers --------------------------------------------------------------------------

    /** Use the supplied domain, else infer the single MVP domain from any current snapshot. */
    private String resolveDomain(String domain, String managedObjectId) {
        return requireDomain(domain);
    }

    private String requireDomain(String domain) {
        if (domain != null && !domain.isBlank()) {
            return domain;
        }
        return metadata.findCurrentAnyDomain()
                .map(SnapshotRecord::domain)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "domain is required"));
    }

    /** Resolve {@code current}/{@code previous}/explicit ref to a concrete snapshotId. */
    private String resolveSnapshotId(String domain, String snapshotRef) {
        if (snapshotRef == null || snapshotRef.isBlank() || "current".equalsIgnoreCase(snapshotRef)) {
            return metadata.findCurrent(domain).map(SnapshotRecord::snapshotId)
                    .orElseThrow(() -> notFound("no current snapshot for domain " + domain));
        }
        if ("previous".equalsIgnoreCase(snapshotRef)) {
            return metadata.listByDomain(domain).stream()
                    .filter(r -> "previous".equals(r.status()))
                    .findFirst().map(SnapshotRecord::snapshotId)
                    .orElseThrow(() -> notFound("no previous snapshot for domain " + domain));
        }
        return snapshotRef;
    }

    private static SnapshotSummaryDto toSummary(SnapshotRecord r) {
        return new SnapshotSummaryDto(r.snapshotId(), r.domain(), r.changeType(), r.status(),
                r.nodeCount(), r.edgeCount(),
                r.ingestedAt() == null ? null : r.ingestedAt().toString());
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
