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
 * @param sampleAlarms bounded sample of real member alarms from the mined event (may be empty)
 * @param domain the domain (from provenance; null defaults to MVP domain)
 * @param snapshotId the topology snapshot the mining ran under (anchor-identity scope)
 * @param codebookVersion the codebook version the mining ran under (anchor-identity scope)
 * @param anchorScenarioId the fault-origin anchor ([ANCHOR-CONSOL]); null/absent => unexplained
 * @param sourceWindowId the mining window (per-event identity component for unexplained patterns)
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
        List<SampleAlarm> sampleAlarms,
        String domain,
        String snapshotId,
        String codebookVersion,
        String anchorScenarioId,
        String sourceWindowId) {

    /** @return true when this pattern is anchored to a fault-origin scenario (consolidation applies). */
    public boolean isAnchored() {
        return anchorScenarioId != null && !anchorScenarioId.isBlank();
    }
}
