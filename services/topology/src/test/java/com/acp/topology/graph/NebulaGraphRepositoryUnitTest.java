package com.acp.topology.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.acp.topology.config.TopologyProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.vesoft.nebula.client.graph.net.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the nGQL REPOSITORY logic — the query/nGQL string construction, the domain-scoping
 * predicate (cross-domain opt-in, design test-plan row 21), the deterministic edge rank, and the
 * {@code ResultSet → GraphVertex/GraphEdge} mapping — exercised in ISOLATION with a mocked
 * {@link NebulaSessionProvider}/{@link Session} (no live NebulaGraph, no Docker). The end-to-end
 * behaviour over a real graph is additionally covered by the Testcontainers
 * {@link NebulaGraphRepositoryTest}; this class makes the string-building + mapping logic part of the
 * always-running unit gate / coverage.
 */
class NebulaGraphRepositoryUnitTest {

    private NebulaSessionProvider sessions;
    private NebulaSchemaBootstrap bootstrap;
    private Session session;
    private NebulaGraphRepository repo;
    private final List<String> executed = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessions = mock(NebulaSessionProvider.class);
        bootstrap = mock(NebulaSchemaBootstrap.class);
        session = mock(Session.class);
        executed.clear();

        // sessions.execute(work) just runs the work against the mocked session.
        when(sessions.execute(any())).thenAnswer(inv ->
                ((Function<Session, Object>) inv.getArgument(0)).apply(session));
        lenient().when(bootstrap.tagNames()).thenReturn(List.of("Node", "IPLink", "Site"));

        TopologyProperties properties = new TopologyProperties();
        properties.getNebula().setSpace("topology");
        repo = new NebulaGraphRepository(sessions, bootstrap, properties, new ObjectMapper());
    }

    /** Record every nGQL string the repository issues; the session returns an empty (no-row) RS. */
    private void recordAllAsEmpty() throws Exception {
        ResultSet empty = mock(ResultSet.class);
        lenient().when(empty.isSucceeded()).thenReturn(true);
        lenient().when(empty.rowsSize()).thenReturn(0);
        when(session.execute(anyString())).thenAnswer(inv -> {
            executed.add(inv.getArgument(0));
            return empty;
        });
    }

    @Test
    void neighborsBuildsGoQueryOverRequestedRelationsFromTheStartVid() throws Exception {
        recordAllAsEmpty();
        repo.neighbors("Node:PE1", List.of("RIDES_ON"), "core-ip", "SNAP-1", false);
        String go = lastGoQuery();
        assertThat(go).contains("GO FROM \"Node:PE1\"");
        assertThat(go).contains("OVER `RIDES_ON`");
        assertThat(go).contains("YIELD src(edge) AS src");

        // No relations → traverse over all edge types ("*").
        executed.clear();
        repo.neighbors("Node:PE1", List.of(), "core-ip", "SNAP-1", false);
        assertThat(lastGoQuery()).contains("OVER * ");
    }

    @Test
    void neighborsAppliesDomainScopePredicateInResultFilteringByDefaultAndOptsInOnCrossDomain()
            throws Exception {
        // Two neighbor rows: one in-domain (core-ip), one cross-domain (metro). Both in SNAP-1.
        // src, dst, rel, dom, sid, attrs
        ResultSet rows1 = neighborRows();
        when(session.execute(anyString())).thenReturn(rows1);
        List<GraphEdge> sameDomain = repo.neighbors("Node:PE1", List.of("RIDES_ON"),
                "core-ip", "SNAP-1", false);
        assertThat(sameDomain).extracting(GraphEdge::to).containsExactly("IPLink:L1"); // metro dropped

        ResultSet rows2 = neighborRows();
        when(session.execute(anyString())).thenReturn(rows2);
        List<GraphEdge> crossDomain = repo.neighbors("Node:PE1", List.of("RIDES_ON"),
                "core-ip", "SNAP-1", true);
        assertThat(crossDomain).extracting(GraphEdge::to)
                .containsExactlyInAnyOrder("IPLink:L1", "IPLink:M1"); // metro followed on opt-in
    }

    @Test
    void traversePinsDomainByDefaultAndDropsItWhenCrossDomain() throws Exception {
        recordAllAsEmpty();

        repo.traverse("Node:PE1", List.of("RIDES_ON", "TERMINATES"), 3, "core-ip", "SNAP-1", false);
        String scoped = lastGoQuery();
        assertThat(scoped).contains("GO 1 TO 3 STEPS FROM \"Node:PE1\"");
        assertThat(scoped).contains("OVER `RIDES_ON`,`TERMINATES`");
        assertThat(scoped).contains("properties(edge).snapshotId == \"SNAP-1\"");
        assertThat(scoped).contains("properties(edge).domain == \"core-ip\"");
        assertThat(scoped).contains("YIELD DISTINCT dst(edge) AS reached");

        executed.clear();
        repo.traverse("Node:PE1", List.of("RIDES_ON"), 3, "core-ip", "SNAP-1", true);
        String cross = lastGoQuery();
        assertThat(cross).contains("properties(edge).snapshotId == \"SNAP-1\"");
        assertThat(cross).doesNotContain("properties(edge).domain ==");
    }

    @Test
    void getNodeResolvesByIdViaFetchPropOnTheObjectTypeTag() throws Exception {
        // #213: by-id resolution must use FETCH PROP ON <tag> "<vid>" (the broken
        // `LOOKUP ... WHERE id(vertex) == ...` is a SemanticError / returns empty on this
        // NebulaGraph). The TAG is derived from the managedObjectId prefix (objectType); the
        // full managedObjectId is the VID.
        recordAllAsEmpty();
        repo.getNode("IPLink:L1", "core-ip", "SNAP-1");
        String fetch = executed.stream().filter(q -> q.startsWith("FETCH PROP"))
                .findFirst().orElseThrow();
        assertThat(fetch).contains("FETCH PROP ON `IPLink` \"IPLink:L1\"");
        assertThat(fetch).contains("id(vertex) AS moid");
        assertThat(fetch).contains("properties(vertex).objectType AS ot");
        assertThat(fetch).contains("properties(vertex).snapshotId AS sid");
        // No LOOKUP-by-id is issued for single-vertex resolution.
        assertThat(executed).noneMatch(q -> q.contains("id(vertex) =="));
    }

    @Test
    void getNodeFiltersOutSnapshotMismatch() throws Exception {
        // FETCH PROP returns the vertex; the post-filter rejects a snapshot that does not match the
        // requested scope (mirrors getEdge snapshot scoping). Build the RS fully BEFORE stubbing
        // session.execute (each str()/rowsOf() involves its own stubbing).
        List<ValueWrapper> row = List.of(str("IPLink:L1"), str("IPLink"), str("core-ip"),
                str("SNAP-OTHER"), str("L1"), str("{}"));
        ResultSet rs = rowsOf(row);
        when(session.execute(anyString())).thenReturn(rs);
        assertThat(repo.getNode("IPLink:L1", "core-ip", "SNAP-1")).isEmpty();
    }

    @Test
    void getNodeFiltersOutDomainMismatch() throws Exception {
        List<ValueWrapper> wrongDomain = List.of(str("IPLink:L1"), str("IPLink"), str("metro"),
                str("SNAP-1"), str("L1"), str("{}"));
        ResultSet rs = rowsOf(wrongDomain);
        when(session.execute(anyString())).thenReturn(rs);
        assertThat(repo.getNode("IPLink:L1", "core-ip", "SNAP-1")).isEmpty();
    }

    @Test
    void getEdgeFetchesByDeterministicRankFromDecodedKey() throws Exception {
        recordAllAsEmpty();
        EdgeId.Decoded key = new EdgeId.Decoded("SNAP-1", "Node:PE1", "RIDES_ON", "IPLink:L1");
        repo.getEdge(key);
        long rank = EdgeId.rank("Node:PE1", "RIDES_ON", "IPLink:L1");
        String fetch = executed.stream().filter(q -> q.startsWith("FETCH PROP")).findFirst().orElseThrow();
        assertThat(fetch).contains("FETCH PROP ON `RIDES_ON`");
        assertThat(fetch).contains("\"Node:PE1\" -> \"IPLink:L1\"@" + rank);
    }

    @Test
    void writeSnapshotBuildsInsertVertexAndEdgeWithDomainAndSnapshotProperties() throws Exception {
        recordAllAsEmpty();
        repo.writeSnapshot(
                List.of(new GraphVertex("Node:PE1", "Node", "core-ip", "SNAP-1", "PE1",
                        Map.of("vendor", "acme"))),
                List.of(new GraphEdge("Node:PE1", "IPLink:L1", "RIDES_ON", "core-ip", "SNAP-1",
                        Map.of())));
        String insertVertex = executed.stream().filter(q -> q.startsWith("INSERT VERTEX"))
                .findFirst().orElseThrow();
        assertThat(insertVertex).contains("INSERT VERTEX `Node`");
        assertThat(insertVertex).contains("\"Node:PE1\":(\"Node\", \"core-ip\", \"SNAP-1\"");
        assertThat(insertVertex).contains("{\\\"vendor\\\":\\\"acme\\\"}");

        String insertEdge = executed.stream().filter(q -> q.startsWith("INSERT EDGE"))
                .findFirst().orElseThrow();
        assertThat(insertEdge).contains("INSERT EDGE `RIDES_ON`");
        assertThat(insertEdge).contains("\"Node:PE1\"->\"IPLink:L1\"@"
                + EdgeId.rank("Node:PE1", "RIDES_ON", "IPLink:L1"));
    }

    @Test
    void mapsResultRowsToTypedVertexWithParsedAttributes() throws Exception {
        // A single LOOKUP row → GraphVertex with attributes parsed from the JSON-string property.
        // Build the value wrappers FIRST (each involves its own stubbing) before stubbing the row.
        List<ValueWrapper> row = List.of(
                str("IPLink:L1"), str("IPLink"), str("core-ip"), str("SNAP-1"), str("Link-1"),
                str("{\"capacity\":100}"));
        ResultSet rs = rowsOf(row);
        when(session.execute(anyString())).thenReturn(rs);

        Optional<GraphVertex> v = repo.getNode("IPLink:L1", "core-ip", "SNAP-1");
        assertThat(v).isPresent();
        assertThat(v.get().managedObjectId()).isEqualTo("IPLink:L1");
        assertThat(v.get().objectType()).isEqualTo("IPLink");
        assertThat(v.get().domain()).isEqualTo("core-ip");
        assertThat(v.get().name()).isEqualTo("Link-1");
        assertThat(v.get().attributes()).containsEntry("capacity", 100);
    }

    @Test
    void getEdgeMapsRowAndRejectsSnapshotMismatch() throws Exception {
        // FETCH PROP returns (relation, domain, snapshotId, attrs) for a matching snapshot.
        List<ValueWrapper> match = List.of(str("RIDES_ON"), str("core-ip"), str("SNAP-1"),
                str("{\"linkType\":\"backbone\"}"));
        ResultSet rsMatch = rowsOf(match);
        when(session.execute(anyString())).thenReturn(rsMatch);
        Optional<GraphEdge> edge = repo.getEdge(
                new EdgeId.Decoded("SNAP-1", "Node:PE1", "RIDES_ON", "IPLink:L1"));
        assertThat(edge).isPresent();
        assertThat(edge.get().relation()).isEqualTo("RIDES_ON");
        assertThat(edge.get().from()).isEqualTo("Node:PE1");
        assertThat(edge.get().to()).isEqualTo("IPLink:L1");
        assertThat(edge.get().attributes()).containsEntry("linkType", "backbone");

        // A stored edge from a DIFFERENT snapshot is not returned for this key (snapshot scoping).
        List<ValueWrapper> other = List.of(str("RIDES_ON"), str("core-ip"), str("SNAP-OTHER"),
                str("{}"));
        ResultSet rsOther = rowsOf(other);
        when(session.execute(anyString())).thenReturn(rsOther);
        assertThat(repo.getEdge(new EdgeId.Decoded("SNAP-1", "Node:PE1", "RIDES_ON", "IPLink:L1")))
                .isEmpty();
    }

    @Test
    void listNodesScopesByDomainSnapshotPerTagOrAcrossAllTagsWhenTypeOmitted() throws Exception {
        recordAllAsEmpty();
        repo.listNodes("IPLink", "core-ip", "SNAP-1");
        // A specific objectType → a single LOOKUP on that TAG.
        assertThat(executed.stream().filter(q -> q.startsWith("LOOKUP ON")).count()).isEqualTo(1L);
        assertThat(lastLookup()).contains("LOOKUP ON `IPLink`");
        assertThat(lastLookup()).contains("`IPLink`.domain == \"core-ip\"");

        // No objectType → one LOOKUP per bootstrapped tag (Node, IPLink, Site).
        executed.clear();
        repo.listNodes(null, "core-ip", "SNAP-1");
        assertThat(executed.stream().filter(q -> q.startsWith("LOOKUP ON")).count()).isEqualTo(3L);
    }

    @Test
    void distinctSnapshotIdsLooksUpEachTagAndDeduplicates() throws Exception {
        // Each per-tag LOOKUP yields snapshotIds; route by the TAG named in the query.
        ResultSet nodeRs = rowsOf(List.of(str("SNAP-1")));
        ResultSet ipLinkRs = rowsOf(List.of(str("SNAP-1")), List.of(str("SNAP-2")));
        ResultSet empty = rowsOf();
        routeLookupByTag(Map.of("`Node`", nodeRs, "`IPLink`", ipLinkRs), empty);
        assertThat(repo.distinctSnapshotIds()).containsExactly("SNAP-1", "SNAP-2");
    }

    @Test
    void deleteSnapshotLooksUpAndDeletesPerTagWithEdge() throws Exception {
        recordAllAsEmpty();
        repo.deleteSnapshot("SNAP-1");
        List<String> lookups = executed.stream().filter(q -> q.startsWith("LOOKUP ON")).toList();
        assertThat(lookups).hasSize(3); // one per bootstrapped tag
        assertThat(lookups.get(0)).contains(".snapshotId == \"SNAP-1\"");
        assertThat(lookups.get(0)).contains("DELETE VERTEX $-.v WITH EDGE");
    }

    @Test
    void objectsAtSiteTraversesLocatedAtReverselyThenResolvesEachDevice() throws Exception {
        // The located-device step uses GO ... LOCATED_AT REVERSELY; resolution uses FETCH PROP.
        // Here the located Node has no further connectivity (the closure step finds nothing), so the
        // device-level subgraph is just the located Node.
        ResultSet deviceRs = rowsOf(List.of(str("Node:PE1")));
        ResultSet nodeRs = rowsOf(List.of(str("Node:PE1"), str("Node"), str("core-ip"),
                str("SNAP-1"), str("PE1"), str("{}")));
        // The located-device GO carries LOCATED_AT REVERSELY; the closure GO carries OVER * and
        // excludes LOCATED_AT — route the located one to the device row, the closure ones to empty.
        ResultSet empty = rowsOf();
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            if (q.startsWith("FETCH PROP")) {
                return nodeRs;
            }
            if (q.contains("OVER LOCATED_AT REVERSELY")) {
                return deviceRs;
            }
            return empty; // closure GO * queries return no further objects
        });

        List<GraphVertex> objects = repo.objectsAtSite("Site:LON", "core-ip", "SNAP-1");
        assertThat(objects).extracting(GraphVertex::managedObjectId).containsExactly("Node:PE1");
        assertThat(executed.stream().anyMatch(q -> q.contains("OVER LOCATED_AT REVERSELY"))).isTrue();
    }

    @Test
    void objectsAtSiteExpandsHostedHierarchyAndConnectivityIntoTheDeviceSubgraph() throws Exception {
        // #245: from the located Node, the closure must pull in the hosted hierarchy and the logical
        // objects those members connect to, so the device-level subgraph is returned (not just the
        // located Node). Topology for the test (mirrors valid-all-core-ip-types.json around one site):
        //   Site:LON  <-LOCATED_AT-  Node:PE1
        //   Port:PE1-P3 -HOSTED_ON-> Node:PE1 ;  Port:PE1-P3 -HOSTS-> Interface:PE1-I1
        //   Interface:PE1-I1 -TERMINATES-> IPLink:L1
        // Expected in-scope set: Node:PE1, Port:PE1-P3, Interface:PE1-I1, IPLink:L1.
        // The closure issues, per member, GO OVER * (forward) and GO OVER * REVERSELY, excluding
        // LOCATED_AT; route the neighbor results by the source VID embedded in the GO query.
        routeSiteSubgraph();

        List<GraphVertex> objects = repo.objectsAtSite("Site:LON", "core-ip", "SNAP-1");
        assertThat(objects).extracting(GraphVertex::managedObjectId)
                .contains("Node:PE1", "Port:PE1-P3", "Interface:PE1-I1", "IPLink:L1");
        // The closure GO queries exclude LOCATED_AT so it never hops site→site.
        assertThat(executed.stream()
                .filter(q -> q.startsWith("GO FROM") && q.contains("OVER *"))
                .allMatch(q -> q.contains("type(edge) != \"LOCATED_AT\""))).isTrue();
    }

    @Test
    void objectsAtSiteDoesNotExpandOutwardFromANodeReachedViaConnectivity() throws Exception {
        // A backbone IPLink connects Interface:PE1-I1 (this site) to Interface:PE2-I1 (another site,
        // hosted on Node:PE2 located at a DIFFERENT site). The closure includes the incident far-end
        // objects reached BY connectivity, but must NOT expand a Node reached via connectivity into
        // ITS OWN hierarchy (that belongs to the other site). So Node:PE2's hierarchy is NOT pulled
        // in beyond what the IPLink directly touches.
        ResultSet siteDevices = rowsOf(List.of(str("Node:PE1")));
        ResultSet empty = rowsOf();
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            if (q.startsWith("FETCH PROP")) {
                return vertexRowFor(q);
            }
            if (q.contains("OVER LOCATED_AT REVERSELY")) {
                return siteDevices;
            }
            if (q.startsWith("GO FROM \"Node:PE1\"")) {
                // Node:PE1 connects (incoming) to Port:PE1-P3 over HOSTED_ON; route reverse only.
                return q.contains("REVERSELY") ? oneNeighbor("Port:PE1-P3") : empty;
            }
            if (q.startsWith("GO FROM \"Port:PE1-P3\"")) {
                // Port HOSTS Interface (forward); Port HOSTED_ON Node (forward, already in scope).
                return q.contains("REVERSELY") ? empty
                        : twoNeighbors("Interface:PE1-I1", "Node:PE1");
            }
            if (q.startsWith("GO FROM \"Interface:PE1-I1\"")) {
                // Interface TERMINATES IPLink:L1 (forward).
                return q.contains("REVERSELY") ? empty : oneNeighbor("IPLink:L1");
            }
            if (q.startsWith("GO FROM \"IPLink:L1\"")) {
                // IPLink connects to Node:PE2 (a placed node of another site) — included as incident,
                // but PE2 must NOT be expanded further.
                return q.contains("REVERSELY") ? oneNeighbor("Node:PE2") : empty;
            }
            // Any GO from Node:PE2 would be the over-expansion we must NOT see.
            return empty;
        });

        List<GraphVertex> objects = repo.objectsAtSite("Site:LON", "core-ip", "SNAP-1");
        assertThat(objects).extracting(GraphVertex::managedObjectId)
                .contains("Node:PE1", "Port:PE1-P3", "Interface:PE1-I1", "IPLink:L1", "Node:PE2");
        // The far-side Node was reached via connectivity, so the closure must NOT have issued a
        // GO from it (its hierarchy belongs to its own site).
        assertThat(executed.stream().noneMatch(q -> q.startsWith("GO FROM \"Node:PE2\"")
                && q.contains("OVER *"))).isTrue();
    }

    /** Route the per-member closure GO queries for the hosted-hierarchy + connectivity topology. */
    private void routeSiteSubgraph() throws Exception {
        ResultSet siteDevices = rowsOf(List.of(str("Node:PE1")));
        ResultSet empty = rowsOf();
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            try {
                if (q.startsWith("FETCH PROP")) {
                    return vertexRowFor(q);
                }
                if (q.contains("OVER LOCATED_AT REVERSELY")) {
                    return siteDevices;
                }
                if (q.startsWith("GO FROM \"Node:PE1\"")) {
                    return q.contains("REVERSELY") ? oneNeighbor("Port:PE1-P3") : empty;
                }
                if (q.startsWith("GO FROM \"Port:PE1-P3\"")) {
                    return q.contains("REVERSELY") ? empty
                            : twoNeighbors("Interface:PE1-I1", "Node:PE1");
                }
                if (q.startsWith("GO FROM \"Interface:PE1-I1\"")) {
                    return q.contains("REVERSELY") ? empty : oneNeighbor("IPLink:L1");
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return empty;
        });
    }

    /** A FETCH PROP vertex row for whichever VID the query addresses (objectType = the VID prefix). */
    private ResultSet vertexRowFor(String fetchQuery) throws Exception {
        int q1 = fetchQuery.indexOf('"');
        int q2 = fetchQuery.indexOf('"', q1 + 1);
        String vid = fetchQuery.substring(q1 + 1, q2);
        String ot = vid.substring(0, vid.indexOf(':'));
        return rowsOf(List.of(str(vid), str(ot), str("core-ip"), str("SNAP-1"), str(""), str("{}")));
    }

    private ResultSet oneNeighbor(String moid) throws Exception {
        return rowsOf(List.of(str(moid)));
    }

    private ResultSet twoNeighbors(String a, String b) throws Exception {
        return rowsOf(List.of(str(a)), List.of(str(b)));
    }

    @Test
    void traverseResolvesEachReachedVidIntoTypedVertex() throws Exception {
        // GO 1 TO n yields reached vid IPLink:L1; the subsequent getNode (FETCH PROP) resolves it.
        ResultSet reachedRs = rowsOf(List.of(str("IPLink:L1")));
        ResultSet nodeRs = rowsOf(List.of(str("IPLink:L1"), str("IPLink"), str("core-ip"),
                str("SNAP-1"), str("L1"), str("{}")));
        routeByPrefix(Map.of("GO 1 TO", reachedRs, "FETCH PROP", nodeRs));

        List<GraphVertex> reached = repo.traverse("Node:PE1", List.of("RIDES_ON"), 2,
                "core-ip", "SNAP-1", false);
        assertThat(reached).extracting(GraphVertex::managedObjectId).containsExactly("IPLink:L1");
    }

    @Test
    void edgesAmongReturnsIntraMemberAndLocatedAtEdgesOnly() throws Exception {
        // For each member, neighbors are scanned; keep edges whose target is a member OR LOCATED_AT.
        // Member set: {Node:PE1, IPLink:L1}. GO from Node:PE1 yields three neighbors:
        //   -> IPLink:L1 (member, RIDES_ON)  [kept]
        //   -> Site:LON  (LOCATED_AT)         [kept]
        //   -> IPLink:Z  (non-member, RIDES_ON) [dropped]
        // GO from IPLink:L1 yields none. Both routed via the GO prefix (the second member's GO
        // returns the same RS but its targets are non-members → dropped, so the assertion holds).
        ResultSet pe1Neighbors = rowsOf(
                List.of(str("Node:PE1"), str("IPLink:L1"), str("RIDES_ON"), str("core-ip"),
                        str("SNAP-1"), str("{}")),
                List.of(str("Node:PE1"), str("Site:LON"), str("LOCATED_AT"), str("core-ip"),
                        str("SNAP-1"), str("{}")));
        ResultSet l1Neighbors = rowsOf();
        // Route by the source VID embedded in the GO query.
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            if (q.startsWith("GO FROM \"Node:PE1\"")) {
                return pe1Neighbors;
            }
            return l1Neighbors;
        });

        List<GraphEdge> among = repo.edgesAmong(List.of("Node:PE1", "IPLink:L1"), "core-ip", "SNAP-1");
        assertThat(among).extracting(GraphEdge::to)
                .containsExactlyInAnyOrder("IPLink:L1", "Site:LON");
    }

    @Test
    void edgesAmongShortCircuitsOnEmptyMembership() {
        assertThat(repo.edgesAmong(List.of(), "core-ip", "SNAP-1")).isEmpty();
    }

    @Test
    void bootstrapSchemaDelegatesToBootstrap() {
        repo.bootstrapSchema();
        org.mockito.Mockito.verify(bootstrap).bootstrap();
    }

    @Test
    void edgeIdEncodeDecodeRoundTripsAndRankIsDeterministicAndNonNegative() {
        String token = EdgeId.encode("SNAP-1", "Node:PE1", "RIDES_ON", "IPLink:L1");
        EdgeId.Decoded decoded = EdgeId.decode(token);
        assertThat(decoded.snapshotId()).isEqualTo("SNAP-1");
        assertThat(decoded.from()).isEqualTo("Node:PE1");
        assertThat(decoded.relation()).isEqualTo("RIDES_ON");
        assertThat(decoded.to()).isEqualTo("IPLink:L1");

        long r1 = EdgeId.rank("Node:PE1", "RIDES_ON", "IPLink:L1");
        long r2 = EdgeId.rank("Node:PE1", "RIDES_ON", "IPLink:L1");
        assertThat(r1).isEqualTo(r2).isNotNegative();
        assertThat(EdgeId.rank("Node:PE1", "RIDES_ON", "IPLink:L2")).isNotEqualTo(r1);
    }

    // --- helpers --------------------------------------------------------------------------

    private ValueWrapper str(String s) throws Exception {
        ValueWrapper w = mock(ValueWrapper.class);
        when(w.isNull()).thenReturn(false);
        when(w.isString()).thenReturn(true);
        when(w.asString()).thenReturn(s);
        return w;
    }

    /**
     * Route {@code session.execute(ngql)} to a {@link ResultSet} chosen by the first query-prefix
     * key that the ngql starts with; anything else (e.g. {@code USE ...}) gets a succeeded empty RS.
     */
    private void routeByPrefix(Map<String, ResultSet> byPrefix) throws Exception {
        ResultSet empty = rowsOf();
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            for (Map.Entry<String, ResultSet> e : byPrefix.entrySet()) {
                if (q.startsWith(e.getKey())) {
                    return e.getValue();
                }
            }
            return empty;
        });
    }

    /**
     * Route per-tag {@code LOOKUP} queries to a {@link ResultSet} by the backtick-quoted TAG token
     * present in the ngql; unmatched LOOKUPs (and {@code USE ...}) get {@code fallback}.
     */
    private void routeLookupByTag(Map<String, ResultSet> byTag, ResultSet fallback) throws Exception {
        when(session.execute(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            executed.add(q);
            for (Map.Entry<String, ResultSet> e : byTag.entrySet()) {
                if (q.contains(e.getKey())) {
                    return e.getValue();
                }
            }
            return fallback;
        });
    }

    /** A successful {@link ResultSet} returning exactly the supplied rows. */
    @SafeVarargs
    private ResultSet rowsOf(List<ValueWrapper>... rows) {
        ResultSet rs = mock(ResultSet.class);
        when(rs.isSucceeded()).thenReturn(true);
        when(rs.rowsSize()).thenReturn(rows.length);
        for (int i = 0; i < rows.length; i++) {
            ResultSet.Record record = mock(ResultSet.Record.class);
            when(record.values()).thenReturn(rows[i]);
            when(rs.rowValues(i)).thenReturn(record);
        }
        return rs;
    }

    /** Two GO-neighbor rows (src, dst, rel, dom, sid, attrs): one core-ip, one metro. */
    private ResultSet neighborRows() throws Exception {
        List<ValueWrapper> inDomain = List.of(str("Node:PE1"), str("IPLink:L1"), str("RIDES_ON"),
                str("core-ip"), str("SNAP-1"), str("{}"));
        List<ValueWrapper> crossDomain = List.of(str("Node:PE1"), str("IPLink:M1"), str("RIDES_ON"),
                str("metro"), str("SNAP-1"), str("{}"));
        return rowsOf(inDomain, crossDomain);
    }

    private String lastGoQuery() {
        return executed.stream().filter(q -> q.startsWith("GO ")).reduce((a, b) -> b).orElseThrow();
    }

    private String lastLookup() {
        return executed.stream().filter(q -> q.startsWith("LOOKUP ON")).reduce((a, b) -> b).orElseThrow();
    }
}
