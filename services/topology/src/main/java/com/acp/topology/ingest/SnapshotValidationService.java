package com.acp.topology.ingest;

import com.acp.topology.api.dto.ApiError.Violation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Validates a topology snapshot file: (1) structural — against the single canonical
 * {@code services/topology/schema/snapshot.schema.json} (loaded from the classpath); (2) semantic —
 * {@code managedObjectId} generic pattern, {@code objectType} equals the id prefix, and edge
 * {@code from}/{@code to} reference nodes present in {@code nodes[]}. Domain-vocabulary checks
 * (objectType/relation in the domain's Knowledge vocabulary) are done by {@link VocabularyValidator}.
 *
 * <p>All validation runs to completion BEFORE any NebulaGraph write or PostgreSQL row, so a
 * malformed file never produces a partial graph.
 */
@Service
public class SnapshotValidationService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotValidationService.class);

    /** Mirrors {@code com.acp.eventmodel.ManagedObjectId.PATTERN} — the generic scheme. */
    private static final java.util.regex.Pattern MOID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9]*:[^:]+$");

    private final ObjectMapper mapper;
    private final JsonSchema schema;

    public SnapshotValidationService(ObjectMapper mapper) {
        this.mapper = mapper;
        this.schema = loadSchema();
    }

    private static JsonSchema loadSchema() {
        JsonSchemaFactory factory =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = new ClassPathResource("snapshot/snapshot.schema.json").getInputStream()) {
            return factory.getSchema(in);
        } catch (IOException e) {
            throw new IllegalStateException("could not load snapshot.schema.json from classpath", e);
        }
    }

    /**
     * Parse + structurally + semantically validate. On success returns the typed {@link SnapshotFile}.
     *
     * @throws ValidationException with structured violations on any failure (HTTP 422)
     */
    public SnapshotFile validate(String rawJson) {
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new ValidationException("snapshot file is not valid JSON",
                    List.of(new Violation("$", "json", "input is not valid JSON")));
        }

        // 1. Structural: JSON Schema (required fields, generic moId pattern, additionalProperties).
        Set<ValidationMessage> messages = schema.validate(root);
        if (!messages.isEmpty()) {
            List<Violation> violations = new ArrayList<>();
            for (ValidationMessage m : messages) {
                violations.add(new Violation(m.getInstanceLocation().toString(),
                        m.getType(), m.getMessage()));
            }
            log.warn("snapshot rejected: schema validation failed with {} violation(s)",
                    violations.size());
            throw new ValidationException("snapshot file failed schema validation", violations);
        }

        SnapshotFile file = bind(root);

        // 2. Semantic: objectType == id prefix; edge refs resolve.
        List<Violation> semantic = semanticChecks(file);
        if (!semantic.isEmpty()) {
            log.warn("snapshot rejected: semantic validation failed with {} violation(s)",
                    semantic.size());
            throw new ValidationException("snapshot file failed semantic validation", semantic);
        }
        return file;
    }

    private SnapshotFile bind(JsonNode root) {
        List<SnapshotFile.NodeRecord> nodes = new ArrayList<>();
        for (JsonNode n : root.path("nodes")) {
            nodes.add(new SnapshotFile.NodeRecord(
                    text(n, "managedObjectId"),
                    text(n, "objectType"),
                    text(n, "name"),
                    attributes(n.get("attributes"))));
        }
        List<SnapshotFile.EdgeRecord> edges = new ArrayList<>();
        for (JsonNode e : root.path("edges")) {
            edges.add(new SnapshotFile.EdgeRecord(
                    text(e, "from"),
                    text(e, "to"),
                    text(e, "relation"),
                    attributes(e.get("attributes"))));
        }
        return new SnapshotFile(
                root.path("schemaVersion").asInt(),
                root.hasNonNull("snapshotId") ? root.get("snapshotId").asText() : null,
                text(root, "domain"),
                nodes,
                edges);
    }

    private List<Violation> semanticChecks(SnapshotFile file) {
        List<Violation> violations = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();

        for (int i = 0; i < file.nodes().size(); i++) {
            SnapshotFile.NodeRecord node = file.nodes().get(i);
            String moid = node.managedObjectId();
            nodeIds.add(moid);
            // EH-3: generic moId pattern (also enforced by schema, defence in depth).
            if (moid == null || !MOID_PATTERN.matcher(moid).matches()) {
                violations.add(new Violation("$.nodes[" + i + "].managedObjectId", "pattern",
                        "does not match the generic objectType:id scheme"));
                continue;
            }
            // EH-4: objectType must equal the id prefix.
            String prefix = moid.substring(0, moid.indexOf(':'));
            if (!prefix.equals(node.objectType())) {
                violations.add(new Violation("$.nodes[" + i + "].objectType", "objectType-prefix",
                        "objectType '" + node.objectType() + "' is inconsistent with managedObjectId "
                                + "prefix '" + prefix + "'"));
            }
        }

        // EH-5: edge from/to must reference a node present in nodes[].
        for (int i = 0; i < file.edges().size(); i++) {
            SnapshotFile.EdgeRecord edge = file.edges().get(i);
            if (!nodeIds.contains(edge.from())) {
                violations.add(new Violation("$.edges[" + i + "].from", "dangling-reference",
                        "edge 'from' " + edge.from() + " is not present in nodes[]"));
            }
            if (!nodeIds.contains(edge.to())) {
                violations.add(new Violation("$.edges[" + i + "].to", "dangling-reference",
                        "edge 'to' " + edge.to() + " is not present in nodes[]"));
            }
        }
        return violations;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attributes(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, Map.class);
    }
}
