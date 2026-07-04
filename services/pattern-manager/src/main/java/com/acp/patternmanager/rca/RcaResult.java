package com.acp.patternmanager.rca;

import java.util.List;

/**
 * RCA output: the designated root-cause alarm type (an {@code alarmType}-vocabulary token — P2-GAP-04),
 * the codebook match id when a scenario overrode the graph ordering (else null), the reconciliation
 * status, and the {@code resolvedObjects} map RCA computed (handed to structural validation for reuse).
 *
 * @param rootCauseAlarmType the designated root-cause alarm-type vocabulary token
 * @param codebookMatchId the matched scenario id (null when no codebook match)
 * @param reconcileStatus {@code confirmed}, {@code merged}, or {@code unexplained}
 * @param resolvedObjects the alarm-type-to-object resolution (reused by structural validation)
 * @param rootCauseObjectId the resolved managedObjectId of the root cause (traversal origin), or null
 */
public record RcaResult(
        String rootCauseAlarmType,
        String codebookMatchId,
        String reconcileStatus,
        List<ResolvedObject> resolvedObjects,
        String rootCauseObjectId) {
}
