package com.acp.topology.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.topology.graph.GraphRepository;
import com.acp.topology.meta.SnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC-18 (OpenAPI 3.1 contract), AC-27 (SnapshotIngestResponse), AC-28 (flat SiteDto), AC-29
 * (SiteObjectsDto nodes AND edges), AC-30 (NodeDto, layer == objectType). Boots the web/springdoc
 * layer in isolation (PostgreSQL/Flyway/Kafka autoconfig excluded; NebulaPool + repositories
 * mocked), fetches the live {@code /openapi.json}, CHECKS IT IN at {@code services/topology/openapi.json}
 * (the single source of truth consumers build against), and asserts the ingestion + all query
 * operations and the frozen response schemas are present.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("openapi")
class OpenApiContractTest {

    // Replace the infra beans that would otherwise open real connections (the OpenAPI surface is
    // generated purely from the annotated controllers/DTOs).
    @MockBean
    private NebulaPool nebulaPool;

    @MockBean
    private GraphRepository graphRepository;

    @MockBean
    private SnapshotRepository snapshotRepository;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void servesOpenApiAndChecksItIn() throws Exception {
        String body = rest.getForObject("/openapi.json", String.class);
        assertThat(body).isNotBlank();
        JsonNode doc = mapper.readTree(body);

        // OpenAPI 3.1 document with the ingestion + all query operations (AC-18).
        assertThat(doc.get("openapi").asText()).startsWith("3.1");
        JsonNode paths = doc.get("paths");
        assertThat(paths.has("/topology/snapshots")).isTrue();
        assertThat(paths.get("/topology/snapshots").has("post")).isTrue();
        assertThat(paths.has("/topology/nodes/{managedObjectId}")).isTrue();
        assertThat(paths.has("/topology/edges/{edgeId}")).isTrue();
        assertThat(paths.has("/topology/nodes/{managedObjectId}/neighbors")).isTrue();
        assertThat(paths.has("/topology/traversal")).isTrue();
        assertThat(paths.has("/topology/nodes")).isTrue();
        assertThat(paths.has("/topology/sites")).isTrue();
        assertThat(paths.has("/topology/sites/{siteId}/objects")).isTrue();
        assertThat(paths.has("/topology/snapshots/current")).isTrue();

        JsonNode schemas = doc.get("components").get("schemas");

        // AC-27: SnapshotIngestResponse frozen shape.
        assertFields(schemas.get("SnapshotIngestResponse"),
                "snapshotId", "domain", "status", "nodeCount", "edgeCount", "changeType");
        // AC-30: NodeDto with NO separate layer field (layer == objectType).
        JsonNode nodeDto = schemas.get("NodeDto");
        assertFields(nodeDto, "managedObjectId", "objectType", "domain", "snapshotId", "attributes");
        assertThat(nodeDto.get("properties").has("layer")).isFalse();
        // AC-28: flat SiteDto geo fields.
        assertFields(schemas.get("SiteDto"), "siteId", "name", "latitude", "longitude", "region");
        // AC-29: SiteObjectsDto carries nodes AND edges.
        assertFields(schemas.get("SiteObjectsDto"),
                "siteId", "domain", "snapshotId", "nodeCount", "edgeCount", "nodes", "edges");

        // Check the generated document in as the single source of truth.
        Path out = projectRoot().resolve("openapi.json");
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        assertThat(Files.isRegularFile(out)).isTrue();
    }

    private void assertFields(JsonNode schema, String... fields) {
        assertThat(schema).as("schema must be present").isNotNull();
        JsonNode props = schema.get("properties");
        for (String f : fields) {
            assertThat(props.has(f)).as("property %s present", f).isTrue();
        }
    }

    private static Path projectRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("src/main/java/com/acp/topology"))) {
            return cwd;
        }
        return cwd.resolve("services/topology");
    }
}
