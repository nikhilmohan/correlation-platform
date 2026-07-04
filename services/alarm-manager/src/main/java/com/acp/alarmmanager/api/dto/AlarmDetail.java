package com.acp.alarmmanager.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Full single-alarm record on {@code GET /alarms/{alarmId}} — all {@code AlarmEvent} fields
 * (including the canonical {@code alarmType} join token, distinct from {@code eventType} and
 * {@code probableCause}), the lifecycle STATE, correlation {@code role} + {@code incidentId}, and
 * the ordered {@code transitions} history (UTC timestamps).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Full live alarm record with ordered transition history.")
public record AlarmDetail(
        String alarmId,
        String managedObjectId,
        @Schema(description = "X.733 event type / category.") String eventType,
        @Schema(description = "X.733 probable cause.") String probableCause,
        @Schema(description = "Canonical alarm-type join token, distinct from eventType/probableCause.")
        String alarmType,
        String perceivedSeverity,
        String raisedAt,
        String clearedAt,
        @Schema(description = "Wire state: raised / cleared.") String state,
        List<String> trailIds,
        @Schema(description = "Alarm-Manager lifecycle STATE: open / in-progress / correlated / cleared.")
        String lifecycleState,
        @Schema(description = "Correlation-group role: root-cause / child / none.") String role,
        String incidentId,
        List<TransitionDto> transitions) {
}
