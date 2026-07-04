package com.acp.correlationengine.knowledge;

/**
 * Config-switchable client for the Knowledge Service's frozen versioned-record endpoint
 * {@code GET /domains/{domain}/model-params/{recordId}}. Built against the Knowledge Service's
 * published OpenAPI ({@code model-params/{recordId}} operation); mock (MockWebServer) in unit
 * tests, real (Compose) in integration.
 */
public interface KnowledgeClient {

    /** The seeded Correlation Engine modelParams recordId. */
    String CORRELATION_ENGINE_RECORD_ID = "core-ip/modelParams/correlation-engine";

    /**
     * Fetch + parse the correlation-engine {@link MatchParams} from the versioned-record envelope
     * ({@code payload.params[]}, read by dotted key).
     *
     * @return the parsed params
     * @throws KnowledgeUnavailableException if the endpoint is unreachable or the record is malformed
     */
    MatchParams fetchMatchParams();
}
