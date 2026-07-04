package com.acp.enrichment.ruleset;

import java.util.Objects;

/**
 * A known-chatter list entry. The match key is {@code (managedObjectId, eventType)} — exactly what
 * {@link com.acp.enrichment.pipeline.ChatterStep} matches on. {@code alarmType} and
 * {@code promotedFrom} are OPTIONAL provenance carried verbatim from a promoted Noise Filter
 * signature; they are never part of the match (design "Chatter entry match key").
 *
 * @param managedObjectId REQUIRED match-key field (e.g. {@code Interface:edge1-12})
 * @param eventType REQUIRED match-key field (canonical X.733 category)
 * @param alarmType OPTIONAL provenance (the promoted NF signature's canonical alarm-type)
 * @param promotedFrom OPTIONAL free-text provenance note (e.g. {@code nf-observed-chatter})
 */
public record ChatterEntry(String managedObjectId, String eventType, String alarmType,
        String promotedFrom) {

    /** @return {@code true} iff both required match-key fields are present and non-blank. */
    public boolean hasValidMatchKey() {
        return managedObjectId != null && !managedObjectId.isBlank()
                && eventType != null && !eventType.isBlank();
    }

    /**
     * @param other another entry
     * @return {@code true} iff the {@code (managedObjectId, eventType)} match keys are equal
     *     (provenance fields ignored)
     */
    public boolean matchesKey(ChatterEntry other) {
        return other != null
                && Objects.equals(managedObjectId, other.managedObjectId)
                && Objects.equals(eventType, other.eventType);
    }

    /** @return a key-only copy (provenance stripped), used as a canonical map/set key. */
    public ChatterEntry keyOnly() {
        return new ChatterEntry(managedObjectId, eventType, null, null);
    }
}
