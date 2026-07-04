package com.acp.correlationengine.codebook;

import com.acp.correlationengine.model.TrailScenarioSignature;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.client.RestClient;

/** Real (and mock-endpoint) client for {@code GET /codebooks/{codebookId}/trail-signatures}. */
public class RestCodebookGeneratorClient implements CodebookGeneratorClient {

    private final RestClient http;

    public RestCodebookGeneratorClient(RestClient http) {
        this.http = http;
    }

    @Override
    public List<TrailScenarioSignature> fetchTrailSignatures(String codebookId) {
        JsonNode body = http.get()
                .uri("/codebooks/{codebookId}/trail-signatures", codebookId)
                .retrieve()
                .body(JsonNode.class);
        return TrailSignaturesMapper.fromResponse(body);
    }
}
