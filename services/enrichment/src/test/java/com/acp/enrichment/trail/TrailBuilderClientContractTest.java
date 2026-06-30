package com.acp.enrichment.trail;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Acceptance criterion 17 + hard-lesson #2: the {@link TrailBuilderClient} must call a path the real
 * Trail Builder actually serves — {@code GET /trails/by-object?managedObjectId={moId}&domain={domain}}
 * with NO {@code /api/v1} prefix — and read {@code trailIds} from the frozen
 * {@code { managedObjectId, domain, trailIds[] }} response.
 *
 * <p>The contract assertions are driven by Trail Builder's CHECKED-IN OpenAPI 3.1 (under
 * {@code src/test/resources/trail-builder-openapi.json}, a verbatim copy of the producer's
 * published spec) so a wrong path (e.g. an invented {@code /api/v1/...}) fails the test rather than
 * a mock-of-a-fiction passing green.
 */
class TrailBuilderClientContractTest {

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
        // The client's compile-time path constant MUST equal a path the published OpenAPI serves,
        // and MUST NOT carry an /api/v1 prefix.
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = getClass().getResourceAsStream("/trail-builder-openapi.json")) {
            assertThat(in).as("checked-in Trail Builder OpenAPI").isNotNull();
            JsonNode spec = mapper.readTree(in);
            JsonNode paths = spec.get("paths");
            assertThat(paths.has(TrailBuilderClient.BY_OBJECT_PATH))
                    .as("published OpenAPI must serve %s", TrailBuilderClient.BY_OBJECT_PATH)
                    .isTrue();
            assertThat(paths.get(TrailBuilderClient.BY_OBJECT_PATH).has("get")).isTrue();
        }
        assertThat(TrailBuilderClient.BY_OBJECT_PATH).isEqualTo("/trails/by-object");
        assertThat(TrailBuilderClient.BY_OBJECT_PATH).doesNotContain("/api/v1");
    }

    @Test
    void callsFrozenByObjectPathWithManagedObjectIdAndDomain() {
        wireMock.stubFor(get(urlPathEqualTo("/trails/by-object"))
                .withQueryParam("managedObjectId", equalTo("Interface:edge1-12"))
                .withQueryParam("domain", equalTo("core-ip"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"managedObjectId\":\"Interface:edge1-12\","
                                + "\"domain\":\"core-ip\",\"trailIds\":[\"trail-7a3f\"]}")));

        TrailBuilderClient client = new TrailBuilderClient(
                RestClient.builder().baseUrl(wireMock.baseUrl()).build());
        List<String> trailIds = client.getTrailsForObject("Interface:edge1-12", "core-ip");

        assertThat(trailIds).containsExactly("trail-7a3f");
        // Both query params present on the exact frozen path (no /api/v1).
        wireMock.verify(getRequestedFor(urlPathEqualTo("/trails/by-object"))
                .withQueryParam("managedObjectId", equalTo("Interface:edge1-12"))
                .withQueryParam("domain", equalTo("core-ip")));
    }

    @Test
    void emptyTrailIdsWhenNoneReturned() {
        wireMock.stubFor(get(urlPathEqualTo("/trails/by-object"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"managedObjectId\":\"Interface:x\",\"domain\":\"core-ip\","
                                + "\"trailIds\":[]}")));
        TrailBuilderClient client = new TrailBuilderClient(
                RestClient.builder().baseUrl(wireMock.baseUrl()).build());
        assertThat(client.getTrailsForObject("Interface:x", "core-ip")).isEmpty();
    }
}
