package com.acp.knowledge.domain;

import java.util.Map;
import java.util.Optional;

/**
 * The registered knowledge record types and their URL path segments.
 *
 * <p>{@code recordType} is data-with-a-schema (each maps to a bundled
 * {@code recordmodel/<recordType>.schema.json}). This enum is a convenience index of the eight
 * MVP-registered types and their kebab-case path segments — it is NOT a hard-coded Core IP
 * literal list (it registers TYPES, not Core IP tokens). Registering a new recordType means
 * adding a schema resource + an entry here; no per-Core-IP-token code exists.
 */
public enum RecordType {

    PROPAGATION_TEMPLATE("propagationTemplate", "propagation-templates"),
    FAULT_ORIGIN_TYPE("faultOriginType", "fault-origin-types"),
    TRAIL_POLICY("trailPolicy", "trail-policies"),
    MODEL_PARAMS("modelParams", "model-params"),
    OBJECT_TYPE_VOCABULARY("objectTypeVocabulary", "object-type-vocabulary"),
    EDGE_RELATION_VOCABULARY("edgeRelationVocabulary", "edge-relation-vocabulary"),
    ATTRIBUTE_CATALOGUE("attributeCatalogue", "attribute-catalogue"),
    ALARM_TYPE_VOCABULARY("alarmTypeVocabulary", "alarm-type-vocabulary");

    private final String id;
    private final String pathSegment;

    RecordType(String id, String pathSegment) {
        this.id = id;
        this.pathSegment = pathSegment;
    }

    /** @return the canonical recordType identifier (e.g. {@code propagationTemplate}). */
    public String id() {
        return id;
    }

    /** @return the kebab-case URL path segment (e.g. {@code propagation-templates}). */
    public String pathSegment() {
        return pathSegment;
    }

    private static final Map<String, RecordType> BY_PATH;
    private static final Map<String, RecordType> BY_ID;

    static {
        var byPath = new java.util.HashMap<String, RecordType>();
        var byId = new java.util.HashMap<String, RecordType>();
        for (RecordType t : values()) {
            byPath.put(t.pathSegment, t);
            byId.put(t.id, t);
        }
        BY_PATH = Map.copyOf(byPath);
        BY_ID = Map.copyOf(byId);
    }

    /** Resolve a record type by its URL path segment. */
    public static Optional<RecordType> byPathSegment(String segment) {
        return Optional.ofNullable(BY_PATH.get(segment));
    }

    /** Resolve a record type by its canonical identifier. */
    public static Optional<RecordType> byId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
