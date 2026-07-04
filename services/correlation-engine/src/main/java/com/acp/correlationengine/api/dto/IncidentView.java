package com.acp.correlationengine.api.dto;

import com.acp.correlationengine.model.Incident;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Read-API view of an incident (one {@code items[]} element of {@code IncidentPage} and the body of
 * {@code GET /incidents/{incidentId}}). Carries {@code rootCauseAlarmType} (D2 read-model field) so
 * the web-ui / evaluation oracle compute RCA accuracy on the canonical {@code alarmType} token space
 * without re-fetching the alarm. {@code matchedPatternId}/{@code matchedCodebookId} are omitted when
 * null (canonical wire format).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncidentView(
        String incidentId,
        String rootCauseAlarmId,
        String rootCauseAlarmType,
        List<String> childAlarmIds,
        String matchedPatternId,
        String matchedCodebookId,
        double confidence,
        String trailId,
        String createdAt) {

    public static IncidentView from(Incident i) {
        return new IncidentView(
                i.incidentId(),
                i.rootCauseAlarmId(),
                i.rootCauseAlarmType(),
                i.childAlarmIds(),
                i.matchedPatternId(),
                i.matchedCodebookId(),
                i.confidence(),
                i.trailId(),
                i.createdAt().toString());
    }
}
