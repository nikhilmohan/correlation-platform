package com.acp.topology.graph;

import com.acp.topology.config.TopologyProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.data.ValueWrapper;
import com.vesoft.nebula.client.graph.net.Session;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * The single class that speaks nGQL — the NebulaGraph abstraction boundary (AC-19 / EH-9). It
 * issues nGQL over the nebula-java {@code Session} (provided by {@link NebulaSessionProvider}) and
 * returns ONLY the internal typed records ({@link GraphVertex} / {@link GraphEdge}); no NebulaGraph
 * host/space/raw rank/nGQL result leaks out of this package.
 *
 * <p>VID = {@code managedObjectId} (the natural key). Every vertex/edge carries {@code domain} +
 * {@code snapshotId} properties; reads scope with {@code WHERE … snapshotId == AND domain ==}
 * predicates (index-backed). TAG = {@code objectType}; EDGE type = {@code relation}.
 */
@Repository
public class NebulaGraphRepository implements GraphRepository {

    private static final Logger log = LoggerFactory.getLogger(NebulaGraphRepository.class);

    private final NebulaSessionProvider sessions;
    private final NebulaSchemaBootstrap bootstrap;
    private final TopologyProperties.Nebula config;
    private final ObjectMapper mapper;
    private final int traversalMaxDepth;

    public NebulaGraphRepository(NebulaSessionProvider sessions, NebulaSchemaBootstrap bootstrap,
            TopologyProperties properties, ObjectMapper mapper) {
        this.sessions = sessions;
        this.bootstrap = bootstrap;
        this.config = properties.getNebula();
        this.mapper = mapper;
        this.traversalMaxDepth = properties.getTraversal().getMaxDepth();
    }

    @Override
    public void bootstrapSchema() {
        bootstrap.bootstrap();
    }

    @Override
    public void writeSnapshot(List<GraphVertex> vertices, List<GraphEdge> edges) {
        sessions.execute(session -> {
            use(session);
            for (GraphVertex v : vertices) {
                String ngql = "INSERT VERTEX " + quoteTag(v.objectType())
                        + " (objectType, domain, snapshotId, name, attributes) VALUES "
                        + str(v.managedObjectId()) + ":(" + str(v.objectType()) + ", "
                        + str(v.domain()) + ", " + str(v.snapshotId()) + ", "
                        + str(v.name() == null ? "" : v.name()) + ", "
                        + str(toJson(v.attributes())) + ");";
                exec(session, ngql);
            }
            for (GraphEdge e : edges) {
                long rank = EdgeId.rank(e.from(), e.relation(), e.to());
                String ngql = "INSERT EDGE " + quoteTag(e.relation())
                        + " (relation, domain, snapshotId, attributes) VALUES "
                        + str(e.from()) + "->" + str(e.to()) + "@" + rank + ":("
                        + str(e.relation()) + ", " + str(e.domain()) + ", "
                        + str(e.snapshotId()) + ", " + str(toJson(e.attributes())) + ");";
                exec(session, ngql);
            }
            return null;
        });
    }

    @Override
    public void deleteSnapshot(String snapshotId) {
        sessions.execute(session -> {
            use(session);
            // Delete vertices tagged with the snapshotId (WITH EDGE removes incident edges too).
            for (String tag : bootstrap.tagNames()) {
                String lookup = "LOOKUP ON " + quoteTag(tag) + " WHERE " + quoteTag(tag)
                        + ".snapshotId == " + str(snapshotId) + " YIELD id(vertex) AS v "
                        + "| DELETE VERTEX $-.v WITH EDGE;";
                execQuietly(session, lookup);
            }
            return null;
        });
    }

    @Override
    public List<String> distinctSnapshotIds() {
        return sessions.execute(session -> {
            use(session);
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            for (String tag : bootstrap.tagNames()) {
                String ngql = "LOOKUP ON " + quoteTag(tag) + " YIELD " + quoteTag(tag)
                        + ".snapshotId AS sid;";
                ResultSet rs = execQuietly(session, ngql);
                if (rs == null) {
                    continue;
                }
                for (int i = 0; i < rs.rowsSize(); i++) {
                    ids.add(asString(rs.rowValues(i).values().get(0)));
                }
            }
            return new ArrayList<>(ids);
        });
    }

    @Override
    public Optional<GraphVertex> getNode(String managedObjectId, String domain, String snapshotId) {
        // #213: resolve a single vertex by its VID via FETCH PROP (NOT `LOOKUP ... WHERE
        // id(vertex) == ...`, which is a SemanticError / returns empty on this NebulaGraph). The
        // tag MUST be the correct one — FETCH PROP on the wrong tag returns EMPTY (not an error) —
        // so derive it from the managedObjectId prefix (`Node:N12` -> tag `Node`, the objectType
        // per the `<objectType>:<id>` scheme). Mirrors how getEdge() fetches by key.
        String objectType = objectTypeOf(managedObjectId);
        return sessions.execute(session -> {
            use(session);
            String ngql = "FETCH PROP ON " + quoteTag(objectType) + " " + str(managedObjectId)
                    + " YIELD id(vertex) AS moid, properties(vertex).objectType AS ot, "
                    + "properties(vertex).domain AS dom, properties(vertex).snapshotId AS sid, "
                    + "properties(vertex).name AS nm, properties(vertex).attributes AS attrs;";
            ResultSet rs = execQuietly(session, ngql);
            if (rs == null || rs.rowsSize() == 0) {
                return Optional.empty();
            }
            GraphVertex vertex = toVertex(rs.rowValues(0).values());
            // Scope to the requested domain + snapshotId (post-filter, as getEdge does for snapshot).
            if (!snapshotId.equals(vertex.snapshotId()) || !domain.equals(vertex.domain())) {
                return Optional.empty();
            }
            return Optional.of(vertex);
        });
    }

    @Override
    public List<GraphVertex> listNodes(String objectType, String domain, String snapshotId) {
        List<String> tags = objectType != null ? List.of(objectType) : bootstrap.tagNames();
        return sessions.execute(session -> {
            use(session);
            List<GraphVertex> out = new ArrayList<>();
            for (String tag : tags) {
                String ngql = "LOOKUP ON " + quoteTag(tag) + " WHERE " + quoteTag(tag)
                        + ".domain == " + str(domain) + " AND " + quoteTag(tag)
                        + ".snapshotId == " + str(snapshotId) + " YIELD id(vertex) AS moid, "
                        + quoteTag(tag) + ".objectType AS ot, " + quoteTag(tag) + ".domain AS dom, "
                        + quoteTag(tag) + ".snapshotId AS sid, " + quoteTag(tag) + ".name AS nm, "
                        + quoteTag(tag) + ".attributes AS attrs;";
                ResultSet rs = execQuietly(session, ngql);
                if (rs == null) {
                    continue;
                }
                for (int i = 0; i < rs.rowsSize(); i++) {
                    out.add(toVertex(rs.rowValues(i).values()));
                }
            }
            return out;
        });
    }

    @Override
    public Optional<GraphEdge> getEdge(EdgeId.Decoded key) {
        long rank = EdgeId.rank(key.from(), key.relation(), key.to());
        return sessions.execute(session -> {
            use(session);
            String ngql = "FETCH PROP ON " + quoteTag(key.relation()) + " " + str(key.from())
                    + " -> " + str(key.to()) + "@" + rank
                    + " YIELD properties(edge).relation AS relation, properties(edge).domain AS domain, "
                    + "properties(edge).snapshotId AS snapshotId, properties(edge).attributes AS attrs;";
            ResultSet rs = execQuietly(session, ngql);
            if (rs == null || rs.rowsSize() == 0) {
                return Optional.empty();
            }
            List<ValueWrapper> row = rs.rowValues(0).values();
            String snapshotId = asString(row.get(2));
            if (!key.snapshotId().equals(snapshotId)) {
                return Optional.empty();
            }
            return Optional.of(new GraphEdge(key.from(), key.to(), asString(row.get(0)),
                    asString(row.get(1)), snapshotId, parseAttrs(asString(row.get(3)))));
        });
    }

    @Override
    public List<GraphEdge> neighbors(String managedObjectId, List<String> relations, String domain,
            String snapshotId, boolean crossDomain) {
        String over = relations == null || relations.isEmpty() ? "*" : joinRelations(relations);
        return sessions.execute(session -> {
            use(session);
            String where = "WHERE $$.snapshotId == " + str(snapshotId)
                    + (crossDomain ? "" : " AND $$.domain == " + str(domain));
            String ngql = "GO FROM " + str(managedObjectId) + " OVER " + over + " "
                    + "YIELD src(edge) AS src, dst(edge) AS dst, type(edge) AS rel, "
                    + "properties(edge).domain AS dom, properties(edge).snapshotId AS sid, "
                    + "properties(edge).attributes AS attrs;";
            ResultSet rs = execQuietly(session, ngql);
            List<GraphEdge> out = new ArrayList<>();
            if (rs == null) {
                return out;
            }
            for (int i = 0; i < rs.rowsSize(); i++) {
                List<ValueWrapper> row = rs.rowValues(i).values();
                String sid = asString(row.get(4));
                String dom = asString(row.get(3));
                if (!snapshotId.equals(sid)) {
                    continue;
                }
                if (!crossDomain && !domain.equals(dom)) {
                    continue;
                }
                out.add(new GraphEdge(asString(row.get(0)), asString(row.get(1)),
                        asString(row.get(2)), dom, sid, parseAttrs(asString(row.get(5)))));
            }
            return out;
        });
    }

    @Override
    public List<GraphVertex> traverse(String start, List<String> relations, int maxDepth,
            String domain, String snapshotId, boolean crossDomain) {
        String over = joinRelations(relations);
        return sessions.execute(session -> {
            use(session);
            String ngql = "GO 1 TO " + maxDepth + " STEPS FROM " + str(start) + " OVER " + over + " "
                    + "WHERE properties(edge).snapshotId == " + str(snapshotId)
                    + (crossDomain ? "" : " AND properties(edge).domain == " + str(domain))
                    + " YIELD DISTINCT dst(edge) AS reached;";
            ResultSet rs = execQuietly(session, ngql);
            List<GraphVertex> out = new ArrayList<>();
            if (rs == null) {
                return out;
            }
            for (int i = 0; i < rs.rowsSize(); i++) {
                String moid = asString(rs.rowValues(i).values().get(0));
                getNode(moid, crossDomain ? domainOf(session, moid, snapshotId) : domain, snapshotId)
                        .ifPresent(out::add);
            }
            return out;
        });
    }

    @Override
    public List<GraphVertex> objectsAtSite(String siteId, String domain, String snapshotId) {
        // #245: the per-site projection must return the site's DEVICE-LEVEL SUBGRAPH, not only the
        // devices directly LOCATED_AT the site. From the located devices (Nodes) we expand:
        //   (a) their hosted hierarchy — LineCard / Port / Interface reachable over HOSTS / HOSTED_ON
        //       (containment, recursive), and
        //   (b) the logical objects those hierarchy members connect to over the multi-layer
        //       connectivity relations (TERMINATES → IPLink, ADJACENCY_OVER → IGPAdjacency,
        //       RIDES_ON → FiberSpan, TRAVERSES → LSP, SERVES → VPNService, MEMBER_OF → SRLG, …),
        // so the web-ui can render and toggle the logical layers from one call (P1-G8 / AC-22).
        // BFS is bounded by `topology.traversal.max-depth` and scoped to (domain, snapshotId); it
        // NEVER traverses LOCATED_AT (so it cannot hop into another site) and it does NOT expand
        // outward from a Node reached via connectivity (a placed Node is a site boundary — its own
        // hierarchy belongs to its own site), so a backbone link's far end is included as an
        // incident object without dragging in the far site's internal device graph.
        return sessions.execute(session -> {
            use(session);
            java.util.LinkedHashSet<String> located = new java.util.LinkedHashSet<>(
                    locatedDeviceIds(session, siteId, domain, snapshotId));
            java.util.LinkedHashSet<String> inScope = new java.util.LinkedHashSet<>(located);
            java.util.ArrayDeque<String> frontier = new java.util.ArrayDeque<>(located);
            int maxDepth = traversalMaxDepth;
            for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
                java.util.ArrayDeque<String> next = new java.util.ArrayDeque<>();
                for (String moid : frontier) {
                    for (String neighbor : connectedDeviceObjects(session, moid, domain, snapshotId)) {
                        if (inScope.add(neighbor)) {
                            // Do not expand outward from a Node reached via connectivity (it is a
                            // different site's placement boundary). The originally-located Nodes ARE
                            // expanded because they were seeded into the first frontier.
                            if (!"Node".equals(objectTypeOf(neighbor))) {
                                next.add(neighbor);
                            }
                        }
                    }
                }
                frontier = next;
            }
            List<GraphVertex> out = new ArrayList<>();
            for (String moid : inScope) {
                getNode(moid, domain, snapshotId).ifPresent(out::add);
            }
            return out;
        });
    }

    /** The Nodes directly LOCATED_AT the site (reverse over LOCATED_AT), scoped to (domain, snap). */
    private List<String> locatedDeviceIds(Session session, String siteId, String domain,
            String snapshotId) {
        String ngql = "GO FROM " + str(siteId) + " OVER LOCATED_AT REVERSELY "
                + "WHERE properties(edge).snapshotId == " + str(snapshotId)
                + " AND properties(edge).domain == " + str(domain)
                + " YIELD src(edge) AS device;";
        ResultSet rs = execQuietly(session, ngql);
        List<String> out = new ArrayList<>();
        if (rs == null) {
            return out;
        }
        for (int i = 0; i < rs.rowsSize(); i++) {
            out.add(asString(rs.rowValues(i).values().get(0)));
        }
        return out;
    }

    /**
     * The objects connected to {@code moid} over any relation EXCEPT LOCATED_AT, in BOTH directions
     * (containment HOSTS/HOSTED_ON is directional; connectivity may be incoming or outgoing), scoped
     * to (domain, snapshotId). LOCATED_AT is excluded so the closure never hops site→site.
     */
    private List<String> connectedDeviceObjects(Session session, String moid, String domain,
            String snapshotId) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (boolean reverse : new boolean[] {false, true}) {
            String ngql = "GO FROM " + str(moid) + " OVER * " + (reverse ? "REVERSELY " : "")
                    + "WHERE properties(edge).snapshotId == " + str(snapshotId)
                    + " AND properties(edge).domain == " + str(domain)
                    + " AND type(edge) != \"LOCATED_AT\" "
                    + "YIELD " + (reverse ? "src(edge)" : "dst(edge)") + " AS neighbor;";
            ResultSet rs = execQuietly(session, ngql);
            if (rs == null) {
                continue;
            }
            for (int i = 0; i < rs.rowsSize(); i++) {
                String neighbor = asString(rs.rowValues(i).values().get(0));
                if (neighbor != null) {
                    out.add(neighbor);
                }
            }
        }
        return new ArrayList<>(out);
    }

    @Override
    public List<GraphEdge> edgesAmong(List<String> memberIds, String domain, String snapshotId) {
        if (memberIds.isEmpty()) {
            return List.of();
        }
        java.util.Set<String> members = new java.util.HashSet<>(memberIds);
        List<GraphEdge> out = new ArrayList<>();
        for (String moid : memberIds) {
            for (GraphEdge e : neighbors(moid, List.of(), domain, snapshotId, false)) {
                // Intra-site edges (both endpoints in the device set) plus LOCATED_AT to the site.
                if (members.contains(e.to()) || "LOCATED_AT".equals(e.relation())) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    // --- helpers (all inside the graph/ boundary) -----------------------------------------

    private String domainOf(Session session, String moid, String snapshotId) {
        // #213: by-VID lookup via FETCH PROP (the wrong-tag fetch returns empty, so the tag is
        // derived from the moid prefix). `LOOKUP ... WHERE id(vertex) == ...` is a SemanticError /
        // returns empty on this NebulaGraph and must not be used for by-id resolution.
        String objectType = objectTypeOf(moid);
        String ngql = "FETCH PROP ON " + quoteTag(objectType) + " " + str(moid)
                + " YIELD properties(vertex).domain AS dom, properties(vertex).snapshotId AS sid;";
        ResultSet rs = execQuietly(session, ngql);
        if (rs == null || rs.rowsSize() == 0) {
            return null;
        }
        List<ValueWrapper> row = rs.rowValues(0).values();
        if (!snapshotId.equals(asString(row.get(1)))) {
            return null;
        }
        return asString(row.get(0));
    }

    private GraphVertex toVertex(List<ValueWrapper> row) {
        String name = asString(row.get(4));
        return new GraphVertex(asString(row.get(0)), asString(row.get(1)), asString(row.get(2)),
                asString(row.get(3)), (name == null || name.isEmpty()) ? null : name,
                parseAttrs(asString(row.get(5))));
    }

    private static String objectTypeOf(String managedObjectId) {
        int idx = managedObjectId.indexOf(':');
        return idx > 0 ? managedObjectId.substring(0, idx) : managedObjectId;
    }

    private static String joinRelations(List<String> relations) {
        List<String> quoted = new ArrayList<>();
        for (String r : relations) {
            quoted.add(quoteTag(r));
        }
        return String.join(",", quoted);
    }

    private void use(Session session) {
        exec(session, "USE " + quoteTag(config.getSpace()) + ";");
    }

    private void exec(Session session, String ngql) {
        try {
            ResultSet rs = session.execute(ngql);
            if (!rs.isSucceeded()) {
                throw new GraphAccessException("nGQL failed: " + rs.getErrorMessage());
            }
        } catch (GraphAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphAccessException("nGQL execution error", e);
        }
    }

    private ResultSet execQuietly(Session session, String ngql) {
        try {
            ResultSet rs = session.execute(ngql);
            if (!rs.isSucceeded()) {
                log.debug("nGQL non-success (treated as empty): {}", rs.getErrorMessage());
                return null;
            }
            return rs;
        } catch (Exception e) {
            log.debug("nGQL execution error (treated as empty)", e);
            return null;
        }
    }

    private String toJson(Map<String, Object> attributes) {
        try {
            return mapper.writeValueAsString(attributes == null ? Map.of() : attributes);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseAttrs(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String asString(ValueWrapper value) {
        try {
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isString()) {
                return value.asString();
            }
            return value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** nGQL string literal with embedded double-quotes escaped. */
    private static String str(String s) {
        return "\"" + (s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"")) + "\"";
    }

    /** Backtick-quote a TAG/EDGE/space identifier (defensive against reserved words). */
    private static String quoteTag(String identifier) {
        return "`" + identifier.replace("`", "") + "`";
    }
}
