package com.acp.enrichment.kafka;

import com.acp.eventmodel.CodecException;
import com.acp.eventmodel.SchemaVersionException;
import com.acp.eventmodel.SchemaVersionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Parses + validates an inbound message at the ENVELOPE level only, keeping the {@code payload} as
 * a raw map.
 *
 * <p>Why not {@code EventCodec.deserialize}? The codec validates the {@code payload} against
 * {@code AlarmEvent.schema.json} ({@code additionalProperties:false}), but raw alarms entering on
 * {@code alarms.history}/{@code alarms.live} carry source-specific fields (e.g.
 * {@code rawSeverity}, {@code ne}) that are not canonical — the codec would reject every raw alarm.
 * Enrichment is the source-adaptation boundary: it validates the envelope shape + schemaVersion
 * here, normalizes the raw payload to canonical, then uses {@code EventCodec.serialize} so the
 * OUTPUT is contract-validated (canonical-output invariant). This mirrors the design's "deserialize
 * + validate envelope" / "codec re-validates on serialize" split.
 *
 * <p>Throws {@link CodecException} (malformed JSON, missing required envelope field, wrong type)
 * and {@link SchemaVersionException} (major &ge; 2) — both DLQ signals.
 */
@Component
public class EnvelopeParser {

    private static final String[] REQUIRED = {
            "eventId", "type", "schemaVersion", "occurredAt", "source", "traceId", "payload"};

    private final ObjectMapper mapper;

    public EnvelopeParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param json the wire JSON
     * @return the parsed envelope with a raw payload map
     * @throws CodecException malformed JSON / missing required envelope field / non-AlarmEvent type
     * @throws SchemaVersionException unsupported major schema version (&ge; 2)
     */
    public RawEnvelope parse(String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new CodecException("input is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new CodecException("envelope JSON must be an object");
        }
        for (String field : REQUIRED) {
            if (!root.hasNonNull(field)) {
                throw new CodecException("envelope missing required field: " + field);
            }
        }
        if (!root.get("schemaVersion").isInt() && !root.get("schemaVersion").isLong()) {
            throw new CodecException("envelope schemaVersion must be an integer");
        }
        // schemaVersion policy (reject major >= 2) — a SchemaVersionException (CodecException subtype).
        SchemaVersionPolicy.check(root.get("schemaVersion").intValue());

        String type = root.get("type").asText();
        if (!"AlarmEvent".equals(type)) {
            throw new CodecException("enrichment consumes AlarmEvent only, got type=" + type);
        }
        if (!root.get("payload").isObject()) {
            throw new CodecException("envelope payload must be an object");
        }
        Map<String, Object> payload =
                mapper.convertValue(root.get("payload"), new TypeReference<>() {});
        return new RawEnvelope(
                root.get("eventId").asText(),
                type,
                root.get("schemaVersion").intValue(),
                root.get("occurredAt").asText(),
                root.get("source").asText(),
                root.get("traceId").asText(),
                payload);
    }
}
