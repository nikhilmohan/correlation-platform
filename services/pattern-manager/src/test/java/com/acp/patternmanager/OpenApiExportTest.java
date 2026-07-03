package com.acp.patternmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * OpenAPI 3.1 export + drift gate. Boots the app, fetches the live {@code /openapi.json}, and:
 * <ul>
 *   <li>on a normal build (updateOpenApi unset) ASSERTS it matches the checked-in
 *       {@code services/pattern-manager/openapi.json} and FAILS on drift (contract change);
 *   <li>under {@code -DupdateOpenApi=true} REWRITES the checked-in file.
 * </ul>
 * The checked-in {@code openapi.json} is the SSoT for the HTTP surface (PatternPage envelope,
 * PatternView incl. trailId + sessionWindow, frozen PatternEdit body).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("openapitest")
class OpenApiExportTest {

    private static final Path CHECKED_IN = Path.of("openapi.json");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void openApiDocumentMatchesCheckedInOrRegenerates() throws Exception {
        String live = rest.getForObject("http://localhost:" + port + "/openapi.json", String.class);
        assertThat(live).isNotBlank();
        JsonNode liveJson = mapper.readTree(live);

        // Sanity: the frozen surface is present.
        assertThat(liveJson.at("/paths/~1patterns/get").isMissingNode()).isFalse();
        assertThat(liveJson.at("/components/schemas/PatternPage").isMissingNode()).isFalse();
        assertThat(liveJson.at("/components/schemas/PatternView").isMissingNode()).isFalse();
        assertThat(liveJson.at("/components/schemas/PatternEdit").isMissingNode()).isFalse();

        String pretty = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(liveJson) + "\n";

        if (System.getProperty("updateOpenApi") != null) {
            Files.writeString(CHECKED_IN, pretty, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(CHECKED_IN))
                .as("checked-in openapi.json must exist (run `./gradlew generateOpenApi`)")
                .isTrue();
        String checkedIn = Files.readString(CHECKED_IN, StandardCharsets.UTF_8);
        assertThat(mapper.readTree(checkedIn))
                .as("served /openapi.json drifted from the checked-in services/pattern-manager/openapi.json "
                        + "— a contract change. Run `./gradlew generateOpenApi` and review+commit it.")
                .isEqualTo(liveJson);
    }
}
