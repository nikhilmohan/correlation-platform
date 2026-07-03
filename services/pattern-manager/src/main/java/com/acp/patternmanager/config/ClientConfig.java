package com.acp.patternmanager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Builds the three collaborator {@link RestClient} instances (Topology, Codebook, Knowledge),
 * each bound to a config-resolved base URL — never a hard-coded URL. The {@code mock|real} toggle
 * is applied by pointing {@code baseUrl} at a WireMock stub (tests) or the live service
 * (integration); the client code is identical in both modes.
 */
@Configuration
public class ClientConfig {

    private final IntegrationProperties integration;

    public ClientConfig(IntegrationProperties integration) {
        this.integration = integration;
    }

    /** @return a {@link RestClient} for the Topology Service (RCA + structural validation). */
    @Bean("topologyRestClient")
    public RestClient topologyRestClient() {
        return build(integration.topology().baseUrl());
    }

    /** @return a {@link RestClient} for the Codebook Generator (reconcile + RCA override). */
    @Bean("codebookRestClient")
    public RestClient codebookRestClient() {
        return build(integration.codebook().baseUrl());
    }

    /** @return a {@link RestClient} for the Knowledge Service (RCA / structural-validation params). */
    @Bean("knowledgeRestClient")
    public RestClient knowledgeRestClient() {
        return build(integration.knowledge().baseUrl());
    }

    /**
     * Build a {@link RestClient} pinned to the JDK {@link java.net.http.HttpClient} request factory.
     * Pinning the factory keeps request routing deterministic and independent of whatever HTTP client
     * happens to be on the classpath (e.g. a test dependency's shaded client).
     */
    public static RestClient build(String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new JdkClientHttpRequestFactory())
                .build();
    }
}
