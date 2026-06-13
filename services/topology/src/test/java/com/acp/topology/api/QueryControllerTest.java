package com.acp.topology.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acp.topology.api.dto.EdgeDto;
import com.acp.topology.api.dto.NeighborsDto;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.NodeListDto;
import com.acp.topology.api.dto.SiteDto;
import com.acp.topology.api.dto.SiteListDto;
import com.acp.topology.api.dto.SiteObjectsDto;
import com.acp.topology.graph.EdgeId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * AC-12 (resolve + 404), AC-13 (list-by-type + neighbors), AC-13b (get-edge 400/404), AC-22/29 (site
 * objects nodes AND edges + 404), AC-30 (frozen NodeDto, layer == objectType). WebMvc slice with a
 * mocked {@link QueryService}; asserts HTTP status + the frozen response shapes (typed DTOs only,
 * no NebulaGraph internals).
 */
@WebMvcTest(controllers = QueryController.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private QueryService queryService;

    @Test
    void getNodeReturnsFrozenNodeDto_layerEqualsObjectType() throws Exception {
        when(queryService.getNode(eq("Node:PE1"), any(), any())).thenReturn(
                new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1",
                        Map.of("vendor", "acme")));

        mvc.perform(get("/topology/nodes/Node:PE1").param("domain", "core-ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.managedObjectId").value("Node:PE1"))
                .andExpect(jsonPath("$.objectType").value("Node"))   // layer == objectType
                .andExpect(jsonPath("$.domain").value("core-ip"))
                .andExpect(jsonPath("$.snapshotId").value("SNAP-1"))
                .andExpect(jsonPath("$.attributes.vendor").value("acme"))
                .andExpect(jsonPath("$.layer").doesNotExist());      // no separate layer field
    }

    @Test
    void getNodeReturns404WhenUnknown() throws Exception {
        when(queryService.getNode(eq("Node:UNKNOWN"), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "not found"));
        mvc.perform(get("/topology/nodes/Node:UNKNOWN"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listByTypeReturnsOnlyThatType() throws Exception {
        when(queryService.listNodes(eq("Port"), any(), any())).thenReturn(
                new NodeListDto("core-ip", "Port", "SNAP-1", 2, List.of(
                        new NodeDto("Port:p1", "Port", "core-ip", "SNAP-1", "p1", Map.of()),
                        new NodeDto("Port:p2", "Port", "core-ip", "SNAP-1", "p2", Map.of()))));

        mvc.perform(get("/topology/nodes").param("objectType", "Port").param("domain", "core-ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objectType").value("Port"))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.nodes[0].objectType").value("Port"))
                .andExpect(jsonPath("$.nodes[1].objectType").value("Port"));
    }

    @Test
    void neighborsReturnsDirectlyConnected() throws Exception {
        NodeDto neighbor = new NodeDto("Interface:i1", "Interface", "core-ip", "SNAP-1", "i1",
                Map.of());
        EdgeDto via = new EdgeDto(EdgeId.encode("SNAP-1", "Port:p1", "HOSTS", "Interface:i1"),
                "Port:p1", "Interface:i1", "HOSTS", "core-ip", Map.of(), "SNAP-1");
        when(queryService.neighbors(eq("Port:p1"), any(), any(), any(), anyBoolean())).thenReturn(
                new NeighborsDto("Port:p1", "core-ip",
                        List.of(new NeighborsDto.Neighbor(neighbor, via))));

        mvc.perform(get("/topology/nodes/Port:p1/neighbors").param("relation", "HOSTS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.neighbors[0].node.managedObjectId").value("Interface:i1"))
                .andExpect(jsonPath("$.neighbors[0].via.relation").value("HOSTS"));
    }

    @Test
    void objectsAtSiteReturnsNodesAndEdges() throws Exception {
        SiteObjectsDto resp = new SiteObjectsDto("Site:LON-DC1", "core-ip", "SNAP-1", 2, 2,
                List.of(new NodeDto("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1", Map.of()),
                        new NodeDto("Node:PE2", "Node", "core-ip", "SNAP-1", "PE2", Map.of())),
                List.of(new EdgeDto(EdgeId.encode("SNAP-1", "Node:PE1", "LOCATED_AT", "Site:LON-DC1"),
                                "Node:PE1", "Site:LON-DC1", "LOCATED_AT", "core-ip", Map.of(), "SNAP-1"),
                        new EdgeDto(EdgeId.encode("SNAP-1", "Node:PE1", "ADJACENCY_OVER", "Node:PE2"),
                                "Node:PE1", "Node:PE2", "ADJACENCY_OVER", "core-ip", Map.of(),
                                "SNAP-1")));
        when(queryService.objectsAtSite(eq("Site:LON-DC1"), any(), any())).thenReturn(resp);

        mvc.perform(get("/topology/sites/Site:LON-DC1/objects").param("domain", "core-ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(2))
                .andExpect(jsonPath("$.edges.length()").value(2))
                .andExpect(jsonPath("$.edges[0].relation").value("LOCATED_AT"));
    }

    @Test
    void objectsAtSiteReturns404WhenUnknownSite() throws Exception {
        when(queryService.objectsAtSite(eq("Site:UNKNOWN"), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "site not found"));
        mvc.perform(get("/topology/sites/Site:UNKNOWN/objects"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listSitesReturnsFlatSiteDto() throws Exception {
        when(queryService.listSites(any(), any())).thenReturn(
                new SiteListDto("core-ip", "SNAP-1", 1, List.of(
                        new SiteDto("Site:LON-DC1", "London DC1", 51.5, -0.12, "EU-West"))));

        mvc.perform(get("/topology/sites").param("domain", "core-ip"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.sites[0].siteId").value("Site:LON-DC1"))
                .andExpect(jsonPath("$.sites[0].latitude").value(51.5))   // flat geo fields
                .andExpect(jsonPath("$.sites[0].longitude").value(-0.12))
                .andExpect(jsonPath("$.sites[0].region").value("EU-West"));
    }

    @Test
    void getEdgeReturns400OnMalformedToken() throws Exception {
        when(queryService.getEdge(eq("!!!bad")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "malformed edgeId"));
        mvc.perform(get("/topology/edges/!!!bad"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getEdgeReturns404WhenAbsent() throws Exception {
        String token = EdgeId.encode("SNAP-1", "Port:p1", "HOSTS", "Interface:none");
        when(queryService.getEdge(eq(token)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "edge not found"));
        mvc.perform(get("/topology/edges/" + token))
                .andExpect(status().isNotFound());
    }
}
