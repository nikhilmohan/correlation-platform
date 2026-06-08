package com.acp.eventmodel;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads and caches the bundled JSON Schema 2020-12 files (the SAME {@code ../schema} files the
 * Python binding uses) for runtime validation.
 *
 * <p>The schema is copied into the jar under {@code /schema/...} at build time (see
 * {@code build.gradle} {@code copySchema}), so the library is self-contained: a downstream Spring
 * service validates against the contract schema without any source dependency on this repo's
 * directory layout.
 *
 * <p>Each schema's {@code $id} is {@code https://acp/event-model/<relative path>}. We map that
 * prefix to {@code classpath:schema/} so absolute {@code $id}s and relative {@code $ref}s
 * (payloads &rarr; {@code ../common/managedObjectId.schema.json}) resolve to the bundled files.
 */
final class SchemaStore {

    private static final String SCHEMA_ID_PREFIX = "https://acp/event-model/";
    private static final String CLASSPATH_PREFIX = "classpath:schema/";

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                    builder -> builder.schemaMappers(
                            mappers -> mappers.mapPrefix(SCHEMA_ID_PREFIX, CLASSPATH_PREFIX)));

    private final Map<String, JsonSchema> cache = new HashMap<>();

    /** @return the compiled envelope schema. */
    JsonSchema envelope() {
        return load("envelope.schema.json");
    }

    /**
     * @param payloadType the payload {@code title} / discriminator (e.g. {@code AlarmEvent})
     * @return the compiled schema for {@code payloads/<payloadType>.schema.json}
     */
    JsonSchema payload(String payloadType) {
        return load("payloads/" + payloadType + ".schema.json");
    }

    private JsonSchema load(String relativePath) {
        return cache.computeIfAbsent(relativePath,
                rp -> factory.getSchema(SchemaLocation.of(SCHEMA_ID_PREFIX + rp)));
    }
}
