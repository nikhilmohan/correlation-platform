package com.acp.knowledge.validation;

import com.acp.knowledge.domain.RecordType;
import com.acp.knowledge.schema.RecordModelSchemaRegistry;
import com.acp.knowledge.store.RecordStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Validates a write before persistence:
 * <ol>
 *   <li>JSON-Schema validation against the per-{@code recordType} record-model schema (shape,
 *       enums, token format) — naming each offending field/entry.</li>
 *   <li>Cross-record reference checks against the target domain's current vocabulary records
 *       (edgeType in edge vocab; objectType in object vocab; alarmType in alarm vocab).</li>
 *   <li>Param-bounds checks ({@code min}/{@code max}).</li>
 * </ol>
 *
 * <p>Validation is <b>driven by the referenced records</b>, never by a hard-coded Core IP list,
 * so it holds for any domain (criteria 16/17). On any violation, throws {@link ValidationException}
 * before any transaction is opened (no partial write).
 */
@Service
public class ValidationService {

    private final RecordModelSchemaRegistry schemas;
    private final RecordStore store;

    public ValidationService(RecordModelSchemaRegistry schemas, RecordStore store) {
        this.schemas = schemas;
        this.store = store;
    }

    /**
     * Validate a payload for a write. Throws {@link ValidationException} (→ HTTP 422) on any
     * violation; returns normally if valid.
     */
    public void validate(String domain, RecordType recordType, JsonNode payload) {
        List<Violation> violations = new ArrayList<>();

        // (1) JSON-Schema validation.
        JsonSchema schema = schemas.schemaFor(recordType)
                .orElseThrow(() -> new ValidationException(domain, recordType.id(),
                        List.of(new Violation("recordType", "unknown-record-type",
                                "recordType " + recordType.id() + " has no registered schema"))));
        Set<ValidationMessage> schemaMessages = schema.validate(payload);
        for (ValidationMessage m : schemaMessages) {
            String field = fieldOf(m);
            // Surface the offending value (e.g. a malformed vocabulary token "123Invalid") so the
            // 422 body names the entry, not just the JSON path (AC5/AC6).
            String offending = valueAt(payload, m);
            String message = m.getMessage();
            if (offending != null && !message.contains(offending)) {
                message = message + " — offending entry: " + offending;
            }
            String rule = message.contains("pattern") ? "token-format" : "schema";
            violations.add(new Violation(field, rule,
                    message + " (recordType " + recordType.id() + ")"));
        }

        // If the basic shape is invalid, stop here — cross-record checks assume a well-formed body.
        if (!violations.isEmpty()) {
            throw new ValidationException(domain, recordType.id(), violations);
        }

        // (2) + (3) Cross-record reference checks + param bounds, per recordType.
        switch (recordType) {
            case PROPAGATION_TEMPLATE -> validatePropagationTemplate(domain, payload, violations);
            case FAULT_ORIGIN_TYPE -> validateFaultOriginType(domain, payload, violations);
            case TRAIL_POLICY -> validateTrailPolicy(domain, payload, violations);
            case MODEL_PARAMS -> validateModelParams(payload, violations);
            default -> {
                // vocabulary + attribute-catalogue: token-format/shape already covered by schema.
            }
        }

        if (!violations.isEmpty()) {
            throw new ValidationException(domain, recordType.id(), violations);
        }
    }

    private void validatePropagationTemplate(String domain, JsonNode payload,
            List<Violation> violations) {
        String edgeType = payload.path("edgeType").asText(null);
        if (edgeType != null && !edgeRelations(domain).contains(edgeType)) {
            violations.add(new Violation("edgeType", "edge-type-in-vocabulary",
                    "edge type " + edgeType + " is not in the " + domain
                            + " edge-relation vocabulary"));
        }
        Set<String> objectTypes = objectTypes(domain);
        Set<String> alarmTypes = alarmTypes(domain);
        checkObjectType(payload, "trigger", objectTypes, violations, domain);
        checkObjectType(payload, "effect", objectTypes, violations, domain);
        checkAlarmType(payload, "trigger", alarmTypes, violations, domain);
        checkAlarmType(payload, "effect", alarmTypes, violations, domain);
    }

    private void checkObjectType(JsonNode payload, String side, Set<String> objectTypes,
            List<Violation> violations, String domain) {
        String ot = payload.path(side).path("objectType").asText(null);
        if (ot != null && !objectTypes.contains(ot)) {
            violations.add(new Violation(side + ".objectType", "object-type-in-vocabulary",
                    "object type " + ot + " is not in the " + domain + " object-type vocabulary"));
        }
    }

    private void checkAlarmType(JsonNode payload, String side, Set<String> alarmTypes,
            List<Violation> violations, String domain) {
        String at = payload.path(side).path("alarmType").asText(null);
        if (at != null && !alarmTypes.contains(at)) {
            violations.add(new Violation(side + ".alarmType", "alarm-type-in-vocabulary",
                    "alarm type " + at + " is not in the " + domain + " alarm-type vocabulary"));
        }
    }

    private void validateFaultOriginType(String domain, JsonNode payload,
            List<Violation> violations) {
        String ot = payload.path("objectType").asText(null);
        if (ot != null && !objectTypes(domain).contains(ot)) {
            violations.add(new Violation("objectType", "object-type-in-vocabulary",
                    "object type " + ot + " is not in the " + domain + " object-type vocabulary"));
        }
        String oat = payload.path("originAlarmType").asText(null);
        if (oat != null && !alarmTypes(domain).contains(oat)) {
            violations.add(new Violation("originAlarmType", "alarm-type-in-vocabulary",
                    "alarm type " + oat + " is not in the " + domain + " alarm-type vocabulary"));
        }
    }

    private void validateTrailPolicy(String domain, JsonNode payload, List<Violation> violations) {
        Set<String> relations = edgeRelations(domain);
        JsonNode closure = payload.path("closureEdgeTypes");
        if (closure.isArray()) {
            for (JsonNode e : closure) {
                String et = e.asText();
                if (!relations.contains(et)) {
                    violations.add(new Violation("closureEdgeTypes", "edge-type-in-vocabulary",
                            "edge type " + et + " is not in the " + domain
                                    + " edge-relation vocabulary"));
                }
            }
        }
        String srlgEdge = payload.path("srlgRule").path("srlgEdgeType").asText(null);
        if (srlgEdge != null && !relations.contains(srlgEdge)) {
            violations.add(new Violation("srlgRule.srlgEdgeType", "edge-type-in-vocabulary",
                    "edge type " + srlgEdge + " is not in the " + domain
                            + " edge-relation vocabulary"));
        }
    }

    private void validateModelParams(JsonNode payload, List<Violation> violations) {
        JsonNode params = payload.path("params");
        if (!params.isArray()) {
            return;
        }
        for (JsonNode param : params) {
            String key = param.path("key").asText("<unknown>");
            JsonNode value = param.path("value");
            if (!value.isNumber()) {
                continue; // bounds only apply to numeric values
            }
            double v = value.asDouble();
            if (param.has("min") && v < param.path("min").asDouble()) {
                violations.add(new Violation(key, "param-bounds",
                        "param " + key + " value " + v + " is below min "
                                + param.path("min").asDouble()));
            }
            if (param.has("max") && v > param.path("max").asDouble()) {
                violations.add(new Violation(key, "param-bounds",
                        "param " + key + " value " + v + " is above max "
                                + param.path("max").asDouble()));
            }
        }
    }

    private Set<String> objectTypes(String domain) {
        return tokenSet(domain, RecordType.OBJECT_TYPE_VOCABULARY, "objectTypes");
    }

    private Set<String> edgeRelations(String domain) {
        return tokenSet(domain, RecordType.EDGE_RELATION_VOCABULARY, "relations");
    }

    private Set<String> alarmTypes(String domain) {
        return tokenSet(domain, RecordType.ALARM_TYPE_VOCABULARY, "alarmTypes");
    }

    private Set<String> tokenSet(String domain, RecordType type, String arrayField) {
        Set<String> tokens = new HashSet<>();
        store.listCurrent(domain, type.id()).forEach(record -> {
            JsonNode arr = record.payload().path(arrayField);
            if (arr.isArray()) {
                arr.forEach(n -> tokens.add(n.asText()));
            }
        });
        return tokens;
    }

    /** Resolve the scalar value at a violation's instance location, if it is a simple value. */
    private static String valueAt(JsonNode payload, ValidationMessage m) {
        com.networknt.schema.JsonNodePath location = m.getInstanceLocation();
        if (location == null) {
            return null;
        }
        JsonNode node = payload;
        for (int i = 0; i < location.getNameCount(); i++) {
            Object element = location.getElement(i);
            if (element instanceof Integer idx) {
                if (!node.isArray()) {
                    return null;
                }
                node = node.get(idx);
            } else {
                String name = String.valueOf(element);
                if (node.isArray()) {
                    try {
                        node = node.get(Integer.parseInt(name));
                    } catch (NumberFormatException e) {
                        return null;
                    }
                } else {
                    node = node.get(name);
                }
            }
            if (node == null) {
                return null;
            }
        }
        return node.isValueNode() ? node.asText() : null;
    }

    /** Derive an offending-field name from a networknt message (path includes the instance). */
    private static String fieldOf(ValidationMessage m) {
        String path = m.getInstanceLocation() == null ? "" : m.getInstanceLocation().toString();
        if (path == null || path.isBlank() || path.equals("$")) {
            // Root-level violations (e.g. token-format on an array item) — fall back to the
            // schema location so the offending entry/path is still surfaced.
            return m.getProperty() != null ? m.getProperty() : "payload";
        }
        return path.startsWith("$.") ? path.substring(2) : path;
    }
}
