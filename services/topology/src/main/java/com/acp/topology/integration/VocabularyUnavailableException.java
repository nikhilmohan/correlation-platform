package com.acp.topology.integration;

/**
 * Raised when the Knowledge domain-vocabulary is unavailable for a domain and no non-expired cache
 * entry exists — ingest fails closed with HTTP 502 (EH-6c). No write, no event.
 */
public class VocabularyUnavailableException extends RuntimeException {

    public VocabularyUnavailableException(String message) {
        super(message);
    }

    public VocabularyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
