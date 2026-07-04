package com.acp.correlationengine.codebook;

import com.acp.correlationengine.knowledge.MatchParams;
import com.acp.correlationengine.model.MatchCandidate;
import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.model.TrailScenarioSignature;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closest-match codebook decoding over {@code alarmType} vocabulary tokens (AC9/AC12/AC23).
 *
 * <p>For an uncovered/expired alarm set on a trail, scores the observed {@code alarmType} multiset
 * against each trail-scoped scenario's {@code expectedSymptoms[].alarmType} multiset:
 * <pre>
 *   distance = missingPenalty * count(S \ O) + spuriousPenalty * count(O \ S)
 * </pre>
 * lower is better — tolerating missing alarms and penalizing spurious ones. The best-scoring
 * scenario whose normalized score clears the Knowledge {@code codebook.scoreFloor} becomes a
 * codebook candidate; its {@code rootCauseAlarmType} is carried for {@code alarmType}-join root-cause
 * resolution. Penalties + floor come from Knowledge only.
 */
public class CodebookDecoder {

    /**
     * @return the best codebook candidate for {@code observed} on {@code trailId}, or empty if none
     *     clears the score floor or there are no scenarios / no alarms.
     */
    public Optional<MatchCandidate> decode(String trailId, List<ObservedAlarm> observed,
            List<TrailScenarioSignature> scenarios, MatchParams params) {
        if (observed.isEmpty() || scenarios.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Long> observedCounts = countByType(
                observed.stream().map(ObservedAlarm::alarmType).toList());

        TrailScenarioSignature best = null;
        double bestScore = -1.0;
        for (TrailScenarioSignature scenario : scenarios) {
            List<String> expected = scenario.expectedAlarmTypes();
            if (expected.isEmpty()) {
                continue;
            }
            Map<String, Long> expectedCounts = countByType(expected);
            double distance = params.codebookMissingPenalty() * multisetDiff(expectedCounts, observedCounts)
                    + params.codebookSpuriousPenalty() * multisetDiff(observedCounts, expectedCounts);
            // Normalize to a [0,1] score: 1 at perfect match, decaying with distance relative to the
            // worst case (all expected missing + all observed spurious).
            double worst = params.codebookMissingPenalty() * expected.size()
                    + params.codebookSpuriousPenalty() * observed.size();
            double score = worst <= 0 ? 1.0 : Math.max(0.0, 1.0 - distance / worst);
            if (score > bestScore) {
                bestScore = score;
                best = scenario;
            }
        }

        if (best == null || bestScore < params.codebookScoreFloor()) {
            return Optional.empty();
        }
        return Optional.of(new MatchCandidate(
                MatchCandidate.MatchType.CODEBOOK,
                trailId,
                best.rootCauseAlarmType(),
                observed,
                bestScore,
                null,
                best.codebookId())); // matchedCodebookId = active codebook artifact id (AC15/AC25)
    }

    private static Map<String, Long> countByType(List<String> types) {
        Map<String, Long> counts = new HashMap<>();
        for (String t : types) {
            counts.merge(t, 1L, Long::sum);
        }
        return counts;
    }

    /** Count of tokens in {@code a} not covered by {@code b} (multiset difference cardinality). */
    private static long multisetDiff(Map<String, Long> a, Map<String, Long> b) {
        long diff = 0;
        for (Map.Entry<String, Long> e : a.entrySet()) {
            long other = b.getOrDefault(e.getKey(), 0L);
            if (e.getValue() > other) {
                diff += e.getValue() - other;
            }
        }
        return diff;
    }

    /** Convenience for the caller: no-op container to keep imports tidy in the orchestrator. */
    static List<MatchCandidate> none() {
        return new ArrayList<>();
    }
}
