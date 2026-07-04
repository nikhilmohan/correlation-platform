package com.acp.alarmmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.alarmmanager.repository.AlarmRepository;
import com.acp.alarmmanager.repository.ProcessedEventRepository;
import com.acp.alarmmanager.repository.StateTransitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC14 — GET /openapi.json returns 200, a valid OpenAPI 3.1 document containing the /alarms and
 * /alarms/{alarmId} operations, where GET /alarms declares limit/offset query params, its 200
 * response is the { items, total, limit, offset } envelope (NOT page/size), the state enum
 * includes in-progress, and both AlarmSummary and AlarmDetail declare an alarmType property
 * distinct from eventType/probableCause. Also a drift guard vs. the checked-in openapi.json.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("openapi")
class OpenApiContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // The event-driven infra beans that would otherwise open real connections; the OpenAPI surface
    // is generated purely from the annotated controllers/DTOs.
    @MockBean
    private AlarmRepository alarmRepository;
    @MockBean
    private StateTransitionRepository stateTransitionRepository;
    @MockBean
    private ProcessedEventRepository processedEventRepository;
    @MockBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void publishesValidOpenApi31WithAlarmPaths() throws Exception {
        String body = rest.getForObject("/openapi.json", String.class);
        assertThat(body).isNotNull();
        JsonNode doc = MAPPER.readTree(body);

        // Valid OpenAPI 3.1 document.
        assertThat(doc.path("openapi").asText()).startsWith("3.1");

        JsonNode paths = doc.path("paths");
        assertThat(paths.has("/alarms")).isTrue();
        assertThat(paths.has("/alarms/{alarmId}")).isTrue();

        JsonNode listGet = paths.path("/alarms").path("get");

        // Declares limit + offset query params (NOT page/size).
        StringBuilder params = new StringBuilder();
        listGet.path("parameters").forEach(p -> params.append(p.path("name").asText()).append(','));
        String paramNames = params.toString();
        assertThat(paramNames).contains("limit").contains("offset");
        assertThat(paramNames).doesNotContain("page").doesNotContain("size");

        // state enum includes in-progress.
        String docStr = body;
        assertThat(docStr).contains("in-progress");

        // GET /alarms 200 response schema is the AlarmPage envelope { items, total, limit, offset }.
        JsonNode schemas = doc.path("components").path("schemas");
        JsonNode alarmPage = schemas.path("AlarmPage");
        assertThat(alarmPage.path("properties").has("items")).isTrue();
        assertThat(alarmPage.path("properties").has("total")).isTrue();
        assertThat(alarmPage.path("properties").has("limit")).isTrue();
        assertThat(alarmPage.path("properties").has("offset")).isTrue();
        assertThat(alarmPage.path("properties").has("totalElements")).isFalse();
        assertThat(alarmPage.path("properties").has("totalPages")).isFalse();

        // AlarmSummary + AlarmDetail both declare alarmType distinct from eventType/probableCause.
        JsonNode summary = schemas.path("AlarmSummary").path("properties");
        assertThat(summary.has("alarmType")).isTrue();
        assertThat(summary.has("eventType")).isTrue();
        JsonNode detail = schemas.path("AlarmDetail").path("properties");
        assertThat(detail.has("alarmType")).isTrue();
        assertThat(detail.has("eventType")).isTrue();
        assertThat(detail.has("probableCause")).isTrue();
    }

    @Test
    void runningSurfaceMatchesCheckedInOpenApiJson() throws Exception {
        String live = rest.getForObject("/openapi.json", String.class);
        JsonNode liveDoc = MAPPER.readTree(live);

        Path checkedIn = Paths.get("openapi.json");
        if (Boolean.getBoolean("openapi.regenerate")) {
            Files.writeString(checkedIn,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(liveDoc));
        }
        assertThat(Files.exists(checkedIn))
                .withFailMessage("services/alarm-manager/openapi.json is missing — generate + commit it")
                .isTrue();
        JsonNode checkedInDoc = MAPPER.readTree(Files.readString(checkedIn));

        // Structural comparison on the stable parts (paths + component schemas); the volatile
        // servers[].url (random test port) is ignored.
        assertThat(liveDoc.path("paths"))
                .withFailMessage("OpenAPI paths drifted from checked-in openapi.json — regenerate + commit")
                .isEqualTo(checkedInDoc.path("paths"));
        assertThat(liveDoc.path("components").path("schemas"))
                .withFailMessage("OpenAPI schemas drifted from checked-in openapi.json — regenerate + commit")
                .isEqualTo(checkedInDoc.path("components").path("schemas"));
    }
}
