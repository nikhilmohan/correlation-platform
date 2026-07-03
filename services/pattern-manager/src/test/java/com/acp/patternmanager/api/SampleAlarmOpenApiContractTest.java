package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.patternmanager.api.dto.PatternPage;
import com.acp.patternmanager.api.dto.PatternView;
import com.acp.patternmanager.api.dto.SampleAlarmView;
import com.acp.patternmanager.api.dto.SequenceElementView;
import com.acp.patternmanager.api.dto.SessionWindowView;
import com.acp.patternmanager.api.dto.SupportingInstanceView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * AC-SA-9 / AC-SA-10: a {@code GET /patterns/{id}} response (single) and a {@code GET /patterns}
 * (list) response — both carrying {@code sampleAlarms[]} — validate against the published, updated
 * {@code services/pattern-manager/openapi.json} schema (incl. {@code SampleAlarmView} and
 * {@code PatternView.sampleAlarms}). Serializes the DTOs exactly as the controller would (Jackson +
 * JavaTime, ISO-8601), then validates against the openapi components with networknt 2020-12.
 */
class SampleAlarmOpenApiContractTest {

    private static final Path OPENAPI = Path.of("openapi.json");

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** Build a validatable 2020-12 schema rooted at {@code component}, carrying the openapi components. */
    private JsonSchema schemaFor(String component) throws Exception {
        JsonNode openapi = mapper.readTree(Files.readString(OPENAPI));
        JsonNode components = openapi.at("/components");
        ObjectNode root = mapper.createObjectNode();
        root.put("$ref", "#/components/schemas/" + component);
        root.set("components", components);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
    }

    private static PatternView view(String id, List<SampleAlarmView> samples) {
        return new PatternView(
                id, "trail:ospf-area0:7",
                List.of(new SequenceElementView("FiberFault", false),
                        new SequenceElementView("LinkDown", false),
                        new SequenceElementView("PortDown", false)),
                "FiberFault", 0.4, 0.9, 3.2, null,
                new SessionWindowView(5000, "gap-based"),
                // Non-null for the nullable-but-string-typed springdoc schema (this test asserts the
                // sampleAlarms[]/SampleAlarmView surface validates; null-typing of other fields is a
                // separate springdoc concern, not part of AC-SA-9/10).
                "scenario-42", "unexplained", true, "connected", 1,
                List.of(new SupportingInstanceView("sw:1", "snap-9", null)),
                samples,
                "draft", "core-ip", OffsetDateTime.now(), OffsetDateTime.now());
    }

    private static List<SampleAlarmView> sample3() {
        return List.of(
                new SampleAlarmView("alm-1001", "FiberFault",
                        OffsetDateTime.parse("2026-06-20T14:03:11Z"),
                        "OpticalPort:lon-agg-1/xe-0/0/3", "critical"),
                new SampleAlarmView("alm-1002", "LinkDown",
                        OffsetDateTime.parse("2026-06-20T14:03:12Z"),
                        "Interface:lon-agg-1/ge-0/0/1", "major"),
                new SampleAlarmView("alm-1003", "PortDown",
                        OffsetDateTime.parse("2026-06-20T14:03:13Z"),
                        "Port:lon-core-2/et-1/1/2", "major"));
    }

    // AC-SA-9: single GET /patterns/{id} response with sample alarms validates against the spec.
    @Test
    void singleResponseValidatesAgainstSpec() throws Exception {
        JsonSchema schema = schemaFor("PatternView");
        JsonNode json = mapper.valueToTree(view("11111111-1111-1111-1111-111111111111", sample3()));

        // Sanity: the sampleAlarms field is present and populated.
        assertThat(json.get("sampleAlarms").isArray()).isTrue();
        assertThat(json.get("sampleAlarms")).hasSize(3);
        assertThat(json.at("/sampleAlarms/0/managedObjectId").asText())
                .isEqualTo("OpticalPort:lon-agg-1/xe-0/0/3");

        Set<com.networknt.schema.ValidationMessage> errors = schema.validate(json);
        assertThat(errors).as("PatternView (with sampleAlarms) must validate against openapi.json")
                .isEmpty();
    }

    // AC-SA-10: list GET /patterns response with sampleAlarms on each item validates against the spec.
    @Test
    void listResponseValidatesAgainstSpec() throws Exception {
        JsonSchema schema = schemaFor("PatternPage");
        PatternPage page = new PatternPage(
                List.of(view("11111111-1111-1111-1111-111111111111", sample3()),
                        view("22222222-2222-2222-2222-222222222222", List.of())),
                2, 50, 0);
        JsonNode json = mapper.valueToTree(page);

        // Every item carries sampleAlarms (present on both, [] on the second).
        assertThat(json.at("/items/0/sampleAlarms")).hasSize(3);
        assertThat(json.at("/items/1/sampleAlarms").isArray()).isTrue();
        assertThat(json.at("/items/1/sampleAlarms")).isEmpty();

        Set<com.networknt.schema.ValidationMessage> errors = schema.validate(json);
        assertThat(errors).as("PatternPage items[] (with sampleAlarms) must validate against openapi.json")
                .isEmpty();
    }
}
