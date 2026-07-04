package com.acp.alarmmanager.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One ordered lifecycle/role transition on {@code GET /alarms/{alarmId}}. {@code source} and
 * {@code changedAt} are populated for {@code AlarmStatusChange}-driven transitions, null otherwise.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "One state-transition audit entry (ascending occurredAt).")
public record TransitionDto(
        String toState,
        String reason,
        @Schema(description = "AlarmStatusChange.source (originating service), when applicable.")
        String source,
        @Schema(description = "AlarmStatusChange.changedAt (ISO-8601 UTC), when applicable.")
        String changedAt,
        @Schema(description = "ISO-8601 UTC time the Alarm Manager applied the transition.")
        String occurredAt) {
}
