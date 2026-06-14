package com.acp.topology.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.acp.topology.graph.GraphRepository;
import com.acp.topology.meta.SnapshotRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.vesoft.nebula.client.graph.net.NebulaPool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * AC-18 (OpenAPI 3.1 contract) + AC-27..AC-30 (frozen response DTO shapes). Boots the
 * web/springdoc layer in isolation (PostgreSQL/Flyway/Kafka autoconfig excluded; NebulaPool +
 * repositories mocked) and asserts the published HTTP contract is FROZEN:
 *
 * <ol>
 *   <li><b>Drift gate</b> — the live {@code /openapi.json} is generated in-memory and compared,
 *       structurally, against the checked-in {@code services/topology/openapi.json} (the single
 *       source of truth consumers build against). The test <b>FAILS</b> on any drift, with a
 *       message telling the developer to regenerate + commit. The volatile {@code servers[].url}
 *       (random test port) is normalised out before comparison; everything else (paths, operations,
 *       component schemas) must match exactly. The test never overwrites the checked-in file.</li>
 *   <li><b>Live-response-vs-schema</b> — representative live API responses are validated against the
 *       component schemas declared in the <b>checked-in</b> {@code openapi.json}, so the running
 *       implementation cannot diverge from the published contract (AC-18/AC-27).</li>
 *   <li>The frozen DTO shapes (SnapshotIngestResponse, flat SiteDto, SiteObjectsDto nodes+edges,
 *       NodeDto with no {@code layer} field) are present in the checked-in document.</li>
 * </ol>
 *
 * <p>To regenerate after an <i>intended</i> contract change, run with {@code -Dopenapi.regenerate=true}
 * (writes the checked-in file) — and then commit it. This is an explicit, opt-in escape hatch; the
 * default run is a read-only guard that fails the build on genuine drift.
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
    void liveOpenApiMatchesCheckedInContract_orFailsOnDrift() throws Exception {
        String body = rest.getForObject("/openapi.json", String.class);
        assertThat(body).as("the service must serve /openapi.json").isNotBlank();
        JsonNode live = mapper.readTree(body);

        // OpenAPI 3.1 document with the ingestion + all query operations (AC-18).
        assertThat(live.get("openapi").asText()).startsWith("3.1");

        Path checkedIn = projectRoot().resolve("openapi.json");

        // Opt-in regenerate hook for an *intended* contract change (then the dev commits the file).
        if (Boolean.getBoolean("openapi.regenerate")) {
            Files.writeString(checkedIn,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(live));
            return;
        }

        assertThat(Files.isRegularFile(checkedIn))
                .as("checked-in openapi.json must exist at %s", checkedIn)
                .isTrue();
        JsonNode committed = mapper.readTree(Files.readString(checkedIn));

        // The drift gate: structural equality of the generated vs the committed contract, after
        // normalising the volatile per-run server url. JsonNode object-equality is key-order
        // independent, so springdoc's nondeterministic ordering does not cause false drift.
        JsonNode normalisedLive = normalise(live);
        JsonNode normalisedCommitted = normalise(committed);
        assertThat(normalisedLive)
                .as("OpenAPI contract DRIFT: the live /openapi.json no longer matches the checked-in "
                        + "services/topology/openapi.json (the frozen source of truth). If this change "
                        + "is intended, regenerate with `./gradlew test "
                        + "-Dopenapi.regenerate=true` and commit the updated openapi.json; otherwise "
                        + "revert the HTTP-surface change.")
                .isEqualTo(normalisedCommitted);
    }

    @Test
    void checkedInContractDeclaresFrozenOperationsAndShapes() throws Exception {
        JsonNode doc = mapper.readTree(Files.readString(projectRoot().resolve("openapi.json")));

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
    }

    @Test
    void checkedInContractPublishesTraversalMaxDepthBound() throws Exception {
        // #214: the GET /topology/traversal maxDepth query-param schema must declare minimum 1 /
        // maximum 32 (the honoured runaway cap), so trail-builder's mock (built from this frozen
        // openapi) rejects out-of-range depths exactly as the live API does.
        JsonNode doc = mapper.readTree(Files.readString(projectRoot().resolve("openapi.json")));
        JsonNode params = doc.get("paths").get("/topology/traversal").get("get").get("parameters");
        JsonNode maxDepthSchema = null;
        for (JsonNode p : params) {
            if ("maxDepth".equals(p.get("name").asText())) {
                maxDepthSchema = p.get("schema");
            }
        }
        assertThat(maxDepthSchema).as("maxDepth query param must be declared").isNotNull();
        assertThat(maxDepthSchema.get("minimum").asInt()).isEqualTo(1);
        assertThat(maxDepthSchema.get("maximum").asInt()).isEqualTo(32);
    }

    /**
     * AC-18/AC-27 — a representative live response validates against the schema declared in the
     * CHECKED-IN openapi.json. A validation 422 ({@code ApiError}) is returned by the same controller
     * surface; its body must conform to the frozen {@code ApiError} component schema. (The happy-path
     * ingest needs a live graph/PostgreSQL, exercised by the Testcontainers ITs; here we validate the
     * always-available structured-error response against the checked-in schema so the contract is
     * enforced without external infra.)
     */
    @Test
    void liveErrorResponseValidatesAgainstCheckedInSchema() throws Exception {
        JsonNode doc = mapper.readTree(Files.readString(projectRoot().resolve("openapi.json")));

        // POST malformed JSON → HttpMessageNotReadableException → structured ApiError (422). This
        // exercises the real controller surface + exception handler without any graph/PostgreSQL.
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<String> req =
                new org.springframework.http.HttpEntity<>("{ not json", headers);
        org.springframework.http.ResponseEntity<String> resp =
                rest.exchange("/topology/snapshots", org.springframework.http.HttpMethod.POST,
                        req, String.class);
        String errorBody = resp.getBody();
        assertThat(errorBody).as("an invalid ingest must return a structured error body").isNotBlank();
        JsonNode errorJson = stripNulls(mapper.readTree(errorBody));

        // Sanity: it really is the structured ApiError shape (not an HTML/whitelabel error page).
        assertThat(errorJson.has("status")).isTrue();
        assertThat(errorJson.has("error")).isTrue();

        JsonSchema apiErrorSchema = schemaFor(doc, "ApiError");
        Set<?> violations = apiErrorSchema.validate(errorJson);
        assertThat(violations)
                .as("live error response %s must validate against the checked-in ApiError schema",
                        errorJson)
                .isEmpty();
    }

    /**
     * Build a networknt {@link JsonSchema} for one component, inlining the document's
     * {@code components} so internal {@code $ref}s resolve.
     */
    private JsonSchema schemaFor(JsonNode doc, String component) {
        ObjectNode schemaNode = ((ObjectNode) doc.get("components").get("schemas").get(component)).deepCopy();
        // Carry the components across so any nested $ref (e.g. ApiError -> Violation) resolves.
        schemaNode.set("components", doc.get("components"));
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        return factory.getSchema(schemaNode);
    }

    /**
     * Remove JSON-null-valued object fields recursively. A null-valued field (e.g. an unset
     * {@code traceId}) is semantically "absent"; since the checked-in schemas declare no field as
     * required, stripping nulls validates the PRESENT fields against the schema without a spurious
     * "null is not a string" failure.
     */
    private JsonNode stripNulls(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = mapper.createObjectNode();
            node.fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    obj.set(e.getKey(), stripNulls(e.getValue()));
                }
            });
            return obj;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode arr = mapper.createArrayNode();
            node.forEach(child -> arr.add(stripNulls(child)));
            return arr;
        }
        return node;
    }

    /** Strip the volatile per-run {@code servers} block so the drift comparison is stable. */
    private JsonNode normalise(JsonNode doc) {
        ObjectNode copy = (ObjectNode) doc.deepCopy();
        copy.remove("servers");
        return copy;
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
