package com.acp.patternmanager.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.client.dto.TopologyNode;
import com.acp.patternmanager.client.dto.TraversalResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.acp.patternmanager.config.ClientConfig;
import org.springframework.web.client.RestClient;

/**
 * Contract test for {@link TopologyClient}: asserts the EXACT paths + query params the client emits
 * ({@code GET /topology/nodes/{id}} and {@code GET /topology/traversal?start=&maxDepth=&relation=}),
 * verified against the live Topology OpenAPI. A fabricated path or wrong query-param name fails CI.
 */
class TopologyClientContractTest {

    private WireMockServer wireMock;
    private TopologyClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        RestClient rc = ClientConfig.build(wireMock.baseUrl());
        client = new TopologyClient(rc);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void getNodeHitsRealNodesPath() {
        // The managedObjectId colon is percent-encoded (%3A) on the wire by the Spring template var;
        // the real Topology {managedObjectId} @PathVariable decodes it back to ':' server-side.
        wireMock.stubFor(get(urlEqualTo("/topology/nodes/FiberSpan%3A1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"managedObjectId\":\"FiberSpan:1\",\"objectType\":\"FiberSpan\","
                                + "\"domain\":\"core-ip\",\"snapshotId\":\"s1\",\"name\":\"span-1\","
                                + "\"attributes\":{}}")));

        TopologyNode node = client.getNode("FiberSpan:1").orElseThrow();

        wireMock.verify(getRequestedFor(urlEqualTo("/topology/nodes/FiberSpan%3A1")));
        assertThat(node.objectType()).isEqualTo("FiberSpan");
    }

    @Test
    void getNode404ReturnsEmpty() {
        wireMock.stubFor(get(urlEqualTo("/topology/nodes/Missing%3A1"))
                .willReturn(aResponse().withStatus(404)));
        assertThat(client.getNode("Missing:1")).isEmpty();
        wireMock.verify(getRequestedFor(urlEqualTo("/topology/nodes/Missing%3A1")));
    }

    @Test
    void traverseHitsRealTraversalPathWithStartMaxDepthRelation() {
        wireMock.stubFor(get(urlPathEqualTo("/topology/traversal"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"start":"FiberSpan:1","maxDepth":4,
                                 "reached":[{"managedObjectId":"IPLink:1","objectType":"IPLink"}],
                                 "edges":[]}
                                """)));

        TraversalResult t = client.traverse("FiberSpan:1", List.of("DEPENDS_ON"), 4);

        // Assert the EXACT query-param names the real Topology API uses.
        wireMock.verify(getRequestedFor(urlPathEqualTo("/topology/traversal"))
                .withQueryParam("start", equalTo("FiberSpan:1"))
                .withQueryParam("maxDepth", equalTo("4"))
                .withQueryParam("relation", equalTo("DEPENDS_ON")));
        assertThat(t.reached()).hasSize(1);
    }
}
