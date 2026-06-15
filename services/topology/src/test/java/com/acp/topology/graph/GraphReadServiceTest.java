package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.acp.topology.api.dto.EdgeDto;
import com.acp.topology.api.dto.NodeDto;
import com.acp.topology.api.dto.SiteDto;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AC-13b (edge fetch by decoded key), AC-22/29 (site objects = nodes AND edges; edges are intra-site
 * plus LOCATED_AT), AC-30 (NodeDto with layer == objectType), AC-21 (flat SiteDto geo). The
 * GraphRepository port is mocked — the read service's mapping + site-edge selection are unit-tested.
 */
@ExtendWith(MockitoExtension.class)
class GraphReadServiceTest {

    @Mock
    private GraphRepository repository;

    private GraphReadService service;

    @BeforeEach
    void setUp() {
        service = new GraphReadService(repository);
    }

    @Test
    void getNodeMapsToFrozenNodeDtoWithLayerEqualsObjectType() {
        when(repository.getNode("Node:PE1", "core-ip", "SNAP-1")).thenReturn(Optional.of(
                new GraphVertex("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1",
                        Map.of("vendor", "acme"))));

        NodeDto dto = service.getNode("Node:PE1", "core-ip", "SNAP-1").orElseThrow();
        assertThat(dto.managedObjectId()).isEqualTo("Node:PE1");
        assertThat(dto.objectType()).isEqualTo("Node"); // layer == objectType (no separate field)
        assertThat(dto.domain()).isEqualTo("core-ip");
        assertThat(dto.snapshotId()).isEqualTo("SNAP-1");
        assertThat(dto.attributes()).containsEntry("vendor", "acme");
    }

    @Test
    void getEdgeDecodesKeyAndMapsToEdgeDtoWithRoundTrippableId() {
        EdgeId.Decoded key = new EdgeId.Decoded("SNAP-1", "Port:p1", "HOSTS", "Interface:i1");
        when(repository.getEdge(key)).thenReturn(Optional.of(
                new GraphEdge("Port:p1", "Interface:i1", "HOSTS", "core-ip", "SNAP-1", Map.of())));

        EdgeDto dto = service.getEdge(key).orElseThrow();
        assertThat(dto.from()).isEqualTo("Port:p1");
        assertThat(dto.to()).isEqualTo("Interface:i1");
        assertThat(dto.relation()).isEqualTo("HOSTS");
        // The returned edgeId round-trips back into GET /topology/edges/{edgeId}.
        assertThat(EdgeId.decode(dto.edgeId())).isEqualTo(key);
    }

    @Test
    void listSitesReturnsFlatSiteDtoWithGeoLiftedOutOfAttributes() {
        when(repository.listNodes("Site", "core-ip", "SNAP-1")).thenReturn(List.of(
                new GraphVertex("Site:LON-DC1", "Site", "core-ip", "SNAP-1", "London DC1",
                        Map.of("latitude", 51.5, "longitude", -0.12, "region", "EU-West"))));

        List<SiteDto> sites = service.listSites("core-ip", "SNAP-1");
        assertThat(sites).hasSize(1);
        SiteDto s = sites.get(0);
        assertThat(s.siteId()).isEqualTo("Site:LON-DC1"); // siteId == managedObjectId
        assertThat(s.name()).isEqualTo("London DC1");
        assertThat(s.latitude()).isEqualTo(51.5);   // flat geo, not nested under attributes
        assertThat(s.longitude()).isEqualTo(-0.12);
        assertThat(s.region()).isEqualTo("EU-West");
    }

    @Test
    void edgesForSiteAreIntraSiteAndLocatedAt() {
        // The two devices at the site, plus their edges (LOCATED_AT to site + an intra-site edge).
        List<NodeDto> devices = List.of(
                node("Node:PE1"), node("Node:PE2"));
        when(repository.edgesAmong(List.of("Node:PE1", "Node:PE2"), "core-ip", "SNAP-1"))
                .thenReturn(List.of(
                        new GraphEdge("Node:PE1", "Site:LON-DC1", "LOCATED_AT", "core-ip", "SNAP-1",
                                Map.of()),
                        new GraphEdge("Node:PE1", "Node:PE2", "ADJACENCY_OVER", "core-ip", "SNAP-1",
                                Map.of())));

        List<EdgeDto> edges = service.edgesForSite("Site:LON-DC1", devices, "core-ip", "SNAP-1");
        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(EdgeDto::relation)
                .containsExactlyInAnyOrder("LOCATED_AT", "ADJACENCY_OVER");
    }

    @Test
    void edgesForSiteIncludesMultiLayerConnectivityEdgesOfTheDeviceSubgraph() {
        // #245: given the EXPANDED device-level node set (located Node + hosted hierarchy +
        // connected logical objects), the per-site edge set must include the multi-layer
        // connectivity edges the web-ui maps to logical layers — not only LOCATED_AT.
        List<NodeDto> nodes = List.of(
                node("Node:PE1"), node("LineCard:PE1-LC2"), node("Port:PE1-P3"),
                node("Interface:PE1-I1"), node("IPLink:L1"), node("IGPAdjacency:A1"),
                node("FiberSpan:F1"));
        List<String> ids = List.of("Node:PE1", "LineCard:PE1-LC2", "Port:PE1-P3",
                "Interface:PE1-I1", "IPLink:L1", "IGPAdjacency:A1", "FiberSpan:F1");
        when(repository.edgesAmong(ids, "core-ip", "SNAP-1")).thenReturn(List.of(
                edge("Node:PE1", "Site:LON-DC1", "LOCATED_AT"),
                edge("Port:PE1-P3", "Node:PE1", "HOSTED_ON"),
                edge("Port:PE1-P3", "Interface:PE1-I1", "HOSTS"),
                edge("Interface:PE1-I1", "IPLink:L1", "TERMINATES"),
                edge("Interface:PE1-I1", "IGPAdjacency:A1", "ADJACENCY_OVER"),
                edge("FiberSpan:F1", "IPLink:L1", "RIDES_ON")));

        List<EdgeDto> edges = service.edgesForSite("Site:LON-DC1", nodes, "core-ip", "SNAP-1");
        assertThat(edges).extracting(EdgeDto::relation)
                .contains("LOCATED_AT", "HOSTED_ON", "HOSTS", "TERMINATES", "ADJACENCY_OVER",
                        "RIDES_ON");
        // At least one true device-level connectivity edge (not just LOCATED_AT) is present.
        assertThat(edges).anyMatch(e -> !"LOCATED_AT".equals(e.relation()));
    }

    @Test
    void traverseEdgesAsksForRelationScopedEdgesOfTheClosureSet() {
        // #252: GraphReadService builds the closure member set (start + reached node ids) and asks
        // the repository for the relation-scoped edges among that set, then maps to EdgeDto.
        List<NodeDto> reached = List.of(node("IPLink:L1"), node("IGPAdjacency:A1"));
        List<String> relations = List.of("TERMINATES", "ADJACENCY_OVER");
        when(repository.edgesAmong(
                eq(List.of("Interface:I1", "IPLink:L1", "IGPAdjacency:A1")),
                eq(relations), eq("core-ip"), eq("SNAP-1")))
                .thenReturn(List.of(
                        edge("Interface:I1", "IPLink:L1", "TERMINATES"),
                        edge("IPLink:L1", "IGPAdjacency:A1", "ADJACENCY_OVER")));

        List<EdgeDto> edges = service.traverseEdges("Interface:I1", reached, relations, "core-ip",
                "SNAP-1");
        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(EdgeDto::from, EdgeDto::to, EdgeDto::relation)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Interface:I1", "IPLink:L1",
                                "TERMINATES"),
                        org.assertj.core.groups.Tuple.tuple("IPLink:L1", "IGPAdjacency:A1",
                                "ADJACENCY_OVER"));
        // The returned edgeIds round-trip back into GET /topology/edges/{edgeId}.
        assertThat(EdgeId.decode(edges.get(0).edgeId()).from()).isEqualTo("Interface:I1");
    }

    @Test
    void traverseEdgesReturnsEmptyNotNullForAnEdgelessClosure() {
        // #252: when the repository finds no closure edges, the result is empty (never null).
        when(repository.edgesAmong(any(List.class), any(List.class), eq("core-ip"), eq("SNAP-1")))
                .thenReturn(List.of());
        List<EdgeDto> edges = service.traverseEdges("Node:PE1", List.of(), List.of("RIDES_ON"),
                "core-ip", "SNAP-1");
        assertThat(edges).isNotNull().isEmpty();
    }

    private static GraphEdge edge(String from, String to, String relation) {
        return new GraphEdge(from, to, relation, "core-ip", "SNAP-1", Map.of());
    }

    private static NodeDto node(String moid) {
        return new NodeDto(moid, moid.substring(0, moid.indexOf(':')), "core-ip", "SNAP-1", null,
                Map.of());
    }
}
