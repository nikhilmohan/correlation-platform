package com.acp.correlationengine.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Knowledge Service client + parser tests. Backed by a MockWebServer stub returning the frozen
 * versioned-record envelope from the Knowledge published OpenAPI (model-params/{recordId}). Proves
 * the params are read by dotted key (AC21 parse) and the correct URL is called.
 */
class KnowledgeClientTest {

    private MockWebServer server;
    private RestKnowledgeClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        RestClient http = RestClient.builder().baseUrl(server.url("/").toString()).build();
        client = new RestKnowledgeClient(http, "core-ip");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchesModelParamsByDottedKey_fromFrozenEnvelope() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "domain": "core-ip",
                          "recordType": "modelParams",
                          "recordId": "core-ip/modelParams/correlation-engine",
                          "version": 3,
                          "isCurrent": true,
                          "payload": {
                            "paramSet": "correlation-engine",
                            "params": [
                              {"key": "match.partialMatchTolerance", "value": 1},
                              {"key": "codebook.missingPenalty", "value": 1.5},
                              {"key": "codebook.spuriousPenalty", "value": 2.5},
                              {"key": "codebook.scoreFloor", "value": 0.4},
                              {"key": "conflict.weights.specificity", "value": 3.0},
                              {"key": "conflict.weights.confidence", "value": 0.2}
                            ]
                          }
                        }
                        """));

        MatchParams params = client.fetchMatchParams();

        assertThat(params.partialMatchTolerance()).isEqualTo(1);
        assertThat(params.codebookMissingPenalty()).isEqualTo(1.5);
        assertThat(params.codebookSpuriousPenalty()).isEqualTo(2.5);
        assertThat(params.codebookScoreFloor()).isEqualTo(0.4);
        assertThat(params.conflictSpecificityWeight()).isEqualTo(3.0);
        assertThat(params.conflictConfidenceWeight()).isEqualTo(0.2);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).contains("/domains/core-ip/model-params/");
        assertThat(req.getPath()).contains("correlation-engine");
    }

    @Test
    void missingRequiredKey_throwsRatherThanInventDefault() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"payload": {"paramSet": "correlation-engine", "params": [
                          {"key": "match.partialMatchTolerance", "value": 1}
                        ]}}
                        """));
        assertThatThrownBy(() -> client.fetchMatchParams())
                .isInstanceOf(KnowledgeUnavailableException.class);
    }
}
