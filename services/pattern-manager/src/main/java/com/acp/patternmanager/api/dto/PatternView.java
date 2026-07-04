package com.acp.patternmanager.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The canonical item shape (SSoT) served by the read API and frozen in the checked-in
 * {@code openapi.json}. Carries the full XAI set for the web-ui review view, {@code trailId} (the
 * Correlation Engine's per-pattern {@code trailId} source — P3-G1), a {@code rootCauseAlarmType}
 * vocabulary token (P2-GAP-04), and the derived {@code sessionWindow}.
 *
 * @param patternId stable pattern id
 * @param patternName deterministic, readable pattern name owned + persisted by the Pattern Manager
 *     ({@code "<label> Cascade · <short8-of-patternId>"}); consumers render this instead of deriving
 *     a name client-side
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
 * @param instanceCount total number of individual alarm instances across all folded occurrences (sum
 *     of per-occurrence mined support counts); see also {@code occurrenceCount}
 * @param occurrenceCount number of mined occurrences (distinct {@code PatternMinedEvent} eventIds)
 *     folded into this pattern — counts events, not alarms
 * @param trailCount number of DISTINCT trails from which this signature has been observed (the
 *     spatial-spread / cross-trail significance metric)
 * @param firstSeen timestamp of the first occurrence folded (earliest contributing event)
 * @param lastSeen timestamp of the most recent occurrence folded (bumped on each fold)
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
        @Schema(description = "deterministic, readable pattern name owned + persisted by the Pattern "
                + "Manager (\"<label> Cascade · <short8-of-patternId>\", e.g. \"IP Link Down Cascade "
                + "· 02007ff1\"); consumers render this instead of deriving a name client-side")
        String patternName,
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
        @Schema(description = "total number of individual alarm instances across all folded "
                + "occurrences (sum of per-occurrence mined support counts); see also occurrenceCount "
                + "for the number of distinct occurrences folded")
        int instanceCount,
        @Schema(description = "number of mined occurrences (distinct PatternMinedEvent eventIds) "
                + "folded into this pattern; counts events, not alarms")
        int occurrenceCount,
        @Schema(description = "number of DISTINCT trails from which this cascade signature has been "
                + "observed (cross-trail spread / significance)")
        int trailCount,
        @Schema(description = "timestamp of the first occurrence folded (earliest contributing event)")
        OffsetDateTime firstSeen,
        @Schema(description = "timestamp of the most recent occurrence folded (bumped on each fold)")
        OffsetDateTime lastSeen,
        List<SupportingInstanceView> supportingInstances,
        List<SampleAlarmView> sampleAlarms,
        String lifecycle,
        String domain,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
