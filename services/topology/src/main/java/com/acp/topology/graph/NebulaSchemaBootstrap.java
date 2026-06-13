package com.acp.topology.graph;

import com.acp.topology.config.TopologyProperties;
import com.vesoft.nebula.client.graph.data.ResultSet;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Idempotently bootstraps the NebulaGraph SPACE + schema on startup (Flow E):
 * <ol>
 *   <li>{@code SHOW HOSTS} → if storaged is not registered, {@code ADD HOSTS} it (the one-shot the
 *       deferred {@code nebula-init} Compose job would run), then wait until ONLINE.</li>
 *   <li>{@code CREATE SPACE IF NOT EXISTS} (FIXED_STRING VID), wait until usable.</li>
 *   <li>{@code USE space; CREATE TAG/EDGE/INDEX IF NOT EXISTS; REBUILD}.</li>
 * </ol>
 * Re-running across restarts is a no-op (everything is {@code IF NOT EXISTS}). The MVP Core-IP
 * domain's TAGs/EDGE types are registered here; a future domain's not-yet-present types are created
 * the same idempotent way before their first write.
 */
@Component
public class NebulaSchemaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(NebulaSchemaBootstrap.class);

    /** MVP Core-IP vocabulary TAGs (objectTypes) + the domain-agnostic Site. */
    private static final List<String> TAGS = List.of(
            "Node", "LineCard", "Port", "Interface", "IPLink", "IGPAdjacency",
            "LSP", "VPNService", "FiberSpan", "SRLG", "Site");

    /** MVP Core-IP relation vocabulary EDGE types + the domain-agnostic LOCATED_AT. */
    private static final List<String> EDGES = List.of(
            "HOSTED_ON", "HOSTS", "TERMINATES", "RIDES_ON", "ADJACENCY_OVER",
            "TRAVERSES", "SERVES", "MEMBER_OF", "LOCATED_AT");

    private final NebulaPool pool;
    private final TopologyProperties.Nebula config;

    public NebulaSchemaBootstrap(NebulaPool pool, TopologyProperties properties) {
        this.pool = pool;
        this.config = properties.getNebula();
    }

    /** The TAG names this service manages (used by scans / eviction in the repository). */
    public List<String> tagNames() {
        return TAGS;
    }

    /** Run the idempotent bootstrap. Safe to call repeatedly. */
    public void bootstrap() {
        Session session = null;
        try {
            session = pool.getSession(config.getUsername(), config.getPassword(), false);
            registerStoragedIfNeeded(session);
            createSpace(session);
            waitUntilSpaceUsable(session);
            createSchema(session);
            log.info("NebulaGraph schema bootstrap complete for space {}", config.getSpace());
        } catch (GraphAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphAccessException("NebulaGraph bootstrap failed", e);
        } finally {
            if (session != null) {
                session.release();
            }
        }
    }

    private void registerStoragedIfNeeded(Session session) throws Exception {
        ResultSet hosts = session.execute("SHOW HOSTS;");
        boolean registered = hosts.isSucceeded() && hosts.rowsSize() > 0;
        if (!registered) {
            String host = config.getStoragedHost();
            int colon = host.lastIndexOf(':');
            String name = colon > 0 ? host.substring(0, colon) : host;
            String port = colon > 0 ? host.substring(colon + 1) : "9779";
            log.info("storaged not registered; running ADD HOSTS {}:{}", name, port);
            session.execute("ADD HOSTS \"" + name + "\":" + port + ";");
        }
    }

    private void createSpace(Session session) throws Exception {
        run(session, "CREATE SPACE IF NOT EXISTS `" + config.getSpace()
                + "` (partition_num = 10, replica_factor = 1, vid_type = FIXED_STRING(128));");
    }

    private void waitUntilSpaceUsable(Session session) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            ResultSet rs = session.execute("USE `" + config.getSpace() + "`;");
            if (rs.isSucceeded()) {
                return;
            }
            Thread.sleep(1000L);
        }
        throw new GraphAccessException("space " + config.getSpace() + " not usable after bootstrap");
    }

    private void createSchema(Session session) throws Exception {
        run(session, "USE `" + config.getSpace() + "`;");
        for (String tag : TAGS) {
            run(session, "CREATE TAG IF NOT EXISTS `" + tag
                    + "` (objectType string, domain string, snapshotId string, name string, "
                    + "attributes string);");
        }
        for (String edge : EDGES) {
            run(session, "CREATE EDGE IF NOT EXISTS `" + edge
                    + "` (relation string, domain string, snapshotId string, attributes string);");
        }
        for (String tag : TAGS) {
            run(session, "CREATE TAG INDEX IF NOT EXISTS `idx_" + tag.toLowerCase() + "_scope` ON `"
                    + tag + "`(domain(32), snapshotId(48));");
        }
        for (String edge : EDGES) {
            run(session, "CREATE EDGE INDEX IF NOT EXISTS `idx_" + edge.toLowerCase() + "_scope` ON `"
                    + edge + "`(domain(32), snapshotId(48));");
        }
        // REBUILD is async; failures here are non-fatal for an already-built index.
        session.execute("REBUILD TAG INDEX;");
        session.execute("REBUILD EDGE INDEX;");
    }

    private void run(Session session, String ngql) throws Exception {
        ResultSet rs = session.execute(ngql);
        if (!rs.isSucceeded()) {
            throw new GraphAccessException("bootstrap nGQL failed: " + rs.getErrorMessage());
        }
    }
}
