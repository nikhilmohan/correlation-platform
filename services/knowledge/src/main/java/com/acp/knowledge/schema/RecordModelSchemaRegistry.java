package com.acp.knowledge.schema;

import com.acp.knowledge.domain.RecordType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads the bundled per-{@code recordType} JSON Schemas (the record ontology) at startup and maps
 * each registered {@link RecordType} to its compiled {@link JsonSchema}.
 *
 * <p>Registering a new {@code recordType} is adding a schema resource under
 * {@code recordmodel/<recordType>.schema.json} (data/config), not validator code — per the
 * design's "register a schema, not code" principle.
 */
@Component
public class RecordModelSchemaRegistry {

    private final ObjectMapper mapper;
    private final Map<RecordType, JsonSchema> schemas = new EnumMap<>(RecordType.class);

    public RecordModelSchemaRegistry(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void load() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        for (RecordType type : RecordType.values()) {
            String path = "recordmodel/" + type.id() + ".schema.json";
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                throw new IllegalStateException("missing record-model schema resource: " + path);
            }
            try (InputStream in = resource.getInputStream()) {
                JsonNode schemaNode = mapper.readTree(in);
                schemas.put(type, factory.getSchema(schemaNode));
            }
        }
    }

    /** @return the compiled schema for a registered record type, if present. */
    public Optional<JsonSchema> schemaFor(RecordType type) {
        return Optional.ofNullable(schemas.get(type));
    }
}
