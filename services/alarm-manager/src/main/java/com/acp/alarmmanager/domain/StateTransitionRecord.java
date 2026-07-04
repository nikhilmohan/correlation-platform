package com.acp.alarmmanager.domain;

import java.time.Instant;

/**
 * An append-only audit row ({@code live_alarm.state_transition}). One row per lifecycle/role
 * change. {@code source} and {@code changedAt} are populated for {@code AlarmStatusChange}-driven
 * transitions, null otherwise.
 */
public record StateTransitionRecord(
        long id,
        String alarmId,
        String toState,
        String reason,
        String source,
        Instant changedAt,
        String causedByEventId,
        Instant occurredAt) {
}
