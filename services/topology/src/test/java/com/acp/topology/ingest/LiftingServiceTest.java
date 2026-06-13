package com.acp.topology.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.topology.TestFixtures;
import com.acp.topology.graph.GraphEdge;
import com.acp.topology.graph.GraphVertex;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AC-10 (lift), AC-20 (attributes verbatim), AC-24 (Interface lift), AC-25 (Interface/HOSTS/
 * TERMINATES/ADJACENCY_OVER lift via the generic typed path): the lifter maps every flat node
 * record to a typed vertex (TAG = objectType, incl. Site/Interface) and every flat edge record to a
 * typed edge (EDGE type = relation, incl. LOCATED_AT/HOSTS/TERMINATES), with no special-casing.
 */
class LiftingServiceTest {

    private LiftingService lifting;
    private SnapshotValidationService validation;

    @BeforeEach
    void setUp() {
        lifting = new LiftingService();
        validation = new SnapshotValidationService(new ObjectMapper());
    }

    @Test
    void liftsAllCoreIpTypesWithCorrectTagsAndEdges() {
        SnapshotFile file = validation.validate(TestFixtures.snapshot("valid-all-core-ip-types.json"));
        LiftingService.Lifted lifted = lifting.lift(file, "SNAP-LIFT-1");

        // Each node lifts to a vertex whose objectType == its managedObjectId prefix (TAG selection
        // is data-driven, not a switch over Core-IP semantics). All 9 types + Site + Interface.
        assertThat(lifted.vertices()).hasSize(11);
        for (GraphVertex v : lifted.vertices()) {
            String prefix = v.managedObjectId().substring(0, v.managedObjectId().indexOf(':'));
            assertThat(v.objectType()).isEqualTo(prefix);
            assertThat(v.domain()).isEqualTo("core-ip");
            assertThat(v.snapshotId()).isEqualTo("SNAP-LIFT-1");
        }
        assertThat(objectTypes(lifted.vertices())).containsExactlyInAnyOrder(
                "Node", "LineCard", "Port", "Interface", "IPLink", "IGPAdjacency",
                "LSP", "VPNService", "FiberSpan", "SRLG", "Site");

        // Each edge lifts to the correct typed relation, incl. LOCATED_AT / HOSTS / TERMINATES.
        assertThat(lifted.edges()).hasSize(10);
        for (GraphEdge e : lifted.edges()) {
            assertThat(e.domain()).isEqualTo("core-ip");
            assertThat(e.snapshotId()).isEqualTo("SNAP-LIFT-1");
        }
        assertThat(relations(lifted.edges())).contains(
                "HOSTED_ON", "HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER",
                "TRAVERSES", "SERVES", "MEMBER_OF", "LOCATED_AT");
    }

    @Test
    void preservesAttributesVerbatim() {
        SnapshotFile file = validation.validate(TestFixtures.snapshot("valid-all-core-ip-types.json"));
        LiftingService.Lifted lifted = lifting.lift(file, "SNAP-LIFT-2");

        GraphVertex node = vertex(lifted.vertices(), "Node:PE1");
        assertThat(node.attributes())
                .containsEntry("vendor", "acme")
                .containsEntry("model", "X9")
                .containsEntry("equipmentType", "router")
                .containsEntry("role", "PE");

        GraphVertex iplink = vertex(lifted.vertices(), "IPLink:PE1-PE2-1");
        assertThat(iplink.attributes())
                .containsEntry("linkType", "backbone")
                .containsEntry("capacity", "100G");
    }

    @Test
    void liftsSiteAndLocatedAt() {
        SnapshotFile file = validation.validate(TestFixtures.snapshot("with-sites.json"));
        LiftingService.Lifted lifted = lifting.lift(file, "SNAP-SITE-1");

        GraphVertex site = vertex(lifted.vertices(), "Site:LON-DC1");
        assertThat(site.objectType()).isEqualTo("Site");
        // Geo attributes preserved (the read service lifts these out into the flat SiteDto).
        assertThat(site.attributes())
                .containsEntry("latitude", 51.5)
                .containsEntry("longitude", -0.12)
                .containsEntry("region", "EU-West");
        assertThat(relations(lifted.edges())).contains("LOCATED_AT");
    }

    @Test
    void liftsInterfaceAndHostsAndTerminates() {
        SnapshotFile file = validation.validate(TestFixtures.snapshot("with-interfaces.json"));
        LiftingService.Lifted lifted = lifting.lift(file, "SNAP-IF-1");

        GraphVertex iface = vertex(lifted.vertices(), "Interface:PE1-LC2-P3-100");
        assertThat(iface.objectType()).isEqualTo("Interface");
        // No Interface-specific code path — the typed edges emerge purely from the file's relations.
        assertThat(relations(lifted.edges()))
                .contains("HOSTS", "TERMINATES", "ADJACENCY_OVER");

        // HOSTS goes Port -> Interface; TERMINATES goes Interface -> IPLink.
        GraphEdge hosts = edge(lifted.edges(), "HOSTS");
        assertThat(hosts.from()).isEqualTo("Port:PE1-LC2-P3");
        assertThat(hosts.to()).isEqualTo("Interface:PE1-LC2-P3-100");
        GraphEdge terminates = edge(lifted.edges(), "TERMINATES");
        assertThat(terminates.from()).isEqualTo("Interface:PE1-LC2-P3-100");
        assertThat(terminates.to()).isEqualTo("IPLink:PE1-PE2-1");
    }

    private static List<String> objectTypes(List<GraphVertex> vertices) {
        return vertices.stream().map(GraphVertex::objectType).toList();
    }

    private static List<String> relations(List<GraphEdge> edges) {
        return edges.stream().map(GraphEdge::relation).toList();
    }

    private static GraphVertex vertex(List<GraphVertex> vertices, String moid) {
        return vertices.stream().filter(v -> v.managedObjectId().equals(moid)).findFirst()
                .orElseThrow();
    }

    private static GraphEdge edge(List<GraphEdge> edges, String relation) {
        return edges.stream().filter(e -> e.relation().equals(relation)).findFirst().orElseThrow();
    }
}
