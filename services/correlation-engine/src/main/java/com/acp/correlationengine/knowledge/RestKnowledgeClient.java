package com.acp.correlationengine.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.web.client.RestClient;

/**
 * Real (and mock-endpoint) HTTP client for the Knowledge Service's frozen versioned-record
 * endpoint. In {@code INTEGRATION_MODE=mock} the base URL points at a MockWebServer stub generated
 * from the Knowledge published OpenAPI; in {@code real} it points at the Compose {@code knowledge}
 * service. Same code path — only the base URL differs (no hard-coded collaborator URL).
 */
public class RestKnowledgeClient implements KnowledgeClient {

    private final RestClient http;
    private final String domain;

    public RestKnowledgeClient(RestClient http, String domain) {
        this.http = http;
        this.domain = domain;
    }

    @Override
    public MatchParams fetchMatchParams() {
        String encodedRecordId = URLEncoder.encode(CORRELATION_ENGINE_RECORD_ID, StandardCharsets.UTF_8);
        try {
            JsonNode envelope = http.get()
                    .uri("/domains/{domain}/model-params/{recordId}", domain, encodedRecordId)
                    .retrieve()
                    .body(JsonNode.class);
            return ModelParamsParser.parse(envelope);
        } catch (KnowledgeUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KnowledgeUnavailableException(
                    "failed to fetch Knowledge model-params for " + CORRELATION_ENGINE_RECORD_ID, e);
        }
    }
}
