package com.acp.patternmanager.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.client.dto.CodebookDtos.ScenarioOut;
import com.acp.patternmanager.config.IntegrationProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.acp.patternmanager.config.ClientConfig;
import org.springframework.web.client.RestClient;

/**
 * Contract test for {@link CodebookClient}: asserts the EXACT real Codebook API paths the client
 * emits ({@code GET /codebooks?domain=}, {@code GET /codebooks/{id}/scenarios},
 * {@code GET /codebooks/{id}/trail-signatures?trailId=}). No {@code /api/v1}.
 */
class CodebookClientContractTest {

    private WireMockServer wireMock;
    private CodebookClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        RestClient rc = ClientConfig.build(wireMock.baseUrl());
        IntegrationProperties props = new IntegrationProperties("mock", null,
                new IntegrationProperties.Endpoint(wireMock.baseUrl(), "core-ip"), null);
        client = new CodebookClient(rc, props);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void findCodebookIdHitsRealCodebooksPathWithDomain() {
        wireMock.stubFor(get(urlPathEqualTo("/codebooks"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"domain":"core-ip","snapshotId":"s1",
                                 "codebooks":[{"codebookId":"cb-1","active":true}]}
                                """)));

        assertThat(client.findCodebookId()).contains("cb-1");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/codebooks")).withQueryParam("domain", equalTo("core-ip")));
    }

    @Test
    void listScenariosHitsRealScenariosPath() {
        wireMock.stubFor(get(urlEqualTo("/codebooks/cb-1/scenarios"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codebookId":"cb-1","domain":"core-ip",
                                 "scenarios":[{"scenarioId":"s1","faultOriginType":"LineCardFault",
                                   "predictedSymptoms":[{"alarmType":"LOS","managedObjectId":"x:1"}]}]}
                                """)));

        List<ScenarioOut> scenarios = client.listScenarios("cb-1");

        wireMock.verify(getRequestedFor(urlEqualTo("/codebooks/cb-1/scenarios")));
        assertThat(scenarios).hasSize(1);
        assertThat(scenarios.get(0).predictedSymptoms().get(0).alarmType()).isEqualTo("LOS");
    }

    @Test
    void trailSignaturesHitsRealTrailSignaturesPathWithTrailId() {
        wireMock.stubFor(get(urlPathEqualTo("/codebooks/cb-1/trail-signatures"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"codebookId":"cb-1",
                                 "signatures":[{"trailId":"trail-1","scenarioId":"s1",
                                   "rootCauseAlarmType":"LineCardFault","expectedSymptoms":[]}]}
                                """)));

        var sigs = client.trailSignatures("cb-1", "trail-1");

        wireMock.verify(getRequestedFor(urlPathEqualTo("/codebooks/cb-1/trail-signatures"))
                .withQueryParam("trailId", equalTo("trail-1")));
        assertThat(sigs).hasSize(1);
        assertThat(sigs.get(0).rootCauseAlarmType()).isEqualTo("LineCardFault");
    }
}
