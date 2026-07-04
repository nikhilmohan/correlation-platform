package com.acp.correlationengine.correlate;

import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.model.MatchCandidate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic conflict resolution among candidates claiming overlapping alarm sets (AC11).
 *
 * <p>Order: (1) <b>specificity</b> — the match covering more alarms wins; then (2) <b>confidence</b>
 * — higher wins. No random tie-break. The specificity/confidence weights come from the Knowledge
 * Service ({@code conflict.weights.specificity} / {@code conflict.weights.confidence}); a weighted
 * score keeps the ordering deterministic and Knowledge-driven (no hard-coded values). A final
 * stable tie-break on the candidate's identity guarantees determinism across replays.
 */
public class ConflictResolver {

    /**
     * @return the single winning candidate, or empty if none supplied.
     */
    public Optional<MatchCandidate> resolve(List<MatchCandidate> candidates, MatchParams params) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Comparator<MatchCandidate> byScoreDesc = Comparator
                .comparingDouble((MatchCandidate c) -> weightedScore(c, params))
                .reversed()
                // Explicit specificity-then-confidence tiebreak (mirrors the weighted order and makes
                // the spec's ordering unambiguous even when weights coincide).
                .thenComparing(Comparator.comparingInt(MatchCandidate::specificity).reversed())
                .thenComparing(Comparator.comparingDouble(MatchCandidate::confidence).reversed())
                // Fully deterministic final tie-break so repeated replays always pick the same winner.
                .thenComparing(ConflictResolver::stableId);
        return candidates.stream().min(byScoreDesc);
    }

    private static double weightedScore(MatchCandidate c, MatchParams params) {
        return params.conflictSpecificityWeight() * c.specificity()
                + params.conflictConfidenceWeight() * c.confidence();
    }

    private static String stableId(MatchCandidate c) {
        String attr = c.matchedPatternId() != null ? c.matchedPatternId()
                : (c.matchedCodebookId() != null ? c.matchedCodebookId() : "");
        return c.matchType() + "|" + attr + "|" + c.rootCauseAlarmType();
    }
}
