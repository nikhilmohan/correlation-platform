package com.acp.patternmanager.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The canonical item shape (SSoT) served by the read API and frozen in the checked-in
 * {@code openapi.json}. Carries the full XAI set for the web-ui review view, {@code trailId} (the
 * Correlation Engine's per-pattern {@code trailId} source — P3-G1), a {@code rootCauseAlarmType}
 * vocabulary token (P2-GAP-04), and the derived {@code sessionWindow}.
 *
 * @param patternId stable pattern id
 * @param trailId the trail this pattern was mined from (from {@code PatternMinedEvent.trailId})
 * @param sequence ordered sequence elements ({@code alarmType} + {@code optional})
 * @param rootCauseAlarmType RCA-designated root cause (alarmType vocab token)
 * @param support mined support
 * @param confidence mined confidence
 * @param lift mined lift
 * @param timing the descriptive mined timing (open object)
 * @param sessionWindow the derived session-window rule ({@code {windowMs, type}})
 * @param codebookMatchId matched scenario id (null when unexplained)
 * @param reconcileStatus confirmed / merged / unexplained
 * @param structurallyValidated whether the objects form a connected dependency path
 * @param structuralValidationReason non-null exactly when {@code structurallyValidated} is false
 * @param instanceCount number of supporting instances (> 0)
 * @param supportingInstances example occurrences (may be empty)
 * @param sampleAlarms bounded sample of real member alarms for operator XAI (always present; {@code []}
 *     when none captured — spec-sample-alarms AC-SA-4/5b)
 * @param lifecycle draft / approved / deprecated / rejected
 * @param domain the domain (may be null)
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record PatternView(
        String patternId,
        String trailId,
        List<SequenceElementView> sequence,
        String rootCauseAlarmType,
        double support,
        double confidence,
        double lift,
        JsonNode timing,
        SessionWindowView sessionWindow,
        String codebookMatchId,
        String reconcileStatus,
        boolean structurallyValidated,
        String structuralValidationReason,
        int instanceCount,
        List<SupportingInstanceView> supportingInstances,
        List<SampleAlarmView> sampleAlarms,
        String lifecycle,
        String domain,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
