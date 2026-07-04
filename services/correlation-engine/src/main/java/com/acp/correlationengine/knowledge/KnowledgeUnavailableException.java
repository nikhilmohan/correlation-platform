package com.acp.correlationengine.knowledge;

/** Raised when the Knowledge Service model-params record cannot be fetched or parsed. */
public class KnowledgeUnavailableException extends RuntimeException {
    public KnowledgeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public KnowledgeUnavailableException(String message) {
        super(message);
    }
}
