package com.acp.enrichment.knowledge;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acp.enrichment.ruleset.AlarmTypeVocabulary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.InputStream;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * FIX #2 + hard-lesson #2 (noise-filter): the {@link KnowledgeClient} must call a path Knowledge
 * actually serves and read the vocabulary from the RecordResponse ENVELOPE's {@code .payload}, not
 * the top level.
 *
 * <p>Knowledge serves the alarm-type vocabulary as the {@code alarm-type-vocabulary} recordType via
 * the generic list endpoint {@code GET /domains/{domain}/{recordType}} (NO {@code /api/v1} prefix),
 * returning a LIST of {@code RecordResponse} envelopes each with a {@code payload}. The contract is
 * driven by Knowledge's CHECKED-IN OpenAPI 3.1 ({@code src/test/resources/knowledge-openapi.json}, a
 * verbatim copy of the producer's published spec) so an invented path fails here rather than a
 * mock-of-a-fiction passing green.
 */
class KnowledgeClientContractTest {

    /** The concrete effective path {@code /domains/{domain}/alarm-type-vocabulary} maps onto. */
    private static final String GENERIC_LIST_PATH = "/domains/{domain}/{recordType}";

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void clientPathMatchesRealPublishedOpenApi() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/knowledge-openapi.json")) {
            assertThat(in).as("checked-in Knowledge OpenAPI").isNotNull();
            JsonNode spec = mapper.readTree(in);
            JsonNode paths = spec.get("paths");
            // alarm-type-vocabulary is served by the generic list endpoint (recordType path segment).
            assertThat(paths.has(GENERIC_LIST_PATH))
                    .as("published OpenAPI must serve %s", GENERIC_LIST_PATH).isTrue();
            assertThat(paths.get(GENERIC_LIST_PATH).has("get")).isTrue();
            // The GET returns an array of RecordResponse (an ENVELOPE) whose body is under payload.
            JsonNode recordResponse = spec.at("/components/schemas/RecordResponse/properties");
            assertThat(recordResponse.has("payload"))
                    .as("RecordResponse must expose a 'payload' envelope field").isTrue();
        }
        assertThat(KnowledgeClient.ALARM_TYPE_VOCABULARY_RECORD_TYPE)
                .isEqualTo("alarm-type-vocabulary");
        assertThat(KnowledgeClient.ALARM_TYPE_VOCABULARY_RECORD_TYPE).doesNotContain("/api/v1");
    }

    @Test
    void fetchesThirtyTokenVocabularyFromRealListShape() {
        // The REAL /domains/{domain}/alarm-type-vocabulary shape: a LIST whose [0].payload.alarmTypes
        // is the 30-token array (task's verified live response).
        wireMock.stubFor(get(urlPathEqualTo("/domains/core-ip/alarm-type-vocabulary"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(thirtyTokenListBody())));

        KnowledgeClient client = new KnowledgeClient(
                RestClient.builder().baseUrl(wireMock.baseUrl()).build(), "core-ip");
        Set<String> tokens = client.fetchAlarmTypeVocabulary();

        assertThat(tokens).hasSize(30)
                .contains("LOS", "InterfaceDown", "BGPPeerDown", "HighLatency", "ReachabilityLoss");
        wireMock.verify(getRequestedFor(urlPathEqualTo("/domains/core-ip/alarm-type-vocabulary")));
    }

    @Test
    void prefersTheCurrentRecordVersion() {
        String body = "[{\"recordId\":\"default\",\"isCurrent\":false,"
                + "\"payload\":{\"alarmTypes\":[\"LinkDown\"]}},"
                + "{\"recordId\":\"default\",\"isCurrent\":true,"
                + "\"payload\":{\"alarmTypes\":[\"LinkDown\",\"PortDown\",\"AdjDown\"]}}]";
        wireMock.stubFor(get(urlPathEqualTo("/domains/core-ip/alarm-type-vocabulary"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));

        KnowledgeClient client = new KnowledgeClient(
                RestClient.builder().baseUrl(wireMock.baseUrl()).build(), "core-ip");
        assertThat(client.fetchAlarmTypeVocabulary())
                .containsExactlyInAnyOrder("LinkDown", "PortDown", "AdjDown");
    }

    @Test
    void throwsWhenKnowledgeUnreachable() {
        // Point at a closed port; the client surfaces a KnowledgeUnavailableException.
        KnowledgeClient client = new KnowledgeClient(
                RestClient.builder().baseUrl("http://localhost:1").build(), "core-ip");
        assertThatThrownBy(client::fetchAlarmTypeVocabulary)
                .isInstanceOf(KnowledgeUnavailableException.class);
    }

    @Test
    void loadVocabularyOrFallbackDegradesToThirtyTokenSetOnFailure() {
        KnowledgeClient client = new KnowledgeClient(
                RestClient.builder().baseUrl("http://localhost:1").build(), "core-ip");
        AlarmTypeVocabulary vocab = client.loadVocabularyOrFallback();
        assertThat(vocab.tokens()).hasSize(30).containsAll(AlarmTypeVocabulary.CORE_IP_FALLBACK);
    }

    private static String thirtyTokenListBody() {
        String tokens = String.join("\",\"",
                "LOS", "LOF", "OpticalPowerLow", "FiberCut", "FiberFault", "PortDown",
                "LineCardFault", "CRCErrors", "PortFlapping", "LinkBundleDegraded", "NodeDown",
                "InterfaceDown", "InterfaceErrors", "IPLinkDown", "LinkDown", "ISISAdjacencyDown",
                "AdjDown", "OSPFAdjacencyDown", "BGPPeerDown", "RouteFlap", "LDPSessionDown",
                "LSPDown", "FRRSwitchover", "TETunnelDown", "VPNReachabilityLoss", "ReachabilityLoss",
                "ServiceDegraded", "Congestion", "QueueDrop", "HighLatency");
        return "[{\"domain\":\"core-ip\",\"recordType\":\"alarm-type-vocabulary\","
                + "\"recordId\":\"default\",\"version\":\"1\",\"isCurrent\":true,"
                + "\"payload\":{\"alarmTypes\":[\"" + tokens + "\"]}}]";
    }
}
