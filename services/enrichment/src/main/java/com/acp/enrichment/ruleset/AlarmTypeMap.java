package com.acp.enrichment.ruleset;

import java.util.Map;

/**
 * The canonical alarm-type join-key mapping that drives the REQUIRED {@code AlarmEvent.alarmType}
 * field (design "alarmType population rule").
 *
 * @param rawField the raw payload field carrying the source's alarm-type id (e.g. {@code rawEventType})
 * @param values raw-alarm-type to canonical {@code alarmTypeVocabulary} token map
 * @param fallback the vocab token used when a raw value is unmapped (must itself be a vocab token)
 * @param onUnmapped behaviour for an unmapped raw value: {@code default} (use {@code fallback}) or
 *     {@code dlq} (route the alarm to the input DLQ with reason {@code alarmtype_unmapped})
 */
public record AlarmTypeMap(String rawField, Map<String, String> values, String fallback,
        String onUnmapped) {

    /** {@code onUnmapped} policy: use the configured {@code fallback} vocab token. */
    public static final String ON_UNMAPPED_DEFAULT = "default";
    /** {@code onUnmapped} policy: route the alarm to the input DLQ. */
    public static final String ON_UNMAPPED_DLQ = "dlq";

    public AlarmTypeMap {
        values = values == null ? Map.of() : Map.copyOf(values);
        onUnmapped = onUnmapped == null || onUnmapped.isBlank() ? ON_UNMAPPED_DEFAULT : onUnmapped;
    }

    /** @return {@code true} iff the unmapped policy is {@code dlq}. */
    public boolean dlqOnUnmapped() {
        return ON_UNMAPPED_DLQ.equalsIgnoreCase(onUnmapped);
    }
}
