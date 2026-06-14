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
 * Idempotently bootstraps the NebulaGraph SPACE + schema on startup (Flow E / Algorithm §D),
 * conforming to {@code docs/startup-robustness-standard.md} (S1 true-readiness, S2 bounded
 * deadline + backoff, S4 idempotency, S5 config-from-env):
 * <ol>
 *   <li>{@code SHOW HOSTS} → read the configured storaged host's {@code Status} column. Readiness is
 *       <strong>{@code Status == "ONLINE"}</strong> (CRIT-1 fix) — <em>not</em> {@code rowsSize() > 0}
 *       (a host can be listed yet OFFLINE). Run {@code ADD HOSTS} only if the host is absent/OFFLINE
 *       (idempotent), then <strong>poll {@code SHOW HOSTS} until {@code ONLINE}</strong>, bounded by
 *       {@code storaged-online-deadline-ms}.</li>
 *   <li>{@code CREATE SPACE IF NOT EXISTS} (FIXED_STRING VID) — only <em>after</em> storaged ONLINE.</li>
 *   <li>{@code waitUntilSpaceUsable}: poll {@code USE space} until it succeeds, bounded by
 *       {@code space-usable-deadline-ms}.</li>
 *   <li>{@code USE space; CREATE TAG/EDGE/INDEX IF NOT EXISTS; REBUILD}.</li>
 * </ol>
 * Transient failures (graphd not reachable, storaged not yet ONLINE, space not yet usable) throw
 * {@link BootstrapTransientException} so {@code StartupBootstrapRunner} retries; genuinely invalid
 * results surface as {@link GraphAccessException}. Re-running across restarts/retries is a no-op
 * (everything is {@code IF NOT EXISTS}; {@code ADD HOSTS} only when not already ONLINE).
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

    /** Sleep abstraction so the deadline-bounded polls can be unit-tested without real waits. */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Monotonic clock abstraction (millis) so poll deadlines can be unit-tested deterministically. */
    interface Clock {
        long nowMillis();
    }

    private final NebulaPool pool;
    private final TopologyProperties.Nebula config;
    private final Sleeper sleeper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public NebulaSchemaBootstrap(NebulaPool pool, TopologyProperties properties) {
        this(pool, properties, Thread::sleep, System::currentTimeMillis);
    }

    /** Test seam: inject a fake {@link Sleeper}/{@link Clock} to exercise the polls without waiting. */
    NebulaSchemaBootstrap(NebulaPool pool, TopologyProperties properties, Sleeper sleeper,
            Clock clock) {
        this.pool = pool;
        this.config = properties.getNebula();
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** The TAG names this service manages (used by scans / eviction in the repository). */
    public List<String> tagNames() {
        return TAGS;
    }

    /**
     * Run the idempotent bootstrap. Safe to call repeatedly. Throws
     * {@link BootstrapTransientException} when a dependency is not yet ready (the runner retries) and
     * {@link GraphAccessException} on a non-transient failure.
     */
    public void bootstrap() {
        Session session = null;
        try {
            session = acquireSession();
            ensureStoragedOnline(session);
            createSpace(session);
            waitUntilSpaceUsable(session);
            createSchema(session);
            log.info("NebulaGraph schema bootstrap complete for space {}", config.getSpace());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BootstrapTransientException("NebulaGraph bootstrap interrupted", e);
        } catch (BootstrapTransientException | GraphAccessException e) {
            throw e;
        } catch (Exception e) {
            // graphd not yet reachable / pool not yet warmed up is transient — let the runner retry.
            throw new BootstrapTransientException("NebulaGraph bootstrap failed (transient)", e);
        } finally {
            if (session != null) {
                session.release();
            }
        }
    }

    private Session acquireSession() {
        try {
            Session session = pool.getSession(config.getUsername(), config.getPassword(), false);
            if (session == null) {
                throw new BootstrapTransientException("no NebulaGraph session available yet");
            }
            return session;
        } catch (BootstrapTransientException e) {
            throw e;
        } catch (Exception e) {
            // Connection-refused / pool-exhausted while graphd is still coming up — transient (S2).
            throw new BootstrapTransientException("graphd not reachable yet", e);
        }
    }

    /**
     * CRIT-1 fix (S1): treat the storaged host ready ONLY when its {@code Status} column is
     * {@code ONLINE}. {@code ADD HOSTS} only if absent/OFFLINE (idempotent, S4), then poll
     * {@code SHOW HOSTS} until {@code ONLINE}, bounded by {@code storaged-online-deadline-ms} (S2).
     */
    void ensureStoragedOnline(Session session) throws Exception {
        String host = storagedName();
        int port = storagedPort();

        if (storagedOnline(session, host, port)) {
            log.info("storaged {}:{} already ONLINE", host, port);
            return;
        }
        log.info("storaged {}:{} not ONLINE; running ADD HOSTS", host, port);
        session.execute("ADD HOSTS \"" + host + "\":" + port + ";");

        long deadline = clock.nowMillis() + config.getStoragedOnlineDeadlineMs();
        while (clock.nowMillis() < deadline) {
            if (storagedOnline(session, host, port)) {
                log.info("storaged {}:{} reached Status ONLINE", host, port);
                return;
            }
            sleeper.sleep(config.getPollIntervalMs());
        }
        throw new BootstrapTransientException(
                "storaged " + host + ":" + port + " not ONLINE within "
                        + config.getStoragedOnlineDeadlineMs() + "ms");
    }

    /**
     * Parse {@code SHOW HOSTS}: find the row for the configured storaged host (match on the
     * {@code Host} + {@code Port} columns) and return true ONLY when its {@code Status} is
     * {@code ONLINE}. Never "a row exists".
     */
    private boolean storagedOnline(Session session, String host, int port) throws Exception {
        ResultSet rs = session.execute("SHOW HOSTS;");
        if (rs == null || !rs.isSucceeded()) {
            // SHOW HOSTS itself failing this early is transient (graphd/metad still settling).
            return false;
        }
        int rows = rs.rowsSize();
        for (int i = 0; i < rows; i++) {
            ResultSet.Record record = rs.rowValues(i);
            String rowHost = stringCol(record, "Host");
            String rowPort = stringCol(record, "Port");
            if (rowHost == null) {
                continue;
            }
            boolean hostMatch = host.equals(rowHost);
            boolean portMatch = rowPort == null || rowPort.isBlank()
                    || String.valueOf(port).equals(rowPort);
            if (hostMatch && portMatch) {
                String status = stringCol(record, "Status");
                return status != null && "ONLINE".equalsIgnoreCase(status.trim());
            }
        }
        return false;
    }

    private static String stringCol(ResultSet.Record record, String column) {
        try {
            if (record == null || !record.contains(column)) {
                return null;
            }
            com.vesoft.nebula.client.graph.data.ValueWrapper v = record.get(column);
            if (v == null) {
                return null;
            }
            if (v.isString()) {
                return v.asString();
            }
            // Port is an int column; toString() yields its literal so the match still works.
            return v.toString().replace("\"", "").trim();
        } catch (Exception e) {
            return null;
        }
    }

    private void createSpace(Session session) throws Exception {
        run(session, "CREATE SPACE IF NOT EXISTS `" + config.getSpace()
                + "` (partition_num = 10, replica_factor = 1, vid_type = FIXED_STRING(128));");
    }

    /**
     * S1 space-readiness: {@code USE space} succeeds (it has propagated to storaged and is usable) —
     * not "{@code CREATE SPACE} returned". Polled on {@code poll-interval-ms}, bounded by
     * {@code space-usable-deadline-ms} (S2).
     */
    void waitUntilSpaceUsable(Session session) throws Exception {
        long deadline = clock.nowMillis() + config.getSpaceUsableDeadlineMs();
        while (clock.nowMillis() < deadline) {
            ResultSet rs = session.execute("USE `" + config.getSpace() + "`;");
            if (rs != null && rs.isSucceeded()) {
                return;
            }
            sleeper.sleep(config.getPollIntervalMs());
        }
        throw new BootstrapTransientException(
                "space " + config.getSpace() + " not usable within "
                        + config.getSpaceUsableDeadlineMs() + "ms");
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

    private String storagedName() {
        String host = config.getStoragedHost();
        int colon = host.lastIndexOf(':');
        return colon > 0 ? host.substring(0, colon) : host;
    }

    private int storagedPort() {
        String host = config.getStoragedHost();
        int colon = host.lastIndexOf(':');
        if (colon <= 0) {
            return 9779;
        }
        try {
            return Integer.parseInt(host.substring(colon + 1));
        } catch (NumberFormatException e) {
            return 9779;
        }
    }

    private void run(Session session, String ngql) throws Exception {
        ResultSet rs = session.execute(ngql);
        if (rs == null || !rs.isSucceeded()) {
            throw new GraphAccessException(
                    "bootstrap nGQL failed: " + (rs == null ? "null result" : rs.getErrorMessage()));
        }
    }
}
