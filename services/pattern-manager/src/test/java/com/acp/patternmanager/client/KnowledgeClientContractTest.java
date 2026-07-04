package com.acp.patternmanager.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.config.IntegrationProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.acp.patternmanager.config.ClientConfig;
import org.springframework.web.client.RestClient;

/**
 * Contract test for {@link KnowledgeClient}: asserts the EXACT request path/method the client emits
 * against a WireMock stub, and that it unwraps the RecordResponse ENVELOPE ({@code payload.params[]}
 * dotted-key list). A fabricated path (e.g. {@code /api/v1/...}) or a flat-shape assumption fails CI.
 *
 * <p>Real verified path: {@code GET /domains/core-ip/model-params/core-ip%2FmodelParams%2Fpattern-manager}.
 */
class KnowledgeClientContractTest {

    private WireMockServer wireMock;
    private KnowledgeClient client;

    private static final String EXPECTED_PATH =
            "/domains/core-ip/model-params/core-ip%2FmodelParams%2Fpattern-manager";

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        RestClient rc = ClientConfig.build(wireMock.baseUrl());
        IntegrationProperties props = new IntegrationProperties("mock", null, null,
                new IntegrationProperties.Endpoint(wireMock.baseUrl(), "core-ip"));
        client = new KnowledgeClient(rc, props);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void fetchesFromRealPathAndUnwrapsEnvelopeParamsList() {
        // The real RecordResponse ENVELOPE shape: {payload:{params:[{key,value},...]}}.
        String body = """
                {
                  "domain": "core-ip",
                  "recordType": "modelParams",
                  "recordId": "core-ip/modelParams/pattern-manager",
                  "version": "v1",
                  "isCurrent": true,
                  "payload": {
                    "paramSet": "pattern-manager",
                    "params": [
                      {"key": "structural.maxHops", "value": 4},
                      {"key": "structural.strictness", "value": "lenient"},
                      {"key": "structural.flagVsReject", "value": "flag"},
                      {"key": "rca.dependencyOrderingWeight", "value": 1.0},
                      {"key": "rca.timestampWeight", "value": 0.5},
                      {"key": "reconciliation.overlapThreshold", "value": 0.5}
                    ]
                  }
                }
                """;
        wireMock.stubFor(get(urlEqualTo(EXPECTED_PATH))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));

        EnrichmentParams params = client.fetchEnrichmentParams();

        // Assert the EXACT emitted path (no /api/v1; recordType kebab; recordId percent-encoded).
        wireMock.verify(getRequestedFor(urlEqualTo(EXPECTED_PATH)));

        assertThat(params.structuralMaxHops()).isEqualTo(4);
        assertThat(params.structuralStrictness()).isEqualTo("lenient");
        assertThat(params.structuralFlagVsReject()).isEqualTo("flag");
        assertThat(params.rcaDependencyOrderingWeight()).isEqualTo(1.0);
        assertThat(params.rcaTimestampWeight()).isEqualTo(0.5);
        assertThat(params.reconciliationOverlapThreshold()).isEqualTo(0.5);
    }
}
