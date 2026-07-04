package com.acp.enrichment.knowledge;

/**
 * Signals that the Knowledge Service could not supply the alarm-type vocabulary at startup (network
 * error, no record, or empty payload). Callers degrade to the documented offline fallback with a
 * logged warning rather than failing hard.
 */
public class KnowledgeUnavailableException extends RuntimeException {

    public KnowledgeUnavailableException(String message) {
        super(message);
    }

    public KnowledgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
