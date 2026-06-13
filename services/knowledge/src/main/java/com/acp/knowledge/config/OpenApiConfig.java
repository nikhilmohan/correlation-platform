package com.acp.knowledge.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1 metadata. springdoc serves the live document at {@code /openapi.json} (configured in
 * application.yml) plus a Swagger UI. The generated document is checked in at
 * {@code services/knowledge/openapi.json} and is the provider contract collaborating services
 * (Topology, web-ui) build their clients against.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI knowledgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Knowledge Service API")
                        .version("0.1.0")
                        .description("Authoritative, versioned store for authored domain knowledge "
                                + "(propagation templates, fault-origin types, trail policy, model "
                                + "params, object-type / edge-relation / alarm-type vocabularies, "
                                + "attribute catalogue). CRUD + versioned read for all record types, "
                                + "a frozen domain vocabulary query, and model-params read/edit."));
    }
}
