package com.acp.correlationengine.model;

import java.util.List;
import java.util.Objects;

/**
 * A single match candidate claiming ownership of an alarm set — either a pattern-instance match or
 * a codebook-decode candidate. Both kinds compete in the same {@code ConflictResolver}
 * (specificity then confidence). Carries everything {@code IncidentFactory} needs to form an
 * incident: the winning {@code rootCauseAlarmType} token, the matched alarms (for the
 * {@code alarmType}-join root-cause resolution), the trail, and the pattern/codebook attribution.
 */
public record MatchCandidate(
        MatchType matchType,
        String trailId,
        String rootCauseAlarmType,
        List<ObservedAlarm> matchedAlarms,
        double confidence,
        String matchedPatternId,
        String matchedCodebookId,
        String discoveryTrailId) {

    public MatchCandidate {
        Objects.requireNonNull(matchType, "matchType");
        Objects.requireNonNull(trailId, "trailId");
        Objects.requireNonNull(rootCauseAlarmType, "rootCauseAlarmType");
        matchedAlarms = List.copyOf(matchedAlarms);
    }

    /**
     * Backward-compatible constructor without discovery provenance (codebook decodes have no
     * discovery trail — {@code discoveryTrailId} is null).
     */
    public MatchCandidate(MatchType matchType, String trailId, String rootCauseAlarmType,
            List<ObservedAlarm> matchedAlarms, double confidence, String matchedPatternId,
            String matchedCodebookId) {
        this(matchType, trailId, rootCauseAlarmType, matchedAlarms, confidence, matchedPatternId,
                matchedCodebookId, null);
    }

    /** Specificity = number of alarms covered (more wins in conflict resolution). */
    public int specificity() {
        return matchedAlarms.size();
    }

    public enum MatchType {
        PATTERN,
        CODEBOOK
    }
}
