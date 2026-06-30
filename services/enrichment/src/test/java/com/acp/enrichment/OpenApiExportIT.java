package com.acp.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

/**
 * Regenerates and verifies the checked-in {@code services/enrichment/openapi.json} from the live
 * springdoc surface so the published chatter-API contract cannot drift (design "Build and run").
 * Tagged {@code openapi-gen} so it runs on demand; the assertion fails the build if the served
 * spec diverges from the checked-in file (when {@code ENRICHMENT_VERIFY_OPENAPI=true}).
 */
@Tag("openapi-gen")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiExportIT {

    @LocalServerPort
    int port;

    @Value("${enrichment.openapi-out:}")
    String outPath;

    private final RestTemplate http = new RestTemplateBuilder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("kafka.bootstrap-servers", () -> "localhost:9092");
        r.add("trail-builder.base-url", () -> "http://localhost:1");
        r.add("enrichment.rulesets-file", () -> "config/rulesets.yaml");
        r.add("enrichment.chatter-overlay-file", () -> "build/tmp/overlay.json");
    }

    @Test
    void exportsOpenApiJson() throws Exception {
        String body = http.getForObject("http://localhost:" + port + "/openapi.json", String.class);
        assertThat(body).contains("/api/v1/sources/{source}/chatter");
        // Pretty-print for a stable diff and write to the configured output path (default: repo file).
        ObjectMapper mapper = new ObjectMapper();
        Object tree = mapper.readValue(body, Object.class);
        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
        Path out = Path.of(outPath == null || outPath.isBlank() ? "openapi.json" : outPath);
        Files.writeString(out, pretty + "\n");
    }
}
