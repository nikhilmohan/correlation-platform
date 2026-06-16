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
        // A colon-bearing-VID Node (#213) plus a Site it is LOCATED_AT, to exercise by-id resolution
        // (getNode), objectsAtSite (which funnels through getNode), and the traversal start-node path.
        // #254: two SITES (LON, FRA) each with their own placed device + hosted hierarchy, joined by
        // a backbone IPLink between their interfaces, to prove the per-site projection is SITE-SCOPED
        // (LON's result != FRA's result, far-site hierarchy not pulled in).
        List<GraphVertex> vertices = List.of(
                v("FiberSpan:F1", "FiberSpan", "core-ip"),
                v("IPLink:L1", "IPLink", "core-ip"),
                v("IPLink:L2", "IPLink", "core-ip"),
                v("SRLG:S1", "SRLG", "core-ip"),
                v("Node:N12", "Node", "core-ip"),
                v("Site:SITE1", "Site", "core-ip"),
                v("IPLink:M1", "IPLink", "metro"),
                // LON site subgraph
                v("Site:LON-01", "Site", "core-ip"),
                v("Node:LON-PE1", "Node", "core-ip"),
                v("Port:LON-PE1-P1", "Port", "core-ip"),
                v("Interface:LON-PE1-I1", "Interface", "core-ip"),
                // FRA site subgraph
                v("Site:FRA-01", "Site", "core-ip"),
                v("Node:FRA-PE1", "Node", "core-ip"),
                v("Port:FRA-PE1-P1", "Port", "core-ip"),
                v("Interface:FRA-PE1-I1", "Interface", "core-ip"),
                // backbone IPLink terminated by BOTH sites' interfaces (a site-spanning connectivity)
                v("IPLink:LON-FRA", "IPLink", "core-ip"));
        List<GraphEdge> edges = List.of(
                e("FiberSpan:F1", "IPLink:L1", "RIDES_ON", "core-ip"),
                e("IPLink:L1", "IPLink:L2", "RIDES_ON", "core-ip"),
                e("IPLink:L2", "SRLG:S1", "MEMBER_OF", "core-ip"),   // reachable only via MEMBER_OF
                e("Node:N12", "Site:SITE1", "LOCATED_AT", "core-ip"), // device located at the site
                e("IPLink:L2", "IPLink:M1", "RIDES_ON", "metro"),   // explicit cross-domain edge
                // LON hierarchy + placement
                e("Node:LON-PE1", "Site:LON-01", "LOCATED_AT", "core-ip"),
                e("Port:LON-PE1-P1", "Node:LON-PE1", "HOSTED_ON", "core-ip"),
                e("Port:LON-PE1-P1", "Interface:LON-PE1-I1", "HOSTS", "core-ip"),
                e("Interface:LON-PE1-I1", "IPLink:LON-FRA", "TERMINATES", "core-ip"),
                // FRA hierarchy + placement
                e("Node:FRA-PE1", "Site:FRA-01", "LOCATED_AT", "core-ip"),
                e("Port:FRA-PE1-P1", "Node:FRA-PE1", "HOSTED_ON", "core-ip"),
                e("Port:FRA-PE1-P1", "Interface:FRA-PE1-I1", "HOSTS", "core-ip"),
                e("Interface:FRA-PE1-I1", "IPLink:LON-FRA", "TERMINATES", "core-ip"));
        repo.writeSnapshot(vertices, edges);
    }

    // --- #213: single-vertex by-id resolution must FETCH PROP, not LOOKUP ... id(vertex). --------
    // These assertions can only be made against a REAL NebulaGraph (Testcontainers): a mock-Session
    // unit test returns whatever the stub is told to return and cannot distinguish FETCH PROP from
    // the broken LOOKUP-by-id nGQL — which is exactly why #203's mock-only test missed this bug.

    @Test
    void getNodeResolvesColonBearingVidById() {
        Optional<GraphVertex> node = repo.getNode("Node:N12", "core-ip", SNAP);
        assertThat(node).as("getNode must resolve the colon-bearing VID via FETCH PROP, not 404")
                .isPresent();
        assertThat(node.get().managedObjectId()).isEqualTo("Node:N12");
        assertThat(node.get().objectType()).isEqualTo("Node");
    }

    @Test
    void getNodeReturnsEmptyForUnknownId() {
        assertThat(repo.getNode("Node:DOES-NOT-EXIST", "core-ip", SNAP)).isEmpty();
    }

    @Test
    void objectsAtSiteResolvesDevicesViaGetNode() {
        // objectsAtSite -> getNode for each device; with the LOOKUP-by-id bug this returned empty -> 404.
        List<GraphVertex> objects = repo.objectsAtSite("Site:SITE1", "core-ip", SNAP);
        assertThat(objects.stream().map(GraphVertex::managedObjectId)).contains("Node:N12");
    }

    @Test
    void objectsAtSiteIsSiteScoped_twoSitesReturnDistinctSubgraphs() {
        // #254: each site's projection is its OWN bounded subgraph (located devices + hosted
        // hierarchy + depth-1 connectivity), NOT the whole component. LON and FRA must DIFFER.
        List<String> lon = repo.objectsAtSite("Site:LON-01", "core-ip", SNAP).stream()
                .map(GraphVertex::managedObjectId).toList();
        List<String> fra = repo.objectsAtSite("Site:FRA-01", "core-ip", SNAP).stream()
                .map(GraphVertex::managedObjectId).toList();

        // LON has its own located device + hosted hierarchy + the depth-1 backbone IPLink.
        assertThat(lon).contains("Node:LON-PE1", "Port:LON-PE1-P1", "Interface:LON-PE1-I1",
                "IPLink:LON-FRA");
        // LON must NOT contain FRA's EXCLUSIVE hosted objects (the far-site hierarchy).
        assertThat(lon).doesNotContain("Node:FRA-PE1", "Port:FRA-PE1-P1", "Interface:FRA-PE1-I1");
        // Symmetrically for FRA.
        assertThat(fra).contains("Node:FRA-PE1", "Port:FRA-PE1-P1", "Interface:FRA-PE1-I1",
                "IPLink:LON-FRA");
        assertThat(fra).doesNotContain("Node:LON-PE1", "Port:LON-PE1-P1", "Interface:LON-PE1-I1");
        // The two site subgraphs are genuinely DISTINCT (not the same whole-graph payload).
        assertThat(lon).isNotEqualTo(fra);
        // Neither site pulls in the other, unrelated component (the RIDES_ON chain / SITE1 devices).
        assertThat(lon).doesNotContain("Node:N12", "IPLink:L1", "FiberSpan:F1");
        assertThat(fra).doesNotContain("Node:N12", "IPLink:L1", "FiberSpan:F1");
    }

    @Test
    void traverseStartNodePathResolvesById() {
        // The traversal reached-node assembly funnels each hop through getNode; assert it resolves.
        List<GraphVertex> reached = repo.traverse("FiberSpan:F1", List.of("RIDES_ON"), 3,
                "core-ip", SNAP, false);
        assertThat(reached).as("reached nodes are resolved by-id via getNode").isNotEmpty();
        assertThat(reached.stream().map(GraphVertex::managedObjectId)).contains("IPLink:L1");
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
