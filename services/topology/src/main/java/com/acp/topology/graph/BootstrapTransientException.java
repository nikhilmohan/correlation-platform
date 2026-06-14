package com.acp.topology.graph;

/**
 * A <em>transient</em> bootstrap failure that the {@code StartupBootstrapRunner} should retry in its
 * bounded background loop (Startup-Robustness Standard S2/S3): the dependency is simply not ready yet
 * — graphd not reachable, a freshly {@code ADD HOSTS}-ed storaged host not yet {@code Status ONLINE},
 * or the space not yet {@code USE}-able after {@code CREATE SPACE}. Distinguished from a
 * {@link GraphAccessException} treated as fatal (malformed config, auth rejected) which must fail
 * fast rather than burn the whole startup deadline.
 */
public class BootstrapTransientException extends RuntimeException {

    public BootstrapTransientException(String message) {
        super(message);
    }

    public BootstrapTransientException(String message, Throwable cause) {
        super(message, cause);
    }
}
