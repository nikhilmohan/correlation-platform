package com.acp.correlationengine.pattern;

import com.acp.correlationengine.model.PatternRef;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.client.RestClient;

/** Real (and mock-endpoint) client for {@code GET /patterns?lifecycle=approved}. */
public class RestPatternManagerClient implements PatternManagerClient {

    private final RestClient http;

    public RestPatternManagerClient(RestClient http) {
        this.http = http;
    }

    @Override
    public List<PatternRef> listApproved() {
        JsonNode page = http.get()
                .uri(uriBuilder -> uriBuilder.path("/patterns").queryParam("lifecycle", "approved").build())
                .retrieve()
                .body(JsonNode.class);
        return PatternViewMapper.fromPage(page);
    }
}
