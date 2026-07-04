package com.acp.correlationengine.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The in-flight correlation-instance state structure — uniquely {@code (trailId, patternId)}.
 *
 * <p>Born lazily on the first alarm matching the pattern's opening condition; accumulates alarms
 * and re-evaluates its match incrementally; either fully matches and fires (destroy) or its
 * per-pattern session window expires (destroy + revert). At most one live instance per pair exists
 * at any time. Instance state is physically separate per pair, which is the structural realization
 * of the isolation invariant (AC2/AC8).
 *
 * <p>This is a mutable holder driven by the correlation core; in production it is the value stored
 * in the Kafka Streams {@code instanceStore} (RocksDB + changelog).
 */
public final class CorrelationInstance {

    private final String trailId;
    private final PatternRef patternRef;
    private final List<ObservedAlarm> matchedAlarms = new ArrayList<>();
    private final Set<String> dedupeAlarmIds = new LinkedHashSet<>();
    /** Which sequence positions (indices into patternRef.sequence) have been satisfied. */
    private final Set<Integer> satisfiedIndices = new LinkedHashSet<>();
    private final long createdAtEpochMs;
    private long lastUpdatedEpochMs;
    private long deadlineEpochMs;

    public CorrelationInstance(String trailId, PatternRef patternRef, long createdAtEpochMs) {
        this.trailId = trailId;
        this.patternRef = patternRef;
        this.createdAtEpochMs = createdAtEpochMs;
        this.lastUpdatedEpochMs = createdAtEpochMs;
        this.deadlineEpochMs = createdAtEpochMs + patternRef.windowMs();
    }

    public String trailId() {
        return trailId;
    }

    public String patternId() {
        return patternRef.patternId();
    }

    public PatternRef patternRef() {
        return patternRef;
    }

    public List<ObservedAlarm> matchedAlarms() {
        return List.copyOf(matchedAlarms);
    }

    public long deadlineEpochMs() {
        return deadlineEpochMs;
    }

    public long createdAtEpochMs() {
        return createdAtEpochMs;
    }

    /** @return true if {@code alarmId} was already admitted to this instance (idempotency, AC16). */
    public boolean alreadyAdmitted(String alarmId) {
        return dedupeAlarmIds.contains(alarmId);
    }

    /**
     * Admit an alarm, advancing the sequence match and (for gap-based windows) extending the
     * deadline. Caller must have checked {@link #alreadyAdmitted(String)} and relevance first.
     */
    public void admit(ObservedAlarm alarm, long nowEpochMs) {
        matchedAlarms.add(alarm);
        dedupeAlarmIds.add(alarm.alarmId());
        markSatisfied(alarm.alarmType());
        this.lastUpdatedEpochMs = nowEpochMs;
        if (patternRef.windowType() == WindowType.GAP_BASED) {
            this.deadlineEpochMs = nowEpochMs + patternRef.windowMs();
        }
        // fixed: deadline never moves.
    }

    /** Record every sequence position whose expected alarmType equals {@code alarmType}. */
    private void markSatisfied(String alarmType) {
        List<String> seq = patternRef.sequence();
        for (int i = 0; i < seq.size(); i++) {
            if (!satisfiedIndices.contains(i) && seq.get(i).equals(alarmType)) {
                satisfiedIndices.add(i);
                return; // satisfy one position per alarm (supports repeated types in a sequence)
            }
        }
    }

    /** @return number of distinct sequence positions satisfied so far. */
    public int matchedCount() {
        return satisfiedIndices.size();
    }

    /** @return the pattern sequence length (the full-match target, minus tolerance). */
    public int sequenceLength() {
        return patternRef.sequence().size();
    }

    /**
     * @return true if {@code alarmType} is relevant to this pattern's sequence (a token the
     *     pattern expects) — an alarm not in the sequence is unrelated noise and is not admitted.
     */
    public boolean relevant(String alarmType) {
        return patternRef.sequence().contains(alarmType);
    }
}
