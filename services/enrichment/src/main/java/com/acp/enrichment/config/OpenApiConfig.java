package com.acp.enrichment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the chatter-management API as OpenAPI 3.1 at {@code /openapi.json} (springdoc) with
 * Swagger UI. The generated {@code services/enrichment/openapi.json} is checked in as the single
 * source of truth for the surface (it drives Enrichment's provider contract tests and the web-ui's
 * generated client).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI enrichmentOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Enrichment Chatter-Management API")
                .version("0.1.0")
                .description("Operator-mediated promote/list/remove surface over a source's "
                        + "known-chatter list (the noise-to-live chatter feedback loop). Edits "
                        + "persist to an Enrichment-owned chatter overlay and hot-apply to the "
                        + "live filter path with no restart."));
    }
}
