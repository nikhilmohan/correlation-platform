package com.acp.correlationengine.knowledge;

/**
 * The match-quality + conflict-resolution parameters sourced from the Knowledge Service's frozen
 * {@code GET /domains/{domain}/model-params/{recordId}} endpoint
 * ({@code recordId = core-ip/modelParams/correlation-engine}), read by dotted key from the
 * versioned-record {@code payload.params[]}. NONE are hard-coded (AC21).
 *
 * <p>Session-window duration/type is deliberately NOT here — it is per-pattern from the pattern's
 * {@code sessionWindow} field (via Pattern Manager). No session-window param exists in this record.
 *
 * @param partialMatchTolerance {@code match.partialMatchTolerance} — how many sequence positions
 *     may be missing and still count as a full match (e.g. 1 => N-1 of N). AC10.
 * @param codebookMissingPenalty {@code codebook.missingPenalty} — distance weight for a token in
 *     the scenario but absent from the observation. AC12.
 * @param codebookSpuriousPenalty {@code codebook.spuriousPenalty} — distance weight for a token in
 *     the observation but absent from the scenario. AC12.
 * @param codebookScoreFloor {@code codebook.scoreFloor} — the minimum normalized score a codebook
 *     candidate must clear to be produced. AC12.
 * @param conflictSpecificityWeight {@code conflict.weights.specificity} — conflict-resolution
 *     weight applied to specificity (alarms covered). AC11.
 * @param conflictConfidenceWeight {@code conflict.weights.confidence} — conflict-resolution weight
 *     applied to confidence. AC11.
 */
public record MatchParams(
        int partialMatchTolerance,
        double codebookMissingPenalty,
        double codebookSpuriousPenalty,
        double codebookScoreFloor,
        double conflictSpecificityWeight,
        double conflictConfidenceWeight) {

    public static final String KEY_PARTIAL_MATCH_TOLERANCE = "match.partialMatchTolerance";
    public static final String KEY_CODEBOOK_MISSING_PENALTY = "codebook.missingPenalty";
    public static final String KEY_CODEBOOK_SPURIOUS_PENALTY = "codebook.spuriousPenalty";
    public static final String KEY_CODEBOOK_SCORE_FLOOR = "codebook.scoreFloor";
    public static final String KEY_CONFLICT_SPECIFICITY = "conflict.weights.specificity";
    public static final String KEY_CONFLICT_CONFIDENCE = "conflict.weights.confidence";
}
