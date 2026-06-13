package com.acp.topology.graph;

import com.vesoft.nebula.client.graph.net.NebulaPool;
import com.vesoft.nebula.client.graph.net.Session;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Borrows a nebula-java {@link Session} from the {@link NebulaPool} for the duration of a single
 * operation, returning it after. Confined to the {@code graph/} package — no NebulaGraph connection
 * detail escapes. Authentication credentials come from config (never logged, never forwarded).
 */
@Component
public class NebulaSessionProvider {

    private final NebulaPool pool;
    private final String username;
    private final String password;

    public NebulaSessionProvider(NebulaPool pool,
            com.acp.topology.config.TopologyProperties properties) {
        this.pool = pool;
        this.username = properties.getNebula().getUsername();
        this.password = properties.getNebula().getPassword();
    }

    /** Run {@code work} with a borrowed session; releases the session afterward. */
    public <T> T execute(Function<Session, T> work) {
        Session session = null;
        try {
            session = pool.getSession(username, password, false);
            return work.apply(session);
        } catch (GraphAccessException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphAccessException("could not obtain NebulaGraph session", e);
        } finally {
            if (session != null) {
                session.release();
            }
        }
    }
}
