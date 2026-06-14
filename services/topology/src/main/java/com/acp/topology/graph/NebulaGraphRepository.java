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

    public NebulaGraphRepository(NebulaSessionProvider sessions, NebulaSchemaBootstrap bootstrap,
            TopologyProperties properties, ObjectMapper mapper) {
        this.sessions = sessions;
        this.bootstrap = bootstrap;
        this.config = properties.getNebula();
        this.mapper = mapper;
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
        String objectType = objectTypeOf(managedObjectId);
        return sessions.execute(session -> {
            use(session);
            // Resolve a vertex by its VID (== managedObjectId) via FETCH PROP. NebulaGraph does NOT
            // support an `id(vertex) == ...` predicate inside a LOOKOP WHERE (it raises a
            // SemanticError), so a single-vertex read MUST use FETCH PROP and validate the
            // domain/snapshot scope in-app — exactly as getEdge does for a single edge. This keeps
            // the read round-trip-consistent with what listSites/listNodes emit as the siteId/moid.
            String ngql = "FETCH PROP ON " + quoteTag(objectType) + " " + str(managedObjectId)
                    + " YIELD id(vertex) AS moid, properties(vertex).objectType AS ot, "
                    + "properties(vertex).domain AS dom, properties(vertex).snapshotId AS sid, "
                    + "properties(vertex).name AS nm, properties(vertex).attributes AS attrs;";
            ResultSet rs = execQuietly(session, ngql);
            if (rs == null || rs.rowsSize() == 0) {
                return Optional.empty();
            }
            GraphVertex v = toVertex(rs.rowValues(0).values());
            // Enforce the same domain + snapshot scope the LOOKUP-based reads apply.
            if (!domain.equals(v.domain()) || !snapshotId.equals(v.snapshotId())) {
                return Optional.empty();
            }
            return Optional.of(v);
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
        return sessions.execute(session -> {
            use(session);
            String ngql = "GO FROM " + str(siteId) + " OVER LOCATED_AT REVERSELY "
                    + "WHERE properties(edge).snapshotId == " + str(snapshotId)
                    + " AND properties(edge).domain == " + str(domain)
                    + " YIELD src(edge) AS device;";
            ResultSet rs = execQuietly(session, ngql);
            List<GraphVertex> out = new ArrayList<>();
            if (rs == null) {
                return out;
            }
            for (int i = 0; i < rs.rowsSize(); i++) {
                String moid = asString(rs.rowValues(i).values().get(0));
                getNode(moid, domain, snapshotId).ifPresent(out::add);
            }
            return out;
        });
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
        String objectType = objectTypeOf(moid);
        // FETCH PROP by VID (not a LOOKUP id(vertex) predicate, which NebulaGraph rejects); the
        // snapshot scope is enforced in-app on the returned row.
        String ngql = "FETCH PROP ON " + quoteTag(objectType) + " " + str(moid)
                + " YIELD properties(vertex).snapshotId AS sid, properties(vertex).domain AS dom;";
        ResultSet rs = execQuietly(session, ngql);
        if (rs == null || rs.rowsSize() == 0) {
            return null;
        }
        List<ValueWrapper> row = rs.rowValues(0).values();
        if (!snapshotId.equals(asString(row.get(0)))) {
            return null;
        }
        return asString(row.get(1));
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
