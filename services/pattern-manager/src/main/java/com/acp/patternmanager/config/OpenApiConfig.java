package com.acp.patternmanager.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 document metadata. The full document is served at {@code /openapi.json} (springdoc)
 * and the generated file is checked in at {@code services/pattern-manager/openapi.json} as the
 * single source of truth for the HTTP surface the web-ui and Correlation Engine consume.
 */
@Configuration
public class OpenApiConfig {

    /** @return the OpenAPI info block for the pattern-manager HTTP surface. */
    @Bean
    public OpenAPI patternManagerOpenApi() {
        // Pin a single explicit relative server so springdoc does not synthesize a per-port
        // absolute URL (e.g. http://localhost:<RANDOM_PORT>). This keeps the served /openapi.json
        // — and therefore the checked-in artifact — deterministic and port-independent.
        return new OpenAPI()
                .servers(List.of(new Server().url("/")))
                .info(new Info()
                        .title("Pattern Manager API")
                        .description("Pattern Store read API + human-approval lifecycle for discovered "
                                + "correlation patterns (RCA, structural validation, codebook "
                                + "reconciliation, session-window, XAI).")
                        .version("0.1.0"));
    }
}
