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

    private static NodeDto node(String moid) {
        return new NodeDto(moid, moid.substring(0, moid.indexOf(':')), "core-ip", "SNAP-1", null,
                Map.of());
    }
}
