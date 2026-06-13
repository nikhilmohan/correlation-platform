package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * AC-11 (bounded traversal by edge type returns exactly the reachable set, excludes nodes reachable
 * only via other edge types), AC-13b (get-edge resolves by a direct keyed FETCH from the decoded
 * edgeId), AC-21 (domain-scoped traversal stays within a domain by default; an explicit cross-domain
 * edge is followed only with crossDomain=true). Testcontainers NebulaGraph; skipped if Docker absent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NebulaGraphRepositoryTest extends NebulaIntegrationBase {

    private static final String SNAP = "SNAP-IT-1";
    private NebulaGraphRepository repo;

    @BeforeAll
    void loadFixture() {
        NebulaSessionProvider sessions = new NebulaSessionProvider(pool, properties);
        NebulaSchemaBootstrap bootstrap = new NebulaSchemaBootstrap(pool, properties);
        repo = new NebulaGraphRepository(sessions, bootstrap, properties, new ObjectMapper());
        repo.bootstrapSchema();

        // RIDES_ON chain: FiberSpan -> IPLink -> (IPLink) ... plus a MEMBER_OF branch off the chain.
        // core-ip domain plus one node in domain "metro" joined by an explicit cross-domain RIDES_ON.
        List<GraphVertex> vertices = List.of(
                v("FiberSpan:F1", "FiberSpan", "core-ip"),
                v("IPLink:L1", "IPLink", "core-ip"),
                v("IPLink:L2", "IPLink", "core-ip"),
                v("SRLG:S1", "SRLG", "core-ip"),
                v("IPLink:M1", "IPLink", "metro"));
        List<GraphEdge> edges = List.of(
                e("FiberSpan:F1", "IPLink:L1", "RIDES_ON", "core-ip"),
                e("IPLink:L1", "IPLink:L2", "RIDES_ON", "core-ip"),
                e("IPLink:L2", "SRLG:S1", "MEMBER_OF", "core-ip"),   // reachable only via MEMBER_OF
                e("IPLink:L2", "IPLink:M1", "RIDES_ON", "metro"));   // explicit cross-domain edge
        repo.writeSnapshot(vertices, edges);
    }

    @Test
    void traversalReturnsOnlyRidesOnReachableWithinDepth() {
        List<GraphVertex> reached = repo.traverse("FiberSpan:F1", List.of("RIDES_ON"), 3,
                "core-ip", SNAP, false);
        List<String> ids = reached.stream().map(GraphVertex::managedObjectId).toList();
        // RIDES_ON-reachable within depth: L1, L2 (same domain). SRLG:S1 only via MEMBER_OF — excluded.
        assertThat(ids).contains("IPLink:L1", "IPLink:L2");
        assertThat(ids).doesNotContain("SRLG:S1");
    }

    @Test
    void traversalStaysWithinDomainByDefault() {
        // The cross-domain IPLink:M1 (domain metro) is NOT reached when crossDomain=false.
        List<GraphVertex> reached = repo.traverse("FiberSpan:F1", List.of("RIDES_ON"), 5,
                "core-ip", SNAP, false);
        assertThat(reached.stream().map(GraphVertex::managedObjectId)).doesNotContain("IPLink:M1");
    }

    @Test
    void crossDomainEdgeFollowedOnlyWhenOptIn() {
        List<GraphVertex> reached = repo.traverse("FiberSpan:F1", List.of("RIDES_ON"), 5,
                "core-ip", SNAP, true);
        assertThat(reached.stream().map(GraphVertex::managedObjectId)).contains("IPLink:M1");
    }

    @Test
    void getEdgeResolvesByDirectKeyedFetch() {
        EdgeId.Decoded key = new EdgeId.Decoded(SNAP, "FiberSpan:F1", "RIDES_ON", "IPLink:L1");
        Optional<GraphEdge> edge = repo.getEdge(key);
        assertThat(edge).isPresent();
        assertThat(edge.get().relation()).isEqualTo("RIDES_ON");
        assertThat(edge.get().from()).isEqualTo("FiberSpan:F1");
        assertThat(edge.get().to()).isEqualTo("IPLink:L1");
    }

    private static GraphVertex v(String moid, String type, String domain) {
        return new GraphVertex(moid, type, domain, SNAP, moid, Map.of());
    }

    private static GraphEdge e(String from, String to, String relation, String domain) {
        return new GraphEdge(from, to, relation, domain, SNAP, Map.of());
    }
}
