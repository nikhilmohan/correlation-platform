package com.acp.enrichment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * Tagged {@code openapi-gen}.
 *
 * <p><b>Deterministic output (minor m1).</b> The served document is normalised before it is written
 * or compared: map keys are sorted ({@link SerializationFeature#ORDER_MAP_ENTRIES_BY_KEYS}) and the
 * dynamic {@code servers[].url} (a random {@code localhost:PORT}) is replaced with the static
 * placeholder {@code /}. This makes the file byte-stable across runs so the drift check below is
 * meaningful.
 *
 * <p>By default the test (re)writes the checked-in file. When {@code ENRICHMENT_VERIFY_OPENAPI=true}
 * (the CI gate, wired via the {@code generateOpenApi}/verify task) it instead asserts the served,
 * normalised document is byte-identical to the checked-in file and fails the build on drift.
 */
@Tag("openapi-gen")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiExportIT {

    private static final String CHECKED_IN = "openapi.json";

    @LocalServerPort
    int port;

    @Value("${enrichment.openapi-out:}")
    String outPath;

    @Value("${enrichment.openapi-verify:false}")
    boolean verify;

    private final RestTemplate http = new RestTemplateBuilder().build();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("kafka.bootstrap-servers", () -> "localhost:9092");
        r.add("trail-builder.base-url", () -> "http://localhost:1");
        r.add("knowledge.base-url", () -> "http://localhost:1");
        r.add("knowledge.connect-timeout-ms", () -> "200");
        r.add("enrichment.rulesets-file", () -> "config/rulesets.yaml");
        r.add("enrichment.chatter-overlay-file", () -> "build/tmp/overlay.json");
    }

    @Test
    void exportsOrVerifiesDeterministicOpenApiJson() throws Exception {
        String body = http.getForObject("http://localhost:" + port + "/openapi.json", String.class);
        assertThat(body).contains("/api/v1/sources/{source}/chatter");

        String normalized = normalize(body);

        if (verify) {
            // Drift gate: the served (normalised) spec must match the checked-in file byte-for-byte.
            Path checkedIn = Path.of(CHECKED_IN);
            assertThat(Files.exists(checkedIn))
                    .as("checked-in services/enrichment/openapi.json must exist").isTrue();
            String onDisk = Files.readString(checkedIn);
            assertThat(normalized)
                    .as("served OpenAPI drifted from the checked-in services/enrichment/openapi.json"
                            + " — run ./gradlew generateOpenApi and commit the result")
                    .isEqualTo(onDisk);
        } else {
            Path out = Path.of(outPath == null || outPath.isBlank() ? CHECKED_IN : outPath);
            Files.writeString(out, normalized);
        }
    }

    /**
     * Normalise the served OpenAPI JSON into a byte-stable form: recursively sort every object's
     * keys (springdoc emits e.g. the {@code responses} status-code map in run-dependent order) and
     * replace the dynamic server URL with a static placeholder.
     */
    private static String normalize(String body) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode tree = (ObjectNode) mapper.readTree(body);

        ArrayNode servers = JsonNodeFactory.instance.arrayNode();
        ObjectNode server = JsonNodeFactory.instance.objectNode();
        server.put("url", "/");
        server.put("description", "Generated server url");
        servers.add(server);
        tree.set("servers", servers);

        JsonNode sorted = sortKeys(tree);
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted) + "\n";
    }

    /** Recursively rebuild the tree with every object's fields in sorted key order. */
    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            ObjectNode src = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            src.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                out.set(name, sortKeys(src.get(name)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode src = (ArrayNode) node;
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : src) {
                out.add(sortKeys(child));
            }
            return out;
        }
        return node;
    }
}
