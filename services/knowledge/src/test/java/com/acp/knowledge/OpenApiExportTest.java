package com.acp.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * OpenAPI drift gate (the real CI enforcement promised in design.md §"OpenAPI generation &amp;
 * contract tests"). This test boots the app, fetches the live {@code /openapi.json} document, and:
 *
 * <ul>
 *   <li><b>Default (CI / {@code ./gradlew build}):</b> compares the freshly generated document to
 *       the checked-in {@code services/knowledge/openapi.json} and <b>FAILS the build on any
 *       difference</b>, with a clear "regenerate and commit" message. The checked-in file is the
 *       single source of truth (the provider contract collaborators build clients against). Any
 *       drift is a contract change requiring {@code architecture.md} + human approval.</li>
 *   <li><b>Update mode ({@code -DupdateOpenApi=true}, wired to the {@code generateOpenApi} Gradle
 *       task):</b> writes the generated document to the checked-in file instead of asserting, so a
 *       deliberate, reviewed contract change can be regenerated and committed.</li>
 * </ul>
 *
 * <p>The test's working directory is the service module dir ({@code services/knowledge}), so the
 * relative {@code openapi.json} path resolves to the checked-in copy. Output is pretty-printed with
 * a trailing newline so the checked-in file is byte-stable across runs and diffs cleanly.
 */
class OpenApiExportTest extends AbstractKnowledgeIT {

    /** Checked-in provider contract, relative to the service module working dir. */
    private static final Path CHECKED_IN = Paths.get("openapi.json");

    private static final String UPDATE_FLAG = "updateOpenApi";

    @Test
    void servedDocumentMatchesCheckedInOpenApi_orRegenerateWhenFlagged() throws Exception {
        String body = mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Re-serialize through the same writer used to produce the checked-in file so the
        // comparison is formatting-stable (key order is springdoc's, indentation is Jackson's).
        JsonNode generatedTree = objectMapper.readTree(body);
        String generated = render(generatedTree);

        if (Boolean.parseBoolean(System.getProperty(UPDATE_FLAG, "false"))) {
            Files.writeString(CHECKED_IN, generated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[generateOpenApi] wrote " + CHECKED_IN.toAbsolutePath());
            return;
        }

        Assertions.assertTrue(Files.exists(CHECKED_IN),
                "checked-in OpenAPI contract is missing: " + CHECKED_IN.toAbsolutePath()
                        + " — run `./gradlew :services:knowledge:generateOpenApi` and commit it.");

        String checkedIn = Files.readString(CHECKED_IN, StandardCharsets.UTF_8);

        // Compare structurally (line-separator/trailing-whitespace independent) so the gate fires
        // on genuine contract drift, not on a CRLF/LF or trailing-newline difference.
        JsonNode checkedInTree = objectMapper.readTree(checkedIn);
        Assertions.assertEquals(checkedInTree, generatedTree,
                "OpenAPI DRIFT: the served /openapi.json no longer matches the checked-in "
                        + "services/knowledge/openapi.json. The checked-in file is the frozen "
                        + "provider contract. If this change is intentional, regenerate it with "
                        + "`./gradlew :services:knowledge:generateOpenApi` and commit the updated "
                        + "services/knowledge/openapi.json (a contract change also requires "
                        + "docs/architecture.md + human approval). If unintentional, revert the "
                        + "API change.");
    }

    /** Pretty-print + trailing newline — must match how {@code generateOpenApi} writes the file. */
    private String render(JsonNode doc) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc)
                + System.lineSeparator();
    }
}
