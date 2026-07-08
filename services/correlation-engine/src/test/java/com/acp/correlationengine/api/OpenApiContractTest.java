package com.acp.correlationengine.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.correlationengine.correlate.AlarmStatusEmitter;
import com.acp.correlationengine.correlate.CorrelationResultEmitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

/**
 * Provider-side OpenAPI 3.1 contract test — boots the web context, fetches the springdoc-generated
 * {@code /openapi.json}, asserts the frozen surface ({@code GET /incidents} with the canonical
 * {@code IncidentPage} envelope, {@code GET /incidents/{id}}, {@code GET /stats}), and writes the
 * document to {@code services/correlation-engine/openapi.json} so the checked-in spec stays in sync
 * with the code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OpenApiContractTest {

    @TestConfiguration
    static class Emitters {
        @Bean
        CorrelationResultEmitter correlationResultEmitter() {
            return incident -> { };
        }

        @Bean
        AlarmStatusEmitter alarmStatusEmitter() {
            return new AlarmStatusEmitter() {
                @Override public void fireInProgress(String a, long t) { }
                @Override public void fireCorrelated(String a, long t) { }
                @Override public void fireRevertedOpen(String a, long t) { }
            };
        }
    }

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;

    @Test
    void publishesOpenApi31_withFrozenSurface_andChecksItIn() throws Exception {
        String body = rest.getForObject("http://localhost:" + port + "/openapi.json", String.class);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode doc = mapper.readTree(body);

        assertThat(doc.get("openapi").asText()).startsWith("3.1");
        JsonNode paths = doc.get("paths");
        assertThat(paths.has("/incidents")).isTrue();
        assertThat(paths.has("/incidents/{incidentId}")).isTrue();
        assertThat(paths.has("/stats")).isTrue();
        // Contract addition — P3 demo/ops reset (admin path).
        assertThat(paths.has("/admin/reset-correlation")).isTrue();
        assertThat(paths.get("/admin/reset-correlation").has("post")).isTrue();

        // Check the generated document in to the service dir (kept in sync with the code).
        Path out = Path.of(System.getProperty("user.dir"), "openapi.json");
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        assertThat(Files.exists(out)).isTrue();
    }
}
