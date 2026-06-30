package com.acp.enrichment.chatter.api;

import com.acp.enrichment.ruleset.ChatterEntry;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The chatter-API entry body (OpenAPI 3.1 {@code ChatterEntry}). The match key the
 * {@code ChatterStep} matches on is {@code (managedObjectId, eventType)} — both REQUIRED.
 * {@code alarmType}/{@code promotedFrom} are OPTIONAL provenance carried from the promoted Noise
 * Filter signature; they never affect the match.
 */
@Schema(name = "ChatterEntry", description = "A known-chatter list entry; match key is "
        + "(managedObjectId, eventType).")
public record ChatterEntryDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "Interface:edge1-12") String managedObjectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                example = "communicationsAlarm") String eventType,
        @Schema(description = "Optional provenance (the promoted NF signature's canonical "
                + "alarm-type)", example = "LinkDown") String alarmType,
        @Schema(description = "Optional free-text provenance note",
                example = "nf-observed-chatter") String promotedFrom) {

    public ChatterEntry toDomain() {
        return new ChatterEntry(managedObjectId, eventType, alarmType, promotedFrom);
    }

    public static ChatterEntryDto fromDomain(ChatterEntry e) {
        return new ChatterEntryDto(e.managedObjectId(), e.eventType(), e.alarmType(),
                e.promotedFrom());
    }
}
