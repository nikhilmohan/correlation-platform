package com.acp.enrichment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enrichment Service entrypoint.
 *
 * <p>One deployment hosts both the Kafka pipeline (history + live listeners over one shared
 * {@code EnrichmentPipeline}) and the chatter-management HTTP API concurrently — the HTTP server
 * (Actuator + chatter API + {@code /openapi.json}) and the Kafka consumers run together in the
 * same process. This is what makes acceptance criterion 9 ("same service instance handles both
 * paths") hold structurally, and what the integration-tagged entrypoint test verifies over real
 * HTTP + real Kafka.
 */
@SpringBootApplication
@EnableScheduling
public class EnrichmentApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnrichmentApplication.class, args);
    }
}
