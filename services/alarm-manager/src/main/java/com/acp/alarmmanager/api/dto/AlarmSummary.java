package com.acp.alarmmanager.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Per-alarm summary in the {@code GET /alarms} list. {@code alarmType} is the canonical
 * alarm-type join token (distinct from {@code eventType} (X.733 category) and — in the detail view
 * — {@code probableCause}); the web-ui live/incident views and the alarm-to-incident join key off
 * it.
 */
@Schema(description = "Per-alarm summary for the live alarm list view.")
public record AlarmSummary(
        String alarmId,
        String managedObjectId,
        @Schema(description = "X.733 event type / category.") String eventType,
        @Schema(description = "Canonical alarm-type join token, distinct from eventType/probableCause.")
        String alarmType,
        String perceivedSeverity,
        @Schema(description = "ISO-8601 UTC time the alarm was raised.") String raisedAt,
        @Schema(description = "Alarm-Manager lifecycle STATE: open / in-progress / correlated / cleared.")
        String lifecycleState,
        @Schema(description = "Correlation-group role: root-cause / child / none.") String role,
        String incidentId,
        List<String> trailIds) {
}
