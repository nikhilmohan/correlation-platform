package com.acp.topology.graph;

import com.acp.topology.api.dto.EdgeDto;
import com.acp.topology.api.dto.NeighborsDto;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.SiteDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Domain-scoped read facade. The ONLY place internal {@link GraphVertex}/{@link GraphEdge} records
 * are mapped to typed DTOs ({@link NodeDto} with {@code layer == objectType}, {@link EdgeDto} with
 * the opaque round-trippable {@code edgeId}, flat {@link SiteDto}). No NebulaGraph internals leave.
 */
@Service
public class GraphReadService {

    private final GraphRepository repository;

    public GraphReadService(GraphRepository repository) {
        this.repository = repository;
    }

    public Optional<NodeDto> getNode(String managedObjectId, String domain, String snapshotId) {
        return repository.getNode(managedObjectId, domain, snapshotId).map(GraphReadService::toNode);
    }

    public List<NodeDto> listNodes(String objectType, String domain, String snapshotId) {
        return repository.listNodes(objectType, domain, snapshotId).stream()
                .map(GraphReadService::toNode).toList();
    }

    public Optional<EdgeDto> getEdge(EdgeId.Decoded key) {
        return repository.getEdge(key).map(GraphReadService::toEdge);
    }

    public NeighborsDto neighbors(String managedObjectId, List<String> relations, String domain,
            String snapshotId, boolean crossDomain) {
        List<GraphEdge> edges =
                repository.neighbors(managedObjectId, relations, domain, snapshotId, crossDomain);
        List<NeighborsDto.Neighbor> neighbors = new ArrayList<>();
        for (GraphEdge e : edges) {
            String neighborId = e.from().equals(managedObjectId) ? e.to() : e.from();
            repository.getNode(neighborId, e.domain(), snapshotId)
                    .map(GraphReadService::toNode)
                    .ifPresent(n -> neighbors.add(new NeighborsDto.Neighbor(n, toEdge(e))));
        }
        return new NeighborsDto(managedObjectId, domain, neighbors);
    }

    public List<NodeDto> traverse(String start, List<String> relations, int maxDepth, String domain,
            String snapshotId, boolean crossDomain) {
        return repository.traverse(start, relations, maxDepth, domain, snapshotId, crossDomain)
                .stream().map(GraphReadService::toNode).toList();
    }

    /** Sites for a domain, returned as the frozen flat {@link SiteDto} (geo lifted out of attrs). */
    public List<SiteDto> listSites(String domain, String snapshotId) {
        return repository.listNodes("Site", domain, snapshotId).stream()
                .map(GraphReadService::toSite).toList();
    }

    public List<NodeDto> objectsAtSite(String siteId, String domain, String snapshotId) {
        return repository.objectsAtSite(siteId, domain, snapshotId).stream()
                .map(GraphReadService::toNode).toList();
    }

    public List<EdgeDto> edgesForSite(String siteId, List<NodeDto> devices, String domain,
            String snapshotId) {
        List<String> ids = devices.stream().map(NodeDto::managedObjectId).toList();
        return repository.edgesAmong(ids, domain, snapshotId).stream()
                .map(GraphReadService::toEdge).toList();
    }

    // --- mapping (internal record → typed DTO) --------------------------------------------

    static NodeDto toNode(GraphVertex v) {
        // P1-G9: no separate layer field; layer == objectType.
        return new NodeDto(v.managedObjectId(), v.objectType(), v.domain(), v.snapshotId(),
                v.name(), v.attributes());
    }

    static EdgeDto toEdge(GraphEdge e) {
        String edgeId = EdgeId.encode(e.snapshotId(), e.from(), e.relation(), e.to());
        return new EdgeDto(edgeId, e.from(), e.to(), e.relation(), e.domain(), e.attributes(),
                e.snapshotId());
    }

    static SiteDto toSite(GraphVertex v) {
        Map<String, Object> attrs = v.attributes() == null ? Map.of() : v.attributes();
        String name = v.name() != null ? v.name() : asString(attrs.get("name"));
        return new SiteDto(v.managedObjectId(), name,
                asDouble(attrs.get("latitude")), asDouble(attrs.get("longitude")),
                asString(attrs.get("region")));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double asDouble(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
