package com.acp.patternmanager.reconcile;

/**
 * The outcome of reconciling a mined sequence against the codebook.
 *
 * @param scenarioId the matched scenario id (becomes {@code codebookMatchId}); null when no match
 * @param rootCauseAlarmType the scenario's designated root-cause alarm type (the RCA-override value);
 *     null when no match
 * @param reconcileStatus {@code confirmed} (scenario match), {@code merged} (complementary appendage
 *     merged), or {@code unexplained} (no scenario — no model explanation)
 */
public record CodebookMatch(String scenarioId, String rootCauseAlarmType, String reconcileStatus) {

    /** @return the "no model explanation" outcome (no codebook match). */
    public static CodebookMatch unexplained() {
        return new CodebookMatch(null, null, "unexplained");
    }

    /** @return whether a scenario matched. */
    public boolean matched() {
        return scenarioId != null;
    }
}
