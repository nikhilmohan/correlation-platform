package com.acp.topology.graph;

/** Internal graph-access failure (nGQL/pool); surfaces as 500 to callers (no NebulaGraph detail). */
public class GraphAccessException extends RuntimeException {

    public GraphAccessException(String message) {
        super(message);
    }

    public GraphAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
