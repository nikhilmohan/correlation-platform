package com.acp.correlationengine.support;

import com.acp.correlationengine.model.Incident;
import com.acp.correlationengine.model.MatchCandidate;
import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.TrailScenarioSignature;
import com.acp.correlationengine.model.WindowType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small fixture builders shared across engine tests. */
public final class Fixtures {

    public static final long T0 = java.time.Instant.parse("2026-06-11T12:00:00Z").toEpochMilli();

    private Fixtures() {
    }

    public static ObservedAlarm alarm(String id, String alarmType, long raisedAtEpochMs) {
        return new ObservedAlarm(id, alarmType, raisedAtEpochMs);
    }

    public static ObservedAlarm alarm(String id, String alarmType) {
        return alarm(id, alarmType, T0);
    }

    public static PatternRef pattern(String patternId, String trailId, List<String> sequence,
            String rootCauseAlarmType, double confidence, long windowMs, WindowType type) {
        // Default 1:1 alarmType -> objectType witness so a pattern's required object types resolve
        // without a Trail Builder fallback: each alarmType T is witnessed on objectType "T" via a
        // typed managedObjectId prefix. Tests exercising distinct/non-default objectTypes (AC39) use
        // patternWithTypes(...) instead.
        Map<String, String> objectTypes = new LinkedHashMap<>();
        for (String t : sequence) {
            objectTypes.putIfAbsent(t, t);
        }
        objectTypes.putIfAbsent(rootCauseAlarmType, rootCauseAlarmType);
        return new PatternRef(patternId, trailId, sequence, rootCauseAlarmType, confidence,
                windowMs, type, objectTypes);
    }

    /** A pattern with an explicit alarmType -> objectType witness map (AC39 non-default types). */
    public static PatternRef patternWithTypes(String patternId, String trailId, List<String> sequence,
            String rootCauseAlarmType, long windowMs, Map<String, String> alarmTypeToObjectType) {
        return new PatternRef(patternId, trailId, sequence, rootCauseAlarmType, 0.9, windowMs,
                WindowType.GAP_BASED, alarmTypeToObjectType);
    }

    public static PatternRef gapPattern(String patternId, String trailId, List<String> sequence,
            String rootCauseAlarmType, long windowMs) {
        return pattern(patternId, trailId, sequence, rootCauseAlarmType, 0.9, windowMs,
                WindowType.GAP_BASED);
    }

    public static PatternRef fixedPattern(String patternId, String trailId, List<String> sequence,
            String rootCauseAlarmType, long windowMs) {
        return pattern(patternId, trailId, sequence, rootCauseAlarmType, 0.9, windowMs,
                WindowType.FIXED);
    }

    /**
     * A committed pattern-match incident for repository-seeding tests (e.g. simulating prior-run
     * all-time history in the Incident Store). Each incident's fingerprint is derived from its id so
     * distinct ids persist as distinct rows.
     */
    public static Incident incident(String incidentId, String trailId, String rootCauseAlarmId,
            String rootCauseAlarmType, List<String> childAlarmIds) {
        return new Incident(incidentId, trailId, rootCauseAlarmId, rootCauseAlarmType, childAlarmIds,
                "P", null, 0.9, MatchCandidate.MatchType.PATTERN, "fp-" + incidentId,
                Instant.parse("2026-06-11T12:00:00Z"));
    }

    public static TrailScenarioSignature scenario(String codebookId, String trailId,
            String scenarioId, String rootCauseAlarmType, List<String> expectedAlarmTypes) {
        List<TrailScenarioSignature.Symptom> symptoms = expectedAlarmTypes.stream()
                .map(t -> new TrailScenarioSignature.Symptom(t, "router:" + t))
                .toList();
        return new TrailScenarioSignature(codebookId, trailId, scenarioId, rootCauseAlarmType,
                symptoms);
    }
}
