package com.acp.enrichment.ruleset;

/**
 * A resolved, immutable per-source ruleset: its field mapping (+ alarmTypeMap) and its filter
 * parameters (+ chatter list). The platform's multi-stream "enrichment profile" model — the MVP
 * ships one profile (the {@code core-ip} default) plus illustrative sources, but the registry,
 * selector, and per-source state-keying already support N (design Profile extension note).
 *
 * @param source the source selector key (the envelope {@code source} value), or {@code default}
 * @param isDefault {@code true} for the mandatory built-in fallback ruleset
 * @param fieldMapping the raw-to-canonical field mapping
 * @param filterParams the per-source filter parameters
 */
public record Ruleset(String source, boolean isDefault, FieldMapping fieldMapping,
        FilterParams filterParams) {

    /** The mandatory built-in fallback ruleset name. */
    public static final String DEFAULT_SOURCE = "default";

    /** @return a copy with the filter params replaced (used by the chatter-overlay merge). */
    public Ruleset withFilterParams(FilterParams newParams) {
        return new Ruleset(source, isDefault, fieldMapping, newParams);
    }
}
