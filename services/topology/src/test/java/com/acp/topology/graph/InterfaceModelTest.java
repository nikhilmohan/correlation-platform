package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.topology.TestFixtures;
import com.acp.topology.ingest.LiftingService;
import com.acp.topology.ingest.SnapshotFile;
import com.acp.topology.ingest.SnapshotValidationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * AC-25 / design row 32 (Interface model lifts and is queryable end-to-end — Port HOSTS Interface
 * TERMINATES IPLink, ADJACENCY_OVER between interfaces): the generic typed-graph machinery lifts the
 * {@code with-interfaces.json} snapshot, and the query API resolves the layering. Testcontainers
 * NebulaGraph; skipped if Docker absent. The lift-only portion is also covered (Docker-free) by
 * {@code LiftingServiceTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InterfaceModelTest extends NebulaIntegrationBase {

    private static final String SNAP = "SNAP-IF-IT";
    private NebulaGraphRepository repo;

    @BeforeAll
    void loadInterfaceSnapshot() {
        NebulaSessionProvider sessions = new NebulaSessionProvider(pool, properties);
        NebulaSchemaBootstrap bootstrap = new NebulaSchemaBootstrap(pool, properties);
        repo = new NebulaGraphRepository(sessions, bootstrap, properties, new ObjectMapper());
        repo.bootstrapSchema();

        SnapshotFile file = new SnapshotValidationService(new ObjectMapper())
                .validate(TestFixtures.snapshot("with-interfaces.json"));
        LiftingService.Lifted lifted = new LiftingService().lift(file, SNAP);
        repo.writeSnapshot(lifted.vertices(), lifted.edges());
    }

    @Test
    void listsInterfacesByObjectType() {
        List<GraphVertex> interfaces = repo.listNodes("Interface", "core-ip", SNAP);
        assertThat(interfaces).extracting(GraphVertex::managedObjectId)
                .containsExactly("Interface:PE1-LC2-P3-100");
    }

    @Test
    void neighborsResolvePortHostsInterfaceTerminatesIpLink() {
        // From a Port, the HOSTS neighbor is its Interface.
        List<GraphEdge> hosts = repo.neighbors("Port:PE1-LC2-P3", List.of("HOSTS"), "core-ip",
                SNAP, false);
        assertThat(hosts).extracting(GraphEdge::to).containsExactly("Interface:PE1-LC2-P3-100");

        // From that Interface, the TERMINATES neighbor is its IPLink.
        List<GraphEdge> terminates = repo.neighbors("Interface:PE1-LC2-P3-100",
                List.of("TERMINATES"), "core-ip", SNAP, false);
        assertThat(terminates).extracting(GraphEdge::to).containsExactly("IPLink:PE1-PE2-1");
    }

    @Test
    void adjacencyOverResolvesBetweenInterfaces() {
        // ADJACENCY_OVER runs between an Interface and the IGPAdjacency (not directly between nodes).
        List<GraphEdge> adj = repo.neighbors("Interface:PE1-LC2-P3-100", List.of("ADJACENCY_OVER"),
                "core-ip", SNAP, false);
        assertThat(adj).extracting(GraphEdge::to).containsExactly("IGPAdjacency:PE1-PE2");
    }

    @Test
    void resolvesInterfaceManagedObjectIdToTypedVertex() {
        GraphVertex iface = repo.getNode("Interface:PE1-LC2-P3-100", "core-ip", SNAP).orElseThrow();
        assertThat(iface.objectType()).isEqualTo("Interface"); // layer == objectType
        assertThat(iface.attributes()).containsEntry("ipAddress", "10.0.0.1");
    }

    @Test
    void hostsThenTerminatesTraversalWalksPortToInterfaceToIpLink() {
        List<GraphVertex> reached = repo.traverse("Port:PE1-LC2-P3",
                List.of("HOSTS", "TERMINATES"), 2, "core-ip", SNAP, false);
        assertThat(reached).extracting(GraphVertex::managedObjectId)
                .contains("Interface:PE1-LC2-P3-100", "IPLink:PE1-PE2-1");
    }
}
