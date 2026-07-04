package com.acp.enrichment.config;

import com.acp.enrichment.pipeline.TrailTagStep;
import com.acp.enrichment.trail.TrailBuilderClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Trail Builder integration point — config-switchable base URL + {@code mock|real} mode (no
 * hard-coded URLs). Both modes build the SAME {@link TrailBuilderClient} against the frozen
 * {@code /trails/by-object} path; the mode only changes the base URL (real = the Docker Compose
 * address {@code http://trail-builder:8000}; mock = a WireMock/MockWebServer stub generated from
 * Trail Builder's published OpenAPI). The client URL therefore matches the real OpenAPI path in
 * every mode (hard-lesson #2).
 */
@Configuration
public class TrailBuilderConfig {

    @Bean
    public RestClient trailBuilderRestClient(
            @Value("${trail-builder.base-url:http://trail-builder:8000}") String baseUrl) {
        return RestClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    public TrailBuilderClient trailBuilderClient(RestClient trailBuilderRestClient) {
        return new TrailBuilderClient(trailBuilderRestClient);
    }

    @Bean
    public TrailTagStep trailTagStep(TrailBuilderClient client,
            @Value("${enrichment.domain:core-ip}") String domain, MeterRegistry meters) {
        return new TrailTagStep(client, domain, meters);
    }
}
