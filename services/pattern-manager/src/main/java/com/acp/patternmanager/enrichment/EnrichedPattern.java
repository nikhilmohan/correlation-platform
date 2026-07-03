package com.acp.patternmanager.enrichment;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import java.util.List;
import java.util.Map;

/**
 * The fully-enriched pattern assembled by {@link PatternEnrichmentService} before persistence — the
 * union of the mined data, RCA output, structural-validation status, session-window derivation, and
 * explainability metadata. Handed to the Pattern Store for a single {@code draft} upsert.
 *
 * @param trailId the mining trail scope (surfaced on PatternView for the CE — P3-G1)
 * @param sequence the ordered alarm-type tokens
 * @param rootCauseAlarmType the RCA-designated root cause (alarmType vocab token — P2-GAP-04)
 * @param support mined support
 * @param confidence mined confidence
 * @param lift mined lift
 * @param timing the descriptive mined timing map (unchanged, opaque)
 * @param sessionWindow the derived session-window rule
 * @param codebookMatchId the matched scenario id (null when unexplained)
 * @param reconcileStatus confirmed / merged / unexplained
 * @param structurallyValidated whether the objects form a connected dependency path
 * @param structuralValidationReason non-null exactly when {@code structurallyValidated} is false
 * @param instanceCount the number of supporting instances (always > 0)
 * @param supportingInstances example occurrences from provenance (may be empty)
 * @param domain the domain (from provenance; null defaults to MVP domain)
 */
public record EnrichedPattern(
        String trailId,
        List<String> sequence,
        String rootCauseAlarmType,
        double support,
        double confidence,
        double lift,
        Map<String, Object> timing,
        DerivedSessionWindow sessionWindow,
        String codebookMatchId,
        String reconcileStatus,
        boolean structurallyValidated,
        String structuralValidationReason,
        int instanceCount,
        List<SupportingInstance> supportingInstances,
        String domain) {
}
