package com.acp.correlationengine.model;

import java.util.Objects;

/**
 * The minimal, engine-facing view of a live alarm admitted to a correlation instance.
 *
 * <p>Carries the canonical join key {@code alarmType} ({@code AlarmEvent.alarmType}) — the single
 * key used to resolve a match's {@code rootCauseAlarmType} to a concrete {@code rootCauseAlarmId}
 * and to tag children — plus enough to revert on expiry. {@code eventType} / {@code probableCause}
 * are deliberately NOT the join key (they collide across distinct alarm types) — see AC26.
 */
public record ObservedAlarm(
        String alarmId,
        String alarmType,
        long raisedAtEpochMs) {

    public ObservedAlarm {
        Objects.requireNonNull(alarmId, "alarmId");
        Objects.requireNonNull(alarmType, "alarmType");
    }
}
