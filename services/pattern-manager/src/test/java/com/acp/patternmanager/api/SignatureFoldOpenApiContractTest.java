package com.acp.patternmanager.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * [SIG-FOLD] AC-SF-19: the checked-in {@code openapi.json} PatternView schema exposes the four new
 * impact-metric fields with the correct JSON-Schema types, the existing {@code instanceCount} is
 * retained with an updated description, and no existing field was removed or renamed. Pure parse of
 * the committed artifact (the drift gate {@code OpenApiExportTest} separately pins it to the live doc).
 */
class SignatureFoldOpenApiContractTest {

    private static final Path OPENAPI = Path.of("openapi.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void patternViewHasImpactFields() throws Exception {
        JsonNode props = mapper.readTree(Files.readString(OPENAPI))
                .at("/components/schemas/PatternView/properties");
        assertThat(props.isMissingNode()).isFalse();

        // Two new integer int32 fields.
        assertIntInt32(props, "occurrenceCount");
        assertIntInt32(props, "trailCount");

        // Two new date-time string fields.
        assertDateTime(props, "firstSeen");
        assertDateTime(props, "lastSeen");

        // instanceCount retained with the updated description distinguishing it from occurrenceCount.
        JsonNode instanceCount = props.get("instanceCount");
        assertThat(instanceCount).isNotNull();
        assertThat(instanceCount.path("type").asText()).isEqualTo("integer");
        assertThat(instanceCount.path("description").asText())
                .contains("total number of individual alarm instances")
                .contains("occurrenceCount");

        // Existing fields still present (no removal/rename).
        for (String kept : new String[] {"patternId", "trailId", "sequence", "rootCauseAlarmType",
                "support", "confidence", "lift", "sessionWindow", "reconcileStatus",
                "supportingInstances", "sampleAlarms", "lifecycle"}) {
            assertThat(props.has(kept)).as("PatternView must still expose %s", kept).isTrue();
        }
    }

    private static void assertIntInt32(JsonNode props, String field) {
        JsonNode n = props.get(field);
        assertThat(n).as("PatternView.%s present", field).isNotNull();
        assertThat(n.path("type").asText()).isEqualTo("integer");
        assertThat(n.path("format").asText()).isEqualTo("int32");
    }

    private static void assertDateTime(JsonNode props, String field) {
        JsonNode n = props.get(field);
        assertThat(n).as("PatternView.%s present", field).isNotNull();
        assertThat(n.path("type").asText()).isEqualTo("string");
        assertThat(n.path("format").asText()).isEqualTo("date-time");
    }
}
