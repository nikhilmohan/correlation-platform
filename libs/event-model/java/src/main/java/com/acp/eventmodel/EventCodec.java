package com.acp.eventmodel;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * (De)serialization helpers — the codec (spec criteria 4, 5, 6).
 *
 * <p>The codec orchestrates the deserialize flow defined in the design:
 *
 * <pre>
 *   parse JSON -&gt; validate envelope shape (required fields, no extras) against
 *   envelope.schema.json -&gt; check schemaVersion (reject major &gt;= 2) -&gt;
 *   resolve payload class by `type` (reject unknown) -&gt; validate the payload
 *   node against its schema (required fields, enum, managedObjectId pattern,
 *   no extras) -&gt; bind to the typed payload POJO.
 * </pre>
 *
 * <p>Validation uses the SAME {@code ../schema} JSON Schema files the Python binding uses
 * (networknt), so required-field / enum / {@code managedObjectId} / {@code additionalProperties}
 * rules behave identically across bindings. On the wire, the canonical format (design "Canonical
 * wire format") is enforced: ISO-8601 UTC timestamps with a {@code Z} suffix (carried verbatim as
 * strings), integer {@code schemaVersion}, lowercase enums, optional fields omitted when absent
 * ({@code @JsonInclude(NON_NULL)}), empty arrays emitted as {@code []}, {@code managedObjectId} as
 * a single string. This is the Java counterpart of the Python {@code codec.py}.
 */
public final class EventCodec {

    private final ObjectMapper mapper;
    private final SchemaStore schemas;

    /** Construct a codec with a canonical-wire-format {@link ObjectMapper}. */
    public EventCodec() {
        this.mapper = new ObjectMapper()
                // Optional fields omitted on output (clearedAt, vendorRaw, codebookMatchId, ...).
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.schemas = new SchemaStore();
    }

    /** @return the underlying {@link ObjectMapper} (canonical wire config). */
    public ObjectMapper objectMapper() {
        return mapper;
    }

    /**
     * Deserialize wire JSON into a {@link TypedEnvelope} with a concrete payload POJO.
     *
     * @param json the wire JSON string
     * @return a typed envelope
     * @throws CodecException malformed JSON, missing/extra envelope fields, or a payload that
     *     fails its schema (required field, enum, {@code managedObjectId} pattern, unknown field)
     * @throws SchemaVersionException {@code schemaVersion} major not supported ({@code >= 2})
     * @throws UnknownEventTypeException {@code type} resolves to no payload class
     */
    public TypedEnvelope<Object> deserialize(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new CodecException("input is not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new CodecException("envelope JSON must be an object");
        }

        // 1. Validate envelope shape (required envelope fields, no extras).
        validateOrThrow(schemas.envelope(), root, "invalid envelope");

        // 2. schemaVersion policy (reject major >= 2).
        SchemaVersionPolicy.check(root.get("schemaVersion").intValue());

        // 3. Resolve payload class by `type` (reject unknown type).
        String type = root.get("type").asText();
        Class<?> payloadClass = TypeRegistry.resolve(type);

        // 4. Validate the payload node against its schema (required, enum, managedObjectId, extras).
        JsonNode payloadNode = root.get("payload");
        validateOrThrow(schemas.payload(type), payloadNode, "invalid " + type + " payload");

        // 5. Bind the validated payload node to the typed POJO.
        Object payload;
        try {
            payload = mapper.treeToValue(payloadNode, payloadClass);
        } catch (JsonProcessingException e) {
            throw new CodecException("failed to bind " + type + " payload: " + e.getOriginalMessage(),
                    e);
        }

        return new TypedEnvelope<>(
                root.get("eventId").asText(),
                type,
                root.get("schemaVersion").intValue(),
                root.get("occurredAt").asText(),
                root.get("source").asText(),
                root.get("traceId").asText(),
                payload);
    }

    /**
     * Serialize a {@link TypedEnvelope} to canonical wire JSON.
     *
     * <p>The payload POJO is serialized by its runtime type so all payload fields are emitted;
     * optional/null fields are omitted ({@code NON_NULL}); the result is validated against the
     * contract schema so a malformed in-memory object cannot produce off-contract wire bytes.
     *
     * @param envelope the typed envelope
     * @return canonical wire JSON
     * @throws CodecException if the resulting JSON does not satisfy the contract schema
     */
    public String serialize(TypedEnvelope<?> envelope) {
        ObjectNode root = mapper.createObjectNode();
        root.put("eventId", envelope.getEventId());
        root.put("type", envelope.getType());
        root.put("schemaVersion", envelope.getSchemaVersion());
        root.put("occurredAt", envelope.getOccurredAt());
        root.put("source", envelope.getSource());
        root.put("traceId", envelope.getTraceId());
        root.set("payload", mapper.valueToTree(envelope.getPayload()));

        // Validate envelope shape + payload shape before emitting (criterion 10: off-contract
        // fields would be caught here against additionalProperties:false).
        validateOrThrow(schemas.envelope(), root, "invalid envelope");
        validateOrThrow(schemas.payload(envelope.getType()), root.get("payload"),
                "invalid " + envelope.getType() + " payload");

        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new CodecException("failed to serialize envelope: " + e.getOriginalMessage(), e);
        }
    }

    private static void validateOrThrow(JsonSchema schema, JsonNode node, String context) {
        Set<ValidationMessage> messages = schema.validate(node);
        if (!messages.isEmpty()) {
            String detail = messages.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new CodecException(context + ": " + detail);
        }
    }
}
