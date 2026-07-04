package com.acp.enrichment.ruleset;

import java.util.List;
import java.util.Map;

/**
 * The per-source field-mapping portion of a {@link Ruleset}: translates a source's raw alarm
 * fields into the canonical {@code AlarmEvent} payload (design Config model).
 *
 * @param defaultObjectType object-type used when the {@code managedObjectIdTemplate} references
 *     {@code {objectType}} and the raw payload omits it
 * @param managedObjectIdTemplate template with {@code {name}} placeholders resolved from the raw
 *     payload (e.g. {@code Interface:{ne}-{ifIndex}})
 * @param severityMap raw severity code to canonical {@code perceivedSeverity} (X.733)
 * @param eventTypeMap raw string to canonical {@code eventType} (empty = identity passthrough)
 * @param probableCauseMap raw string to canonical {@code probableCause} (empty = identity)
 * @param alarmTypeMap the canonical {@code alarmType} mapping (REQUIRED on every emitted alarm)
 * @param vendorRawPassthrough which raw keys (or {@code *} for all) are carried into {@code vendorRaw}
 */
public record FieldMapping(String defaultObjectType, String managedObjectIdTemplate,
        Map<String, String> severityMap, Map<String, String> eventTypeMap,
        Map<String, String> probableCauseMap, AlarmTypeMap alarmTypeMap,
        List<String> vendorRawPassthrough) {

    public FieldMapping {
        severityMap = severityMap == null ? Map.of() : Map.copyOf(severityMap);
        eventTypeMap = eventTypeMap == null ? Map.of() : Map.copyOf(eventTypeMap);
        probableCauseMap = probableCauseMap == null ? Map.of() : Map.copyOf(probableCauseMap);
        vendorRawPassthrough = vendorRawPassthrough == null ? List.of()
                : List.copyOf(vendorRawPassthrough);
    }

    /** @return {@code true} iff {@code vendorRawPassthrough} is the wildcard {@code ["*"]}. */
    public boolean passthroughAll() {
        return vendorRawPassthrough.contains("*");
    }
}
