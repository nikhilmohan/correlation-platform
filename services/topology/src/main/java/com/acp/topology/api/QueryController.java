package com.acp.topology.api;

import com.acp.topology.api.dto.EdgeDto;
import com.acp.topology.api.dto.NeighborsDto;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.NodeListDto;
import com.acp.topology.api.dto.SiteListDto;
import com.acp.topology.api.dto.SiteObjectsDto;
import com.acp.topology.api.dto.SnapshotListDto;
import com.acp.topology.api.dto.SnapshotSummaryDto;
import com.acp.topology.api.dto.TraversalDto;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only query API (Task 5). Typed DTOs only — NebulaGraph internals are never exposed. Every
 * node/neighbor/traversal/list/site query is domain-scoped (supplied {@code ?domain=} or inferred).
 */
@RestController
@RequestMapping(path = "/topology", produces = MediaType.APPLICATION_JSON_VALUE)
public class QueryController {

    private final QueryService queryService;

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/nodes/{managedObjectId}")
    @Operation(summary = "Resolve a node by managedObjectId (layer == objectType).")
    public NodeDto getNode(@PathVariable String managedObjectId,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String snapshotId) {
        return queryService.getNode(managedObjectId, domain, snapshotId);
    }

    @GetMapping("/edges/{edgeId}")
    @Operation(summary = "Get an edge by its opaque round-trippable edgeId.")
    public EdgeDto getEdge(@PathVariable String edgeId) {
        return queryService.getEdge(edgeId);
    }

    @GetMapping("/nodes/{managedObjectId}/neighbors")
    @Operation(summary = "Direct neighbors of a node (optionally filtered by relation).")
    public NeighborsDto neighbors(@PathVariable String managedObjectId,
            @RequestParam(name = "relation", required = false) List<String> relations,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(name = "crossDomain", defaultValue = "false") boolean crossDomain) {
        return queryService.neighbors(managedObjectId,
                relations == null ? List.of() : relations, domain, snapshotId, crossDomain);
    }

    @GetMapping("/traversal")
    @Operation(summary = "Bounded traversal over one or more edge types up to maxDepth.")
    public TraversalDto traverse(@RequestParam String start,
            @RequestParam(name = "relation") List<String> relations,
            @RequestParam(name = "maxDepth") int maxDepth,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String snapshotId,
            @RequestParam(name = "crossDomain", defaultValue = "false") boolean crossDomain) {
        return queryService.traverse(start, relations, maxDepth, domain, snapshotId, crossDomain);
    }

    @GetMapping("/nodes")
    @Operation(summary = "List nodes, optionally filtered by objectType.")
    public NodeListDto listNodes(@RequestParam(required = false) String objectType,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String snapshotId) {
        return queryService.listNodes(objectType, domain, snapshotId);
    }

    @GetMapping("/sites")
    @Operation(summary = "List sites with flat geo fields (P1-G7).")
    public SiteListDto listSites(@RequestParam(required = false) String domain,
            @RequestParam(required = false) String snapshotId) {
        return queryService.listSites(domain, snapshotId);
    }

    @GetMapping("/sites/{siteId}/objects")
    @Operation(summary = "List the nodes AND edges at a site for the device graph (P1-G8).")
    public SiteObjectsDto objectsAtSite(@PathVariable String siteId,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String snapshotId) {
        return queryService.objectsAtSite(siteId, domain, snapshotId);
    }

    @GetMapping("/snapshots")
    @Operation(summary = "List available snapshots (at least current + previous per domain).")
    public SnapshotListDto listSnapshots(@RequestParam(required = false) String domain) {
        return queryService.listSnapshots(domain);
    }

    @GetMapping("/snapshots/current")
    @Operation(summary = "Return the current snapshot summary.")
    public SnapshotSummaryDto currentSnapshot(@RequestParam(required = false) String domain) {
        return queryService.currentSnapshot(domain);
    }
}
