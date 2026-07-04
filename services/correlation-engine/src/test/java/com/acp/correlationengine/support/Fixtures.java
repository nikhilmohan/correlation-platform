package com.acp.correlationengine.support;

import com.acp.correlationengine.model.ObservedAlarm;
import com.acp.correlationengine.model.PatternRef;
import com.acp.correlationengine.model.TrailScenarioSignature;
import com.acp.correlationengine.model.WindowType;
import java.util.List;

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
        return new PatternRef(patternId, trailId, sequence, rootCauseAlarmType, confidence,
                windowMs, type);
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

    public static TrailScenarioSignature scenario(String codebookId, String trailId,
            String scenarioId, String rootCauseAlarmType, List<String> expectedAlarmTypes) {
        List<TrailScenarioSignature.Symptom> symptoms = expectedAlarmTypes.stream()
                .map(t -> new TrailScenarioSignature.Symptom(t, "router:" + t))
                .toList();
        return new TrailScenarioSignature(codebookId, trailId, scenarioId, rootCauseAlarmType,
                symptoms);
    }
}
