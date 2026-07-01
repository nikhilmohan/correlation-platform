package com.acp.patternmanager.enrichment;

import com.acp.patternmanager.derive.DerivedSessionWindow;
import java.util.List;
import java.util.Map;

/**
 * Explainability (XAI) metadata assembled per pattern (design task 6): instance count,
 * support/confidence/lift, timing, codebook overlap ref, reconciliation + structural-validation
 * status, the derived session window, and supporting example instances. Folded into the persisted
 * record and served by the read API.
 *
 * @param instanceCount number of supporting instances (> 0)
 * @param support mined support
 * @param confidence mined confidence
 * @param lift mined lift
 * @param timing descriptive mined timing (unchanged)
 * @param codebookMatchId matched scenario id (null when unexplained)
 * @param reconcileStatus confirmed / merged / unexplained
 * @param structurallyValidated whether the objects form a connected dependency path
 * @param structuralValidationReason non-null exactly when {@code structurallyValidated} is false
 * @param sessionWindow the derived session window
 * @param supportingInstances example occurrences (may be empty)
 */
public record XaiMetadata(
        int instanceCount,
        double support,
        double confidence,
        double lift,
        Map<String, Object> timing,
        String codebookMatchId,
        String reconcileStatus,
        boolean structurallyValidated,
        String structuralValidationReason,
        DerivedSessionWindow sessionWindow,
        List<SupportingInstance> supportingInstances) {
}
