package com.acp.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * AC13 — the published OpenAPI 3.1 document is served and matches the implemented operations
 * (provider-side contract test). {@code GET /openapi.json} returns a valid OpenAPI 3.1 document
 * including GET/POST/PUT for each of the eight knowledge-record types, the vocabulary query
 * endpoint, and a versioned-read operation accepting a version path parameter.
 */
class OpenApiContractTest extends AbstractKnowledgeIT {

    @Test
    void openapiJson_isOpenApi31_andCoversTheOperations() throws Exception {
        String body = mockMvc.perform(get("/openapi.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode doc = objectMapper.readTree(body);

        // Export the served document to the checked-in services/knowledge/openapi.json (the
        // provider contract collaborating services build their clients against). Written
        // pretty-printed + with a trailing newline so the checked-in file is stable across runs.
        java.nio.file.Path target = java.nio.file.Paths.get("openapi.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), doc);
        java.nio.file.Files.writeString(target,
                System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);

        // Valid OpenAPI 3.1 document.
        Assertions.assertTrue(doc.path("openapi").asText().startsWith("3.1"),
                "expected OpenAPI 3.1, got: " + doc.path("openapi").asText());
        JsonNode paths = doc.path("paths");
        Assertions.assertTrue(paths.isObject() && paths.size() > 0, "paths must be present");

        // The generic CRUD path template covers every recordType (one templated path).
        JsonNode collection = paths.path("/domains/{domain}/{recordType}");
        Assertions.assertFalse(collection.isMissingNode(),
                "expected the generic collection path /domains/{domain}/{recordType}");
        Assertions.assertFalse(collection.path("get").isMissingNode(), "GET (list) expected");
        Assertions.assertFalse(collection.path("post").isMissingNode(), "POST (create) expected");

        JsonNode item = paths.path("/domains/{domain}/{recordType}/{recordId}");
        Assertions.assertFalse(item.isMissingNode(),
                "expected the item path /domains/{domain}/{recordType}/{recordId}");
        Assertions.assertFalse(item.path("get").isMissingNode(), "GET (current) expected");
        Assertions.assertFalse(item.path("put").isMissingNode(), "PUT (update) expected");

        // Versioned-read operation accepting a {version} path parameter.
        JsonNode versioned =
                paths.path("/domains/{domain}/{recordType}/{recordId}/versions/{version}");
        Assertions.assertFalse(versioned.isMissingNode(),
                "expected a versioned-read operation accepting {version}");
        Assertions.assertFalse(versioned.path("get").isMissingNode(),
                "GET on the versioned-read operation expected");
        boolean hasVersionParam = false;
        for (JsonNode p : versioned.path("get").path("parameters")) {
            if ("version".equals(p.path("name").asText())) {
                hasVersionParam = true;
                break;
            }
        }
        Assertions.assertTrue(hasVersionParam,
                "the versioned-read operation must accept a 'version' path parameter");

        // The dedicated vocabulary query endpoint.
        Assertions.assertFalse(paths.path("/domains/{domain}/vocabulary").path("get").isMissingNode(),
                "expected GET /domains/{domain}/vocabulary");
    }
}
